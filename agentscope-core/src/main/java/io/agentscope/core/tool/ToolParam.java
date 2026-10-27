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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 描述工具方法参数的注解。
 *
 * <p>带 {@link Tool} 注解的方法，其参数都需要此注解（自动注入的 {@link ToolEmitter} 除外）。提供生成 JSON 模式的元数据，向大语言模型描述工具参数。
 *
 * <p><b>使用示例：</b>
 *
 * <pre>{@code
 * @Tool(name = "calculate_area", description = "Calculate rectangle area")
 * public double calculateArea(
 *     @ToolParam(name = "width", description = "Width in meters", required = true)
 *     double width,
 *     @ToolParam(name = "height", description = "Height in meters", required = true)
 *     double height,
 *     @ToolParam(name = "unit", description = "Unit of measurement", required = false)
 *     String unit
 * ) {
 *     // 实现逻辑
 * }
 * }</pre>
 *
 * <p><b>重要说明：</b>
 * <ul>
 * <li>{@code name} 属性<b>必填</b>，因为 Java 默认不在运行时保留参数名</li>
 * <li>参数名应遵循 snake_case 约定以兼容模型</li>
 * <li>描述帮助模型理解应提供的值</li>
 * <li>{@link ToolEmitter} 参数由框架注入，无需此注解</li>
 * </ul>
 *
 * @see Tool
 * @see ToolEmitter
 */
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolParam {

    /**
     * 工具参数名称。
     *
     * <p><b>此属性必填</b>，因为 Java 默认不在运行时保留参数名（除非编译时使用 {@code -parameters}，但不能依赖该设置）。工具集用此名称将模型参数映射到方法参数。
     *
     * <p>应遵循 snake_case 约定（例如 "file_path"、"max_results"），以兼容不同模型提供商。
     *
     * @return 工具模式中应出现的参数名
     */
    String name();

    /**
     * 此参数是否必填。
     *
     * <p>模型调用工具时必须提供必填参数。可选参数可以省略，此时对象参数接收 null，基本类型接收默认值。
     *
     * @return true 表示必填（默认），false 表示可选
     */
    boolean required() default true;

    /**
     * 参数描述。
     *
     * <p>作为工具模式的一部分发送给模型，帮助它理解：
     * <ul>
     * <li>参数代表什么</li>
     * <li>预期格式或取值</li>
     * <li>约束与验证规则</li>
     * </ul>
     *
     * <p>良好描述有助于模型提供正确参数。
     *
     * @return 参数描述；未提供时为空字符串
     */
    String description() default "";
}
