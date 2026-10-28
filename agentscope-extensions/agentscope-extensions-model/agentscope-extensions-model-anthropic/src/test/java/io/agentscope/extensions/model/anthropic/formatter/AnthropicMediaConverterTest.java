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
package io.agentscope.extensions.model.anthropic.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.UrlImageSource;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** AnthropicMediaConverter 单元测试。 */
class AnthropicMediaConverterTest extends AnthropicFormatterTestBase {

    private final AnthropicMediaConverter converter = new AnthropicMediaConverter();

    @Test
    void testConvertImageBlockWithBase64Source() throws Exception {
        Base64Source source =
                Base64Source.builder()
                        .data("ZmFrZSBpbWFnZSBjb250ZW50")
                        .mediaType("image/png")
                        .build();
        ImageBlock block = ImageBlock.builder().source(source).build();

        ImageBlockParam result = converter.convertImageBlock(block);

        assertNotNull(result);
        assertTrue(result.source().isBase64());

        Base64ImageSource base64Source = result.source().asBase64();
        assertEquals("ZmFrZSBpbWFnZSBjb250ZW50", base64Source.data());
        assertEquals("image/png", base64Source.mediaType().toString());
    }

    @Test
    void testConvertImageBlockWithURLSourceLocal() throws Exception {
        URLSource source = URLSource.builder().url(tempImageFile.toString()).build();
        ImageBlock block = ImageBlock.builder().source(source).build();

        ImageBlockParam result = converter.convertImageBlock(block);

        assertNotNull(result);
        assertTrue(result.source().isBase64());

        Base64ImageSource base64Source = result.source().asBase64();
        assertNotNull(base64Source.data());
        // 验证为合法 Base64
        byte[] decoded = Base64.getDecoder().decode(base64Source.data());
        assertEquals("fake image content", new String(decoded));
    }

    @Test
    void testConvertImageBlockWithURLSourceRemote() throws Exception {
        String remoteUrl = "https://example.com/image.png";
        URLSource source = URLSource.builder().url(remoteUrl).build();
        ImageBlock block = ImageBlock.builder().source(source).build();

        ImageBlockParam result = converter.convertImageBlock(block);

        assertNotNull(result);
        assertTrue(result.source().isUrl());

        UrlImageSource urlSource = result.source().asUrl();
        assertEquals(remoteUrl, urlSource.url());
    }

    @Test
    void testConvertImageBlockWithInvalidExtension() {
        URLSource source = URLSource.builder().url("file.invalid").build();
        ImageBlock block = ImageBlock.builder().source(source).build();

        assertThrows(Exception.class, () -> converter.convertImageBlock(block));
    }

    @Test
    void testConvertImageBlockWithNonExistentFile() {
        URLSource source = URLSource.builder().url("/nonexistent/file.png").build();
        ImageBlock block = ImageBlock.builder().source(source).build();

        assertThrows(Exception.class, () -> converter.convertImageBlock(block));
    }

    @Test
    void testConvertImageBlockWithUnsupportedSourceType() {
        ImageBlock block = ImageBlock.builder().source(new CustomSource()).build();

        assertThrows(IllegalArgumentException.class, () -> converter.convertImageBlock(block));
    }

    @Test
    void testBase64EncodingDecoding() throws Exception {
        String originalText = "fake image content";
        String base64Encoded = Base64.getEncoder().encodeToString(originalText.getBytes());
        assertEquals("ZmFrZSBpbWFnZSBjb250ZW50", base64Encoded);

        Base64Source source =
                Base64Source.builder().data(base64Encoded).mediaType("image/png").build();
        ImageBlock block = ImageBlock.builder().source(source).build();

        ImageBlockParam result = converter.convertImageBlock(block);
        Base64ImageSource base64Source = result.source().asBase64();

        byte[] decoded = Base64.getDecoder().decode(base64Source.data());
        assertEquals(originalText, new String(decoded));
    }

    @Test
    void testConvertImageBlockWithJpegMediaType() throws Exception {
        Base64Source source =
                Base64Source.builder()
                        .data("ZmFrZSBpbWFnZSBjb250ZW50")
                        .mediaType("image/jpeg")
                        .build();
        ImageBlock block = ImageBlock.builder().source(source).build();

        ImageBlockParam result = converter.convertImageBlock(block);

        Base64ImageSource base64Source = result.source().asBase64();
        assertEquals("image/jpeg", base64Source.mediaType().toString());
    }

    @Test
    void testConvertImageBlockWithWebpMediaType() throws Exception {
        Base64Source source =
                Base64Source.builder()
                        .data("ZmFrZSBpbWFnZSBjb250ZW50")
                        .mediaType("image/webp")
                        .build();
        ImageBlock block = ImageBlock.builder().source(source).build();

        ImageBlockParam result = converter.convertImageBlock(block);

        Base64ImageSource base64Source = result.source().asBase64();
        assertEquals("image/webp", base64Source.mediaType().toString());
    }

    @Test
    void testConvertImageBlockWithGifMediaType() throws Exception {
        Base64Source source =
                Base64Source.builder()
                        .data("ZmFrZSBpbWFnZSBjb250ZW50")
                        .mediaType("image/gif")
                        .build();
        ImageBlock block = ImageBlock.builder().source(source).build();

        ImageBlockParam result = converter.convertImageBlock(block);

        Base64ImageSource base64Source = result.source().asBase64();
        assertEquals("image/gif", base64Source.mediaType().toString());
    }

    // 自定义来源类型，用于测试不支持的来源
    private static class CustomSource extends Source {}

    @Test
    void testConvertDataBlockWithBase64Source() throws Exception {
        Base64Source source =
                Base64Source.builder()
                        .data("ZmFrZSBpbWFnZSBjb250ZW50")
                        .mediaType("image/png")
                        .build();
        DataBlock block = DataBlock.builder().source(source).build();

        ImageBlockParam result = converter.convertDataBlock(block);

        assertNotNull(result);
        assertTrue(result.source().isBase64());
        Base64ImageSource base64Source = result.source().asBase64();
        assertEquals("ZmFrZSBpbWFnZSBjb250ZW50", base64Source.data());
        assertEquals("image/png", base64Source.mediaType().toString());
    }

    @Test
    void testConvertDataBlockWithRemoteURLAndExtension() throws Exception {
        String remoteUrl = "https://example.com/photo.jpg";
        URLSource source = URLSource.builder().url(remoteUrl).build();
        DataBlock block = DataBlock.builder().source(source).build();

        ImageBlockParam result = converter.convertDataBlock(block);

        assertNotNull(result);
        assertTrue(result.source().isUrl());
        assertEquals(remoteUrl, result.source().asUrl().url());
    }

    @Test
    void testConvertDataBlockWithMimeTypeHintExtensionlessUrl() throws Exception {
        // 主要场景：无扩展名的 CDN URL，但带显式 mimeType 提示
        String cdnUrl = "https://cdn.example.com/media/abc123";
        URLSource source = URLSource.builder().url(cdnUrl).mimeType("image/png").build();
        DataBlock block = DataBlock.builder().source(source).build();

        ImageBlockParam result = converter.convertDataBlock(block);

        assertNotNull(result);
        assertTrue(result.source().isUrl());
        assertEquals(cdnUrl, result.source().asUrl().url());
    }

    @Test
    void testConvertDataBlockWithLocalFile() throws Exception {
        URLSource source = URLSource.builder().url(tempImageFile.toString()).build();
        DataBlock block = DataBlock.builder().source(source).build();

        ImageBlockParam result = converter.convertDataBlock(block);

        assertNotNull(result);
        assertTrue(result.source().isBase64());
        byte[] decoded = Base64.getDecoder().decode(result.source().asBase64().data());
        assertEquals("fake image content", new String(decoded));
    }

    @Test
    void testConvertDataBlockNonImageMimeTypeThrows() {
        // 此转换器仅支持图片，音频/视频 DataBlock 必须抛异常
        Base64Source source =
                Base64Source.builder()
                        .data("ZmFrZSBhdWRpbyBjb250ZW50")
                        .mediaType("audio/mp3")
                        .build();
        DataBlock block = DataBlock.builder().source(source).build();

        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class, () -> converter.convertDataBlock(block));
        assertTrue(ex.getMessage().contains("image"));
    }

    @Test
    void testConvertDataBlockNoExtensionNoHintThrows() {
        URLSource source = URLSource.builder().url("https://cdn.example.com/media/abc123").build();
        DataBlock block = DataBlock.builder().source(source).build();

        assertThrows(Exception.class, () -> converter.convertDataBlock(block));
    }
}
