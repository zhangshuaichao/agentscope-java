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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.model.AguiContext;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.TaskContextState;
import io.agentscope.core.util.JsonUtils;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 运行仍在流式输出时，将智能体进度转为共享状态帧。
 * <ul><li>初始化：将客户端 {@code state} 与 {@code context} 合入线程 {@link WorkbenchState}，实现双向共享状态。
 * <li>任务镜像：每次工具结果后，将 {@code todo_write} 维护的 {@code AgentState.getTasksContext()} 列表差异同步到共享状态，让计划板实时更新。
 * <li>权限审计：记录 {@link RequireUserConfirmEvent} 和 {@link UserConfirmResultEvent}，展示询问到决定的完整轨迹；中断本身由框架生命周期转换器发出。
 * </ul>
 * <p>排队的 {@link CustomEvent} 插入上游事件附近：开场快照紧跟 {@link AgentStartEvent}，位于 {@code RUN_STARTED} 之后；在 {@link AgentEndEvent} 前关闭开放步骤并刷新队列，确保每个 {@code STEP_STARTED} 匹配 {@code STEP_FINISHED}，且无事件晚于 {@code RUN_FINISHED}。
 */
public class WorkbenchEventMiddleware implements MiddlewareBase {

    /**
     * 浏览器通过 {@code useAgentContext} 公布 A2UI 目录时使用的 {@code description}。
     */
    public static final String CONTEXT_INLINE_CATALOG = "a2ui-inline-catalog";

    private static final Logger logger = LoggerFactory.getLogger(WorkbenchEventMiddleware.class);

    private final WorkbenchStateRegistry registry;

    public WorkbenchEventMiddleware(WorkbenchStateRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        WorkbenchState workbench = registry.forContext(ctx);
        seedFromClient(workbench, ctx);

        return next.apply(input)
                .concatMap(
                        event -> {
                            if (event instanceof AgentStartEvent) {
                                workbench.clearOpenSteps();
                                workbench.queueSnapshot();
                                return Flux.concat(Flux.just(event), drain(workbench));
                            }
                            observe(agent, ctx, workbench, event);
                            if (event instanceof AgentEndEvent) {
                                // AG-UI：每个 STEP_STARTED 都必须在 RUN_FINISHED 前闭合。
                                workbench.closeOpenSteps();
                                return Flux.concat(drain(workbench), Flux.just(event));
                            }
                            return Flux.concat(Flux.just(event), drain(workbench));
                        });
    }

    private static Flux<AgentEvent> drain(WorkbenchState workbench) {
        List<CustomEvent> queued = workbench.drainPendingEvents();
        return queued.isEmpty() ? Flux.empty() : Flux.fromIterable(queued).cast(AgentEvent.class);
    }

    private void observe(
            Agent agent, RuntimeContext ctx, WorkbenchState workbench, AgentEvent event) {
        if (event instanceof ToolResultEndEvent) {
            syncTasks(agent, ctx, workbench);
        }
    }

    /**
     * 将内置任务列表镜像到共享状态，参见 {@link WorkbenchState#syncTasks}。会话状态查询属于 {@link ReActAgent} 而非 {@link Agent} 接口，因此不支持该能力的智能体跳过计划板。
     */
    private void syncTasks(Agent agent, RuntimeContext ctx, WorkbenchState workbench) {
        if (!(agent instanceof ReActAgent reActAgent)) {
            return;
        }
        try {
            TaskContextState tasks = reActAgent.getAgentState(ctx).getTasksContext();
            if (tasks != null) {
                workbench.syncTasks(tasks.getTasks());
            }
        } catch (Exception e) {
            logger.debug("跳过任务同步：{}", e.toString());
        }
    }

    /**
     * 将客户端 {@code state} 与 {@code context} 合入线程工作台；两者来自 AG-UI 适配器存入 {@link RuntimeContext} 的 {@link RunAgentInput}。
     */
    private void seedFromClient(WorkbenchState workbench, RuntimeContext ctx) {
        RunAgentInput runInput = runInput(ctx);
        if (runInput == null) {
            return;
        }
        workbench.mergeFromClient(runInput.getState());

        for (AguiContext entry : runInput.getContext()) {
            if (entry == null || !CONTEXT_INLINE_CATALOG.equals(entry.getDescription())) {
                continue;
            }
            workbench.setClientComponents(parseClientComponents(entry.getValue()));
        }
    }

    private static RunAgentInput runInput(RuntimeContext ctx) {
        return ctx == null ? null : ctx.get(RunAgentInput.class);
    }

    /**
     * 读取浏览器报告的 {@code {"catalogId": "...", "components": ["MetricTile", ...]}}。
     * @return 声明的组件名称；载荷不存在或不可用时为空集合
     */
    private static Set<String> parseClientComponents(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        try {
            Map<?, ?> payload = JsonUtils.getJsonCodec().fromJson(value, Map.class);
            if (!(payload.get("components") instanceof List<?> components)) {
                return Set.of();
            }
            Set<String> names = new LinkedHashSet<>();
            for (Object component : components) {
                if (component instanceof String name && !name.isBlank()) {
                    names.add(name);
                }
            }
            return names;
        } catch (Exception e) {
            logger.warn("浏览器上报的 A2UI 组件清单解析失败：{}", e.toString());
            return Set.of();
        }
    }

    private static String describe(ToolUseBlock toolCall) {
        if (toolCall == null) {
            return "";
        }
        Map<String, Object> args = toolCall.getInput();
        if (args == null || args.isEmpty()) {
            return toolCall.getName();
        }
        try {
            return JsonUtils.getJsonCodec().toJson(args);
        } catch (Exception e) {
            return String.valueOf(args);
        }
    }
}
