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

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.ReaderException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import reactor.core.publisher.Mono;

/**
 * 通用外部 API 文档解析读取器，可集成第三方解析服务。
 *
 * <p>通过函数式接口高度抽象和配置，以适配不同 API：
 * <ul>
 * <li>自定义 HTTP 请求构造（请求头、请求体、方法等）。</li>
 * <li>自定义响应解析，从 JSON/XML 等格式提取 Markdown。</li>
 * <li>支持通过自定义逻辑轮询异步任务完成状态。</li>
 * <li>支持上传文件或传递 URL。</li>
 * </ul>
 *
 * <p>集成 MinerU Tianshu 的示例：
 * <pre>{@code
 * ExternalApiReader reader = ExternalApiReader.builder()
 *     .requestBuilder((filePath, client) -> {
 *         // Build task submission request
 *         String json = String.format(
 *             "{\"file_path\":\"%s\",\"engine\":\"pipeline\"}",
 *             filePath
 *         );
 *         return new Request.Builder()
 *             .url("http://localhost:8000/api/v1/tasks/submit")
 *             .header("Content-Type", "application/json")
 *             .post(RequestBody.create(json, MediaType.get("application/json")))
 *             .build();
 *     })
 *     .responseParser((response, client) -> {
 *         // Extract task_id from response, then poll for results
 *         String responseBody = response.body().string();
 *         // Parse JSON and poll...
 *         return markdown;
 *     })
 *     .chunkSize(512)
 *     .splitStrategy(SplitStrategy.PARAGRAPH)
 *     .overlapSize(50)
 *     .supportedFormats("pdf", "docx", "pptx", "xlsx", "txt")  // Specify supported formats
 *     .build();
 *
 * List<Document> docs = reader.read(ReaderInput.fromString("path/to/file.pdf")).block();
 * }</pre>
 *
 * <p>集成其他 API 的示例：
 * <pre>{@code
 * ExternalApiReader reader = ExternalApiReader.builder()
 *     .requestBuilder((filePath, client) -> {
 *         // Custom request building logic
 *         byte[] fileBytes = Files.readAllBytes(Paths.get(filePath));
 *         RequestBody body = RequestBody.create(fileBytes, MediaType.get("application/octet-stream"));
 *         return new Request.Builder()
 *             .url("https://api.example.com/parse")
 *             .header("Authorization", "Bearer YOUR_TOKEN")
 *             .post(body)
 *             .build();
 *     })
 *     .responseParser((response, client) -> {
 *         // Custom response parsing logic
 *         return response.body().string();
 *     })
 *     .build();
 * }</pre>
 */
public class ExternalApiReader extends AbstractChunkingReader {

    private final RequestBuilder requestBuilder;
    private final ResponseParser responseParser;
    private final OkHttpClient httpClient;
    private final int maxRetries;
    private final Duration retryDelay;
    private final List<String> supportedFormats;

    /**
     * 私有构造方法，通过 Builder 创建实例。
     */
    private ExternalApiReader(Builder builder) {
        super(builder.chunkSize, builder.splitStrategy, builder.overlapSize);
        this.requestBuilder = builder.requestBuilder;
        this.responseParser = builder.responseParser;
        this.maxRetries = builder.maxRetries;
        this.retryDelay = builder.retryDelay;
        this.supportedFormats =
                builder.supportedFormats != null && !builder.supportedFormats.isEmpty()
                        ? Collections.unmodifiableList(new ArrayList<>(builder.supportedFormats))
                        : Collections.emptyList();

        // 构建 OkHttpClient。
        OkHttpClient.Builder clientBuilder =
                new OkHttpClient.Builder()
                        .connectTimeout(builder.connectTimeout)
                        .readTimeout(builder.readTimeout)
                        .writeTimeout(builder.writeTimeout);

        // 添加自定义拦截器。
        if (builder.interceptors != null) {
            builder.interceptors.forEach(clientBuilder::addInterceptor);
        }

        this.httpClient = clientBuilder.build();
    }

    @Override
    public Mono<List<Document>> read(ReaderInput input) {
        if (input == null) {
            return Mono.error(new ReaderException("Input cannot be null"));
        }

        return Mono.fromCallable(
                        () -> {
                            String filePath = input.asString();
                            validateFile(filePath);

                            // 1. 调用外部 API 获取 Markdown。
                            String markdown = callExternalApi(filePath);

                            // 2. 用已有分块逻辑处理 Markdown。
                            List<String> chunks =
                                    TextChunker.chunkText(
                                            markdown, chunkSize, splitStrategy, overlapSize);

                            // 3. 创建 Document 对象。
                            return createDocuments(chunks, filePath);
                        })
                .onErrorMap(
                        throwable -> {
                            if (throwable instanceof ReaderException) {
                                return throwable;
                            }
                            return new ReaderException(
                                    "Failed to read document via external API", throwable);
                        });
    }

    @Override
    public List<String> getSupportedFormats() {
        // 返回配置的格式，未指定时返回空列表。
        // 空列表表示尝试处理任何文件格式。
        return supportedFormats;
    }

    /**
     * 调用外部 API 解析文档。
     *
     * @param filePath 文件路径
     * @return 解析后的 Markdown 文本
     * @throws ReaderException 当 API 调用失败时
     */
    private String callExternalApi(String filePath) throws ReaderException {
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                // 使用自定义 requestBuilder 构建请求。
                Request request = requestBuilder.buildRequest(filePath, httpClient);

                // 发送请求。
                try (Response response = httpClient.newCall(request).execute()) {
                    // 检查响应状态。
                    if (response.isSuccessful()) {
                        // 使用自定义 responseParser 解析响应。
                        return responseParser.parseResponse(response, httpClient);
                    } else {
                        String errorBody =
                                response.body() != null
                                        ? response.body().string()
                                        : "No response body";
                        throw new ReaderException(
                                "API returned error status: "
                                        + response.code()
                                        + ", body: "
                                        + errorBody);
                    }
                }

            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(retryDelay.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ReaderException("Request interrupted during retry", ie);
                    }
                }
            }
        }

        throw new ReaderException(
                "Failed to call external API after " + (maxRetries + 1) + " attempts",
                lastException);
    }

    /**
     * 校验文件是否存在。
     *
     * @param filePath 待校验文件路径
     * @throws ReaderException 当文件不存在时
     */
    private void validateFile(String filePath) throws ReaderException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new ReaderException("File does not exist: " + filePath);
        }
    }

    /**
     * 创建 Document 对象列表。
     */
    private List<Document> createDocuments(List<String> chunks, String filePath) {
        String docId = ReaderUtils.generateDocIdSHA256(filePath);
        List<Document> documents = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            TextBlock content = TextBlock.builder().text(chunks.get(i)).build();
            DocumentMetadata metadata = new DocumentMetadata(content, docId, String.valueOf(i));
            documents.add(new Document(metadata));
        }

        return documents;
    }

    /**
     * 创建 Builder 实例。
     *
     * @return 用于构造 {@link ExternalApiReader} 的新 {@link Builder} 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 请求构建函数式接口，允许用户自定义 HTTP 请求构造。
     */
    @FunctionalInterface
    public interface RequestBuilder {
        /**
         * 根据文件路径构建 HTTP 请求。
         *
         * @param filePath 文件路径
         * @param httpClient OkHttpClient 实例，可用于文件上传等复杂场景
         * @return 构建后的 OkHttp Request
         * @throws Exception 当构建失败时
         */
        Request buildRequest(String filePath, OkHttpClient httpClient) throws Exception;
    }

    /**
     * 响应解析函数式接口，允许用户自定义从 API 响应提取 Markdown 的方式。
     */
    @FunctionalInterface
    public interface ResponseParser {
        /**
         * 从 HTTP 响应解析 Markdown 文本。
         *
         * @param response OkHttp Response 对象
         * @param httpClient OkHttpClient 实例，可用于轮询任务状态等后续请求
         * @return 解析后的 Markdown 文本
         * @throws Exception 当解析失败时
         */
        String parseResponse(Response response, OkHttpClient httpClient) throws Exception;
    }

    /**
     * 构造 ExternalApiReader 的构建器。
     */
    public static class Builder {
        private RequestBuilder requestBuilder;
        private ResponseParser responseParser;
        private int chunkSize = 512;
        private SplitStrategy splitStrategy = SplitStrategy.PARAGRAPH;
        private int overlapSize = 50;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofMinutes(5);
        private Duration writeTimeout = Duration.ofMinutes(5);
        private int maxRetries = 3;
        private Duration retryDelay = Duration.ofSeconds(2);
        private List<okhttp3.Interceptor> interceptors;
        private List<String> supportedFormats;

        /**
         * 设置请求构建器。
         */
        public Builder requestBuilder(RequestBuilder requestBuilder) {
            this.requestBuilder = requestBuilder;
            return this;
        }

        /**
         * 设置响应解析器。
         */
        public Builder responseParser(ResponseParser responseParser) {
            this.responseParser = responseParser;
            return this;
        }

        /**
         * 设置分块大小。
         */
        public Builder chunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
            return this;
        }

        /**
         * 设置拆分策略。
         */
        public Builder splitStrategy(SplitStrategy splitStrategy) {
            this.splitStrategy = splitStrategy;
            return this;
        }

        /**
         * 设置重叠大小。
         */
        public Builder overlapSize(int overlapSize) {
            this.overlapSize = overlapSize;
            return this;
        }

        /**
         * 设置连接超时。
         */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /**
         * 设置读取超时。
         */
        public Builder readTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        /**
         * 设置写入超时。
         */
        public Builder writeTimeout(Duration writeTimeout) {
            this.writeTimeout = writeTimeout;
            return this;
        }

        /**
         * 设置最大重试次数。
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * 设置重试间隔。
         */
        public Builder retryDelay(Duration retryDelay) {
            this.retryDelay = retryDelay;
            return this;
        }

        /**
         * 添加 OkHttp 拦截器，用于日志、认证等。
         */
        public Builder addInterceptor(okhttp3.Interceptor interceptor) {
            if (this.interceptors == null) {
                this.interceptors = new ArrayList<>();
            }
            this.interceptors.add(interceptor);
            return this;
        }

        /**
         * 设置支持的文件格式。
         *
         * <p>未设置或为空时，尝试处理任何格式，由外部 API 负责格式校验。
         *
         * @param formats 支持的扩展名列表，如 pdf、docx、txt
         * @return 当前构建器
         */
        public Builder supportedFormats(List<String> formats) {
            this.supportedFormats = formats;
            return this;
        }

        /**
         * 设置支持的文件格式（可变参数版本）。
         *
         * <p>未设置或为空时，尝试处理任何格式，由外部 API 负责格式校验。
         *
         * @param formats 支持的扩展名，如 pdf、docx、txt
         * @return 当前构建器
         */
        public Builder supportedFormats(String... formats) {
            this.supportedFormats = Arrays.asList(formats);
            return this;
        }

        /**
         * 构建 ExternalApiReader 实例。
         */
        public ExternalApiReader build() {
            if (requestBuilder == null) {
                throw new IllegalArgumentException("Request builder cannot be null");
            }
            if (responseParser == null) {
                throw new IllegalArgumentException("Response parser cannot be null");
            }
            return new ExternalApiReader(this);
        }
    }
}
