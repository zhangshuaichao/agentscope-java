/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.openaiofficial;

import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI Responses API 的多智能体格式化器。
 *
 * <p>将 AgentScope Msg 对象转换为支持多智能体的 Responses API ResponseInputItem 对象。
 *
 * <p>将消息分为以下组：
 * <ul>
 *   <li><b>SYSTEM</b>：逐条提取为 SYSTEM 角色的 {@code EasyInputMessage}
 *   <li><b>TOOL_SEQUENCE</b>：工具调用与结果，原样传给
 *       {@link ResponsesRequestMapper}（包括推理重放）
 *   <li><b>AGENT_CONVERSATION</b>：合并为 USER 角色的单个
 *       {@code EasyInputMessage}，包装在 {@code <history>} 标签内
 *   <li><b>BYPASS</b>：带 {@code BYPASS_MULTIAGENT_HISTORY_MERGE} 元数据的消息，
 *       逐条作为用户消息传递
 * </ul>
 */
public class ResponsesMultiAgentFormatter {

    private static final Logger log = LoggerFactory.getLogger(ResponsesMultiAgentFormatter.class);

    private static final String DEFAULT_CONVERSATION_HISTORY_PROMPT =
            "# Conversation History\n"
                    + "The content between <history></history> tags contains your conversation"
                    + " history\n";

    private static final String HISTORY_START_TAG = "<history>";
    private static final String HISTORY_END_TAG = "</history>";

    private final String conversationHistoryPrompt;

    /**
     * 使用默认对话历史提示创建格式化器。
     */
    public ResponsesMultiAgentFormatter() {
        this(DEFAULT_CONVERSATION_HISTORY_PROMPT);
    }

    /**
     * 使用自定义对话历史提示创建格式化器。
     *
     * @param conversationHistoryPrompt 添加到首个历史块之前的提示
     */
    public ResponsesMultiAgentFormatter(String conversationHistoryPrompt) {
        this.conversationHistoryPrompt = conversationHistoryPrompt;
    }

    /**
     * 将 AgentScope 消息格式化为 Responses API 输入项，并合并多智能体对话。
     *
     * <p>系统消息和工具序列原样传给 {@link ResponsesRequestMapper}。
     * 智能体对话消息合并为带 {@code <history>} 标签的单条用户消息。
     *
     * @param messages 对话历史
     * @return Responses API 输入项
     */
    public List<ResponseInputItem> formatHistory(List<Msg> messages) {
        List<ResponseInputItem> result = new ArrayList<>();
        List<MessageGroup> groups = groupMessages(messages);
        boolean isFirstAgentGroup = true;

        for (MessageGroup group : groups) {
            switch (group.type) {
                case SYSTEM ->
                        ResponsesRequestMapper.mapSystemMessage(group.messages.get(0), result);
                case TOOL_SEQUENCE -> {
                    for (Msg msg : group.messages) {
                        if (msg.getRole() == MsgRole.ASSISTANT || msg.getRole() == MsgRole.TOOL) {
                            ResponsesRequestMapper.mapMessage(msg, result);
                        }
                    }
                }
                case AGENT_CONVERSATION -> {
                    result.addAll(mergeAgentConversation(group.messages, isFirstAgentGroup));
                    isFirstAgentGroup = false;
                }
                case BYPASS -> ResponsesRequestMapper.mapMessage(group.messages.get(0), result);
            }
        }

        return result;
    }

    // -- 分组 --------------------------------------------------

    /**
     * 多智能体对话中的消息组类型。
     */
    private enum GroupType {
        SYSTEM, // 系统消息
        TOOL_SEQUENCE, // 工具使用与工具结果消息
        AGENT_CONVERSATION, // 普通智能体对话消息
        BYPASS // 绕过历史合并的消息
    }

    private record MessageGroup(GroupType type, List<Msg> messages) {}

    /**
     * 将同类型消息按连续段分组。SYSTEM 和 BYPASS 消息始终开始新组，
     * 绝不与相邻消息合并分组。
     */
    private List<MessageGroup> groupMessages(List<Msg> msgs) {
        List<MessageGroup> groups = new ArrayList<>();
        List<Msg> currentGroup = new ArrayList<>();
        GroupType currentType = null;

        for (Msg msg : msgs) {
            GroupType msgType = determineGroupType(msg);

            if (currentType == null
                    || currentType != msgType
                    || msgType == GroupType.SYSTEM
                    || msgType == GroupType.BYPASS) {
                if (!currentGroup.isEmpty()) {
                    groups.add(new MessageGroup(currentType, new ArrayList<>(currentGroup)));
                }
                currentGroup = new ArrayList<>();
                currentType = msgType;
            }
            currentGroup.add(msg);
        }

        if (!currentGroup.isEmpty()) {
            groups.add(new MessageGroup(currentType, currentGroup));
        }

        return groups;
    }

    private GroupType determineGroupType(Msg msg) {
        if (shouldBypassHistory(msg)) {
            return GroupType.BYPASS;
        }

        return switch (msg.getRole()) {
            case SYSTEM -> GroupType.SYSTEM;
            case TOOL -> GroupType.TOOL_SEQUENCE;
            case USER, ASSISTANT -> {
                if (msg.hasContentBlocks(ToolUseBlock.class)
                        || msg.hasContentBlocks(ToolResultBlock.class)) {
                    yield GroupType.TOOL_SEQUENCE;
                }
                // 带加密推理内容的 assistant 消息必须
                // 传给 mapAssistantMessage。
                if (msg.getRole() == MsgRole.ASSISTANT && hasEncryptedReasoning(msg)) {
                    yield GroupType.TOOL_SEQUENCE;
                }
                yield GroupType.AGENT_CONVERSATION;
            }
        };
    }

    private static boolean shouldBypassHistory(Msg msg) {
        if (msg.getMetadata() == null) {
            return false;
        }
        Object bypassFlag =
                msg.getMetadata().get(MessageMetadataKeys.BYPASS_MULTIAGENT_HISTORY_MERGE);
        return Boolean.TRUE.equals(bypassFlag);
    }

    /**
     * 检查消息是否携带必须保留为 Responses 推理重放项的加密推理内容，
     * 这类内容不能合并到历史文本。
     */
    private static boolean hasEncryptedReasoning(Msg msg) {
        ThinkingBlock thinkingBlock = msg.getFirstContentBlock(ThinkingBlock.class);
        if (thinkingBlock == null || thinkingBlock.getMetadata() == null) {
            return false;
        }
        Object ec =
                thinkingBlock
                        .getMetadata()
                        .get(OpenAIOfficialConstants.MD_REASONING_ENCRYPTED_CONTENT);
        return ec instanceof String s && !s.isEmpty();
    }

    // -- 对话合并 --------------------------------------

    /**
     * 将一组智能体对话消息合并为带 {@code <history>} 标签的单条用户消息。
     *
     * <p>文本和思考块累积到文本缓冲区。图像与数据块会先输出缓冲区内容，
     * 再作为独立的 {@link ResponseInputContent} 部分添加，以保留其多模态性质。
     *
     * @param messages 待合并的对话消息
     * @param isFirstGroup 是否为首个智能体对话组（控制是否包含历史提示）
     * @return 包含合并用户消息的单元素列表，或空列表
     */
    private List<ResponseInputItem> mergeAgentConversation(
            List<Msg> messages, boolean isFirstGroup) {

        List<ResponseInputContent> parts = new ArrayList<>();
        StringBuilder textBuffer = new StringBuilder();

        String prompt = isFirstGroup ? conversationHistoryPrompt : "";
        textBuffer.append(prompt).append(HISTORY_START_TAG).append("\n");

        // 仅在多轮上下文中添加智能体名称前缀
        boolean includePrefix = messages.size() > 1;

        for (Msg msg : messages) {
            processMessage(msg, textBuffer, parts, includePrefix);
        }

        textBuffer.append(HISTORY_END_TAG).append("\n");

        // 输出剩余文本（始终非空：至少存在历史标签）
        parts.add(
                ResponseInputContent.ofInputText(
                        ResponseInputText.builder().text(textBuffer.toString()).build()));

        return List.of(
                ResponseInputItem.ofEasyInputMessage(
                        EasyInputMessage.builder()
                                .role(EasyInputMessage.Role.USER)
                                .contentOfResponseInputMessageContentList(parts)
                                .build()));
    }

    /**
     * 处理单条消息，按需将每个内容块路由到文本缓冲区或部分列表。
     */
    private void processMessage(
            Msg msg,
            StringBuilder textBuffer,
            List<ResponseInputContent> parts,
            boolean includePrefix) {

        String agentName = msg.getName();
        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null) {
            return;
        }

        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock tb) {
                if (includePrefix) {
                    appendNamePrefix(textBuffer, agentName);
                }
                textBuffer.append(tb.getText()).append("\n");
            } else if (block instanceof HintBlock hb) {
                if (includePrefix) {
                    appendNamePrefix(textBuffer, agentName);
                }
                textBuffer.append(hb.getHint()).append("\n");
            } else if (block instanceof ImageBlock ib) {
                flushText(textBuffer, parts);
                try {
                    String imageUrl = ResponsesRequestMapper.resolveImageUrl(ib.getSource());
                    parts.add(
                            ResponseInputContent.ofInputImage(
                                    ResponseInputImage.builder()
                                            .imageUrl(imageUrl)
                                            .detail(ResponseInputImage.Detail.of("auto"))
                                            .build()));
                } catch (Exception e) {
                    log.warn(
                            "Failed to process ImageBlock in multi-agent conversation: {}",
                            e.getMessage());
                    if (includePrefix) {
                        appendNamePrefix(textBuffer, agentName);
                    }
                    textBuffer.append("[Image - processing failed]\n");
                }
            } else if (block instanceof DataBlock db) {
                flushText(textBuffer, parts);
                try {
                    String imageUrl = ResponsesRequestMapper.resolveDataBlockImageUrl(db);
                    parts.add(
                            ResponseInputContent.ofInputImage(
                                    ResponseInputImage.builder()
                                            .imageUrl(imageUrl)
                                            .detail(ResponseInputImage.Detail.of("auto"))
                                            .build()));
                } catch (Exception e) {
                    log.warn(
                            "Failed to process DataBlock in multi-agent conversation: {}",
                            e.getMessage());
                    if (includePrefix) {
                        appendNamePrefix(textBuffer, agentName);
                    }
                    textBuffer.append("[Data - processing failed]\n");
                }
            } else if (block instanceof ThinkingBlock tb) {
                if (includePrefix) {
                    appendNamePrefix(textBuffer, agentName);
                }
                String thinking = tb.getThinking();
                if (thinking != null && !thinking.isEmpty()) {
                    textBuffer.append("[Thinking]: ").append(thinking).append("\n");
                }
            }
            // 静默跳过其他块类型（AudioBlock、VideoBlock、ToolUseBlock、
            // ToolResultBlock）。ToolUseBlock 和
            // ToolResultBlock 不会到达此处
        }
    }

    private void flushText(StringBuilder textBuffer, List<ResponseInputContent> parts) {
        if (textBuffer.length() > 0) {
            parts.add(
                    ResponseInputContent.ofInputText(
                            ResponseInputText.builder().text(textBuffer.toString()).build()));
            textBuffer.setLength(0);
        }
    }

    private void appendNamePrefix(StringBuilder buffer, String agentName) {
        if (agentName != null && !agentName.isEmpty()) {
            buffer.append(agentName).append(": ");
        }
    }
}
