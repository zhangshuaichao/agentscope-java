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
package io.agentscope.extensions.model.anthropic.formatter;

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.RawMessageStreamEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.tool.ToolValidator;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/** 将 Anthropic API 的流式与非流式响应解析为 AgentScope ChatResponse。 */
public class AnthropicResponseParser {

    private static final Logger log = LoggerFactory.getLogger(AnthropicResponseParser.class);
    private static final AnthropicServerToolHelper SERVER_TOOL_HELPER =
            AnthropicServerToolHelper.instance();

    /** 将非流式 Anthropic Message 解析为 ChatResponse。 */
    public static ChatResponse parseMessage(Message message, Instant startTime) {
        List<ContentBlock> contentBlocks = new ArrayList<>();

        // 处理内容块。
        for (var block : message.content()) {
            // 文本块。
            block.text()
                    .ifPresent(
                            textBlock ->
                                    contentBlocks.add(
                                            TextBlock.builder().text(textBlock.text()).build()));

            // 工具调用块。
            block.toolUse()
                    .ifPresent(
                            toolUse -> {
                                if (!ToolValidator.requireNonBlank(
                                        "Anthropic", toolUse.name(), toolUse.id())) {
                                    return;
                                }
                                Map<String, Object> input =
                                        parseJsonInput(toolUse._input(), toolUse.name());
                                contentBlocks.add(
                                        ToolUseBlock.builder()
                                                .id(toolUse.id())
                                                .name(toolUse.name())
                                                .input(input)
                                                .content(
                                                        toolUse._input() != null
                                                                ? toolUse._input().toString()
                                                                : "")
                                                .build());
                            });

            // 思考块，用于扩展思考。
            block.thinking()
                    .ifPresent(
                            thinking ->
                                    contentBlocks.add(
                                            ThinkingBlock.builder()
                                                    .thinking(thinking.thinking())
                                                    .build()));

            // 服务端工具调用块，例如在 Anthropic 侧执行的 web_search。
            block.serverToolUse()
                    .ifPresent(
                            serverToolUse -> {
                                ToolUseBlock decoded = SERVER_TOOL_HELPER.decodeUse(serverToolUse);
                                if (decoded != null) {
                                    contentBlocks.add(decoded);
                                }
                            });

            // 服务端工具结果块，包含 Anthropic 侧执行工具的结果。
            SERVER_TOOL_HELPER
                    .toResultParam(block)
                    .ifPresent(param -> contentBlocks.add(SERVER_TOOL_HELPER.decodeResult(param)));
        }

        // 解析用量。
        long baseInput = message.usage().inputTokens();
        long cacheRead = message.usage().cacheReadInputTokens().orElse(0L);
        long cacheCreate = message.usage().cacheCreationInputTokens().orElse(0L);
        long reasoning =
                message.usage()
                        .outputTokensDetails()
                        .map(details -> details.thinkingTokens())
                        .orElse(0L);
        ChatUsage usage =
                ChatUsage.builder()
                        .inputTokens((int) (baseInput + cacheRead + cacheCreate))
                        .outputTokens((int) message.usage().outputTokens())
                        .cachedTokens((int) cacheRead)
                        .cacheCreationTokens((int) cacheCreate)
                        .reasoningTokens((int) reasoning)
                        .time(Duration.between(startTime, Instant.now()).toMillis() / 1000.0)
                        .build();

        return ChatResponse.builder()
                .id(resolveMessageId(message))
                .content(contentBlocks)
                .usage(usage)
                .build();
    }

    /**
     * 可变容器，保存 message_start 事件中的提示词 token 数，
     * 使 message_delta 发出的最终用量能够包含输入及缓存 token 数。
     */
    private static class StreamUsageState {
        int inputTokens;
        int cachedTokens;
        int cacheCreationTokens;
    }

    /**
     * 将 Anthropic 流式事件解析为 ChatResponse Flux。
     *
     * <p>【学习提示】Flux.defer 为每次订阅创建独立用量状态，避免不同请求或重订阅共享计数。
     * 开始事件记录输入用量，最终增量事件补充输出用量；不能把各事件当成互不相关的完整响应。
     */
    public static Flux<ChatResponse> parseStreamEvents(
            Flux<RawMessageStreamEvent> eventFlux, Instant startTime) {
        return Flux.defer(
                () -> {
                    StreamUsageState usageState = new StreamUsageState();
                    return eventFlux
                            .flatMap(
                                    event -> {
                                        try {
                                            return Flux.just(
                                                    parseStreamEvent(event, startTime, usageState));
                                        } catch (Exception e) {
                                            log.warn(
                                                    "Error parsing stream event: {}",
                                                    e.getMessage());
                                            return Flux.empty();
                                        }
                                    })
                            .filter(
                                    response ->
                                            response != null
                                                    && (!response.getContent().isEmpty()
                                                            || response.getUsage() != null));
                });
    }

    /** 解析单个流事件。 */
    private static ChatResponse parseStreamEvent(
            RawMessageStreamEvent event, Instant startTime, StreamUsageState usageState) {
        List<ContentBlock> contentBlocks = new ArrayList<>();
        ChatUsage usage = null;
        String messageId = null;

        // 消息开始事件：记录提示词用量，包括输入、缓存读取和缓存创建 token，
        // 供 message_delta 发出的最终用量合并使用。
        if (event.isMessageStart()) {
            var startMessage = event.asMessageStart().message();
            messageId = resolveMessageId(startMessage);

            var startUsage = startMessage.usage();
            long cacheReadTokens = startUsage.cacheReadInputTokens().orElse(0L);
            long cacheCreationTokens = startUsage.cacheCreationInputTokens().orElse(0L);
            // Anthropic 报告的 input_tokens 不含缓存 token，因此将其加回，
            // 使 cachedTokens 始终为 inputTokens 的子集，满足 ChatUsage 不变式。
            usageState.inputTokens =
                    (int) (startUsage.inputTokens() + cacheReadTokens + cacheCreationTokens);
            usageState.cachedTokens = (int) cacheReadTokens;
            usageState.cacheCreationTokens = (int) cacheCreationTokens;
        }

        // 内容块增量：文本。
        if (event.isContentBlockDelta()) {
            var deltaEvent = event.asContentBlockDelta();

            deltaEvent
                    .delta()
                    .text()
                    .ifPresent(
                            textDelta ->
                                    contentBlocks.add(
                                            TextBlock.builder().text(textDelta.text()).build()));

            deltaEvent
                    .delta()
                    .thinking()
                    .ifPresent(
                            thinkingDelta ->
                                    contentBlocks.add(
                                            ThinkingBlock.builder()
                                                    .thinking(thinkingDelta.thinking())
                                                    .build()));

            // 输入 JSON 增量，用于工具调用。
            deltaEvent
                    .delta()
                    .inputJson()
                    .ifPresent(
                            jsonDelta -> {
                                // 创建片段 ToolUseBlock，供后续累积。
                                contentBlocks.add(
                                        ToolUseBlock.builder()
                                                .id("") // 空 ID 表示片段。
                                                .name("__fragment__") // 片段标记。
                                                .content(jsonDelta.partialJson())
                                                .input(Map.of())
                                                .build());
                            });
        }

        // 内容块开始：工具调用。
        if (event.isContentBlockStart()) {
            var startEvent = event.asContentBlockStart();

            startEvent
                    .contentBlock()
                    .toolUse()
                    .ifPresent(
                            toolUse -> {
                                if (!ToolValidator.requireNonBlank(
                                        "Anthropic", toolUse.name(), toolUse.id())) {
                                    return;
                                }
                                contentBlocks.add(
                                        ToolUseBlock.builder()
                                                .id(toolUse.id())
                                                .name(toolUse.name())
                                                .input(Map.of())
                                                .content("")
                                                .build());
                            });

            // 服务端工具调用开始，输入由后续 input_json_delta 事件到达，
            // 通过常规片段机制累积。
            startEvent
                    .contentBlock()
                    .serverToolUse()
                    .ifPresent(
                            serverToolUse -> {
                                if (!ToolValidator.requireNonBlank(
                                        "Anthropic",
                                        serverToolUse.name().asString(),
                                        serverToolUse.id())) {
                                    return;
                                }
                                contentBlocks.add(
                                        ToolUseBlock.builder()
                                                .id(serverToolUse.id())
                                                .name(serverToolUse.name().asString())
                                                .input(Map.of())
                                                .content("")
                                                .metadata(
                                                        Map.of(
                                                                ToolUseBlock.METADATA_SERVER_TOOL,
                                                                true))
                                                .state(ToolCallState.FINISHED)
                                                .build());
                            });

            // 服务端工具结果在开始事件中完整到达。
            SERVER_TOOL_HELPER
                    .toResultParam(startEvent.contentBlock())
                    .ifPresent(param -> contentBlocks.add(SERVER_TOOL_HELPER.decodeResult(param)));
        }

        // 消息增量：最终用量信息，将累计输出 token 数与
        // message_start 捕获的提示词用量合并。
        if (event.isMessageDelta()) {
            var deltaUsage = event.asMessageDelta().usage();
            long cacheReadTokens =
                    deltaUsage.cacheReadInputTokens().orElse((long) usageState.cachedTokens);
            long cacheCreationTokens =
                    deltaUsage
                            .cacheCreationInputTokens()
                            .orElse((long) usageState.cacheCreationTokens);
            long inputTokens =
                    deltaUsage.inputTokens().isPresent()
                            ? deltaUsage.inputTokens().get() + cacheReadTokens + cacheCreationTokens
                            : usageState.inputTokens;
            long reasoningTokens =
                    deltaUsage
                            .outputTokensDetails()
                            .map(details -> details.thinkingTokens())
                            .orElse(0L);
            usage =
                    ChatUsage.builder()
                            .inputTokens((int) inputTokens)
                            .cachedTokens((int) cacheReadTokens)
                            .cacheCreationTokens((int) cacheCreationTokens)
                            .outputTokens((int) deltaUsage.outputTokens())
                            .reasoningTokens((int) reasoningTokens)
                            .time(Duration.between(startTime, Instant.now()).toMillis() / 1000.0)
                            .build();
        }

        return ChatResponse.builder().id(messageId).content(contentBlocks).usage(usage).build();
    }

    /**
     * 解析 Anthropic 消息 ID，代理移除该字段时不失败。
     *
     * <p>字段缺失时，SDK 的强类型 {@code id()} 访问器会抛出 {@code AnthropicInvalidDataException}，
     * 因此优先读取原始 {@code _id()} 字段，原始字段不可用时才回退到强类型访问器。
     * 返回 {@code null} 是安全的：{@code
     * ChatResponse.Builder} 会在未设置 ID 时自动生成。
     *
     */
    private static String resolveMessageId(Message message) {
        if (message == null) {
            return null;
        }

        var rawId = message._id();
        if (rawId != null && !rawId.isMissing()) {
            return rawId.asString().orElse(null);
        }

        try {
            return message.id();
        } catch (Exception e) {
            log.debug("Anthropic response has no message id, using a generated one");
            return null;
        }
    }

    /** 将 JsonValue 解析为工具输入 Map。 */
    static Map<String, Object> parseJsonInput(JsonValue jsonValue, String toolName) {
        if (jsonValue == null) {
            return Map.of();
        }

        try {
            String jsonString = ObjectMappers.jsonMapper().writeValueAsString(jsonValue);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = JsonUtils.getJsonCodec().fromJson(jsonString, Map.class);
            return result != null ? result : Map.of();
        } catch (Exception e) {
            log.warn("Failed to parse tool input JSON for tool {}: {}", toolName, e.getMessage());
            return Map.of();
        }
    }
}
