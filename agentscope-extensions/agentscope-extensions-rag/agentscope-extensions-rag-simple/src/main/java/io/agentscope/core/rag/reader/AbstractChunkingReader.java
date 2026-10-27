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

/**
 * 支持文本分块的读取器抽象基类。
 *
 * <p>提供按策略拆分文档的公共功能，负责参数校验及块大小、拆分策略、重叠大小的共享访问。
 *
 * <p>子类应实现 {@link Reader}，提供具体格式的读取逻辑。
 */
public abstract class AbstractChunkingReader implements Reader {

    protected final int chunkSize;
    protected final SplitStrategy splitStrategy;
    protected final int overlapSize;

    /**
     * 使用指定配置创建 AbstractChunkingReader。
     *
     * @param chunkSize 每块目标大小，单位依策略而定
     * @param splitStrategy 文本拆分策略
     * @param overlapSize 块间重叠字符/token 数
     * @throws IllegalArgumentException 当参数无效时
     */
    protected AbstractChunkingReader(int chunkSize, SplitStrategy splitStrategy, int overlapSize) {
        validateChunkingParameters(chunkSize, splitStrategy, overlapSize);
        this.chunkSize = chunkSize;
        this.splitStrategy = splitStrategy;
        this.overlapSize = overlapSize;
    }

    /**
     * 校验分块参数。
     *
     * @param chunkSize 待校验块大小
     * @param splitStrategy 待校验拆分策略
     * @param overlapSize 待校验重叠大小
     * @throws IllegalArgumentException 当任一参数无效时
     */
    protected static void validateChunkingParameters(
            int chunkSize, SplitStrategy splitStrategy, int overlapSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("Chunk size must be positive");
        }
        if (splitStrategy == null) {
            throw new IllegalArgumentException("Split strategy cannot be null");
        }
        if (overlapSize < 0) {
            throw new IllegalArgumentException("Overlap size cannot be negative");
        }
        if (overlapSize >= chunkSize) {
            throw new IllegalArgumentException("Overlap size must be less than chunk size");
        }
    }

    /**
     * 获取块大小。
     *
     * @return 块大小
     */
    public int getChunkSize() {
        return chunkSize;
    }

    /**
     * 获取拆分策略。
     *
     * @return 拆分策略
     */
    public SplitStrategy getSplitStrategy() {
        return splitStrategy;
    }

    /**
     * 获取重叠大小。
     *
     * @return 重叠大小
     */
    public int getOverlapSize() {
        return overlapSize;
    }
}
