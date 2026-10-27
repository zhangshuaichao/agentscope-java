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

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.extensions.model.dashscope.dto.DashScopeContentPart;
import io.agentscope.extensions.model.dashscope.dto.DashScopeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 为 DashScope API 合并多智能体对话消息。
 *
 * <p>将多条智能体消息整合成单条消息，对话历史包裹在特殊标签内，同时保留智能体名称、角色及多模态内容（图像/视频）。
 */
public class DashScopeConversationMerger {

    private static final Logger log = LoggerFactory.getLogger(DashScopeConversationMerger.class);

    private static final String HISTORY_START_TAG = "<history>";
    private static final String HISTORY_END_TAG = "</history>";

    private final DashScopeMediaConverter mediaConverter;
    private final String conversationHistoryPrompt;

    /**
     * 使用自定义对话历史提示创建 DashScopeConversationMerger。
     *
     * @param conversationHistoryPrompt 放在对话历史之前的提示
     */
    public DashScopeConversationMerger(String conversationHistoryPrompt) {
        this.mediaConverter = new DashScopeMediaConverter();
        this.conversationHistoryPrompt = conversationHistoryPrompt;
    }

    /**
     * 将对话消息合并为单个 DashScopeMessage（文本模式）。
     *
     * <p>将全部智能体消息合并为一条用户消息，历史包裹在 {@code <history>} 标签内，智能体名称和角色嵌入文本。
     *
     * @param msgs 待合并的对话消息列表
     * @param nameExtractor 从消息提取智能体名称的函数
     * @param toolResultConverter 将工具结果块转换为字符串的函数
     * @param historyPrompt 前置提示（非首组时为空）
     * @return 合并后的单个 DashScopeMessage
     */
    public DashScopeMessage mergeToMessage(
            List<Msg> msgs,
            Function<Msg, String> nameExtractor,
            Function<List<ContentBlock>, String> toolResultConverter,
            String historyPrompt) {

        // 构建包含智能体名称的文本对话历史。
        StringBuilder textAccumulator = new StringBuilder();
        if (!historyPrompt.isEmpty()) {
            textAccumulator.append(historyPrompt);
        }
        textAccumulator.append(HISTORY_START_TAG).append("\n");

        // 单独收集图像以支持多模态。
        List<DashScopeContentPart> imageContents = new ArrayList<>();

        for (Msg msg : msgs) {
            String name = nameExtractor.apply(msg);

            List<ContentBlock> blocks = msg.getContent();
            for (ContentBlock block : blocks) {
                if (block instanceof TextBlock tb) {
                    textAccumulator.append(name).append(": ").append(tb.getText()).append("\n");
                } else if (block instanceof HintBlock hb) {
                    textAccumulator.append(name).append(": ").append(hb.getHint()).append("\n");

                } else if (block instanceof ImageBlock imageBlock) {
                    // 保留图像以构造多模态内容。
                    try {
                        DashScopeContentPart imageContent =
                                mediaConverter.convertImageBlockToContentPart(imageBlock);
                        imageContents.add(imageContent);
                        textAccumulator.append(name).append(": [Image]\n");
                    } catch (Exception e) {
                        log.warn("Failed to process ImageBlock: {}", e.getMessage());
                        textAccumulator.append(name).append(": [Image - processing failed]\n");
                    }

                } else if (block instanceof VideoBlock videoBlock) {
                    try {
                        String videoUrl = mediaConverter.convertVideoBlockToUrl(videoBlock);
                        // 将视频 URL 加入文本。
                        textAccumulator
                                .append(name)
                                .append(": [Video: ")
                                .append(videoUrl)
                                .append("]\n");
                        log.debug("Processed VideoBlock in multi-agent conversation: {}", videoUrl);
                    } catch (Exception e) {
                        log.warn("Failed to process VideoBlock: {}", e.getMessage());
                        textAccumulator.append(name).append(": [Video - processing failed]\n");
                    }

                } else if (block instanceof DataBlock dataBlock) {
                    try {
                        DashScopeContentPart dataPart =
                                mediaConverter.convertDataBlockToContentPart(dataBlock);
                        imageContents.add(dataPart);
                        textAccumulator.append(name).append(": [Data]\n");
                    } catch (Exception e) {
                        log.warn("Failed to process DataBlock: {}", e.getMessage());
                        textAccumulator.append(name).append(": [Data - processing failed]\n");
                    }

                } else if (block instanceof ThinkingBlock) {
                    log.debug("Skipping ThinkingBlock in multi-agent conversation");

                } else if (block instanceof ToolResultBlock toolResult) {
                    // 使用提供的转换器处理工具结果中的多模态内容。
                    String resultText = toolResultConverter.apply(toolResult.getOutput());
                    String finalResultText =
                            !resultText.isEmpty() ? resultText : "[Empty tool result]";
                    textAccumulator
                            .append(name)
                            .append(" (")
                            .append(toolResult.getName())
                            .append("): ")
                            .append(finalResultText)
                            .append("\n");
                }
            }
        }

        textAccumulator.append(HISTORY_END_TAG);

        // 必要时构建含多模态内容的消息。
        if (imageContents.isEmpty()) {
            // 没有图像，使用简单文本格式。
            return DashScopeMessage.builder()
                    .role("user")
                    .content(textAccumulator.toString())
                    .build();
        } else {
            // 存在图像，使用内容列表多模态格式。
            List<DashScopeContentPart> contents = new ArrayList<>();
            // 先加入文本对话历史。
            contents.add(DashScopeContentPart.text(textAccumulator.toString()));
            // 再加入全部图像。
            contents.addAll(imageContents);

            return DashScopeMessage.builder().role("user").content(contents).build();
        }
    }

    /**
     * 将对话消息合并为单个 DashScopeMessage（多模态模式）。
     *
     * <p>全部智能体消息合并为一条用户消息，历史包裹在 {@code <history>} 标签内。图像与视频在多模态格式中保留为独立内容片段。
     *
     * @param msgs 待合并的对话消息列表
     * @param nameExtractor 从消息提取智能体名称的函数
     * @param toolResultConverter 将工具结果块转换为字符串的函数
     * @param isFirst 是否为首个智能体消息组（true 时加入历史提示）
     * @return 含多模态内容的合并 DashScopeMessage
     */
    public DashScopeMessage mergeToMultiModalMessage(
            List<Msg> msgs,
            Function<Msg, String> nameExtractor,
            Function<List<ContentBlock>, String> toolResultConverter,
            boolean isFirst) {

        List<DashScopeContentPart> content = new ArrayList<>();
        List<String> accumulatedText = new ArrayList<>();

        // 加入对话历史提示（仅首个智能体消息组）。
        if (isFirst) {
            accumulatedText.add(conversationHistoryPrompt + HISTORY_START_TAG);
        } else {
            accumulatedText.add(HISTORY_START_TAG);
        }

        for (Msg msg : msgs) {
            String name = nameExtractor.apply(msg);

            for (ContentBlock block : msg.getContent()) {
                if (block instanceof TextBlock tb) {
                    accumulatedText.add(name + ": " + tb.getText());
                } else if (block instanceof HintBlock hb) {
                    accumulatedText.add(name + ": " + hb.getHint());

                } else if (block instanceof ImageBlock imageBlock) {
                    // 添加图像前先输出累计文本。
                    if (!accumulatedText.isEmpty()) {
                        content.add(DashScopeContentPart.text(String.join("\n", accumulatedText)));
                        accumulatedText.clear();
                    }

                    // 将图像加入为独立内容片段。
                    try {
                        DashScopeContentPart imagePart =
                                mediaConverter.convertImageBlockToContentPart(imageBlock);
                        content.add(imagePart);
                    } catch (Exception e) {
                        log.warn("Failed to process ImageBlock in multimodal: {}", e.getMessage());
                        content.add(
                                DashScopeContentPart.text(
                                        "[Image - processing failed: " + e.getMessage() + "]"));
                    }

                } else if (block instanceof AudioBlock audioBlock) {
                    // 添加音频前先输出累计文本。
                    if (!accumulatedText.isEmpty()) {
                        content.add(DashScopeContentPart.text(String.join("\n", accumulatedText)));
                        accumulatedText.clear();
                    }

                    // 将音频加入为独立内容片段。
                    try {
                        DashScopeContentPart audioPart =
                                mediaConverter.convertAudioBlockToContentPart(audioBlock);
                        content.add(audioPart);
                    } catch (Exception e) {
                        log.warn("Failed to process AudioBlock in multimodal: {}", e.getMessage());
                        content.add(
                                DashScopeContentPart.text(
                                        "[Audio - processing failed: " + e.getMessage() + "]"));
                    }

                } else if (block instanceof VideoBlock videoBlock) {
                    // 添加视频前先输出累计文本。
                    if (!accumulatedText.isEmpty()) {
                        content.add(DashScopeContentPart.text(String.join("\n", accumulatedText)));
                        accumulatedText.clear();
                    }

                    // 将视频加入为独立内容片段。
                    try {
                        DashScopeContentPart videoPart =
                                mediaConverter.convertVideoBlockToContentPart(videoBlock);
                        content.add(videoPart);
                    } catch (Exception e) {
                        log.warn("Failed to process VideoBlock in multimodal: {}", e.getMessage());
                        content.add(
                                DashScopeContentPart.text(
                                        "[Video - processing failed: " + e.getMessage() + "]"));
                    }

                } else if (block instanceof DataBlock dataBlock) {
                    // 添加数据块前先输出累计文本。
                    if (!accumulatedText.isEmpty()) {
                        content.add(DashScopeContentPart.text(String.join("\n", accumulatedText)));
                        accumulatedText.clear();
                    }

                    try {
                        DashScopeContentPart dataPart =
                                mediaConverter.convertDataBlockToContentPart(dataBlock);
                        content.add(dataPart);
                    } catch (Exception e) {
                        log.warn("Failed to process DataBlock in multimodal: {}", e.getMessage());
                        content.add(
                                DashScopeContentPart.text(
                                        "[Media - processing failed: " + e.getMessage() + "]"));
                    }

                } else if (block instanceof ThinkingBlock) {
                    log.debug("Skipping ThinkingBlock in multi-agent multimodal formatting");

                } else if (block instanceof ToolResultBlock) {
                    // 工具结果不应出现在智能体消息中，
                    // 它们由 tool_sequence 组单独处理。
                    log.warn("Unexpected ToolResultBlock in agent message group, skipping");
                }
            }
        }

        // 关闭历史标签并输出剩余文本。
        accumulatedText.add(HISTORY_END_TAG);
        if (!accumulatedText.isEmpty()) {
            content.add(DashScopeContentPart.text(String.join("\n", accumulatedText)));
        }

        // 内容为空时加入空文本。
        if (content.isEmpty()) {
            content.add(DashScopeContentPart.text(""));
        }

        return DashScopeMessage.builder().role("user").content(content).build();
    }
}
