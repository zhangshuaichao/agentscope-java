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
package io.agentscope.extensions.model.dashscope.formatter;

import io.agentscope.core.formatter.FormatterException;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.tool.ToolValidator;
import io.agentscope.extensions.model.dashscope.dto.DashScopeChoice;
import io.agentscope.extensions.model.dashscope.dto.DashScopeFunction;
import io.agentscope.extensions.model.dashscope.dto.DashScopeMessage;
import io.agentscope.extensions.model.dashscope.dto.DashScopeOutput;
import io.agentscope.extensions.model.dashscope.dto.DashScopeResponse;
import io.agentscope.extensions.model.dashscope.dto.DashScopeToolCall;
import io.agentscope.extensions.model.dashscope.dto.DashScopeUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 DashScope API 响应解析为 AgentScope ChatResponse。
 */
public class DashScopeResponseParser {

    private static final Logger log = LoggerFactory.getLogger(DashScopeResponseParser.class);

    /**
     * 流式响应中工具调用参数片段使用的占位名称。
     */
    protected static final String FRAGMENT_PLACEHOLDER = "__fragment__";

    public DashScopeResponseParser() {}

    /**
     * 将 DashScopeResponse 解析为 AgentScope ChatResponse。
     *
     * @param response DashScope 响应 DTO
     * @param startTime 请求开始时间，用于计算耗时
     * @return AgentScope ChatResponse
     */
    public ChatResponse parseResponse(DashScopeResponse response, Instant startTime) {
        try {
            List<ContentBlock> blocks = new ArrayList<>();
            String finishReason = null;

            DashScopeOutput output = response.getOutput();
            if (output != null) {
                DashScopeChoice choice = output.getFirstChoice();
                if (choice != null) {
                    DashScopeMessage message = choice.getMessage();
                    if (message != null) {
                        // 顺序很重要，按以下顺序处理：
                        // 1. 首先 ThinkingBlock（reasoning_content）。
                        // 2. 然后 TextBlock（content）。
                        // 3. 最后 ToolUseBlock（tool_calls）。

                        String reasoningContent = message.getReasoningContent();
                        if (reasoningContent != null && !reasoningContent.isEmpty()) {
                            blocks.add(ThinkingBlock.builder().thinking(reasoningContent).build());
                        }

                        String content = message.getContentAsString();
                        if (content != null && !content.isEmpty()) {
                            blocks.add(TextBlock.builder().text(content).build());
                        }

                        // 处理工具调用。
                        addToolCallsFromMessage(message, blocks);
                    }
                    finishReason = choice.getFinishReason();
                }

                // 回退到 output 层结束原因。
                if (finishReason == null) {
                    finishReason = output.getFinishReason();
                }
            }

            ChatUsage usage = null;
            DashScopeUsage u = response.getUsage();
            if (u != null) {
                DashScopeUsage.OutputTokensDetails outputTokensDetails = u.getOutputTokensDetails();
                DashScopeUsage.PromptTokensDetails promptTokensDetails = u.getPromptTokensDetails();
                usage =
                        ChatUsage.builder()
                                .inputTokens(u.getInputTokens() != null ? u.getInputTokens() : 0)
                                .outputTokens(u.getOutputTokens() != null ? u.getOutputTokens() : 0)
                                .cachedTokens(
                                        promptTokensDetails != null
                                                        && promptTokensDetails.getCachedTokens()
                                                                != null
                                                ? promptTokensDetails.getCachedTokens()
                                                : 0)
                                .cacheCreationTokens(
                                        promptTokensDetails != null
                                                        && promptTokensDetails
                                                                        .getCacheCreationInputTokens()
                                                                != null
                                                ? promptTokensDetails.getCacheCreationInputTokens()
                                                : 0)
                                .reasoningTokens(
                                        outputTokensDetails != null
                                                        && outputTokensDetails.getReasoningTokens()
                                                                != null
                                                ? outputTokensDetails.getReasoningTokens()
                                                : 0)
                                .time(
                                        Duration.between(startTime, Instant.now()).toMillis()
                                                / 1000.0)
                                .build();
            }

            return ChatResponse.builder()
                    .id(response.getRequestId())
                    .content(blocks)
                    .usage(usage)
                    .finishReason(finishReason)
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse DashScope response: {}", e.getMessage(), e);
            throw new FormatterException(
                    "Failed to parse DashScope response: " + e.getMessage(), e);
        }
    }

    /**
     * 从 DashScopeMessage 解析工具调用并加入内容块。
     *
     * <p>【学习提示】流式工具调用先建立身份，后续参数增量再由上层累加。为续传片段生成新 ID 会破坏关联；这里保留空 ID，让累加器识别其为续传。不要在收到半段参数时就执行工具或要求它已是完整 JSON。
     *
     * @param message DashScopeMessage
     * @param blocks 用于追加工具使用块的内容块列表
     */
    protected void addToolCallsFromMessage(DashScopeMessage message, List<ContentBlock> blocks) {
        List<DashScopeToolCall> toolCalls = message.getToolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }

        int idx = 0;
        for (DashScopeToolCall toolCall : toolCalls) {
            if (toolCall == null) continue;

            String id = toolCall.getId();
            DashScopeFunction function = toolCall.getFunction();
            if (function == null) continue;

            String name = function.getName();
            String argsJson = function.getArguments();

            // DashScope 流式工具调用：
            // 首个分片包含 name、id 及部分参数；
            // 后续分片仅包含参数片段，没有 name/id。
            if (name != null && ToolValidator.requireNonBlank("DashScope", name, id)) {
                // 首个分片具有完整元数据。
                String callId =
                        id != null ? id : ("tool_call_" + System.currentTimeMillis() + "_" + idx);
                blocks.add(
                        ToolUseBlock.builder()
                                .id(callId)
                                .name(name)
                                .input(Map.of())
                                .content(argsJson)
                                .build());
            } else if (argsJson != null) {
                // 后续分片仅含参数片段。
                // 片段不能拥有独立 ID：当 ID 为空时，累加器
                // 将续传分片合并到最后一个具名工具调用
                // （与 OpenAI、Anthropic 解析器约定一致）。若为每片生成
                // 新 ID，会把片段孤立成独立的无名工具调用。
                String callId = id != null ? id : "";
                blocks.add(
                        ToolUseBlock.builder()
                                .id(callId)
                                .name(FRAGMENT_PLACEHOLDER)
                                .input(Map.of())
                                .content(argsJson)
                                .build());
            }
            idx++;
        }
    }
}
