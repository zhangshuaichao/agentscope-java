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

import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.extensions.model.openai.dto.OpenAIContentPart;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 为 OpenAI HTTP API 合并多智能体对话消息，将多个智能体的消息整合为带历史标签的单条用户消息。
 *
 * <p>所有智能体消息合并后，对话历史由特殊标签包裹；图像与音频保留为独立 ContentPart。
 */
public class OpenAIConversationMerger {

    private static final Logger log = LoggerFactory.getLogger(OpenAIConversationMerger.class);

    private static final String HISTORY_START_TAG = "<history>";
    private static final String HISTORY_END_TAG = "</history>";

    private final String conversationHistoryPrompt;

    /**
     * 使用自定义对话历史提示词创建 OpenAIConversationMerger。
     *
     * @param conversationHistoryPrompt 对话历史之前添加的提示词
     */
    public OpenAIConversationMerger(String conversationHistoryPrompt) {
        this.conversationHistoryPrompt = conversationHistoryPrompt;
    }

    /**
     * 将对话消息合并为单条 OpenAIMessage。
     *
     * <p>将所有智能体消息合并为单条用户消息，对话历史用 {@code <history>} 标签包裹。
     * 图像和音频以多模态格式中的独立 ContentPart 保留。
     *
     * @param msgs 待合并的对话消息列表
     * @param roleFormatter 角色标签格式化函数，例如 USER → "User"
     * @param toolResultConverter 将工具结果块转换为字符串的函数
     * @return 供 OpenAI API 使用的单条合并消息
     */
    public OpenAIMessage mergeToUserMessage(
            List<Msg> msgs,
            Function<Msg, String> roleFormatter,
            Function<List<ContentBlock>, String> toolResultConverter) {

        // 保存交错内容片段的列表，包含文本、图像与音频。
        List<OpenAIContentPart> allParts = new ArrayList<>();

        // 累积文本片段的缓冲区。
        StringBuilder textBuffer = new StringBuilder();
        textBuffer.append(conversationHistoryPrompt);
        textBuffer.append(HISTORY_START_TAG).append("\n");
        // 仅在存在历史，即多轮上下文时添加前缀。
        // 对于单轮用户查询，
        // 省略前缀可以保持标准聊天请求的形式。
        boolean includePrefix = msgs.size() > 1;
        // 将全部消息作为历史处理，与 DashScopeConversationMerger 类似。
        for (Msg msg : msgs) {
            processMessage(
                    msg, roleFormatter, toolResultConverter, textBuffer, allParts, includePrefix);
        }
        textBuffer.append(HISTORY_END_TAG).append("\n");

        // 输出剩余文本。
        if (textBuffer.length() > 0) {
            allParts.add(OpenAIContentPart.text(textBuffer.toString()));
        }

        return OpenAIMessage.builder().role("user").content(allParts).build();
    }

    private void processMessage(
            Msg msg,
            Function<Msg, String> roleFormatter,
            Function<List<ContentBlock>, String> toolResultConverter,
            StringBuilder textBuffer,
            List<OpenAIContentPart> allParts,
            boolean includePrefix) {
        String agentName = msg.getName();

        // 处理全部内容块。
        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null) {
            blocks = new ArrayList<>();
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

            } else if (block instanceof ImageBlock imageBlock) {
                // 将已有文本输出为内容片段。
                if (textBuffer.length() > 0) {
                    allParts.add(OpenAIContentPart.text(textBuffer.toString()));
                    textBuffer.setLength(0); // 清空缓冲区。
                }

                // 处理图像。
                try {
                    Source source = imageBlock.getSource();
                    if (source == null) {
                        log.warn("ImageBlock has null source, skipping");
                        if (includePrefix) {
                            appendNamePrefix(textBuffer, agentName);
                        }
                        textBuffer.append("[Image - null source]\n");
                    } else {
                        String imageUrl = convertImageSourceToUrl(source);
                        allParts.add(OpenAIContentPart.imageUrl(imageUrl));
                    }
                } catch (Exception e) {
                    String errorMsg =
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warn("Failed to process ImageBlock: {}", errorMsg);
                    if (includePrefix) {
                        appendNamePrefix(textBuffer, agentName);
                    }
                    textBuffer
                            .append("[Image - processing failed: ")
                            .append(errorMsg)
                            .append("]\n");
                }

            } else if (block instanceof VideoBlock videoBlock) {
                // 将已有文本输出为内容片段。
                if (textBuffer.length() > 0) {
                    allParts.add(OpenAIContentPart.text(textBuffer.toString()));
                    textBuffer.setLength(0); // 清空缓冲区。
                }

                // 处理视频。
                try {
                    Source source = videoBlock.getSource();
                    if (source == null) {
                        log.warn("VideoBlock has null source, skipping");
                        if (includePrefix) {
                            appendNamePrefix(textBuffer, agentName);
                        }
                        textBuffer.append("[Video - null source]\n");
                    } else {
                        String videoUrl = convertVideoSourceToUrl(source);
                        allParts.add(OpenAIContentPart.videoUrl(videoUrl));
                    }
                } catch (Exception e) {
                    String errorMsg =
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warn("Failed to process VideoBlock: {}", errorMsg);
                    if (includePrefix) {
                        appendNamePrefix(textBuffer, agentName);
                    }
                    textBuffer
                            .append("[Video - processing failed: ")
                            .append(errorMsg)
                            .append("]\n");
                }

            } else if (block instanceof AudioBlock audioBlock) {
                // 输出已有文本。
                if (textBuffer.length() > 0) {
                    allParts.add(OpenAIContentPart.text(textBuffer.toString()));
                    textBuffer.setLength(0);
                }

                // 处理音频。
                try {
                    Source source = audioBlock.getSource();
                    if (source == null) {
                        log.warn("AudioBlock has null source, skipping");
                        if (includePrefix) {
                            appendNamePrefix(textBuffer, agentName);
                        }
                        textBuffer.append("[Audio - null source]\n");
                    } else if (source instanceof Base64Source b64) {
                        String audioData = b64.getData();
                        if (audioData == null || audioData.isEmpty()) {
                            log.warn("Base64Source has null or empty data, skipping");
                            if (includePrefix) {
                                appendNamePrefix(textBuffer, agentName);
                            }
                            textBuffer.append("[Audio - null or empty data]\n");
                        } else {
                            String format = detectAudioFormat(b64.getMediaType());
                            allParts.add(OpenAIContentPart.inputAudio(audioData, format));
                        }
                    } else if (source instanceof URLSource urlSource) {
                        String url = urlSource.getUrl();
                        if (url == null || url.isEmpty()) {
                            log.warn("URLSource has null or empty URL, skipping");
                            if (includePrefix) {
                                appendNamePrefix(textBuffer, agentName);
                            }
                            textBuffer.append("[Audio - null or empty URL]\n");
                        } else {
                            log.warn(
                                    "URL-based audio not directly supported, using text"
                                            + " reference");
                            if (includePrefix) {
                                appendNamePrefix(textBuffer, agentName);
                            }
                            textBuffer.append("[Audio URL: ").append(url).append("]\n");
                        }
                    } else {
                        log.warn("Unknown audio source type: {}", source.getClass());
                        if (includePrefix) {
                            appendNamePrefix(textBuffer, agentName);
                        }
                        textBuffer.append("[Audio - unsupported source type]\n");
                    }
                } catch (Exception e) {
                    String errorMsg =
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warn("Failed to process AudioBlock: {}", errorMsg);
                    appendNamePrefix(textBuffer, agentName);
                    textBuffer
                            .append("[Audio - processing failed: ")
                            .append(errorMsg)
                            .append("]\n");
                }

            } else if (block instanceof DataBlock db) {
                // 输出已有文本。
                if (textBuffer.length() > 0) {
                    allParts.add(OpenAIContentPart.text(textBuffer.toString()));
                    textBuffer.setLength(0);
                }

                try {
                    Source source = db.getSource();
                    if (source == null) {
                        log.warn("DataBlock has null source, skipping");
                        if (includePrefix) {
                            appendNamePrefix(textBuffer, agentName);
                        }
                        textBuffer.append("[Data - null source]\n");
                    } else {
                        String mimeType = MediaUtils.resolveMimeType(source);
                        if (mimeType.startsWith("image/")) {
                            allParts.add(
                                    OpenAIContentPart.imageUrl(convertImageSourceToUrl(source)));
                        } else if (mimeType.startsWith("video/")) {
                            allParts.add(
                                    OpenAIContentPart.videoUrl(convertVideoSourceToUrl(source)));
                        } else if (mimeType.startsWith("audio/")) {
                            if (source instanceof Base64Source b64) {
                                String format = detectAudioFormat(b64.getMediaType());
                                allParts.add(OpenAIContentPart.inputAudio(b64.getData(), format));
                            } else {
                                if (includePrefix) {
                                    appendNamePrefix(textBuffer, agentName);
                                }
                                textBuffer
                                        .append("[Audio URL: ")
                                        .append(((URLSource) source).getUrl())
                                        .append("]\n");
                            }
                        } else {
                            if (includePrefix) {
                                appendNamePrefix(textBuffer, agentName);
                            }
                            textBuffer
                                    .append("[Media - unsupported MIME type: ")
                                    .append(mimeType)
                                    .append("]\n");
                        }
                    }
                } catch (Exception e) {
                    String errorMsg =
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warn("Failed to process DataBlock: {}", errorMsg);
                    if (includePrefix) {
                        appendNamePrefix(textBuffer, agentName);
                    }
                    textBuffer
                            .append("[Media - processing failed: ")
                            .append(errorMsg)
                            .append("]\n");
                }

            } else if (block instanceof ThinkingBlock thinkingBlock) {
                // 对于支持推理的模型，将 ThinkingBlock 纳入对话历史。
                if (includePrefix) {
                    appendNamePrefix(textBuffer, agentName);
                    String thinking = thinkingBlock.getThinking();
                    if (thinking != null && !thinking.isEmpty()) {
                        textBuffer.append("[Thinking]: ").append(thinking).append("\n");
                    }
                } else {
                    String thinking = thinkingBlock.getThinking();
                    if (thinking != null && !thinking.isEmpty()) {
                        textBuffer.append("[Thinking]: ").append(thinking).append("\n");
                    }
                }
            } else if (block instanceof ToolResultBlock toolResult) {
                // 使用传入的转换器处理工具结果中的多模态内容。
                String resultText = toolResultConverter.apply(toolResult.getOutput());
                String finalResultText =
                        (resultText != null && !resultText.isEmpty())
                                ? resultText
                                : "[Empty tool result]";

                // 工具结果格式为：name (tool_name): result。
                textBuffer.append(agentName);
                textBuffer
                        .append(" (")
                        .append(toolResult.getName())
                        .append("): ")
                        .append(finalResultText)
                        .append("\n");
            }
        }
    }

    private void appendNamePrefix(StringBuilder buffer, String agentName) {
        if (agentName != null && !agentName.isEmpty()) {
            buffer.append(agentName).append(": ");
        }
    }

    /** 将图像 Source 转换为 OpenAI API 所需的 URL 字符串。 */
    private String convertImageSourceToUrl(Source source) {
        return OpenAIConverterUtils.convertImageSourceToUrl(source);
    }

    /** 将视频 Source 转换为 OpenAI API 所需的 URL 字符串。 */
    private String convertVideoSourceToUrl(Source source) {
        return OpenAIConverterUtils.convertVideoSourceToUrl(source);
    }

    /** 根据媒体类型判断音频格式。 */
    private String detectAudioFormat(String mediaType) {
        return OpenAIConverterUtils.detectAudioFormat(mediaType);
    }
}
