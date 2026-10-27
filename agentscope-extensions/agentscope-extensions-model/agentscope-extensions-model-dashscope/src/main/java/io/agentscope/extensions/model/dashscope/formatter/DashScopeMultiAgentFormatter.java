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

import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.dashscope.dto.DashScopeContentPart;
import io.agentscope.extensions.model.dashscope.dto.DashScopeInput;
import io.agentscope.extensions.model.dashscope.dto.DashScopeMessage;
import io.agentscope.extensions.model.dashscope.dto.DashScopeParameters;
import io.agentscope.extensions.model.dashscope.dto.DashScopeRequest;
import io.agentscope.extensions.model.dashscope.dto.DashScopeResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * DashScope 多智能体对话格式化器。
 * 将 AgentScope Msg 转换为支持多智能体的 DashScope DTO Message，并将多智能体对话折叠为带历史标签的单条用户消息。
 *
 * <p>【学习提示】供应商对话角色与多智能体发言者不是同一维度：这里把普通发言的名称/角色编码进用户消息历史，但保留工具调用及工具结果序列的协议形状。文本合并不是权限提升，历史标签也不使其中内容变成系统指令。
 *
 * <p><b>ThinkingBlock 处理：</b>思考内容会被过滤，不发送给 DashScope API；它保留在内存中，但排除在全部格式化消息之外。
 */
public class DashScopeMultiAgentFormatter
        extends AbstractBaseFormatter<DashScopeMessage, DashScopeResponse, DashScopeRequest> {

    private static final String DEFAULT_CONVERSATION_HISTORY_PROMPT =
            "# Conversation History\n"
                    + "The content between <history></history> tags contains your conversation"
                    + " history\n";

    private final DashScopeMessageConverter messageConverter;
    private final DashScopeResponseParser responseParser;
    private final DashScopeToolsHelper toolsHelper;
    private final DashScopeConversationMerger conversationMerger;
    private final String conversationHistoryPrompt;

    /**
     * 使用默认对话历史提示创建 DashScopeMultiAgentFormatter。
     */
    public DashScopeMultiAgentFormatter() {
        this(DEFAULT_CONVERSATION_HISTORY_PROMPT);
    }

    /**
     * 使用自定义对话历史提示创建 DashScopeMultiAgentFormatter。
     *
     * @param conversationHistoryPrompt 追加在对话历史之前的提示
     */
    public DashScopeMultiAgentFormatter(String conversationHistoryPrompt) {
        this.messageConverter = new DashScopeMessageConverter(this::convertToolResultToString);
        this.responseParser = new DashScopeResponseParser();
        this.toolsHelper = new DashScopeToolsHelper();
        this.conversationMerger = new DashScopeConversationMerger(conversationHistoryPrompt);
        this.conversationHistoryPrompt = conversationHistoryPrompt;
    }

    @Override
    protected List<DashScopeMessage> doFormat(List<Msg> msgs) {
        return doFormat(msgs, null);
    }

    @Override
    protected List<DashScopeMessage> doFormat(List<Msg> msgs, GenerateOptions options) {
        List<DashScopeMessage> result = new ArrayList<>();
        List<Boolean> cacheDirectives = new ArrayList<>();
        int startIndex = 0;

        // 先处理系统消息（若有），单独输出。
        if (!msgs.isEmpty() && msgs.get(0).getRole() == MsgRole.SYSTEM) {
            Msg systemMsg = msgs.get(0);
            DashScopeMessage formattedSystemMessage =
                    DashScopeMessage.builder()
                            .role("system")
                            .content(extractTextContent(systemMsg))
                            .build();
            messageConverter.applyCacheControlFromMetadata(systemMsg, formattedSystemMessage);
            result.add(formattedSystemMessage);
            cacheDirectives.add(DashScopeChatFormatter.cacheControlDirective(systemMsg));
            startIndex = 1;
        }

        // 对其余消息分组并逐组处理。
        List<MessageGroup> groups =
                groupMessagesSequentially(msgs.subList(startIndex, msgs.size()));
        boolean isFirstAgentMessage = true;

        for (MessageGroup group : groups) {
            if (group.type == GroupType.AGENT_MESSAGE) {
                // 将智能体消息格式化为对话历史。
                String historyPrompt = isFirstAgentMessage ? conversationHistoryPrompt : "";
                DashScopeMessage mergedMessage =
                        conversationMerger.mergeToMessage(
                                group.messages,
                                msg -> msg.getName() != null ? msg.getName() : "Unknown",
                                this::convertToolResultToString,
                                historyPrompt);
                cacheDirectives.add(applyMergedCacheControlMetadata(group.messages, mergedMessage));
                result.add(mergedMessage);
                isFirstAgentMessage = false;
            } else if (group.type == GroupType.TOOL_SEQUENCE) {
                // 直接格式化工具序列。
                for (Msg msg : group.messages) {
                    if (msg.getRole() == MsgRole.ASSISTANT) {
                        result.add(formatAssistantToolCall(msg));
                        cacheDirectives.add(DashScopeChatFormatter.cacheControlDirective(msg));
                    } else if (msg.getRole() == MsgRole.TOOL
                            || (msg.getRole() == MsgRole.SYSTEM
                                    && msg.hasContentBlocks(ToolResultBlock.class))) {
                        result.add(formatToolResult(msg));
                        cacheDirectives.add(DashScopeChatFormatter.cacheControlDirective(msg));
                    }
                }
            }
        }

        DashScopeChatFormatter.applyAutomaticCacheControl(result, cacheDirectives, options);
        return result;
    }

    @Override
    public ChatResponse parseResponse(DashScopeResponse result, Instant startTime) {
        return responseParser.parseResponse(result, startTime);
    }

    @Override
    public void applyOptions(
            DashScopeRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        DashScopeParameters params = request.getParameters();
        if (params == null) {
            params = DashScopeParameters.builder().build();
            request.setParameters(params);
        }
        toolsHelper.applyOptions(params, options, defaultOptions);
    }

    @Override
    public void applyTools(DashScopeRequest request, List<ToolSchema> tools) {
        DashScopeParameters params = request.getParameters();
        if (params == null) {
            params = DashScopeParameters.builder().build();
            request.setParameters(params);
        }
        params.setTools(toolsHelper.convertTools(tools));
    }

    @Override
    public void applyToolChoice(DashScopeRequest request, ToolChoice toolChoice) {
        DashScopeParameters params = request.getParameters();
        if (params == null) {
            params = DashScopeParameters.builder().build();
            request.setParameters(params);
        }
        toolsHelper.applyToolChoice(params, toolChoice);
    }

    /**
     * 将 AgentScope Msg 格式化为 DashScope 多模态消息，供需要 MultiModalConversation API 的视觉模型使用。
     *
     * <p>处理步骤：
     * 1. 处理系统消息（若有）。
     * 2. 将其余消息分为 agent_message 与 tool_sequence。
     * 3. 按顺序处理每组，仅首个 agent_message 组带历史提示。
     *
     * @param msgs 待转换的 AgentScope 消息
     * @return 包含多模态内容的 DashScopeMessage 列表
     */
    public List<DashScopeMessage> formatMultiModal(List<Msg> msgs) {
        return formatMultiModal(msgs, null);
    }

    /**
     * 使用请求级生成选项将 AgentScope Msg 格式化为 DashScope 多模态消息。
     *
     * @param msgs 待转换的 AgentScope 消息
     * @param options 请求级生成选项，可为 {@code null}
     * @return 包含多模态内容的 DashScopeMessage 列表
     */
    public List<DashScopeMessage> formatMultiModal(List<Msg> msgs, GenerateOptions options) {
        List<DashScopeMessage> result = new ArrayList<>();
        List<Boolean> cacheDirectives = new ArrayList<>();
        int startIndex = 0;

        // 先处理系统消息（若有）。
        if (!msgs.isEmpty() && msgs.get(0).getRole() == MsgRole.SYSTEM) {
            Msg systemMsg = msgs.get(0);
            DashScopeMessage formattedSystemMessage =
                    DashScopeMessage.builder()
                            .role("system")
                            .content(
                                    List.of(
                                            DashScopeContentPart.text(
                                                    extractTextContent(systemMsg))))
                            .build();
            messageConverter.applyCacheControlFromMetadata(systemMsg, formattedSystemMessage);
            result.add(formattedSystemMessage);
            cacheDirectives.add(DashScopeChatFormatter.cacheControlDirective(systemMsg));
            startIndex = 1;
        }

        // 对其余消息分组并逐组处理。
        List<MessageGroup> groups =
                groupMessagesSequentially(msgs.subList(startIndex, msgs.size()));
        boolean isFirstAgentMessage = true;

        for (MessageGroup group : groups) {
            if (group.type == GroupType.AGENT_MESSAGE) {
                // 将智能体消息格式化为对话历史。
                DashScopeMessage mergedMessage =
                        conversationMerger.mergeToMultiModalMessage(
                                group.messages,
                                msg -> msg.getName() != null ? msg.getName() : "Unknown",
                                this::convertToolResultToString,
                                isFirstAgentMessage);
                cacheDirectives.add(applyMergedCacheControlMetadata(group.messages, mergedMessage));
                result.add(mergedMessage);
                isFirstAgentMessage = false;
            } else if (group.type == GroupType.TOOL_SEQUENCE) {
                // 直接格式化工具序列。
                for (Msg msg : group.messages) {
                    DashScopeMessage message = messageConverter.convertToMessage(msg, true);
                    result.add(message);
                    cacheDirectives.add(DashScopeChatFormatter.cacheControlDirective(msg));
                }
            }
        }

        DashScopeChatFormatter.applyAutomaticCacheControl(result, cacheDirectives, options);
        return result;
    }

    /**
     * 为 API 调用构建完整 DashScopeRequest。
     *
     * @param model 模型名称
     * @param messages 格式化后的 DashScope 消息
     * @param stream 是否启用流式
     * @return 可用于 API 调用的完整 DashScopeRequest
     */
    public DashScopeRequest buildRequest(
            String model, List<DashScopeMessage> messages, boolean stream) {
        DashScopeParameters params =
                DashScopeParameters.builder().incrementalOutput(stream).build();

        return DashScopeRequest.builder()
                .model(model)
                .input(DashScopeInput.builder().messages(messages).build())
                .parameters(params)
                .build();
    }

    // ========== 私有辅助方法 ==========

    /**
     * 按顺序将消息分为 agent_message 与 tool_sequence 组。
     *
     * @param msgs 待分组消息（不含系统消息）
     * @return 有序的 MessageGroup 列表
     */
    private List<MessageGroup> groupMessagesSequentially(List<Msg> msgs) {
        List<MessageGroup> result = new ArrayList<>();
        if (msgs.isEmpty()) {
            return result;
        }

        GroupType currentType = null;
        List<Msg> currentGroup = new ArrayList<>();

        for (Msg msg : msgs) {
            boolean isToolRelated =
                    msg.getRole() == MsgRole.TOOL
                            || msg.hasContentBlocks(ToolUseBlock.class)
                            || msg.hasContentBlocks(ToolResultBlock.class);

            GroupType msgType = isToolRelated ? GroupType.TOOL_SEQUENCE : GroupType.AGENT_MESSAGE;

            if (currentType == null) {
                // 首条消息。
                currentType = msgType;
                currentGroup.add(msg);
            } else if (currentType == msgType) {
                // 类型相同，加入当前组。
                currentGroup.add(msg);
            } else {
                // 类型不同，输出当前组并开始新组。
                result.add(new MessageGroup(currentType, new ArrayList<>(currentGroup)));
                currentGroup.clear();
                currentGroup.add(msg);
                currentType = msgType;
            }
        }

        // 加入最后一组。
        if (!currentGroup.isEmpty()) {
            result.add(new MessageGroup(currentType, currentGroup));
        }

        return result;
    }

    /**
     * 格式化包含工具调用的助手消息。
     */
    private DashScopeMessage formatAssistantToolCall(Msg msg) {
        DashScopeMessage.Builder builder = DashScopeMessage.builder().role("assistant");

        List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
        if (!toolBlocks.isEmpty()) {
            builder.toolCalls(toolsHelper.convertToolCalls(toolBlocks));
            String textContent = extractTextContent(msg);
            builder.content(textContent.isEmpty() ? "" : textContent);
        } else {
            builder.content(extractTextContent(msg));
        }

        DashScopeMessage result = builder.build();
        messageConverter.applyCacheControlFromMetadata(msg, result);
        return result;
    }

    /**
     * 格式化工具结果消息。
     */
    private DashScopeMessage formatToolResult(Msg msg) {
        ToolResultBlock result = msg.getFirstContentBlock(ToolResultBlock.class);
        DashScopeMessage message;
        if (result != null) {
            message =
                    DashScopeMessage.builder()
                            .role("tool")
                            .toolCallId(result.getId())
                            .name(result.getName())
                            .content(convertToolResultToString(result.getOutput()))
                            .build();
        } else {
            message =
                    DashScopeMessage.builder()
                            .role("tool")
                            .toolCallId("tool_call_" + System.currentTimeMillis())
                            .content(extractTextContent(msg))
                            .build();
        }
        messageConverter.applyCacheControlFromMetadata(msg, message);
        return message;
    }

    private Boolean applyMergedCacheControlMetadata(
            List<Msg> messages, DashScopeMessage mergedMessage) {
        // 合并输出只有一个内容边界，因此最后一条显式指令生效。
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg message = messages.get(i);
            if (message.getMetadata() != null
                    && message.getMetadata().get(MessageMetadataKeys.CACHE_CONTROL)
                            instanceof Boolean) {
                messageConverter.applyCacheControlFromMetadata(message, mergedMessage);
                return (Boolean) message.getMetadata().get(MessageMetadataKeys.CACHE_CONTROL);
            }
        }
        return null;
    }

    /**
     * 顺序消息分组的组类型枚举。
     */
    private enum GroupType {
        AGENT_MESSAGE,
        TOOL_SEQUENCE
    }

    /**
     * 保存一组消息及其类型的辅助类。
     */
    private static class MessageGroup {
        final GroupType type;
        final List<Msg> messages;

        MessageGroup(GroupType type, List<Msg> messages) {
            this.type = type;
            this.messages = messages;
        }
    }
}
