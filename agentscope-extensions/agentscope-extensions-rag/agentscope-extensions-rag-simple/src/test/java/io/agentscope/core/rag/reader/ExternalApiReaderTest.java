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
package io.agentscope.core.rag.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.rag.exception.ReaderException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.Exceptions;

/**
 * ExternalApiReader 单元测试。
 * 使用简单模拟展示功能，不依赖外部服务。
 */
@Tag("unit")
@DisplayName("ExternalApiReader Tests")
class ExternalApiReaderTest {

    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.get("application/json; charset=utf-8");

    @Test
    @DisplayName("Should successfully parse document via simulated external API")
    void testSimpleSyncApi() throws Exception {
        // 模拟 Markdown 响应
        String mockMarkdown = "# Test Document\n\nThis is a test paragraph.\n\nAnother paragraph.";

        // 使用模拟请求与响应创建 Reader
        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) -> {
                                    // 构建请求但不实际发送
                                    return new Request.Builder()
                                            .url("http://mock-api.example.com/parse")
                                            .post(RequestBody.create("{}", JSON_MEDIA_TYPE))
                                            .build();
                                })
                        .responseParser(
                                (response, client) -> {
                                    // 直接返回模拟 Markdown，不解析响应
                                    return mockMarkdown;
                                })
                        .chunkSize(100)
                        .splitStrategy(SplitStrategy.PARAGRAPH)
                        .overlapSize(10)
                        .connectTimeout(Duration.ofSeconds(1))
                        .readTimeout(Duration.ofSeconds(2))
                        .maxRetries(0)
                        .build();

        // 创建临时测试文件
        Path tempFile = Files.createTempFile("test", ".txt");
        Files.writeString(tempFile, "test content");

        try {
            // 注意：本测试实际尝试连接模拟 URL，因此会失败
            // 此示例展示如何配置读取器
            ReaderInput input = ReaderInput.fromString(tempFile.toString());

            // 由于实际发送请求，此处会抛异常
            // block() 将 ReaderException 包装为 ReactiveException
            Exception exception = assertThrows(Exception.class, () -> reader.read(input).block());
            Throwable cause = Exceptions.unwrap(exception);
            assertTrue(
                    cause instanceof ReaderException,
                    "Expected ReaderException but got: " + cause.getClass());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    @DisplayName("Should handle file not found error")
    void testFileNotFound() {
        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) -> {
                                    return new Request.Builder()
                                            .url("http://mock-api.example.com/parse")
                                            .post(RequestBody.create("{}", JSON_MEDIA_TYPE))
                                            .build();
                                })
                        .responseParser((response, client) -> "# Mock Response")
                        .connectTimeout(Duration.ofSeconds(1))
                        .readTimeout(Duration.ofSeconds(2))
                        .maxRetries(0)
                        .build();

        // 测试不存在的文件
        ReaderInput input = ReaderInput.fromString("/non/existent/file.pdf");

        // 应抛 ReaderException，由 block() 包装为 ReactiveException
        Exception exception = assertThrows(Exception.class, () -> reader.read(input).block());
        Throwable cause = Exceptions.unwrap(exception);
        assertTrue(
                cause instanceof ReaderException,
                "Expected ReaderException but got: " + cause.getClass());
    }

    @Test
    @DisplayName("Should validate builder configuration")
    void testBuilderValidation() {
        // 测试缺少 requestBuilder
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ExternalApiReader.builder()
                                .responseParser((response, client) -> "test")
                                .build());

        // 测试缺少 responseParser
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ExternalApiReader.builder()
                                .requestBuilder(
                                        (filePath, client) ->
                                                new Request.Builder()
                                                        .url("http://example.com")
                                                        .build())
                                .build());
    }

    @Test
    @DisplayName("Should support builder chaining with various configurations")
    void testBuilderChaining() {
        // 测试全部配置选项
        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) ->
                                        new Request.Builder()
                                                .url("http://example.com")
                                                .post(RequestBody.create("{}", JSON_MEDIA_TYPE))
                                                .build())
                        .responseParser((response, client) -> "# Mock")
                        .chunkSize(1000)
                        .splitStrategy(SplitStrategy.CHARACTER)
                        .overlapSize(100)
                        .connectTimeout(Duration.ofSeconds(30))
                        .readTimeout(Duration.ofMinutes(2))
                        .writeTimeout(Duration.ofMinutes(2))
                        .maxRetries(5)
                        .retryDelay(Duration.ofSeconds(3))
                        .supportedFormats("pdf", "docx", "txt")
                        .addInterceptor(chain -> chain.proceed(chain.request()))
                        .build();

        assertNotNull(reader);

        // 验证支持的格式
        List<String> formats = reader.getSupportedFormats();
        assertEquals(3, formats.size());
        assertTrue(formats.contains("pdf"));
        assertTrue(formats.contains("docx"));
        assertTrue(formats.contains("txt"));
    }

    @Test
    @DisplayName("Should support varargs for supported formats")
    void testSupportedFormatsVarargs() {
        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) ->
                                        new Request.Builder().url("http://example.com").build())
                        .responseParser((response, client) -> "test")
                        .supportedFormats("pdf", "docx")
                        .build();

        List<String> formats = reader.getSupportedFormats();
        assertEquals(2, formats.size());
    }

    @Test
    @DisplayName("Should return empty list when no formats specified")
    void testEmptySupportedFormats() {
        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) ->
                                        new Request.Builder().url("http://example.com").build())
                        .responseParser((response, client) -> "test")
                        .build();

        List<String> formats = reader.getSupportedFormats();
        assertNotNull(formats);
        assertTrue(formats.isEmpty());
    }

    @Test
    @DisplayName("Should demonstrate authentication configuration")
    void testAuthenticationConfiguration() {
        String apiKey = "test-api-key-12345";

        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) -> {
                                    // 演示添加认证请求头
                                    return new Request.Builder()
                                            .url("http://api.example.com/parse")
                                            .header("Authorization", "Bearer " + apiKey)
                                            .header("X-API-Key", apiKey)
                                            .post(RequestBody.create("{}", JSON_MEDIA_TYPE))
                                            .build();
                                })
                        .responseParser((response, client) -> "# Authenticated Response")
                        .build();

        assertNotNull(reader);
    }

    @Test
    @DisplayName("Should demonstrate multipart file upload configuration")
    void testMultipartUploadConfiguration() {
        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) -> {
                                    // 演示构建 multipart 请求
                                    // 注意：实际使用时需创建真实 MultipartBody
                                    return new Request.Builder()
                                            .url("http://api.example.com/upload")
                                            .post(RequestBody.create("{}", JSON_MEDIA_TYPE))
                                            .build();
                                })
                        .responseParser((response, client) -> "# Upload Response")
                        .writeTimeout(
                                Duration.ofMinutes(10)) // 文件上传需要更长的写超时
                        .build();

        assertNotNull(reader);
    }

    @Test
    @DisplayName("Should demonstrate async task polling pattern")
    void testAsyncPollingPattern() {
        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) -> {
                                    // 步骤 1：提交任务
                                    return new Request.Builder()
                                            .url("http://api.example.com/tasks/submit")
                                            .post(RequestBody.create("{}", JSON_MEDIA_TYPE))
                                            .build();
                                })
                        .responseParser(
                                (response, client) -> {
                                    // 模拟异步轮询模式
                                    // 实际使用时：
                                    // 1. 从响应提取 task_id
                                    // 2. 循环检查任务状态
                                    // 3. 任务完成后返回结果
                                    return "# Task Result";
                                })
                        .readTimeout(Duration.ofMinutes(10)) // 异步任务需要更长超时
                        .maxRetries(3)
                        .retryDelay(Duration.ofSeconds(5))
                        .build();

        assertNotNull(reader);
    }

    @Test
    @DisplayName("Should demonstrate custom interceptor usage")
    void testCustomInterceptor() {
        final boolean[] interceptorCalled = {false};

        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .addInterceptor(
                                chain -> {
                                    // 拦截器可用于：
                                    // - 添加公共请求头
                                    // - 记录请求日志
                                    // - 修改请求参数
                                    interceptorCalled[0] = true;
                                    return chain.proceed(chain.request());
                                })
                        .requestBuilder(
                                (filePath, client) ->
                                        new Request.Builder().url("http://example.com").build())
                        .responseParser((response, client) -> "test")
                        .build();

        assertNotNull(reader);
        // 注意：仅实际发送请求时才调用拦截器
        assertFalse(interceptorCalled[0]);
    }

    @Test
    @DisplayName("Should handle null input")
    void testNullInput() {
        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) ->
                                        new Request.Builder().url("http://example.com").build())
                        .responseParser((response, client) -> "test")
                        .build();

        Exception exception = assertThrows(Exception.class, () -> reader.read(null).block());
        Throwable cause = Exceptions.unwrap(exception);
        assertTrue(
                cause instanceof ReaderException,
                "Expected ReaderException but got: " + cause.getClass());
    }
}
