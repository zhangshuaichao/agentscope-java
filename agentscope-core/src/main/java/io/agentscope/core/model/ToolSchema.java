/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 不可变的工具模式定义。
 * 使用 JSON Schema 描述工具参数及其接口。
 *
 * <p>【学习提示】模式是“参数应长什么样”的说明，不是某次工具调用的实际参数，也不会执行工具。
 */
public class ToolSchema {
    private final String name;
    private final String description;
    private final Map<String, Object> parameters;
    private final Map<String, Object> outputSchema;
    private final Boolean strict;
    private final Boolean deferLoading;

    /**
     * 通过建造者模式创建新的 ToolSchema 实例。
     *
     * @param builder 包含模式配置的建造者
     */
    private ToolSchema(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name is required");
        this.description = Objects.requireNonNull(builder.description, "description is required");
        this.parameters =
                builder.parameters != null
                        ? Collections.unmodifiableMap(new HashMap<>(builder.parameters))
                        : Collections.emptyMap();
        this.outputSchema =
                builder.outputSchema != null
                        ? Collections.unmodifiableMap(new HashMap<>(builder.outputSchema))
                        : null;
        this.strict = builder.strict;
        this.deferLoading = builder.deferLoading;
    }

    /**
     * 获取工具名称。
     *
     * @return 工具名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取工具描述。
     *
     * @return 工具描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 获取以 JSON Schema 表示的工具参数。
     *
     * @return 包含参数模式的不可修改映射
     */
    public Map<String, Object> getParameters() {
        return parameters;
    }

    /**
     * 获取以 JSON Schema 表示的可选工具输出模式。
     *
     * @return 包含输出模式的不可修改映射；未指定时为 null
     */
    public Map<String, Object> getOutputSchema() {
        return outputSchema;
    }

    /**
     * 获取模式验证的严格模式标记。
     *
     * @return 启用严格模式时为 true，否则为 false；未指定时为 null
     */
    public Boolean getStrict() {
        return strict;
    }

    /**
     * 获取工具定义的延迟加载标记。
     *
     * <p>支持工具搜索的服务商可以延迟加载此工具的参数模式，直到模型搜索它。
     * 未指定时返回 {@code null}，此时工具保持立即加载。
     *
     * @return 延迟加载时为 true，否则为 false；未指定时为 null
     */
    public Boolean getDeferLoading() {
        return deferLoading;
    }

    /**
     * 创建新的 ToolSchema 建造者。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** 创建 ToolSchema 实例的建造者。 */
    public static class Builder {
        private String name;
        private String description;
        private Map<String, Object> parameters;
        private Map<String, Object> outputSchema;
        private Boolean strict;
        private Boolean deferLoading;

        /**
         * 设置工具名称。
         *
         * @param name 工具名称
         * @return 当前建造者实例
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 设置工具描述。
         *
         * @param description 工具描述
         * @return 当前建造者实例
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * 设置以 JSON Schema 表示的工具参数。
         *
         * @param parameters 参数模式
         * @return 当前建造者实例
         */
        public Builder parameters(Map<String, Object> parameters) {
            this.parameters = parameters;
            return this;
        }

        /**
         * 设置以 JSON Schema 表示的可选工具输出模式。
         *
         * @param outputSchema 输出模式
         * @return 当前建造者实例
         */
        public Builder outputSchema(Map<String, Object> outputSchema) {
            this.outputSchema = outputSchema;
            return this;
        }

        /**
         * 设置模式验证的严格模式。
         *
         * @param strict 是否启用严格模式
         * @return 当前建造者实例
         */
        public Builder strict(Boolean strict) {
            this.strict = strict;
            return this;
        }

        /**
         * 设置工具定义的延迟加载标记。
         *
         * @param deferLoading 是否将此工具模式的加载延迟至工具搜索时
         * @return 当前建造者实例
         */
        public Builder deferLoading(Boolean deferLoading) {
            this.deferLoading = deferLoading;
            return this;
        }

        /**
         * 使用已设置的值构建新的 ToolSchema 实例。
         *
         * @return 新的 ToolSchema 实例
         */
        public ToolSchema build() {
            return new ToolSchema(this);
        }
    }
}
