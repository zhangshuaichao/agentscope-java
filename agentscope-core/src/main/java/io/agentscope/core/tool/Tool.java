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
 * 将方法标记为 AI 智能体可调用工具的注解。
 *
 * <p>带 {@code @Tool} 注解的方法会自动注册到工具集，供智能体执行。工具集通过反射发现工具方法，并生成供大语言模型使用的 JSON 模式。
 *
 * <p><b>使用示例：</b>
 *
 * <pre>{@code
 * public class WeatherTools {
 *     @Tool(name = "get_weather", description = "Get current weather for a city")
 *     public String getWeather(
 *         @ToolParam(name = "city", description = "City name") String city,
 *         @ToolParam(name = "unit", description = "Temperature unit") String unit) {
 *         // Implementation
 *         return "Weather data...";
 *     }
 * }
 * }</pre>
 *
 * <p><b>要求：</b>
 * <ul>
 * <li>所有参数必须带 {@link ToolParam} 注解（{@link ToolEmitter} 除外）</li>
 * <li>返回类型必须为 String、Mono&lt;String&gt; 或其他响应式类型</li>
 * <li>工具名称应遵循 snake_case 约定以兼容大语言模型</li>
 * <li>描述应明确说明工具作用和适用时机</li>
 * </ul>
 *
 * @see ToolParam
 * @see Toolkit
 * @see ToolEmitter
 */
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Tool {

    /**
     * 工具名称。
     *
     * <p>未提供时使用方法名。名称应遵循 snake_case 约定（例如 "get_weather"、"send_email"），以兼容不同大语言模型提供商。
     *
     * @return 工具名称；空字符串表示使用方法名
     */
    String name() default "";

    /**
     * 说明工具用途和使用方式的描述。
     *
     * <p>此描述发送给大语言模型，帮助其决定何时调用工具。应明确说明：
     * <ul>
     * <li>工具的作用</li>
     * <li>适用时机</li>
     * <li>返回结果的类型</li>
     * </ul>
     *
     * <p>未提供时，根据方法名自动生成通用描述。
     *
     * @return 工具描述；空字符串表示自动生成
     */
    String description() default "";

    /**
     * 是否为工具启用严格模式。
     *
     * <p>启用后，框架向生成模式中的所有对象类型递归注入 {@code additionalProperties: false}，使任意模型提供商的工具参数验证都能拒绝未声明参数，同时满足 OpenAI 严格模式（缺少该设置的模式会被以 400 Bad Request 拒绝）。兼容的提供商还会更严格地遵循工具参数的 JSON 模式。
     *
     * <p>若需要对 {@code additionalProperties} 进行更细粒度的逐节点控制，请通过 {@link SchemaOnlyTool} 或自定义 {@link AgentTool} 提供手写模式。
     *
     * @return true 表示启用严格模式
     */
    boolean strict() default false;

    /**
     * 是否延迟加载工具完整定义，直到模型需要它。
     *
     * <p>这是面向支持延迟加载的提供商（例如 OpenAI Responses 的 {@code tool_search} 服务端工具）的逐工具提示。模型仍能看到名称和描述，参数模式仅在工具搜索后加载。不支持延迟加载的提供商会忽略此标记。
     *
     * @return true 表示延迟加载模式，直到工具搜索加载它
     */
    boolean deferLoading() default false;

    /**
     * 工具是否只读取数据且没有可观察的副作用。
     *
     * <p>只读工具在 {@code PermissionMode.EXPLORE} 和 {@code ACCEPT_EDITS} 下自动获准，与 {@code ToolBase.isReadOnly()} 契约一致。
     *
     * @return 工具不修改状态时为 true
     */
    boolean readOnly() default false;

    /**
     * 工具是否可以安全地与自身并发调用。
     *
     * <p>为 false 时，框架会在并行批次中串行执行此工具的各次调用。默认为 {@code true}，符合 {@code @Tool} 方法通常为纯函数的形态。
     *
     * @return 多次调用无需协调即可并行运行时为 true
     */
    boolean concurrencySafe() default true;

    /**
     * 工具是否在框架外部执行。
     *
     * <p>外部工具不会通过 Toolkit 执行其 {@code callAsync} 方法体。智能体循环通过 {@code TOOL_SUSPENDED} 消息向调用方暴露此调用。
     *
     * @return 应将工具呈现为挂起调用时为 true
     */
    boolean externalTool() default false;

    /**
     * 工具是否需要在调用时注入智能体状态。
     *
     * <p>为 true 时，方法签名必须声明且只声明一个 {@code io.agentscope.core.state.AgentState} 参数；框架将其排除在 JSON 模式外，并在调用时绑定实时状态。
     *
     * @return 工具方法使用智能体状态时为 true
     */
    boolean stateInjected() default false;

    /**
     * 是否将工具结果直接作为本轮最终助手消息返回给调用方，跳过下一次模型推理。
     *
     * <p>此标记不属于工具模式，对模型不可见，因此不影响模型调用工具的决定，仅控制成功调用后的处理。
     *
     * <p>批处理中，仅当本轮<em>所有</em>已执行工具都设置 {@code returnDirect = true}，<em>且</em>全部结果均为 {@code SUCCESS} 时生效；否则所有结果反馈给模型。
     *
     * <p>无论成功结果来自框架执行，还是 {@code TOOL_SUSPENDED} 后恢复时由调用方提供（例如 {@code externalTool = true} 工具），都可直接作为本轮最终助手消息，替代收尾模型调用。边界情况：跨多次恢复才完成的批次，或混合外部结果与框架执行结果的批次，始终反馈给模型。
     *
     * <p>声明 {@code returnDirect = true} 的工具必须保证成功结果含可展示内容块，不能为空输出列表。最终答复的事件投影仅包含文本：图像等非文本块仍保留在消息级结果中，但不产生文本事件，所以纯文本消费者只能看到文本形式的最终答复。收尾消息会跳过零内容块结果；若整个批次没有输出，则显示防御性占位符 {@code "(no output)"}。
     *
     * @return true 表示执行后短路 ReAct 循环
     */
    boolean returnDirect() default false;

    /**
     * 此工具必须显式请求权限的敏感文件名。
     *
     * <p>空数组表示使用 {@code ToolBase} 维护的默认列表。
     *
     * @return 附加危险文件名；空值表示使用默认值
     */
    String[] dangerousFiles() default {};

    /**
     * 敏感目录名称；路径任意片段包含这些名称即标记为危险路径。
     *
     * <p>空数组表示使用 {@code ToolBase} 维护的默认列表。
     *
     * @return 附加危险目录名；空值表示使用默认值
     */
    String[] dangerousDirectories() default {};

    /**
     * 工具的自定义结果转换器。
     *
     * <p>将工具方法返回值转换为适合大语言模型使用的 {@link io.agentscope.core.message.ToolResultBlock} 实例。自定义转换器可用于：
     * <ul>
     * <li>过滤结果中的敏感数据</li>
     * <li>按指定方式格式化输出</li>
     * <li>添加结果元数据</li>
     * <li>压缩或概括大型输出</li>
     * </ul>
     *
     * <p><b>使用示例：</b>
     * <pre>{@code
     * @Tool(
     *     name = "get_data",
     *     converter = CustomJsonConverter.class
     * )
     * public MyData getData(String id) {
     *     return dataService.findById(id);
     * }
     * }</pre>
     *
     * <p>未指定时使用默认转换器 {@link DefaultToolResultConverter}，提供带模式信息的 JSON 序列化。
     *
     * <p><b>注意：</b>多步骤复杂处理应通过自定义转换器组合所需逻辑。
     *
     * @return 转换器类
     * @see ToolResultConverter
     * @see DefaultToolResultConverter
     */
    Class<? extends ToolResultConverter> converter() default DefaultToolResultConverter.class;
}
