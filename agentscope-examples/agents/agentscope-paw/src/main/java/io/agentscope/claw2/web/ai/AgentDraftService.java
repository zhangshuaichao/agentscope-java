/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.claw2.web.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.claw2.web.catalog.AgentCatalogService.AgentDraft;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 通过 AI 草拟起始智能体配置。输入一句描述，以低温度提示词调用配置的 {@link Model} 一次，再将 JSON 响应解析为 {@link AgentDraft}。
 *
 * <p>【学习提示】“草稿不落盘”不等于没有外部效果：用户描述会发送给配置模型，调用可能产生费用；
 * 模型返回内容只是待审阅建议，后续安装工具／技能前仍需校验其权限与来源。
 *
 * <p>没有可用 {@link Model} bean 时返回 503；{@code GET /api/auth/me} 的 {@code aiAvailable} 标记反映该状态，便于前端隐藏或禁用 AI 标签页。
 */
@Service
public class AgentDraftService {

    private static final Logger log = LoggerFactory.getLogger(AgentDraftService.class);
    private static final String PROMPT_RESOURCE = "classpath:prompts/agent-draft.md";
    private static final String FALLBACK_PROMPT =
            "You are an agent designer. Given a one-sentence description, return strict JSON with"
                    + " keys name, description, sysPrompt, suggestedTools (array of strings),"
                    + " suggestedSkills (array of {name,content}), suggestedSubagents (array of"
                    + " {name,content}). User description: {{DESCRIPTION}}. Output JSON only.";
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);

    private final Model model;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String promptTemplate;

    public AgentDraftService(Optional<Model> modelOpt, ResourceLoader resourceLoader) {
        this.model = modelOpt.orElse(null);
        this.promptTemplate = loadPrompt(resourceLoader);
    }

    private static String loadPrompt(ResourceLoader resourceLoader) {
        try {
            Resource res = resourceLoader.getResource(PROMPT_RESOURCE);
            if (!res.exists()) {
                log.warn(
                        "AgentDraftService: prompt resource not found at {}, using fallback",
                        PROMPT_RESOURCE);
                return FALLBACK_PROMPT;
            }
            try (InputStream in = res.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn(
                    "AgentDraftService: failed to load prompt resource {}: {} (using fallback)",
                    PROMPT_RESOURCE,
                    e.getMessage());
            return FALLBACK_PROMPT;
        }
    }

    /**
     * 根据自由文本描述草拟智能体配置。错误情况：
     *
     * <ul>
     *   <li>未配置模型时返回 503
     *   <li>描述为空白时返回 400
     *   <li>模型返回无效 JSON 或无文本内容时返回 502
     * </ul>
     */
    public Mono<AgentDraft> draft(String description) {
        if (description == null || description.isBlank()) {
            return Mono.error(
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "description is required"));
        }
        if (model == null) {
            return Mono.error(
                    new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "AI drafting not available — configure a model"));
        }

        String prompt = promptTemplate.replace("{{DESCRIPTION}}", description.trim());
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(prompt).build())
                        .build();

        return Mono.fromCallable(() -> callModelBlocking(userMsg))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(this::parseDraft);
    }

    /** 订阅模型流式响应，将发出的文本块拼接为字符串；阻塞调用方，直到流完成或超时。 */
    private String callModelBlocking(Msg userMsg) {
        try {
            List<ChatResponse> responses =
                    model.stream(List.of(userMsg), null, null).collectList().block(CALL_TIMEOUT);
            if (responses == null || responses.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Model returned no response");
            }
            StringBuilder sb = new StringBuilder();
            for (ChatResponse r : responses) {
                if (r == null || r.getContent() == null) continue;
                for (ContentBlock cb : r.getContent()) {
                    if (cb instanceof TextBlock tb) {
                        String txt = tb.getText();
                        if (txt != null) sb.append(txt);
                    }
                }
            }
            String raw = sb.toString();
            if (raw.isBlank()) {
                // 部分提供方只在最后一帧给出最终内容；若全部帧均为增量且最终仍未收集到文本，
                // 返回 502，供 UI 重试。
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Model returned empty content");
            }
            return raw;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Model call failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    e);
        }
    }

    /** 宽松解析 JSON：剥离 ```json 围栏及首尾说明文字，然后反序列化为 {@link AgentDraft}；失败时以原始输出作为错误信息返回 502。 */
    private Mono<AgentDraft> parseDraft(String raw) {
        String stripped = stripCodeFence(raw).trim();
        // 模型包含前后说明文字时，尝试定位最外层 JSON 对象。
        int firstBrace = stripped.indexOf('{');
        int lastBrace = stripped.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            stripped = stripped.substring(firstBrace, lastBrace + 1);
        }
        try {
            AgentDraft draft = mapper.readValue(stripped, AgentDraft.class);
            return Mono.just(draft);
        } catch (Exception e) {
            log.warn("AgentDraftService: failed to parse model output: {}", e.getMessage());
            return Mono.error(
                    new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY,
                            "Model returned non-JSON output: "
                                    + (raw.length() > 500 ? raw.substring(0, 500) + "..." : raw)));
        }
    }

    private static String stripCodeFence(String s) {
        if (s == null) return "";
        String trimmed = s.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            int closing = trimmed.lastIndexOf("```");
            if (closing >= 0) {
                trimmed = trimmed.substring(0, closing);
            }
        }
        return trimmed;
    }
}
