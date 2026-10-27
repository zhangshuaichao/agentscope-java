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

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.extensions.model.ollama.dto.OllamaFunction;
import io.agentscope.extensions.model.ollama.dto.OllamaMessage;
import io.agentscope.extensions.model.ollama.dto.OllamaToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 AgentScope Msg 对象转换为 OllamaMessage 对象的转换器。
 */
public class OllamaMessageConverter {

    private static final Logger log = LoggerFactory.getLogger(OllamaMessageConverter.class);
    private final OllamaMediaConverter mediaConverter = new OllamaMediaConverter();

    /**
     * 将 AgentScope Msg 对象转换为 OllamaMessage 对象。
     *
     * @param msg 待转换的 AgentScope 消息
     * @return 转换后的 OllamaMessage 对象
     */
    public OllamaMessage convertMessage(Msg msg) {
        OllamaMessage ollamaMsg = new OllamaMessage();

        // 1. 映射角色并处理工具结果
        if (msg.getRole() == MsgRole.TOOL) {
            ollamaMsg.setRole("tool");
            // 处理工具结果
            ToolResultBlock toolResult = msg.getFirstContentBlock(ToolResultBlock.class);
            if (toolResult != null) {
                // 从输出块提取文本内容和图像路径
                StringBuilder contentBuilder = new StringBuilder();

                // 添加文本内容
                String textContent = extractTextContentFromBlocks(toolResult.getOutput());
                if (textContent != null && !textContent.isEmpty()) {
                    contentBuilder.append(textContent);
                }

                // 按提升逻辑要求的格式添加图像信息
                List<String> imagePaths = extractImagePathsFromBlocks(toolResult.getOutput());
                if (!imagePaths.isEmpty()) {
                    if (contentBuilder.length() > 0) {
                        contentBuilder.append("\n");
                    }
                    for (String imagePath : imagePaths) {
                        contentBuilder
                                .append("- The returned image can be found at: ")
                                .append(imagePath)
                                .append("\n");
                    }
                }

                ollamaMsg.setContent(contentBuilder.toString().trim());

                // 可用时设置 tool_call_id 和 name
                if (toolResult.getId() != null) {
                    ollamaMsg.setToolCallId(toolResult.getId());
                }
                if (toolResult.getName() != null) {
                    ollamaMsg.setName(toolResult.getName());
                }
            } else {
                // 没有 ToolResultBlock 时的回退处理
                ollamaMsg.setContent(extractTextContent(msg));
            }
            return ollamaMsg;
        }

        // 标准角色映射
        ollamaMsg.setRole(msg.getRole().name().toLowerCase());

        // 2. 映射内容与工具调用（Assistant）
        StringBuilder textContent = new StringBuilder();
        StringBuilder thinkingContent = new StringBuilder();
        List<String> images = new ArrayList<>();
        List<OllamaToolCall> toolCalls = new ArrayList<>();

        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock) {
                textContent.append(((TextBlock) block).getText());
            } else if (block instanceof ThinkingBlock thinkingBlock) {
                if (thinkingBlock.getThinking() != null) {
                    thinkingContent.append(thinkingBlock.getThinking());
                }
            } else if (block instanceof ImageBlock) {
                ImageBlock imageBlock = (ImageBlock) block;
                try {
                    String base64Image = mediaConverter.convertImageBlockToBase64(imageBlock);
                    images.add(base64Image);
                } catch (Exception e) {
                    log.error("Failed to convert image block to Ollama format", e);
                    // 决定是直接失败还是跳过。
                    // 当前记录错误并跳过，避免整个请求崩溃，
                    // 但图像通常是重要上下文。
                }
            } else if (block instanceof ToolUseBlock) {
                ToolUseBlock toolUse = (ToolUseBlock) block;
                // 将 ToolUseBlock 转换为 OllamaToolCall
                Map<String, Object> args = toolUse.getInput();

                OllamaFunction function = new OllamaFunction(toolUse.getName(), args);
                OllamaToolCall toolCall = new OllamaToolCall(function);
                // 可用时设置 ID
                if (toolUse.getId() != null) {
                    // 注意：OllamaToolCall 可能没有直接的 ID 字段，这取决于 DTO
                    // 结构
                    // 可能需要根据 Ollama API 要求采用其他处理方式
                }
                toolCalls.add(toolCall);
            }
        }

        if (textContent.length() > 0) {
            ollamaMsg.setContent(textContent.toString());
        }

        if (thinkingContent.length() > 0) {
            ollamaMsg.setThinking(thinkingContent.toString());
        }

        if (!images.isEmpty()) {
            ollamaMsg.setImages(images);
        }

        if (!toolCalls.isEmpty()) {
            ollamaMsg.setToolCalls(toolCalls);
        }

        return ollamaMsg;
    }

    /**
     * 合并所有文本块与工具结果输出，从消息提取文本内容。
     *
     * @param msg 从中提取文本内容的消息
     * @return 合并后的文本内容字符串
     */
    private String extractTextContent(Msg msg) {
        return msg.getContent().stream()
                .flatMap(
                        block -> {
                            if (block instanceof TextBlock tb) {
                                return Stream.of(tb.getText());
                            } else if (block instanceof HintBlock hb) {
                                return Stream.of(hb.getHint());
                            } else if (block instanceof ToolResultBlock toolResult) {
                                return toolResult.getOutput().stream()
                                        .filter(output -> output instanceof TextBlock)
                                        .map(output -> ((TextBlock) output).getText());
                            }
                            return Stream.empty();
                        })
                .collect(Collectors.joining("\n"));
    }

    /**
     * 从内容块列表提取文本内容，包括工具结果输出中的文本。
     *
     * @param blocks 从中提取文本的内容块列表
     * @return 合并后的文本内容字符串
     */
    private String extractTextContentFromBlocks(List<ContentBlock> blocks) {
        return blocks.stream()
                .flatMap(
                        block -> {
                            if (block instanceof TextBlock tb) {
                                return Stream.of(tb.getText());
                            } else if (block instanceof HintBlock hb) {
                                return Stream.of(hb.getHint());
                            } else if (block instanceof ToolResultBlock toolResult) {
                                return toolResult.getOutput().stream()
                                        .filter(output -> output instanceof TextBlock)
                                        .map(output -> ((TextBlock) output).getText());
                            }
                            return Stream.empty();
                        })
                .collect(Collectors.joining("\n"));
    }

    /**
     * 从内容块列表提取图像路径。
     *
     * @param blocks 从中提取图像路径的内容块列表
     * @return 图像路径字符串列表
     */
    private List<String> extractImagePathsFromBlocks(List<ContentBlock> blocks) {
        List<String> imagePaths = new ArrayList<>();

        for (ContentBlock block : blocks) {
            if (block instanceof ImageBlock) {
                ImageBlock imageBlock = (ImageBlock) block;
                if (imageBlock.getSource() != null) {
                    // 从来源中提取 URL 路径
                    String imagePath = imageBlock.getSource().toString();
                    if (imageBlock.getSource() instanceof URLSource) {
                        imagePath = ((URLSource) imageBlock.getSource()).getUrl();
                    }
                    imagePaths.add(imagePath);
                }
            }
        }

        return imagePaths;
    }
}
