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
package io.agentscope.extensions.model.dashscope.tool;

import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisOutput;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationOutput;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.aigc.videosynthesis.VideoSynthesis;
import com.alibaba.dashscope.aigc.videosynthesis.VideoSynthesisOutput;
import com.alibaba.dashscope.aigc.videosynthesis.VideoSynthesisParam;
import com.alibaba.dashscope.aigc.videosynthesis.VideoSynthesisResult;
import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.audio.tts.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.tts.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.tts.SpeechSynthesizer;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.ResultCallback;
import com.alibaba.dashscope.common.Role;
import io.agentscope.core.Version;
import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * DashScope 多模态工具。
 * <p>支持工具：
 * <ul>
 * <li>dashscope_text_to_image：根据文本生成图像。</li>
 * <li>dashscope_image_to_image：根据文本提示和输入图像编辑或转换图像。</li>
 * <li>dashscope_image_to_text：根据图像生成文本。</li>
 * <li>dashscope_text_to_audio：将文本转换为音频。</li>
 * <li>dashscope_audio_to_text：将音频转换为文本。</li>
 * <li>dashscope_text_to_video：根据文本提示生成视频。</li>
 * <li>dashscope_image_to_video：根据单张输入图像及可选文本提示生成视频。</li>
 * <li>dashscope_first_and_last_frame_image_to_video：根据首尾帧及可选文本提示生成过渡视频。</li>
 * <li>dashscope_video_to_text：分析视频，生成描述或回答相关问题。</li>
 * </ul>
 * 支持文生图、图生文、文生音频及音频转文本等转换，详情参见 <a href="https://dashscope.aliyun.com/">DashScope 文档</a>。
 */
public class DashScopeMultiModalTool {

    private static final Logger log = LoggerFactory.getLogger(DashScopeMultiModalTool.class);

    /**
     * {@code dashscope_image_to_image} 默认模型，必须为编辑模型：同系列纯文生图模型会拒绝携带图像的 {@code input.messages} 载荷。
     */
    private static final String DEFAULT_IMAGE_EDIT_MODEL = "qwen-image-edit";

    /**
     * qwen-image 系列前缀。第一代编辑模型名称含 {@code -edit}（{@code qwen-image-edit}、{@code -plus}、{@code -max}）；从 2.0 起的编号代际省略此标记，改用代际编号（{@code qwen-image-2.0}、{@code qwen-image-2.0-pro}）。两种命名都用于编辑输入图像，而无版本号的 {@code qwen-image} / {@code qwen-image-plus} 仅用于文生图。
     */
    private static final String QWEN_IMAGE_PREFIX = "qwen-image-";

    /**
     * DashScope API 密钥。
     */
    private final String apiKey;

    public DashScopeMultiModalTool(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("DashScope API key cannot be empty.");
        }
        this.apiKey = apiKey;
    }

    /**
     * 根据提示生成图像，并返回图像 URL 或 Base64 数据。
     *
     * @param prompt 生成图像的文本提示
     * @param model 使用的模型，如 wanx-v1、qwen-image、wan2.2-t2i-flash
     * @param n 生成图像数量
     * @param size 图像尺寸，如 1024*1024、1280*1280、800*1200
     * @param useBase64 是否使用 Base64 图像数据
     * @return 包含图像 URL、Base64 数据或错误消息的 ToolResultBlock
     */
    @Tool(
            name = "dashscope_text_to_image",
            description =
                    "Generate image(s) based on the given prompt, and return image url(s) or base64"
                            + " data.")
    public Mono<ToolResultBlock> dashscopeTextToImage(
            @ToolParam(name = "prompt", description = "The text prompt to generate image")
                    String prompt,
            @ToolParam(
                            name = "model",
                            description =
                                    "The model to use, e.g., 'wanx-v1', 'qwen-image',"
                                            + " 'wan2.2-t2i-flash', etc.",
                            required = false)
                    String model,
            @ToolParam(
                            name = "n",
                            description = "The number of images to generate",
                            required = false)
                    Integer n,
            @ToolParam(
                            name = "size",
                            description =
                                    "Size of the image, e.g., '1024*1024', '1280*1280', '800*1200',"
                                            + " etc.",
                            required = false)
                    String size,
            @ToolParam(
                            name = "use_base64",
                            description = "Whether to use base64 data for images",
                            required = false)
                    Boolean useBase64) {

        Integer finalN = Optional.ofNullable(n).orElse(1);
        String finalSize =
                Optional.ofNullable(size).filter(s -> !s.trim().isEmpty()).orElse("1024*1024");
        String finalModel =
                Optional.ofNullable(model).filter(s -> !s.trim().isEmpty()).orElse("wanx-v1");
        Boolean finalUseBase64 = Optional.ofNullable(useBase64).orElse(false);

        log.debug(
                "dashscope_text_to_image called: prompt='{}', n='{}', size='{}', model='{}',"
                        + " useBase64='{}'",
                prompt,
                finalN,
                finalSize,
                finalModel,
                useBase64);

        return Mono.fromCallable(
                        () -> {
                            ImageSynthesisParam param =
                                    ImageSynthesisParam.builder()
                                            .apiKey(this.apiKey)
                                            .prompt(prompt)
                                            .model(finalModel)
                                            .n(finalN)
                                            .size(finalSize)
                                            .header("user-agent", Version.getUserAgent())
                                            .build();
                            ImageSynthesis imageSynthesis = new ImageSynthesis();
                            ImageSynthesisResult response = imageSynthesis.call(param);

                            List<String> urls =
                                    Optional.ofNullable(response)
                                            .map(ImageSynthesisResult::getOutput)
                                            .map(ImageSynthesisOutput::getResults)
                                            .map(
                                                    results ->
                                                            results.stream()
                                                                    .map(item -> item.get("url"))
                                                                    .toList())
                                            .orElse(null);

                            if (urls == null || urls.isEmpty()) {
                                log.error("No image url returned.");
                                return ToolResultBlock.error("Failed to generate images.");
                            }

                            List<ContentBlock> contentBlocks = new ArrayList<>();
                            for (String url : urls) {
                                if (finalUseBase64) {
                                    String mediaType;
                                    String data;
                                    try {
                                        mediaType = MediaUtils.determineMediaType(url);
                                        data = MediaUtils.downloadUrlToBase64(url);
                                    } catch (IOException e) {
                                        log.error("Failed to generate images.");
                                        return ToolResultBlock.error(e.getMessage());
                                    }
                                    if (data == null || data.trim().isEmpty()) {
                                        log.error("Failed to convert image url to base64.");
                                        return ToolResultBlock.error("Failed to generate images.");
                                    }

                                    contentBlocks.add(
                                            ImageBlock.builder()
                                                    .source(
                                                            Base64Source.builder()
                                                                    .mediaType(mediaType)
                                                                    .data(data)
                                                                    .build())
                                                    .build());
                                } else {

                                    contentBlocks.add(
                                            ImageBlock.builder()
                                                    .source(URLSource.builder().url(url).build())
                                                    .build());
                                }
                            }
                            return ToolResultBlock.of(contentBlocks);
                        })
                .onErrorResume(
                        e -> {
                            log.error("Failed to generate images '{}'", e.getMessage(), e);
                            return Mono.just(ToolResultBlock.error(e.getMessage()));
                        });
    }

    /**
     * 根据文本提示与输入图像编辑或转换图像。
     *
     * <p>日志约定：仅记录 {@code image_url} 的类型和长度，以及 {@code prompt} 的长度。不记录引用本身或提示文本，因为引用可能是包含整张图像的 Base64 data URL。
     *
     * @param imageUrl 待编辑图像的公共 URL、oss:// URL、本地文件路径或 Base64 data URL
     * @param prompt 描述所需编辑或转换的文本提示
     * @param model 图像编辑模型，如 qwen-image-edit
     * @param size 输出尺寸，如 1024*1024；编号代际（2.0 及以后）及第一代 plus/max 支持
     * @param useBase64 是否返回 Base64 图像而不是 URL
     * @return 包含生成图像 URL/Base64 或错误消息的 ToolResultBlock
     */
    @Tool(
            name = "dashscope_image_to_image",
            description =
                    "Edit or transform an existing image based on a text prompt and an input"
                            + " image. The input image must be reachable by the service: a public"
                            + " http(s) URL (the address an uploaded attachment is served from),"
                            + " an oss:// URL or a Base64 data URL. A path inside the sandbox"
                            + " workspace is not readable by this tool, so never invent one - ask"
                            + " for a link instead. Returns the edited image as URL or base64."
                            + " Use dashscope_text_to_image when there is no input image.")
    public Mono<ToolResultBlock> dashscopeImageToImage(
            @ToolParam(
                            name = "image_url",
                            description =
                                    "The URL, local file path or Base64 data URL (the format"
                                            + " pattern is data:[MIME_type];base64,{base64_image},"
                                            + " e.g., 'data:image/png;base64,iVBORw0KGgo...') of"
                                            + " the input image to edit")
                    String imageUrl,
            @ToolParam(
                            name = "prompt",
                            description =
                                    "The text prompt describing the desired edit or"
                                            + " transformation")
                    String prompt,
            @ToolParam(
                            name = "model",
                            description =
                                    "The image-edit model to use, e.g., 'qwen-image-edit',"
                                            + " 'qwen-image-edit-plus', 'qwen-image-edit-max',"
                                            + " 'qwen-image-2.0-pro'. Every numbered generation"
                                            + " (2.0 and later) edits an input image too."
                                            + " Text-to-image models such as 'qwen-image' or"
                                            + " 'wanx-v1' take no image input.",
                            required = false)
                    String model,
            @ToolParam(
                            name = "size",
                            description =
                                    "Size of the output image, e.g., '1024*1024', '1280*1280'."
                                            + " Only supported by 'qwen-image-edit-plus',"
                                            + " 'qwen-image-edit-max' and the numbered generations"
                                            + " ('qwen-image-2.0' and later); otherwise the"
                                            + " resolution follows the input image.",
                            required = false)
                    String size,
            @ToolParam(
                            name = "use_base64",
                            description = "Whether to return image as base64 data instead of URL",
                            required = false)
                    Boolean useBase64) {

        String finalModel =
                Optional.ofNullable(model)
                        .filter(s -> !s.trim().isEmpty())
                        .orElse(DEFAULT_IMAGE_EDIT_MODEL);
        Boolean finalUseBase64 = Optional.ofNullable(useBase64).orElse(false);

        // 构建请求前拒绝不可用模型，避免无效调用；此检查也必须
        // 先于尺寸解析，以免为即将拒绝、根本不会发送的参数
        // 记录误导性警告。
        if (rejectsImageInput(finalModel)) {
            log.error(
                    "dashscope_image_to_image rejected model '{}': it takes no image input on this"
                            + " endpoint",
                    finalModel);
            return Mono.just(ToolResultBlock.error(unsupportedEditModelMessage(finalModel)));
        }

        String finalSize = resolveOutputSize(finalModel, size);

        log.debug(
                "dashscope_image_to_image called: {}, model='{}', size='{}', useBase64='{}'",
                describeEditRequest(imageUrl, prompt),
                finalModel,
                finalSize,
                useBase64);

        return Mono.fromCallable(
                        () -> {
                            // 此端点只接受一条消息，且角色必须为 `user`。
                            // 前置系统消息或任何第二条消息会被拒绝，并返回
                            // 错误："Input should be 'user'" / "messages parameter length invalid"。
                            List<Map<String, Object>> content = new ArrayList<>();
                            content.add(Map.of("image", toEditableImageRef(imageUrl)));
                            content.add(Map.of("text", prompt));

                            MultiModalMessage userMessage =
                                    MultiModalMessage.builder()
                                            .role(Role.USER.getValue())
                                            .content(content)
                                            .build();
                            List<MultiModalMessage> messages = List.of(userMessage);

                            var builder =
                                    MultiModalConversationParam.builder()
                                            .apiKey(this.apiKey)
                                            .model(finalModel)
                                            .messages(messages)
                                            .header("user-agent", Version.getUserAgent());
                            // 不支持或空的 `size` 会使整个请求失败，因此仅在
                            // 模型接受显式分辨率时才添加此参数。
                            if (finalSize != null) {
                                builder.parameter("size", finalSize);
                            }
                            MultiModalConversationParam param = builder.build();

                            MultiModalConversation conv = new MultiModalConversation();
                            MultiModalConversationResult result = conv.call(param);

                            // 从响应提取图像 URL。
                            String resultImageUrl =
                                    Optional.ofNullable(result)
                                            .map(MultiModalConversationResult::getOutput)
                                            .map(MultiModalConversationOutput::getChoices)
                                            .flatMap(choices -> choices.stream().findFirst())
                                            .map(MultiModalConversationOutput.Choice::getMessage)
                                            .map(MultiModalMessage::getContent)
                                            .flatMap(contents -> contents.stream().findFirst())
                                            .map(m -> m.get("image"))
                                            .map(Object::toString)
                                            .orElse(null);

                            if (resultImageUrl == null || resultImageUrl.isEmpty()) {
                                // 回退：检查是否返回文本（模型错误消息）。
                                String errorText =
                                        Optional.ofNullable(result)
                                                .map(MultiModalConversationResult::getOutput)
                                                .map(MultiModalConversationOutput::getChoices)
                                                .flatMap(c -> c.stream().findFirst())
                                                .map(
                                                        MultiModalConversationOutput.Choice
                                                                ::getMessage)
                                                .map(MultiModalMessage::getContent)
                                                .flatMap(c -> c.stream().findFirst())
                                                .map(m -> m.get("text"))
                                                .map(Object::toString)
                                                .orElse(null);
                                log.error(
                                        "No image URL returned from image-to-image."
                                                + " Response text: {}",
                                        errorText);
                                return ToolResultBlock.error(
                                        "Failed to generate image: "
                                                + (errorText != null
                                                        ? errorText
                                                        : "no image in response"));
                            }

                            List<ContentBlock> contentBlocks = new ArrayList<>();
                            if (finalUseBase64) {
                                String mediaType;
                                String data;
                                try {
                                    mediaType = MediaUtils.determineMediaType(resultImageUrl);
                                    data = MediaUtils.downloadUrlToBase64(resultImageUrl);
                                } catch (IOException e) {
                                    log.error("Failed to download generated image.");
                                    return ToolResultBlock.error(e.getMessage());
                                }
                                if (data == null || data.trim().isEmpty()) {
                                    return ToolResultBlock.error(
                                            "Failed to convert image to base64.");
                                }
                                contentBlocks.add(
                                        ImageBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .mediaType(mediaType)
                                                                .data(data)
                                                                .build())
                                                .build());
                            } else {
                                contentBlocks.add(
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(resultImageUrl)
                                                                .build())
                                                .build());
                            }
                            return ToolResultBlock.of(contentBlocks);
                        })
                .onErrorResume(
                        e -> {
                            log.error(
                                    "Failed to generate image from image '{}'", e.getMessage(), e);
                            return Mono.just(ToolResultBlock.error(e.getMessage()));
                        });
    }

    /**
     * 解析编辑请求的 {@code size} 参数。基础 {@code qwen-image-edit} 模型不支持配置输出分辨率，会拒绝此参数，因此丢弃并记录警告，避免整次调用失败。
     */
    private static String resolveOutputSize(String model, String size) {
        String requested = Optional.ofNullable(size).filter(s -> !s.trim().isEmpty()).orElse(null);
        if (requested == null) {
            return null;
        }
        if (!supportsOutputSize(model)) {
            log.warn(
                    "dashscope_image_to_image drops size='{}' for model '{}': the output"
                            + " resolution follows the input image",
                    requested,
                    model);
            return null;
        }
        return requested;
    }

    /**
     * 模型是否接受显式输出分辨率。
     */
    private static boolean supportsOutputSize(String model) {
        String name = model.toLowerCase();
        // 发送模型不接受的分辨率会让整个请求失败，因此第一代
        // 名称采用显式列表；编号代际按模式匹配，依据是
        // 2.0 支持分辨率，当前没有证据表明后续代际移除了它。
        return isNumberedEditGeneration(name)
                || name.startsWith("qwen-image-edit-plus")
                || name.startsWith("qwen-image-edit-max");
    }

    /**
     * 判断模型是否为图像系列的编号代际，例如 {@code qwen-image-2.0}、{@code qwen-image-3.0-pro}。匹配首个数字而不是具体版本，以兼容尚未发布的模型命名。
     */
    private static boolean isNumberedEditGeneration(String name) {
        return name.length() > QWEN_IMAGE_PREFIX.length()
                && name.startsWith(QWEN_IMAGE_PREFIX)
                && Character.isDigit(name.charAt(QWEN_IMAGE_PREFIX.length()));
    }

    /**
     * 判断模型是否无法在此端点消费输入图像：包括图像编辑使用另一异步任务 API 的 wan 系列，以及 qwen-image 系列的纯文生图成员。
     *
     * <p>无法识别的模型交给服务端处理并报告真实原因；本地误拒绝会无声移除能力，并将原因错误归因。
     */
    private static boolean rejectsImageInput(String model) {
        String name = model.toLowerCase();
        if (name.startsWith("wan")) {
            return true;
        }
        return name.startsWith("qwen-image")
                && !name.contains("edit")
                && !isNumberedEditGeneration(name);
    }

    /**
     * 在不携带用户载荷的情况下生成编辑请求日志摘要。
     *
     * <p>{@code image_url} 可以是 Base64 data URL，一次调用可能包含整张编码图像；写到 INFO/DEBUG 日志会让应用日志出现每行数 MB 的明文图像数据。提示文本同样是用户内容，没有更充分的记录理由。因此两者仅保留类型与长度，连前缀也不输出。
     */
    private static String describeEditRequest(String imageUrl, String prompt) {
        String ref = imageUrl == null ? "" : imageUrl.trim().toLowerCase();
        String kind;
        if (ref.isEmpty()) {
            kind = "absent";
        } else if (ref.startsWith("data:")) {
            kind = "base64-data-url";
        } else if (ref.startsWith("oss://")) {
            kind = "oss-url";
        } else if (ref.startsWith("http://") || ref.startsWith("https://")) {
            kind = "http-url";
        } else {
            kind = "local-path";
        }
        return "imageRef="
                + kind
                + "(len="
                + ref.length()
                + "), promptLength="
                + (prompt == null ? 0 : prompt.length());
    }

    /**
     * 用可操作提示替代不透明的服务端参数错误。
     */
    private static String unsupportedEditModelMessage(String model) {
        return "Model '"
                + model
                + "' cannot edit an input image with this tool. Omit the model to"
                + " use '"
                + DEFAULT_IMAGE_EDIT_MODEL
                + "', or pass an edit model:"
                + " 'qwen-image-edit-plus', 'qwen-image-edit-max', 'qwen-image-2.0-pro'."
                + " dashscope_text_to_image is the right tool when there is no input image.";
    }

    /**
     * 规范化输入图像引用。支持公开 http(s) URL、上传 API 返回的 {@code oss://} URL，以及 Base64 data URL；已有本地文件会内联为 Base64，而不是发送端点不接受的 {@code file://} URL。
     */
    private static String toEditableImageRef(String imageUrl) throws IOException {
        if (MediaUtils.isFileExists(imageUrl)) {
            return MediaUtils.urlToBase64DataUrl(imageUrl);
        }
        return imageUrl;
    }

    /**
     * 根据给定图像生成文本。
     *
     * @param imageUrls 待转为文本的图像 URL 列表
     * @param prompt 文本提示
     * @param model 使用的模型，如 qwen3-vl-plus、qwen-vl-plus、qvq-plus
     * @return 包含生成文本或错误消息的 ToolResultBlock
     */
    @Tool(
            name = "dashscope_image_to_text",
            description = "Generate text based on the given images.")
    public Mono<ToolResultBlock> dashscopeImageToText(
            @ToolParam(
                            name = "image_urls",
                            description =
                                    "The URL(s), local file path(s) or Base64 data URL(s)(the"
                                        + " format pattern is"
                                        + " data:[MIME_type];base64,{base64_image}, e.g.,"
                                        + " 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAABDg...')"
                                        + " of image(s) to be converted into text.")
                    List<String> imageUrls,
            @ToolParam(name = "prompt", description = "The text prompt.", required = false)
                    String prompt,
            @ToolParam(
                            name = "model",
                            description =
                                    "The model to use, e.g., 'qwen3-vl-plus', qwen-vl-plus'"
                                            + " 'qwen-vl-max', etc.",
                            required = false)
                    String model) {

        String finalPrompt = Optional.ofNullable(prompt).orElse("Describe the image");
        String finalModel =
                Optional.ofNullable(model).filter(s -> !s.trim().isEmpty()).orElse("qwen3-vl-plus");
        log.debug("dashscope_image_to_text called: prompt='{}', model='{}'", prompt, finalModel);

        return Mono.fromCallable(
                        () -> {
                            List<MultiModalMessage> multiModalMessages = new ArrayList<>();
                            MultiModalMessage systemMessage =
                                    MultiModalMessage.builder()
                                            .role(Role.SYSTEM.getValue())
                                            .content(
                                                    List.of(
                                                            Map.of(
                                                                    "text",
                                                                    "You are a helpful"
                                                                            + " assistant.")))
                                            .build();

                            List<Map<String, Object>> content = new ArrayList<>();

                            for (String url : imageUrls) {
                                try {
                                    content.add(Map.of("image", MediaUtils.urlToProtocolUrl(url)));
                                } catch (IOException e) {
                                    return ToolResultBlock.error(e.getMessage());
                                }
                            }

                            content.add(Map.of("text", finalPrompt));
                            MultiModalMessage userMessage =
                                    MultiModalMessage.builder()
                                            .role(Role.USER.getValue())
                                            .content(content)
                                            .build();

                            multiModalMessages.add(systemMessage);
                            multiModalMessages.add(userMessage);

                            MultiModalConversationParam param =
                                    MultiModalConversationParam.builder()
                                            .apiKey(this.apiKey)
                                            .model(finalModel)
                                            .messages(multiModalMessages)
                                            .header("user-agent", Version.getUserAgent())
                                            .build();

                            MultiModalConversation conv = new MultiModalConversation();
                            MultiModalConversationResult result = conv.call(param);

                            String text =
                                    Optional.ofNullable(result)
                                            .map(MultiModalConversationResult::getOutput)
                                            .map(MultiModalConversationOutput::getChoices)
                                            .flatMap(choices -> choices.stream().findFirst())
                                            .map(MultiModalConversationOutput.Choice::getMessage)
                                            .map(MultiModalMessage::getContent)
                                            .flatMap(contents -> contents.stream().findFirst())
                                            .map(contentMap -> contentMap.get("text"))
                                            .map(Object::toString)
                                            .orElse(null);

                            if (text == null) {
                                log.error("MultiModalConversation response text is empty.");
                                return ToolResultBlock.error("Failed to generate text.");
                            }

                            return ToolResultBlock.of(TextBlock.builder().text(text).build());
                        })
                .onErrorResume(
                        e -> {
                            log.error("Failed to generate text '{}'", e.getMessage(), e);
                            return Mono.just(ToolResultBlock.error(e.getMessage()));
                        });
    }

    /**
     * 将给定文本转换为音频。
     *
     * <p>支持两类 TTS 模型：
     * <ul>
     * <li>Qwen TTS（qwen3-tts-flash、qwen-tts）：使用多模态生成 API。</li>
     * <li>Sambert（sambert-*）：使用语音合成 SDK。</li>
     * </ul>
     *
     * @param text 待转为音频的文本
     * @param model TTS 模型；Qwen 示例为 qwen3-tts-flash、qwen-tts，Sambert 示例为 sambert-zhinan-v1、sambert-zhiqi-v1、sambert-zhichu-v1
     * @param voice Qwen TTS 音色名称，如 Cherry、Serena；Sambert 忽略此参数
     * @param language Qwen TTS 语言类型，如 Chinese、English；Sambert 忽略此参数
     * @param sampleRate 音频采样率，如 16000、24000、48000
     * @return 包含音频 Base64 数据或错误消息的 ToolResultBlock
     */
    @Tool(name = "dashscope_text_to_audio", description = "Convert the given text to audio.")
    public Mono<ToolResultBlock> dashscopeTextToAudio(
            @ToolParam(name = "text", description = "The text to be converted into audio.")
                    String text,
            @ToolParam(
                            name = "model",
                            description =
                                    "The TTS model to use. For Qwen TTS: 'qwen3-tts-flash',"
                                            + " 'qwen-tts'. For Sambert: 'sambert-zhinan-v1',"
                                            + " 'sambert-zhiqi-v1', 'sambert-zhichu-v1', etc.",
                            required = false)
                    String model,
            @ToolParam(
                            name = "voice",
                            description =
                                    "Voice name for Qwen TTS models, e.g., 'Cherry', 'Serena'."
                                            + " Ignored for Sambert models.",
                            required = false)
                    String voice,
            @ToolParam(
                            name = "language",
                            description =
                                    "Language type for Qwen TTS, e.g., 'Chinese', 'English'."
                                            + " Ignored for Sambert models.",
                            required = false)
                    String language,
            @ToolParam(
                            name = "sample_rate",
                            description = "Sample rate of the audio (e.g., 16000, 24000, 48000).",
                            required = false)
                    Integer sampleRate) {

        String finalModel =
                Optional.ofNullable(model)
                        .filter(s -> !s.trim().isEmpty())
                        .orElse("qwen3-tts-flash");
        Integer finalSampleRate = Optional.ofNullable(sampleRate).orElse(24000);

        // 检查是否为 Qwen TTS 模型。
        boolean isQwenTTS = finalModel.startsWith("qwen3-tts") || finalModel.startsWith("qwen-tts");

        log.debug(
                "dashscope_text_to_audio called: text='{}', model='{}', voice='{}', language='{}',"
                        + " sampleRate='{}', isQwenTTS='{}'",
                text,
                finalModel,
                voice,
                language,
                finalSampleRate,
                isQwenTTS);

        if (isQwenTTS) {
            return synthesizeWithQwenTTS(text, finalModel, voice, language);
        } else {
            return synthesizeWithSambert(text, finalModel, finalSampleRate);
        }
    }

    /**
     * 通过多模态生成 API 使用 Qwen TTS 合成音频。
     *
     * <p>处理与 DashScope Qwen TTS 端点的 HTTP 通信，构建包含文本、音色及语言参数的请求，再解析响应提取音频数据。
     *
     * <p>此处使用多模态生成端点，不同于其他模型采用的标准语音合成端点。
     *
     * @param text 待合成为语音的文本
     * @param model Qwen TTS 模型名称，如 qwen3-tts-flash、qwen-tts
     * @param voice 合成音色，null/空时默认 Cherry
     * @param language 语言类型，null/空时默认 Chinese
     * @return 成功时发出包含 AudioBlock 的 ToolResultBlock，失败时发出错误 ToolResultBlock 的 Mono
     */
    private Mono<ToolResultBlock> synthesizeWithQwenTTS(
            String text, String model, String voice, String language) {
        String finalVoice =
                Optional.ofNullable(voice).filter(s -> !s.trim().isEmpty()).orElse("Cherry");
        String finalLanguage =
                Optional.ofNullable(language).filter(s -> !s.trim().isEmpty()).orElse("Chinese");

        return Mono.fromCallable(
                        () -> {
                            // 构建 Qwen TTS API 请求。
                            Map<String, Object> input = new HashMap<>();
                            input.put("text", text);
                            input.put("voice", finalVoice);
                            input.put("language_type", finalLanguage);

                            Map<String, Object> request = new HashMap<>();
                            request.put("model", model);
                            request.put("input", input);

                            String requestBody = JsonUtils.getJsonCodec().toJson(request);

                            // 使用 Java HttpClient 调用 DashScope API。
                            HttpClient client = HttpClient.newHttpClient();
                            HttpRequest httpRequest =
                                    HttpRequest.newBuilder()
                                            .uri(
                                                    URI.create(
                                                            "https://dashscope.aliyuncs.com/api/v1/services"
                                                                + "/aigc/multimodal-generation/generation"))
                                            .header("Authorization", "Bearer " + this.apiKey)
                                            .header("Content-Type", "application/json")
                                            .header("User-Agent", Version.getUserAgent())
                                            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                                            .build();

                            HttpResponse<String> response =
                                    client.send(httpRequest, HttpResponse.BodyHandlers.ofString());

                            if (response.statusCode() != 200) {
                                log.error(
                                        "Qwen TTS API failed: status={}, body={}",
                                        response.statusCode(),
                                        response.body());
                                return ToolResultBlock.error(
                                        "TTS API failed: " + response.statusCode());
                            }

                            return parseQwenTTSResponse(response.body());
                        })
                .onErrorResume(
                        e -> {
                            log.error(
                                    "Failed to generate audio with Qwen TTS: '{}'",
                                    e.getMessage(),
                                    e);
                            return Mono.just(ToolResultBlock.error(e.getMessage()));
                        });
    }

    /**
     * 解析 Qwen TTS API 响应并提取音频数据。
     *
     * <p>响应结构如下：
     * <pre>{@code
     * {
     *   "request_id": "...",
     *   "output": {
     *     "audio": {
     *       "url": "https://..."  // 或 "data": "base64..."
     *     }
     *   }
     * }
     * }</pre>
     *
     * <p>处理两种音频格式：
     * <ul>
     * <li>URL：返回使用 URLSource 的 AudioBlock。</li>
     * <li>Base64：返回使用 Base64Source 的 AudioBlock。</li>
     * </ul>
     *
     * @param responseBody API 返回的原始 JSON 响应体
     * @return 成功时返回包含 AudioBlock 的 ToolResultBlock；解析失败或响应含错误时返回错误 ToolResultBlock
     */
    private ToolResultBlock parseQwenTTSResponse(String responseBody) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response =
                    JsonUtils.getJsonCodec().fromJson(responseBody, Map.class);

            // 检查错误。
            if (response.containsKey("code") && response.get("code") != null) {
                String message =
                        response.containsKey("message")
                                ? response.get("message").toString()
                                : "Unknown error";
                log.error("Qwen TTS error: {}", message);
                return ToolResultBlock.error(message);
            }

            // 从 output 提取音频。
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) response.get("output");
            if (output == null) {
                return ToolResultBlock.error("No output in response");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> audio = (Map<String, Object>) output.get("audio");
            if (audio == null) {
                return ToolResultBlock.error("No audio in response");
            }

            // 检查 URL 或 Base64 数据。
            if (audio.containsKey("url") && audio.get("url") != null) {
                String url = audio.get("url").toString();
                return ToolResultBlock.of(
                        AudioBlock.builder().source(URLSource.builder().url(url).build()).build());
            } else if (audio.containsKey("data") && audio.get("data") != null) {
                String data = audio.get("data").toString();
                return ToolResultBlock.of(
                        AudioBlock.builder()
                                .source(
                                        Base64Source.builder()
                                                .mediaType("audio/wav")
                                                .data(data)
                                                .build())
                                .build());
            } else {
                return ToolResultBlock.error("No audio data in response");
            }
        } catch (Exception e) {
            log.error("Failed to parse Qwen TTS response: {}", e.getMessage());
            return ToolResultBlock.error("Failed to parse response: " + e.getMessage());
        }
    }

    /**
     * 通过语音合成 SDK 使用 Sambert 模型合成音频。
     */
    private Mono<ToolResultBlock> synthesizeWithSambert(
            String text, String model, Integer sampleRate) {
        return Mono.fromCallable(
                        () -> {
                            SpeechSynthesisParam param =
                                    SpeechSynthesisParam.builder()
                                            .apiKey(this.apiKey)
                                            .text(text)
                                            .model(model)
                                            .sampleRate(sampleRate)
                                            .format(SpeechSynthesisAudioFormat.WAV)
                                            .header("user-agent", Version.getUserAgent())
                                            .build();
                            SpeechSynthesizer synthesizer = new SpeechSynthesizer();
                            ByteBuffer byteBuffer = synthesizer.call(param);
                            if (byteBuffer == null || byteBuffer.remaining() == 0) {
                                log.error("Audio byte buffer is empty.");
                                return ToolResultBlock.error("Failed to generate audio.");
                            }
                            byte[] bytes = new byte[byteBuffer.remaining()];
                            byteBuffer.get(bytes);
                            String data = Base64.getEncoder().encodeToString(bytes);

                            return ToolResultBlock.of(
                                    AudioBlock.builder()
                                            .source(
                                                    Base64Source.builder()
                                                            .mediaType("audio/wav")
                                                            .data(data)
                                                            .build())
                                            .build());
                        })
                .onErrorResume(
                        e -> {
                            log.error(
                                    "Failed to generate audio with Sambert: '{}'",
                                    e.getMessage(),
                                    e);
                            return Mono.just(ToolResultBlock.error(e.getMessage()));
                        });
    }

    /**
     * 将给定音频转换为文本。
     *
     * @param audioUrl 待识别音频的文件路径或 URL
     * @param model 语音识别模型，如 paraformer-realtime-v1、paraformer-realtime-8k-v1、paraformer-realtime-v2、paraformer-realtime-8k-v2
     * @param sampleRate 音频采样率，如 8000、16000
     * @return 包含识别文本或错误消息的 ToolResultBlock
     */
    @Tool(name = "dashscope_audio_to_text", description = "Convert the given audio to text.")
    public Mono<ToolResultBlock> dashscopeAudioToText(
            @ToolParam(
                            name = "audio_url",
                            description =
                                    "The file path or URL of audio to be converted into text.")
                    String audioUrl,
            @ToolParam(
                            name = "model",
                            description =
                                    "The TTS model to use, e.g., 'paraformer-realtime-v1',"
                                        + " 'paraformer-realtime-8k-v1', 'paraformer-realtime-v2',"
                                        + " 'paraformer-realtime-8k-v2'.",
                            required = false)
                    String model,
            @ToolParam(
                            name = "sample_rate",
                            description = "Sample rate of the audio (e.g., 8000, 16000).",
                            required = false)
                    Integer sampleRate) {

        String finalModel =
                Optional.ofNullable(model)
                        .filter(s -> !s.trim().isEmpty())
                        .orElse("paraformer-realtime-v2");
        Integer finalSampleRate = Optional.ofNullable(sampleRate).orElse(16000);
        log.debug(
                "dashscope_audio_to_text called: audioUrl='{}', model='{}', sampleRate='{}'",
                audioUrl,
                finalModel,
                finalSampleRate);

        return Mono.fromCallable(
                        () -> {
                            AtomicReference<TextBlock> textBlockRef = new AtomicReference<>(null);
                            CountDownLatch stopLatch = new CountDownLatch(1);

                            RecognitionParam param =
                                    RecognitionParam.builder()
                                            .apiKey(this.apiKey)
                                            .model(finalModel)
                                            .format(MediaUtils.getExtension(audioUrl))
                                            .sampleRate(finalSampleRate)
                                            // "language_hints" 仅支持
                                            // paraformer-realtime-v2 模型。
                                            .parameter("language_hints", new String[] {"zh", "en"})
                                            .header("user-agent", Version.getUserAgent())
                                            .build();
                            Recognition recognizer = new Recognition();
                            ResultCallback<RecognitionResult> callback =
                                    new ResultCallback<>() {
                                        @Override
                                        public void onEvent(RecognitionResult message) {
                                            String text = message.getSentence().getText();
                                            if (message.isSentenceEnd()) {
                                                log.debug("Final Result: " + text);
                                                textBlockRef.set(
                                                        TextBlock.builder().text(text).build());
                                            } else {
                                                log.debug("Intermediate Result: " + text);
                                            }
                                        }

                                        @Override
                                        public void onComplete() {
                                            stopLatch.countDown();
                                            log.debug("Recognition complete");
                                        }

                                        @Override
                                        public void onError(Exception e) {
                                            stopLatch.countDown();
                                            log.error(
                                                    "RecognitionCallback error: " + e.getMessage());
                                        }
                                    };

                            try {
                                recognizer.call(param, callback);

                                this.sendAudioChunk(audioUrl, recognizer);

                                recognizer.stop();

                                if (!stopLatch.await(60, TimeUnit.SECONDS)) {
                                    log.error("Timeout waiting for recognition to complete.");
                                    return ToolResultBlock.error("Failed to transcribe audio.");
                                }

                                TextBlock textBlock = textBlockRef.get();
                                if (textBlock == null) {
                                    log.error("Generate text is empty.");
                                    return ToolResultBlock.error("Failed to transcribe audio.");
                                }
                                return ToolResultBlock.of(textBlock);
                            } finally {
                                // 任务结束后关闭 WebSocket。
                                log.debug("Finish transcribe audio.");
                                recognizer.getDuplexApi().close(1000, "bye");
                            }
                        })
                .onErrorResume(
                        e -> {
                            log.error("Failed to transcribe audio, '{}'", e.getMessage(), e);
                            return Mono.just(ToolResultBlock.error(e.getMessage()));
                        });
    }

    /**
     * 按分块向 DashScope 发送音频输入流。
     * 使用包级可见性以便单元测试。
     *
     * @param audioUrl 音频文件路径或 URL
     * @param recognizer DashScope Recognition 实例
     * @throws IOException 当读取失败时
     * @throws InterruptedException 当被中断时
     */
    void sendAudioChunk(String audioUrl, Recognition recognizer)
            throws IOException, InterruptedException {
        // 分块大小按 16 KHz 采样率约 1 秒音频设置。
        byte[] buffer = new byte[3200];
        int bytesRead;
        if (MediaUtils.isFileExists(audioUrl)) {
            try ( // 读取文件并分块发送音频。
            InputStream is = Files.newInputStream(Paths.get(audioUrl))) {
                while ((bytesRead = is.read(buffer)) != -1) {
                    ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, bytesRead);
                    recognizer.sendAudioFrame(byteBuffer);

                    // 避免过高 CPU 占用。
                    Thread.sleep(100);
                }
            }
        } else {
            try ( // 读取网页 URL 并分块发送音频。
            InputStream is = URI.create(audioUrl).toURL().openStream()) {
                while ((bytesRead = is.read(buffer)) != -1) {
                    ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, bytesRead);
                    recognizer.sendAudioFrame(byteBuffer);

                    // 避免过高 CPU 占用。
                    Thread.sleep(100);
                }
            }
        }
    }

    /**
     * 根据提示生成视频。
     *
     * @param prompt 生成视频的文本提示
     * @param model 模型，如 wan2.6-t2v、wan2.5-t2v-preview
     * @param negativePrompt 排除特定元素的负向提示
     * @param audioUrl 背景音频 URL
     * @param size 视频尺寸，如 1920*1080、1280*720
     * @param duration 视频时长（秒），如 5、10
     * @param shotType 镜头类型：single 为默认单镜头，multi 为多镜头
     * @param promptExtend 是否自动扩展提示（默认 true）
     * @param watermark 是否包含水印（默认 false）
     * @param seed 用于复现的随机种子
     * @return 包含视频 URL 或错误消息的 ToolResultBlock
     */
    @Tool(
            name = "dashscope_text_to_video",
            description = "Generate video based on the given text prompt")
    public Mono<ToolResultBlock> dashscopeTextToVideo(
            @ToolParam(name = "prompt", description = "The text prompt to generate video")
                    String prompt,
            @ToolParam(
                            name = "model",
                            description =
                                    "The model to use, e.g., 'wan2.6-t2v', 'wan2.5-t2v-preview',"
                                            + " etc",
                            required = false)
                    String model,
            @ToolParam(
                            name = "negative_prompt",
                            description = "The negative prompt to avoid certain elements",
                            required = false)
                    String negativePrompt,
            @ToolParam(
                            name = "audio_url",
                            description = "The URL for background audio",
                            required = false)
                    String audioUrl,
            @ToolParam(
                            name = "size",
                            description = "Size of the video, e.g., '1920*1080', '1280*720', etc",
                            required = false)
                    String size,
            @ToolParam(
                            name = "duration",
                            description = "Duration of the video in seconds, e.g., '5', '10', etc",
                            required = false)
                    Integer duration,
            @ToolParam(
                            name = "shot_type",
                            description =
                                    "Specify the shot type that generates the video. single:"
                                        + " default value, output single shot video; multi: output"
                                        + " multi-lens video",
                            required = false)
                    String shotType,
            @ToolParam(
                            name = "prompt_extend",
                            description =
                                    "Whether to automatically extend the prompt (default true)",
                            required = false)
                    Boolean promptExtend,
            @ToolParam(
                            name = "watermark",
                            description = "Whether to include watermark (default false)",
                            required = false)
                    Boolean watermark,
            @ToolParam(
                            name = "seed",
                            description = "The seed for reproducibility",
                            required = false)
                    Integer seed) {

        String finalModel =
                Optional.ofNullable(model).filter(s -> !s.trim().isEmpty()).orElse("wan2.6-t2v");
        String finalSize =
                Optional.ofNullable(size).filter(s -> !s.trim().isEmpty()).orElse("1920*1080");
        String finalShotType = Optional.ofNullable(shotType).orElse("single");
        boolean finalPromptExtend = Optional.ofNullable(promptExtend).orElse(true);
        boolean finalWatermark = Optional.ofNullable(watermark).orElse(false);

        log.debug(
                "dashscope_text_to_video called: prompt='{}', model='{}', negativePrompt='{}',"
                    + " audioUrl='{}', size='{}', duration={}, shotType='{}', promptExtend='{}',"
                    + " watermark='{}', seed={}",
                prompt,
                finalModel,
                negativePrompt,
                audioUrl,
                finalSize,
                duration,
                finalShotType,
                finalPromptExtend,
                finalWatermark,
                seed);

        return Mono.fromCallable(
                        () -> {
                            Map<String, Object> parameters = new HashMap<>();
                            parameters.put("size", finalSize);
                            parameters.put("shot_type", finalShotType);
                            parameters.put("prompt_extend", finalPromptExtend);
                            parameters.put("watermark", finalWatermark);
                            if (duration != null) {
                                parameters.put("duration", duration);
                            }
                            if (seed != null) {
                                parameters.put("seed", seed);
                            }

                            VideoSynthesisParam param =
                                    VideoSynthesisParam.builder()
                                            .apiKey(this.apiKey)
                                            .model(finalModel)
                                            .prompt(prompt)
                                            .negativePrompt(negativePrompt)
                                            .audioUrl(audioUrl)
                                            .parameters(parameters)
                                            .header("user-agent", Version.getUserAgent())
                                            .build();

                            VideoSynthesis videoSynthesis = new VideoSynthesis();

                            // 视频调用方法会阻塞，直到视频生成成功或失败。
                            log.info(
                                    "Starting text to video generation task, please wait for a"
                                            + " while...");
                            VideoSynthesisResult response = videoSynthesis.call(param);

                            // 从响应提取视频 URL。
                            String videoUrl =
                                    Optional.ofNullable(response)
                                            .map(VideoSynthesisResult::getOutput)
                                            .map(VideoSynthesisOutput::getVideoUrl)
                                            .orElse(null);

                            if (videoUrl == null || videoUrl.trim().isEmpty()) {
                                log.error("No video url returned. Response: {}", response);
                                return ToolResultBlock.error("Failed to generate video.");
                            }

                            log.info("Text to video generated successfully, videoUrl:{}", videoUrl);

                            VideoBlock vb =
                                    VideoBlock.builder()
                                            .source(URLSource.builder().url(videoUrl).build())
                                            .build();

                            return ToolResultBlock.of(vb);
                        })
                .onErrorResume(
                        e -> {
                            log.error("Failed to generate video '{}'", e.getMessage(), e);
                            return Mono.just(ToolResultBlock.error(e.getMessage()));
                        });
    }

    /**
     * 根据单张输入图像（首帧）和可选文本提示生成视频。
     *
     * @param prompt 描述视频内容与运动的文本提示
     * @param model 模型，如 wan2.6-i2v-flash、wan2.1-i2v-turbo
     * @param imageUrl 输入首帧图像的 URL、本地路径或 Base64 数据
     * @param audioUrl 模型用于生成视频的音频文件 URL
     * @param negativePrompt 排除特定元素的负向提示
     * @param template 视频特效模板名称，如 squish、rotation
     * @param resolution 视频分辨率，如 720P、1080P
     * @param duration 视频时长（秒，如 5、10、15），默认依模型而定
     * @param shotType 镜头类型：single 为默认单镜头，multi 为多镜头
     * @param audio 是否生成带音频的视频（默认 true）
     * @param promptExtend 是否自动扩展提示（默认 true）
     * @param watermark 是否包含水印（默认 false）
     * @param seed 可选随机种子，用于复现
     * @return 包含视频 URL 或错误消息的 ToolResultBlock
     */
    @Tool(
            name = "dashscope_image_to_video",
            description =
                    "Generate a video from a single input image and an optional text prompt."
                            + "Supports optional audio guidance and duration control.")
    public Mono<ToolResultBlock> dashscopeImageToVideo(
            @ToolParam(
                            name = "prompt",
                            description = "Text prompt describing the video content and motion",
                            required = false)
                    String prompt,
            @ToolParam(
                            name = "model",
                            description =
                                    "Model to use, e.g., 'wan2.6-i2v-flash', 'wan2.6-i2v', etc",
                            required = false)
                    String model,
            @ToolParam(
                            name = "image_url",
                            description =
                                    "URL, local file path or Base64 data URL((the format pattern is"
                                        + " data:[MIME_type];base64,{base64_image}, e.g.,"
                                        + " 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAABDg...'))"
                                        + " of the first frame image")
                    String imageUrl,
            @ToolParam(
                            name = "audio_url",
                            description =
                                    "URL of the audio file that the model will use to generate"
                                            + " video",
                            required = false)
                    String audioUrl,
            @ToolParam(
                            name = "negative_prompt",
                            description = "The negative prompt to avoid certain elements",
                            required = false)
                    String negativePrompt,
            @ToolParam(
                            name = "template",
                            description =
                                    "Name of video effect template, e.g., 'squish', 'rotation',"
                                            + " etc",
                            required = false)
                    String template,
            @ToolParam(
                            name = "resolution",
                            description = "Video resolution, e.g., '720P', '1080P'",
                            required = false)
                    String resolution,
            @ToolParam(
                            name = "duration",
                            description = "Duration of the video in seconds, e.g., 5, 10, 15",
                            required = false)
                    Integer duration,
            @ToolParam(
                            name = "shot_type",
                            description =
                                    "Specify the shot type that generates the video. single:"
                                        + " default value, output single shot video; multi: output"
                                        + " multi-lens video",
                            required = false)
                    String shotType,
            @ToolParam(
                            name = "audio",
                            description = "Whether to generate audio video (default true)",
                            required = false)
                    Boolean audio,
            @ToolParam(
                            name = "prompt_extend",
                            description =
                                    "Whether to automatically extend the prompt (default true)",
                            required = false)
                    Boolean promptExtend,
            @ToolParam(
                            name = "watermark",
                            description = "Whether to include watermark (default false)",
                            required = false)
                    Boolean watermark,
            @ToolParam(
                            name = "seed",
                            description = "The seed for reproducibility",
                            required = false)
                    Integer seed) {

        String finalModel =
                Optional.ofNullable(model)
                        .filter(s -> !s.trim().isEmpty())
                        .orElse("wan2.6-i2v-flash");
        String finalResolution =
                Optional.ofNullable(resolution).filter(s -> !s.trim().isEmpty()).orElse("720P");
        String finalShotType = Optional.ofNullable(shotType).orElse("single");
        boolean finalAudio = Optional.ofNullable(audio).orElse(true);
        boolean finalPromptExtend = Optional.ofNullable(promptExtend).orElse(true);
        boolean finalWatermark = Optional.ofNullable(watermark).orElse(false);

        log.debug(
                "dashscope_image_to_video called: prompt='{}', model='{}', imageUrl='{}',"
                        + " audioUrl='{}', negativePrompt='{}', template='{}', resolution='{}',"
                        + " duration={}, shotType='{}', audio={}, promptExtend={}, watermark={},"
                        + " seed={}",
                prompt,
                finalModel,
                imageUrl,
                audioUrl,
                negativePrompt,
                template,
                finalResolution,
                duration,
                finalShotType,
                finalAudio,
                finalPromptExtend,
                finalWatermark,
                seed);

        return Mono.fromCallable(
                        () -> {
                            Map<String, Object> parameters = new HashMap<>();
                            parameters.put("resolution", finalResolution);
                            parameters.put("shot_type", finalShotType);
                            parameters.put("audio", finalAudio);
                            parameters.put("prompt_extend", finalPromptExtend);
                            parameters.put("watermark", finalWatermark);
                            if (duration != null) {
                                parameters.put("duration", duration);
                            }
                            if (seed != null) {
                                parameters.put("seed", seed);
                            }

                            VideoSynthesisParam param =
                                    VideoSynthesisParam.builder()
                                            .apiKey(this.apiKey)
                                            .prompt(prompt)
                                            .model(finalModel)
                                            .imgUrl(MediaUtils.urlToProtocolUrl(imageUrl))
                                            .audioUrl(audioUrl)
                                            .negativePrompt(negativePrompt)
                                            .template(template)
                                            .parameters(parameters)
                                            .header("user-agent", Version.getUserAgent())
                                            .build();

                            VideoSynthesis videoSynthesis = new VideoSynthesis();

                            log.info(
                                    "Starting image to video generation task, please wait for a"
                                            + " while...");
                            VideoSynthesisResult response = videoSynthesis.call(param);

                            // 从响应提取视频 URL。
                            String videoUrl =
                                    Optional.ofNullable(response)
                                            .map(VideoSynthesisResult::getOutput)
                                            .map(VideoSynthesisOutput::getVideoUrl)
                                            .orElse(null);

                            if (videoUrl == null || videoUrl.trim().isEmpty()) {
                                log.error("Failed to generate video. No video url returned.");
                                return ToolResultBlock.error("Failed to generate video.");
                            }

                            log.info(
                                    "Image to video generated successfully, videoUrl:{}", videoUrl);

                            VideoBlock vb =
                                    VideoBlock.builder()
                                            .source(URLSource.builder().url(videoUrl).build())
                                            .build();

                            return ToolResultBlock.of(vb);
                        })
                .onErrorResume(
                        e -> {
                            log.error("Failed to generate video: '{}'", e.getMessage(), e);
                            return Mono.just(ToolResultBlock.error(e.getMessage()));
                        });
    }

    /**
     * 根据首尾帧和可选文本提示生成过渡视频。
     *
     * @param prompt 描述视频内容与镜头运动的文本提示
     * @param model 模型，如 wan2.2-kf2v-flash、wanx2.1-kf2v-plus
     * @param firstFrameUrl 首帧图像 URL 或 Base64 数据
     * @param lastFrameUrl 尾帧图像 URL 或 Base64 数据
     * @param negativePrompt 排除特定元素的负向提示
     * @param template 视频特效模板名称，如 hanfu-1、solaron
     * @param resolution 视频分辨率，如 480P、720P、1080P
     * @param promptExtend 是否自动扩展提示（默认 true）
     * @param watermark 是否包含水印（默认 false）
     * @param seed 可选随机种子，用于复现
     * @return 包含视频 URL 或错误消息的 ToolResultBlock
     */
    @Tool(
            name = "dashscope_first_and_last_frame_image_to_video",
            description =
                    "Generate video transitioning from a first frame to a last frame and an"
                            + " optional text prompt")
    public Mono<ToolResultBlock> dashscopeFirstAndLastFrameImageToVideo(
            @ToolParam(
                            name = "prompt",
                            description = "Text prompt describing the video content and motion",
                            required = false)
                    String prompt,
            @ToolParam(
                            name = "model",
                            description =
                                    "Model to use, e.g., 'wan2.2-kf2v-flash', 'wanx2.1-kf2v-plus'",
                            required = false)
                    String model,
            @ToolParam(
                            name = "first_frame_url",
                            description =
                                    "URL, local file path or Base64 data URL(the format pattern is"
                                        + " data:[MIME_type];base64,{base64_image}, e.g.,"
                                        + " 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAABDg...')"
                                        + " of the first frame image")
                    String firstFrameUrl,
            @ToolParam(
                            name = "last_frame_url",
                            description =
                                    "URL, local file path or Base64 data URL(the format pattern is"
                                        + " data:[MIME_type];base64,{base64_image}, e.g.,"
                                        + " 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAABDg...')"
                                        + " of the last frame image",
                            required = false)
                    String lastFrameUrl,
            @ToolParam(
                            name = "negative_prompt",
                            description = "The negative prompt to avoid certain elements",
                            required = false)
                    String negativePrompt,
            @ToolParam(
                            name = "template",
                            description =
                                    "Name of video effect template, e.g., 'hanfu-1', 'solaron',"
                                            + " etc",
                            required = false)
                    String template,
            @ToolParam(
                            name = "resolution",
                            description = "Video resolution, e.g., '720P', '1080P'",
                            required = false)
                    String resolution,
            @ToolParam(
                            name = "prompt_extend",
                            description =
                                    "Whether to automatically extend the prompt (default true)",
                            required = false)
                    Boolean promptExtend,
            @ToolParam(
                            name = "watermark",
                            description = "Whether to include watermark (default false)",
                            required = false)
                    Boolean watermark,
            @ToolParam(
                            name = "seed",
                            description = "The seed for reproducibility",
                            required = false)
                    Integer seed) {

        String finalModel =
                Optional.ofNullable(model)
                        .filter(s -> !s.trim().isEmpty())
                        .orElse("wan2.2-kf2v-flash");
        String finalResolution =
                Optional.ofNullable(resolution).filter(s -> !s.trim().isEmpty()).orElse("720P");
        boolean finalPromptExtend = Optional.ofNullable(promptExtend).orElse(true);
        boolean finalWatermark = Optional.ofNullable(watermark).orElse(false);

        log.debug(
                "dashscope_first_and_last_frame_image_to_video called: prompt='{}', model='{}',"
                    + " firstFrameUrl='{}', lastFrameUrl='{}', negativePrompt='{}', template='{}',"
                    + " resolution='{}', promptExtend={}, watermark={}, seed={}",
                prompt,
                finalModel,
                firstFrameUrl,
                lastFrameUrl,
                negativePrompt,
                template,
                finalResolution,
                finalPromptExtend,
                finalWatermark,
                seed);

        return Mono.fromCallable(
                        () -> {
                            Map<String, Object> parameters = new HashMap<>();
                            parameters.put("resolution", finalResolution);
                            parameters.put("prompt_extend", finalPromptExtend);
                            parameters.put("watermark", finalWatermark);
                            if (seed != null) {
                                parameters.put("seed", seed);
                            }

                            VideoSynthesisParam param =
                                    VideoSynthesisParam.builder()
                                            .apiKey(this.apiKey)
                                            .model(finalModel)
                                            .prompt(prompt)
                                            .firstFrameUrl(
                                                    MediaUtils.urlToProtocolUrl(firstFrameUrl))
                                            .lastFrameUrl(MediaUtils.urlToProtocolUrl(lastFrameUrl))
                                            .negativePrompt(negativePrompt)
                                            .template(template)
                                            .parameters(parameters)
                                            .header("user-agent", Version.getUserAgent())
                                            .build();

                            VideoSynthesis videoSynthesis = new VideoSynthesis();

                            log.info(
                                    "Starting first and last frame image to video generation task,"
                                            + " please wait for a while...");
                            // 视频调用方法会阻塞，直到视频生成成功或失败。
                            VideoSynthesisResult response = videoSynthesis.call(param);

                            // 提取视频 URL。
                            String videoUrl =
                                    Optional.ofNullable(response)
                                            .map(VideoSynthesisResult::getOutput)
                                            .map(VideoSynthesisOutput::getVideoUrl)
                                            .orElse(null);

                            if (videoUrl == null || videoUrl.trim().isEmpty()) {
                                log.error("Failed to generate video. No URL returned.");
                                return ToolResultBlock.error("Failed to generate video.");
                            }

                            log.info(
                                    "First and last frame image to video video generated"
                                            + " successfully, videoUrl:{}",
                                    videoUrl);

                            VideoBlock vb =
                                    VideoBlock.builder()
                                            .source(URLSource.builder().url(videoUrl).build())
                                            .build();

                            return ToolResultBlock.of(vb);
                        })
                .onErrorResume(
                        e -> {
                            log.error("Failed to generate key-frame video '{}'", e.getMessage(), e);
                            return Mono.just(ToolResultBlock.error(e.getMessage()));
                        });
    }

    /**
     * 分析视频并生成描述，或回答关于视频内容的问题。
     *
     * @param videoUrl 待分析视频的 URL 或本地路径
     * @param prompt 关于视频内容的文本提示或问题
     * @param model 视觉模型，如 qwen3.5-plus、qwen3.5-flash、qwen3-vl-plus、qwen3-vl-flash
     * @param fps 每秒采样帧数，如 1、2、4，默认 2
     * @return 包含文本分析结果或错误消息的 ToolResultBlock
     */
    @Tool(
            name = "dashscope_video_to_text",
            description =
                    "Analyze video and generate a text description or answer questions based on the"
                            + " video content.Supports controlling the frame sampling rate (fps).")
    public Mono<ToolResultBlock> dashscopeVideoToText(
            @ToolParam(
                            name = "video_url",
                            description =
                                    "The URL, local file path or base64 data URL(the format pattern"
                                        + " is data:[MIME_type];base64,{base64_video}, e.g.,"
                                        + " 'data:video/mp4;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAA...')"
                                        + " of the video to analyze.")
                    String videoUrl,
            @ToolParam(
                            name = "prompt",
                            description = "The question or instruction regarding the video content",
                            required = false)
                    String prompt,
            @ToolParam(
                            name = "model",
                            description =
                                    "The vision model to use, e.g., 'qwen3.5-plus',"
                                            + " 'qwen3.5-flash', 'qwen3-vl-plus', 'qwen3-vl-flash',"
                                            + " etc",
                            required = false)
                    String model,
            @ToolParam(
                            name = "fps",
                            description =
                                    "Frames per second to sample from the video for analysis"
                                            + " (default 2.0)",
                            required = false)
                    Double fps) {

        String finalModel =
                Optional.ofNullable(model).filter(s -> !s.trim().isEmpty()).orElse("qwen3.5-plus");
        String finalPrompt =
                Optional.ofNullable(prompt)
                        .filter(s -> !s.trim().isEmpty())
                        .orElse("Describe the video");
        double finalFps = Optional.ofNullable(fps).orElse(2.0);

        log.debug(
                "dashscope_video_to_text called: videoUrl:'{}', prompt='{}', model='{}', fps={}",
                videoUrl,
                prompt,
                finalModel,
                finalFps);

        return Mono.fromCallable(
                        () -> {
                            Map<String, Object> videoParams = new HashMap<>();
                            videoParams.put("video", MediaUtils.urlToProtocolUrl(videoUrl));
                            videoParams.put("fps", finalFps);
                            List<Map<String, Object>> content = new ArrayList<>();
                            content.add(Map.of("text", finalPrompt));
                            content.add(videoParams);
                            MultiModalMessage userMessage =
                                    MultiModalMessage.builder()
                                            .role(Role.USER.getValue())
                                            .content(content)
                                            .build();

                            MultiModalMessage systemMessage =
                                    MultiModalMessage.builder()
                                            .role(Role.SYSTEM.getValue())
                                            .content(
                                                    List.of(
                                                            Map.of(
                                                                    "text",
                                                                    "You are a helpful"
                                                                            + " assistant.")))
                                            .build();

                            List<MultiModalMessage> multiModalMessages = new ArrayList<>();
                            multiModalMessages.add(systemMessage);
                            multiModalMessages.add(userMessage);

                            MultiModalConversationParam param =
                                    MultiModalConversationParam.builder()
                                            .apiKey(this.apiKey)
                                            .model(finalModel)
                                            .messages(multiModalMessages)
                                            .header("user-agent", Version.getUserAgent())
                                            .build();

                            MultiModalConversation conv = new MultiModalConversation();
                            MultiModalConversationResult result = conv.call(param);

                            // 从结果提取文本。
                            String text =
                                    Optional.ofNullable(result)
                                            .map(MultiModalConversationResult::getOutput)
                                            .map(MultiModalConversationOutput::getChoices)
                                            .flatMap(choices -> choices.stream().findFirst())
                                            .map(MultiModalConversationOutput.Choice::getMessage)
                                            .map(MultiModalMessage::getContent)
                                            .flatMap(contents -> contents.stream().findFirst())
                                            .map(contentMap -> contentMap.get("text"))
                                            .map(Object::toString)
                                            .orElse(null);

                            if (text == null || text.trim().isEmpty()) {
                                log.error("Failed to analyze video. No text response returned.");
                                return ToolResultBlock.error("Failed to analyze video.");
                            }

                            log.info("Video analysis completed successfully.");

                            TextBlock tb = TextBlock.builder().text(text).build();
                            return ToolResultBlock.of(tb);
                        })
                .onErrorResume(
                        e -> {
                            log.error("Failed to analyze video '{}'", e.getMessage(), e);
                            return Mono.just(ToolResultBlock.error(e.getMessage()));
                        });
    }
}
