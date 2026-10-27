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

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.ReaderException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import reactor.core.publisher.Mono;

/**
 * 从 Word 文件提取文本、表格和图像的读取器。
 *
 * <p>支持完整提取 .docx 内容，包括：
 * <ul>
 * <li>段落文本。</li>
 * <li>Markdown 或 JSON 格式表格。</li>
 * <li>Base64 编码图像。</li>
 * <li>可配置分块策略。</li>
 * </ul>
 *
 * <p><b>依赖：</b>classpath 中需要 Apache POI（poi-ooxml）。
 *
 * <p><b>用法示例：</b>
 * <pre>{@code
 * WordReader reader = new WordReader(512, SplitStrategy.PARAGRAPH, 50,
 *                                     true, false, TableFormat.MARKDOWN);
 * ReaderInput input = ReaderInput.fromString("path/to/document.docx");
 * List<Document> documents = reader.read(input).block();
 * }</pre>
 */
public class WordReader extends AbstractChunkingReader {

    private final boolean includeImage;
    private final boolean separateTable;
    private final TableFormat tableFormat;

    /**
     * 使用完整配置创建 WordReader。
     *
     * @param chunkSize 每块目标大小
     * @param splitStrategy 文本拆分策略
     * @param overlapSize 块间重叠字符数
     * @param includeImage 是否提取并包含图像
     * @param separateTable 是否将表格作为独立块
     * @param tableFormat 表格格式（MARKDOWN 或 JSON）
     * @throws IllegalArgumentException 当参数无效时
     */
    public WordReader(
            int chunkSize,
            SplitStrategy splitStrategy,
            int overlapSize,
            boolean includeImage,
            boolean separateTable,
            TableFormat tableFormat) {
        super(chunkSize, splitStrategy, overlapSize);
        if (tableFormat == null) {
            throw new IllegalArgumentException("Table format cannot be null");
        }
        this.includeImage = includeImage;
        this.separateTable = separateTable;
        this.tableFormat = tableFormat;
    }

    /**
     * 使用默认配置创建 WordReader。
     *
     * <p>默认值：
     * <ul>
     * <li>chunkSize = 512。</li>
     * <li>splitStrategy = PARAGRAPH。</li>
     * <li>overlapSize = 50。</li>
     * <li>includeImage = true。</li>
     * <li>separateTable = false。</li>
     * <li>tableFormat = MARKDOWN。</li>
     * </ul>
     */
    public WordReader() {
        this(512, SplitStrategy.PARAGRAPH, 50, true, false, TableFormat.MARKDOWN);
    }

    @Override
    public Mono<List<Document>> read(ReaderInput input) {
        if (input == null) {
            return Mono.error(new ReaderException("Input cannot be null"));
        }

        return Mono.fromCallable(
                        () -> {
                            try {
                                String wordPath = input.asString();
                                List<ContentBlock> blocks = getDataBlocks(wordPath);
                                String docId = ReaderUtils.generateDocIdMD5(wordPath);

                                List<Document> documents = new ArrayList<>();

                                // 逐块处理。
                                for (ContentBlock block : blocks) {
                                    if (block instanceof TextBlock) {
                                        // 使用 TextChunker 对文本块分块。
                                        String text = ((TextBlock) block).getText();
                                        List<String> chunks =
                                                TextChunker.chunkText(
                                                        text,
                                                        chunkSize,
                                                        splitStrategy,
                                                        overlapSize);

                                        for (String chunk : chunks) {
                                            TextBlock contentBlock =
                                                    TextBlock.builder().text(chunk).build();
                                            DocumentMetadata metadata =
                                                    new DocumentMetadata(
                                                            contentBlock,
                                                            docId,
                                                            "0"); // 临时值，稍后重设。
                                            documents.add(new Document(metadata));
                                        }
                                    } else if (block instanceof ImageBlock) {
                                        // 图像块原样加入。
                                        DocumentMetadata metadata =
                                                new DocumentMetadata(
                                                        block, docId,
                                                        "0"); // 临时值，稍后重设。
                                        documents.add(new Document(metadata));
                                    }
                                }

                                // 为全部文档重设 chunk_id。
                                List<Document> finalDocuments = new ArrayList<>();
                                for (int i = 0; i < documents.size(); i++) {
                                    Document doc = documents.get(i);
                                    DocumentMetadata oldMetadata = doc.getMetadata();
                                    DocumentMetadata newMetadata =
                                            new DocumentMetadata(
                                                    oldMetadata.getContent(),
                                                    oldMetadata.getDocId(),
                                                    String.valueOf(i));
                                    finalDocuments.add(new Document(newMetadata));
                                }

                                return finalDocuments;
                            } catch (Exception e) {
                                throw new ReaderException(
                                        "Failed to read Word document from: " + input, e);
                            }
                        })
                .onErrorMap(ReaderException.class, e -> e);
    }

    @Override
    public List<String> getSupportedFormats() {
        return List.of("doc", "docx");
    }

    /**
     * 从 Word 文档提取文本、表格、图像内容块。
     *
     * <p>按文档顺序处理，并根据配置决定合并或拆分内容块。
     *
     * @param wordPath Word 文件路径
     * @return ContentBlock 列表（TextBlock 或 ImageBlock）
     * @throws IOException 当文件无法读取时
     * @throws ReaderException 当 Word 解析失败时
     */
    private List<ContentBlock> getDataBlocks(String wordPath) throws IOException, ReaderException {
        Path path = Paths.get(wordPath);
        if (!Files.exists(path)) {
            throw new ReaderException("Word document file does not exist: " + wordPath);
        }

        try (FileInputStream fis = new FileInputStream(wordPath);
                XWPFDocument doc = new XWPFDocument(fis)) {

            List<ContentBlock> blocks = new ArrayList<>();
            String lastType = null;

            for (IBodyElement element : doc.getBodyElements()) {
                if (element.getElementType() == BodyElementType.PARAGRAPH) {
                    XWPFParagraph para = (XWPFParagraph) element;

                    // 启用时提取图像。
                    if (includeImage) {
                        List<ImageBlock> imageBlocks = extractImageData(para);
                        if (!imageBlocks.isEmpty()) {
                            blocks.addAll(imageBlocks);
                            lastType = "image";
                        }
                    }

                    // 提取文本。
                    String text = extractTextFromParagraph(para);
                    if (text != null && !text.isEmpty()) {
                        // 根据 lastType 和 separateTable 决定合并方式。
                        if ("text".equals(lastType)
                                || ("table".equals(lastType) && !separateTable)) {
                            // 追加到最后一个文本块。
                            TextBlock lastBlock = (TextBlock) blocks.get(blocks.size() - 1);
                            TextBlock mergedBlock =
                                    TextBlock.builder()
                                            .text(lastBlock.getText() + "\n" + text)
                                            .build();
                            blocks.set(blocks.size() - 1, mergedBlock);
                        } else {
                            // 创建新文本块。
                            blocks.add(TextBlock.builder().text(text).build());
                        }
                        lastType = "text";
                    } else if (!blocks.isEmpty() && "text".equals(lastType)) {
                        // 空 <w:p> 是用户在 Word 中输入的空行。追加
                        // 换行以保留段落边界；下一段合并时再添加
                        // 一个 "\n"，两者共同构成空行。
                        int lastIndex = blocks.size() - 1;
                        TextBlock lastBlock = (TextBlock) blocks.get(lastIndex);
                        blocks.set(
                                lastIndex,
                                TextBlock.builder().text(lastBlock.getText() + "\n").build());
                    }

                } else if (element.getElementType() == BodyElementType.TABLE) {
                    XWPFTable table = (XWPFTable) element;

                    // 提取表格数据。
                    List<List<String>> tableData = extractTableData(table);
                    String tableText =
                            (tableFormat == TableFormat.MARKDOWN)
                                    ? tableToMarkdown(tableData)
                                    : tableToJson(tableData);

                    // 合并逻辑。
                    if (!separateTable && ("text".equals(lastType) || "table".equals(lastType))) {
                        // 追加到最后一个文本块。
                        TextBlock lastBlock = (TextBlock) blocks.get(blocks.size() - 1);
                        TextBlock mergedBlock =
                                TextBlock.builder()
                                        .text(lastBlock.getText() + "\n" + tableText)
                                        .build();
                        blocks.set(blocks.size() - 1, mergedBlock);
                    } else {
                        // 创建新文本块。
                        blocks.add(TextBlock.builder().text(tableText).build());
                    }
                    lastType = "table";
                }
            }

            return blocks;
        } catch (IOException e) {
            throw new ReaderException("Failed to parse Word document: " + wordPath, e);
        }
    }

    /**
     * 从段落提取文本。
     *
     * <p>使用标准 POI API；文本框等高级提取需要 XML 解析。
     *
     * @param para 待提取段落
     * @return 提取文本；未找到时为空字符串
     */
    private String extractTextFromParagraph(XWPFParagraph para) {
        if (para == null) {
            return "";
        }

        // 方式 1：使用标准 API。
        String text = para.getText();
        if (text != null && !text.isEmpty()) {
            return text.trim();
        }

        // 方式 2：从 runs 提取，处理部分特殊格式。
        StringBuilder sb = new StringBuilder();
        for (XWPFRun run : para.getRuns()) {
            String runText = run.getText(0);
            if (runText != null) {
                sb.append(runText);
            }
        }

        return sb.toString().trim();
    }

    /**
     * 将表格提取为二维字符串列表。
     *
     * @param table 待提取表格
     * @return 二维列表，每个内层列表表示一行单元格
     */
    private List<List<String>> extractTableData(XWPFTable table) {
        List<List<String>> tableData = new ArrayList<>();

        for (XWPFTableRow row : table.getRows()) {
            List<String> rowData = new ArrayList<>();

            for (XWPFTableCell cell : row.getTableCells()) {
                // 提取单元格内全部段落，并以换行连接。
                StringBuilder cellText = new StringBuilder();
                for (XWPFParagraph para : cell.getParagraphs()) {
                    String paraText = para.getText();
                    if (paraText != null && !paraText.isEmpty()) {
                        if (cellText.length() > 0) {
                            cellText.append("\n");
                        }
                        cellText.append(paraText);
                    }
                }
                rowData.add(cellText.toString());
            }

            tableData.add(rowData);
        }

        return tableData;
    }

    /**
     * 将表格数据转为 Markdown。
     *
     * @param tableData 二维表格数据
     * @return Markdown 表格字符串
     */
    private String tableToMarkdown(List<List<String>> tableData) {
        if (tableData == null || tableData.isEmpty()) {
            return "";
        }

        StringBuilder md = new StringBuilder();
        // 列数按表头确定，以便填充合并或不规则数据行。
        int numCols = tableData.get(0).size();

        // 表头行。
        md.append(formatMarkdownTableRow(tableData.get(0), numCols));

        // 分隔行。
        md.append("| ");
        for (int i = 0; i < numCols; i++) {
            md.append("---");
            if (i < numCols - 1) {
                md.append(" | ");
            }
        }
        md.append(" |\n");

        // 数据行。
        for (int i = 1; i < tableData.size(); i++) {
            md.append(formatMarkdownTableRow(tableData.get(i), numCols));
        }

        return md.toString();
    }

    /**
     * 填充到 {@code numCols} 列并转义每个单元格的 Markdown 表格分隔符后格式化表格行。填充的空单元格作为空字符串走同一格式化路径。
     */
    private String formatMarkdownTableRow(List<String> cells, int numCols) {
        List<String> escaped = new ArrayList<>(numCols);
        for (int i = 0; i < numCols; i++) {
            String cell = i < cells.size() && cells.get(i) != null ? cells.get(i) : "";
            escaped.add(escapeMarkdownTableCell(cell));
        }
        return "| " + String.join(" | ", escaped) + " |\n";
    }

    /**
     * 让单元格文本保持在一行 Markdown 表格中，同时保留字面竖线和反斜杠。
     */
    private String escapeMarkdownTableCell(String cell) {
        String normalized = cell.replace("\r\n", "\n").replace('\r', '\n');
        return normalized.replace("\\", "\\\\").replace("|", "\\|").replace("\n", "<br>");
    }

    /**
     * 将表格数据转为 JSON。
     *
     * @param tableData 二维表格数据
     * @return JSON 表格字符串
     */
    private String tableToJson(List<List<String>> tableData) {
        if (tableData == null || tableData.isEmpty()) {
            return "";
        }

        StringBuilder json = new StringBuilder();
        json.append("<system-info>A table loaded as a JSON array:</system-info>\n");

        for (List<String> row : tableData) {
            try {
                json.append(JsonUtils.getJsonCodec().toJson(row));
                json.append("\n");
            } catch (JsonException e) {
                // 回退：手动构造 JSON。
                json.append("[");
                for (int i = 0; i < row.size(); i++) {
                    json.append("\"").append(escapeJson(row.get(i))).append("\"");
                    if (i < row.size() - 1) {
                        json.append(", ");
                    }
                }
                json.append("]\n");
            }
        }

        return json.toString();
    }

    /**
     * 转义 JSON 字符串特殊字符。
     *
     * @param str 待转义字符串
     * @return 转义后字符串
     */
    private String escapeJson(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /**
     * 从段落提取图像，生成 Base64 编码的 ImageBlock。
     *
     * @param para 待提取图像的段落
     * @return 包含 Base64 图像数据的 ImageBlock 列表
     */
    private List<ImageBlock> extractImageData(XWPFParagraph para) {
        List<ImageBlock> images = new ArrayList<>();

        if (para == null) {
            return images;
        }

        for (XWPFRun run : para.getRuns()) {
            List<XWPFPicture> pictures = run.getEmbeddedPictures();

            for (XWPFPicture picture : pictures) {
                try {
                    byte[] imageData = picture.getPictureData().getData();
                    String base64Data = Base64.getEncoder().encodeToString(imageData);
                    // 使用 getPictureType() 确定媒体类型。
                    int pictureType = picture.getPictureData().getPictureType();
                    String mediaType = getMediaTypeFromPictureType(pictureType);

                    ImageBlock imageBlock =
                            ImageBlock.builder()
                                    .source(
                                            Base64Source.builder()
                                                    .data(base64Data)
                                                    .mediaType(mediaType)
                                                    .build())
                                    .build();

                    images.add(imageBlock);
                } catch (Exception e) {
                    // 记录错误并继续，不因单张图像失败而让整个文档失败。
                    System.err.println("Failed to extract image: " + e.getMessage());
                }
            }
        }

        return images;
    }

    /**
     * 将 Apache POI 图片类型转换为媒体类型字符串。
     *
     * @param pictureType POI 图片类型常量
     * @return 对应媒体类型字符串
     */
    private String getMediaTypeFromPictureType(int pictureType) {
        // POI 图片类型常量来自 org.apache.poi.xwpf.usermodel.Document。
        // 对应 XWPFDocument.PICTURE_TYPE_*。
        return switch (pictureType) {
            case 2 -> "image/emf"; // EMF 格式。 格式。
            case 3 -> "image/wmf"; // WMF 格式。 格式。
            case 4 -> "image/x-pict"; // PICT 格式。 格式。
            case 5 -> "image/jpeg"; // JPEG 格式。 格式。
            case 6 -> "image/png"; // PNG 格式。 格式。
            case 7 -> "image/x-dib"; // DIB 格式。 格式。
            case 8 -> "image/gif"; // GIF 格式。 格式。
            case 9 -> "image/tiff"; // TIFF 格式。 格式。
            case 10 -> "image/x-eps"; // EPS 格式。 格式。
            case 11 -> "image/bmp"; // BMP 格式。 格式。
            case 12 -> "image/x-wpg"; // WPG 格式。 格式。
            default -> "application/octet-stream"; // 未知格式。
        };
    }

    /**
     * 检查是否启用图像提取。
     *
     * @return 启用时为 true
     */
    public boolean isIncludeImage() {
        return includeImage;
    }

    /**
     * 检查表格是否拆为独立块。
     *
     * @return 独立分块时为 true
     */
    public boolean isSeparateTable() {
        return separateTable;
    }

    /**
     * 获取表格格式。
     *
     * @return MARKDOWN 或 JSON 格式
     */
    public TableFormat getTableFormat() {
        return tableFormat;
    }
}
