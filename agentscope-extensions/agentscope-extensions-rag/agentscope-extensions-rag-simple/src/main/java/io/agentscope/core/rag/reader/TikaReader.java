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

import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.ReaderException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MimeType;
import org.apache.tika.mime.MimeTypeException;
import org.apache.tika.mime.MimeTypes;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import reactor.core.publisher.Mono;

/**
 * 使用 Apache Tika 从多种文档格式提取文本的读取器。
 *
 * <p>支持 DOC、XSL、PDF、PPT 等格式及多种拆分策略。
 *
 * <p>【学习提示】响应式对象可被重复订阅，状态隔离必须按订阅分配，而不只是每次调用 read 分配。默认路径通过工厂创建内容处理器；若自定义工厂仍返回同一个有状态实例，接口本身不能阻止文档内容串扰。
 *
 * <p>用法示例：
 * <pre>{@code
 * TikaReader reader = new TikaReader();
 * ReaderInput input = ReaderInput.fromPath("/path/to/file");
 * List<Document> documents = reader.read(input).block();
 * }</pre>
 */
public class TikaReader extends AbstractChunkingReader {

    private static final Logger log = LoggerFactory.getLogger(TikaReader.class);

    /**
     * 为单次 {@link #read(ReaderInput)} 订阅提供内容处理器。
     *
     * <p>处理器有状态：{@link BodyContentHandler} 保留全部写入内容，因此实例不能跨读取共享，否则后续文档会包含之前文档的文本。每次读取订阅调用一次工厂。
     */
    private final Supplier<ContentHandler> handlerFactory;

    /**
     * 使用指定配置创建 TikaReader。
     *
     * <p>传入处理器原样跨订阅复用。处理器会累积字符，共享实例可能使前一文档内容残留到后一文档；需要每次订阅新建实例时使用 {@link #perReadHandler(int, SplitStrategy, int, Supplier)}。
     *
     * @param chunkSize 每块目标大小，单位依策略而定
     * @param splitStrategy 文本拆分策略
     * @param overlapSize 重叠字符/token 数
     * @param handler 文本提取内容处理器
     * @throws IllegalArgumentException 当参数无效时
     * @deprecated 请使用 {@link #perReadHandler(int, SplitStrategy, int, Supplier)}。此构造方法跨订阅复用处理器，有状态处理器可能造成文档内容串扰。
     */
    @Deprecated(since = "2.0.5")
    public TikaReader(
            int chunkSize, SplitStrategy splitStrategy, int overlapSize, ContentHandler handler) {
        super(chunkSize, splitStrategy, overlapSize);
        this.handlerFactory = singletonHandler(handler);
    }

    /**
     * 创建每次读取订阅都向 {@code handlerFactory} 请求处理器的 TikaReader。
     *
     * <p>不同于 {@link #TikaReader(int, SplitStrategy, int, ContentHandler)}，工厂版本让每次订阅使用自己的处理器，避免连续及并发读取共享累计状态。
     *
     * <p>工厂返回 null 时，发布者以 {@link IllegalStateException} 失败；工厂抛出异常时原样传播，不包装为 {@link ReaderException}。
     *
     * @param chunkSize 每块目标大小，单位依策略而定
     * @param splitStrategy 文本拆分策略
     * @param overlapSize 重叠字符/token 数
     * @param handlerFactory 每次读取订阅的内容处理器工厂，不得返回 null
     * @return 新的 TikaReader
     * @throws IllegalArgumentException 当参数无效或工厂为 null 时
     */
    public static TikaReader perReadHandler(
            int chunkSize,
            SplitStrategy splitStrategy,
            int overlapSize,
            Supplier<ContentHandler> handlerFactory) {
        return new TikaReader(chunkSize, splitStrategy, overlapSize, handlerFactory);
    }

    /**
     * 由默认构造方法和 {@link #perReadHandler} 共享。
     */
    private TikaReader(
            int chunkSize,
            SplitStrategy splitStrategy,
            int overlapSize,
            Supplier<ContentHandler> handlerFactory) {
        super(chunkSize, splitStrategy, overlapSize);
        if (handlerFactory == null) {
            throw new IllegalArgumentException("handler factory cannot be null");
        }
        this.handlerFactory = handlerFactory;
    }

    /**
     * 使用默认配置创建 TikaReader。
     *
     * <p>默认 chunkSize=512、strategy=PARAGRAPH、overlapSize=50、handler=BodyContentHandler(-1)。每次订阅新建默认处理器，使连续读取保持独立。
     */
    public TikaReader() {
        this(512, SplitStrategy.PARAGRAPH, 50, () -> new BodyContentHandler(-1));
    }

    /**
     * 包装单个处理器，使每次读取返回同一实例。
     */
    private static Supplier<ContentHandler> singletonHandler(ContentHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("content handler cannot be null");
        }
        AtomicInteger callCount = new AtomicInteger();
        return () -> {
            if (callCount.incrementAndGet() == 2) {
                log.warn(
                        "TikaReader is reusing one content handler across read subscriptions; "
                                + "text from earlier reads may appear in later ones. Use "
                                + "TikaReader.perReadHandler(...) instead.");
            }
            return handler;
        };
    }

    @Override
    public Mono<List<Document>> read(ReaderInput input) {
        if (input == null) {
            return Mono.error(new ReaderException("Input cannot be null"));
        }

        return Mono.fromCallable(
                        () -> {
                            String path = input.asString();
                            // 处理器会累计所有写入内容，因此每次读取
                            // 都使用自己的实例。
                            ContentHandler handler = handlerFactory.get();
                            if (handler == null) {
                                throw new IllegalStateException(
                                        "content handler factory returned null");
                            }
                            try {
                                String text = extractTextFromTika(path, handler);
                                List<String> chunks =
                                        TextChunker.chunkText(
                                                text, chunkSize, splitStrategy, overlapSize);
                                return createDocuments(chunks, path);
                            } catch (Exception e) {
                                throw new ReaderException(
                                        "Failed to read document from: " + input, e);
                            }
                        })
                .onErrorMap(ReaderException.class, e -> e);
    }

    @Override
    public List<String> getSupportedFormats() {
        Set<String> extensions = new HashSet<>();
        MimeTypes mimeTypes = MimeTypes.getDefaultMimeTypes();

        for (MediaType mediaType : mimeTypes.getMediaTypeRegistry().getTypes()) {
            MimeType mimeType;
            try {
                mimeType = mimeTypes.forName(mediaType.toString());
                extensions.addAll(mimeType.getExtensions());
            } catch (MimeTypeException e) {
                log.warn("Failed to parse mime type for " + mediaType.toString(), e);
            }
        }

        // 移除点字符。
        Set<String> formats =
                extensions.stream()
                        .map(MediaUtils::getExtension)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toSet());
        return List.copyOf(formats);
    }

    private String extractTextFromTika(String path, ContentHandler handler)
            throws IOException, SAXException, TikaException {
        try (InputStream is = Files.newInputStream(Path.of(path))) {
            AutoDetectParser parser = new AutoDetectParser();
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            parser.parse(is, handler, metadata, context);
            return handler.toString();
        }
    }

    /**
     * 从文本块创建具有确定性 doc_id 的 Document。
     *
     * @param chunks 文本块列表
     * @param path 文档路径，用于生成 doc_id
     * @return Document 列表
     */
    private List<Document> createDocuments(List<String> chunks, String path) {
        // 使用文档路径的 SHA256 生成确定性 doc_id。
        String docId = ReaderUtils.generateDocIdSHA256(path);
        List<Document> documents = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            TextBlock content = TextBlock.builder().text(chunks.get(i)).build();
            DocumentMetadata metadata = new DocumentMetadata(content, docId, String.valueOf(i));
            documents.add(new Document(metadata));
        }

        return documents;
    }
}
