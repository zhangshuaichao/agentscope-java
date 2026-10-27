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
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.ollama.dto.OllamaFunction;
import io.agentscope.extensions.model.ollama.dto.OllamaMessage;
import io.agentscope.extensions.model.ollama.dto.OllamaRequest;
import io.agentscope.extensions.model.ollama.dto.OllamaResponse;
import io.agentscope.extensions.model.ollama.dto.OllamaToolCall;
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
 * Ollama Chat API 的格式化器，在 AgentScope Msg 对象与 Ollama DTO 类型之间转换。
 */
public class OllamaChatFormatter
        extends AbstractBaseFormatter<OllamaMessage, OllamaResponse, OllamaRequest> {
    private static final Logger log = LoggerFactory.getLogger(OllamaChatFormatter.class);

    private final OllamaMessageConverter messageConverter;
    private final OllamaResponseParser responseParser;
    private final OllamaToolsHelper toolsHelper;
    private final boolean promoteToolResultImages;

    public OllamaChatFormatter() {
        this(false);
    }

    public OllamaChatFormatter(boolean promoteToolResultImages) {
        this.messageConverter = new OllamaMessageConverter();
        this.responseParser = new OllamaResponseParser();
        this.toolsHelper = new OllamaToolsHelper();
        this.promoteToolResultImages = promoteToolResultImages;
    }

    @Override
    protected List<OllamaMessage> doFormat(List<Msg> msgs) {
        List<OllamaMessage> result = new ArrayList<>();

        for (Msg msg : msgs) {
            // 逐条处理消息并添加到结果中
            processMessage(msg, result);
        }

        return result;
    }

    /**
     * 处理单条消息，将对应的一个或多个 OllamaMessage 添加到结果列表。
     *
     * @param msg 待处理的消息
     * @param result 接收格式化消息的列表
     */
    private void processMessage(Msg msg, List<OllamaMessage> result) {
        // 按类型分离内容块
        MessageContent messageContent = separateContentBlocks(msg.getContent());

        // 先处理工具结果块（直接添加到结果中）
        if (messageContent.toolResultBlocks != null) {
            for (ContentBlock toolResultBlock : messageContent.toolResultBlocks) {
                processToolResultBlock((ToolResultBlock) toolResultBlock, result);
            }
        }

        // 存在内容、思考、图像或工具调用时，创建并添加主消息
        if (!messageContent.textBlocks.isEmpty()
                || !messageContent.thinkingBlocks.isEmpty()
                || !messageContent.images.isEmpty()
                || !messageContent.toolUseBlocks.isEmpty()) {

            OllamaMessage mainMessage =
                    createMainOllamaMessage(
                            msg,
                            messageContent.textBlocks,
                            messageContent.thinkingBlocks,
                            messageContent.toolUseBlocks,
                            messageContent.images);

            if (mainMessage.getContent() != null
                    || mainMessage.getThinking() != null
                    || mainMessage.getImages() != null
                    || mainMessage.getToolCalls() != null) {
                result.add(mainMessage);
            }
        }
    }

    /**
     * 将内容块按不同类型分离，便于处理。
     *
     * @param contentBlocks 待分离的内容块列表
     * @return 包含已分类内容块的 MessageContent 对象
     */
    private MessageContent separateContentBlocks(List<ContentBlock> contentBlocks) {
        MessageContent messageContent = new MessageContent();

        for (ContentBlock block : contentBlocks) {
            if (block instanceof TextBlock) {
                messageContent.textBlocks.add(block);
            } else if (block instanceof ThinkingBlock) {
                messageContent.thinkingBlocks.add(block);
            } else if (block instanceof ToolUseBlock) {
                messageContent.toolUseBlocks.add(block);
            } else if (block instanceof ToolResultBlock) {
                messageContent.toolResultBlocks.add(block);
            } else if (block instanceof ImageBlock) {
                processImageBlock((ImageBlock) block, messageContent.images);
            } else {
                log.warn(
                        "Unsupported block type {} in the message, skipped.",
                        block.getClass().getSimpleName());
            }
        }

        return messageContent;
    }

    /**
     * 处理工具使用块并转换为 OllamaToolCall。
     *
     * @param block 待处理的工具使用块
     * @param toolCalls 接收已转换工具调用的列表
     */
    private void processToolUseBlock(ContentBlock block, List<OllamaToolCall> toolCalls) {
        ToolUseBlock toolUseBlock = (ToolUseBlock) block;
        OllamaFunction function =
                new OllamaFunction(toolUseBlock.getName(), toolUseBlock.getInput());
        OllamaToolCall toolCall = new OllamaToolCall(function);
        toolCalls.add(toolCall);
    }

    /**
     * 处理工具结果块，将对应的 OllamaMessage 添加到结果中。
     *
     * @param toolResultBlock 待处理的工具结果块
     * @param result 接收格式化消息的列表
     */
    private void processToolResultBlock(
            ToolResultBlock toolResultBlock, List<OllamaMessage> result) {
        // 创建工具结果消息
        OllamaMessage toolResultMsg = new OllamaMessage();
        toolResultMsg.setRole("tool");
        toolResultMsg.setToolCallId(toolResultBlock.getId());
        toolResultMsg.setName(toolResultBlock.getName());

        // 从工具结果输出中提取文本输出
        StringBuilder textualOutput = new StringBuilder();
        List<ContentBlock> multimodalData = new ArrayList<>();

        for (ContentBlock outputBlock : toolResultBlock.getOutput()) {
            if (outputBlock instanceof TextBlock) {
                if (textualOutput.length() > 0) {
                    textualOutput.append("\n");
                }
                textualOutput.append(((TextBlock) outputBlock).getText());
            } else if (outputBlock instanceof ImageBlock) {
                multimodalData.add(outputBlock);
            }
        }

        toolResultMsg.setContent(textualOutput.toString());
        result.add(toolResultMsg);

        // 按需处理多模态数据提升
        if (promoteToolResultImages && !multimodalData.isEmpty()) {
            handleImagePromotion(toolResultBlock, multimodalData, result);
        }
    }

    /**
     * 处理工具结果中的图像提升。
     *
     * @param toolResultBlock 原始工具结果块
     * @param multimodalData 待提升的多模态数据
     * @param result 接收提升后图像消息的列表
     */
    private void handleImagePromotion(
            ToolResultBlock toolResultBlock,
            List<ContentBlock> multimodalData,
            List<OllamaMessage> result) {
        List<ContentBlock> promotedBlocks = new ArrayList<>();
        for (ContentBlock multimodalBlock : multimodalData) {
            if (multimodalBlock instanceof ImageBlock) {
                ImageBlock imageBlock = (ImageBlock) multimodalBlock;
                // 添加包含图像信息的文本块
                String imageUrl = imageBlock.getSource().toString();
                if (imageBlock.getSource() instanceof URLSource) {
                    imageUrl = ((URLSource) imageBlock.getSource()).getUrl();
                }
                promotedBlocks.add(
                        new TextBlock.Builder()
                                .text("\n- The image from '" + imageUrl + "': ")
                                .build());
                promotedBlocks.add(imageBlock);
            }
        }

        if (!promotedBlocks.isEmpty()) {
            // 创建包含系统信息和提升后内容块的新用户消息
            List<ContentBlock> allPromotedBlocks = new ArrayList<>();
            allPromotedBlocks.add(
                    new TextBlock.Builder()
                            .text(
                                    "<system-info>The following are "
                                            + "the image contents from the tool "
                                            + "result of '"
                                            + toolResultBlock.getName()
                                            + "':")
                            .build());
            allPromotedBlocks.addAll(promotedBlocks);
            allPromotedBlocks.add(new TextBlock.Builder().text("</system-info>").build());

            // 创建临时消息，并使用构建器转换为 OllamaMessage 格式
            Msg tempMsg =
                    new Msg.Builder()
                            .name("user")
                            .role(MsgRole.USER)
                            .content(allPromotedBlocks)
                            .build();
            OllamaMessage imagePromotionMsg = messageConverter.convertMessage(tempMsg);
            result.add(imagePromotionMsg);
        }
    }

    /**
     * 处理图像块并转换为 Base64 字符串。
     *
     * @param block 待处理的图像块
     * @param images 接收已转换 Base64 图像的列表
     */
    private void processImageBlock(ImageBlock block, List<String> images) {
        try {
            String base64Image = new OllamaMediaConverter().convertImageBlockToBase64(block);
            images.add(base64Image);
        } catch (Exception e) {
            log.warn("Failed to convert image block to Ollama format", e);
        }
    }

    /**
     * 根据文本／思考内容、工具调用和图像创建主 OllamaMessage。
     *
     * @param msg 原始消息
     * @param textBlocks 文本内容块
     * @param thinkingBlocks 思考内容块
     * @param toolUseBlocks 工具使用块
     * @param images Base64 编码的图像
     * @return 格式化后的 OllamaMessage
     */
    private OllamaMessage createMainOllamaMessage(
            Msg msg,
            List<ContentBlock> textBlocks,
            List<ContentBlock> thinkingBlocks,
            List<ContentBlock> toolUseBlocks,
            List<String> images) {
        // 根据工具使用块创建工具调用
        List<OllamaToolCall> toolCalls = new ArrayList<>();
        for (ContentBlock block : toolUseBlocks) {
            processToolUseBlock(block, toolCalls);
        }

        // 合并文本内容
        StringBuilder contentMsg = new StringBuilder();
        for (int j = 0; j < textBlocks.size(); j++) {
            if (j > 0) contentMsg.append("\n");
            contentMsg.append(((TextBlock) textBlocks.get(j)).getText());
        }

        StringBuilder thinkingMsg = new StringBuilder();
        for (ContentBlock block : thinkingBlocks) {
            String thinking = ((ThinkingBlock) block).getThinking();
            if (thinking != null) {
                thinkingMsg.append(thinking);
            }
        }

        OllamaMessage msgOllama = new OllamaMessage();
        msgOllama.setRole(msg.getRole().name().toLowerCase());
        msgOllama.setContent(contentMsg.length() > 0 ? contentMsg.toString() : null);
        msgOllama.setThinking(thinkingMsg.length() > 0 ? thinkingMsg.toString() : null);

        if (!images.isEmpty()) {
            msgOllama.setImages(images);
        }

        if (!toolCalls.isEmpty()) {
            msgOllama.setToolCalls(toolCalls);
        }

        return msgOllama;
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
        // 查找格式为 "image can be found at: ./path" 的图像路径
        String content = convertedMsg.getContent();

        // 在内容中查找图像路径
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

        OllamaRequest.Builder builder =
                OllamaRequest.builder().model(model).messages(messages).stream(stream);

        OllamaRequest request = builder.build();

        applyOptions(request, options, defaultOptions);
        if (tools != null && !tools.isEmpty()) {
            applyTools(request, tools);
        }
        applyToolChoice(request, toolChoice);

        return request;
    }

    /**
     * 保存已分类内容块的辅助类。
     */
    private static class MessageContent {
        List<ContentBlock> textBlocks = new ArrayList<>();
        List<ContentBlock> thinkingBlocks = new ArrayList<>();
        List<ContentBlock> toolUseBlocks = new ArrayList<>();
        List<ContentBlock> toolResultBlocks = new ArrayList<>();
        List<String> images = new ArrayList<>();
    }
}
