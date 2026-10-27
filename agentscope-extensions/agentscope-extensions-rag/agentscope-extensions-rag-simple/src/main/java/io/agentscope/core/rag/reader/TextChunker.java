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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 将文本拆成较小片段的工具类。
 *
 * <p>支持多种策略及块间重叠，以保留上下文。
 *
 * <p>【学习提示】这里的 TOKEN 策略只是按约 4 字符/token 换算，中文、代码和不同 tokenizer 的实际长度可能明显不同；SEMANTIC 当前也只是段落回退。重叠能保留跨块上下文，但会增加索引量和重复检索内容，不等于更高召回质量的保证。
 */
public final class TextChunker {

    private static final int CHARS_PER_TOKEN = 4; // 近似估算：1 token ≈ 4 字符。
    private static final Pattern PARAGRAPH_SEPARATOR = Pattern.compile("\\n\\s*\\n");

    private TextChunker() {
        // 工具类，禁止实例化。
    }

    /**
     * 按指定策略分块文本。
     *
     * @param text 待分块文本
     * @param chunkSize 每块目标大小，单位依策略而定
     * @param strategy 拆分策略
     * @param overlapSize 块间重叠的字符/token 数
     * @return 文本块列表
     */
    public static List<String> chunkText(
            String text, int chunkSize, SplitStrategy strategy, int overlapSize) {
        if (text == null) {
            throw new IllegalArgumentException("Text cannot be null");
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("Chunk size must be positive");
        }
        if (overlapSize < 0) {
            throw new IllegalArgumentException("Overlap size cannot be negative");
        }
        if (overlapSize >= chunkSize) {
            throw new IllegalArgumentException("Overlap size must be less than chunk size");
        }

        if (text.isEmpty()) {
            return List.of("");
        }

        return switch (strategy) {
            case CHARACTER -> chunkByCharacter(text, chunkSize, overlapSize);
            case PARAGRAPH -> chunkByParagraph(text, chunkSize, overlapSize);
            case TOKEN -> chunkByToken(text, chunkSize, overlapSize);
            case SEMANTIC -> chunkBySemantic(text, chunkSize, overlapSize);
        };
    }

    /**
     * 按字符数分块文本。
     *
     * @param text 待分块文本
     * @param chunkSize 每块字符数
     * @param overlapSize 重叠字符数
     * @return 文本块列表
     */
    private static List<String> chunkByCharacter(String text, int chunkSize, int overlapSize) {
        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(text.substring(start, end));

            if (end >= text.length()) {
                break;
            }

            // 前移起点，并考虑重叠部分。
            start = end - overlapSize;
            if (start < 0) {
                start = 0;
            }
        }

        return chunks;
    }

    /**
     * 按段落分块，同时遵循块大小限制。
     *
     * @param text 待分块文本
     * @param chunkSize 每块最大字符数
     * @param overlapSize 重叠字符数
     * @return 文本块列表
     */
    private static List<String> chunkByParagraph(String text, int chunkSize, int overlapSize) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = PARAGRAPH_SEPARATOR.split(text);

        StringBuilder currentChunk = new StringBuilder();
        for (String paragraph : paragraphs) {
            String trimmedParagraph = paragraph.trim();
            if (trimmedParagraph.isEmpty()) {
                continue;
            }

            // 加入当前段落会超出大小时，保存已有块。
            if (currentChunk.length() > 0
                    && currentChunk.length() + trimmedParagraph.length() + 2 > chunkSize) {
                chunks.add(currentChunk.toString().trim());
                currentChunk = new StringBuilder();

                // 加入前一块的重叠部分。
                if (!chunks.isEmpty() && overlapSize > 0) {
                    String lastChunk = chunks.get(chunks.size() - 1);
                    int overlapStart = Math.max(0, lastChunk.length() - overlapSize);
                    currentChunk.append(lastChunk.substring(overlapStart));
                }
            }

            if (currentChunk.length() > 0) {
                currentChunk.append("\n\n");
            }
            currentChunk.append(trimmedParagraph);

            // 单段超过块大小时改按字符拆分。
            if (currentChunk.length() > chunkSize) {
                String largeParagraph = currentChunk.toString();
                currentChunk = new StringBuilder();
                List<String> subChunks = chunkByCharacter(largeParagraph, chunkSize, overlapSize);
                chunks.addAll(subChunks.subList(0, subChunks.size() - 1));
                if (!subChunks.isEmpty()) {
                    currentChunk.append(subChunks.get(subChunks.size() - 1));
                }
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks.isEmpty() ? List.of(text) : chunks;
    }

    /**
     * 按近似 token 数分块。
     *
     * <p>使用简单启发式：1 token ≈ 4 字符。
     *
     * @param text 待分块文本
     * @param chunkSize 每块目标 token 数
     * @param overlapSize 重叠 token 数
     * @return 文本块列表
     */
    private static List<String> chunkByToken(String text, int chunkSize, int overlapSize) {
        // 将 token 大小转换为字符大小。
        int charChunkSize = chunkSize * CHARS_PER_TOKEN;
        int charOverlapSize = overlapSize * CHARS_PER_TOKEN;

        // 按换算后的大小执行字符分块。
        return chunkByCharacter(text, charChunkSize, charOverlapSize);
    }

    /**
     * 按语义边界分块。
     *
     * <p>当前回退到段落分块；完整实现应通过 NLP 识别句子与分句边界。
     *
     * @param text 待分块文本
     * @param chunkSize 每块目标大小
     * @param overlapSize 重叠字符数
     * @return 文本块列表
     */
    private static List<String> chunkBySemantic(String text, int chunkSize, int overlapSize) {
        // 目前回退到段落分块。
        // 完整实现应使用句子切分。
        return chunkByParagraph(text, chunkSize, overlapSize);
    }
}
