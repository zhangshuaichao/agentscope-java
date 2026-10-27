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

import java.util.ArrayList;
import java.util.List;

/**
 * Dify 知识库检索的元数据过滤配置。
 *
 * <p>按文档元数据字段过滤，可用逻辑运算符 AND/OR 组合多个条件。
 *
 * <p>用法示例：
 * <pre>{@code
 * MetadataFilter filter = MetadataFilter.builder()
 *     .logicalOperator("and")
 *     .addCondition(MetadataFilterCondition.builder()
 *         .name("category")
 *         .comparisonOperator("equals")
 *         .value("technical")
 *         .build())
 *     .addCondition(MetadataFilterCondition.builder()
 *         .name("language")
 *         .comparisonOperator("equals")
 *         .value("en")
 *         .build())
 *     .build();
 * }</pre>
 */
public class MetadataFilter {

    private final String logicalOperator;
    private final List<MetadataFilterCondition> conditions;

    private MetadataFilter(Builder builder) {
        this.logicalOperator = builder.logicalOperator != null ? builder.logicalOperator : "and";
        this.conditions =
                builder.conditions != null
                        ? new ArrayList<>(builder.conditions)
                        : new ArrayList<>();
    }

    /**
     * 获取组合条件的逻辑运算符。
     *
     * @return "and" 或 "or"
     */
    public String getLogicalOperator() {
        return logicalOperator;
    }

    /**
     * 获取过滤条件列表。
     *
     * @return 条件列表的副本
     */
    public List<MetadataFilterCondition> getConditions() {
        return new ArrayList<>(conditions);
    }

    /**
     * 创建 MetadataFilter 构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** MetadataFilter 的构建器。 */
    public static class Builder {
        private String logicalOperator;
        private List<MetadataFilterCondition> conditions;

        private Builder() {}

        /**
         * 设置组合条件的逻辑运算符。
         *
         * <p>默认为 "and"。
         *
         * @param logicalOperator "and" 或 "or"
         * @return 当前构建器，支持链式调用
         */
        public Builder logicalOperator(String logicalOperator) {
            this.logicalOperator = logicalOperator;
            return this;
        }

        /**
         * 设置过滤条件列表。
         *
         * @param conditions 条件列表
         * @return 当前构建器，支持链式调用
         */
        public Builder conditions(List<MetadataFilterCondition> conditions) {
            this.conditions = conditions != null ? new ArrayList<>(conditions) : null;
            return this;
        }

        /**
         * 添加单个过滤条件。
         *
         * @param condition 要添加的条件
         * @return 当前构建器，支持链式调用
         */
        public Builder addCondition(MetadataFilterCondition condition) {
            if (this.conditions == null) {
                this.conditions = new ArrayList<>();
            }
            this.conditions.add(condition);
            return this;
        }

        /**
         * 构建 MetadataFilter 实例。
         *
         * @return 新的 MetadataFilter 实例
         */
        public MetadataFilter build() {
            return new MetadataFilter(this);
        }
    }

    @Override
    public String toString() {
        return "MetadataFilter{"
                + "logicalOperator='"
                + logicalOperator
                + '\''
                + ", conditions="
                + conditions
                + '}';
    }
}
