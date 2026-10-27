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
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import reactor.core.publisher.Mono;

/**
 * 读取 PDF 并提取文本的读取器。
 *
 * <p>从 PDF 提取文本，再按指定策略分块，支持与 TextReader 类似的文本拆分策略。
 *
 * <p>当前文本提取使用 Apache PDFBox；图像提取选项仍为未来实现的占位，不应据此假定已执行图像解析或 OCR。
 *
 * <p>用法示例：
 * <pre>{@code
 * PDFReader reader = new PDFReader(512, SplitStrategy.PARAGRAPH, 50);
 * ReaderInput input = ReaderInput.fromString("path/to/document.pdf");
 * List<Document> documents = reader.read(input).block();
 * }</pre>
 */
public class PDFReader extends AbstractChunkingReader {

    private final boolean extractImages;

    /**
     * 使用指定配置创建 PDFReader。
     *
     * @param chunkSize 每块目标大小
     * @param splitStrategy 文本拆分策略
     * @param overlapSize 块间重叠字符数
     * @throws IllegalArgumentException 当参数无效时
     */
    public PDFReader(int chunkSize, SplitStrategy splitStrategy, int overlapSize) {
        this(chunkSize, splitStrategy, overlapSize, false);
    }

    /**
     * 使用指定配置创建 PDFReader。
     *
     * @param chunkSize 每块目标大小
     * @param splitStrategy 文本拆分策略
     * @param overlapSize 块间重叠字符数
     * @param extractImages true 表示启用 PDF 图像提取选项（未来实现的占位）
     * @throws IllegalArgumentException 当参数无效时
     */
    public PDFReader(
            int chunkSize, SplitStrategy splitStrategy, int overlapSize, boolean extractImages) {
        super(chunkSize, splitStrategy, overlapSize);
        this.extractImages = extractImages;
    }

    /**
     * 使用默认配置创建 PDFReader。
     *
     * <p>默认 chunkSize=512、strategy=PARAGRAPH、overlapSize=50。
     */
    public PDFReader() {
        this(512, SplitStrategy.PARAGRAPH, 50, false);
    }

    @Override
    public Mono<List<Document>> read(ReaderInput input) {
        if (input == null) {
            return Mono.error(new ReaderException("Input cannot be null"));
        }

        return Mono.fromCallable(
                        () -> {
                            try {
                                String pdfPath = input.asString();
                                String text = extractTextFromPDF(pdfPath);
                                List<String> chunks =
                                        TextChunker.chunkText(
                                                text, chunkSize, splitStrategy, overlapSize);
                                return createDocuments(chunks, pdfPath);
                            } catch (Exception e) {
                                throw new ReaderException("Failed to read PDF from: " + input, e);
                            }
                        })
                .onErrorMap(ReaderException.class, e -> e);
    }

    @Override
    public List<String> getSupportedFormats() {
        return List.of("pdf");
    }

    /**
     * 使用 Apache PDFBox 从 PDF 文件提取文本。
     *
     * <p>加载文档后逐页提取，以双换行（\n\n）连接页面，对齐 Python 实现。
     *
     * @param pdfPath PDF 文件路径
     * @return 页面以 \n\n 分隔的提取文本
     * @throws IOException 当文件无法读取时
     * @throws ReaderException 当 PDF 解析失败时
     */
    private String extractTextFromPDF(String pdfPath) throws IOException, ReaderException {
        Path path = Paths.get(pdfPath);
        if (!Files.exists(path)) {
            throw new ReaderException("PDF file does not exist: " + pdfPath);
        }

        try (PDDocument document = Loader.loadPDF(new File(pdfPath))) {
            PDFTextStripper stripper = new PDFTextStripper();
            StringBuilder fullText = new StringBuilder();

            int totalPages = document.getNumberOfPages();
            for (int page = 1; page <= totalPages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document);
                fullText.append(pageText);

                // 页面间加入双换行，与 Python 实现一致。
                if (page < totalPages) {
                    fullText.append("\n\n");
                }
            }

            return fullText.toString();
        } catch (IOException e) {
            throw new ReaderException("Failed to extract text from PDF: " + pdfPath, e);
        }
    }

    /**
     * 从文本块创建具有确定性 doc_id 的 Document。
     *
     * @param chunks 文本块列表
     * @param pdfPath PDF 路径，用于生成 doc_id
     * @return Document 列表
     */
    private List<Document> createDocuments(List<String> chunks, String pdfPath) {
        // 使用 PDF 路径的 SHA256 生成确定性 doc_id，与 Python 实现一致。
        String docId = ReaderUtils.generateDocIdSHA256(pdfPath);
        List<Document> documents = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            TextBlock content = TextBlock.builder().text(chunks.get(i)).build();
            DocumentMetadata metadata = new DocumentMetadata(content, docId, String.valueOf(i));
            documents.add(new Document(metadata));
        }

        return documents;
    }

    /**
     * 检查是否启用图像提取选项。
     *
     * @return 选项启用时为 true
     */
    public boolean isExtractImages() {
        return extractImages;
    }
}
