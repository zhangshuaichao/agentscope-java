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
package io.agentscope.examples.documentation2.copilotkit.a2ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 本示例向模型公布的 A2UI 组件目录。
 * <p>在 A2UI v0.9 基础目录上增加四个工作台组件；浏览器 {@code frontend/src/a2ui/workbenchCatalog.tsx} 通过 {@code createCatalog(...)} 实现相同目录 ID 与组件名称，因此这里生成的组件均有对应 React 渲染器。
 */
public final class WorkbenchCatalog {

    /**
     * 与浏览器目录共享的 ID，写入每个 {@code createSurface}。
     */
    public static final String CATALOG_ID = "agentscope.io:workbench";

    /**
     * 双方实现的 A2UI 线路协议版本。
     */
    public static final String VERSION = "v0.9";

    /**
     * A2UI 渲染器自带的基础组件。对应 v0.9 的 {@code basic_catalog.json}，仅公布仪表盘相关子集；完整目录还包含表单输入、媒体和覆盖层。
     */
    public static final Set<String> BASIC_COMPONENTS =
            Set.of("Text", "Card", "Column", "Row", "List", "Divider", "Button", "Image");

    /**
     * 仅由本示例实现的工作台组件。
     */
    public static final Set<String> CUSTOM_COMPONENTS =
            Set.of("MetricTile", "RiskGauge", "TimelineStep", "ApprovalCallout", "StatBadge");

    private WorkbenchCatalog() {}

    public static boolean isKnownComponent(String component) {
        return BASIC_COMPONENTS.contains(component) || CUSTOM_COMPONENTS.contains(component);
    }

    /**
     * 各自定义组件的语法行，使提示词可缩小到客户端支持的范围。
     */
    private static final Map<String, String> CUSTOM_COMPONENT_GRAMMAR =
            Map.of(
                    "MetricTile",
                    """
                    - MetricTile      { id, component:"MetricTile", label:string, value:string,
                                        delta?:string, trend?:"up"|"down"|"flat",
                                        accent?:"champagne"|"aurora"|"danger" }\
                    """,
                    "RiskGauge",
                    """
                    - RiskGauge       { id, component:"RiskGauge", label:string, score:number(0-100),
                                        threshold?:number, caption?:string }\
                    """,
                    "TimelineStep",
                    """
                    - TimelineStep    { id, component:"TimelineStep", index:number, title:string,
                                        state:"pending"|"in_progress"|"completed", note?:string }\
                    """,
                    "ApprovalCallout",
                    """
                    - ApprovalCallout { id, component:"ApprovalCallout", title:string, body:string,
                                        tone?:"info"|"warn"|"danger" }\
                    """,
                    "StatBadge",
                    """
                    - StatBadge       { id, component:"StatBadge", text:string,
                                        tone?:"neutral"|"success"|"warn"|"danger" }\
                    """);

    /**
     * 提供给 LLM 的紧凑语法。手写而非直接倾倒 40 KB JSON Schema 目录，以降低提示开销并改善中等规模模型的可靠性。
     * @param clientComponents 浏览器可渲染的自定义组件；为空时公布全部
     */
    public static String grammar(Set<String> clientComponents) {
        return BASE_GRAMMAR
                .formatted(CATALOG_ID, customComponentGrammar(clientComponents))
                .stripTrailing();
    }

    private static String customComponentGrammar(Set<String> clientComponents) {
        Set<String> advertised =
                clientComponents == null || clientComponents.isEmpty()
                        ? CUSTOM_COMPONENTS
                        : clientComponents;
        return CUSTOM_COMPONENT_GRAMMAR.entrySet().stream()
                .filter(entry -> advertised.contains(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .collect(Collectors.joining("\n"));
    }

    private static final String BASE_GRAMMAR =
            """
            ## A2UI v0.9 输出格式

            输出一个 JSON 数组，数组元素是 A2UI server-to-client 消息，按顺序执行：

            1. {"version":"v0.9","createSurface":{"surfaceId":"<id>","catalogId":"%s"}}
            2. {"version":"v0.9","updateDataModel":{"surfaceId":"<id>","path":"/","value":{...}}}
            3. {"version":"v0.9","updateComponents":{"surfaceId":"<id>","components":[...]}}

            规则：
            - surfaceId 必须三条消息保持一致，且使用调用方给定的 surfaceId。
            - components 是扁平数组，其中必须恰好有一个 id 为 "root" 的组件作为根节点。
            - 每个组件形如 {"id":"<唯一id>","component":"<组件名>", ...属性}；父子关系通过 id 字符串引用。
            - 属性表里没有列出的字段一律不要写，多余字段会导致整个界面校验失败。
            - 字符串/数字属性可写字面量，也可写 {"path":"/字段名"} 绑定 updateDataModel 写入的数据。
            - 只能使用下面列出的组件。

            ## 基础组件（A2UI 基础 catalog）

            - Text    { id, component:"Text", text:string,
                        variant?:"h1"|"h2"|"h3"|"h4"|"h5"|"caption"|"body" }
            - Card    { id, component:"Card", child:组件id }            // 只能包一个子节点
            - Column  { id, component:"Column", children:[组件id...],
                        justify?:"start"|"center"|"end"|"spaceBetween", align?:"start"|"center"|"end"|"stretch" }
            - Row     { id, component:"Row", children:[组件id...],
                        justify?:"start"|"center"|"end"|"spaceBetween", align?:"start"|"center"|"end"|"stretch" }
            - List    { id, component:"List", children:[组件id...], direction?:"vertical"|"horizontal" }
            - Divider { id, component:"Divider", axis?:"horizontal"|"vertical" }
            - Button  { id, component:"Button", child:组件id,           // 文案要用一个 Text 子节点
                        action:{"event":{"name":"<动作名>"}}, variant?:"default"|"primary"|"borderless" }

            ## 工作台自定义组件（本示例专属，暗色 + 香槟金视觉；以下为当前浏览器声明可渲染的组件）

            %s

            ## 排版建议

            root 用 Column 组织；关键指标横向排 Row + 多个 MetricTile；计划步骤纵向排 Column + TimelineStep；
            结论用 ApprovalCallout。标题用 Text + variant:"h3"，说明文字用 variant:"caption"。

            ## 输出约定

            只输出被 <a2ui-json> 与 </a2ui-json> 包裹的 JSON 数组，不要输出任何解释文字。
            """;

    /**
     * A2UI v0.9 内联目录形式的自定义组件部分。以 {@code inlineCatalogs} 传给官方 SDK，使其验证前与内置基础目录合并。
     * @param clientComponents 浏览器可渲染的自定义组件；为空时包含全部
     */
    public static Map<String, Object> inlineCatalogSchema(Set<String> clientComponents) {
        Map<String, Object> components = new LinkedHashMap<>();
        components.put(
                "MetricTile",
                component(
                        "MetricTile",
                        Map.of(
                                "label", stringProp("指标名称"),
                                "value", stringProp("指标数值，已格式化"),
                                "delta", stringProp("环比变化，例如 +12.4%"),
                                "trend", enumProp("up", "down", "flat"),
                                "accent", enumProp("champagne", "aurora", "danger")),
                        List.of("component", "label", "value")));
        components.put(
                "RiskGauge",
                component(
                        "RiskGauge",
                        Map.of(
                                "label", stringProp("风险维度名称"),
                                "score", numberProp("0-100 的风险分"),
                                "threshold", numberProp("告警阈值"),
                                "caption", stringProp("补充说明")),
                        List.of("component", "label", "score")));
        components.put(
                "TimelineStep",
                component(
                        "TimelineStep",
                        Map.of(
                                "index", numberProp("步骤序号，从 1 开始"),
                                "title", stringProp("步骤标题"),
                                "state", enumProp("pending", "in_progress", "completed"),
                                "note", stringProp("步骤备注")),
                        List.of("component", "index", "title", "state")));
        components.put(
                "ApprovalCallout",
                component(
                        "ApprovalCallout",
                        Map.of(
                                "title", stringProp("提示标题"),
                                "body", stringProp("提示正文"),
                                "tone", enumProp("info", "warn", "danger")),
                        List.of("component", "title", "body")));
        components.put(
                "StatBadge",
                component(
                        "StatBadge",
                        Map.of(
                                "text", stringProp("徽标文案"),
                                "tone", enumProp("neutral", "success", "warn", "danger")),
                        List.of("component", "text")));

        if (clientComponents != null && !clientComponents.isEmpty()) {
            components.keySet().retainAll(clientComponents);
        }

        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("catalogId", CATALOG_ID);
        catalog.put("components", components);
        return catalog;
    }

    private static Map<String, Object> component(
            String name, Map<String, Object> properties, List<String> required) {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("component", Map.of("const", name));
        merged.putAll(properties);

        Map<String, Object> specific = new LinkedHashMap<>();
        specific.put("properties", merged);
        specific.put("required", required);

        return Map.of(
                "allOf",
                List.of(Map.of("$ref", "common_types.json#/$defs/ComponentCommon"), specific));
    }

    private static Map<String, Object> stringProp(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> numberProp(String description) {
        return Map.of("type", "number", "description", description);
    }

    private static Map<String, Object> enumProp(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }
}
