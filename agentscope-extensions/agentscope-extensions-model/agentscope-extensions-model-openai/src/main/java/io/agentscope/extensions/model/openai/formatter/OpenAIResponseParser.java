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
package io.agentscope.extensions.model.openai.formatter;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.tool.ToolValidator;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.dto.OpenAIChoice;
import io.agentscope.extensions.model.openai.dto.OpenAIError;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIReasoningDetail;
import io.agentscope.extensions.model.openai.dto.OpenAIResponse;
import io.agentscope.extensions.model.openai.dto.OpenAIToolCall;
import io.agentscope.extensions.model.openai.dto.OpenAIUsage;
import io.agentscope.extensions.model.openai.exception.OpenAIException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 OpenAI HTTP API 响应解析为 AgentScope ChatResponse。
 * 使用 DTO 处理非流式响应和流式分块响应。
 *
 * <p>【学习提示】流式工具参数通常是尚不完整的 JSON 片段，单块解析不能替代整次调用的累积装配。
 * 思考签名属于重放元数据，不是新的工具调用；应随对应内容保留，不能据此伪造调用。
 */
public class OpenAIResponseParser {

    private static final Logger log = LoggerFactory.getLogger(OpenAIResponseParser.class);

    /** 流式响应中工具调用参数片段的占位名称。 */
    protected static final String FRAGMENT_PLACEHOLDER = "__fragment__";

    /**
     * 从用量中安全获取提示词 token 数，null 或无效时返回 0。
     *
     * @param usage OpenAI 用量对象，可为 null
     * @return 提示词 token 数，或 0
     */
    private long getSafePromptTokens(OpenAIUsage usage) {
        return usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
    }

    /**
     * 从用量中安全获取补全 token 数，null 或无效时返回 0。
     *
     * @param usage OpenAI 用量对象，可为 null
     * @return 补全 token 数，或 0
     */
    private long getSafeCompletionTokens(OpenAIUsage usage) {
        return usage != null && usage.getCompletionTokens() != null
                ? usage.getCompletionTokens()
                : 0;
    }

    /**
     * 从用量中安全获取缓存命中的提示词 token 数，未报告时返回 0。
     *
     * <p>缓存 token 是提示词 token 的子集，来自提示词缓存
     * （{@code prompt_tokens_details.cached_tokens}）。
     *
     * @param usage OpenAI 用量对象，可为 null
     * @return 缓存命中的提示词 token 数，或 0
     */
    private long getSafeCachedTokens(OpenAIUsage usage) {
        if (usage != null && usage.getPromptCacheHitTokens() != null) {
            return usage.getPromptCacheHitTokens();
        }
        if (usage == null || usage.getPromptTokensDetails() == null) {
            return 0;
        }
        Integer cachedTokens = usage.getPromptTokensDetails().getCachedTokens();
        return cachedTokens != null ? cachedTokens : 0;
    }

    /**
     * 从用量中安全获取缓存创建 token 数，未报告时返回 0。
     *
     * <p>缓存创建 token 是提示词 token 的子集（{@code
     * prompt_tokens_details.cache_write_tokens}）。
     *
     * @param usage OpenAI 用量对象，可为 null
     * @return 缓存创建 token 数，或 0
     */
    private long getSafeCacheCreationTokens(OpenAIUsage usage) {
        if (usage == null || usage.getPromptTokensDetails() == null) {
            return 0;
        }
        Integer cacheWriteTokens = usage.getPromptTokensDetails().getCacheWriteTokens();
        return cacheWriteTokens != null ? cacheWriteTokens : 0;
    }

    /**
     * 从用量中安全获取推理 token 数，未报告时返回 0。
     *
     * <p>推理 token 是补全 token 的子集（{@code
     * completion_tokens_details.reasoning_tokens}）。
     *
     * @param usage OpenAI 用量对象，可为 null
     * @return 推理 token 数，或 0
     */
    private long getSafeReasoningTokens(OpenAIUsage usage) {
        if (usage == null || usage.getCompletionTokensDetails() == null) {
            return 0;
        }
        Integer reasoningTokens = usage.getCompletionTokensDetails().getReasoningTokens();
        return reasoningTokens != null ? reasoningTokens : 0;
    }

    /**
     * 将 OpenAI 用量信息解析为 AgentScope ChatUsage。
     *
     * @param usage OpenAI 用量对象，可为 null
     * @param startTime 请求开始时间，用于计算耗时
     * @return AgentScope ChatUsage；没有用量信息时返回 null
     */
    private ChatUsage parseUsage(OpenAIUsage usage, Instant startTime) {
        if (usage == null) {
            return null;
        }

        return ChatUsage.builder()
                .inputTokens((int) getSafePromptTokens(usage))
                .outputTokens((int) getSafeCompletionTokens(usage))
                .cachedTokens((int) getSafeCachedTokens(usage))
                .cacheCreationTokens((int) getSafeCacheCreationTokens(usage))
                .reasoningTokens((int) getSafeReasoningTokens(usage))
                .time(Duration.between(startTime, Instant.now()).toMillis() / 1000.0)
                .build();
    }

    public OpenAIResponseParser() {}

    /**
     * 将 OpenAI 响应 DTO 解析为 AgentScope ChatResponse。
     *
     * @param response OpenAI 响应 DTO
     * @param startTime 请求开始时间，用于计算耗时
     * @return AgentScope ChatResponse
     */
    public ChatResponse parseResponse(OpenAIResponse response, Instant startTime) {
        if (response.isChunk()) {
            return parseChunkResponse(response, startTime);
        } else {
            return parseCompletionResponse(response, startTime);
        }
    }

    /**
     * 解析 OpenAI 非流式响应。
     *
     * @param response OpenAI 响应 DTO
     * @param startTime 请求开始时间
     * @return AgentScope ChatResponse
     */
    protected ChatResponse parseCompletionResponse(OpenAIResponse response, Instant startTime) {
        List<ContentBlock> contentBlocks = new ArrayList<>();
        ChatUsage usage = null;
        String finishReason = null;

        try {
            // 解析用量信息。
            usage = parseUsage(response.getUsage(), startTime);

            // 解析响应内容。
            OpenAIChoice choice = response.getFirstChoice();
            if (choice != null) {
                OpenAIMessage message = choice.getMessage();
                finishReason = choice.getFinishReason();

                if (message != null) {
                    // 顺序很重要，必须按以下次序处理：
                    // 1. 先处理 ThinkingBlock：reasoning_content 和 reasoning_details。
                    // 2. 再处理 TextBlock：content。
                    // 3. 最后处理 ToolUseBlock：tool_calls。

                    // 解析 OpenRouter/Gemini 专用的推理详情。
                    // 从 reasoning_details 收集签名与文本内容。
                    Map<String, String> reasoningSignatures = new HashMap<>();
                    List<OpenAIReasoningDetail> reasoningDetails = message.getReasoningDetails();
                    StringBuilder reasoningTextBuilder = new StringBuilder();

                    if (reasoningDetails != null) {
                        for (OpenAIReasoningDetail detail : reasoningDetails) {
                            if ("reasoning.encrypted".equals(detail.getType())
                                    && detail.getData() != null) {
                                // 仅收集签名，不创建 ToolUseBlock。
                                if (detail.getId() != null) {
                                    reasoningSignatures.put(detail.getId(), detail.getData());
                                }
                            } else if ("reasoning.text".equals(detail.getType())
                                    && detail.getText() != null) {
                                reasoningTextBuilder.append(detail.getText());
                            } else if ("reasoning.summary".equals(detail.getType())
                                    && detail.getText() != null) {
                                // reasoning.summary 同样使用 text 字段。
                                reasoningTextBuilder.append(detail.getText());
                            }
                        }
                    }

                    // 解析推理/思考内容，用于 o1 模型与 OpenRouter。
                    // 将 reasoning_content 与 reasoning_details 中的文本合并。
                    String reasoningContent = message.getReasoningContent();
                    String reasoningText = reasoningTextBuilder.toString();
                    String thinkingContent =
                            (reasoningContent != null && !reasoningContent.isEmpty())
                                    ? reasoningContent
                                    : reasoningText;

                    // 存在思考内容或 reasoning_details 时创建 ThinkingBlock。
                    if (!thinkingContent.isEmpty()
                            || (reasoningDetails != null && !reasoningDetails.isEmpty())) {
                        ThinkingBlock.Builder thinkingBuilder = ThinkingBlock.builder();
                        if (!thinkingContent.isEmpty()) {
                            thinkingBuilder.thinking(thinkingContent);
                        }
                        // 将 reasoning_details 存入元数据，以便之后恢复。
                        if (reasoningDetails != null && !reasoningDetails.isEmpty()) {
                            Map<String, Object> metadata = new HashMap<>();
                            metadata.put(
                                    ThinkingBlock.METADATA_REASONING_DETAILS, reasoningDetails);
                            thinkingBuilder.metadata(metadata);
                        }
                        contentBlocks.add(thinkingBuilder.build());
                    }

                    // 解析文本内容。
                    String textContent = message.getContentAsString();
                    if (textContent != null && !textContent.isEmpty()) {
                        contentBlocks.add(TextBlock.builder().text(textContent).build());
                    }

                    // 解析工具调用。
                    List<OpenAIToolCall> toolCalls = message.getToolCalls();
                    if (toolCalls != null && !toolCalls.isEmpty()) {
                        log.debug(
                                "Tool calls detected in non-stream response: {}", toolCalls.size());

                        for (OpenAIToolCall toolCall : toolCalls) {
                            if (toolCall.getFunction() != null) {
                                try {
                                    String arguments = toolCall.getFunction().getArguments();
                                    String name = toolCall.getFunction().getName();
                                    String toolCallId = toolCall.getId();
                                    String thoughtSignature = toolCall.getThoughtSignature();

                                    // 没有签名时尝试从推理详情中寻找。
                                    if (thoughtSignature == null && toolCallId != null) {
                                        thoughtSignature = reasoningSignatures.get(toolCallId);
                                    }

                                    if (!ToolValidator.requireNonBlank(
                                            "OpenAI", name, toolCallId)) {
                                        continue;
                                    }
                                    if (toolCallId == null) {
                                        toolCallId = "tool_call_" + System.currentTimeMillis();
                                        log.debug(
                                                "Tool call has null id, generated: {}", toolCallId);
                                    }
                                    if (arguments == null) {
                                        arguments = "";
                                    }

                                    log.debug(
                                            "Non-stream tool call: id={}, name={}, arguments={},"
                                                    + " signature={}",
                                            toolCallId,
                                            name,
                                            arguments,
                                            thoughtSignature != null ? "present" : "null");

                                    Map<String, Object> argsMap = new HashMap<>();
                                    if (!arguments.isEmpty()) {
                                        try {
                                            @SuppressWarnings("unchecked")
                                            Map<String, Object> parsed =
                                                    JsonUtils.getJsonCodec()
                                                            .fromJson(arguments, Map.class);
                                            if (parsed != null) {
                                                argsMap.putAll(parsed);
                                            }
                                        } catch (Exception parseEx) {
                                            log.warn(
                                                    "Failed to parse tool call arguments as JSON;"
                                                            + " preserving raw arguments: id={},"
                                                            + " name={}, error={}",
                                                    toolCallId,
                                                    name,
                                                    parseEx.getMessage());
                                        }
                                    }

                                    Map<String, Object> metadata = new HashMap<>();
                                    if (thoughtSignature != null) {
                                        metadata.put(
                                                ToolUseBlock.METADATA_THOUGHT_SIGNATURE,
                                                thoughtSignature);
                                    }

                                    contentBlocks.add(
                                            ToolUseBlock.builder()
                                                    .id(toolCallId)
                                                    .name(name)
                                                    .input(argsMap)
                                                    .content(arguments)
                                                    .metadata(metadata)
                                                    .build());

                                    log.debug(
                                            "Parsed tool call: id={}, name={}",
                                            toolCall.getId(),
                                            name);
                                } catch (Exception ex) {
                                    if (ex instanceof JsonException) {
                                        log.warn(
                                                "Failed to parse tool call arguments due to JSON"
                                                        + " error: {}",
                                                ex.getMessage());
                                    } else {
                                        log.warn(
                                                "Failed to parse tool call arguments: {}",
                                                ex.getMessage(),
                                                ex);
                                    }
                                }
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("Failed to parse OpenAI completion response: {}", e.getMessage(), e);
            // 返回带错误消息的回退响应。
            contentBlocks.add(
                    TextBlock.builder().text("Error parsing response: " + e.getMessage()).build());
        }

        return ChatResponse.builder()
                .id(response.getId())
                .content(contentBlocks)
                .usage(usage)
                .finishReason(finishReason)
                .build();
    }

    /**
     * 解析 OpenAI 流式响应分块。
     *
     * @param response OpenAI 分块响应 DTO
     * @param startTime 请求开始时间
     * @return AgentScope ChatResponse；格式错误的分块返回 null
     */
    protected ChatResponse parseChunkResponse(OpenAIResponse response, Instant startTime) {
        // 检查流式响应块中的错误。
        if (response.isError()) {
            OpenAIError error = response.getError();
            String errorMessage =
                    error != null && error.getMessage() != null
                            ? error.getMessage()
                            : "Unknown error in streaming response";
            String errorCode = error != null && error.getCode() != null ? error.getCode() : null;
            throw new OpenAIException(
                    "OpenAI API error in streaming response: " + errorMessage,
                    400,
                    errorCode,
                    null);
        }

        List<ContentBlock> contentBlocks = new ArrayList<>();
        ChatUsage usage = null;
        String finishReason = null;

        try {
            // 解析用量信息，通常仅出现在最后一块。
            usage = parseUsage(response.getUsage(), startTime);

            // 解析分块内容。
            OpenAIChoice choice = response.getFirstChoice();
            if (choice != null) {
                OpenAIMessage delta = choice.getDelta();
                finishReason = choice.getFinishReason();

                if (delta != null) {
                    // 顺序很重要，必须按以下次序处理：
                    // 1. 先处理 ThinkingBlock：reasoning_content 和 reasoning_details。
                    // 2. 再处理 TextBlock：content。
                    // 3. 最后处理 ToolUseBlock：tool_calls。

                    // 解析 OpenRouter/Gemini 专用的推理详情。
                    // 收集工具调用签名和用于展示的文本内容。
                    Map<String, String> reasoningSignatures = new HashMap<>();
                    Map<Integer, String> reasoningSignaturesByIndex = new HashMap<>();
                    List<OpenAIReasoningDetail> reasoningDetailsList = new ArrayList<>();
                    StringBuilder reasoningTextBuilder = new StringBuilder();

                    List<OpenAIReasoningDetail> reasoningDetails = delta.getReasoningDetails();
                    if (reasoningDetails != null) {
                        reasoningDetailsList.addAll(reasoningDetails);

                        for (OpenAIReasoningDetail detail : reasoningDetails) {
                            // 处理加密推理，即思考签名。
                            if ("reasoning.encrypted".equals(detail.getType())
                                    && detail.getData() != null) {
                                String signature = detail.getData();
                                // 收集签名，稍后关联到工具调用。
                                if (detail.getId() != null) {
                                    reasoningSignatures.put(detail.getId(), signature);
                                }
                                if (detail.getIndex() != null) {
                                    reasoningSignaturesByIndex.put(detail.getIndex(), signature);
                                }
                                // 此处不创建 ToolUseBlock，签名将关联到
                                // 下面实际存在的 tool_calls。
                            } else if ("reasoning.text".equals(detail.getType())
                                    && detail.getText() != null) {
                                reasoningTextBuilder.append(detail.getText());
                                log.debug("Received reasoning text: {}", detail.getText());
                            } else if ("reasoning.summary".equals(detail.getType())
                                    && detail.getText() != null) {
                                // reasoning.summary 同样使用 text 字段。
                                reasoningTextBuilder.append(detail.getText());
                                log.debug("Received reasoning summary: {}", detail.getText());
                            }
                        }
                    }

                    // 解析推理/思考内容，用于 o1 模型与 OpenRouter。
                    String reasoningContent = delta.getReasoningContent();
                    String reasoningText = reasoningTextBuilder.toString();

                    // 为推理内容创建 ThinkingBlock。
                    // 优先级：reasoningContent 高于详情中的 reasoning.text/summary。
                    if ((reasoningContent != null && !reasoningContent.isEmpty())
                            || !reasoningText.isEmpty()
                            || !reasoningDetailsList.isEmpty()) {
                        String thinkingContent =
                                (reasoningContent != null && !reasoningContent.isEmpty())
                                        ? reasoningContent
                                        : reasoningText;

                        if (!thinkingContent.isEmpty() || !reasoningDetailsList.isEmpty()) {
                            ThinkingBlock.Builder thinkingBuilder = ThinkingBlock.builder();
                            if (!thinkingContent.isEmpty()) {
                                thinkingBuilder.thinking(thinkingContent);
                            }
                            // 将 reasoning_details 存入元数据，以便之后恢复。
                            if (!reasoningDetailsList.isEmpty()) {
                                Map<String, Object> metadata = new HashMap<>();
                                metadata.put(
                                        ThinkingBlock.METADATA_REASONING_DETAILS,
                                        reasoningDetailsList);
                                thinkingBuilder.metadata(metadata);
                            }
                            contentBlocks.add(thinkingBuilder.build());
                        }
                    }

                    // 解析文本内容。
                    String textContent = delta.getContentAsString();
                    if (textContent != null && !textContent.isEmpty()) {
                        contentBlocks.add(TextBlock.builder().text(textContent).build());
                    }

                    // 解析工具调用，流式场景下增量到达。
                    List<OpenAIToolCall> toolCalls = delta.getToolCalls();
                    if (toolCalls != null && !toolCalls.isEmpty()) {
                        log.debug("Streaming tool calls detected: {}", toolCalls.size());

                        for (OpenAIToolCall toolCall : toolCalls) {
                            if (toolCall.getFunction() != null) {
                                try {
                                    String toolCallId = toolCall.getId();
                                    Integer toolIndex = toolCall.getIndex();
                                    String toolName = toolCall.getFunction().getName();
                                    String arguments = toolCall.getFunction().getArguments();
                                    String thoughtSignature = toolCall.getThoughtSignature();

                                    // 没有签名时尝试从推理详情中寻找。
                                    if (thoughtSignature == null) {
                                        if (toolCallId != null) {
                                            thoughtSignature = reasoningSignatures.get(toolCallId);
                                        }
                                        if (thoughtSignature == null && toolIndex != null) {
                                            thoughtSignature =
                                                    reasoningSignaturesByIndex.get(toolIndex);
                                        }
                                    }

                                    if (toolCallId == null) {
                                        toolCallId = "streaming_" + System.currentTimeMillis();
                                    }
                                    if (arguments == null) {
                                        arguments = "";
                                    }

                                    log.debug(
                                            "Streaming tool call chunk: id={}, name={},"
                                                    + " arguments={}, signature={}",
                                            toolCallId,
                                            toolName,
                                            arguments,
                                            thoughtSignature != null ? "present" : "null");

                                    // 流式场景收到的是部分工具调用，需要
                                    // 累积装配。
                                    if (toolName != null
                                            && ToolValidator.requireNonBlank(
                                                    "OpenAI", toolName, toolCallId)) {
                                        // 首个带完整元数据的分块，包含工具名称。
                                        Map<String, Object> argsMap = new HashMap<>();

                                        // 仅当参数看起来完整时才尝试解析。
                                        if (!arguments.isEmpty()
                                                && arguments.trim().startsWith("{")
                                                && arguments.trim().endsWith("}")) {
                                            try {
                                                @SuppressWarnings("unchecked")
                                                Map<String, Object> parsed =
                                                        JsonUtils.getJsonCodec()
                                                                .fromJson(arguments, Map.class);
                                                if (parsed != null) {
                                                    argsMap.putAll(parsed);
                                                }
                                            } catch (Exception parseEx) {
                                                log.debug(
                                                        "Partial arguments in streaming (expected):"
                                                                + " {}",
                                                        arguments.length() > 50
                                                                ? arguments.substring(0, 50) + "..."
                                                                : arguments);
                                            }
                                        }

                                        Map<String, Object> metadata = new HashMap<>();
                                        if (thoughtSignature != null) {
                                            metadata.put(
                                                    ToolUseBlock.METADATA_THOUGHT_SIGNATURE,
                                                    thoughtSignature);
                                        }

                                        contentBlocks.add(
                                                ToolUseBlock.builder()
                                                        .id(toolCallId)
                                                        .name(toolName)
                                                        .input(argsMap)
                                                        .content(arguments)
                                                        .metadata(metadata)
                                                        .build());
                                        log.debug(
                                                "Added streaming tool call chunk: id={}, name={}",
                                                toolCallId,
                                                toolName);
                                    } else if (!arguments.isEmpty() || thoughtSignature != null) {
                                        // 后续分块可能只有参数片段，或仅包含
                                        // 签名。
                                        Map<String, Object> metadata = new HashMap<>();
                                        if (thoughtSignature != null) {
                                            metadata.put(
                                                    ToolUseBlock.METADATA_THOUGHT_SIGNATURE,
                                                    thoughtSignature);
                                        }

                                        contentBlocks.add(
                                                ToolUseBlock.builder()
                                                        .id("")
                                                        .name(FRAGMENT_PLACEHOLDER)
                                                        .input(new HashMap<>())
                                                        .content(arguments)
                                                        .metadata(metadata)
                                                        .build());
                                        if (!arguments.isEmpty()) {
                                            log.debug(
                                                    "Added argument fragment: {}",
                                                    arguments.substring(
                                                            0, Math.min(30, arguments.length())));
                                        }
                                        if (thoughtSignature != null) {
                                            log.debug("Added thought signature fragment");
                                        }
                                    }
                                } catch (Exception ex) {
                                    log.warn(
                                            "Failed to parse streaming tool call: {}",
                                            ex.getMessage());
                                }
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("Failed to parse OpenAI chunk response: {}", e.getMessage(), e);
            // 流式场景返回 null，以跳过格式错误的分块。
            return null;
        }

        return ChatResponse.builder()
                .id(response.getId())
                .content(contentBlocks)
                .usage(usage)
                .finishReason(finishReason)
                .build();
    }
}
