/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.ollama.formatter;

import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.ollama.dto.OllamaMessage;
import io.agentscope.extensions.model.ollama.dto.OllamaRequest;
import io.agentscope.extensions.model.ollama.dto.OllamaResponse;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 面向多智能体对话的 Ollama 格式化器，将 AgentScope Msg 对象转换为
 * 支持多智能体的 Ollama DTO Message 对象。
 * 将多智能体对话折叠为带历史标签的单条用户消息。
 */
public class OllamaMultiAgentFormatter
        extends AbstractBaseFormatter<OllamaMessage, OllamaResponse, OllamaRequest> {
    private static final Logger log = LoggerFactory.getLogger(OllamaMultiAgentFormatter.class);

    private static final String DEFAULT_CONVERSATION_HISTORY_PROMPT =
            "# Conversation History\n"
                    + "The content between <history></history> tags contains your conversation"
                    + " history\n";

    private final OllamaMessageConverter messageConverter;
    private final OllamaResponseParser responseParser;
    private final OllamaToolsHelper toolsHelper;
    private final OllamaConversationMerger conversationMerger;
    private final String conversationHistoryPrompt;
    private final boolean promoteToolResultImages;

    /**
     * 使用默认对话历史提示创建 OllamaMultiAgentFormatter。
     */
    public OllamaMultiAgentFormatter() {
        this(DEFAULT_CONVERSATION_HISTORY_PROMPT);
    }

    /**
     * 使用自定义对话历史提示创建 OllamaMultiAgentFormatter。
     *
     * @param conversationHistoryPrompt 添加到对话历史之前的提示
     */
    public OllamaMultiAgentFormatter(String conversationHistoryPrompt) {
        this(conversationHistoryPrompt, false);
    }

    public OllamaMultiAgentFormatter(boolean promoteToolResultImages) {
        this(DEFAULT_CONVERSATION_HISTORY_PROMPT, promoteToolResultImages);
    }

    public OllamaMultiAgentFormatter(
            String conversationHistoryPrompt, boolean promoteToolResultImages) {
        this.messageConverter = new OllamaMessageConverter();
        this.responseParser = new OllamaResponseParser();
        this.toolsHelper = new OllamaToolsHelper();
        this.conversationMerger = new OllamaConversationMerger(conversationHistoryPrompt);
        this.conversationHistoryPrompt = conversationHistoryPrompt;
        this.promoteToolResultImages = promoteToolResultImages;
    }

    @Override
    protected List<OllamaMessage> doFormat(List<Msg> msgs) {
        List<OllamaMessage> result = new ArrayList<>();
        int startIndex = 0;

        // 先处理系统消息（若有），单独输出
        if (!msgs.isEmpty() && msgs.get(0).getRole() == MsgRole.SYSTEM) {
            OllamaMessage systemMsg = new OllamaMessage();
            systemMsg.setRole("system");
            systemMsg.setContent(extractTextContent(msgs.get(0)));
            result.add(systemMsg);
            startIndex = 1;
        }

        // 系统消息之后仅有一条非工具消息时直接转换，
        // 避免将其包装为对话历史
        List<Msg> remainingMsgs = msgs.subList(startIndex, msgs.size());
        if (remainingMsgs.size() == 1 && !isToolMessage(remainingMsgs.get(0))) {
            result.add(messageConverter.convertMessage(remainingMsgs.get(0)));
            return result;
        }

        // 对其余消息分组，逐组处理
        List<MessageGroup> groups = groupMessagesSequentially(remainingMsgs);
        boolean isFirstAgentMessage = true;

        for (MessageGroup group : groups) {
            if (group.type == GroupType.AGENT_MESSAGE) {
                // 使用对话历史格式化智能体消息
                String historyPrompt = isFirstAgentMessage ? conversationHistoryPrompt : "";
                result.add(
                        conversationMerger.mergeToMessage(
                                group.messages,
                                msg -> msg.getName() != null ? msg.getName() : "Unknown",
                                this::convertToolResultToString,
                                historyPrompt));
                isFirstAgentMessage = false;
            } else if (group.type == GroupType.TOOL_SEQUENCE) {
                // 采用与 OllamaChatFormatter 相同的方式直接格式化工具序列
                List<OllamaMessage> toolMessages = formatToolSeq(group.messages);

                // 启用 promoteToolResultImages 时，检查工具结果中的图像
                if (promoteToolResultImages) {
                    for (int i = 0; i < toolMessages.size(); i++) {
                        OllamaMessage toolMsg = toolMessages.get(i);
                        result.add(toolMsg);

                        // 若是包含图像的工具结果，添加单独的用户消息
                        if (isToolResultWithImages(toolMsg)) {
                            OllamaMessage imageMsg =
                                    createImagePromotionMessage(group.messages.get(i), toolMsg);
                            if (imageMsg != null) {
                                result.add(imageMsg);
                            }
                        }
                    }
                } else {
                    result.addAll(toolMessages);
                }
            }
        }

        return result;
    }

    /**
     * 检查给定 OllamaMessage 的内容中是否包含图像。
     *
     * @param msg 待检查的 OllamaMessage
     * @return 消息包含图像和所需文本模式时返回 true，否则返回 false
     */
    private boolean isToolResultWithImages(OllamaMessage msg) {
        return "tool".equals(msg.getRole())
                && msg.getContent() != null
                && msg.getContent().contains("image")
                && msg.getContent().contains("can be found at:");
    }

    /**
     * 工具结果中发现图像时创建图像提升消息。
     *
     * @param originalMsg 包含图像的原始消息
     * @param convertedMsg 已转换的 OllamaMessage
     * @return 包含图像提升信息的 OllamaMessage；创建失败时返回 null
     */
    private OllamaMessage createImagePromotionMessage(Msg originalMsg, OllamaMessage convertedMsg) {
        // 从工具结果内容中提取图像路径
        String content = convertedMsg.getContent();

        // 查找格式为 "image can be found at: ./path" 的图像路径
        Pattern pattern = Pattern.compile("can be found at: ([^\s\n]+)");
        Matcher matcher = pattern.matcher(content);

        if (matcher.find()) {
            String imagePath = matcher.group(1);

            // 尝试将图像转换为 Base64
            try {
                ImageBlock imageBlock = extractImageBlockFromMsg(originalMsg);
                if (imageBlock != null) {
                    String base64Image =
                            new OllamaMediaConverter().convertImageBlockToBase64(imageBlock);

                    OllamaMessage imageMsg = new OllamaMessage();
                    imageMsg.setRole("user");
                    imageMsg.setContent(
                            "<system-info>The following are "
                                    + "the image contents from the tool "
                                    + "result of '"
                                    + convertedMsg.getName()
                                    + "':\n\n"
                                    + "- The image from '"
                                    + imagePath
                                    + "': \n</system-info>");
                    imageMsg.setImages(Collections.singletonList(base64Image));
                    return imageMsg;
                }
            } catch (Exception e) {
                // 记录错误，但不使整个请求失败
                log.warn("Failed to promote image from tool result", e);
            }
        }

        return null;
    }

    /**
     * 从给定消息内容中提取 ImageBlock。
     *
     * @param msg 从中提取图像块的消息
     * @return 找到时返回 ImageBlock，否则返回 null
     */
    private ImageBlock extractImageBlockFromMsg(Msg msg) {
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof ImageBlock) {
                return (ImageBlock) block;
            } else if (block instanceof ToolResultBlock) {
                ToolResultBlock toolResult = (ToolResultBlock) block;
                for (ContentBlock outputBlock : toolResult.getOutput()) {
                    if (outputBlock instanceof ImageBlock) {
                        return (ImageBlock) outputBlock;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public ChatResponse parseResponse(OllamaResponse result, Instant startTime) {
        return responseParser.parseResponse(result);
    }

    @Override
    public void applyOptions(
            OllamaRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        toolsHelper.applyOptions(request, options, defaultOptions);
    }

    /**
     * 将 OllamaOptions 应用到请求。
     *
     * @param request 接收选项的请求
     * @param options 运行时选项
     * @param defaultOptions 默认选项
     */
    public void applyOptions(
            OllamaRequest request, OllamaOptions options, OllamaOptions defaultOptions) {
        toolsHelper.applyOptions(request, options, defaultOptions);
    }

    @Override
    public void applyTools(OllamaRequest request, List<ToolSchema> tools) {
        toolsHelper.applyTools(request, tools);
    }

    @Override
    public void applyToolChoice(OllamaRequest request, ToolChoice toolChoice) {
        toolsHelper.applyToolChoice(request, toolChoice);
    }

    /**
     * 为 API 调用构建完整的 OllamaRequest。
     *
     * @param model 模型名称
     * @param messages 格式化后的 Ollama 消息
     * @param stream 是否启用流式处理
     * @param options 生成选项
     * @param defaultOptions 默认生成选项
     * @param tools 工具 schema
     * @param toolChoice 工具选择配置
     * @return 可用于 API 调用的完整 OllamaRequest
     */
    public OllamaRequest buildRequest(
            String model,
            List<OllamaMessage> messages,
            boolean stream,
            OllamaOptions options,
            OllamaOptions defaultOptions,
            List<ToolSchema> tools,
            ToolChoice toolChoice) {

        OllamaRequest request = new OllamaRequest();
        request.setModel(model);
        request.setMessages(messages);
        request.setStream(stream);

        // 应用选项
        applyOptions(request, options, defaultOptions);

        // 存在工具时应用工具
        if (tools != null && !tools.isEmpty()) {
            applyTools(request, tools);
            if (toolChoice != null) {
                applyToolChoice(request, toolChoice);
            }
        }

        return request;
    }

    // ========== 私有辅助方法 ==========

    /**
     * 检查消息是否与工具相关。
     *
     * @param msg 待检查的消息
     * @return 工具消息返回 true，否则返回 false
     */
    private boolean isToolMessage(Msg msg) {
        return msg.getRole() == MsgRole.TOOL
                || msg.hasContentBlocks(ToolUseBlock.class)
                || msg.hasContentBlocks(ToolResultBlock.class);
    }

    /**
     * 按顺序将消息分为 agent_message 与 tool_sequence 组。
     *
     * @param msgs 待分组的消息（不含系统消息）
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

    /**
     * 采用与 OllamaChatFormatter 相同的逻辑，将工具序列消息格式化为 OllamaMessage。
     *
     * @param msgs 待格式化的消息列表
     * @return 格式化后的 OllamaMessage 对象列表
     */
    private List<OllamaMessage> formatToolSeq(List<Msg> msgs) {
        List<OllamaMessage> result = new ArrayList<>();
        for (Msg msg : msgs) {
            // 根据消息角色与内容转换消息
            OllamaMessage ollamaMsg = messageConverter.convertMessage(msg);

            // 根据消息角色设置角色
            if (msg.getRole() == MsgRole.TOOL) {
                ollamaMsg.setRole("tool");
            } else {
                ollamaMsg.setRole(msg.getRole().name().toLowerCase());
            }

            // 对于工具消息，需要正确提取内容
            if (msg.getRole() == MsgRole.TOOL) {
                // 特殊处理工具结果消息
                for (ContentBlock block : msg.getContent()) {
                    if (block instanceof ToolResultBlock) {
                        ToolResultBlock toolResultBlock = (ToolResultBlock) block;
                        ollamaMsg.setToolCallId(toolResultBlock.getId());
                        ollamaMsg.setName(toolResultBlock.getName());

                        // 从工具结果提取文本输出
                        StringBuilder textualOutput = new StringBuilder();
                        List<ContentBlock> multimodalOutputs = new ArrayList<>();

                        for (ContentBlock outputBlock : toolResultBlock.getOutput()) {
                            if (outputBlock instanceof TextBlock) {
                                if (textualOutput.length() > 0) {
                                    textualOutput.append("\n");
                                }
                                textualOutput.append(((TextBlock) outputBlock).getText());
                            } else if (outputBlock instanceof ImageBlock) {
                                multimodalOutputs.add(outputBlock);
                            }
                        }

                        // 若有多模态输出（例如图像），可能需要
                        // 特殊处理
                        // 目前仅包含文本内容
                        ollamaMsg.setContent(textualOutput.toString());

                        // 检查是否有需要提升的图像
                        if (!multimodalOutputs.isEmpty() && promoteToolResultImages) {
                            // 向内容添加可由
                            // isToolResultWithImages 检测的特殊文本
                            for (ContentBlock multimodalBlock : multimodalOutputs) {
                                if (multimodalBlock instanceof ImageBlock) {
                                    ImageBlock imageBlock = (ImageBlock) multimodalBlock;
                                    String imageUrl = imageBlock.getSource().toString();
                                    if (imageBlock.getSource() instanceof URLSource) {
                                        imageUrl = ((URLSource) imageBlock.getSource()).getUrl();
                                    }
                                    ollamaMsg.setContent(
                                            ollamaMsg.getContent()
                                                    + "\nimage can be found at: "
                                                    + imageUrl);
                                }
                            }
                        }
                        break; // 处理第一个工具结果块
                    }
                }
            }

            result.add(ollamaMsg);
        }
        return result;
    }

    /**
     * 将内容块列表（通常来自工具结果）转换为字符串。
     *
     * @param blocks 待转换的内容块列表
     * @return 内容块的字符串表示
     */
    public String convertToolResultToString(List<ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock) {
                sb.append(((TextBlock) block).getText());
            } else if (block instanceof ToolResultBlock) {
                // 从 ToolResultBlock 输出块提取文本
                List<ContentBlock> outputBlocks = ((ToolResultBlock) block).getOutput();
                for (ContentBlock outputBlock : outputBlocks) {
                    if (outputBlock instanceof TextBlock) {
                        sb.append(((TextBlock) outputBlock).getText());
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * 用于按顺序分组消息的分组类型枚举。
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

    /**
     * 保存已分类内容块的辅助类。
     */
    private static class MessageContent {
        List<ContentBlock> textBlocks = new ArrayList<>();
        List<ContentBlock> toolUseBlocks = new ArrayList<>();
        List<ContentBlock> toolResultBlocks = new ArrayList<>();
        List<String> images = new ArrayList<>();
    }
}
