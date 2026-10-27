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
package io.agentscope.extensions.model.openai.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.OpenAIClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * OpenAI 多模态工具。
 *
 * <p>接口涵盖：
 * <ul><li>文本生成图像。</li><li>通过视觉模型将图像转为文本。</li>
 * <li>文本转音频（语音）。</li><li>音频转文本（转录）。</li></ul>
 *
 * <p>使用自定义 {@link OpenAIClient} HTTP 客户端，而非 OpenAI Java SDK，避免引入该 SDK 依赖。
 *
 * <p>更多信息参见 <a href="https://platform.openai.com/">OpenAI 文档</a>。
 *
 * <p>【学习提示】接口声明不等于实现已经完备：本类的语音合成和转录路径仍有占位逻辑，
 * 分别等待二进制响应及 multipart 上传支持。阅读能力边界时应结合具体方法实现。
 */
public class OpenAIMultiModalTool {

    private static final Logger log = LoggerFactory.getLogger(OpenAIMultiModalTool.class);

    /** OpenAI API 密钥。 */
    private final String apiKey;

    /** 执行 API 调用的 OpenAI 客户端。 */
    private final OpenAIClient client;

    /** OpenAI API 基础 URL，默认为 https://api.openai.com。 */
    private final String baseUrl;

    /** 调用方未指定时使用的默认视觉模型。 */
    private final String defaultModelName;

    /**
     * 使用默认基础 URL 创建 OpenAIMultiModalTool。
     *
     * @param apiKey OpenAI API 密钥
     */
    public OpenAIMultiModalTool(String apiKey) {
        this(apiKey, null, "gpt-4o");
    }

    /**
     * 使用自定义基础 URL 创建 OpenAIMultiModalTool。
     *
     * @param apiKey OpenAI API 密钥
     * @param baseUrl 基础 URL，null 使用默认 https://api.openai.com
     */
    public OpenAIMultiModalTool(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, "gpt-4o");
    }

    /**
     * 使用自定义基础 URL 和默认视觉模型创建 OpenAIMultiModalTool。
     *
     * @param apiKey OpenAI API 密钥
     * @param baseUrl 基础 URL，null 使用默认 https://api.openai.com
     * @param defaultModelName 调用方省略 model 参数时使用的默认视觉模型名称，例如 OpenAI 的 "gpt-4o" 或后端视觉模型名称
     */
    public OpenAIMultiModalTool(String apiKey, String baseUrl, String defaultModelName) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("OpenAI API key cannot be empty.");
        }
        if (defaultModelName == null || defaultModelName.trim().isEmpty()) {
            throw new IllegalArgumentException("defaultModelName cannot be empty.");
        }
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.defaultModelName = defaultModelName;
        this.client = new OpenAIClient();
    }

    /**
     * 使用自定义客户端创建 OpenAIMultiModalTool，供测试使用。
     *
     * @param client OpenAI 客户端
     */
    protected OpenAIMultiModalTool(OpenAIClient client) {
        this(client, "gpt-4o");
    }

    /**
     * 使用自定义客户端和默认模型创建 OpenAIMultiModalTool，供测试使用。
     *
     * @param client OpenAI 客户端
     * @param defaultModelName 默认视觉模型名称
     */
    protected OpenAIMultiModalTool(OpenAIClient client, String defaultModelName) {
        if (defaultModelName == null || defaultModelName.trim().isEmpty()) {
            throw new IllegalArgumentException("defaultModelName cannot be empty.");
        }
        this.apiKey = "test-key";
        this.baseUrl = null;
        this.defaultModelName = defaultModelName;
        this.client = client;
    }

    /**
     * 根据给定提示词生成图像。
     *
     * @param prompt 图像生成文本提示词
     * @param model 使用的模型，例如 "dall-e-3"、"dall-e-2"
     * @param n 图像数量，dall-e-3 为 1，dall-e-2 为 1～10
     * @param size 图像尺寸，例如 "1024x1024"、"1792x1024"、"1024x1792"
     * @param quality 图像质量，dall-e-3 为 "standard" 或 "hd"
     * @param responseFormat 响应格式，"url" 或 "b64_json"
     * @return 包含生成图像的 ToolResultBlock
     */
    @Tool(
            name = "openai_text_to_image",
            description =
                    "Generate image(s) based on the given prompt using OpenAI DALL-E models. "
                            + "Returns image URL(s) or base64 data.")
    public Mono<ToolResultBlock> openaiTextToImage(
            @ToolParam(name = "prompt", description = "The text prompt to generate image")
                    String prompt,
            @ToolParam(
                            name = "model",
                            description = "The model to use, e.g., 'dall-e-3', 'dall-e-2'",
                            required = false)
                    String model,
            @ToolParam(
                            name = "n",
                            description =
                                    "The number of images to generate (1 for dall-e-3, 1-10 for"
                                            + " dall-e-2)",
                            required = false)
                    Integer n,
            @ToolParam(
                            name = "size",
                            description =
                                    "Size of the image, e.g., '1024x1024', '1792x1024',"
                                            + " '1024x1792'",
                            required = false)
                    String size,
            @ToolParam(
                            name = "quality",
                            description =
                                    "The quality of the image ('standard' or 'hd' for dall-e-3)",
                            required = false)
                    String quality,
            @ToolParam(
                            name = "response_format",
                            description = "The format of the response ('url' or 'b64_json')",
                            required = false)
                    String responseFormat) {

        String finalModel =
                Optional.ofNullable(model).filter(s -> !s.trim().isEmpty()).orElse("dall-e-3");
        Integer finalN = Optional.ofNullable(n).orElse(1);
        String finalSize =
                Optional.ofNullable(size).filter(s -> !s.trim().isEmpty()).orElse("1024x1024");
        String finalQuality =
                Optional.ofNullable(quality).filter(s -> !s.trim().isEmpty()).orElse("standard");
        String finalResponseFormat =
                Optional.ofNullable(responseFormat).filter(s -> !s.trim().isEmpty()).orElse("url");

        log.debug(
                "openai_text_to_image called: prompt='{}', model='{}', n='{}', size='{}',"
                        + " quality='{}', responseFormat='{}'",
                prompt,
                finalModel,
                finalN,
                finalSize,
                finalQuality,
                finalResponseFormat);

        return Mono.fromCallable(
                        () -> {
                            Map<String, Object> request = new HashMap<>();
                            request.put("prompt", prompt);
                            request.put("model", finalModel);
                            request.put("n", finalN);
                            request.put("size", finalSize);
                            if ("dall-e-3".equals(finalModel)) {
                                request.put("quality", finalQuality);
                            }
                            request.put("response_format", finalResponseFormat);

                            String responseBody =
                                    client.callApi(
                                            apiKey, baseUrl, "/v1/images/generations", request);
                            Map<String, Object> response =
                                    JsonUtils.getJsonCodec()
                                            .fromJson(
                                                    responseBody,
                                                    new TypeReference<Map<String, Object>>() {});

                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> data =
                                    (List<Map<String, Object>>) response.get("data");
                            if (data == null || data.isEmpty()) {
                                log.error("No image data returned.");
                                return ToolResultBlock.error("Failed to generate images.");
                            }

                            List<ContentBlock> contentBlocks = new ArrayList<>();
                            for (Map<String, Object> item : data) {
                                if ("b64_json".equals(finalResponseFormat)) {
                                    String base64Data = (String) item.get("b64_json");
                                    if (base64Data != null && !base64Data.trim().isEmpty()) {
                                        contentBlocks.add(
                                                ImageBlock.builder()
                                                        .source(
                                                                Base64Source.builder()
                                                                        .mediaType("image/png")
                                                                        .data(base64Data)
                                                                        .build())
                                                        .build());
                                    }
                                } else {
                                    String url = (String) item.get("url");
                                    if (url != null && !url.trim().isEmpty()) {
                                        contentBlocks.add(
                                                ImageBlock.builder()
                                                        .source(
                                                                URLSource.builder()
                                                                        .url(url)
                                                                        .build())
                                                        .build());
                                    }
                                }
                            }

                            if (contentBlocks.isEmpty()) {
                                log.error("No valid image content generated.");
                                return ToolResultBlock.error("Failed to generate images.");
                            }

                            return ToolResultBlock.builder().output(contentBlocks).build();
                        })
                .onErrorMap(
                        ex -> {
                            log.error("Failed to generate images: {}", ex.getMessage(), ex);
                            return new RuntimeException(
                                    "Failed to generate images: " + ex.getMessage(), ex);
                        });
    }

    /**
     * 使用视觉模型将图像转换为文本。
     *
     * @param imageUrls 待分析图像的 URL
     * @param prompt 描述要从图像提取什么的文本提示词
     * @param model 使用的视觉模型，留空则使用已配置默认值
     * @param maxTokens 响应的最大 token 数
     * @return 包含图像文本描述的 ToolResultBlock
     */
    @Tool(
            name = "openai_image_to_text",
            description =
                    "Convert image(s) to text using OpenAI vision models. "
                            + "Analyzes images and returns text descriptions.")
    public Mono<ToolResultBlock> openaiImageToText(
            @ToolParam(
                            name = "image_urls",
                            description = "The URLs of the images to analyze (comma-separated)")
                    String imageUrls,
            @ToolParam(
                            name = "prompt",
                            description =
                                    "The text prompt describing what to extract from the images",
                            required = false)
                    String prompt,
            @ToolParam(
                            name = "model",
                            description =
                                    "The vision model to use (leave empty to use the configured"
                                            + " default)",
                            required = false)
                    String model,
            @ToolParam(
                            name = "max_tokens",
                            description = "The maximum number of tokens in the response",
                            required = false)
                    Integer maxTokens) {

        String finalModel =
                Optional.ofNullable(model)
                        .filter(s -> !s.trim().isEmpty())
                        .orElse(this.defaultModelName);
        String finalPrompt =
                Optional.ofNullable(prompt)
                        .filter(s -> !s.trim().isEmpty())
                        .orElse("Describe the image(s) in detail.");
        Integer finalMaxTokens = Optional.ofNullable(maxTokens).orElse(300);

        log.debug(
                "openai_image_to_text called: imageUrls='{}', prompt='{}', model='{}',"
                        + " maxTokens='{}'",
                imageUrls,
                finalPrompt,
                finalModel,
                finalMaxTokens);

        return Mono.fromCallable(
                        () -> {
                            // 解析图像 URL。
                            String[] urls = imageUrls.split(",");
                            List<Map<String, Object>> contentParts = new ArrayList<>();

                            // 添加文本提示词。
                            contentParts.add(Map.of("type", "text", "text", finalPrompt));

                            // 添加图像 URL。
                            for (String url : urls) {
                                String trimmedUrl = url.trim();
                                if (!trimmedUrl.isEmpty()) {
                                    contentParts.add(
                                            Map.of(
                                                    "type",
                                                    "image_url",
                                                    "image_url",
                                                    Map.of("url", trimmedUrl)));
                                }
                            }

                            Map<String, Object> request = new HashMap<>();
                            request.put("model", finalModel);
                            request.put(
                                    "messages",
                                    List.of(Map.of("role", "user", "content", contentParts)));
                            request.put("max_tokens", finalMaxTokens);

                            String responseBody =
                                    client.callApi(
                                            apiKey, baseUrl, "/v1/chat/completions", request);
                            Map<String, Object> response =
                                    JsonUtils.getJsonCodec()
                                            .fromJson(
                                                    responseBody,
                                                    new TypeReference<Map<String, Object>>() {});

                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> choices =
                                    (List<Map<String, Object>>) response.get("choices");
                            if (choices == null || choices.isEmpty()) {
                                log.error("No choices returned from vision model.");
                                return ToolResultBlock.error("Failed to analyze images.");
                            }

                            Map<String, Object> firstChoice = choices.get(0);
                            @SuppressWarnings("unchecked")
                            Map<String, Object> message =
                                    (Map<String, Object>) firstChoice.get("message");
                            if (message == null) {
                                log.error("No message in choice.");
                                return ToolResultBlock.error("Failed to analyze images.");
                            }

                            String text = (String) message.get("content");
                            if (text == null || text.trim().isEmpty()) {
                                log.error("No content in message.");
                                return ToolResultBlock.error("Failed to analyze images.");
                            }

                            return ToolResultBlock.builder()
                                    .output(List.of(TextBlock.builder().text(text).build()))
                                    .build();
                        })
                .onErrorMap(
                        ex -> {
                            log.error("Failed to analyze images: {}", ex.getMessage(), ex);
                            return new RuntimeException(
                                    "Failed to analyze images: " + ex.getMessage(), ex);
                        });
    }

    /**
     * 使用 OpenAI TTS 模型将文本转换为语音。
     *
     * @param text 待转为语音的文本
     * @param model TTS 模型，例如 "tts-1"、"tts-1-hd"
     * @param voice 音色，"alloy"、"echo"、"fable"、"onyx"、"nova"、"shimmer"
     * @param responseFormat 音频格式，"mp3"、"opus"、"aac"、"flac"
     * @param speed 语速，范围 0.25～4.0
     * @return 预期为包含 base64 音频的 ToolResultBlock；当前实现仍返回未支持的错误结果
     */
    @Tool(
            name = "openai_text_to_audio",
            description =
                    "Convert text to audio (speech) using OpenAI TTS models. "
                            + "Returns audio as base64 data.")
    public Mono<ToolResultBlock> openaiTextToAudio(
            @ToolParam(name = "text", description = "The text to convert to speech") String text,
            @ToolParam(
                            name = "model",
                            description = "The TTS model to use, e.g., 'tts-1', 'tts-1-hd'",
                            required = false)
                    String model,
            @ToolParam(
                            name = "voice",
                            description =
                                    "The voice to use: 'alloy', 'echo', 'fable', 'onyx', 'nova',"
                                            + " 'shimmer'",
                            required = false)
                    String voice,
            @ToolParam(
                            name = "response_format",
                            description = "The audio format: 'mp3', 'opus', 'aac', 'flac'",
                            required = false)
                    String responseFormat,
            @ToolParam(
                            name = "speed",
                            description = "The speed of the speech (0.25 to 4.0)",
                            required = false)
                    Double speed) {

        String finalModel =
                Optional.ofNullable(model).filter(s -> !s.trim().isEmpty()).orElse("tts-1");
        String finalVoice =
                Optional.ofNullable(voice).filter(s -> !s.trim().isEmpty()).orElse("alloy");
        String finalResponseFormat =
                Optional.ofNullable(responseFormat).filter(s -> !s.trim().isEmpty()).orElse("mp3");
        Double finalSpeed = Optional.ofNullable(speed).orElse(1.0);

        log.debug(
                "openai_text_to_audio called: text='{}', model='{}', voice='{}',"
                        + " responseFormat='{}', speed='{}'",
                text,
                finalModel,
                finalVoice,
                finalResponseFormat,
                finalSpeed);

        return Mono.fromCallable(
                        () -> {
                            Map<String, Object> request = new HashMap<>();
                            request.put("model", finalModel);
                            request.put("input", text);
                            request.put("voice", finalVoice);
                            request.put("response_format", finalResponseFormat);
                            request.put("speed", finalSpeed);

                            // 注意：/v1/audio/speech 返回二进制音频数据，而非 JSON。
                            // 需要处理二进制响应；当前先返回说明性的
                            // 错误。
                            // TODO：扩展 OpenAIClient，支持 TTS 二进制响应。
                            return ToolResultBlock.error(
                                    "Text-to-speech requires binary response handling, which is not"
                                        + " yet fully implemented. Please use the OpenAI SDK"
                                        + " directly for this feature, or extend OpenAIClient to"
                                        + " support binary responses.");
                        })
                .onErrorMap(
                        ex -> {
                            log.error("Failed to generate audio: {}", ex.getMessage(), ex);
                            return new RuntimeException(
                                    "Failed to generate audio: " + ex.getMessage(), ex);
                        });
    }

    /**
     * 使用 OpenAI Whisper 模型将音频转为文本（转录）。
     *
     * <p>注意：需要 multipart/form-data 上传，目前尚未完整支持，此处为占位实现。
     *
     * @param audioUrl 待转录音频文件的 URL
     * @param model 转录模型，例如 "whisper-1"
     * @param language 音频语言，ISO-639-1 代码，可选
     * @param prompt 可选文本，用于引导模型风格
     * @param responseFormat 响应格式，例如 "json"、"text"、"verbose_json"
     * @param temperature 采样温度，范围 0.0～1.0
     * @return 预期为包含转录文本的 ToolResultBlock；当前实现仍返回缺少 multipart 支持的错误结果
     */
    @Tool(
            name = "openai_audio_to_text",
            description =
                    "Convert audio to text (transcription) using OpenAI Whisper models. "
                            + "Requires audio file URL.")
    public Mono<ToolResultBlock> openaiAudioToText(
            @ToolParam(name = "audio_url", description = "The URL of the audio file to transcribe")
                    String audioUrl,
            @ToolParam(
                            name = "model",
                            description = "The transcription model to use, e.g., 'whisper-1'",
                            required = false)
                    String model,
            @ToolParam(
                            name = "language",
                            description = "The language of the audio (ISO-639-1 code, optional)",
                            required = false)
                    String language,
            @ToolParam(
                            name = "prompt",
                            description = "Optional text to guide the model's style",
                            required = false)
                    String prompt,
            @ToolParam(
                            name = "response_format",
                            description =
                                    "The format of the response: 'json', 'text', 'verbose_json',"
                                            + " etc.",
                            required = false)
                    String responseFormat,
            @ToolParam(
                            name = "temperature",
                            description = "The temperature for sampling (0.0 to 1.0)",
                            required = false)
                    Double temperature) {

        String finalModel =
                Optional.ofNullable(model).filter(s -> !s.trim().isEmpty()).orElse("whisper-1");
        String finalResponseFormat =
                Optional.ofNullable(responseFormat).filter(s -> !s.trim().isEmpty()).orElse("text");

        log.debug(
                "openai_audio_to_text called: audioUrl='{}', model='{}', language='{}',"
                        + " prompt='{}', responseFormat='{}', temperature='{}'",
                audioUrl,
                finalModel,
                language,
                prompt,
                finalResponseFormat,
                temperature);

        return Mono.fromCallable(
                        () -> {
                            // 从 URL 下载音频并转为 base64。
                            String audioBase64;
                            String mediaType;
                            try {
                                mediaType = MediaUtils.determineMediaType(audioUrl);
                                audioBase64 = MediaUtils.downloadUrlToBase64(audioUrl);
                            } catch (IOException e) {
                                log.error(
                                        "Failed to download audio from URL: {}", e.getMessage(), e);
                                return ToolResultBlock.error(
                                        "Failed to download audio: " + e.getMessage());
                            }

                            if (audioBase64 == null || audioBase64.trim().isEmpty()) {
                                log.error("Failed to convert audio to base64.");
                                return ToolResultBlock.error("Failed to process audio.");
                            }

                            // TODO：为以下端点实现 multipart/form-data 上传：
                            // /v1/audio/transcriptions
                            // 当前返回错误，说明此功能需要 multipart
                            // 支持。
                            return ToolResultBlock.error(
                                    "Audio transcription requires multipart/form-data upload, which"
                                        + " is not yet fully implemented. Please use the OpenAI SDK"
                                        + " directly for this feature.");
                        })
                .onErrorMap(
                        ex -> {
                            log.error("Failed to transcribe audio: {}", ex.getMessage(), ex);
                            return new RuntimeException(
                                    "Failed to transcribe audio: " + ex.getMessage(), ex);
                        });
    }
}
