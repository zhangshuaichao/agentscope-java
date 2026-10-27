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
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.dto.OpenAIContentPart;
import io.agentscope.extensions.model.openai.dto.OpenAIFunction;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIReasoningDetail;
import io.agentscope.extensions.model.openai.dto.OpenAIToolCall;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 AgentScope Msg 对象转换为 HTTP API 使用的 OpenAI DTO。
 *
 * <p>处理 system、user、assistant 和 tool 等所有消息角色的转换，
 * 支持文本、图像、音频等多模态内容以及工具调用。
 */
public class OpenAIMessageConverter {

    private static final Logger log = LoggerFactory.getLogger(OpenAIMessageConverter.class);

    private final Function<Msg, String> textExtractor;
    private final Function<List<ContentBlock>, String> toolResultConverter;

    /**
     * 使用所需的依赖函数创建 OpenAIMessageConverter。
     *
     * @param textExtractor 从 Msg 提取文本内容的函数
     * @param toolResultConverter 将工具结果块转换为字符串的函数
     */
    public OpenAIMessageConverter(
            Function<Msg, String> textExtractor,
            Function<List<ContentBlock>, String> toolResultConverter) {
        this.textExtractor = textExtractor;
        this.toolResultConverter = toolResultConverter;
    }

    /**
     * 将单条 Msg 转换为 OpenAI DTO 消息。
     *
     * @param msg 待转换的消息
     * @param hasMediaContent 消息是否包含图像/音频等媒体
     * @return 供 OpenAI API 使用的 OpenAIMessage
     */
    public OpenAIMessage convertToMessage(Msg msg, boolean hasMediaContent) {
        // 检查 SYSTEM 消息是否包含工具结果；存在时按 TOOL 角色处理。
        OpenAIMessage result;
        if (msg.getRole() == MsgRole.SYSTEM && msg.hasContentBlocks(ToolResultBlock.class)) {
            result = convertToolMessage(msg);
        } else {
            result =
                    switch (msg.getRole()) {
                        case SYSTEM -> convertSystemMessage(msg);
                        case USER -> convertUserMessage(msg, hasMediaContent);
                        case ASSISTANT -> convertAssistantMessage(msg);
                        case TOOL -> convertToolMessage(msg);
                    };
        }

        // 若消息元数据已手动标记，则应用其中的 cache_control。
        applyCacheControlFromMetadata(msg, result);

        return result;
    }

    /**
     * 转换系统消息。
     *
     * @param msg 系统消息
     * @return OpenAIMessage
     */
    private OpenAIMessage convertSystemMessage(Msg msg) {
        String content = textExtractor.apply(msg);
        OpenAIMessage.Builder builder =
                OpenAIMessage.builder().role("system").content(content != null ? content : "");
        if (msg.getName() != null) {
            builder.name(sanitizeName(msg.getName()));
        }
        return builder.build();
    }

    /**
     * 转换用户消息，支持多模态内容。
     *
     * @param msg 用户消息
     * @param hasMediaContent 消息是否包含媒体
     * @return OpenAIMessage
     */
    private OpenAIMessage convertUserMessage(Msg msg, boolean hasMediaContent) {
        OpenAIMessage.Builder builder = OpenAIMessage.builder().role("user");

        if (msg.getName() != null) {
            builder.name(sanitizeName(msg.getName()));
        }

        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null) {
            blocks = new ArrayList<>();
        }

        // 优化：纯文本快速路径。
        if (!hasMediaContent
                && !blocks.isEmpty()
                && blocks.size() == 1
                && blocks.get(0) instanceof TextBlock) {
            builder.content(((TextBlock) blocks.get(0)).getText());
            return builder.build();
        }

        // 多模态路径：构建 ContentPart 列表。
        List<OpenAIContentPart> contentParts = convertContentBlocks(blocks);

        if (!contentParts.isEmpty()) {
            builder.content(contentParts);
        } else {
            // 避免向 OpenAI API 发送 null 内容。
            builder.content("");
        }

        return builder.build();
    }

    /**
     * 将内容块转换为 OpenAI 内容片段。
     *
     * @param blocks 内容块列表
     * @return OpenAI 内容片段列表
     */
    private List<OpenAIContentPart> convertContentBlocks(List<ContentBlock> blocks) {
        List<OpenAIContentPart> contentParts = new ArrayList<>();

        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock tb) {
                contentParts.add(OpenAIContentPart.text(tb.getText()));
            } else if (block instanceof ImageBlock ib) {
                try {
                    Source source = ib.getSource();
                    if (source == null) {
                        log.warn("ImageBlock has null source, skipping");
                        continue;
                    }
                    String imageUrl = convertImageSourceToUrl(source);
                    contentParts.add(OpenAIContentPart.imageUrl(imageUrl));
                } catch (Exception e) {
                    String errorMsg =
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warn("Failed to process ImageBlock: {}", errorMsg);
                    contentParts.add(
                            OpenAIContentPart.text(
                                    "[Image - processing failed: " + errorMsg + "]"));
                }
            } else if (block instanceof AudioBlock ab) {
                try {
                    // OpenAI 要求 input_audio 格式中的音频为 base64。
                    Source source = ab.getSource();
                    if (source == null) {
                        log.warn("AudioBlock has null source, using placeholder");
                        contentParts.add(OpenAIContentPart.text("[Audio - source missing]"));
                        continue;
                    }
                    if (source instanceof Base64Source b64) {
                        String audioData = b64.getData();
                        if (audioData == null || audioData.isEmpty()) {
                            log.warn("Base64Source has null or empty data, using placeholder");
                            contentParts.add(OpenAIContentPart.text("[Audio - data missing]"));
                            continue;
                        }
                        String mediaType = b64.getMediaType();
                        String format = mediaType != null ? detectAudioFormat(mediaType) : "wav";
                        if (format == null) {
                            log.debug("Audio format detection returned null, defaulting to wav");
                            format = "wav";
                        }
                        contentParts.add(OpenAIContentPart.inputAudio(audioData, format));
                    } else if (source instanceof URLSource urlSource) {
                        // URL 音频需要作为文本添加，因为 OpenAI 的
                        // input_audio 要求 base64 数据。
                        String url = urlSource.getUrl();
                        if (url == null || url.isEmpty()) {
                            log.warn("URLSource has null or empty URL, using placeholder");
                            contentParts.add(OpenAIContentPart.text("[Audio URL - missing]"));
                            continue;
                        }
                        log.warn("URL-based audio not directly supported, using text reference");
                        contentParts.add(OpenAIContentPart.text("[Audio URL: " + url + "]"));
                    } else {
                        log.warn(
                                "Unknown audio source type: {}", source.getClass().getSimpleName());
                        contentParts.add(
                                OpenAIContentPart.text("[Audio - unsupported source type]"));
                    }
                } catch (Exception e) {
                    String errorMsg =
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warn("Failed to process AudioBlock: {}", errorMsg, e);
                    contentParts.add(
                            OpenAIContentPart.text(
                                    "[Audio - processing failed: " + errorMsg + "]"));
                }
            } else if (block instanceof HintBlock hb) {
                contentParts.add(OpenAIContentPart.text(hb.getHint()));
            } else if (block instanceof ThinkingBlock) {
                log.debug("Skipping ThinkingBlock when formatting for OpenAI");
            } else if (block instanceof VideoBlock vb) {
                try {
                    Source source = vb.getSource();
                    if (source == null) {
                        log.warn("VideoBlock has null source, skipping");
                        continue;
                    }
                    String videoUrl = convertVideoSourceToUrl(source);
                    contentParts.add(OpenAIContentPart.videoUrl(videoUrl));
                } catch (Exception e) {
                    String errorMsg =
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warn("Failed to process VideoBlock: {}", errorMsg);
                    contentParts.add(
                            OpenAIContentPart.text(
                                    "[Video - processing failed: " + errorMsg + "]"));
                }
            } else if (block instanceof DataBlock db) {
                try {
                    Source source = db.getSource();
                    if (source == null) {
                        log.warn("DataBlock has null source, skipping");
                        continue;
                    }
                    String mimeType = MediaUtils.resolveMimeType(source);
                    if (mimeType.startsWith("image/")) {
                        contentParts.add(
                                OpenAIContentPart.imageUrl(convertImageSourceToUrl(source)));
                    } else if (mimeType.startsWith("video/")) {
                        contentParts.add(
                                OpenAIContentPart.videoUrl(convertVideoSourceToUrl(source)));
                    } else if (mimeType.startsWith("audio/")) {
                        if (source instanceof Base64Source b64) {
                            String format = detectAudioFormat(b64.getMediaType());
                            contentParts.add(OpenAIContentPart.inputAudio(b64.getData(), format));
                        } else {
                            log.warn(
                                    "URL-based audio DataBlock not supported by OpenAI input_audio;"
                                            + " using text reference");
                            contentParts.add(
                                    OpenAIContentPart.text(
                                            "[Audio URL: " + ((URLSource) source).getUrl() + "]"));
                        }
                    } else {
                        log.warn("DataBlock has unroutable MIME type '{}', skipping", mimeType);
                        contentParts.add(
                                OpenAIContentPart.text(
                                        "[Media - unsupported MIME type: " + mimeType + "]"));
                    }
                } catch (Exception e) {
                    String errorMsg =
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warn("Failed to process DataBlock: {}", errorMsg);
                    contentParts.add(
                            OpenAIContentPart.text(
                                    "[Media - processing failed: " + errorMsg + "]"));
                }
            } else if (block instanceof ToolUseBlock) {
                log.warn("ToolUseBlock is not supported in user messages");
            } else if (block instanceof ToolResultBlock) {
                log.warn("ToolResultBlock is not supported in user messages");
            }
        }
        return contentParts;
    }

    /**
     * 转换助手消息，支持工具调用。
     *
     * @param msg 助手消息
     * @return OpenAIMessage
     */
    private OpenAIMessage convertAssistantMessage(Msg msg) {
        OpenAIMessage.Builder builder = OpenAIMessage.builder().role("assistant");

        String textContent = textExtractor.apply(msg);
        // Qwen3 等模型在思考模式下可能生成如下助手消息：
        // 包含 reasoning_content 和 tool_calls，但 content 为 null。由于 @JsonInclude(NON_NULL)，
        // null 内容会在 JSON 中被省略，使严格的 OpenAI 兼容 API
        // （如 vLLM）以 "Field required: input.messages.N.content" 拒绝请求。
        // 因此始终确保 content 存在：使用实际文本，否则回退为空字符串。
        if (textContent != null && !textContent.isEmpty()) {
            builder.content(textContent);
        } else {
            builder.content("");
        }

        // 处理推理模型的 ThinkingBlock，例如通过 OpenRouter 使用的 Gemini。
        // 这些模型要求在历史中保留推理内容。
        ThinkingBlock thinkingBlock = msg.getFirstContentBlock(ThinkingBlock.class);
        if (thinkingBlock != null) {
            String thinking = thinkingBlock.getThinking();
            if (thinking != null && !thinking.isEmpty()) {
                builder.reasoningContent(thinking);
            }

            // 从 ThinkingBlock 元数据恢复 reasoning_details。
            // 使用推理 token 的 OpenRouter/Gemini 模型需要此信息。
            if (thinkingBlock.getMetadata() != null) {
                Object detailsObj =
                        thinkingBlock.getMetadata().get(ThinkingBlock.METADATA_REASONING_DETAILS);
                if (detailsObj instanceof List<?> list && !list.isEmpty()) {
                    List<OpenAIReasoningDetail> details = new ArrayList<>();
                    for (Object item : list) {
                        OpenAIReasoningDetail detail = toReasoningDetail(item);
                        if (detail != null) {
                            details.add(detail);
                        }
                    }
                    if (!details.isEmpty()) {
                        builder.reasoningDetails(details);
                    }
                }
            }
        }

        if (msg.getName() != null) {
            builder.name(sanitizeName(msg.getName()));
        }

        // 处理工具调用。
        List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
        if (!toolBlocks.isEmpty()) {
            List<OpenAIToolCall> toolCalls = new ArrayList<>();

            // 第一遍扫描：寻找内容块中的任意思考签名。
            String fallbackSignature = null;
            for (ToolUseBlock toolUse : toolBlocks) {
                if (toolUse.getMetadata() != null) {
                    String candidate =
                            toSignatureString(
                                    toolUse.getMetadata()
                                            .get(ToolUseBlock.METADATA_THOUGHT_SIGNATURE));
                    if (candidate != null) {
                        fallbackSignature = candidate;
                        break;
                    }
                }
            }

            for (ToolUseBlock toolUse : toolBlocks) {
                String toolId = toolUse.getId();
                String toolName = toolUse.getName();
                if (toolId == null || toolName == null) {
                    log.warn("ToolUseBlock has null id or name, skipping");
                    continue;
                }

                String argsJson = JsonUtils.resolveToolCallArgsJson(toolUse);

                // 元数据中存在思考签名时添加，Gemini 需要它。
                String signature = null;
                if (toolUse.getMetadata() != null) {
                    Object signatureObj =
                            toolUse.getMetadata().get(ToolUseBlock.METADATA_THOUGHT_SIGNATURE);
                    signature = toSignatureString(signatureObj);
                }

                // 缺失时回退到共享签名。
                if (signature == null) {
                    signature = fallbackSignature;
                }

                OpenAIFunction function = OpenAIFunction.of(toolName, argsJson);
                if (signature != null) {
                    function.setThoughtSignature(signature);
                }

                OpenAIToolCall.Builder toolCallBuilder =
                        OpenAIToolCall.builder().id(toolId).type("function").function(function);

                toolCalls.add(toolCallBuilder.build());

                log.debug(
                        "Formatted assistant tool call: id={}, name={}, hasSignature={}",
                        toolId,
                        toolName,
                        signature != null);
            }
            builder.toolCalls(toolCalls);
        }

        return builder.build();
    }

    /**
     * 转换工具结果消息。
     *
     * @param msg 工具结果消息
     * @return OpenAIMessage
     */
    private OpenAIMessage convertToolMessage(Msg msg) {
        ToolResultBlock result = msg.getFirstContentBlock(ToolResultBlock.class);
        String toolCallId =
                result != null && result.getId() != null
                        ? result.getId()
                        : "tool_call_" + System.currentTimeMillis();

        OpenAIMessage.Builder builder = OpenAIMessage.builder().role("tool").toolCallId(toolCallId);

        // 检查工具结果中的多模态内容。
        if (result != null && hasMediaContent(result.getOutput())) {
            List<OpenAIContentPart> parts = convertContentBlocks(result.getOutput());
            builder.content(parts);
        } else {
            // 纯文本或回退路径使用传入的转换器。
            String content;
            if (result != null) {
                content = toolResultConverter.apply(result.getOutput());
            } else {
                content = textExtractor.apply(msg);
            }
            if (content == null) {
                content = "";
            }
            builder.content(content);
        }

        return builder.build();
    }

    private boolean hasMediaContent(List<ContentBlock> blocks) {
        if (blocks == null) {
            return false;
        }
        for (ContentBlock block : blocks) {
            if (block instanceof ImageBlock
                    || block instanceof AudioBlock
                    || block instanceof VideoBlock
                    || block instanceof DataBlock) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将思考签名元数据值规范化为 Base64 字符串。
     *
     * <p>部分模型解析器生成 {@code byte[]} 签名，另一些生成 Base64 字符串；
     * 经过 JSON 状态持久化往返后，{@code byte[]} 签名会恢复为 Base64 {@code String}。
     * 此处接受两种形式。
     *
     * @param signature 原始元数据值
     * @return Base64 字符串形式；没有可用签名时返回 {@code null}
     */
    private static String toSignatureString(Object signature) {
        if (signature instanceof String s && !s.isEmpty()) {
            return s;
        }
        if (signature instanceof byte[] bytes && bytes.length > 0) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        return null;
    }

    /**
     * 从元数据值恢复 {@link OpenAIReasoningDetail}。
     *
     * <p>经过 JSON 状态持久化往返后，带类型的详情对象恢复为 {@code
     * LinkedHashMap}，因此在此转换回相应类型。
     *
     * @param value 原始元数据值
     * @return 带类型的详情；无法恢复时返回 {@code null}
     */
    private static OpenAIReasoningDetail toReasoningDetail(Object value) {
        if (value instanceof OpenAIReasoningDetail detail) {
            return detail;
        }
        if (value instanceof Map<?, ?>) {
            try {
                return JsonUtils.getJsonCodec().convertValue(value, OpenAIReasoningDetail.class);
            } catch (RuntimeException e) {
                log.warn("Failed to restore reasoning detail from metadata", e);
                return null;
            }
        }
        return null;
    }

    /**
     * 将图像 Source 转换为 OpenAI API 所需的 URL 字符串。
     *
     * @param source 待转换的 Source
     * @return URL 字符串，可以是 URL 或 base64 数据 URI
     * @throws IllegalArgumentException 来源为 null 或类型未知时抛出
     */
    private String convertImageSourceToUrl(Source source) {
        return OpenAIConverterUtils.convertImageSourceToUrl(source);
    }

    /**
     * 将视频 Source 转换为 OpenAI API 所需的 URL 字符串。
     *
     * @param source 待转换的 Source
     * @return URL 字符串，可以是 URL 或 base64 数据 URI
     * @throws IllegalArgumentException 来源为 null 或类型未知时抛出
     */
    private String convertVideoSourceToUrl(Source source) {
        return OpenAIConverterUtils.convertVideoSourceToUrl(source);
    }

    /**
     * 根据媒体类型判断音频格式。
     *
     * @param mediaType 媒体类型，例如 "audio/wav"
     * @return 格式字符串，例如 "wav"
     */
    private String detectAudioFormat(String mediaType) {
        return OpenAIConverterUtils.detectAudioFormat(mediaType);
    }

    /** 清理消息名称，使其满足 OpenAI 的 {@code ^[a-zA-Z0-9_-]{1,64}$} 约束。 */
    static String sanitizeName(String name) {
        String sanitized = OPENAI_NAME_ILLEGAL_CHARS.matcher(name).replaceAll("_");
        if (sanitized.length() > 64) {
            sanitized = sanitized.substring(0, 64);
        }
        return sanitized.isEmpty() ? "agent" : sanitized;
    }

    private static final Pattern OPENAI_NAME_ILLEGAL_CHARS = Pattern.compile("[^a-zA-Z0-9_-]");

    /**
     * 将 Msg 元数据中的 cache_control 应用到转换后的 OpenAIMessage。
     *
     * @param msg 带元数据的源消息
     * @param result 转换后的 OpenAI 消息
     */
    void applyCacheControlFromMetadata(Msg msg, OpenAIMessage result) {
        if (msg.getMetadata() == null) {
            return;
        }
        Object cacheFlag = msg.getMetadata().get(MessageMetadataKeys.CACHE_CONTROL);
        if (Boolean.TRUE.equals(cacheFlag)) {
            OpenAIBaseFormatter.setCacheControlOnContent(result);
        }
    }
}
