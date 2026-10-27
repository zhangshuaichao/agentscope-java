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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * 读取并分块文本文档的 TextReader。
 *
 * <p>支持多种文本格式与拆分策略，可从字符串或文件读取文本，并按指定策略分块。
 *
 * <p>用法示例：
 * <pre>{@code
 * TextReader reader = new TextReader(512, SplitStrategy.PARAGRAPH, 50);
 * ReaderInput input = ReaderInput.fromString("Long text content...");
 * List<Document> documents = reader.read(input).block();
 * }</pre>
 */
public class TextReader extends AbstractChunkingReader {

    /**
     * 使用指定配置创建 TextReader。
     *
     * @param chunkSize 每块目标大小，单位依策略而定
     * @param splitStrategy 文本拆分策略
     * @param overlapSize 块间重叠字符/token 数
     * @throws IllegalArgumentException 当参数无效时
     */
    public TextReader(int chunkSize, SplitStrategy splitStrategy, int overlapSize) {
        super(chunkSize, splitStrategy, overlapSize);
    }

    /**
     * 使用默认配置创建 TextReader。
     *
     * <p>默认 chunkSize=512、strategy=PARAGRAPH、overlapSize=50。
     */
    public TextReader() {
        this(512, SplitStrategy.PARAGRAPH, 50);
    }

    @Override
    public Mono<List<Document>> read(ReaderInput input) {
        if (input == null) {
            return Mono.error(new ReaderException("Input cannot be null"));
        }

        return Mono.fromCallable(
                        () -> {
                            try {
                                String text = input.asString();
                                List<String> chunks =
                                        TextChunker.chunkText(
                                                text, chunkSize, splitStrategy, overlapSize);
                                return createDocuments(chunks);
                            } catch (Exception e) {
                                throw new ReaderException("Failed to read text input", e);
                            }
                        })
                .onErrorMap(ReaderException.class, e -> e); // 原样重新抛出 ReaderException。
    }

    @Override
    public List<String> getSupportedFormats() {
        return List.of("txt", "md", "rst");
    }

    /**
     * 从文本块创建 Document 对象。
     *
     * @param chunks 文本块列表
     * @return Document 列表
     */
    private List<Document> createDocuments(List<String> chunks) {
        String docId = UUID.randomUUID().toString();
        List<Document> documents = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            TextBlock content = TextBlock.builder().text(chunks.get(i)).build();
            DocumentMetadata metadata = new DocumentMetadata(content, docId, String.valueOf(i));
            documents.add(new Document(metadata));
        }

        return documents;
    }
}
