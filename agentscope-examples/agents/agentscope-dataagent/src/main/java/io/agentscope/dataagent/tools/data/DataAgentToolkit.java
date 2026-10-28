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

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 面向智能体的数据分析基础工具包：列举配置数据源、描述表、预览 SQL 查询、渲染图表。
 * 无状态单例；通过 {@link DataSourceRegistry} 与 {@link ChartRenderer} 解析后端，
 * 运维无需修改此类即可替换真实实现。
 * <p>v1 提供接口位置，但仅 {@code list_data_sources} 真正实现；
 * {@code describe_table} 与 {@code run_sql_preview} 明确返回未实现说明，
 * 让智能体告知用户限制，而非编造查询结果。具体 JDBC / BigQuery / Hologres 连接器
 * 计划在后续的 {@code agentscope-extensions/} 中提供。
 * <p>启动时注册到主 {@code data-agent}；用户自定义智能体可在工作区 {@code tools.json} 中列出工具来启用。
 * <p>【学习提示】方法名包含 SQL 不代表当前示例已连接数据库。接入真实连接器时，
 * 还需落实数据源访问授权、只读限制、参数绑定及查询资源上限，不能只依赖模型提示词。
 */
public final class DataAgentToolkit {

    private final DataSourceRegistry registry;
    private final ChartRenderer chartRenderer;

    public DataAgentToolkit(DataSourceRegistry registry, ChartRenderer chartRenderer) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.chartRenderer = Objects.requireNonNull(chartRenderer, "chartRenderer");
    }

    @Tool(
            name = "list_data_sources",
            description =
                    """
                    List the data sources the admin has configured for this deployment. Each entry \
                    is reported as 'id | kind | label — description (tags)'. Call this before \
                    drafting any SQL so you pick a source the runtime actually knows about. \
                    Returns 'none' when no sources are configured.\
                    """)
    public String listDataSources() {
        List<DataSource> all = registry.list();
        if (all.isEmpty()) {
            return "none: no data sources are configured. Ask the operator to seed"
                    + " dataagent.data.sources in agentscope.json.";
        }
        StringBuilder sb = new StringBuilder();
        for (DataSource ds : all) {
            sb.append(ds.id()).append(" | ").append(ds.kind()).append(" | ").append(ds.label());
            if (ds.description() != null && !ds.description().isBlank()) {
                sb.append(" — ").append(ds.description());
            }
            if (!ds.tags().isEmpty()) {
                sb.append(" (").append(String.join(",", ds.tags())).append(")");
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    @Tool(
            name = "describe_table",
            description =
                    """
                    Return the column schema and a short row sample for a table in a configured \
                    data source. Use after list_data_sources to confirm the columns you intend \
                    to project / filter / group by. Returns 'not implemented' in v1 — the slot is \
                    reserved for a connector module.\
                    """)
    public String describeTable(
            @ToolParam(name = "source_id", description = "Data source id from list_data_sources")
                    String sourceId,
            @ToolParam(
                            name = "table",
                            description = "Fully-qualified table name as understood by the source")
                    String table) {
        Optional<DataSource> ds = registry.findById(sourceId);
        if (ds.isEmpty()) {
            return "error: unknown source_id '" + sourceId + "'";
        }
        if (table == null || table.isBlank()) {
            return "error: table must not be blank";
        }
        return "not implemented: describe_table requires a connector module (see DataAgent docs)";
    }

    @Tool(
            name = "run_sql_preview",
            description =
                    """
                    Execute a read-only SQL query against a configured data source and return the \
                    first N rows as a small markdown table. Only SELECT statements are accepted; \
                    the connector enforces a row cap. Returns 'not implemented' in v1 — the slot \
                    is reserved for a connector module.\
                    """)
    public String runSqlPreview(
            @ToolParam(name = "source_id", description = "Data source id from list_data_sources")
                    String sourceId,
            @ToolParam(name = "sql", description = "SELECT-only SQL statement") String sql,
            @ToolParam(
                            name = "row_limit",
                            description = "Max rows to return; the connector enforces a hard cap",
                            required = false)
                    Integer rowLimit) {
        Optional<DataSource> ds = registry.findById(sourceId);
        if (ds.isEmpty()) {
            return "error: unknown source_id '" + sourceId + "'";
        }
        if (sql == null || sql.isBlank()) {
            return "error: sql must not be blank";
        }
        String trimmed = sql.trim().toLowerCase();
        if (!trimmed.startsWith("select") && !trimmed.startsWith("with")) {
            return "error: only SELECT / WITH statements are allowed";
        }
        return "not implemented: run_sql_preview requires a connector module (see DataAgent docs)";
    }

    @Tool(
            name = "render_chart",
            description =
                    """
                    Render a chart from a Vega-Lite spec. chart_type is one of \
                    line | bar | area | scatter. The spec must include the data inline. Returns a \
                    short status string; the SPA renders the chart client-side from the same spec \
                    so no server-side image is produced in v1.\
                    """)
    public String renderChart(
            @ToolParam(name = "chart_type", description = "line | bar | area | scatter")
                    String chartType,
            @ToolParam(
                            name = "vega_lite_spec",
                            description = "Vega-Lite JSON spec including inline data")
                    String vegaLiteSpec) {
        return chartRenderer.render(chartType, vegaLiteSpec);
    }
}
