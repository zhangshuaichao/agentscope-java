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

import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI Chat Completion HTTP API 的多智能体格式化器。
 * 将 AgentScope Msg 对象转换为 OpenAI DTO，并支持多智能体。
 *
 * <p>扩展 {@link OpenAIChatFormatter}，通过以下方式处理多智能体间对话：
 * <ul><li>将多智能体消息分组为对话历史。</li><li>使用历史标签等特殊标记组织对话。</li>
 * <li>将多智能体对话整合为单条用户消息。</li></ul>
 *
 * <p>所有选项、工具和 tool_choice 处理均继承自 {@link OpenAIChatFormatter}。
 *
 * <p>【学习提示】多智能体历史合并改变的是传给模型的表示，不会把历史文本提升为系统指令。
 * 工具调用序列和必须原样保留的推理信息应保留独立边界，不能无条件全部拼成用户文本。
 */
public class OpenAIMultiAgentFormatter extends OpenAIChatFormatter {

    private static final String DEFAULT_CONVERSATION_HISTORY_PROMPT =
            "# Conversation History\n"
                    + "The content between <history></history> tags contains your conversation"
                    + " history\n";

    private final OpenAIConversationMerger conversationMerger;

    /** 使用默认对话历史提示词创建 OpenAIMultiAgentFormatter。 */
    public OpenAIMultiAgentFormatter() {
        this(DEFAULT_CONVERSATION_HISTORY_PROMPT);
    }

    /**
     * 使用自定义对话历史提示词创建 OpenAIMultiAgentFormatter。
     *
     * @param conversationHistoryPrompt 对话历史之前添加的提示词
     */
    public OpenAIMultiAgentFormatter(String conversationHistoryPrompt) {
        super();
        this.conversationMerger = new OpenAIConversationMerger(conversationHistoryPrompt);
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        return doFormat(msgs, null);
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs, GenerateOptions options) {
        List<OpenAIMessage> result = new ArrayList<>();
        List<Boolean> cacheDirectives = new ArrayList<>();

        for (MessageGroup group : groupMessages(msgs)) {
            switch (group.type) {
                case SYSTEM -> {
                    Msg systemMsg = group.messages.get(0);
                    result.add(convertMessage(systemMsg, false));
                    cacheDirectives.add(cacheControlDirective(systemMsg));
                }
                case TOOL_SEQUENCE -> {
                    for (Msg msg : group.messages) {
                        if (msg.getRole() == MsgRole.ASSISTANT || msg.getRole() == MsgRole.TOOL) {
                            result.add(convertMessage(msg, hasMediaContent(msg)));
                            cacheDirectives.add(cacheControlDirective(msg));
                        }
                    }
                }
                case AGENT_CONVERSATION -> {
                    OpenAIMessage mergedMessage =
                            conversationMerger.mergeToUserMessage(
                                    group.messages,
                                    msg -> formatRoleLabel(msg.getRole()),
                                    this::convertToolResultToString);
                    cacheDirectives.add(
                            applyMergedCacheControlMetadata(group.messages, mergedMessage));
                    result.add(mergedMessage);
                }
                case BYPASS -> {
                    Msg bypassMsg = group.messages.get(0);
                    result.add(convertMessage(bypassMsg, hasMediaContent(bypassMsg)));
                    cacheDirectives.add(cacheControlDirective(bypassMsg));
                }
            }
        }

        applyAutomaticCacheControl(result, cacheDirectives, options);
        return result;
    }

    private Boolean applyMergedCacheControlMetadata(
            List<Msg> messages, OpenAIMessage mergedMessage) {
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

    // ========== 私有辅助方法 ==========

    /** 将消息分为系统消息、工具序列、智能体对话及绕过合并的消息等类型。 */
    private List<MessageGroup> groupMessages(List<Msg> msgs) {
        List<MessageGroup> groups = new ArrayList<>();
        List<Msg> currentGroup = new ArrayList<>();
        MessageGroupType currentType = null;

        for (Msg msg : msgs) {
            MessageGroupType msgType = determineGroupType(msg);

            if (currentType == null
                    || currentType != msgType
                    || (msgType == MessageGroupType.SYSTEM)
                    || (msgType == MessageGroupType.BYPASS)) {
                // 开始新分组。
                if (!currentGroup.isEmpty()) {
                    groups.add(new MessageGroup(currentType, new ArrayList<>(currentGroup)));
                }
                currentGroup = new ArrayList<>();
                currentType = msgType;
            }

            currentGroup.add(msg);
        }

        // 加入最后一个分组。
        if (!currentGroup.isEmpty()) {
            groups.add(new MessageGroup(currentType, currentGroup));
        }

        return groups;
    }

    /** 判断消息所属的分组类型。 */
    private MessageGroupType determineGroupType(Msg msg) {
        // 优先检查绕过标记。
        if (shouldBypassHistory(msg)) {
            return MessageGroupType.BYPASS;
        }

        return switch (msg.getRole()) {
            case SYSTEM -> MessageGroupType.SYSTEM;
            case TOOL -> MessageGroupType.TOOL_SEQUENCE;
            case USER, ASSISTANT -> {
                if (msg.hasContentBlocks(ToolUseBlock.class)) {
                    yield MessageGroupType.TOOL_SEQUENCE;
                }
                if (msg.getRole() == MsgRole.ASSISTANT && hasReasoningDetails(msg)) {
                    yield MessageGroupType.TOOL_SEQUENCE;
                }
                yield MessageGroupType.AGENT_CONVERSATION;
            }
        };
    }

    /** 检查消息是否携带必须保存在独立助手消息中的加密推理详情，不能合并到用户历史消息中。 */
    private boolean hasReasoningDetails(Msg msg) {
        ThinkingBlock tb = msg.getFirstContentBlock(ThinkingBlock.class);
        if (tb == null || tb.getMetadata() == null) {
            return false;
        }
        return tb.getMetadata().containsKey(ThinkingBlock.METADATA_REASONING_DETAILS);
    }

    /** 表示一组相关消息。 */
    private static class MessageGroup {
        private final MessageGroupType type;
        private final List<Msg> messages;

        public MessageGroup(MessageGroupType type, List<Msg> messages) {
            this.type = type;
            this.messages = messages;
        }
    }

    /** 多智能体对话中的消息分组类型。 */
    private enum MessageGroupType {
        SYSTEM, // 系统消息。
        TOOL_SEQUENCE, // 工具调用和工具结果消息。
        AGENT_CONVERSATION, // 普通智能体对话消息。
        BYPASS // 绕过历史合并的消息。
    }
}
