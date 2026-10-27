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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 在下游推理看到膨胀的消息列表前，将过大的工具结果转存到 {@link AbstractFilesystem} 的中间件。
 *
 * <p>当权威智能体状态或当前推理视图中的 {@link ToolResultBlock} 文本超过
 * {@link ToolResultEvictionConfig#getMaxResultChars()} 时，此中间件会：
 * <ol>
 *   <li>将完整结果写入文件系统的
 *       {@code {evictionPath}/{agentName}/{sanitized-toolCallId}-{contentHash}}。</li>
 *   <li>先压缩 {@link AgentState#contextMutable()}，避免旧版推理前钩子隐藏原始结果，
 *       从而绕过持久化前的清理。</li>
 *   <li>重建当前 {@link ReasoningInput}，保留钩子的删除和改写，
 *       同时替换即将发送给模型的视图中仍可见的结果。</li>
 *   <li>在元数据中标记压缩后的结果，防止后续轮次递归转存。</li>
 * </ol>
 *
 * <p>{@link ToolResultEvictionConfig#getExcludedToolNames()} 列出的工具永不转存
 * （例如 {@code readFile}，否则可能造成反复重读循环）。
 *
 * <p>【学习提示】转存不是丢弃结果，而是用较小引用替代大块上下文。
 * 必须先成功写入文件再替换消息，避免模型既失去原文又无法从文件恢复。
 */
public class ToolResultEvictionMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(ToolResultEvictionMiddleware.class);
    static final String EVICTED_METADATA_KEY = "agentscope.tool_result_evicted";

    private final AbstractFilesystem filesystem;
    private final ToolResultEvictionConfig config;

    public ToolResultEvictionMiddleware(
            AbstractFilesystem filesystem, ToolResultEvictionConfig config) {
        this.filesystem = filesystem;
        this.config = config;
    }

    /** 仅声明所需扩展点；子类若重写更多钩子，必须扩展此集合。 */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_REASONING);
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return next.apply(prepare(agent, ctx, input));
    }

    /** 可复用的最终边界转存步骤。先成功写入，再执行替换。 */
    public ReasoningInput prepare(Agent agent, RuntimeContext ctx, ReasoningInput input) {
        RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        AgentState state = RuntimeContext.resolveAgentState(rc, agent);
        List<Msg> original = state == null ? List.of() : List.copyOf(state.contextMutable());
        Candidate candidate = prepareCandidate(agent, rc, input, original);
        if (state != null
                && state.contextMutable().equals(original)
                && !original.equals(candidate.history())) {
            state.contextMutable().clear();
            state.contextMutable().addAll(candidate.history());
        }
        return candidate.input();
    }

    public record Candidate(ReasoningInput input, List<Msg> history) {
        public Candidate {
            history = List.copyOf(history);
        }
    }

    /** 先转存文件再生成候选结果；不要在此替换活动历史。 */
    public Candidate prepareCandidate(
            Agent agent, RuntimeContext ctx, ReasoningInput input, List<Msg> history) {
        final RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        Map<ToolResultFingerprint, String> replacements = new HashMap<>();

        // PreReasoning 钩子先于中间件运行，可能删除或改写消息。先转存
        // 权威状态，确保钩子行为不能阻止原始结果在持久化前
        // 得到压缩。
        EvictionResult canonical = evictMessages(history, agent.getName(), rc, replacements);

        // 保留钩子生成的模型视图，仅压缩该视图中仍存在或新加入的结果。
        // 复用权威状态的替换结果，避免重复写入。
        EvictionResult result = evictMessages(input.messages(), agent.getName(), rc, replacements);
        if (!result.changed()) {
            return new Candidate(input, canonical.messages());
        }

        return new Candidate(
                new ReasoningInput(result.messages(), input.tools(), input.options()),
                canonical.messages());
    }

    private EvictionResult evictMessages(
            List<Msg> messages,
            String agentName,
            RuntimeContext rc,
            Map<ToolResultFingerprint, String> replacements) {
        if (messages == null || messages.isEmpty()) {
            return new EvictionResult(messages, false);
        }

        List<Msg> rebuiltMessages = new ArrayList<>(messages.size());
        boolean changed = false;
        for (Msg msg : messages) {
            Msg rebuilt = evictMessage(msg, agentName, rc, replacements);
            rebuiltMessages.add(rebuilt);
            if (rebuilt != msg) {
                changed = true;
            }
        }

        return new EvictionResult(changed ? rebuiltMessages : messages, changed);
    }

    private Msg evictMessage(
            Msg msg,
            String agentName,
            RuntimeContext rc,
            Map<ToolResultFingerprint, String> replacements) {
        if (msg == null) {
            return null;
        }
        List<ContentBlock> contentBlocks = msg.getContent();
        if (contentBlocks == null || contentBlocks.isEmpty()) {
            return msg;
        }
        boolean changed = false;
        List<ContentBlock> rebuilt = new ArrayList<>(contentBlocks.size());
        for (ContentBlock block : contentBlocks) {
            if (block instanceof ToolResultBlock tr) {
                if (isEvicted(tr)) {
                    rebuilt.add(block);
                    continue;
                }
                ToolResultFingerprint original = fingerprint(tr);
                String placeholder = replacements.get(original);
                if (placeholder == null) {
                    placeholder = maybeEvict(tr, agentName, rc);
                    if (placeholder != null) {
                        replacements.put(original, placeholder);
                    }
                }
                if (placeholder != null) {
                    changed = true;
                    rebuilt.add(withPlaceholder(tr, placeholder));
                    continue;
                }
            }
            rebuilt.add(block);
        }
        if (!changed) {
            return msg;
        }
        return rebuildMessage(msg, rebuilt);
    }

    private Msg rebuildMessage(Msg msg, List<ContentBlock> content) {
        return Msg.builderForRole(msg.getRole())
                .id(msg.getId())
                .name(msg.getName())
                .content(content)
                .metadata(msg.getMetadata())
                .timestamp(msg.getTimestamp())
                .usage(msg.getUsage())
                .build();
    }

    private String maybeEvict(ToolResultBlock toolResult, String agentName, RuntimeContext rc) {
        String toolName = toolResult.getName();
        if (toolName != null && config.getExcludedToolNames().contains(toolName)) {
            return null;
        }
        String fullText = extractText(toolResult);
        if (fullText.length() <= config.getMaxResultChars()) {
            return null;
        }
        String toolCallId = toolResult.getId();
        try {
            String evictionPath = buildEvictionPath(agentName, toolCallId, fullText);
            WriteResult writeResult = filesystem.write(rc, evictionPath, fullText);
            if (!writeResult.isSuccess()) {
                log.warn(
                        "[{}] Failed to evict tool result [tool={}, id={}]: {}",
                        agentName,
                        toolName,
                        toolCallId,
                        writeResult.error());
                return null;
            }
            String placeholder = buildPlaceholder(fullText, evictionPath);
            log.info(
                    "[{}] Evicted large tool result [tool={}, id={}, chars={} -> {}]",
                    agentName,
                    toolName,
                    toolCallId,
                    fullText.length(),
                    evictionPath);
            return placeholder;
        } catch (Exception e) {
            log.warn(
                    "[{}] Exception evicting tool result [tool={}, id={}]: {}",
                    agentName,
                    toolName,
                    toolCallId,
                    e.getMessage());
            return null;
        }
    }

    private boolean isEvicted(ToolResultBlock toolResult) {
        return Boolean.TRUE.equals(toolResult.getMetadata().get(EVICTED_METADATA_KEY));
    }

    private ToolResultBlock withPlaceholder(ToolResultBlock toolResult, String placeholder) {
        Map<String, Object> metadata = new LinkedHashMap<>(toolResult.getMetadata());
        metadata.put(EVICTED_METADATA_KEY, true);
        return new ToolResultBlock(
                toolResult.getId(),
                toolResult.getName(),
                List.of(TextBlock.builder().text(placeholder).build()),
                metadata,
                toolResult.getState(),
                toolResult.getExecutionDetails());
    }

    private String extractText(ToolResultBlock toolResult) {
        if (toolResult.getOutput() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : toolResult.getOutput()) {
            if (block instanceof TextBlock tb && tb.getText() != null) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }

    private String buildEvictionPath(String agentName, String toolCallId, String fullText) {
        String base = config.getEvictionPath();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String safeAgent = agentName.replaceAll("[^a-zA-Z0-9_-]", "_");
        String safeId = toolCallId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return base + "/" + safeAgent + "/" + safeId + "-" + contentHash(fullText);
    }

    private String contentHash(String content) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String buildPlaceholder(String fullText, String evictionPath) {
        int len = fullText.length();
        int pLen = Math.min(config.getPreviewChars(), len / 2);

        StringBuilder sb = new StringBuilder();
        sb.append(
                String.format(
                        "Tool output was too large (%,d chars) and has been saved to `%s`.%n"
                                + "To read the full output, use `read_file` with path `%s`.%n%n",
                        len, evictionPath, evictionPath));

        if (pLen > 0) {
            sb.append(String.format("Preview (first %,d chars):%n", pLen));
            sb.append(fullText, 0, pLen);
            sb.append(String.format("%n%n... and last %,d chars:%n", pLen));
            sb.append(fullText, len - pLen, len);
        }

        return sb.toString();
    }

    private ToolResultFingerprint fingerprint(ToolResultBlock block) {
        return new ToolResultFingerprint(block.getId(), block.getName(), extractText(block));
    }

    private record ToolResultFingerprint(String id, String name, String text) {

        private ToolResultFingerprint {
            text = Objects.requireNonNullElse(text, "");
        }
    }

    private record EvictionResult(List<Msg> messages, boolean changed) {}
}
