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
package io.agentscope.harness.agent.tool;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.EventSource;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agent.SubagentEventBus;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.SessionIdUtils;
import io.agentscope.harness.agent.gateway.SubagentGatewayBridge;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.RemoteAskPolicy;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.protocol.RemoteConfirmDecision;
import io.agentscope.harness.agent.subagent.protocol.RemoteEventCodec;
import io.agentscope.harness.agent.subagent.protocol.RemotePendingConfirm;
import io.agentscope.harness.agent.subagent.protocol.RemoteStreamDetail;
import io.agentscope.harness.agent.subagent.task.AgentProtocolTransport;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.RemoteSubagentTransport;
import io.agentscope.harness.agent.subagent.task.RemoteSubmitContext;
import io.agentscope.harness.agent.subagent.task.RemoteTarget;
import io.agentscope.harness.agent.subagent.task.RemoteTaskStatus;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * 供智能体内部使用的简单子智能体工具，比 {@code SessionsTool} 轻量得多：
 * <ul>
 *   <li>{@code agent_spawn}：启动子智能体、执行任务并返回结果（同步或异步）
 *   <li>{@code agent_send}：向先前启动的子智能体发送后续消息
 *   <li>{@code agent_list}：列出活动子智能体
 * </ul>
 *
 * <p>没有会话管理、执行通道、运行注册表或通知分派。只负责“创建智能体、调用、返回结果”。
 * 仅使用 {@link DefaultAgentManager} 创建和调用智能体。
 *
 * <p>异步任务（{@code timeout_seconds=0}）提交到 {@link TaskRepository}，
 * 作用域由 {@link RuntimeContext} 中的当前会话 ID 确定。这使工作区存储中的任务状态
 * 可用于跨节点读取和压缩后的恢复。
 *
 * <h2>流式处理</h2>
 *
 * <p>{@code agent_spawn} 和 {@code agent_send} 返回 {@link Mono}{@code <ToolResultBlock>}，
 * 使框架的响应式工具调用管线（参见 {@code ToolMethodInvoker}）可在父级流式链中订阅它们。
 * 当 Reactor Context 中存在 {@link SubagentEventBus}（由 {@code AgentBase.createEventStream}
 * 注入）时，每个子级 {@link io.agentscope.core.agent.Event} 都实时转发到父级 sink，
 * 向消费者提供覆盖整个调用层级的扁平事件流。没有总线时（普通 {@code call()} 模式），
 * 执行回退到非流式 {@code invokeAgent} 路径，不产生额外开销。
 */
public class AgentSpawnTool {

    private static final Logger log = LoggerFactory.getLogger(AgentSpawnTool.class);

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 600;
    private static final int MAX_SPAWN_DEPTH = 3;

    /**
     * {@link RuntimeContext} 字符串键，用于单次调用覆盖子智能体的用户可见性。
     * 在该键下放入 {@link Boolean}（或其字符串形式），即可控制当前调用中
     * 每次 {@code agent_spawn} 的可见性，不受 LLM 请求影响。
     *
     * <p>示例：
     * <pre>{@code
     * RuntimeContext ctx = RuntimeContext.builder()
     *     .userId("user-1")
     *     .put(AgentSpawnTool.CTX_EXPOSE_TO_USER, true)
     *     .build();
     * }</pre>
     *
     * <p>解析优先级（由高到低）：此上下文值 → 被启动子智能体的
     * {@link SubagentDeclaration#getExposeToUser()} 策略 → LLM 的 {@code expose_to_user}
     * 参数 → {@code false}。
     */
    public static final String CTX_EXPOSE_TO_USER = "agentscope.subagent.expose_to_user";

    /**
     * {@link RuntimeContext} 字符串键，保存当前父级调用选定的不可变子智能体注册表。
     * {@link io.agentscope.harness.agent.middleware.SubagentsMiddleware}
     * 在此安装具有命名空间作用域的管理器，避免并发调用方相互覆盖声明。
     */
    public static final String CTX_AGENT_MANAGER = "agentscope.subagent.agent_manager";

    /**
     * {@link RuntimeContext} 字符串键，保存调用方定义的 {@code Map<String, Object>}
     * 属性，在当前调用的每次远程子智能体提交中作为 {@code context.attributes} 发送：
     *
     * <pre>{@code
     * RuntimeContext ctx = RuntimeContext.builder()
     *     .sessionId("s-1")
     *     .put(AgentSpawnTool.CTX_REMOTE_CONTEXT_ATTRIBUTES, Map.of("tenant", "acme"))
     *     .build();
     * }</pre>
     *
     * <p>在子智能体的静态 {@link SubagentDeclaration#getRemoteContextAttributes()}
     * 上合并，因此冲突时单次调用值优先。值必须可序列化为 JSON。
     */
    public static final String CTX_REMOTE_CONTEXT_ATTRIBUTES =
            "agentscope.subagent.remote_context_attributes";

    /**
     * {@link RuntimeContext} 字符串键，强制当前调用同步执行子智能体。
     * 在此键下放入 {@link Boolean}（或其字符串形式），即可忽略 LLM 请求的后台模式
     * （{@code timeout_seconds=0}），并禁止超时后提升为后台 {@code task_id}。
     *
     * <p>启用 force-sync 时：
     * <ul>
     *   <li>{@code timeout_seconds=0} 转为默认同步超时（30 秒），除非
     *       {@link #CTX_FORCE_SYNC_TIMEOUT_SECONDS} 提供绝对覆盖值
     *   <li>同步等待超时后，中断子智能体并返回 {@code status: timeout}，
     *       <em>不会</em>提升为异步后台任务
     * </ul>
     *
     * <pre>{@code
     * RuntimeContext ctx = RuntimeContext.builder()
     *     .sessionId("s-1")
     *     .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
     *     .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 120)
     *     .build();
     * }</pre>
     *
     * <p>适用于 {@code agent_spawn} 和 {@code agent_send}。
     * 同一轮中的多个同步工具调用默认仍并行执行（Toolkit {@code parallel=true}）。
     */
    public static final String CTX_FORCE_SYNC = "agentscope.subagent.force_sync";

    /**
     * 启用 {@link #CTX_FORCE_SYNC} 时，同步等待时间（秒）的可选
     * {@link RuntimeContext} 覆盖值。接受 {@link Integer}/{@link Number} 或其字符串形式。
     *
     * <p>存在此值且启用 force-sync 时，它会完全替代本次调用中 LLM 的
     * {@code timeout_seconds}。{@code <= 0} 的值回退到默认同步超时（30 秒）；
     * 超过 600 的值截断为 600。
     *
     * <p>未启用 force-sync 时忽略。
     *
     * <pre>{@code
     * RuntimeContext ctx = RuntimeContext.builder()
     *     .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
     *     .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 120)
     *     .build();
     * }</pre>
     */
    public static final String CTX_FORCE_SYNC_TIMEOUT_SECONDS =
            "agentscope.subagent.force_sync_timeout_seconds";

    private static final String BG_RESULT_TEMPLATE =
            """
            status: accepted
            task_id: %s
            Use task_output(task_id='%s', block=false) to check status, \
            wait_async_results(task_ids=...) to wait for a chosen group, \
            wait_async_results(wait_all=true) to wait for all current background tasks, \
            task_cancel(task_id='%s') to cancel, or task_list() to see all tasks. \
            Do NOT call task_output immediately — the task has just started. \
            Do not redo the task's work yourself while it runs in the background. \
            Never mention task_id, agent_key, or session_id to the user — they are internal handles. \
            Do not read the task's output files to watch progress; wait for the result instead.\
            """;

    /**
     * 等待远程同步任务时使用的短轮询间隔，以便及时发现 awaiting_confirm。
     */
    private static final long REMOTE_CONFIRM_POLL_MS = 1_000L;

    private final DefaultAgentManager agentManager;
    private final TaskRepository taskRepository;
    private final int parentSpawnDepth;
    private volatile SubagentGatewayBridge gatewayBridge;
    private volatile RemoteSubagentTransport remoteTransport = new AgentProtocolTransport();

    private record SpawnedAgent(
            String key, String agentId, String sessionId, String label, Agent agent, int depth) {}

    private final ConcurrentHashMap<String, SpawnedAgent> agentsByKey = new ConcurrentHashMap<>();

    private record LabelKey(String userId, String parentSessionId, String label) {}

    private final ConcurrentHashMap<LabelKey, String> labelToKey = new ConcurrentHashMap<>();

    /**
     * 创建 {@code AgentSpawnTool}，从每次工具调用的 {@link RuntimeContext}
     * 获取活动用户 ID，而非使用共享 supplier，防止同一智能体实例服务并发调用方时发生身份竞争。
     *
     * @param agentManager 子智能体工厂与调用器
     * @param taskRepository 后台任务存储
     * @param parentSpawnDepth 父级当前启动深度（顶层主智能体为 0）
     */
    public AgentSpawnTool(
            DefaultAgentManager agentManager, TaskRepository taskRepository, int parentSpawnDepth) {
        this(agentManager, taskRepository, parentSpawnDepth, null);
    }

    /**
     * 创建带可选网关桥接器的 {@code AgentSpawnTool}，
     * 用于将子智能体暴露为用户可访问的线程。
     *
     * @param agentManager 子智能体工厂与调用器
     * @param taskRepository 后台任务存储
     * @param parentSpawnDepth 父级当前启动深度（顶层主智能体为 0）
     * @param gatewayBridge 可选的线程暴露桥接器；独立模式下为 null
     */
    public AgentSpawnTool(
            DefaultAgentManager agentManager,
            TaskRepository taskRepository,
            int parentSpawnDepth,
            SubagentGatewayBridge gatewayBridge) {
        this.agentManager = Objects.requireNonNull(agentManager, "agentManager");
        this.taskRepository = taskRepository;
        this.parentSpawnDepth = parentSpawnDepth;
        this.gatewayBridge = gatewayBridge;
    }

    /**
     * 连接或重新连接用于将子智能体暴露为用户可访问线程的网关桥接器。
     *
     * <p>必须修改现有实例上的桥接器，而不是构造替代对象：工具集在编排时
     * 将 {@code agent_spawn} 绑定到这个具体对象，因此替代工具永远不会收到调用。
     * 桥接器通常在智能体构建后、创建内部网关时延迟提供
     * （参见 {@code HarnessAgent#ensureGateway}）。
     *
     * @param gatewayBridge 桥接器实现，或用 {@code null} 禁用暴露
     */
    public void setGatewayBridge(SubagentGatewayBridge gatewayBridge) {
        this.gatewayBridge = gatewayBridge;
    }

    /**
     * 仅用于测试的钩子，为远程流式／HITL 测试注入假的 {@link RemoteSubagentTransport}。
     */
    void setRemoteTransport(RemoteSubagentTransport remoteTransport) {
        this.remoteTransport = Objects.requireNonNull(remoteTransport, "remoteTransport");
    }

    @Tool(
            name = "agent_spawn",
            stateInjected = true,
            description =
                    """
                    Spawn an isolated subagent for delegated or background work. \
                    Every response starts with three lines: agent_key (pass this verbatim to \
                    agent_send as agent_key), agent_id (the subagent type name), and session_id \
                    (internal; do not use as agent_key). Sync mode returns the reply below that; \
                    async (timeout_seconds=0) adds task_id for task_output or wait_async_results; \
                    task_id is NOT agent_key. Multiple sync tool calls in one turn run in parallel \
                    by default; pass a Toolkit with parallel=false to serialize, \
                    or use async tasks for fire-and-forget parallelism. \
                    The subagent does not inherit the parent conversation, so include every \
                    file path, constraint, and expected output format the task needs. New \
                    sessions start from the supplied task; persistent sessions may retain their own history. \
                    Do not re-spawn the same task reworded to bypass a permission denial; \
                    report the limitation instead.\
                    """)
    public Mono<ToolResultBlock> agentSpawn(
            RuntimeContext runtimeContext,
            AgentState parentState,
            @ToolParam(name = "agent_id", description = "Subagent identifier to instantiate")
                    String agentId,
            @ToolParam(
                            name = "task",
                            description = "Task or prompt to send to the spawned agent",
                            required = false)
                    String task,
            @ToolParam(
                            name = "label",
                            description =
                                    "Optional human-readable label for referencing via agent_send",
                            required = false)
                    String label,
            @ToolParam(
                            name = "timeout_seconds",
                            description =
                                    """
                                    Max seconds to wait for the task result. 0=fire-and-forget, \
                                    returns task_id. Default: 30. Max: 600.\
                                    """,
                            required = false)
                    Integer timeoutSeconds,
            @ToolParam(
                            name = "expose_to_user",
                            description =
                                    """
                                    When true, the spawned subagent becomes directly addressable \
                                    by the user via a thread_id handle. Returns thread_id in the \
                                    response. Requires a gateway bridge to be configured.\
                                    """,
                            required = false)
                    Boolean exposeToUser) {

        log.debug(
                "agent_spawn called: agentId={}, timeoutSeconds={}, task={}",
                agentId,
                timeoutSeconds,
                task);
        int nextDepth = parentSpawnDepth + 1;
        if (nextDepth > MAX_SPAWN_DEPTH) {
            log.warn("agent_spawn depth exceeded: depth={}, max={}", nextDepth, MAX_SPAWN_DEPTH);
            return Mono.just(
                    ToolResultBlock.error(
                            "Maximum spawn depth exceeded (max=" + MAX_SPAWN_DEPTH + ")"));
        }
        String canonLabel = label != null && !label.isBlank() ? label.trim() : null;
        DefaultAgentManager manager = managerFor(runtimeContext);

        if (manager.isPrimaryOnly(agentId)) {
            return Mono.just(
                    ToolResultBlock.error(
                            "agent_id '"
                                    + agentId
                                    + "' is PRIMARY-only and cannot be spawned as a subagent."));
        }
        if (!manager.hasAgent(agentId)) {
            return Mono.just(ToolResultBlock.error("Unknown agent_id: " + agentId));
        }
        String currentUserId = runtimeContext != null ? runtimeContext.getUserId() : null;
        String parentSessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
        var declOpt = manager.getDeclaration(agentId);
        boolean persist = declOpt.map(SubagentDeclaration::isPersistSession).orElse(false);

        String key;
        String sessionId;
        if (persist) {
            String hash = deterministicHash(currentUserId, parentSessionId, agentId, canonLabel);
            key = "agent:" + agentId + ":" + hash;
            sessionId = "sub-" + hash;
        } else {
            key = "agent:" + agentId + ":" + UUID.randomUUID();
            sessionId = "sub-" + UUID.randomUUID();
        }

        LabelKey labelKey = canonLabel != null ? labelKey(runtimeContext, canonLabel) : null;
        String labeledKey = labelKey != null ? labelToKey.get(labelKey) : null;
        if (labeledKey != null && (!persist || !labeledKey.equals(key))) {
            return Mono.just(ToolResultBlock.error("Label already in use: " + canonLabel));
        }

        // 仅在缓存未命中时创建，包括并发启动同一身份的情况。
        AtomicBoolean created = new AtomicBoolean();
        SpawnedAgent spawned =
                agentsByKey.computeIfAbsent(
                        key,
                        ignored -> {
                            Optional<Agent> agentOpt =
                                    manager.createAgentIfPresent(agentId, runtimeContext);
                            if (agentOpt.isEmpty()) {
                                return null;
                            }
                            created.set(true);
                            return new SpawnedAgent(
                                    key, agentId, sessionId, canonLabel, agentOpt.get(), nextDepth);
                        });
        if (spawned == null) {
            return Mono.just(ToolResultBlock.error("Unknown agent_id: " + agentId));
        }
        Agent agent = spawned.agent();
        if (labelKey != null) {
            labelToKey.put(labelKey, key);
        }
        persistSpawnEntry(parentState, key, agentId, sessionId, canonLabel, nextDepth);
        if (!created.get()) {
            propagatePlanMode(parentState, currentUserId, sessionId, agent);
            propagateParentDenyRules(parentState, currentUserId, sessionId, agent, declOpt);
            String spawnInfo = formatSpawnInfo(key, agentId, sessionId, null);
            if (task == null || task.isBlank()) {
                return Mono.just(
                        ToolResultBlock.success(spawnInfo + "\nstatus: accepted (reused)"));
            }
            return execSpawnTask(
                    spawned, runtimeContext, parentState, spawnInfo, task, timeoutSeconds, declOpt);
        }
        var recorder = io.agentscope.core.session.SessionRecorder.from(runtimeContext);
        if (recorder != null)
            recorder.recordNow(
                    "subagent/spawned",
                    Map.of(
                            "childSessionId",
                            sessionId,
                            "childAgentId",
                            agentId,
                            "agentKey",
                            key,
                            "childLogAgentId",
                            agent instanceof HarnessAgent child
                                    ? child.sessionKey(
                                                    RuntimeContext.builder()
                                                            .userId(currentUserId)
                                                            .sessionId(sessionId)
                                                            .build())
                                            .agentId()
                                    : agentId));

        // 传播计划模式：父级处于计划模式时，也强制子级进入只读模式。
        propagatePlanMode(parentState, currentUserId, sessionId, agent);

        // 从父级向子级传播 DENY 权限规则（继承安全边界）。
        propagateParentDenyRules(parentState, currentUserId, sessionId, agent, declOpt);

        // 请求时通过网关桥接器向用户暴露子智能体。最终决策组合
        // 单次调用的 RuntimeContext 覆盖值、声明策略及 LLM 提供的参数
        // （按上述优先级），使应用代码可以强制允许或禁止暴露，
        // 不受模型决定影响。
        boolean effectiveExpose = resolveExposeToUser(exposeToUser, declOpt, runtimeContext);
        String subagentId = null;
        if (effectiveExpose && gatewayBridge != null) {
            OutboundAddress replyTo =
                    runtimeContext != null
                            ? runtimeContext.get("outboundAddress", OutboundAddress.class)
                            : null;
            SubagentGatewayBridge.ExposeResult er =
                    gatewayBridge.expose(agentId, sessionId, agent, replyTo);
            subagentId = er.subagentId();
        }

        String spawnInfo = formatSpawnInfo(key, agentId, sessionId, subagentId);
        boolean hasTask = task != null && !task.isBlank();

        if (!hasTask) {
            return withSubagentExposedEvent(
                    Mono.just(ToolResultBlock.success(spawnInfo + "\nstatus: accepted")),
                    subagentId,
                    agentId,
                    sessionId,
                    canonLabel);
        }

        boolean forceSync = isForceSync(runtimeContext);
        long timeoutMs = resolveEffectiveTimeoutMs(timeoutSeconds, runtimeContext);
        boolean remote = declOpt.map(SubagentDeclaration::isRemote).orElse(false);

        if (timeoutMs == 0) {
            String taskId = "task_" + UUID.randomUUID();
            final String capturedTask = task;
            TaskRunSpec spec;
            if (remote) {
                SubagentDeclaration d = declOpt.get();
                spec =
                        new TaskRunSpec.RemoteTaskRunSpec(
                                d.getUrl(),
                                d.getHeaders(),
                                agentId,
                                capturedTask,
                                buildRemoteSubmitContext(runtimeContext, parentState, d));
            } else {
                spec =
                        new TaskRunSpec.LocalTaskRunSpec(
                                () -> {
                                    Msg reply =
                                            manager.invokeAgent(
                                                            agent,
                                                            sessionId,
                                                            currentUserId,
                                                            capturedTask,
                                                            runtimeContext)
                                                    .block();
                                    return reply != null ? reply.getTextContent() : "";
                                });
            }
            taskRepository.putTask(runtimeContext, taskId, agentId, parentSessionId, spec);
            return withSubagentExposedEvent(
                    Mono.just(
                            ToolResultBlock.success(
                                    spawnInfo
                                            + "\n"
                                            + String.format(
                                                    BG_RESULT_TEMPLATE, taskId, taskId, taskId))),
                    subagentId,
                    agentId,
                    sessionId,
                    canonLabel);
        }

        if (remote) {
            final String finalTask = task;
            return withSubagentExposedEvent(
                    runRemoteSyncReactive(
                            runtimeContext,
                            parentState,
                            spawnInfo,
                            agentId,
                            parentSessionId,
                            declOpt.get(),
                            finalTask.trim(),
                            timeoutMs,
                            forceSync),
                    subagentId,
                    agentId,
                    sessionId,
                    canonLabel);
        }

        // 带超时转后台的本地同步执行：智能体未在超时前完成时，
        // 将正在进行的执行转为异步任务，避免丢失；
        // 但启用 force-sync 时会中断智能体，
        // 并返回 status: timeout。
        final String finalTask = task.trim();
        final String finalSpawnInfo = spawnInfo;
        final String finalSubagentId = subagentId;
        final String finalLabel = canonLabel;
        return withSubagentExposedEvent(
                execWithTimeoutPromotion(
                        agent,
                        sessionId,
                        currentUserId,
                        finalTask,
                        spawned,
                        runtimeContext,
                        finalSpawnInfo,
                        timeoutMs,
                        agentId,
                        forceSync),
                finalSubagentId,
                agentId,
                sessionId,
                finalLabel);
    }

    @Tool(
            name = "agent_send",
            stateInjected = true,
            description =
                    """
                    Send a message to an existing subagent. Use the exact string from the \
                    agent_key line of agent_spawn output (starts with agent:), or the label \
                    you set at spawn. Do not pass agent_id, session_id, or task_id here. \
                    timeout_seconds=0 returns task_id for task_output or wait_async_results. \
                    Sends are follow-ups to a subagent that already holds the context you \
                    gave it. Use follow-ups to steer the task or continue its work. Do not \
                    re-send the same request reworded to bypass a permission denial.\
                    """)
    public Mono<ToolResultBlock> agentSend(
            RuntimeContext runtimeContext,
            AgentState parentState,
            @ToolParam(
                            name = "agent_key",
                            description =
                                    "Exact value from agent_spawn's first line after 'agent_key: '"
                                        + " (format agent:<type>:<uuid>). Not agent_id, session_id,"
                                        + " or task_id. Mutually exclusive with label.",
                            required = false)
                    String agentKey,
            @ToolParam(
                            name = "label",
                            description =
                                    "Agent label assigned at spawn time. Mutually exclusive with"
                                            + " agent_key.",
                            required = false)
                    String label,
            @ToolParam(name = "message", description = "Message to send to the subagent")
                    String message,
            @ToolParam(
                            name = "timeout_seconds",
                            description =
                                    """
                                    Max seconds to wait for a reply. 0=fire-and-forget, returns \
                                    task_id. Default: 30. Max: 600.\
                                    """,
                            required = false)
                    Integer timeoutSeconds) {

        boolean hasKey = agentKey != null && !agentKey.isBlank();
        boolean hasLabel = label != null && !label.isBlank();
        if (hasKey && hasLabel) {
            return Mono.just(ToolResultBlock.error("Provide either agent_key or label, not both."));
        }
        if (!hasKey && !hasLabel) {
            return Mono.just(ToolResultBlock.error("Either agent_key or label is required."));
        }
        if (message == null || message.isBlank()) {
            return Mono.just(ToolResultBlock.error("message is required"));
        }

        String key;
        if (hasKey) {
            key = agentKey.trim();
        } else {
            key = labelToKey.get(labelKey(runtimeContext, label.trim()));
            if (key == null) {
                key = tryResolveLabelFromState(parentState, label.trim());
            }
            if (key == null) {
                return Mono.just(ToolResultBlock.error("Unknown label: " + label.trim()));
            }
        }

        SpawnedAgent resolved = agentsByKey.get(key);
        if (resolved == null) {
            resolved = tryRestoreFromState(parentState, key, runtimeContext);
        }
        if (resolved == null) {
            return Mono.just(ToolResultBlock.error("Unknown agent_key: " + key));
        }
        final SpawnedAgent spawned = resolved;

        boolean forceSync = isForceSync(runtimeContext);
        long timeoutMs = resolveEffectiveTimeoutMs(timeoutSeconds, runtimeContext);
        String currentUserId = runtimeContext != null ? runtimeContext.getUserId() : null;
        String parentSessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
        DefaultAgentManager manager = managerFor(runtimeContext);
        propagatePlanMode(parentState, currentUserId, spawned.sessionId(), spawned.agent());
        var declOpt = manager.getDeclaration(spawned.agentId());
        propagateParentDenyRules(
                parentState, currentUserId, spawned.sessionId(), spawned.agent(), declOpt);
        boolean remote = declOpt.map(SubagentDeclaration::isRemote).orElse(false);

        if (timeoutMs == 0) {
            String taskId = "task_" + UUID.randomUUID();
            final String capturedMessage = message;
            TaskRunSpec spec;
            if (remote) {
                SubagentDeclaration d = declOpt.get();
                spec =
                        new TaskRunSpec.RemoteTaskRunSpec(
                                d.getUrl(),
                                d.getHeaders(),
                                spawned.agentId(),
                                capturedMessage,
                                buildRemoteSubmitContext(runtimeContext, parentState, d));
            } else {
                spec =
                        new TaskRunSpec.LocalTaskRunSpec(
                                () -> {
                                    Msg reply =
                                            manager.invokeAgent(
                                                            spawned.agent(),
                                                            spawned.sessionId(),
                                                            currentUserId,
                                                            capturedMessage,
                                                            runtimeContext)
                                                    .block();
                                    return reply != null ? reply.getTextContent() : "";
                                });
            }
            taskRepository.putTask(
                    runtimeContext, taskId, spawned.agentId(), parentSessionId, spec);
            return Mono.just(
                    ToolResultBlock.success(
                            String.format(BG_RESULT_TEMPLATE, taskId, taskId, taskId)));
        }

        if (remote) {
            final String finalMessage = message;
            final String finalKey = key;
            return runRemoteSyncReactive(
                    runtimeContext,
                    parentState,
                    "agent_key: " + finalKey,
                    spawned.agentId(),
                    parentSessionId,
                    declOpt.get(),
                    finalMessage.trim(),
                    timeoutMs,
                    forceSync);
        }

        final String finalKey = key;
        return execWithTimeoutPromotion(
                spawned.agent(),
                spawned.sessionId(),
                currentUserId,
                message.trim(),
                spawned,
                runtimeContext,
                "agent_key: " + finalKey,
                timeoutMs,
                spawned.agentId(),
                forceSync);
    }

    @Tool(name = "agent_list", description = "List active subagents spawned by this agent.")
    public ToolResultBlock agentList() {
        if (agentsByKey.isEmpty()) {
            return ToolResultBlock.success("No active subagents.");
        }

        StringBuilder sb =
                new StringBuilder("Active subagents (").append(agentsByKey.size()).append("):\n");
        for (SpawnedAgent a : agentsByKey.values()) {
            sb.append("- agent_key: ").append(a.key()).append("\n");
            sb.append("  agent_id: ").append(a.agentId()).append("\n");
            if (a.label() != null) {
                sb.append("  label: ").append(a.label()).append("\n");
            }
            sb.append("  spawn_depth: ").append(a.depth()).append("\n");
        }
        return ToolResultBlock.success(sb.toString().trim());
    }

    // -----------------------------------------------------------------
    // 辅助方法
    // -----------------------------------------------------------------

    private DefaultAgentManager managerFor(RuntimeContext runtimeContext) {
        DefaultAgentManager scoped =
                runtimeContext != null
                        ? runtimeContext.get(CTX_AGENT_MANAGER, DefaultAgentManager.class)
                        : null;
        return scoped != null ? scoped : agentManager;
    }

    /**
     * 在本地子智能体即将被调用之前激活计划模式。
     *
     * <p>新建和复用的子智能体都必须执行此步骤。尤其是持久化子智能体可能
     * 在父级处于构建模式时创建，却在父级进入计划模式后被复用。
     */
    private static void propagatePlanMode(
            AgentState parentState, String userId, String sessionId, Agent child) {
        if (parentState != null
                && parentState.getPlanModeContext().isPlanActive()
                && child instanceof HarnessAgent harnessChild) {
            harnessChild.enterPlanMode(userId, sessionId);
        }
    }

    /**
     * 返回调用本地子智能体的 {@link Mono}。
     *
     * <p>依次检查三条路径：
     * <ol>
     *   <li><b>{@code streamEvents()} 路径</b>：Reactor Context 中存在
     *       {@link AgentEventEmitter}（由 {@code ReActAgent.streamEvents} 设置）。
     *       子事件通过附加来源标签的包装 emitter 转发到父级 {@code Flux<AgentEvent>}。
     *   <li><b>{@code stream()} 路径</b>（已弃用）：存在 {@link SubagentEventBus}。
     *       子事件通过总线转发，并带上 {@link EventSource} 元数据。
     *   <li><b>非流式路径</b>：普通 {@code call()}，不转发事件。
     * </ol>
     *
     * <p><b>上下文传播说明：</b>此方法返回的 {@code Mono} 中，{@code deferContextual}
     * 由 {@code ToolMethodInvoker} 的 {@code flatMap} 订阅，能够正确继承父级流式链的
     * Reactor Context。不要在返回 {@link String} 的工具方法内直接对此 Mono 调用
     * {@code .block()}，因为 {@code block()} 创建隔离订阅，会丢失 Context。
     */
    private Mono<Msg> execLocalSync(
            Agent agent,
            String sessionId,
            String userId,
            String prompt,
            SpawnedAgent spawned,
            RuntimeContext parentCtx) {
        return Mono.deferContextual(
                        ctxView -> {
                            DefaultAgentManager manager = managerFor(parentCtx);
                            // ── 路径 1：streamEvents()，转发 AgentEvent ──
                            Optional<AgentEventEmitter> emitterOpt =
                                    AgentEventEmitter.fromContext(ctxView);
                            if (emitterOpt.isPresent()) {
                                AgentEventEmitter parentEmitter = emitterOpt.get();
                                String sourcePath = buildSourcePath(spawned, parentCtx);
                                String replyId = UUID.randomUUID().toString().replace("-", "");
                                AgentEventEmitter taggedEmitter =
                                        event -> parentEmitter.emit(event.withSource(sourcePath));

                                parentEmitter.emit(
                                        new AgentStartEvent(
                                                        spawned.sessionId(),
                                                        replyId,
                                                        spawned.agentId())
                                                .withSource(sourcePath));

                                AtomicBoolean endEmitted = new AtomicBoolean();
                                Runnable emitEnd =
                                        () -> {
                                            if (endEmitted.compareAndSet(false, true)) {
                                                parentEmitter.emit(
                                                        new AgentEndEvent(replyId)
                                                                .withSource(sourcePath));
                                            }
                                        };

                                return manager.invokeAgent(
                                                agent, sessionId, userId, prompt, parentCtx)
                                        .contextWrite(
                                                c ->
                                                        c.put(
                                                                AgentEventEmitter
                                                                        .FORWARDING_CONTEXT_KEY,
                                                                taggedEmitter))
                                        // 在成功或错误到达父级之前发出事件，否则父级
                                        // 可能
                                        // 在 doFinally 运行前就已完成其事件 sink。
                                        .doOnSuccess(ignored -> emitEnd.run())
                                        .doOnError(ignored -> emitEnd.run())
                                        // 保留尽力而为的取消信号，同时避免
                                        // 取消与正常终止发生竞争时
                                        // 重复发出事件。
                                        .doFinally(
                                                signal -> {
                                                    if (signal == SignalType.CANCEL) {
                                                        emitEnd.run();
                                                    }
                                                });
                            }

                            // ── 路径 2：stream()（已弃用），通过 SubagentEventBus 转发 ──
                            if (ctxView.hasKey(SubagentEventBus.CONTEXT_KEY)) {
                                SubagentEventBus bus = ctxView.get(SubagentEventBus.CONTEXT_KEY);
                                EventSource childSource = buildChildSource(spawned, parentCtx);

                                return manager.invokeAgentStream(
                                                agent,
                                                sessionId,
                                                userId,
                                                prompt,
                                                childSource,
                                                StreamOptions.defaults(),
                                                parentCtx)
                                        .doOnNext(
                                                e -> {
                                                    log.debug(
                                                            "[execLocalSync] forwarding child event"
                                                                    + " to bus: type={} msgId={}"
                                                                    + " isLast={}",
                                                            e.getType(),
                                                            e.getMessage().getId(),
                                                            e.isLast());
                                                    bus.emit(e);
                                                })
                                        .filter(
                                                e ->
                                                        e.isLast()
                                                                && e.getType()
                                                                        == EventType.AGENT_RESULT)
                                        .last()
                                        .map(e -> e.getMessage())
                                        .switchIfEmpty(
                                                Mono.defer(
                                                        () ->
                                                                manager.invokeAgent(
                                                                        agent, sessionId, userId,
                                                                        prompt, parentCtx)));
                            }

                            // ── 路径 3：非流式 ──
                            return manager.invokeAgent(agent, sessionId, userId, prompt, parentCtx);
                        })
                .doOnSuccess(
                        reply -> {
                            var recorder =
                                    io.agentscope.core.session.SessionRecorder.from(parentCtx);
                            if (recorder != null)
                                recorder.recordIfOpen(
                                        "subagent/completed",
                                        Map.of(
                                                "childSessionId",
                                                sessionId,
                                                "childAgentId",
                                                spawned.agentId(),
                                                "status",
                                                "completed"));
                        });
    }

    /**
     * 执行本地子智能体并支持超时转后台：智能体未在 {@code timeoutMs} 内完成时，
     * 将正在进行的执行提升为异步后台任务，而不是取消并丢失它；但 {@code forceSync}
     * 为 true 时会中断智能体并返回 {@code status: timeout}，不会转入后台。
     *
     * <p>关键机制是使用 {@link CompletableFuture} 桥接执行与观察，将两者解耦。
     * 订阅 {@link #execLocalSync} 返回的 Mono 时传播 Reactor Context
     * （使同步等待期间仍流转事件），并将结果写入桥接 future。
     * 从桥接 future 派生一个竞争 future 来增加超时，而不取消原始执行：
     * <ul>
     *   <li>智能体先于超时完成：返回正常结果
     *   <li>超时先发生（默认）：将仍运行的桥接 future 以
     *       {@link TaskRunSpec.AdoptedTaskRunSpec} 注册到 {@link TaskRepository}，
     *       并返回 {@code task_id}
     *   <li>超时先发生（{@code forceSync}）：中断智能体，返回 {@code status: timeout}，
     *       不返回 {@code task_id}
     *   <li>智能体出错：返回错误消息
     * </ul>
     */
    private Mono<ToolResultBlock> execWithTimeoutPromotion(
            Agent agent,
            String sessionId,
            String userId,
            String task,
            SpawnedAgent spawned,
            RuntimeContext runtimeContext,
            String header,
            long timeoutMs,
            String agentId,
            boolean forceSync) {

        return Mono.deferContextual(
                parentCtx ->
                        Mono.<ToolResultBlock>create(
                                sink -> {
                                    CompletableFuture<Msg> bridge = new CompletableFuture<>();

                                    Mono<Msg> inner =
                                            execLocalSync(
                                                            agent,
                                                            sessionId,
                                                            userId,
                                                            task,
                                                            spawned,
                                                            runtimeContext)
                                                    .contextWrite(
                                                            c ->
                                                                    reactor.util.context.Context.of(
                                                                            parentCtx))
                                                    .doFinally(
                                                            signal -> {
                                                                // 父级订阅已被取消
                                                                // （外层 Mono.timeout、
                                                                // 用户停止，或上游 Reactor
                                                                // 取消）。下面即发即弃的
                                                                // subscribe 将内部执行
                                                                // 从父级生命周期中分离，
                                                                // 因此如果没有此处的
                                                                // doFinally，子智能体将
                                                                // 作为孤立执行继续运行，
                                                                // 最终的 sink.success(...)
                                                                // 在已取消的 sink 上
                                                                // 也不会产生任何作用。
                                                                if (signal == SignalType.CANCEL) {
                                                                    interruptAgent(
                                                                            agent, runtimeContext);
                                                                }
                                                            });

                                    Disposable innerSub =
                                            inner.subscribe(
                                                    bridge::complete,
                                                    bridge::completeExceptionally,
                                                    () -> {
                                                        if (!bridge.isDone()) {
                                                            bridge.complete(null);
                                                        }
                                                    });

                                    // 与超时竞争，但不取消桥接 future。
                                    // thenApply(m -> m) 创建依赖 future，让 orTimeout
                                    // 完成竞争副本，而不是原始桥接 future。
                                    // 【学习提示】超时约束的是同步观察窗口，不默认终止执行。
                                    // 保留原始 bridge，才能将同一次执行交给任务仓库继续跟踪，避免重跑。
                                    CompletableFuture<Msg> race = bridge.thenApply(m -> m);
                                    race.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);

                                    race.whenComplete(
                                            (msg, err) -> {
                                                if (err != null) {
                                                    handleExecError(
                                                            err,
                                                            bridge,
                                                            runtimeContext,
                                                            header,
                                                            timeoutMs,
                                                            agentId,
                                                            sink,
                                                            forceSync,
                                                            innerSub);
                                                } else {
                                                    sink.success(
                                                            ToolResultBlock.success(
                                                                    header
                                                                            + "\n"
                                                                            + "status: ok\n"
                                                                            + "reply:\n"
                                                                            + textOf(msg)));
                                                }
                                            });

                                    // 将父级取消传播到内部即发即弃的订阅。
                                    // 否则，上面的 doFinally(CANCEL) 永远不会
                                    // 触发，因为内部订阅独立于
                                    // 父级 Mono.create 的 sink。
                                    // 【学习提示】主动取消与“等待超时转后台”是两种不同策略；
                                    // 手动 subscribe 建立了独立订阅，因此必须显式接回父级取消信号。
                                    sink.onCancel(innerSub);
                                }));
    }

    /**
     * 当父级工具调用订阅被取消时，中断子智能体推理循环。
     * 对应 core 中 {@code SubAgentTool.interruptAgent} 的修复
     * （提交 {@code 029cc55e}，issue #1783）；Harness 对应问题参见 issue #2062。
     *
     * <p>只有 {@link ReActAgent} 暴露 {@code interrupt(RuntimeContext)}。
     * 其他 {@link Agent} 实现在此不执行操作；调用方的
     * {@code sink.onCancel(innerSub::dispose)} 仍会释放其内部执行，
     * 对没有循环的智能体已经足够。
     */
    private void interruptAgent(Agent agent, RuntimeContext ctx) {
        if (agent instanceof ReActAgent ra) {
            ra.interrupt(ctx);
            log.warn(
                    "Sub-agent '{}' (id={}) was interrupted because its parent tool call"
                            + " subscription was cancelled.",
                    ra.getName(),
                    ra.getAgentId());
        }
    }

    /**
     * 处理 {@link #execWithTimeoutPromotion} 中竞争 future 的错误。
     * 独立为方法以保持 lambda 可读性：区分超时（转入后台，或 force-sync 下直接失败）
     * 与真实错误（报告）。
     */
    private void handleExecError(
            Throwable err,
            CompletableFuture<Msg> bridge,
            RuntimeContext runtimeContext,
            String header,
            long timeoutMs,
            String agentId,
            reactor.core.publisher.MonoSink<ToolResultBlock> sink,
            boolean forceSync,
            Disposable innerSub) {

        Throwable cause = err instanceof CompletionException ? err.getCause() : err;
        if (cause instanceof TimeoutException) {
            if (forceSync) {
                // 硬超时：dispose 触发 doFinally(CANCEL) → interruptAgent，不转入后台。
                if (innerSub != null && !innerSub.isDisposed()) {
                    innerSub.dispose();
                }
                log.info(
                        "agent_spawn sync timeout after {}ms under force_sync (not promoted):"
                                + " agentId={}",
                        timeoutMs,
                        agentId);
                sink.success(
                        ToolResultBlock.text(header + "\n" + formatForceSyncTimeout(timeoutMs))
                                .withState(ToolResultState.ERROR));
                return;
            }
            String taskId = "task_" + UUID.randomUUID();
            String parentSessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
            CompletableFuture<String> textFuture = bridge.thenApply(AgentSpawnTool::textOf);
            taskRepository.putTask(
                    runtimeContext,
                    taskId,
                    agentId,
                    parentSessionId,
                    new TaskRunSpec.AdoptedTaskRunSpec(textFuture));
            log.info(
                    "agent_spawn sync timeout after {}ms, promoted to async: agentId={}, taskId={}",
                    timeoutMs,
                    agentId,
                    taskId);
            sink.success(
                    ToolResultBlock.success(
                            header + "\n" + formatTimeoutPromoted(taskId, timeoutMs)));
        } else {
            Throwable reportable = cause != null ? cause : err;
            String errStr =
                    reportable.getMessage() != null
                            ? reportable.getMessage()
                            : reportable.getClass().getSimpleName();
            log.warn("agent execution failed: agentId={}", agentId, reportable);
            sink.success(
                    ToolResultBlock.text(header + "\nstatus: error\nerror: " + errStr)
                            .withState(ToolResultState.ERROR));
        }
    }

    private void persistSpawnEntry(
            AgentState parentState,
            String key,
            String agentId,
            String sessionId,
            String label,
            int depth) {
        if (parentState == null) {
            return;
        }
        parentState
                .getToolContext()
                .putSpawnEntry(
                        key,
                        new io.agentscope.core.state.ToolContextState.SpawnEntry(
                                key, agentId, sessionId, label, depth));
    }

    private String tryResolveLabelFromState(AgentState parentState, String label) {
        if (parentState == null) {
            return null;
        }
        String lowerLabel = label.toLowerCase(Locale.ROOT);
        for (io.agentscope.core.state.ToolContextState.SpawnEntry entry :
                parentState.getToolContext().getSpawnRegistry().values()) {
            if (entry.label() != null
                    && entry.label().toLowerCase(Locale.ROOT).equals(lowerLabel)) {
                return entry.key();
            }
        }
        return null;
    }

    private SpawnedAgent tryRestoreFromState(
            AgentState parentState, String key, RuntimeContext runtimeContext) {
        if (parentState == null) {
            return null;
        }
        io.agentscope.core.state.ToolContextState.SpawnEntry entry =
                parentState.getToolContext().getSpawnRegistry().get(key);
        if (entry == null) {
            return null;
        }
        Optional<Agent> agentOpt =
                managerFor(runtimeContext).createAgentIfPresent(entry.agentId(), runtimeContext);
        if (agentOpt.isEmpty()) {
            log.warn(
                    "Failed to restore subagent from state: agentId={} not found in registry",
                    entry.agentId());
            return null;
        }
        SpawnedAgent restored =
                new SpawnedAgent(
                        key,
                        entry.agentId(),
                        entry.sessionId(),
                        entry.label(),
                        agentOpt.get(),
                        entry.depth());
        agentsByKey.put(key, restored);
        if (entry.label() != null) {
            labelToKey.put(labelKey(runtimeContext, entry.label()), key);
        }
        log.info(
                "Restored subagent from persisted state: key={}, agentId={}", key, entry.agentId());
        return restored;
    }

    private static String textOf(Msg msg) {
        return msg != null ? msg.getTextContent() : "";
    }

    private static String formatTimeoutPromoted(String taskId, long timeoutMs) {
        return String.format(
                """
                status: timeout_promoted
                task_id: %s
                The task exceeded the %ds sync timeout but is still running in the background. \
                Use task_output(task_id='%s', block=false) to check status, \
                wait_async_results(task_ids=...) when this task is part of a required barrier, \
                or wait — completed tasks are pushed back to you automatically. \
                Do NOT retry the same task.\
                """,
                taskId, timeoutMs / 1000, taskId);
    }

    private static String formatForceSyncTimeout(long timeoutMs) {
        return String.format(
                """
                status: timeout
                The task exceeded the %ds sync timeout and was interrupted because force_sync \
                is enabled. Background promotion is disabled for this call.\
                """,
                timeoutMs / 1000);
    }

    /**
     * 为新启动或已知子智能体构建 {@link EventSource}。
     * 路径由父会话 ID（回退值为 {@code "main"}）与子级 {@code agentId}
     * 组成，使用 {@code "/"} 分隔。
     */
    private EventSource buildChildSource(SpawnedAgent spawned, RuntimeContext parentCtx) {
        String parentName =
                (parentCtx != null && parentCtx.getSessionId() != null)
                        ? parentCtx.getSessionId()
                        : "main";
        String path = parentName + "/" + spawned.agentId();
        return EventSource.builder()
                .agentKey(spawned.key())
                .agentId(spawned.agentId())
                .sessionId(spawned.sessionId())
                .depth(spawned.depth())
                .path(path)
                .build();
    }

    /**
     * 为 {@link AgentEvent#withSource} 标签构建来源路径字符串。
     * 使用与 {@link #buildChildSource} 相同的父会话／子智能体 ID 约定。
     */
    private String buildSourcePath(SpawnedAgent spawned, RuntimeContext parentCtx) {
        String parentName =
                (parentCtx != null && parentCtx.getSessionId() != null)
                        ? parentCtx.getSessionId()
                        : "main";
        return parentName + "/" + spawned.agentId();
    }

    /**
     * 为转发到父级流的远程事件构建 {@code parentSession/agentId} 来源路径。
     */
    static String buildRemoteSourcePath(String parentSessionId, String agentId) {
        String parent =
                parentSessionId != null && !parentSessionId.isBlank() ? parentSessionId : "main";
        String child = agentId != null && !agentId.isBlank() ? agentId : "remote";
        return parent + "/" + child;
    }

    /**
     * 为远程转发的 {@link AgentEvent} 标记父级可见的 {@code source} 路径、
     * Harness 的 {@link AgentEvent#METADATA_TASK_ID} 和
     * {@link AgentEvent#METADATA_PARENT_SESSION_ID}，使对同一远程智能体的并发调用
     * 仍能关联到各自的 {@code TaskRecord} 及发起它们的父会话。
     */
    static AgentEvent tagRemoteForwardedEvent(
            AgentEvent event, String sourcePath, String taskId, String parentSessionId) {
        if (event == null) {
            return null;
        }
        event.withSource(sourcePath);
        if (taskId != null && !taskId.isBlank()) {
            event.withMetadataEntry(AgentEvent.METADATA_TASK_ID, taskId);
        }
        if (parentSessionId != null && !parentSessionId.isBlank()) {
            event.withMetadataEntry(AgentEvent.METADATA_PARENT_SESSION_ID, parentSessionId.trim());
        }
        return event;
    }

    /**
     * 构建远程任务的提交元数据（流式偏好、父级身份、拒绝权限规则、调用方自定义属性）。
     */
    private RemoteSubmitContext buildRemoteSubmitContext(
            RuntimeContext runtimeContext, AgentState parentState, SubagentDeclaration decl) {
        String userId = runtimeContext != null ? runtimeContext.getUserId() : null;
        String parentSessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
        boolean stream = decl != null && decl.isRemoteStreaming();
        String detail =
                stream
                        ? decl.getRemoteStreamDetail().wireValue()
                        : RemoteStreamDetail.STATUS.wireValue();
        return RemoteSubmitContext.builder().userId(userId).parentSessionId(parentSessionId).stream(
                        stream)
                .detail(detail)
                .denyRules(collectParentDenyRules(parentState, Optional.ofNullable(decl)))
                .attributes(collectRemoteContextAttributes(runtimeContext, decl))
                .build();
    }

    /**
     * 将子智能体声明的静态属性与 {@link #CTX_REMOTE_CONTEXT_ATTRIBUTES}
     * 中的单次调用属性合并；冲突时单次调用条目优先。
     */
    static Map<String, Object> collectRemoteContextAttributes(
            RuntimeContext runtimeContext, SubagentDeclaration decl) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (decl != null && decl.getRemoteContextAttributes() != null) {
            merged.putAll(decl.getRemoteContextAttributes());
        }
        Object perCall =
                runtimeContext != null ? runtimeContext.get(CTX_REMOTE_CONTEXT_ATTRIBUTES) : null;
        if (perCall instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    merged.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
        }
        return merged;
    }

    /**
     * 将父级 DENY 规则展平为 {@link RemoteSubmitContext} 的传输映射。
     * 禁用继承或父级没有 DENY 规则时返回空列表。
     */
    static List<Map<String, String>> collectParentDenyRules(
            AgentState parentState, Optional<SubagentDeclaration> declaration) {
        boolean inherit =
                declaration.map(SubagentDeclaration::isInheritParentPermissions).orElse(true);
        if (!inherit || parentState == null) {
            return List.of();
        }
        PermissionContextState parentPermissions = parentState.getPermissionContext();
        if (parentPermissions == null || parentPermissions.getDenyRules().isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> out = new ArrayList<>();
        parentPermissions
                .getDenyRules()
                .forEach(
                        (toolName, rules) -> {
                            for (PermissionRule rule : rules) {
                                Map<String, String> m = new LinkedHashMap<>();
                                m.put("tool_name", rule.toolName());
                                if (rule.ruleContent() != null) {
                                    m.put("rule_content", rule.ruleContent());
                                }
                                m.put("behavior", rule.behavior().name());
                                m.put("source", rule.source());
                                out.add(m);
                            }
                        });
        return out;
    }

    /**
     * 远程同步执行的响应式入口。在阻塞等待远程任务之前，
     * 从 Reactor Context 捕获 {@link AgentEventEmitter}。
     */
    private Mono<ToolResultBlock> runRemoteSyncReactive(
            RuntimeContext runtimeContext,
            AgentState parentState,
            String header,
            String agentId,
            String parentSessionId,
            SubagentDeclaration decl,
            String input,
            long timeoutMs,
            boolean forceSync) {
        return Mono.deferContextual(
                ctxView -> {
                    Optional<AgentEventEmitter> emitterOpt = AgentEventEmitter.fromContext(ctxView);
                    return Mono.fromCallable(
                            () ->
                                    runRemoteSync(
                                            runtimeContext,
                                            parentState,
                                            header,
                                            agentId,
                                            parentSessionId,
                                            decl,
                                            input,
                                            timeoutMs,
                                            emitterOpt.orElse(null),
                                            forceSync));
                });
    }

    /**
     * 通过 {@link TaskRepository} 提交远程任务（用于持久化状态），
     * 并阻塞直到任务完成或超时。
     *
     * <p>存在 {@link AgentEventEmitter} 且 {@link SubagentDeclaration#isRemoteStreaming()}
     * 为 true 时，将远程事件转发到父级流。没有 emitter 或应用
     * {@link RemoteAskPolicy#DENY} 时，通过 {@link RemoteSubagentTransport#resume}
     * 自动拒绝待处理的远程确认。
     */
    private ToolResultBlock runRemoteSync(
            RuntimeContext runtimeContext,
            AgentState parentState,
            String header,
            String agentId,
            String parentSessionId,
            SubagentDeclaration decl,
            String input,
            long timeoutMs,
            AgentEventEmitter emitter,
            boolean forceSync) {
        String taskId = "task_" + UUID.randomUUID();
        RemoteSubmitContext submitContext =
                buildRemoteSubmitContext(runtimeContext, parentState, decl);
        TaskRunSpec spec =
                new TaskRunSpec.RemoteTaskRunSpec(
                        decl.getUrl(), decl.getHeaders(), agentId, input, submitContext);
        BackgroundTask bgTask =
                taskRepository.putTask(runtimeContext, taskId, agentId, parentSessionId, spec);

        RemoteTarget target = new RemoteTarget(decl.getUrl(), decl.getHeaders());
        RemoteSubagentTransport transport = this.remoteTransport;
        String sourcePath = buildRemoteSourcePath(parentSessionId, agentId);
        // 【学习提示】远程任务先进入持久化任务仓库，事件流只是观察通道。
        // 是否流式转发与任务是否存在是不同维度，不能把断开观察等同于远程执行结束。
        boolean wantStream = emitter != null && decl.isRemoteStreaming();
        AtomicBoolean autoDenied = new AtomicBoolean(false);
        AtomicBoolean resumedEpisode = new AtomicBoolean(false);

        Closeable streamHandle = () -> {};
        try {
            if (wantStream) {
                streamHandle =
                        transport.streamEvents(
                                target,
                                taskId,
                                0L,
                                remoteEvent ->
                                        RemoteEventCodec.toAgentEvent(remoteEvent)
                                                .ifPresent(
                                                        ae ->
                                                                emitter.emit(
                                                                        tagRemoteForwardedEvent(
                                                                                ae,
                                                                                sourcePath,
                                                                                taskId,
                                                                                parentSessionId))));
            }

            long deadlineMs = System.currentTimeMillis() + Math.max(timeoutMs, 0L);
            while (true) {
                long remaining = deadlineMs - System.currentTimeMillis();
                if (remaining <= 0) {
                    if (forceSync) {
                        taskRepository.cancelTask(runtimeContext, parentSessionId, taskId);
                        log.info(
                                "agent remote sync timeout after {}ms under force_sync"
                                        + " (cancelled): agentId={}, taskId={}",
                                timeoutMs,
                                agentId,
                                taskId);
                        return ToolResultBlock.text(
                                        header + "\n" + formatForceSyncTimeout(timeoutMs))
                                .withState(ToolResultState.ERROR);
                    }
                    return ToolResultBlock.success(
                            header + "\nstatus: timeout\ntask_id: " + taskId);
                }
                long slice = Math.min(REMOTE_CONFIRM_POLL_MS, remaining);
                boolean done = bgTask.waitForCompletion(slice);

                try {
                    RemoteTaskStatus st = transport.getStatus(target, taskId);
                    if (st.isAwaitingConfirm()) {
                        boolean shouldAutoDeny =
                                emitter == null
                                        || decl.getRemoteAskPolicy() == RemoteAskPolicy.DENY;
                        if (shouldAutoDeny && resumedEpisode.compareAndSet(false, true)) {
                            List<RemotePendingConfirm> pending =
                                    st.pendingConfirms() != null ? st.pendingConfirms() : List.of();
                            List<RemoteConfirmDecision> decisions = new ArrayList<>(pending.size());
                            for (RemotePendingConfirm p : pending) {
                                decisions.add(new RemoteConfirmDecision(p.getToolCallId(), false));
                            }
                            if (!decisions.isEmpty()) {
                                transport.resume(target, taskId, decisions);
                                autoDenied.set(true);
                            }
                        }
                    } else {
                        resumedEpisode.set(false);
                    }
                } catch (Exception e) {
                    log.debug(
                            "Remote status poll during sync wait failed for {}: {}",
                            taskId,
                            e.getMessage());
                }

                if (done) {
                    break;
                }
            }

            TaskStatus ts = bgTask.getTaskStatus();
            if (ts == TaskStatus.FAILED) {
                Exception err = bgTask.getError();
                String msg = err != null ? err.getMessage() : "remote task failed";
                return ToolResultBlock.text(header + "\nstatus: error\nerror: " + msg)
                        .withState(ToolResultState.ERROR);
            }
            if (ts == TaskStatus.CANCELLED) {
                return ToolResultBlock.text(header + "\nstatus: cancelled\ntask_id: " + taskId)
                        .withState(ToolResultState.INTERRUPTED);
            }
            String result = bgTask.getResult();
            StringBuilder sb = new StringBuilder(header).append("\nstatus: ok");
            if (autoDenied.get()) {
                sb.append("\nnote: remote tool confirmation(s) were auto-denied");
            }
            sb.append("\nreply:\n").append(result != null ? result : "");
            return ToolResultBlock.success(sb.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("agent remote sync interrupted: agentId={}", agentId);
            return ToolResultBlock.text(header + "\nstatus: error\nerror: interrupted")
                    .withState(ToolResultState.INTERRUPTED);
        } finally {
            try {
                streamHandle.close();
            } catch (IOException e) {
                log.debug("Closing remote event stream failed: {}", e.getMessage());
            }
        }
    }

    /**
     * 解析此次启动的最终用户可见性决策。
     *
     * <p>优先级由高到低：
     * <ol>
     *   <li>单次调用 {@link RuntimeContext} 中 {@link #CTX_EXPOSE_TO_USER} 对应的值：
     *       允许宿主应用为整次调用强制允许／禁止暴露。
     *   <li>被启动子智能体的 {@link SubagentDeclaration#getExposeToUser()} 策略：
     *       按类型的静态默认值；{@code null} 表示“不指定”。
     *   <li>LLM 提供的 {@code expose_to_user} 工具参数。
     *   <li>以上均未指定时使用 {@code false}。
     * </ol>
     */
    private static boolean resolveExposeToUser(
            Boolean llmParam, Optional<SubagentDeclaration> declOpt, RuntimeContext ctx) {
        if (ctx != null) {
            Boolean override = asBoolean(ctx.get(CTX_EXPOSE_TO_USER));
            if (override != null) {
                return override;
            }
        }
        Boolean declPolicy = declOpt.map(SubagentDeclaration::getExposeToUser).orElse(null);
        if (declPolicy != null) {
            return declPolicy;
        }
        return Boolean.TRUE.equals(llmParam);
    }

    /**
     * 将上下文值（Boolean 或其字符串形式）转换为三态 Boolean。
     */
    private static Boolean asBoolean(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s.trim());
        }
        return null;
    }

    private static long resolveTimeoutMs(Integer timeoutSeconds, int defaultSeconds) {
        if (timeoutSeconds == null) {
            return (long) defaultSeconds * 1_000;
        }
        if (timeoutSeconds <= 0) {
            return 0L;
        }
        return (long) Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS) * 1_000;
    }

    /**
     * 当前调用是否启用 {@link #CTX_FORCE_SYNC}。
     */
    static boolean isForceSync(RuntimeContext ctx) {
        if (ctx == null) {
            return false;
        }
        return Boolean.TRUE.equals(asBoolean(ctx.get(CTX_FORCE_SYNC)));
    }

    /**
     * 解析当前调用实际采用的同步等待时间。
     *
     * <p>force-sync 下的优先级由高到低：
     * <ol>
     *   <li>存在 {@link #CTX_FORCE_SYNC_TIMEOUT_SECONDS} 时使用它，作为应用的绝对覆盖值
     *   <li>LLM 的 {@code timeout_seconds}，其中 {@code 0} 转为默认同步超时
     * </ol>
     *
     * <p>未启用 force-sync 时，行为与 {@link #resolveTimeoutMs} 一致
     * （包括表示异步的 {@code 0}）。
     */
    static long resolveEffectiveTimeoutMs(Integer timeoutSeconds, RuntimeContext ctx) {
        boolean forceSync = isForceSync(ctx);
        if (forceSync) {
            Integer override = forceSyncTimeoutSeconds(ctx);
            if (override != null) {
                int seconds = override <= 0 ? DEFAULT_TIMEOUT_SECONDS : override;
                return (long) Math.min(seconds, MAX_TIMEOUT_SECONDS) * 1_000;
            }
        }
        long timeoutMs = resolveTimeoutMs(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS);
        if (forceSync && timeoutMs == 0L) {
            return (long) DEFAULT_TIMEOUT_SECONDS * 1_000;
        }
        return timeoutMs;
    }

    /**
     * 读取 {@link #CTX_FORCE_SYNC_TIMEOUT_SECONDS}；{@code null} 表示“不覆盖”。
     */
    static Integer forceSyncTimeoutSeconds(RuntimeContext ctx) {
        if (ctx == null) {
            return null;
        }
        return asPositiveOrZeroInt(ctx.get(CTX_FORCE_SYNC_TIMEOUT_SECONDS));
    }

    /**
     * 将上下文值（{@link Number} 或数字字符串）转换为 Integer；空白／无效值返回 null。
     */
    private static Integer asPositiveOrZeroInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 包装 {@code Mono<ToolResultBlock>}，在 {@code subagentId} 非 null 时向父级
     * 事件流发出 {@link SubagentExposedEvent}。subagentId 为 null（不暴露）时，
     * 原样返回原始 Mono。
     */
    private static Mono<ToolResultBlock> withSubagentExposedEvent(
            Mono<ToolResultBlock> source,
            String subagentId,
            String agentId,
            String sessionId,
            String label) {
        if (subagentId == null) {
            return source;
        }
        return Mono.deferContextual(
                ctx -> {
                    AgentEventEmitter.fromContext(ctx)
                            .ifPresent(
                                    emitter ->
                                            emitter.emit(
                                                    new SubagentExposedEvent(
                                                            subagentId,
                                                            agentId,
                                                            sessionId,
                                                            label)));
                    return source;
                });
    }

    private static String formatSpawnInfo(
            String key, String agentId, String sessionId, String subagentId) {
        StringBuilder sb = new StringBuilder();
        sb.append("agent_key: ").append(key).append("\n");
        sb.append("agent_id: ").append(agentId).append("\n");
        sb.append("session_id: ").append(sessionId);
        if (subagentId != null) {
            sb.append("\nsubagent_id: ").append(subagentId);
            sb.append("\nstatus: exposed (user can send messages directly via subagent_id)");
        }
        return sb.toString();
    }

    /**
     * 对先前启动或复用的子智能体执行任务。从 {@code agentSpawn} 提取出来，
     * 用于处理确定性键复用路径，避免重复同步／异步／远程分派逻辑。
     */
    private Mono<ToolResultBlock> execSpawnTask(
            SpawnedAgent spawned,
            RuntimeContext runtimeContext,
            AgentState parentState,
            String spawnInfo,
            String task,
            Integer timeoutSeconds,
            Optional<SubagentDeclaration> declOpt) {
        boolean forceSync = isForceSync(runtimeContext);
        long timeoutMs = resolveEffectiveTimeoutMs(timeoutSeconds, runtimeContext);
        String currentUserId = runtimeContext != null ? runtimeContext.getUserId() : null;
        String parentSessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
        DefaultAgentManager manager = managerFor(runtimeContext);
        boolean remote = declOpt.map(SubagentDeclaration::isRemote).orElse(false);

        if (timeoutMs == 0) {
            String taskId = "task_" + UUID.randomUUID();
            final String capturedTask = task;
            TaskRunSpec spec;
            if (remote) {
                SubagentDeclaration d = declOpt.get();
                spec =
                        new TaskRunSpec.RemoteTaskRunSpec(
                                d.getUrl(),
                                d.getHeaders(),
                                spawned.agentId(),
                                capturedTask,
                                buildRemoteSubmitContext(runtimeContext, parentState, d));
            } else {
                spec =
                        new TaskRunSpec.LocalTaskRunSpec(
                                () -> {
                                    Msg reply =
                                            manager.invokeAgent(
                                                            spawned.agent(),
                                                            spawned.sessionId(),
                                                            currentUserId,
                                                            capturedTask,
                                                            runtimeContext)
                                                    .block();
                                    return reply != null ? reply.getTextContent() : "";
                                });
            }
            taskRepository.putTask(
                    runtimeContext, taskId, spawned.agentId(), parentSessionId, spec);
            return Mono.just(
                    ToolResultBlock.success(
                            spawnInfo
                                    + "\n"
                                    + String.format(BG_RESULT_TEMPLATE, taskId, taskId, taskId)));
        }

        if (remote) {
            final String finalTask = task;
            return runRemoteSyncReactive(
                    runtimeContext,
                    parentState,
                    spawnInfo,
                    spawned.agentId(),
                    parentSessionId,
                    declOpt.get(),
                    finalTask.trim(),
                    timeoutMs,
                    forceSync);
        }

        final String finalTask = task.trim();
        final String finalSpawnInfo = spawnInfo;
        return execWithTimeoutPromotion(
                spawned.agent(),
                spawned.sessionId(),
                currentUserId,
                finalTask,
                spawned,
                runtimeContext,
                finalSpawnInfo,
                timeoutMs,
                spawned.agentId(),
                forceSync);
    }

    private static LabelKey labelKey(RuntimeContext runtimeContext, String label) {
        return new LabelKey(
                runtimeContext != null ? runtimeContext.getUserId() : null,
                runtimeContext != null ? runtimeContext.getSessionId() : null,
                label.toLowerCase(Locale.ROOT));
    }

    /**
     * 根据 (userId, parentSessionId, agentId, label) 派生确定性的 12 字符十六进制哈希。
     * 长度前缀可区分 null 值与内嵌分隔符，而不改变外部键格式。
     */
    static String deterministicHash(
            String userId, String parentSessionId, String agentId, String label) {
        return SessionIdUtils.deterministicHash(
                identityPart(userId),
                identityPart(parentSessionId),
                identityPart(agentId),
                identityPart(label));
    }

    private static String identityPart(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }

    private static void propagateParentDenyRules(
            AgentState parentState,
            String userId,
            String childSessionId,
            Agent childAgent,
            Optional<SubagentDeclaration> declaration) {
        boolean inherit =
                declaration.map(SubagentDeclaration::isInheritParentPermissions).orElse(true);
        if (!inherit || parentState == null) {
            return;
        }

        PermissionContextState parentPermissions = parentState.getPermissionContext();
        if (parentPermissions == null || parentPermissions.getDenyRules().isEmpty()) {
            return;
        }

        if (childAgent instanceof HarnessAgent harnessAgent) {
            mergeParentDenyRulesIntoSlot(
                    userId, childSessionId, harnessAgent.getDelegate(), parentPermissions);
        } else if (childAgent instanceof ReActAgent reactAgent) {
            mergeParentDenyRulesIntoSlot(userId, childSessionId, reactAgent, parentPermissions);
        }
    }

    private static void mergeParentDenyRulesIntoSlot(
            String userId,
            String childSessionId,
            ReActAgent child,
            PermissionContextState parentPermissions) {
        PermissionContextState childPermissions =
                child.getAgentState(userId, childSessionId).getPermissionContext();
        PermissionContextState merged = mergeParentDenyRules(childPermissions, parentPermissions);
        if (!merged.equals(childPermissions)) {
            child.replacePermissionContext(userId, childSessionId, merged);
        }
    }

    /**
     * 添加父级 DENY 规则，不扩大子级配置的权限。
     *
     * <p>简单权限配置的子级使用旧版轻量级权限路径，允许工具级
     * {@code PASSTHROUGH}。添加首条 DENY 规则会使上下文不再简单，从而激活完整引擎；
     * {@link PermissionMode#BYPASS} 保留先前的回退行为，而显式 DENY 规则仍然优先。
     */
    private static PermissionContextState mergeParentDenyRules(
            PermissionContextState child, PermissionContextState parent) {
        PermissionContextState.Builder merged =
                PermissionContextState.builder()
                        .mode(child.isTrivial() ? PermissionMode.BYPASS : child.getMode());

        child.getWorkingDirectories().forEach(merged::addWorkingDirectory);
        child.getAllowRules()
                .forEach(
                        (toolName, rules) ->
                                rules.forEach(rule -> merged.addAllowRule(toolName, rule)));

        Map<String, List<PermissionRule>> denyRules = new LinkedHashMap<>();
        child.getDenyRules()
                .forEach((toolName, rules) -> denyRules.put(toolName, new ArrayList<>(rules)));
        parent.getDenyRules()
                .forEach(
                        (toolName, rules) -> {
                            List<PermissionRule> targetRules =
                                    denyRules.computeIfAbsent(
                                            toolName, ignored -> new ArrayList<>());
                            for (PermissionRule rule : rules) {
                                if (!targetRules.contains(rule)) {
                                    targetRules.add(rule);
                                }
                            }
                        });
        denyRules.forEach(
                (toolName, rules) -> rules.forEach(rule -> merged.addDenyRule(toolName, rule)));

        child.getAskRules()
                .forEach(
                        (toolName, rules) ->
                                rules.forEach(rule -> merged.addAskRule(toolName, rule)));
        return merged.build();
    }
}
