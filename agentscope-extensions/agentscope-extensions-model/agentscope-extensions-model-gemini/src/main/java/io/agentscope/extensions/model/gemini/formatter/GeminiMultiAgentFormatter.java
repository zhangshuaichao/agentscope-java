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
package io.agentscope.extensions.model.gemini.formatter;

import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 面向多智能体对话的 Gemini 格式化器。
 *
 * <p>将 AgentScope Msg 对象转换为支持多智能体的 Gemini Content 对象，
 * 将多智能体对话折叠为带历史标签的单条用户消息。
 *
 * <p><b>格式化策略：</b>
 * <ul>
 *   <li>系统消息：转为 user 角色（Gemini 的 contents 不支持 system）</li>
 *   <li>智能体消息：合并为带 {@code <history>} 标签的单个 Content</li>
 *   <li>工具序列：直接转换（包含工具调用的 assistant 加包含工具结果的 user）</li>
 * </ul>
 */
public class GeminiMultiAgentFormatter
        extends AbstractBaseFormatter<
                Content, GenerateContentResponse, GenerateContentConfig.Builder> {

    private static final String DEFAULT_CONVERSATION_HISTORY_PROMPT =
            "# Conversation History\n"
                    + "The content between <history></history> tags contains your conversation"
                    + " history\n";

    private final GeminiMessageConverter messageConverter;
    private final GeminiResponseParser responseParser;
    private final GeminiToolsHelper toolsHelper;
    private final GeminiConversationMerger conversationMerger;
    private final GeminiChatFormatter chatFormatter;

    /**
     * 使用默认对话历史提示创建 GeminiMultiAgentFormatter。
     */
    public GeminiMultiAgentFormatter() {
        this(DEFAULT_CONVERSATION_HISTORY_PROMPT);
    }

    /**
     * 使用自定义对话历史提示创建 GeminiMultiAgentFormatter。
     *
     * @param conversationHistoryPrompt 添加到对话历史之前的提示
     */
    public GeminiMultiAgentFormatter(String conversationHistoryPrompt) {
        this.messageConverter = new GeminiMessageConverter();
        this.responseParser = new GeminiResponseParser();
        this.toolsHelper = new GeminiToolsHelper();
        this.conversationMerger = new GeminiConversationMerger(conversationHistoryPrompt);
        this.chatFormatter = new GeminiChatFormatter();
    }

    @Override
    protected List<Content> doFormat(List<Msg> msgs) {
        List<Content> result = new ArrayList<>();
        int startIndex = 0;

        // 先处理系统消息（若有），转为 user 角色
        if (!msgs.isEmpty() && msgs.get(0).getRole() == MsgRole.SYSTEM) {
            Msg systemMsg = msgs.get(0);
            // Gemini 的 contents 不支持 system 角色，转为 user
            Content systemContent =
                    Content.builder()
                            .role("user")
                            .parts(
                                    List.of(
                                            Part.builder()
                                                    .text(extractTextContent(systemMsg))
                                                    .build()))
                            .build();
            result.add(systemContent);
            startIndex = 1;
        }

        // 对其余消息分组并逐组处理
        List<MessageGroup> groups =
                groupMessagesSequentially(msgs.subList(startIndex, msgs.size()));
        boolean isFirstAgentMessage = true;

        for (MessageGroup group : groups) {
            if (group.type == GroupType.AGENT_MESSAGE) {
                // 使用对话历史格式化智能体消息
                String historyPrompt =
                        isFirstAgentMessage ? DEFAULT_CONVERSATION_HISTORY_PROMPT : "";
                result.add(
                        conversationMerger.mergeToContent(
                                group.messages,
                                msg -> msg.getName() != null ? msg.getName() : "Unknown",
                                this::convertToolResultToString,
                                historyPrompt));
                isFirstAgentMessage = false;

            } else if (group.type == GroupType.TOOL_SEQUENCE) {
                // 使用消息转换器直接格式化工具序列
                result.addAll(messageConverter.convertMessages(group.messages));
            }
        }

        return result;
    }

    @Override
    public ChatResponse parseResponse(GenerateContentResponse response, Instant startTime) {
        return responseParser.parseResponse(response, startTime);
    }

    @Override
    public void applyOptions(
            GenerateContentConfig.Builder configBuilder,
            GenerateOptions options,
            GenerateOptions defaultOptions) {
        // 委托给聊天格式化器
        chatFormatter.applyOptions(configBuilder, options, defaultOptions);
    }

    @Override
    public void applyTools(GenerateContentConfig.Builder configBuilder, List<ToolSchema> tools) {
        chatFormatter.applyTools(configBuilder, tools);
    }

    @Override
    public void applyToolChoice(
            GenerateContentConfig.Builder configBuilder, ToolChoice toolChoice) {
        chatFormatter.applyToolChoice(configBuilder, toolChoice);
    }

    // ========== 私有辅助方法 ==========

    /**
     * 按顺序将消息分为 agent_message 与 tool_sequence 组。
     *
     * @param msgs 待分组消息（不含系统消息）
     * @return 按顺序排列的 MessageGroup 对象列表
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
                // 第一条消息
                currentType = msgType;
                currentGroup.add(msg);
            } else if (currentType == msgType) {
                // 类型相同，添加到当前组
                currentGroup.add(msg);
            } else {
                // 类型不同，输出当前组并开始新组
                result.add(new MessageGroup(currentType, new ArrayList<>(currentGroup)));
                currentGroup.clear();
                currentGroup.add(msg);
                currentType = msgType;
            }
        }

        // 添加最后一组
        if (!currentGroup.isEmpty()) {
            result.add(new MessageGroup(currentType, currentGroup));
        }

        return result;
    }

    // ========== 内部类 ==========

    /**
     * 消息组类型。
     */
    private enum GroupType {
        /**
         * 普通智能体对话消息
         */
        AGENT_MESSAGE,
        /**
         * 工具调用与工具结果序列
         */
        TOOL_SEQUENCE
    }

    /**
     * 一组消息的容器。
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
