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
package io.agentscope.dataagent.tools.data;

/** 根据小型表格结果渲染内嵌图表的 SPI。v1 附带占位实现；具体渲染器（XChart 服务端 PNG、供 SPA 使用的 ECharts JSON、内嵌 Markdown 的 Mermaid）应放在 {@code agentscope-extensions/} 中。 */
public interface ChartRenderer {

    /**
     * 按类似 Vega-Lite 的规范渲染图表。返回可由智能体纳入回答的简短状态字符串，
     * 通常是 Markdown 图片链接、内嵌 Base64 图片或 {@code "ok: rendered to <url>"} 指针。
     * @param chartType "line"、"bar"、"area"、"scatter" 之一
     * @param vegaLiteSpec 兼容 Vega-Lite 的 JSON 规范，包含内联数据
     */
    String render(String chartType, String vegaLiteSpec);
}
