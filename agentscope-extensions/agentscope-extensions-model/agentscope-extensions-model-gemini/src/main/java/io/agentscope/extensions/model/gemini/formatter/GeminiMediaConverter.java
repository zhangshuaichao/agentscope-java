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
package io.agentscope.extensions.model.gemini.formatter;

import com.google.genai.types.Blob;
import com.google.genai.types.Part;
import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gemini API 多模态内容转换器，将 ImageBlock、AudioBlock 和 VideoBlock
 * 转换为带内联数据的 Gemini Part 对象。
 */
public class GeminiMediaConverter {

    private static final Logger log = LoggerFactory.getLogger(GeminiMediaConverter.class);

    /**
     * 每种媒体类型支持的文件扩展名。转换媒体块时验证这些扩展名，
     * 确保与 Gemini API 支持的格式兼容。
     */
    private static final Map<String, List<String>> SUPPORTED_EXTENSIONS =
            Map.of(
                    "image", List.of("png", "jpeg", "jpg", "webp", "heic", "heif"),
                    "video",
                            List.of(
                                    "mp4", "mpeg", "mov", "avi", "x-flv", "flv", "mpg", "webm",
                                    "wmv", "3gpp"),
                    "audio", List.of("mp3", "wav", "aiff", "aac", "ogg", "flac"));

    /**
     * 将 ImageBlock 转为带内联数据的 Gemini Part。
     *
     * @param block 待转换的 ImageBlock
     * @return 包含内联数据的 Part 对象
     */
    public Part convertToInlineDataPart(ImageBlock block) {
        return convertMediaBlockToInlineDataPart(block.getSource(), "image");
    }

    /**
     * 将 AudioBlock 转为带内联数据的 Gemini Part。
     *
     * @param block 待转换的 AudioBlock
     * @return 包含内联数据的 Part 对象
     */
    public Part convertToInlineDataPart(AudioBlock block) {
        return convertMediaBlockToInlineDataPart(block.getSource(), "audio");
    }

    /**
     * 将 VideoBlock 转为带内联数据的 Gemini Part。
     *
     * @param block 待转换的 VideoBlock
     * @return 包含内联数据的 Part 对象
     */
    public Part convertToInlineDataPart(VideoBlock block) {
        return convertMediaBlockToInlineDataPart(block.getSource(), "video");
    }

    /**
     * 将 DataBlock 转为带内联数据的 Gemini Part。
     *
     * <p>MIME 类型解析顺序：
     * <ol>
     *   <li>{@code Base64Source.mediaType}：始终显式指定</li>
     *   <li>{@code URLSource.mimeType}：调用方为无扩展名 URL 提供的提示</li>
     *   <li>通过 {@link #getMimeType} 根据扩展名推断</li>
     * </ol>
     *
     * @param block 待转换的 DataBlock
     * @return 包含内联数据的 Part 对象
     */
    public Part convertToInlineDataPart(DataBlock block) {
        Source source = block.getSource();
        String mimeType = MediaUtils.resolveMimeType(source);
        byte[] data;

        if (source instanceof Base64Source base64Source) {
            data = Base64.getDecoder().decode(base64Source.getData());
        } else if (source instanceof URLSource urlSource) {
            try {
                data = readFileAsBytes(urlSource.getUrl());
            } catch (IOException e) {
                throw new RuntimeException(
                        "Failed to read DataBlock file: " + urlSource.getUrl(), e);
            }
        } else {
            throw new IllegalArgumentException(
                    "Unsupported source type: " + source.getClass().getName());
        }

        Blob blob = Blob.builder().data(data).mimeType(mimeType).build();
        return Part.builder().inlineData(blob).build();
    }

    /**
     * 将媒体来源转为带内联数据的 Gemini Part。
     *
     * @param source 来源对象（Base64Source 或 URLSource）
     * @param mediaType 媒体类型字符串（"image"、"audio" 或 "video"）
     * @return 带内联数据的 Part 对象
     */
    private Part convertMediaBlockToInlineDataPart(Source source, String mediaType) {
        byte[] data;
        String mimeType;

        if (source instanceof Base64Source base64Source) {
            // Base64：解码后直接使用
            data = Base64.getDecoder().decode(base64Source.getData());
            mimeType = base64Source.getMediaType();

        } else if (source instanceof URLSource urlSource) {
            // URL：读取文件并获取 MIME 类型
            String url = urlSource.getUrl();
            try {
                data = readFileAsBytes(url);
                mimeType = getMimeType(url, mediaType);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read file: " + url, e);
            }

        } else {
            throw new IllegalArgumentException(
                    "Unsupported source type: " + source.getClass().getName());
        }

        // 创建 Blob 和 Part
        Blob blob = Blob.builder().data(data).mimeType(mimeType).build();

        return Part.builder().inlineData(blob).build();
    }

    /**
     * 从 URL／路径读取文件为字节数组。
     *
     * <p>支持远程 URL（http://、https://）和本地文件路径。
     *
     * @param url 文件 URL 或路径
     * @return 文件内容字节数组
     * @throws IOException 无法读取文件时抛出
     */
    private byte[] readFileAsBytes(String url) throws IOException {
        // 检查是否为远程 URL
        if (url.startsWith("http://") || url.startsWith("https://")) {
            try {
                URL remoteUrl = URI.create(url).toURL();
                try (InputStream in = remoteUrl.openStream()) {
                    return in.readAllBytes();
                }
            } catch (IOException e) {
                throw new IOException("Failed to download remote file: " + url, e);
            }
        } else {
            // 本地文件路径
            Path path = Paths.get(url);
            if (!Files.exists(path)) {
                throw new IOException("File not found: " + url);
            }
            return Files.readAllBytes(path);
        }
    }

    /**
     * 根据文件扩展名确定 MIME 类型。
     *
     * @param url 文件 URL 或路径
     * @param mediaType 媒体类型类别（"image"、"audio"、"video"）
     * @return MIME 类型字符串（例如 "image/png"）
     */
    private String getMimeType(String url, String mediaType) {
        String extension = extractExtension(url);

        // 验证扩展名是否受支持
        List<String> supportedExts = SUPPORTED_EXTENSIONS.get(mediaType);
        if (supportedExts == null || !supportedExts.contains(extension)) {
            throw new IllegalArgumentException(
                    String.format(
                            "Unsupported file extension: %s for media type: %s, expected one of:"
                                    + " %s",
                            extension, mediaType, supportedExts));
        }

        // jpg → jpeg 的特殊情况
        if ("jpg".equals(extension)) {
            extension = "jpeg";
        }

        return mediaType + "/" + extension;
    }

    /**
     * 从 URL 或路径提取文件扩展名。
     *
     * @param url 文件 URL 或路径
     * @return 小写文件扩展名（不含点）
     */
    private String extractExtension(String url) {
        String extension = MediaUtils.getExtension(url);
        if (extension.isBlank()) {
            throw new IllegalArgumentException("Cannot extract file extension from: " + url);
        }
        return extension.toLowerCase();
    }
}
