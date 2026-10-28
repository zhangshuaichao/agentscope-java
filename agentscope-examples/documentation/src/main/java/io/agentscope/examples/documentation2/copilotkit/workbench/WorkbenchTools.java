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
package io.agentscope.examples.documentation2.copilotkit.workbench;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.examples.documentation2.copilotkit.a2ui.A2uiComposer;
import io.agentscope.examples.documentation2.copilotkit.a2ui.A2uiSurface;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 工作台工具集，各工具展示不同能力，并与 {@code AgentConfiguration} 权限规则对应。
 * <ul><li>{@code query_service_metrics}：只读，自动允许。
 * <li>{@code update_workbench_brief}：写共享状态，以 STATE_DELTA 发给浏览器。
 * <li>{@code deploy_release}：ASK，暂停为 AG-UI 中断。
 * <li>{@code purge_production_data}：DENY，不会进入此类执行。
 * <li>{@code render_dashboard}：通过 A2UI 生成界面。
 * </ul>
 * <p>框架注入的 {@code AgentState} 以 AG-UI 线程 ID 为会话 ID，工具据此找到 {@link WorkbenchState}。
 */
public class WorkbenchTools {

    private final WorkbenchStateRegistry registry;
    private final A2uiComposer composer;

    public WorkbenchTools(WorkbenchStateRegistry registry, A2uiComposer composer) {
        this.registry = registry;
        this.composer = composer;
    }

    /**
     * 读取模拟服务指标。
     * @param service 待检查服务
     * @param window 时间窗口，如 {@code 1h} 或 {@code 24h}
     * @param state 注入的智能体状态
     * @return 可读指标摘要
     */
    @Tool(
            name = "query_service_metrics",
            description = "查询指定服务的运行指标（QPS、错误率、P99 延迟）。只读操作，可直接调用。",
            readOnly = true,
            stateInjected = true)
    public String queryServiceMetrics(
            @ToolParam(name = "service", description = "服务名，例如 order-api") String service,
            @ToolParam(name = "window", description = "时间窗口，例如 1h / 24h") String window,
            AgentState state) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("QPS", random.nextInt(800, 4200));
        metrics.put("错误率", "%.2f%%".formatted(random.nextDouble(0.01, 1.8)));
        metrics.put("P99 延迟", random.nextInt(80, 520) + "ms");
        metrics.put("实例数", random.nextInt(3, 24));

        WorkbenchState workbench = registry.forState(state);
        workbench.recordMetrics(metrics);
        return "%s 在 %s 窗口的指标：%s".formatted(service, window, metrics);
    }

    /**
     * 更新浏览器显示的共享简报。
     * @param topic 可选新主题
     * @param priority 可选优先级（低／中／高）
     * @param status 可选状态文本
     * @param state 注入状态
     * @return 合并确认
     */
    @Tool(
            name = "update_workbench_brief",
            description = "更新工作台共享状态中的主题、优先级或状态文案，前端会实时收到 STATE_DELTA。",
            stateInjected = true,
            concurrencySafe = false)
    public String updateWorkbenchBrief(
            @ToolParam(name = "topic", description = "新的演示主题，可留空") String topic,
            @ToolParam(name = "priority", description = "优先级：低 / 中 / 高，可留空") String priority,
            @ToolParam(name = "status", description = "新的状态文案，可留空") String status,
            AgentState state) {
        return registry.forState(state).updateBrief(topic, priority, status);
    }

    /**
     * 记录当前计划目标。
     * @param goal 计划目标
     * @param state 注入状态
     * @return 确认信息
     */
    @Tool(
            name = "set_plan_goal",
            description = "在使用 todo_write 制定计划前，先声明本次计划的总目标，前端计划面板会展示它。",
            stateInjected = true,
            concurrencySafe = false)
    public String setPlanGoal(
            @ToolParam(name = "goal", description = "计划的总目标") String goal, AgentState state) {
        registry.forState(state).setPlanGoal(goal);
        return "计划目标已记录：" + goal;
    }

    /**
     * 模拟生产发布，受 {@code ASK} 权限规则控制。
     * @param service 目标服务
     * @param version 发布版本
     * @param state 注入状态
     * @return 发布结果
     */
    @Tool(
            name = "deploy_release",
            description = "把指定版本发布到生产环境。高风险操作，执行前需要用户在前端确认。",
            stateInjected = true,
            concurrencySafe = false)
    public String deployRelease(
            @ToolParam(name = "service", description = "服务名") String service,
            @ToolParam(name = "version", description = "版本号，例如 v2.4.1") String version,
            AgentState state) {
        String detail = "%s → %s".formatted(service, version);
        return "已发布 %s 到生产环境，灰度 10%% 观察中。".formatted(detail);
    }

    /**
     * 受 {@code DENY} 规则保护的破坏性操作，用于展示硬阻止。
     * @param dataset 数据集名称
     * @return 正常演示路径不会返回
     */
    @Tool(
            name = "purge_production_data",
            description = "永久删除生产数据集。该工具被 DENY 规则拦截，任何情况下都不会执行。",
            concurrencySafe = false)
    public String purgeProductionData(
            @ToolParam(name = "dataset", description = "数据集名称") String dataset) {
        return "不应该执行到这里：" + dataset;
    }

    /**
     * 生成 A2UI 界面，以 {@code a2ui-surface} 活动推送到浏览器。
     * @param intent 界面应展示的内容
     * @param state 注入状态
     * @return 提供给模型的简短摘要
     */
    @Tool(
            name = "render_dashboard",
            description = "根据自然语言意图动态生成一块 A2UI 界面并推送到前端渲染。适合展示指标看板、计划进度、" + "风险评估等结构化信息。",
            stateInjected = true,
            concurrencySafe = false)
    public String renderDashboard(
            @ToolParam(name = "intent", description = "希望界面展示什么内容") String intent,
            AgentState state) {
        WorkbenchState workbench = registry.forState(state);
        A2uiSurface surface =
                composer.compose(intent, workbench.snapshot(), workbench.getClientComponents());
        workbench.recordA2uiSurface(
                surface.surfaceId(),
                surface.catalogId(),
                surface.intent(),
                surface.generatedBy(),
                surface.componentCount(),
                surface.operations());
        return "已生成 A2UI 界面 %s（生成器 %s，%d 个组件，schema 来源 %s），界面已渲染在对话中。"
                .formatted(
                        surface.surfaceId(),
                        surface.generatedBy(),
                        surface.componentCount(),
                        composer.schemaSourceName());
    }
}
