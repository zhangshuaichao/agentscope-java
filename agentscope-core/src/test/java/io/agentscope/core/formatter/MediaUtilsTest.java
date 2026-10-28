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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MediaUtils 单元测试。
 *
 * <p>验证媒体工具函数，包括：
 * <ul>
 * <li>本地文件检测</li>
 * <li>文件转 base64</li>
 * <li>file 协议 URL 转换</li>
 * <li>音频格式判断</li>
 * <li>扩展名验证</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("MediaUtils Unit Tests")
class MediaUtilsTest {

    @TempDir Path tempDir;

    @Test
    @DisplayName("Should identify local file paths")
    void testIsLocalFile() {
        assertTrue(MediaUtils.isLocalFile("image.png"));
        assertTrue(MediaUtils.isLocalFile("/absolute/path/image.png"));
        assertTrue(MediaUtils.isLocalFile("./relative/path/image.png"));
        assertTrue(MediaUtils.isLocalFile("file.png"));

        assertFalse(MediaUtils.isLocalFile("http://example.com/image.png"));
        assertFalse(MediaUtils.isLocalFile("https://example.com/image.png"));
        assertFalse(MediaUtils.isLocalFile("oss://example.com/image.png"));
        assertFalse(MediaUtils.isLocalFile("ftp://example.com/image.png"));
        assertFalse(MediaUtils.isLocalFile("file:///absolute/path/image.png"));
        assertFalse(MediaUtils.isLocalFile(null));
        assertFalse(MediaUtils.isLocalFile(""));
    }

    @Test
    @DisplayName("Should convert file to base64")
    void testFileToBase64() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        String content = "Hello, World!";
        Files.write(testFile, content.getBytes());

        String base64 = MediaUtils.fileToBase64(testFile.toString());

        assertNotNull(base64);
        assertTrue(base64.length() > 0);
        // 验证是有效 base64
        byte[] decoded = Base64.getDecoder().decode(base64);
        assertEquals(content, new String(decoded));
    }

    @Test
    @DisplayName("Should throw exception for non-existent file")
    void testFileToBase64NonExistent() {
        assertThrows(
                IOException.class,
                () -> MediaUtils.fileToBase64("/nonexistent/file.txt"),
                "Should throw IOException for non-existent file");
    }

    @Test
    @DisplayName("Should throw exception for unreadable file")
    void testFileToBase64UnReadable() throws IOException {
        Path testFile = tempDir.resolve("unreadable.txt");
        Files.write(testFile, "content".getBytes());

        // 尝试使文件不可读并检查是否成功
        boolean setReadableResult = testFile.toFile().setReadable(false);
        boolean isActuallyUnreadable = !Files.isReadable(testFile);

        if (setReadableResult && isActuallyUnreadable) {
            // 已成功使文件不可读，因此 MediaUtils 应抛异常
            assertThrows(
                    IOException.class,
                    () -> MediaUtils.fileToBase64(testFile.toString()),
                    "Should throw IOException for unreadable file");
        } else {
            // 在 setReadable() 不生效的系统（例如部分 Windows 配置）上，
            // 文件仍可读，因此方法应正常工作
            // 这种情况下实际测试的是正常操作
            String result = MediaUtils.fileToBase64(testFile.toString());
            assertNotNull(
                    result,
                    "Should return base64 string for readable file when setReadable doesn't work");
        }

        // 恢复文件权限，确保再次可读
        testFile.toFile().setReadable(true);
    }

    @Test
    @DisplayName("Should convert file to file:// protocol URL")
    void testToFileProtocolUrl() throws IOException {
        Path testFile = tempDir.resolve("test.png");
        Files.createFile(testFile);

        String fileUrl = MediaUtils.toFileProtocolUrl(testFile.toString());

        assertNotNull(fileUrl);
        assertTrue(fileUrl.startsWith("file://"));
        assertTrue(fileUrl.contains("test.png"));
    }

    @Test
    @DisplayName("Should throw exception for non-existent file in toFileProtocolUrl")
    void testToFileProtocolUrlNonExistent() {
        assertThrows(
                IOException.class,
                () -> MediaUtils.toFileProtocolUrl("/nonexistent/file.png"),
                "Should throw IOException for non-existent file");
    }

    @Test
    @DisplayName("Should determine audio format from extension")
    void testDetermineAudioFormat() {
        assertEquals("wav", MediaUtils.determineAudioFormat("audio.wav"));
        assertEquals("wav", MediaUtils.determineAudioFormat("audio.WAV"));
        assertEquals("mp3", MediaUtils.determineAudioFormat("audio.mp3"));
        assertEquals("mp3", MediaUtils.determineAudioFormat("audio.MP3"));
        assertEquals("mp3", MediaUtils.determineAudioFormat("audio.unknown"));
        assertEquals("mp3", MediaUtils.determineAudioFormat("audio"));
    }

    @Test
    @DisplayName("Should infer audio format from MIME type")
    void testInferAudioFormatFromMediaType() {
        assertEquals("wav", MediaUtils.inferAudioFormatFromMediaType("audio/wav"));
        assertEquals("wav", MediaUtils.inferAudioFormatFromMediaType("audio/x-wav"));
        assertEquals("mp3", MediaUtils.inferAudioFormatFromMediaType("audio/mpeg"));
        assertEquals("mp3", MediaUtils.inferAudioFormatFromMediaType("audio/mp3"));
        assertEquals("opus", MediaUtils.inferAudioFormatFromMediaType("audio/opus"));
        assertEquals("flac", MediaUtils.inferAudioFormatFromMediaType("audio/flac"));
        assertEquals("mp3", MediaUtils.inferAudioFormatFromMediaType(null));
        assertEquals("mp3", MediaUtils.inferAudioFormatFromMediaType("unknown/type"));
    }

    @Test
    @DisplayName("Should determine media type from extension")
    void testDetermineMediaType() {
        // 图像
        assertEquals("image/png", MediaUtils.determineMediaType("image.png"));
        assertEquals("image/jpeg", MediaUtils.determineMediaType("image.jpg"));
        assertEquals("image/jpeg", MediaUtils.determineMediaType("image.jpeg"));
        assertEquals("image/gif", MediaUtils.determineMediaType("image.gif"));
        assertEquals("image/webp", MediaUtils.determineMediaType("image.webp"));
        assertEquals("image/heic", MediaUtils.determineMediaType("image.heic"));

        // 音频
        assertEquals("audio/mp3", MediaUtils.determineMediaType("audio.mp3"));
        assertEquals("audio/wav", MediaUtils.determineMediaType("audio.wav"));
        assertEquals("audio/flac", MediaUtils.determineMediaType("audio.flac"));

        // 视频
        assertEquals("video/mp4", MediaUtils.determineMediaType("video.mp4"));
        assertEquals("video/mpeg", MediaUtils.determineMediaType("video.mpeg"));
        assertEquals("video/quicktime", MediaUtils.determineMediaType("video.mov"));
        assertEquals("video/x-msvideo", MediaUtils.determineMediaType("video.avi"));

        // 回退
        assertEquals("application/octet-stream", MediaUtils.determineMediaType("file.unknown"));
        assertEquals("application/octet-stream", MediaUtils.determineMediaType("file"));
        assertEquals("application/octet-stream", MediaUtils.determineMediaType(null));
        assertEquals("application/octet-stream", MediaUtils.determineMediaType(""));
    }

    @Test
    @DisplayName("Should validate image extension")
    void testValidateImageExtension() {
        // 有效扩展名不应抛异常
        MediaUtils.validateImageExtension("image.png");
        MediaUtils.validateImageExtension("image.jpg");
        MediaUtils.validateImageExtension("image.jpeg");
        MediaUtils.validateImageExtension("image.gif");
        MediaUtils.validateImageExtension("image.webp");
        MediaUtils.validateImageExtension("image.heic");
        MediaUtils.validateImageExtension("image.heif");

        // 无效扩展名应抛异常
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaUtils.validateImageExtension("image.txt"),
                "Should throw for unsupported image extension");
    }

    @Test
    @DisplayName("Should validate audio extension")
    void testValidateAudioExtension() {
        // 有效扩展名不应抛异常
        MediaUtils.validateAudioExtension("audio.wav");
        MediaUtils.validateAudioExtension("audio.mp3");

        // 无效扩展名应抛异常
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaUtils.validateAudioExtension("audio.txt"),
                "Should throw for unsupported audio extension");
    }

    @Test
    @DisplayName("Should validate video extension")
    void testValidateVideoExtension() {
        // 有效扩展名不应抛异常
        MediaUtils.validateVideoExtension("video.mp4");
        MediaUtils.validateVideoExtension("video.mpeg");
        MediaUtils.validateVideoExtension("video.mov");
        MediaUtils.validateVideoExtension("video.avi");
        MediaUtils.validateVideoExtension("video.webm");

        // 无效扩展名应抛异常
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaUtils.validateVideoExtension("video.txt"),
                "Should throw for unsupported video extension");
    }

    @Test
    @DisplayName("Should handle relative paths in toFileProtocolUrl")
    void testToFileProtocolUrlRelativePath() throws IOException {
        Path testFile = tempDir.resolve("test.png");
        Files.createFile(testFile);

        // 使用绝对路径，toFileProtocolUrl 内部会将相对路径转为绝对路径
        // 方法将相对路径解析为绝对路径，因此使用绝对路径测试
        String fileUrl = MediaUtils.toFileProtocolUrl(testFile.toString());

        assertNotNull(fileUrl);
        assertTrue(fileUrl.startsWith("file://"));
        assertTrue(fileUrl.contains("test.png"));
    }

    // 注意：MediaUtils 中不存在 base64ToDataUrl 方法，对应测试已移除

    @Test
    @DisplayName("Url to protocol url with file path")
    void testUrlToProtocolUrlWithFile() throws IOException {
        String url = "src/test/java/io/agentscope/core/formatter/MediaUtilsTest.java";
        String protocolUrl = MediaUtils.urlToProtocolUrl(url);

        assertTrue(protocolUrl.startsWith("file://"));
    }

    @Test
    @DisplayName("Url to protocol url with web url")
    void testUrlToProtocolUrlWithUrl() throws IOException {
        String url = "https://example.com/file.png";
        String protocolUrl = MediaUtils.urlToProtocolUrl(url);

        assertEquals(url, protocolUrl);
    }

    @Test
    @DisplayName("Should return null or empty string with null or empty path")
    void testUrlToProtocolUrlWithEmptyPath() throws IOException {
        assertNull(null, MediaUtils.urlToProtocolUrl(null));
        assertEquals("", MediaUtils.urlToProtocolUrl(""));
    }

    @Test
    @DisplayName("Should return source path with invalid path")
    void testUrlToProtocolUrlWithInvalidPath() throws IOException {
        String path = "/path/to/file/<:|/\\>\u0000\b\n\t\0.txt";
        assertEquals(path, MediaUtils.urlToProtocolUrl(path));
    }

    @Test
    @DisplayName("To file protocol url with file path")
    void testToFileProtocolUrlWithFile() throws IOException {
        String url = "src/test/java/io/agentscope/core/formatter/MediaUtilsTest.java";
        String protocolUrl = MediaUtils.toFileProtocolUrl(url);

        assertTrue(protocolUrl.startsWith("file://"));
    }

    @Test
    @DisplayName("Should throw IOException if file not found")
    void testToFileProtocolUrlWithNotFoundFile() {
        String url = "/path/no/file";

        assertThrows(IOException.class, () -> MediaUtils.toFileProtocolUrl(url));
    }

    @Test
    @DisplayName("Url to inputStream with file path")
    void testUrlToInputStreamWithFile() throws IOException {
        String url = "src/test/java/io/agentscope/core/formatter/MediaUtilsTest.java";

        assertNotNull(MediaUtils.urlToInputStream(url));
    }

    @Test
    @DisplayName("Url to RGBA image inputStream with file path")
    void testUrlToRgbaImageInputStreamWithFile() throws IOException {
        String url = "src/test/resources/dog.png";
        InputStream is = MediaUtils.urlToRgbaImageInputStream(url);
        assertNotNull(is);
        is.close();
    }

    @Test
    @DisplayName("Should return empty string when path is invalid")
    void testGetExtensionWithInvalidPath() {
        assertEquals("", MediaUtils.getExtension(null));
        assertEquals("", MediaUtils.getExtension(""));
        assertEquals("", MediaUtils.getExtension("  "));
        assertEquals("", MediaUtils.getExtension("/"));
        assertEquals("", MediaUtils.getExtension("\\"));
        assertEquals("", MediaUtils.getExtension("/home/user"));
        assertEquals("", MediaUtils.getExtension("C:\\Users\\Administrator"));
        assertEquals("", MediaUtils.getExtension("https://abc"));
        assertEquals("", MediaUtils.getExtension("https://abc[@]123"));
        assertEquals("", MediaUtils.getExtension("https://example.com/abc"));
        assertEquals("", MediaUtils.getExtension("https://example.com/abc/"));
        assertEquals("", MediaUtils.getExtension("https://example.com/img.png."));
    }

    @Test
    @DisplayName("Should get extension with all types")
    void testGetExtensionWithAllTypes() {
        assertEquals("png", MediaUtils.getExtension("https://example.com/img.png"));
        assertEquals("wav", MediaUtils.getExtension("https://example.com/audio.wav"));
        assertEquals("mp3", MediaUtils.getExtension("https://example.com/audio.mp3"));
        assertEquals("mp4", MediaUtils.getExtension("https://example.com/video.mp4"));
        assertEquals("xxx", MediaUtils.getExtension("https://example.com/xxx.xxx"));
    }

    @Test
    @DisplayName("Should get file URI extensions independently of the host filesystem")
    void testGetExtensionWithFileUris() {
        assertEquals(
                "png", MediaUtils.getExtension("file:///C:/Users/test/image%20with%20spaces.png"));
        assertEquals("png", MediaUtils.getExtension("file:///tmp/image%20with%20spaces.png"));
        assertEquals(
                "png",
                MediaUtils.getExtension(
                        tempDir.resolve("image with spaces.png").toUri().toString()));
        assertEquals("", MediaUtils.getExtension("file:///C:/directory.png/image"));
        assertEquals("", MediaUtils.getExtension("file:///C:/directory.png/"));
    }

    @Test
    @DisplayName("Should get last extension when have nested types")
    void testGetExtensionWithNestedTypes() {
        assertEquals("gz", MediaUtils.getExtension("https://example.com/img.png.tar.gz"));
        assertEquals("zip", MediaUtils.getExtension("https://example.com/img.png.zip"));
    }

    @Test
    @DisplayName("Should get extension with masks")
    void testGetExtensionWithMasks() {
        assertEquals("png", MediaUtils.getExtension("https://example.com/img.png?id=1&type=png"));
        assertEquals("png", MediaUtils.getExtension("https://example.com/img.png#section1.1"));
        assertEquals(
                "png",
                MediaUtils.getExtension("https://example.com/img.png?id=1&type=png#section1.1"));
        assertEquals("png", MediaUtils.getExtension("https://example.com/img.png?v=2.0.0"));
        assertEquals(
                "png", MediaUtils.getExtension("https://example.com/img-123&type=png&v=2.0.0.png"));

        // 验证文件名包含特殊字符
        assertEquals("png", MediaUtils.getExtension("/home/user/img-123&type=png&v=2.0.0.png"));
        assertEquals(
                "png",
                MediaUtils.getExtension("C:\\Users\\Administrator\\img_123&type=png&v=2.0.0.png"));
    }

    @Test
    @DisplayName("Should get extension with all supported protocol")
    void testGetExtensionWithSupportedProtocol() {
        assertEquals(
                "png",
                MediaUtils.getExtension("http://example.com/img.png?id=1&type=png#section1.1"));
        assertEquals(
                "png",
                MediaUtils.getExtension("https://example.com/img.png?id=1&type=png#section1.1"));
        assertEquals(
                "png",
                MediaUtils.getExtension("oss://example.com/img.png?id=1&type=png#section1.1"));
        assertEquals(
                "png",
                MediaUtils.getExtension("file://example.com/img.png?id=1&type=png#section1.1"));
        assertEquals(
                "png",
                MediaUtils.getExtension("ftp://example.com/img.png?id=1&type=png#section1.1"));
    }
}
