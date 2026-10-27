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
package io.agentscope.core.formatter;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 处理多模态内容中媒体文件的工具类。
 * 提供图像、音频等媒体类型的处理方法。
 */
public class MediaUtils {

    private static final Logger log = LoggerFactory.getLogger(MediaUtils.class);

    // 文件大小限制
    private static final long WARN_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB
    private static final long MAX_SIZE_BYTES = 50 * 1024 * 1024; // 50 MB

    // 支持的扩展名
    private static final List<String> SUPPORTED_IMAGE_EXTENSIONS =
            List.of("png", "jpg", "jpeg", "gif", "webp", "heic", "heif");
    private static final List<String> SUPPORTED_AUDIO_EXTENSIONS = List.of("wav", "mp3");
    private static final List<String> SUPPORTED_VIDEO_EXTENSIONS =
            List.of("mp4", "mpeg", "mpg", "mov", "avi", "webm", "wmv", "flv", "3gp", "3gpp");

    private MediaUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 检查 URL 是否为本地文件路径（而不是带协议的 URL）。
     * 没有 http://、https://、ftp://、file:// 或 oss:// 前缀的路径返回 true。
     * 用于区分本地文件与远程 URL，以采用不同处理流程。
     *
     * @param url 要检查的 URL 或文件路径
     * @return 本地路径为 true；带协议的 URL 为 false
     */
    public static boolean isLocalFile(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        return !url.startsWith("http://")
                && !url.startsWith("https://")
                && !url.startsWith("ftp://")
                && !url.startsWith("file://")
                && !url.startsWith("oss://");
    }

    /**
     * 将本地文件转换为 Base64 编码字符串。
     * 读取前检查文件大小（最大 50 MB）。
     * 适用于 API 要求 Base64 编码媒体内容的情况。
     *
     * @param path 本地文件路径
     * @return 文件内容的 Base64 编码字符串
     * @throws IOException 文件无法读取或超过大小限制时抛出
     */
    public static String fileToBase64(String path) throws IOException {
        Path filePath = Path.of(path);
        if (!Files.exists(filePath)) {
            throw new IOException("File does not exist: " + path);
        }
        if (!Files.isReadable(filePath)) {
            throw new IOException("File is not readable: " + path);
        }
        checkFileSize(path);
        byte[] bytes = Files.readAllBytes(filePath);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 下载远程 URL 并转换为 Base64。
     * 适用于要求 Base64 编码而非直接 URL 的 API（例如 OpenAI 音频）。
     * 检查下载内容大小（最大 50 MB），并设置连接超时。
     *
     * @param url 要下载的远程 URL
     * @return 下载内容的 Base64 编码字符串
     * @throws IOException 下载失败、超过大小限制或状态码不是 200 时抛出
     */
    public static String downloadUrlToBase64(String url) throws IOException {
        log.debug("Downloading remote URL for base64 encoding: {}", url);

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000); // 10 秒
            connection.setReadTimeout(30000); // 30 秒
            connection.connect();

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException(
                        "Failed to download URL: HTTP " + responseCode + " for " + url);
            }

            try (InputStream is = connection.getInputStream()) {
                // 【学习提示】此实现先完整读取，再检查大小；该上限不是流式读取的内存硬限制。
                // 理解这一顺序有助于评估大资源风险。本学习分支仅加说明，不改变原行为。
                byte[] bytes = is.readAllBytes();

                // 下载后检查大小
                if (bytes.length > MAX_SIZE_BYTES) {
                    throw new IOException(
                            "Downloaded content too large: "
                                    + bytes.length
                                    + " bytes (max: "
                                    + MAX_SIZE_BYTES
                                    + ")");
                }
                if (bytes.length > WARN_SIZE_BYTES) {
                    log.warn("Large download detected: {} bytes from {}", bytes.length, url);
                }

                return Base64.getEncoder().encodeToString(bytes);
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 将路径转换为 file:// 协议 URL；网页 URL 则保持原样。
     *
     * @param url URL 或文件路径
     * @return file:// 协议 URL 或原始 URL（例如 file:///absolute/path/image.png）
     * @throws IOException 文件不存在时抛出
     */
    public static String urlToProtocolUrl(String url) throws IOException {
        if (isFileExists(url)) {
            return toFileProtocolUrl(url);
        }

        return url;
    }

    /**
     * 将本地文件路径转换为 file:// 协议 URL。
     * 将相对路径解析为绝对路径，并验证文件存在。
     * 用于接受 file:// 本地文件协议的 DashScope 视觉模型。
     *
     * @param path 本地文件路径（相对或绝对）
     * @return 含绝对路径的 file:// 协议 URL（例如 file:///absolute/path/image.png）
     * @throws IOException 文件不存在时抛出
     */
    public static String toFileProtocolUrl(String path) throws IOException {
        Path absolutePath = Path.of(path).toAbsolutePath();
        if (!Files.exists(absolutePath)) {
            throw new IOException("File does not exist: " + path);
        }
        return "file://" + absolutePath;
    }

    /**
     * 将文件路径或网页 URL 转换为 InputStream。
     *
     * @param url 文件路径或网页 URL
     * @return 资源对应的 InputStream
     * @throws IOException 读取资源失败时抛出
     */
    public static InputStream urlToInputStream(String url) throws IOException {
        if (isFileExists(url)) {
            // 按本地文件处理
            return Files.newInputStream(Path.of(url));
        } else {
            // 按网页 URL 处理
            return URI.create(url).toURL().openStream();
        }
    }

    /**
     * 将文件路径或网页 URL 转换为包含 RGBA 图像的 InputStream。
     * @param url 本地文件路径或网页 URL
     * @return 包含 RGBA 图像的 InputStream
     * @throws IOException 资源转换失败时抛出
     * @throws IllegalArgumentException 读取资源失败时抛出
     */
    public static InputStream urlToRgbaImageInputStream(String url) throws IOException {
        BufferedImage img = ImageIO.read(urlToInputStream(url));
        if (img == null) {
            throw new IllegalArgumentException("Unable to read image from: " + url);
        }

        // 确保图像具有 alpha 通道（RGBA）
        BufferedImage rgbaImg =
                new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = rgbaImg.createGraphics();
        try {
            graphics.drawImage(img, 0, 0, null);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(rgbaImg, "png", bos);
            return new ByteArrayInputStream(bos.toByteArray());
        } finally {
            graphics.dispose();
        }
    }

    /**
     * 将文件路径或网页 URL 转换为 Base64 编码的 data URL。
     * @param url 本地文件路径或网页 URL
     * @return Base64 编码的 data URL，格式为 data:{mediaType};base64,{base64Data}
     */
    public static String urlToBase64DataUrl(String url) throws IOException {
        String base64;
        if (isFileExists(url)) {
            // 按本地文件处理
            base64 = fileToBase64(url);
        } else {
            // 按网页 URL 处理
            base64 = downloadUrlToBase64(url);
        }

        String mediaType = determineMediaType(url);
        return String.format("data:%s;base64,%s", mediaType, base64);
    }

    /**
     * 根据文件扩展名确定 MIME 类型。
     */
    public static String determineMediaType(String path) {
        String ext = getExtension(path).toLowerCase();
        return switch (ext) {
            // 图像格式
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "heic" -> "image/heic";
            case "heif" -> "image/heif";

            // 音频格式
            case "mp3" -> "audio/mp3";
            case "wav" -> "audio/wav";
            case "aiff" -> "audio/aiff";
            case "aac" -> "audio/aac";
            case "ogg" -> "audio/ogg";
            case "flac" -> "audio/flac";

            // 视频格式（供将来使用）
            case "mp4" -> "video/mp4";
            case "mpeg", "mpg" -> "video/mpeg";
            case "mov" -> "video/quicktime";
            case "avi" -> "video/x-msvideo";
            case "webm" -> "video/webm";
            case "wmv" -> "video/x-ms-wmv";
            case "flv" -> "video/x-flv";
            case "3gpp", "3gp" -> "video/3gpp";

            default -> "application/octet-stream";
        };
    }

    /**
     * 从 {@link Source} 解析 MIME 类型。
     *
     * <p>解析顺序：
     * <ol>
     *   <li>{@link Base64Source#getMediaType()}：始终显式给出</li>
     *   <li>{@link URLSource#getMimeType()}：调用方为无扩展名 URL 提供的提示</li>
     *   <li>{@link #determineMediaType(String)}：根据扩展名推断</li>
     * </ol>
     *
     * @param source 用于解析 MIME 类型的来源
     * @return MIME 类型字符串（例如 "image/jpeg"）
     * @throws IllegalArgumentException 无法确定类型或来源类型未知时抛出
     */
    public static String resolveMimeType(Source source) {
        if (source instanceof Base64Source b64) {
            return b64.getMediaType();
        }
        if (source instanceof URLSource urlSource) {
            String hint = urlSource.getMimeType();
            if (hint != null && !hint.isBlank()) {
                return hint;
            }
            String inferred = determineMediaType(urlSource.getUrl());
            if (!"application/octet-stream".equals(inferred)) {
                return inferred;
            }
            throw new IllegalArgumentException(
                    "Cannot determine MIME type for URL '"
                            + urlSource.getUrl()
                            + "'; set URLSource.mimeType explicitly");
        }
        throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
    }

    /**
     * 验证图像文件扩展名是否受支持。
     */
    public static void validateImageExtension(String url) {
        String ext = getExtension(url).toLowerCase();
        if (!SUPPORTED_IMAGE_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException(
                    String.format(
                            "\"%s\" should end with one of %s", url, SUPPORTED_IMAGE_EXTENSIONS));
        }
    }

    /**
     * 验证音频文件扩展名是否受支持。
     */
    public static void validateAudioExtension(String url) {
        String ext = getExtension(url).toLowerCase();
        if (!SUPPORTED_AUDIO_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException(
                    String.format(
                            "Unsupported audio file extension: %s, %s are supported",
                            ext, SUPPORTED_AUDIO_EXTENSIONS));
        }
    }

    /**
     * 验证视频文件扩展名是否受支持。
     */
    public static void validateVideoExtension(String url) {
        String ext = getExtension(url).toLowerCase();
        if (!SUPPORTED_VIDEO_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException(
                    String.format(
                            "Unsupported video file extension: %s, %s are supported",
                            ext, SUPPORTED_VIDEO_EXTENSIONS));
        }
    }

    /**
     * 根据文件扩展名确定音频格式字符串。
     *
     * @param path 文件路径
     * @return 音频格式字符串（"wav" 或 "mp3"）
     */
    public static String determineAudioFormat(String path) {
        String ext = getExtension(path).toLowerCase();
        return ext.equals("wav") ? "wav" : "mp3";
    }

    /**
     * 根据 MIME 类型推断音频格式字符串。
     *
     * @param mediaType MIME 类型
     * @return 音频格式字符串（"wav"、"mp3"、"opus"、"flac" 等）
     */
    public static String inferAudioFormatFromMediaType(String mediaType) {
        if (mediaType == null) {
            return "mp3"; // 默认值
        }
        if (mediaType.contains("wav")) {
            return "wav";
        } else if (mediaType.contains("opus")) {
            return "opus";
        } else if (mediaType.contains("flac")) {
            return "flac";
        }
        return "mp3"; // 默认值
    }

    /**
     * 从路径或 URL 提取文件扩展名。
     */
    public static String getExtension(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }

        String fileName;
        try {
            if (isLocalFile(path)) {
                // 按文件处理
                Path fileNamePath = Paths.get(path).normalize().getFileName();
                fileName = fileNamePath == null ? "" : fileNamePath.toString();
            } else {
                // URI 路径始终使用 /，与宿主机文件系统无关（例如 Windows 上的 /C:/）。
                URI uri = URI.create(path).normalize();
                String uriPath = uri.getPath();
                fileName = uriPath.substring(uriPath.lastIndexOf('/') + 1);
            }
        } catch (Exception e) {
            log.warn("Invalid path: {}", path, e);
            return "";
        }

        int dotIndex = fileName.lastIndexOf('.');
        // 确保存在点号，且它不是最后一个字符
        if (dotIndex != -1 && dotIndex < fileName.length() - 1) {
            return fileName.substring(dotIndex + 1);
        }
        return "";
    }

    /**
     * 检查文件是否存在。
     * @param path 本地文件路径或 URL。
     * @return true 表示存在；false 表示不存在或路径无效
     */
    public static boolean isFileExists(String path) {
        if (!isLocalFile(path)) {
            return false;
        }
        try {
            return Files.exists(Path.of(path));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /**
     * 读取前检查文件大小。
     */
    private static void checkFileSize(String path) throws IOException {
        long size = Files.size(Path.of(path));
        if (size > MAX_SIZE_BYTES) {
            throw new IOException(
                    "File too large: " + size + " bytes (max: " + MAX_SIZE_BYTES + ")");
        }
        if (size > WARN_SIZE_BYTES) {
            log.warn("Large file detected: {} bytes at {}", size, path);
        }
    }
}
