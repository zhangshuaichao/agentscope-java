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
 * Dify 知识库检索的元数据过滤条件。
 *
 * <p>每个条件指定对文档元数据字段的过滤规则。
 *
 * <p>用法示例：
 * <pre>{@code
 * MetadataFilterCondition condition = MetadataFilterCondition.builder()
 *     .name("category")
 *     .comparisonOperator("equals")
 *     .value("technical")
 *     .build();
 * }</pre>
 */
public class MetadataFilterCondition {

    private final String name;
    private final String comparisonOperator;
    private final String value;

    private MetadataFilterCondition(Builder builder) {
        this.name = builder.name;
        this.comparisonOperator = builder.comparisonOperator;
        this.value = builder.value;
    }

    /**
     * 获取元数据字段名称。
     *
     * @return 字段名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取比较运算符。
     *
     * @return 比较运算符，例如 "equals"、"contains"、"not_equals"
     */
    public String getComparisonOperator() {
        return comparisonOperator;
    }

    /**
     * 获取过滤值。
     *
     * @return 用于比较的值
     */
    public String getValue() {
        return value;
    }

    /**
     * 创建 MetadataFilterCondition 构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** MetadataFilterCondition 的构建器。 */
    public static class Builder {
        private String name;
        private String comparisonOperator;
        private String value;

        private Builder() {}

        /**
         * 设置元数据字段名称。
         *
         * @param name 字段名称
         * @return 当前构建器，支持链式调用
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 设置比较运算符。
         *
         * <p>常见运算符：
         * <ul><li>equals：完全匹配。</li><li>not_equals：不相等。</li><li>contains：子串匹配。</li>
         * <li>not_contains：不包含。</li><li>starts_with：前缀匹配。</li><li>ends_with：后缀匹配。</li></ul>
         *
         * @param comparisonOperator 比较运算符
         * @return 当前构建器，支持链式调用
         */
        public Builder comparisonOperator(String comparisonOperator) {
            this.comparisonOperator = comparisonOperator;
            return this;
        }

        /**
         * 设置过滤值。
         *
         * @param value 用于比较的值
         * @return 当前构建器，支持链式调用
         */
        public Builder value(String value) {
            this.value = value;
            return this;
        }

        /**
         * 构建 MetadataFilterCondition 实例。
         *
         * @return 新的 MetadataFilterCondition 实例
         */
        public MetadataFilterCondition build() {
            return new MetadataFilterCondition(this);
        }
    }

    @Override
    public String toString() {
        return "MetadataFilterCondition{"
                + "name='"
                + name
                + '\''
                + ", comparisonOperator='"
                + comparisonOperator
                + '\''
                + ", value='"
                + value
                + '\''
                + '}';
    }
}
