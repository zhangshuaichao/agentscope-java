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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.SubagentSpecGenerator;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskDelivery;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import io.agentscope.harness.agent.tool.AgentGenerateTool;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import io.agentscope.harness.agent.tool.TaskTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 提供托管子智能体机制的中间件。
 *
 * <p><strong>默认模式</strong>（独立 Harness 配置）下，中间件创建
 * {@link AgentSpawnTool}，由 {@link DefaultAgentManager} 支撑。
 * <strong>会话模式</strong>（经 {@code AgentBootstrap} 编排）下，注入外部工具
 * （通常为 {@code SessionsTool}），替代默认 {@link AgentSpawnTool}。
 *
 * <p>职责：
 * <ol>
 *   <li>暴露子智能体工具与 {@link TaskTool}；调用方通过 {@link #getTools()}
 *       获取，并在编排阶段注册到工具集。
 *   <li>每次 {@link #onAgent} 时，从按命名空间隔离的工作区文件系统重新加载子智能体声明，
 *       支持按用户隔离子智能体。
 *   <li>将稳定的子智能体使用指导放入每个 {@link ReasoningInput} 的首条 SYSTEM 消息，
 *       把易变状态（可用智能体目录、异步任务摘要）作为合成尾部消息追加。
 *       框架每轮从冻结基线重建 SYSTEM 消息，因此内容不会逐轮累积；
 *       将易变部分移出头部，还能保持系统字节稳定，利于前缀缓存。
 * </ol>
 *
 * <p>【学习提示】声明、工厂、实例与后台任务是不同对象：声明描述能力，工厂负责创建，
 * 实例执行任务，任务记录跟踪结果。不要仅因目录里出现一个名称，就推断它已经在运行。
 */
public class SubagentsMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(SubagentsMiddleware.class);

    private static final DateTimeFormatter ISO_SHORT =
            DateTimeFormatter.ofPattern("HH:mm'Z'").withZone(ZoneOffset.UTC);

    private static final int MAX_TASK_SUMMARY_ENTRIES = 10;

    /**
     * 聚合到一条 {@code <system-reminder>} 投递消息中的单项任务结果上限。
     * 超过部分会以“还有 N 项，请调用 task_list()”的尾注提示，LLM 仍可逐项显式获取。
     */
    static final int MAX_DELIVERIES_PER_REMINDER = 10;

    // @formatter:off
    private static final String SUBAGENT_SECTION_TEMPLATE =
            """

            ## Subagents

            You have access to subagent tools for spawning and coordinating isolated subagents.
            Subagents are ephemeral — they live only for the duration of the task and return a single result.

            ### Agent Tools

            **`%s`** — Spawn an isolated subagent
            - `agent_id` (required): which subagent to instantiate
            - `task` (optional): initial prompt; omit to create a persistent session
            - `label` (optional): human-readable name for referencing via send
            - `timeout_seconds`: wait time; 0=fire-and-forget (returns task_id), default=30, max=600
            - Response always includes `agent_key:` (opaque handle) — save it for follow-up sends

            **`%s`** — Send a follow-up message to an existing subagent
            - `agent_key`: copy the **full value** after `agent_key:` from spawn output (starts with `agent:`). This is NOT `agent_id`, NOT `session_id`, and NOT `task_id`
            - Or use `label` if you set one at spawn (mutually exclusive with agent_key)
            - `message` (required): content to send
            - `timeout_seconds`: 0=fire-and-forget, >0=wait for reply (default: 30)

            **`%s`** — List active subagents

            ### Task Tools (for async/background operations)

            **`task_output`** — Retrieve the result of a background task by task_id.
            - **You rarely need this.** Completed tasks are pushed back to you automatically as a `<system-reminder>` block before your next reasoning step.
            - Use `task_output(block=false)` when you need a specific task's latest status/result, the pushed summary was truncated, or you intentionally want to check progress while continuing other reasoning.
            - Use `task_output(block=true)` only for one specific task you are ready to wait for; for multiple tasks use `wait_async_results`.

            **`wait_async_results`** — Wait for background-task results when the next step depends on them.
            - Prefer `wait_async_results(task_ids=...)`: waits until those tasks are terminal and **returns their results in the tool output**.
            - Prefer `wait_async_results(wait_all=true)`: waits for the snapshot of currently non-terminal background tasks (tasks started later are not added) and **returns their results**.
            - Without `task_ids` and without `wait_all`: legacy **inbox-any** mode — returns when ANY inbox message arrives. This is NOT wait-all; use `task_ids` / `wait_all=true` when every task in a group must finish.

            **`task_cancel`** — Cancel a running background task by task_id. No effect on already-completed tasks.

            **`task_list`** — List all in-flight background tasks (durable, accurate across compaction and migration). Completed tasks fall off this list after they're pushed to you.

            ### Background task flow
            1. Spawn with `timeout_seconds=0` to fire-and-forget; the response gives you a task_id.
            2. Continue with independent work. If you need fresh state, use `task_output(block=false)` for selected tasks.
            3. If a later step must wait for a known group, call `wait_async_results(task_ids=...)`; if it must wait for every current background task, call `wait_async_results(wait_all=true)`.
            4. If the agent has nothing useful to do, hand control back to the user — they'll prompt again when ready and the next reasoning round will surface any completions.

            ### Timeout promotion
            When a sync spawn/send exceeds its timeout, the task is **not lost** — it is automatically promoted to a background task. You receive `status: timeout_promoted` with a `task_id`. Treat it like any async task: the result will be pushed back to you automatically as a `<system-reminder>`. Do NOT retry the same task — it is already running in the background.

            ### When to use subagents
            - When a task is complex and multi-step, and can be fully delegated in isolation
            - When a task is independent of other tasks and can run in parallel
            - When a task requires focused reasoning or heavy context usage that would bloat the main thread
            - When sandboxing improves reliability (e.g. code analysis, structured searches, data formatting)
            - When you only care about the output, not the intermediate steps (e.g. research → synthesized report)

            ### When NOT to use subagents
            - If the task is trivial (a few tool calls or simple lookup)
            - If you need to see intermediate reasoning or steps after completion
            - If delegating does not reduce token usage, complexity, or context switching
            - If splitting would add latency without benefit

            ### Subagent lifecycle
            1. **Spawn** → Provide clear role, instructions, and expected output format
            2. **Run** → The subagent completes the task autonomously
            3. **Return** → The subagent provides a single structured result
            4. **Reconcile** → Incorporate or synthesize the result into the main thread

            ### Usage patterns
            - **Parallel async execution**: Split work by independence/dependency. Launch independent, non-conflicting tasks with `timeout_seconds=0`; continue reasoning, then use `task_output(block=false)` for selective progress checks or `wait_async_results(task_ids=...)` / `wait_async_results(wait_all=true)` when a barrier is required
            - **Parallel sync delegation**: Multiple sync `agent_spawn` / `agent_send` calls in one turn run concurrently by default (Toolkit parallel=true); the parent waits for that batch of tool results before continuing. Pass a Toolkit with parallel=false to opt out.
            - **Mixed short/long work**: Wait for short prerequisite tasks first, continue reasoning with those results, and merge long-running async results later through `task_output` or `wait_async_results`
            - **Sync delegation**: Use default timeout for simple one-shot delegation when one result is needed before the next reasoning step
            - **Persistent session**: Spawn without a task, then use send for multi-turn interaction
            - **Cancel stale work**: Use task_cancel to stop background tasks that are no longer needed
            - Subagent results are NOT visible to the user — always summarize them in your response
            """;
    // @formatter:on

    private final List<SubagentEntry> baseEntries;
    private volatile Object subagentTool;
    private final TaskTool taskTool;
    private final TaskRepository taskRepository;
    private final boolean isSessionMode;

    private final AbstractFilesystem filesystem;
    private final Path mainWorkspace;
    private final Function<SubagentDeclaration, SubagentFactory> factoryBuilder;
    private final DefaultAgentManager agentManager;
    private final WorkspaceManager workspaceManager;

    private record SubagentSnapshot(
            List<SubagentEntry> entries, DefaultAgentManager agentManager) {}

    /**
     * 可选的 {@link AgentGenerateTool}，用于让 LLM 生成子智能体规格。
     * 采用延迟初始化，因为规格生成器需要 {@link io.agentscope.core.model.Model} 实例，
     * 由调用方通过 {@link #enableAgentGenerateTool} 提供；未启用时为 null（开箱默认）。
     */
    private volatile AgentGenerateTool agentGenerateTool;

    /** 默认模式：在内部创建 {@link AgentSpawnTool} 与 {@link DefaultAgentManager}。 */
    public SubagentsMiddleware(
            List<SubagentEntry> entries,
            TaskRepository taskRepository,
            WorkspaceManager workspaceManager,
            AbstractFilesystem filesystem,
            Path mainWorkspace,
            Function<SubagentDeclaration, SubagentFactory> factoryBuilder) {
        this.baseEntries = List.copyOf(entries);
        this.isSessionMode = false;
        DefaultAgentManager dam = new DefaultAgentManager(entries, workspaceManager);
        this.agentManager = dam;
        this.workspaceManager = workspaceManager;
        java.util.Objects.requireNonNull(taskRepository, "taskRepository");
        this.taskRepository = taskRepository;
        this.subagentTool = new AgentSpawnTool(dam, taskRepository, 0);
        this.taskTool = new TaskTool(taskRepository);
        this.filesystem = filesystem;
        this.mainWorkspace = mainWorkspace;
        this.factoryBuilder = factoryBuilder;
    }

    /** 不支持动态重载的默认模式。 */
    public SubagentsMiddleware(
            List<SubagentEntry> entries,
            TaskRepository taskRepository,
            WorkspaceManager workspaceManager) {
        this(entries, taskRepository, workspaceManager, null, null, null);
    }

    /** AgentStateStore 模式：使用外部提供的工具（通常为 {@code SessionsTool}）。 */
    public SubagentsMiddleware(
            List<SubagentEntry> entries,
            Object externalSubagentTool,
            TaskRepository taskRepository) {
        this.baseEntries = List.copyOf(entries);
        this.isSessionMode = true;
        this.agentManager = null;
        this.workspaceManager = null;
        this.subagentTool = externalSubagentTool;
        java.util.Objects.requireNonNull(taskRepository, "taskRepository");
        this.taskRepository = taskRepository;
        this.taskTool = new TaskTool(taskRepository);
        this.filesystem = null;
        this.mainWorkspace = null;
        this.factoryBuilder = null;
    }

    /** 仅声明所需扩展点；子类若重写更多钩子，必须扩展此集合。 */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_AGENT, ExtensionPoint.ON_REASONING);
    }

    /**
     * 启用 {@code agent_generate} 工具，让 LLM 根据描述编写新的子智能体规格。
     * 默认关闭，因为生成器需要 {@link io.agentscope.core.model.Model} 并会写入工作区，
     * 必须由调用方显式启用。
     *
     * <p>仅在此中间件拥有 {@link DefaultAgentManager} 的默认（非会话）模式生效。
     * 会话模式下管理器来自外部，此调用不执行任何操作。
     *
     * @param generator 由用于编写规格的模型支撑的 {@link SubagentSpecGenerator}
     * @return 当前中间件，便于链式调用
     */
    public SubagentsMiddleware enableAgentGenerateTool(SubagentSpecGenerator generator) {
        if (isSessionMode || agentManager == null) {
            log.debug("enableAgentGenerateTool ignored in session mode (no internal manager)");
            return this;
        }
        this.agentGenerateTool = new AgentGenerateTool(generator, agentManager, filesystem);
        return this;
    }

    /** 返回此中间件使用的 {@link TaskRepository}。 */
    public TaskRepository getTaskRepository() {
        return taskRepository;
    }

    /**
     * 接入 {@link io.agentscope.harness.agent.bus.MessageBus}，在后台任务完成时
     * 向会话收件箱推送结果并将唤醒信号入队，使
     * {@link io.agentscope.harness.agent.gateway.WakeupDispatcher} 能在子智能体工作结束后重新触发空闲会话。
     *
     * <p>向底层仓库注册 {@link TaskRepository.TaskCompletionCallback}。
     * 可安全重复调用，每次替换之前的回调。
     *
     * @param messageBus 应用消息总线
     * @param agentId 父智能体 ID（用于唤醒路由）
     * @return 当前中间件，便于链式调用
     */
    public SubagentsMiddleware wireMessageBus(
            io.agentscope.harness.agent.bus.MessageBus messageBus, String agentId) {
        if (messageBus == null) {
            return this;
        }
        taskRepository.setCompletionCallback(
                (rc, taskId, subAgentId, sessionId, result) -> {
                    String userId = rc != null ? rc.getUserId() : null;
                    String hintContent =
                            String.format(
                                    "<system-reminder>Background subagent task '%s'"
                                            + " (agent=%s) has completed.\n\nResult:\n\n%s"
                                            + "</system-reminder>",
                                    taskId, subAgentId, result != null ? result : "(no output)");
                    String hintId = java.util.UUID.randomUUID().toString().replace("-", "");
                    java.util.Map<String, Object> hintPayload =
                            java.util.Map.of(
                                    "type",
                                    "hint",
                                    "id",
                                    hintId,
                                    "hint",
                                    hintContent,
                                    "source",
                                    "subagent_task");
                    messageBus.inboxPush(sessionId, hintPayload).subscribe();
                    messageBus
                            .enqueueWakeup(
                                    userId != null ? userId : "",
                                    sessionId,
                                    agentId != null ? agentId : "")
                            .subscribe(
                                    unused -> {},
                                    err ->
                                            log.warn(
                                                    "Failed to enqueue wakeup after task {}"
                                                            + " completion: {}",
                                                    taskId,
                                                    err.getMessage()));
                    log.info(
                            "Subagent task {} completed, pushed to inbox and enqueued wakeup:"
                                    + " session={}",
                            taskId,
                            sessionId);
                });
        return this;
    }

    /**
     * 将网关桥接器接入内部 {@link AgentSpawnTool}，使新建子智能体可暴露为用户可寻址的线程。
     * 仅在默认（非会话）模式生效。
     *
     * @param bridge 桥接器实现，通常由
     *     {@link io.agentscope.harness.agent.gateway.GatewayBootstrap#gatewayBridge()} 获取
     * @return 当前中间件，便于链式调用
     */
    public SubagentsMiddleware setGatewayBridge(
            io.agentscope.harness.agent.gateway.SubagentGatewayBridge bridge) {
        if (isSessionMode || agentManager == null) {
            log.debug("setGatewayBridge ignored in session mode (no internal manager)");
            return this;
        }
        // 修改活动工具上的桥接器，而不是替换工具：编排时工具集将
        // agent_spawn 绑定到 getTools() 返回的 AgentSpawnTool 实例，
        // 因此在此创建的新实例永远不会被调用，线程暴露也就会
        // 静默失效。
        if (this.subagentTool instanceof AgentSpawnTool ast) {
            ast.setGatewayBridge(bridge);
        } else {
            this.subagentTool = new AgentSpawnTool(agentManager, taskRepository, 0, bridge);
        }
        return this;
    }

    /**
     * 返回可重新物化子智能体的内部 {@link DefaultAgentManager}；
     * 会话模式（外部工具）下为 {@code null}。
     * 用于连接网关物化器，实现跨节点恢复已暴露的子智能体。
     */
    public DefaultAgentManager getAgentManager() {
        return agentManager;
    }

    /**
     * 返回此中间件向智能体工具集贡献的工具实例。
     * 调用方负责在编排阶段将它们注册到工具集。
     *
     * <p>调用过 {@link #enableAgentGenerateTool} 后，返回列表还会包含 {@link AgentGenerateTool}。
     */
    public List<Object> getTools() {
        if (baseEntries.isEmpty()) {
            return List.of();
        }
        AgentGenerateTool gen = this.agentGenerateTool;
        if (gen != null) {
            return List.of(subagentTool, taskTool, gen);
        }
        return List.of(subagentTool, taskTool);
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (ctx != null) {
            installSnapshot(ctx, loadSubagentSnapshot(ctx));
        }
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        List<SubagentEntry> currentEntries = snapshotFor(rc).entries();
        String sessionId = rc != null ? rc.getSessionId() : null;

        // ---- 阶段 B-3 推送投递 -------------------------------------------------------
        // 先取出刚进入终态的任务，使随后构造的 SYSTEM 摘要可以省略它们。
        // 仅当智能体为 ReActAgent 时才写入 AgentState；其他 Agent 类型
        // 保持旧版仅拉取的流程不变。
        List<TaskDelivery> pending = this.taskRepository.findPendingDeliveries(rc, sessionId);
        Msg deliveryMsg = null;
        if (!pending.isEmpty()) {
            deliveryMsg = buildDeliveryReminder(pending);
        }
        if (deliveryMsg != null && agent instanceof ReActAgent reAct) {
            try {
                RuntimeContext.resolveAgentState(rc, reAct).contextMutable().add(deliveryMsg);
            } catch (RuntimeException e) {
                log.warn(
                        "Failed to append task delivery reminder to AgentState; "
                                + "will inject for this round only: {}",
                        e.getMessage());
            }
        }

        List<Msg> rebuilt =
                prependToSystemMessage(
                        input.messages(), renderSubagentSection(currentEntries, isSessionMode));
        Msg stateMsg =
                buildSubagentStateMsg(currentEntries, buildTaskSummary(this.taskRepository, rc));
        if (stateMsg != null) {
            rebuilt = new ArrayList<>(rebuilt);
            rebuilt.add(stateMsg);
        }
        if (deliveryMsg != null) {
            // 为本轮注入消息，与 AgentState 写入并行；无论框架何时在下一轮
            // 从状态重新派生消息，都确保当前紧接着的 LLM 调用
            // 能看到此消息。
            rebuilt = new ArrayList<>(rebuilt);
            rebuilt.add(deliveryMsg);
        }

        Flux<AgentEvent> downstream =
                next.apply(new ReasoningInput(rebuilt, input.tools(), input.options()));
        if (!pending.isEmpty()) {
            // 仅在内层推理成功完成后标记已投递。顺序非常重要：
            // 若先标记再推理，调用中途崩溃可能已持久化 deliveredAt，
            // 但 AgentState 写入尚未落盘，导致永久丢消息。反过来，
            // 推理完成后、markDelivered 前崩溃，只会导致下一轮
            // 重复投递，虽有冗余但更安全。
            final TaskRepository repoRef = this.taskRepository;
            final RuntimeContext rcRef = rc;
            final String sidRef = sessionId;
            downstream =
                    downstream.doOnComplete(
                            () -> {
                                for (TaskDelivery d :
                                        pending.subList(
                                                0,
                                                Math.min(
                                                        pending.size(),
                                                        MAX_DELIVERIES_PER_REMINDER))) {
                                    try {
                                        repoRef.markDelivered(rcRef, sidRef, d.taskId());
                                    } catch (RuntimeException e) {
                                        log.warn(
                                                "Failed to mark task {} as delivered; "
                                                        + "may re-push next round: {}",
                                                d.taskId(),
                                                e.getMessage());
                                    }
                                }
                            });
        }
        return downstream;
    }

    /**
     * 构建单条聚合 {@code <system-reminder>} 消息，向 LLM 展示刚进入终态的后台任务。
     * 最多包含 {@link #MAX_DELIVERIES_PER_REMINDER} 项；其余项以尾注提示，
     * 让 LLM 知道需要完整集合时可调用 {@code task_list()}。
     *
     * <p>角色／元数据与 {@code TaskReminderMiddleware} 一致，
     * 让下游 UI 和持久化层识别其为系统生成的合成内容，而非用户输入。
     */
    static Msg buildDeliveryReminder(List<TaskDelivery> deliveries) {
        int total = deliveries.size();
        int shown = Math.min(total, MAX_DELIVERIES_PER_REMINDER);
        StringBuilder sb = new StringBuilder();
        sb.append("<system-reminder>\n");
        sb.append(total).append(" background subagent task");
        sb.append(total == 1 ? " has" : "s have");
        sb.append(" completed since your last turn. ");
        sb.append("These results are now part of your conversation history.\n\n");
        for (int i = 0; i < shown; i++) {
            TaskDelivery d = deliveries.get(i);
            String state = stateLiteral(d.status());
            sb.append("<task id=\"").append(d.taskId()).append("\" state=\"").append(state);
            sb.append("\"");
            if (d.agentId() != null) {
                sb.append(" agent=\"").append(d.agentId()).append("\"");
            }
            sb.append(">\n");
            switch (d.status()) {
                case COMPLETED -> {
                    sb.append("<task_result>\n");
                    sb.append(d.result() != null ? d.result() : "");
                    sb.append("\n</task_result>\n");
                }
                case FAILED -> {
                    sb.append("<task_error>\n");
                    sb.append(d.errorMessage() != null ? d.errorMessage() : "(no error message)");
                    sb.append("\n</task_error>\n");
                }
                case CANCELLED -> sb.append("Task was cancelled before producing a result.\n");
                default ->
                        sb.append("Task ended in non-terminal state ")
                                .append(d.status())
                                .append('\n');
            }
            sb.append("</task>\n");
        }
        if (total > shown) {
            sb.append("\n... and ")
                    .append(total - shown)
                    .append(" more — call task_list() to inspect.\n");
        }
        sb.append(
                "\nIf you need a single task's full output, call"
                        + " task_output(task_id=..., block=false).\n");
        sb.append("</system-reminder>");
        return Msg.builder()
                .role(MsgRole.USER)
                .name("system")
                .content(TextBlock.builder().text(sb.toString()).build())
                .metadata(
                        Map.of(
                                Msg.METADATA_SYNTHETIC,
                                true,
                                Msg.METADATA_REMINDER_KIND,
                                "task_delivery"))
                .build();
    }

    private static String stateLiteral(TaskStatus status) {
        return switch (status) {
            case COMPLETED -> "completed";
            case FAILED -> "error";
            case CANCELLED -> "cancelled";
            default -> status.name().toLowerCase();
        };
    }

    /**
     * 将指定附加内容追加到 {@code messages} 的首条 SYSTEM 消息。
     * 不存在 SYSTEM 消息时，在下标 0 插入一条。
     */
    static List<Msg> prependToSystemMessage(List<Msg> messages, String extra) {
        if (extra == null || extra.isEmpty()) {
            return messages != null ? messages : List.of();
        }
        List<Msg> out = new ArrayList<>(messages != null ? messages.size() : 1);
        if (messages == null || messages.isEmpty() || messages.get(0).getRole() != MsgRole.SYSTEM) {
            out.add(
                    Msg.builder()
                            .name("system")
                            .role(MsgRole.SYSTEM)
                            .content(TextBlock.builder().text(extra).build())
                            .build());
            if (messages != null) {
                out.addAll(messages);
            }
            return out;
        }
        Msg sys = messages.get(0);
        String existing = sys.getTextContent() != null ? sys.getTextContent() : "";
        String merged = existing.isEmpty() ? extra : existing + "\n" + extra;
        Msg newSys =
                Msg.builder()
                        .id(sys.getId())
                        .name(sys.getName())
                        .role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text(merged).build())
                        .metadata(sys.getMetadata())
                        .timestamp(sys.getTimestamp())
                        .build();
        out.add(newSys);
        out.addAll(messages.subList(1, messages.size()));
        return out;
    }

    private SubagentSnapshot snapshotFor(RuntimeContext runtimeContext) {
        SubagentSnapshot existing = runtimeContext.get(SubagentSnapshot.class);
        if (existing != null) {
            return existing;
        }
        SubagentSnapshot snapshot = loadSubagentSnapshot(runtimeContext);
        installSnapshot(runtimeContext, snapshot);
        return snapshot;
    }

    private void installSnapshot(RuntimeContext runtimeContext, SubagentSnapshot snapshot) {
        runtimeContext.put(SubagentSnapshot.class, snapshot);
        runtimeContext.put(AgentSpawnTool.CTX_AGENT_MANAGER, snapshot.agentManager());
    }

    private SubagentSnapshot loadSubagentSnapshot(RuntimeContext runtimeContext) {
        if (filesystem == null || factoryBuilder == null || isSessionMode) {
            return new SubagentSnapshot(baseEntries, agentManager);
        }
        try {
            List<SubagentDeclaration> decls =
                    AgentSpecLoader.loadFromFilesystem(filesystem, runtimeContext, mainWorkspace);

            List<SubagentEntry> newEntries = new ArrayList<>(baseEntries);
            for (SubagentDeclaration decl : decls) {
                boolean alreadyExists =
                        baseEntries.stream().anyMatch(e -> e.name().equals(decl.getName()));
                if (!alreadyExists) {
                    newEntries.add(
                            new SubagentEntry(
                                    decl.getName(),
                                    decl.getDescription(),
                                    factoryBuilder.apply(decl),
                                    decl));
                }
            }
            List<SubagentEntry> snapshotEntries = List.copyOf(newEntries);
            return new SubagentSnapshot(
                    snapshotEntries, new DefaultAgentManager(snapshotEntries, workspaceManager));
        } catch (Exception e) {
            log.warn("Failed to reload subagent entries from filesystem: {}", e.getMessage());
            return new SubagentSnapshot(baseEntries, agentManager);
        }
    }

    /**
     * 为给定条目渲染 {@code ## Subagents} 系统提示段落，与 {@link DynamicSubagentsMiddleware} 共用。
     * 只包含按配置稳定的指导；易变的智能体目录与异步任务摘要放入尾部状态消息
     * （见 {@link #buildSubagentStateMsg}），保持系统头部在各步骤间字节稳定。
     */
    public static String renderSubagentSection(List<SubagentEntry> entries, boolean isSessionMode) {
        String spawnName = isSessionMode ? "sessions_spawn" : "agent_spawn";
        String sendName = isSessionMode ? "sessions_send" : "agent_send";
        String listName = isSessionMode ? "sessions_list" : "agent_list";
        return String.format(SUBAGENT_SECTION_TEMPLATE, spawnName, sendName, listName);
    }

    /**
     * 渲染可见智能体目录行；没有可见条目时返回空字符串。
     *
     * <p>过滤声明中设置 {@code hidden=true} 或 {@code mode=PRIMARY} 的条目：
     * LLM 不应看到仅供内部使用的隐藏子智能体，也不能创建仅限主智能体的声明。
     * {@link io.agentscope.harness.agent.subagent.DefaultAgentManager} 本来就会拒绝这种创建，
     * 展示它们只会造成误用。没有声明的程序化注册项
     * （{@code entry.declaration() == null}）始终可见。
     */
    private static String renderSubagentCatalog(List<SubagentEntry> entries) {
        return entries.stream()
                .filter(SubagentsMiddleware::isVisibleToLlm)
                .map(e -> String.format("- `%s`: %s", e.name(), e.description()))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 构建携带易变子智能体状态的合成尾部消息，包括可用智能体目录与异步任务摘要。
     * 两部分都为空时返回 {@code null}。
     *
     * <p>角色和元数据与 {@link #buildDeliveryReminder} 一致：Harness 布局将该消息放在对话之后，
     * 与 {@code runtime_materials} 和 {@code
     * plan_state} 同区；压缩时不会把这些合成消息加入持久化摘要。
     */
    static Msg buildSubagentStateMsg(List<SubagentEntry> entries, String taskSummary) {
        String catalog = renderSubagentCatalog(entries);
        boolean hasTasks = taskSummary != null && !taskSummary.isBlank();
        if (catalog.isEmpty() && !hasTasks) {
            return null;
        }
        StringBuilder sb = new StringBuilder("<RUNTIME_STATE>\n");
        if (!catalog.isEmpty()) {
            sb.append("### Available agent ids\n").append(catalog).append('\n');
        }
        if (hasTasks) {
            sb.append(taskSummary.strip()).append('\n');
        }
        sb.append("</RUNTIME_STATE>");
        return Msg.builder()
                .role(MsgRole.USER)
                .name("system")
                .content(TextBlock.builder().text(sb.toString()).build())
                .metadata(
                        Map.of(
                                Msg.METADATA_SYNTHETIC,
                                true,
                                Msg.METADATA_REMINDER_KIND,
                                "subagent_state"))
                .build();
    }

    /**
     * 条目是否应出现在面向 LLM 的可用子智能体列表中。
     * 没有声明的程序化条目默认可见。
     */
    private static boolean isVisibleToLlm(SubagentEntry e) {
        SubagentDeclaration decl = e.declaration();
        if (decl == null) return true;
        if (decl.isHidden()) return false;
        return decl.getMode() != SubagentDeclaration.Mode.PRIMARY;
    }

    /**
     * 构建当前会话在途任务的简要摘要；没有时为 {@code null}。
     * 与 {@link DynamicSubagentsMiddleware} 共用。
     *
     * <p>阶段 B-3：过滤已投递任务，因为它们已作为 {@code <system-reminder>} 块存在于对话历史。
     * 再次提及会浪费词元，把真正仍在运行的任务挤出 {@link #MAX_TASK_SUMMARY_ENTRIES} 上限，
     * 还会诱使 LLM 再次获取结果，怀疑已推送的信息。
     */
    public static String buildTaskSummary(TaskRepository repo, RuntimeContext ctx) {
        if (repo == null) {
            return null;
        }
        String sessionId = ctx != null ? ctx.getSessionId() : null;
        Collection<BackgroundTask> tasks = repo.listTasks(ctx, sessionId, null);
        if (tasks.isEmpty()) {
            return null;
        }

        List<BackgroundTask> visible = new ArrayList<>();
        for (BackgroundTask task : tasks) {
            if (repo.isDelivered(ctx, sessionId, task.getTaskId())) continue;
            visible.add(task);
        }
        if (visible.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder("\n### Async tasks (current session)\n");
        int count = 0;
        for (BackgroundTask task : visible) {
            if (count >= MAX_TASK_SUMMARY_ENTRIES) {
                sb.append("- ... (")
                        .append(visible.size() - MAX_TASK_SUMMARY_ENTRIES)
                        .append(" more — use task_list() to see all)\n");
                break;
            }
            sb.append("- task_id: ").append(task.getTaskId());
            if (task.getAgentId() != null) {
                sb.append("  agent: ").append(task.getAgentId());
            }
            sb.append("  status: ").append(task.getTaskStatus().name().toLowerCase());
            sb.append("  started: ").append(ISO_SHORT.format(task.getCreatedAt()));
            sb.append('\n');
            count++;
        }
        sb.append(
                "(Completed/failed/cancelled tasks are pushed back to you as <system-reminder>"
                        + " blocks, not listed here — no need to call task_output unless you need"
                        + " the full payload.)\n");
        return sb.toString();
    }
}
