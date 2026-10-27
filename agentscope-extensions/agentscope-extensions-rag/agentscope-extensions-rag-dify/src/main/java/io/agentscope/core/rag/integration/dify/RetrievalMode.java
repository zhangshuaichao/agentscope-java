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
package io.agentscope.core.rag.integration.dify;

/**
 * Dify 知识库搜索的检索模式。
 *
 * <p>不同模式使用不同策略查找相关文档：
 * <ul><li><b>KEYWORD_SEARCH</b>：传统关键词搜索，如 BM25、TF-IDF。</li>
 * <li><b>SEMANTIC_SEARCH</b>：基于向量的语义相似性搜索。</li>
 * <li><b>HYBRID_SEARCH</b>：结合关键词与语义搜索。</li>
 * <li><b>FULL_TEXT_SEARCH</b>：搜索文档的全部文本内容。</li></ul>
 *
 * <p>用法示例：
 * <pre>{@code
 * DifyRAGConfig config = DifyRAGConfig.builder()
 *     .retrievalMode(RetrievalMode.HYBRID_SEARCH)  // 使用混合搜索
 *     .build();
 * }</pre>
 */
public enum RetrievalMode {

    /**
     * 使用 BM25 等算法的关键词检索。
     *
     * <p>适合精确词项匹配、专有名词和技术术语。
     */
    KEYWORD_SEARCH("keyword_search"),

    /**
     * 使用向量嵌入与相似性搜索的语义检索。
     *
     * <p>适合概念查询、改写后的问题与语义理解。
     */
    SEMANTIC_SEARCH("semantic_search"),

    /**
     * 结合关键词与语义方法的混合检索。
     *
     * <p>结合两类方法的优势，合并并重排结果以改善相关性。
     *
     * <p>适合通用场景，兼顾精确率与召回率；实际效果仍取决于数据和服务端配置。
     */
    HYBRID_SEARCH("hybrid_search"),

    /**
     * 搜索文档全部文本内容。
     *
     * <p>适合复杂查询和大段文本内搜索。
     */
    FULL_TEXT_SEARCH("full_text_search");

    private final String value;

    /**
     * 创建 RetrievalMode 枚举常量。
     *
     * @param value Dify API 使用的字符串值
     */
    RetrievalMode(String value) {
        this.value = value;
    }

    /**
     * 获取用于 Dify API 请求的字符串值。
     *
     * @return API 值
     */
    public String getValue() {
        return value;
    }

    /**
     * 将字符串转换为对应 RetrievalMode 枚举。
     *
     * @param value 字符串值
     * @return 匹配的 RetrievalMode
     * @throws IllegalArgumentException 不匹配任何模式时抛出
     */
    public static RetrievalMode fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Retrieval mode value cannot be null");
        }

        for (RetrievalMode mode : values()) {
            if (mode.value.equalsIgnoreCase(value)) {
                return mode;
            }
        }

        throw new IllegalArgumentException(
                "Unknown retrieval mode: "
                        + value
                        + ". Valid values are: keyword_search, semantic_search, hybrid_search,"
                        + " full_text_search");
    }

    @Override
    public String toString() {
        return value;
    }
}
