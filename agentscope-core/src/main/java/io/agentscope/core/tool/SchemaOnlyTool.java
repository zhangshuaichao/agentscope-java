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
package io.agentscope.core.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ToolSchema;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * 仅包含模式定义、不包含执行逻辑的外部工具实现。
 *
 * <p>用于注册在框架外部执行的工具。模型返回 SchemaOnlyTool 调用时，Toolkit 将其呈现为待处理的 {@link ToolResultBlock}，随后智能体向用户返回挂起消息。
 *
 * <p>在 Toolkit 执行路径之外直接调用 {@link #callAsync(ToolCallParam)} 时，仍会抛出 {@link ToolSuspendException}。
 *
 * <p>使用示例：
 * <pre>{@code
 * ToolSchema schema = ToolSchema.builder()
 *     .name("query_database")
 *     .description("Query external database")
 *     .parameters(Map.of(
 *         "type", "object",
 *         "properties", Map.of("sql", Map.of("type", "string")),
 *         "required", List.of("sql")
 *     ))
 *     .build();
 *
 * toolkit.registerSchema(schema);
 * // Or: toolkit.registerAgentTool(new SchemaOnlyTool(schema));
 * }</pre>
 *
 * @see ToolSchema
 * @see Toolkit#registerSchema(ToolSchema)
 * @see Toolkit#isExternalTool(String)
 */
public class SchemaOnlyTool extends ToolBase {

    private final Boolean strict;
    private final Boolean deferLoading;

    /**
     * 从 ToolSchema 创建 SchemaOnlyTool。
     *
     * @param schema 包含名称、描述、参数、严格模式和延迟加载配置的工具模式
     * @throws NullPointerException 当 schema 为 null 时
     */
    public SchemaOnlyTool(ToolSchema schema) {
        this(
                Objects.requireNonNull(schema, "schema cannot be null").getName(),
                schema.getDescription(),
                schema.getParameters(),
                schema.getStrict(),
                schema.getDeferLoading());
    }

    /**
     * 使用指定名称、描述和参数创建 SchemaOnlyTool。
     *
     * <p>严格模式设为 null（未指定）。
     *
     * @param name 工具名称
     * @param description 工具描述
     * @param parameters 工具参数模式
     * @throws NullPointerException 当 name 或 description 为 null 时
     */
    public SchemaOnlyTool(String name, String description, Map<String, Object> parameters) {
        this(name, description, parameters, null);
    }

    /**
     * 使用指定名称、描述、参数和严格模式配置创建 SchemaOnlyTool。
     *
     * <p>对参数映射进行防御性复制，防止外部修改影响工具内部状态。
     *
     * @param name 工具名称
     * @param description 工具描述
     * @param parameters 工具参数模式
     * @param strict 是否启用严格模式验证（未指定时为 null）
     * @param deferLoading 是否将模式加载推迟到工具搜索时（未指定时为 null）
     * @throws NullPointerException 当 name 或 description 为 null 时
     */
    public SchemaOnlyTool(
            String name,
            String description,
            Map<String, Object> parameters,
            Boolean strict,
            Boolean deferLoading) {
        super(
                ToolBase.builder()
                        .name(Objects.requireNonNull(name, "name cannot be null"))
                        .description(
                                Objects.requireNonNull(description, "description cannot be null"))
                        .inputSchema(
                                parameters != null
                                        ? Collections.unmodifiableMap(new HashMap<>(parameters))
                                        : Collections.emptyMap())
                        .externalTool(true)
                        .readOnly(false)
                        .concurrencySafe(true));
        this.strict = strict;
        this.deferLoading = deferLoading;
    }

    /**
     * 使用指定名称、描述、参数和严格模式配置创建 SchemaOnlyTool。
     *
     * <p>延迟加载设为 null（未指定）。
     *
     * @param name 工具名称
     * @param description 工具描述
     * @param parameters 工具参数模式
     * @param strict 是否启用严格模式验证（未指定时为 null）
     * @throws NullPointerException 当 name 或 description 为 null 时
     */
    public SchemaOnlyTool(
            String name, String description, Map<String, Object> parameters, Boolean strict) {
        this(name, description, parameters, strict, null);
    }

    @Override
    public Boolean getStrict() {
        return strict;
    }

    @Override
    public Boolean getDeferLoading() {
        return deferLoading;
    }

    /**
     * 抛出 ToolSuspendException，表示工具需要外部执行。
     *
     * <p>Toolkit 在调用此方法前即对外部工具短路处理。直接调用仍保留同样的挂起信号。
     *
     * @param param 工具调用参数（忽略）
     * @return 从不正常返回
     * @throws ToolSuspendException 始终抛出，表示需要外部执行
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.error(new ToolSuspendException());
    }
}
