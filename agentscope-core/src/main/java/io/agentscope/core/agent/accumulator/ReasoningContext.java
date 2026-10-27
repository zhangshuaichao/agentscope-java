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
package io.agentscope.core.agent.accumulator;

import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 管理单轮推理全部状态和内容累积的推理上下文。
 *
 * <p>职责：
 * <ul>
 * <li>累积流式响应中的各种内容类型（文本、思考、工具调用）</li>
 * <li>生成实时流式消息（用于 Hook 通知）</li>
 * <li>构建最终汇总消息（用于保存到记忆）</li>
 * </ul>
 * @hidden
 */
public class ReasoningContext {

    private final String agentName;
    private String messageId;

    private final TextAccumulator textAcc = new TextAccumulator();
    private final ThinkingAccumulator thinkingAcc = new ThinkingAccumulator();
    private final ToolCallsAccumulator toolCallsAcc = new ToolCallsAccumulator();
    private final ServerToolResultAccumulator serverToolResults = new ServerToolResultAccumulator();

    private String finishReason;

    // ChatUsage
    private int inputTokens = 0;
    private int outputTokens = 0;
    private int cachedTokens = 0;
    private int cacheCreationTokens = 0;
    private int reasoningTokens = 0;
    private int toolUsePromptTokens = 0;
    private double time = 0;

    // 需要传播到最终消息的提供方特定响应元数据
    private final Map<String, Object> responseMetadata = new HashMap<>();

    public ReasoningContext(String agentName) {
        this.agentName = agentName;
    }

    /**
     * 处理响应片段并返回可立即发送的消息。
     *
     * <p>策略：
     * <ul>
     * <li>TextBlock/ThinkingBlock：立即发出，供实时展示</li>
     * <li>ToolUseBlock：累积后立即发出，支持实时流式处理</li>
     * </ul>
     *
     * @hidden
     * @param chunk 模型返回的响应片段
     * @return 可立即发送的消息列表
     */
    public List<Msg> processChunk(ChatResponse chunk) {
        this.messageId = chunk.getId();
        if (chunk.getFinishReason() != null) {
            finishReason = chunk.getFinishReason();
        }

        // 累积 ChatUsage
        ChatUsage usage = chunk.getUsage();
        if (usage != null) {
            inputTokens = usage.getInputTokens();
            outputTokens = usage.getOutputTokens();
            cachedTokens = usage.getCachedTokens();
            cacheCreationTokens = usage.getCacheCreationTokens();
            reasoningTokens = usage.getReasoningTokens();
            toolUsePromptTokens = usage.getToolUsePromptTokens();
            time = usage.getTime();
        }

        // 传播提供方特定元数据
        if (chunk.getMetadata() != null && !chunk.getMetadata().isEmpty()) {
            responseMetadata.putAll(chunk.getMetadata());
        }

        List<Msg> streamingMsgs = new ArrayList<>();

        for (ContentBlock block : chunk.getContent()) {
            if (block instanceof TextBlock tb) {
                textAcc.add(tb);

                // 立即发出文本块
                Msg msg = buildChunkMsg(tb);
                streamingMsgs.add(msg);

            } else if (block instanceof ThinkingBlock tb) {
                thinkingAcc.add(tb);

                // 立即发出思考块
                Msg msg = buildChunkMsg(tb);
                streamingMsgs.add(msg);

            } else if (block instanceof ToolUseBlock tub) {
                // 累积工具调用并立即发出，支持实时流式处理
                toolCallsAcc.add(tub);

                // 立即发出 ToolUseBlock 片段，供实时展示
                // 每个工具调用片段分别发出，以支持多个并行
                // 工具调用
                // 对于片段（例如 "__fragment__" 之类的占位名称），需要包含
                // 正确的工具调用 ID，用户才能正确连接片段
                ToolUseBlock outputBlock = enrichToolUseBlockWithId(tub);
                Msg msg = buildChunkMsg(outputBlock);
                streamingMsgs.add(msg);

            } else if (block instanceof ToolResultBlock trb && trb.isServerTool()) {
                // 服务端工具结果随助手响应到达；保留它们，
                // 使其进入最终消息，并能在后续轮次回传。
                serverToolResults.add(trb);

                Msg msg = buildChunkMsg(trb);
                streamingMsgs.add(msg);
            }
        }

        return streamingMsgs;
    }

    /**
     * 构建包含所有内容块的最终推理消息。
     * 将文本、思考和工具调用合并在同一条消息中。
     *
     * <p>确保单轮推理只产生一条消息，该消息可以包含多个内容块。
     *
     * <p>策略：
     * <ol>
     * <li>存在文本内容时添加文本</li>
     * <li>存在思考内容时添加思考</li>
     * <li>添加全部工具调用</li>
     * </ol>
     *
     * @hidden
     * @return 包含所有块的完整推理消息；没有内容时为 null
     */
    public Msg buildFinalMessage() {
        List<ContentBlock> blocks = new ArrayList<>();

        // 存在思考内容时添加
        if (thinkingAcc.hasContent()) {
            blocks.add(thinkingAcc.buildAggregated());
        }

        // 存在文本内容时添加
        if (textAcc.hasContent()) {
            blocks.add(textAcc.buildAggregated());
        }

        // 添加所有工具调用，并将服务端工具结果紧接在对应调用之后
        boolean outputLimitReached = isTruncated();
        List<ToolUseBlock> toolCalls = toolCallsAcc.buildAllToolCalls(outputLimitReached);
        blocks.addAll(serverToolResults.placeAfterToolCalls(toolCalls));

        // 完全没有内容时返回 null
        if (blocks.isEmpty()) {
            return null;
        }

        // 构建元数据：先使用传播的响应元数据，再添加 ChatUsage
        Map<String, Object> metadata = new HashMap<>(responseMetadata);
        if (outputLimitReached) {
            metadata.put(MessageMetadataKeys.OUTPUT_LIMIT_REACHED, true);
        }
        ChatUsage chatUsage = null;
        if (inputTokens > 0 || outputTokens > 0 || time > 0) {
            chatUsage =
                    ChatUsage.builder()
                            .inputTokens(inputTokens)
                            .outputTokens(outputTokens)
                            .cachedTokens(cachedTokens)
                            .cacheCreationTokens(cacheCreationTokens)
                            .reasoningTokens(reasoningTokens)
                            .toolUsePromptTokens(toolUsePromptTokens)
                            .time(time)
                            .build();
            metadata.put(MessageMetadataKeys.CHAT_USAGE, chatUsage);
        }

        return AssistantMessage.builder()
                .id(messageId)
                .name(agentName)
                .content(blocks)
                .metadata(metadata)
                .usage(chatUsage)
                .build();
    }

    private boolean isTruncated() {
        // OpenAI Chat Completions 与 DashScope 报告 "length"；Anthropic 和 Gemini 报告
        // "max_tokens" 或 "MAX_TOKENS"。
        return "length".equalsIgnoreCase(finishReason)
                || "max_tokens".equalsIgnoreCase(finishReason)
                || isOpenAiResponsesOutputLimit();
    }

    private boolean isOpenAiResponsesOutputLimit() {
        if (!"incomplete".equalsIgnoreCase(finishReason)) {
            return false;
        }
        String incompleteReason =
                Objects.toString(responseMetadata.get("openai.response.incomplete_reason"), null);
        return "max_output_tokens".equalsIgnoreCase(incompleteReason);
    }

    /**
     * 根据内容块构建片段消息。
     * @hidden
     */
    private Msg buildChunkMsg(ContentBlock block) {
        return AssistantMessage.builder().id(messageId).name(agentName).content(block).build();
    }

    /**
     * 为 ToolUseBlock 补上正确的工具调用 ID。
     *
     * <p>片段（例如 "__fragment__" 占位名称）的原始块可能没有正确 ID。
     * 此方法从累积器取出 ID 并创建带正确 ID 的新块，使用户能正确连接片段。
     *
     * @param block 原始 ToolUseBlock
     * @return 带正确 ID 的 ToolUseBlock
     */
    private ToolUseBlock enrichToolUseBlockWithId(ToolUseBlock block) {
        // 已有 ID 时原样返回该块
        if (block.getId() != null && !block.getId().isEmpty()) {
            return block;
        }

        // 从累积器获取当前工具调用 ID
        String currentId = toolCallsAcc.getCurrentToolCallId();
        if (currentId == null || currentId.isEmpty()) {
            return block;
        }

        // 创建带正确 ID 的新块
        return ToolUseBlock.builder()
                .id(currentId)
                .name(block.getName())
                .input(block.getInput())
                .content(block.getContent())
                .metadata(block.getMetadata())
                .state(block.getState())
                .build();
    }

    /**
     * 获取已累积的文本内容。
     *
     * @hidden
     * @return 以字符串形式返回累积的文本
     */
    public String getAccumulatedText() {
        return textAcc.getAccumulated();
    }

    /**
     * {@code onModelCall} 中间件转换文本增量事件后，替换已累积文本。
     *
     * @hidden
     * @param text 从转换后事件流重建的文本
     */
    public void replaceAccumulatedText(String text) {
        textAcc.replace(text);
    }

    /**
     * 获取已累积的思考内容。
     *
     * @hidden
     * @return 以字符串形式返回累积的思考内容
     */
    public String getAccumulatedThinking() {
        return thinkingAcc.getAccumulated();
    }

    /**
     * 按 ID 获取已累积工具调用。
     *
     * <p>ID 为 null／空，或找不到对应构建器时，回退到最后一个工具调用。
     *
     * @param id 要查找的工具调用 ID
     * @return 已累积的 ToolUseBlock；未找到时为 null
     */
    public ToolUseBlock getAccumulatedToolCall(String id) {
        return toolCallsAcc.getAccumulatedToolCall(id);
    }

    /**
     * 获取全部已累积工具调用。
     *
     * @return 全部已累积 ToolUseBlock 的列表
     */
    public List<ToolUseBlock> getAllAccumulatedToolCalls() {
        return toolCallsAcc.getAllAccumulatedToolCalls();
    }

    /**
     * 获取已累积的 ChatUsage。
     *
     * @return 包含累积 token 数的 ChatUsage；没有用量数据时为 null
     */
    public ChatUsage getChatUsage() {
        if (inputTokens > 0 || outputTokens > 0 || time > 0) {
            return ChatUsage.builder()
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .cachedTokens(cachedTokens)
                    .cacheCreationTokens(cacheCreationTokens)
                    .reasoningTokens(reasoningTokens)
                    .toolUsePromptTokens(toolUsePromptTokens)
                    .time(time)
                    .build();
        }
        return null;
    }
}
