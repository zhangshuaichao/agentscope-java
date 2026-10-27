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

import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.extensions.model.dashscope.dto.DashScopeContentPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 处理 DashScope API 的媒体内容转换。
 * 将 ImageBlock/VideoBlock/AudioBlock 转换为兼容格式。
 *
 * <p>【学习提示】本地路径、远程 URL、内联 Base64 是不同传输形式。转换成功只说明生成了某种引用，不保证服务端能访问该地址；本地内容内联会增加请求大小，并涉及将本机数据发送给模型服务的授权边界。
 *
 * <p>本适配器将本地图像嵌入 data URL；音视频路径按各自转换方法处理，不能将 SDK 的本地文件支持直接等同于远端 HTTP API 能读取本机文件。
 */
public class DashScopeMediaConverter {

    private static final Logger log = LoggerFactory.getLogger(DashScopeMediaConverter.class);

    /**
     * 将 ImageBlock 转换为 DashScope API URL 字符串。
     *
     * <p>HTTP API 无法读取本地文件，因此将本地图像嵌入 data URL。
     *
     * <p>支持：
     * <ul>
     * <li>本地文件 → 带图像媒体类型的 Base64 data URL。</li>
     * <li>远程 URL → 原始 URL（如 https://example.com/image.png）。</li>
     * <li>Base64 来源 → data URL（如 data:image/png;base64,...）。</li>
     * </ul>
     *
     * @param imageBlock 待转换的图像块
     * @return DashScope API 使用的 URL 字符串
     * @throws Exception 当转换失败时
     */
    public String convertImageBlockToUrl(ImageBlock imageBlock) throws Exception {
        Source source = imageBlock.getSource();

        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            MediaUtils.validateImageExtension(url);
            if (url.startsWith("file:")) {
                return MediaUtils.urlToBase64DataUrl(
                        java.nio.file.Path.of(java.net.URI.create(url)).toString());
            }
            if (MediaUtils.isLocalFile(url)) {
                return MediaUtils.urlToBase64DataUrl(url);
            }
            return url;

        } else if (source instanceof Base64Source base64Source) {
            // Base64 来源：构建 data URL。
            String mediaType = base64Source.getMediaType();
            String base64Data = base64Source.getData();
            return String.format("data:%s;base64,%s", mediaType, base64Data);

        } else {
            throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
        }
    }

    /**
     * 将 ImageBlock 转换为 DashScopeContentPart。
     *
     * @param imageBlock 待转换的图像块
     * @return 图像对应的 DashScopeContentPart
     * @throws Exception 当转换失败时
     */
    public DashScopeContentPart convertImageBlockToContentPart(ImageBlock imageBlock)
            throws Exception {
        String imageUrl = convertImageBlockToUrl(imageBlock);
        return DashScopeContentPart.builder()
                .image(imageUrl)
                .minPixels(imageBlock.getMinPixels())
                .maxPixels(imageBlock.getMaxPixels())
                .build();
    }

    /**
     * 将 VideoBlock 转换为 DashScope API URL 字符串。
     *
     * <p>本地视频使用 file:// 协议。
     *
     * <p>支持：
     * <ul>
     * <li>本地文件 → file:// URL（如 file:///absolute/path/video.mp4）。</li>
     * <li>远程 URL → 原始 URL（如 https://example.com/video.mp4）。</li>
     * <li>Base64 来源 → data URL（如 data:video/mp4;base64,...）。</li>
     * </ul>
     *
     * @param videoBlock 待转换的视频块
     * @return DashScope API 使用的 URL 字符串
     * @throws Exception 当转换失败时
     */
    public String convertVideoBlockToUrl(VideoBlock videoBlock) throws Exception {
        Source source = videoBlock.getSource();

        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            MediaUtils.validateVideoExtension(url);
            return MediaUtils.urlToProtocolUrl(url);

        } else if (source instanceof Base64Source base64Source) {
            // Base64 来源：构建 data URL。
            String mediaType = base64Source.getMediaType();
            String base64Data = base64Source.getData();
            return String.format("data:%s;base64,%s", mediaType, base64Data);

        } else {
            throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
        }
    }

    /**
     * 将 VideoBlock 转换为 DashScopeContentPart。
     *
     * @param videoBlock 待转换的视频块
     * @return 视频对应的 DashScopeContentPart
     * @throws Exception 当转换失败时
     */
    public DashScopeContentPart convertVideoBlockToContentPart(VideoBlock videoBlock)
            throws Exception {
        String videoUrl = convertVideoBlockToUrl(videoBlock);
        return DashScopeContentPart.builder()
                .video(videoUrl)
                .fps(videoBlock.getFps())
                .maxFrames(videoBlock.getMaxFrames())
                .minPixels(videoBlock.getMinPixels())
                .maxPixels(videoBlock.getMaxPixels())
                .totalPixels(videoBlock.getTotalPixels())
                .build();
    }

    /**
     * 将 AudioBlock 转换为 DashScope API URL 字符串。
     *
     * <p>本地音频使用 file:// 协议。
     *
     * <p>支持：
     * <ul>
     * <li>本地文件 → file:// URL（如 file:///absolute/path/audio.mp3）。</li>
     * <li>远程 URL → 原始 URL（如 https://example.com/audio.mp3）。</li>
     * <li>Base64 来源 → data URL（如 data:audio/wav;base64,...）。</li>
     * </ul>
     *
     * @param audioBlock 待转换的音频块
     * @return DashScope API 使用的 URL 字符串
     * @throws Exception 当转换失败时
     */
    public String convertAudioBlockToUrl(AudioBlock audioBlock) throws Exception {
        Source source = audioBlock.getSource();

        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            // 注意：DashScope 对音频的校验支持可能与图像不同。
            return MediaUtils.urlToProtocolUrl(url);

        } else if (source instanceof Base64Source base64Source) {
            // Base64 来源：构建 data URL。
            String mediaType = base64Source.getMediaType();
            String base64Data = base64Source.getData();
            return String.format("data:%s;base64,%s", mediaType, base64Data);

        } else {
            throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
        }
    }

    /**
     * 将 AudioBlock 转换为 DashScopeContentPart。
     *
     * @param audioBlock 待转换的音频块
     * @return 音频对应的 DashScopeContentPart
     * @throws Exception 当转换失败时
     */
    public DashScopeContentPart convertAudioBlockToContentPart(AudioBlock audioBlock)
            throws Exception {
        String audioUrl = convertAudioBlockToUrl(audioBlock);
        return DashScopeContentPart.audio(audioUrl);
    }

    /**
     * 通过解析 MIME 类型，将 DataBlock 路由到适当图像、音频或视频字段，生成 DashScopeContentPart。
     *
     * <p>MIME 类型解析顺序：
     * <ol>
     * <li>{@code Base64Source.mediaType}：始终显式提供。</li>
     * <li>{@code URLSource.mimeType}：调用方为无扩展名 URL 提供的提示。</li>
     * <li>{@code MediaUtils.determineMediaType(url)}：根据扩展名推断。</li>
     * </ol>
     *
     * @param dataBlock 待转换的数据块
     * @return 对应媒体类型的 DashScopeContentPart
     * @throws Exception 当转换失败或无法解析 MIME 类型时
     */
    public DashScopeContentPart convertDataBlockToContentPart(DataBlock dataBlock)
            throws Exception {
        Source source = dataBlock.getSource();
        String mimeType = MediaUtils.resolveMimeType(source);

        if (mimeType.startsWith("image/")) {
            String url = sourceToUrl(source);
            return DashScopeContentPart.builder().image(url).build();
        } else if (mimeType.startsWith("audio/")) {
            String url = sourceToUrl(source);
            return DashScopeContentPart.audio(url);
        } else if (mimeType.startsWith("video/")) {
            String url = sourceToUrl(source);
            return DashScopeContentPart.builder().video(url).build();
        } else {
            throw new IllegalArgumentException(
                    "Cannot route DataBlock: unrecognised MIME type '" + mimeType + "'");
        }
    }

    // 将任意 Source 转换为 URL/data URL 字符串。
    private String sourceToUrl(Source source) throws Exception {
        if (source instanceof URLSource urlSource) {
            return MediaUtils.urlToProtocolUrl(urlSource.getUrl());
        }
        if (source instanceof Base64Source b64) {
            return String.format("data:%s;base64,%s", b64.getMediaType(), b64.getData());
        }
        throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
    }
}
