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

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.rag.exception.ReaderException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * 图像文件读取器。
 *
 * <p>支持多种图像格式，创建包含图像内容的 Document，供多模态 RAG 使用。OCR 开关目前仅预留接口，实际提取仍为占位实现。
 *
 * <p>用法示例：
 * <pre>{@code
 * ImageReader reader = new ImageReader(false);
 * ReaderInput input = ReaderInput.fromString("path/to/image.jpg");
 * List<Document> documents = reader.read(input).block();
 * }</pre>
 */
public class ImageReader implements Reader {

    private final boolean enableOCR;

    /**
     * 创建禁用 OCR 的 ImageReader。
     */
    public ImageReader() {
        this(false);
    }

    /**
     * 使用指定 OCR 设置创建 ImageReader。
     *
     * @param enableOCR true 表示启用 OCR 文本提取选项
     */
    public ImageReader(boolean enableOCR) {
        this.enableOCR = enableOCR;
    }

    @Override
    public Mono<List<Document>> read(ReaderInput input) {
        if (input == null) {
            return Mono.error(new ReaderException("Input cannot be null"));
        }

        return Mono.fromCallable(
                        () -> {
                            try {
                                String imagePath = input.asString();
                                return loadImageDocument(imagePath);
                            } catch (Exception e) {
                                throw new ReaderException("Failed to read image from: " + input, e);
                            }
                        })
                .onErrorMap(ReaderException.class, e -> e);
    }

    @Override
    public List<String> getSupportedFormats() {
        return List.of("jpg", "jpeg", "png", "gif", "bmp", "tiff", "webp");
    }

    /**
     * 从给定路径加载图像文档。
     *
     * @param imagePath 图像文件路径，可为本地路径或 URL
     * @return 包含单个图像内容 Document 的列表
     */
    private List<Document> loadImageDocument(String imagePath) {
        // 使用图像路径的 MD5 生成确定性 doc_id，与 Python 实现一致。
        String docId = ReaderUtils.generateDocIdMD5(imagePath);

        // 创建使用 URLSource 的 ImageBlock。
        String url;
        if (isUrl(imagePath)) {
            url = imagePath;
        } else {
            // 本地文件使用 file:// 协议。
            url = imagePath.startsWith("file://") ? imagePath : "file://" + imagePath;
        }

        URLSource source = URLSource.builder().url(url).build();
        ImageBlock content = ImageBlock.builder().source(source).build();

        // 若启用 OCR，则尝试从图像提取文本。
        // 实际 OCR 需要额外依赖，
        // 当前只保存图像内容。
        // 未来可创建复合 ContentBlock，或将 OCR 文本保存为独立字段。

        DocumentMetadata metadata = new DocumentMetadata(content, docId, "0");
        return List.of(new Document(metadata));
    }

    /**
     * 检查给定路径是否为 URL。
     *
     * @param path 待检查路径
     * @return 是 URL 时为 true，否则为 false
     */
    private boolean isUrl(String path) {
        if (path == null) {
            return false;
        }
        String lowerPath = path.toLowerCase();
        return lowerPath.startsWith("http://")
                || lowerPath.startsWith("https://")
                || lowerPath.startsWith("file://");
    }

    /**
     * 通过 OCR 从图像提取文本。
     *
     * <p>目前为占位方法；实际实现需要 Tesseract 或云 OCR API 等额外依赖。
     *
     * @param imagePath 图像文件路径
     * @return 提取文本；OCR 不可用时为空字符串
     */
    private String extractTextFromImage(String imagePath) {
        // 占位：未来在此实现实际 OCR。
        // 当前返回空字符串，表示未提取文本。
        // 未来可集成：
        // Tesseract OCR（tess4j 库）。
        // 云 OCR API（DashScope、OpenAI Vision 等）。
        return "";
    }

    /**
     * 检查 OCR 选项是否启用。
     *
     * @return 启用时为 true
     */
    public boolean isOcrEnabled() {
        return enableOCR;
    }
}
