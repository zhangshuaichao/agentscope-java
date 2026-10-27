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

import com.github.victools.jsonschema.generator.FieldScope;
import com.github.victools.jsonschema.generator.MemberScope;
import com.github.victools.jsonschema.generator.Module;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigPart;
import java.util.stream.Stream;

/**
 * 读取 {@link ToolParam} 注解的 victools JSON Schema {@link Module}，向生成模式填充 {@code "description"} 和 {@code "required"}。
 *
 * <p>默认将无注解字段视为必填；传入 {@link Option#PROPERTY_REQUIRED_FALSE_BY_DEFAULT} 可反转此行为。
 *
 * @see ToolParam
 */
public class ToolSchemaModule implements Module {

    private final boolean requiredByDefault;

    /**
     * 使用给定选项创建模块。
     *
     * @param options 传入 {@link Option#PROPERTY_REQUIRED_FALSE_BY_DEFAULT}，将无注解字段视为可选
     */
    public ToolSchemaModule(Option... options) {
        this.requiredByDefault =
                Stream.of(options)
                        .noneMatch(option -> option == Option.PROPERTY_REQUIRED_FALSE_BY_DEFAULT);
    }

    @Override
    public void applyToConfigBuilder(SchemaGeneratorConfigBuilder builder) {
        this.applyToConfigBuilder(builder.forFields());
    }

    /**
     * 注册描述解析器和必填检查。
     */
    private void applyToConfigBuilder(SchemaGeneratorConfigPart<FieldScope> configPart) {
        configPart.withPropertyNameOverrideResolver(this::resolvePropertyName);
        configPart.withDescriptionResolver(this::resolveDescription);
        configPart.withRequiredCheck(this::checkRequired);
    }

    /**
     * 返回 {@code @ToolParam} 的名称；不存在时为 {@code null}。
     *
     * @param member 正在处理的字段或 getter
     * @return 属性名称，或 {@code null}
     */
    private String resolvePropertyName(FieldScope member) {
        ToolParam toolParam = member.getAnnotationConsideringFieldAndGetter(ToolParam.class);
        if (toolParam != null && hasText(toolParam.name())) {
            return toolParam.name();
        }
        return null;
    }

    /**
     * 返回 {@code @ToolParam} 的描述；不存在时为 {@code null}。
     *
     * @param member 正在处理的字段或 getter
     * @return 描述文本，或 {@code null}
     */
    private String resolveDescription(MemberScope<?, ?> member) {
        ToolParam toolParam = member.getAnnotationConsideringFieldAndGetter(ToolParam.class);
        if (toolParam != null && hasText(toolParam.description())) {
            return toolParam.description();
        }
        return null;
    }

    /**
     * 返回 {@code @ToolParam.required()}；缺失时回退到 {@link #requiredByDefault}。
     *
     * @param member 正在处理的字段或 getter
     * @return 字段应为必填时返回 {@code true}
     */
    private boolean checkRequired(MemberScope<?, ?> member) {
        ToolParam toolParam = member.getAnnotationConsideringFieldAndGetter(ToolParam.class);
        if (toolParam != null) {
            return toolParam.required();
        }
        return this.requiredByDefault;
    }

    /**
     * 字符串非 null 且非空白时返回 {@code true}。
     */
    private static boolean hasText(String str) {
        return str != null && !str.isBlank();
    }

    /**
     * 定制模块行为的选项。
     */
    public enum Option {

        /**
         * 将无注解字段视为可选，而不是必填。
         */
        PROPERTY_REQUIRED_FALSE_BY_DEFAULT
    }
}
