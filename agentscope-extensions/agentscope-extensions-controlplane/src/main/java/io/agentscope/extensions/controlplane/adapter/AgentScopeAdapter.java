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
package io.agentscope.extensions.controlplane.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.PlanModeContextState;
import io.agentscope.core.state.Task;
import io.agentscope.core.state.ToolContextState;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.controlplane.FrameworkAdapter;
import io.agentscope.extensions.controlplane.SessionBridge;
import io.agentscope.extensions.controlplane.model.ContextSnapshot;
import io.agentscope.extensions.controlplane.model.Inventory;
import io.agentscope.extensions.controlplane.model.MessagePage;
import io.agentscope.extensions.controlplane.model.SessionEvent;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import reactor.core.publisher.Mono;

/**
 * 自行部署的 AgentScope Java 智能体旁路观察适配器。
 *
 * <p>这是 BYO 路径：用户编写并运行自己的智能体，控制平面从旁观察。它不同于托管智能体模式，后者由拥有运行时的数据平面服务直接实现 {@code /agentscope/*} 契约。
 *
 * <p>通过 {@link ControlPlaneObserverMiddleware} 观察，原样传递所有输入和事件。从 {@link AgentState} 读取上下文与历史；它是后续模型调用所用状态缓冲区的来源，实际有效上下文还要结合提示组合等路径理解。
 *
 * <p>{@code ReActAgent} 构建时固定中间件，因此事件流要求构建时注册 {@link #middleware()}：
 * <pre>{@code
 * AgentScopeAdapter adapter = new AgentScopeAdapter();
 * ReActAgent agent = ReActAgent.builder()
 *         .middleware(adapter.middleware())
 *         .build();
 * SessionBridge bridge = ControlPlane.instrument(agent, config, adapter);
 * }</pre>
 *
 * <p>已经构建的智能体仍可接入观测，快照、上下文、历史与命令都读取实时状态，但不产生二级事件。
 */
public final class AgentScopeAdapter implements FrameworkAdapter {

    private static final Logger LOG = Logger.getLogger(AgentScopeAdapter.class.getName());

    public static final String FRAMEWORK = "agentscope-java";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * {@code ConversationCompactor} 为注入的摘要消息指定的名称。按值匹配，而不导入此处可选的 {@code agentscope-harness}。
     */
    private static final String COMPACTION_SUMMARY_NAME = "__compaction_summary__";

    private final SessionCompactor compactor;
    private final ControlPlaneObserverMiddleware middleware;
    private volatile SessionHistorySource historySource;
    private volatile AgentRuntimeSource runtimeSource;
    private volatile AgentTaskStarter agentTaskStarter;

    /**
 * 已观察到的会话，映射到其状态所在的用户槽位。
 */
    private final Map<String, String> sessionUsers = new ConcurrentHashMap<>();

    private volatile Agent agent;
    private volatile Consumer<SessionEvent> emit;
    private volatile SessionBridge bridge;

    public AgentScopeAdapter() {
        this(null);
    }

    /**
     * @param compactor 处理 {@code compress}；{@code null} 表示不支持此命令
     */
    public AgentScopeAdapter(SessionCompactor compactor) {
        this.compactor = compactor;
        this.middleware = new ControlPlaneObserverMiddleware(this);
    }

    /**
 * 在智能体构建器注册此中间件，以启用二级事件流。
 */
    public ControlPlaneObserverMiddleware middleware() {
        return middleware;
    }

    /**
     * 可选完整历史读取器（例如 Harness {@code .log.jsonl}）。适配器 bean 创建后，待 {@code HarnessAgent} 存在时设置即可。
     */
    public void setHistorySource(SessionHistorySource historySource) {
        this.historySource = historySource;
    }

    /**
     * 可选 Harness／应用钩子，用于资源清单、计划文件、子智能体任务和 Definition 补充。
     */
    public void setRuntimeSource(AgentRuntimeSource runtimeSource) {
        this.runtimeSource = runtimeSource;
    }

    /**
 * 安装将 ASDP AgentTask 投递物化的宿主钩子。
 */
    public void setAgentTaskStarter(AgentTaskStarter agentTaskStarter) {
        this.agentTaskStarter = agentTaskStarter;
    }

    // ─── 身份 ───

    @Override
    public String frameworkName() {
        return FRAMEWORK;
    }

    @Override
    public String frameworkVersion() {
        String version = Agent.class.getPackage().getImplementationVersion();
        return version == null ? "" : version;
    }

    @Override
    public boolean canHandle(Object target) {
        return target instanceof Agent;
    }

    @Override
    public Set<String> capabilities() {
        Set<String> caps = new java.util.TreeSet<>();
        caps.add(CAP_CONTEXT_QUERY);
        caps.add(CAP_MESSAGE_QUERY);
        caps.add(CAP_SESSION_COMMAND);
        caps.add(CAP_SESSION_ABORT);
        caps.add(CAP_TASK_QUERY);
        caps.add(CAP_PLAN_MODE);
        caps.add(CAP_EXPORT_TRANSCRIPT);
        caps.add(CAP_CONVERSATION_INBOUND);
        if (runtimeSource != null) {
            caps.add(CAP_SUBAGENT_INVENTORY);
            caps.add(CAP_WORKSPACE_INVENTORY);
            caps.add(CAP_SUBAGENT_TASK_QUERY);
            caps.add(CAP_SUBAGENT_TASK_COMMAND);
        }
        if (agentTaskStarter != null) {
            caps.add(CAP_AGENT_TASK);
            if (agentTaskStarter instanceof HarnessAgentTaskStarter starter
                    && starter.consumesWorkspaceDefinition()) caps.add("workspace-definition-v1");
        }
        return caps;
    }

    // ─── 挂载 ───

    @Override
    public void attach(Object target, Consumer<SessionEvent> emit) {
        this.agent = (Agent) target;
        this.emit = emit;
    }

    @Override
    public void detach() {
        this.agent = null;
        this.emit = null;
        this.bridge = null;
        sessionUsers.clear();
    }

    @Override
    public void onBridgeAttached(SessionBridge bridge) {
        this.bridge = bridge;
    }

    // ─── 由观察中间件调用 ───

    Agent agent() {
        return agent;
    }

    void publishCommitted(String source, long seq, SessionEvent event) {
        SessionBridge attached = bridge;
        if (attached == null) throw new IllegalStateException("ControlPlane bridge not attached");
        attached.onCommittedEvent(source, seq, event);
    }

    void publish(SessionEvent event) {
        Consumer<SessionEvent> sink = emit;
        if (sink == null) {
            return;
        }
        try {
            sink.accept(event);
        } catch (RuntimeException e) {
            // 旁路原则：上报绝不干扰对话。
            LOG.log(Level.FINE, "controlplane: event publish failed", e);
        }
    }

    /**
     * 记录会话，并用提示、工具、上下文窗口和模型初始化跟踪器；这些信息不由事件流自身携带。
     */
    void rememberSession(String sessionId, String userId, Agent observed) {
        sessionUsers.put(sessionId, userId == null ? "" : userId);
        SessionBridge target = bridge;
        if (target == null) {
            return;
        }
        String systemPrompt = sysPromptOf(observed);
        int contextWindow = contextWindowOf(observed);
        String modelName = null;
        ReActAgent react = reactOf(observed);
        if (react != null && react.getModel() != null) {
            try {
                modelName = react.getModel().getModelName();
            } catch (RuntimeException e) {
                modelName = null;
            }
        }
        target.describeSession(
                sessionId, systemPrompt, toolsOf(observed), contextWindow, modelName);
    }

    /**
     * 标记 {@code sessionId} 是否正在执行轮次。观察中间件在每次 {@code call()} 前后调用，使会话列表／状态能报告 {@code busy}。轮次结束时刷新上下文占用词元量，让压力反映当前窗口，而非生命周期累计用量。
     */
    void markBusy(String sessionId, boolean busy) {
        SessionBridge target = bridge;
        if (target != null) {
            target.setBusy(sessionId, busy);
        }
        if (!busy) {
            refreshContextPressure(sessionId);
        }
    }

    /**
 * 为会话 Context 保存最近一次中间件组合后的系统提示。
 */
    void recordEffectiveSystemPrompt(String sessionId, String prompt) {
        SessionBridge target = bridge;
        if (target != null) {
            target.setEffectiveSystemPrompt(sessionId, prompt);
        }
    }

    private void refreshContextPressure(String sessionId) {
        SessionBridge target = bridge;
        if (target == null) {
            return;
        }
        try {
            Agent agent = this.agent;
            if (agent == null) {
                return;
            }
            AgentState state = resolveState(agent, sessionId);
            if (state == null) {
                return;
            }
            target.setContextUsedTokens(sessionId, windowUsedTokens(state.getContext()));
        } catch (RuntimeException e) {
            LOG.log(
                    Level.FINE,
                    "controlplane: context pressure refresh failed for " + sessionId,
                    e);
        }
    }

    /**
     * 估算当前占用模型上下文窗口的词元数。
     *
     * <p>每条消息的 {@link ChatUsage#getInputTokens()} 已包含该轮完整提示（先前历史加新词元），跨消息求和会重复计算历史。最近一个非零输入大小是当前窗口占用的最佳可用近似。
     */
    static int windowUsedTokens(List<Msg> context) {
        if (context == null || context.isEmpty()) {
            return 0;
        }
        int used = 0;
        for (Msg msg : context) {
            ChatUsage usage = chatUsageOf(msg);
            if (usage == null) {
                continue;
            }
            int input = usage.getInputTokens();
            if (input > 0) {
                used = input;
            }
        }
        return used;
    }

    private static ChatUsage chatUsageOf(Msg msg) {
        if (msg == null) {
            return null;
        }
        ChatUsage usage = msg.getChatUsage();
        return usage != null ? usage : msg.getUsage();
    }

    boolean isKnownSession(String sessionId) {
        return sessionUsers.containsKey(sessionId);
    }

    // ─── 第四级：有效上下文 ───

    @Override
    public Mono<ContextSnapshot> extractContext(String sessionId) {
        return Mono.fromCallable(
                () -> {
                    Agent target = requireAgent();
                    AgentState state = resolveState(target, sessionId);
                    List<Msg> context = state == null ? List.of() : state.getContext();

                    List<ContextSnapshot.ContextMessage> messages = new ArrayList<>(context.size());
                    String compactionSummary = "";
                    for (Msg msg : context) {
                        boolean isSummary = COMPACTION_SUMMARY_NAME.equals(msg.getName());
                        String text = textOf(msg);
                        if (isSummary) {
                            compactionSummary = text;
                        }
                        messages.add(
                                new ContextSnapshot.ContextMessage(roleOf(msg), text, isSummary));
                    }

                    // 窗口占用量，而非生命周期累计用量；参见 windowUsedTokens()。
                    int totalTokens = windowUsedTokens(context);

                    String basePrompt = sysPromptOf(target);
                    String effectivePrompt = basePrompt;
                    SessionBridge b = bridge;
                    if (b != null) {
                        String sampled = b.effectiveSystemPrompt(sessionId);
                        if (sampled != null && !sampled.isEmpty()) {
                            effectivePrompt = sampled;
                        }
                    }

                    List<String> activatedGroups = List.of();
                    if (state != null && state.getToolContext() != null) {
                        List<String> groups = state.getToolContext().getActivatedGroups();
                        if (groups != null) {
                            activatedGroups = groups;
                        }
                    }

                    Map<String, Object> frameworkState =
                            buildFrameworkState(
                                    target, sessionId, state, basePrompt, activatedGroups);

                    return ContextSnapshot.builder(sessionId)
                            .systemPrompt(effectivePrompt)
                            .messages(messages)
                            .tools(toolsOf(target, activatedGroups))
                            .compacted(!compactionSummary.isEmpty())
                            .compactionSummary(compactionSummary)
                            .totalTokens(totalTokens)
                            .maxTokens(contextWindowOf(target))
                            .framework(FRAMEWORK)
                            .model(modelNameOf(target))
                            .frameworkState(toJsonBytes(frameworkState))
                            .build();
                });
    }

    private Map<String, Object> buildFrameworkState(
            Agent target,
            String sessionId,
            AgentState state,
            String basePrompt,
            List<String> activatedGroups) {
        Map<String, Object> fs = new LinkedHashMap<>();
        fs.put("baseSystemPrompt", basePrompt == null ? "" : basePrompt);
        fs.put("activatedGroups", activatedGroups);
        SessionBridge b = bridge;
        boolean hasEffective = b != null && !b.effectiveSystemPrompt(sessionId).isEmpty();
        fs.put("systemPromptSource", hasEffective ? "effective" : "base");

        PlanModeContextState plan = state == null ? null : state.getPlanModeContext();
        boolean planActive = plan != null && plan.isPlanActive();
        fs.put("planActive", planActive);
        String planFile = plan == null ? null : plan.getCurrentPlanFile();
        if (planFile != null && !planFile.isEmpty()) {
            fs.put("currentPlanFile", planFile);
            AgentRuntimeSource src = runtimeSource;
            if (src != null) {
                src.readPlanExcerpt(target, sessionId, sessionUsers.get(sessionId), planFile)
                        .ifPresent(excerpt -> fs.put("planExcerpt", excerpt));
            }
        }

        if (state != null && state.getToolContext() != null) {
            Map<String, ToolContextState.SpawnEntry> spawns =
                    state.getToolContext().getSpawnRegistry();
            if (spawns != null && !spawns.isEmpty()) {
                List<Map<String, Object>> spawnList = new ArrayList<>();
                for (Map.Entry<String, ToolContextState.SpawnEntry> e : spawns.entrySet()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key", e.getKey());
                    ToolContextState.SpawnEntry entry = e.getValue();
                    if (entry != null) {
                        row.put("agentId", entry.agentId());
                        row.put("label", entry.label());
                        row.put("sessionId", entry.sessionId());
                        row.put("depth", entry.depth());
                    }
                    spawnList.add(row);
                }
                fs.put("spawns", spawnList);
            }
        }
        return fs;
    }

    private static byte[] toJsonBytes(Map<String, Object> value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            return "{}".getBytes(StandardCharsets.UTF_8);
        }
    }

    // ─── 第三级：完整历史 ───

    @Override
    public Mono<MessagePage> listMessages(String sessionId, int offset, int limit) {
        return Mono.fromCallable(
                () -> {
                    SessionHistorySource history = historySource;
                    if (history != null) {
                        String userId = sessionUsers.getOrDefault(sessionId, "");
                        var fromLog = history.loadMessages(sessionId, userId);
                        if (fromLog.isPresent()) {
                            return MessagePage.of(sessionId, fromLog.get(), offset, limit);
                        }
                    }
                    AgentState state = resolveState(requireAgent(), sessionId);
                    List<Msg> context = state == null ? List.<Msg>of() : state.getContext();
                    List<MessagePage.MessageItem> items = new ArrayList<>(context.size());
                    int seq = 0;
                    for (Msg msg : context) {
                        seq++;
                        ToolUseBlock use = firstBlock(msg, ToolUseBlock.class);
                        ToolResultBlock result = firstBlock(msg, ToolResultBlock.class);
                        long occurred = parseMsgTimestamp(msg.getTimestamp());
                        items.add(
                                new MessagePage.MessageItem(
                                        seq,
                                        roleOf(msg),
                                        textOf(msg),
                                        use != null
                                                ? use.getName()
                                                : (result != null ? result.getName() : ""),
                                        use != null ? use.getInput() : null,
                                        result != null ? blocksText(result.getOutput()) : "",
                                        occurred));
                    }
                    return MessagePage.of(sessionId, items, offset, limit);
                });
    }

    // ─── 命令 ───

    @Override
    public Mono<Void> handleCommand(String sessionId, String command, byte[] params) {
        if (COMMAND_TERMINATE.equals(command) || COMMAND_ABORT.equals(command)) {
            return Mono.fromRunnable(() -> interruptSession(sessionId));
        }
        if (COMMAND_COMPRESS.equals(command)) {
            if (compactor == null) {
                return Mono.error(
                        new UnsupportedOperationException(
                                "agentscope-java: compress requires a SessionCompactor"));
            }
            return Mono.defer(
                    () -> {
                        AgentState state = resolveState(requireAgent(), sessionId);
                        if (state == null) {
                            return Mono.error(
                                    new IllegalStateException(
                                            "agentscope-java: no state for session " + sessionId));
                        }
                        return compactor
                                .compact(sessionId, state)
                                .doOnSuccess(
                                        ignored ->
                                                publish(
                                                        SessionEvent.builder(
                                                                        sessionId,
                                                                        SessionEvent.COMPACTION)
                                                                .content(summaryOf(state))
                                                                .build()));
                    });
        }
        return Mono.error(new IllegalArgumentException("unsupported command: " + command));
    }

    @Override
    public Mono<Void> handleAgentTask(
            io.agentscope.extensions.controlplane.model.AgentTaskAssignment assignment) {
        AgentTaskStarter starter = agentTaskStarter;
        if (starter == null) {
            return Mono.error(
                    new UnsupportedOperationException(
                            "agentscope-java: AgentTask requires setAgentTaskStarter"));
        }
        String sessionId = assignment.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = assignment.agentTaskId();
        }
        rememberSession(sessionId, "", requireAgent());
        return starter.start(assignment);
    }

    @Override
    public Mono<Void> injectUserMessage(String sessionId, String content) {
        return callUserMessage(sessionId, content).then();
    }

    @Override
    public Mono<String> runConversationTurn(String sessionId, String content) {
        return callUserMessage(sessionId, content).map(Msg::getTextContent);
    }

    private Mono<Msg> callUserMessage(String sessionId, String content) {
        return Mono.defer(
                () -> {
                    Agent target = requireAgent();
                    rememberSession(sessionId, sessionUsers.getOrDefault(sessionId, ""), target);
                    Msg msg = Msg.builder().role(MsgRole.USER).textContent(content).build();
                    if (target instanceof HarnessAgent harness) {
                        RuntimeContext rc = RuntimeContext.builder().sessionId(sessionId).build();
                        return harness.call(msg, rc);
                    }
                    return target.call(msg);
                });
    }

    // ─── 任务 ───

    @Override
    public Mono<List<Map<String, Object>>> listTasks(String sessionId) {
        return Mono.fromCallable(
                () -> {
                    AgentState state = resolveState(requireAgent(), sessionId);
                    if (state == null || state.getTasksContext() == null) {
                        return List.of();
                    }
                    List<Task> tasks = state.getTasksContext().getTasks();
                    List<Map<String, Object>> out = new ArrayList<>(tasks.size());
                    for (Task task : tasks) {
                        out.add(toTaskJson(task));
                    }
                    return out;
                });
    }

    private static Map<String, Object> toTaskJson(Task task) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", task.getId());
        out.put("subject", task.getSubject());
        if (task.getDescription() != null && !task.getDescription().isEmpty()) {
            out.put("description", task.getDescription());
        }
        out.put(
                "state",
                task.getState() == null ? Task.State.PENDING.getWire() : task.getState().getWire());
        if (task.getOwner() != null && !task.getOwner().isEmpty()) {
            out.put("owner", task.getOwner());
        }
        List<String> blockedBy = task.getBlockedBy();
        if (blockedBy != null && !blockedBy.isEmpty()) {
            out.put("blockedBy", List.copyOf(blockedBy));
        } else {
            out.put("blockedBy", List.of());
        }
        if (task.getCreatedAt() != null && !task.getCreatedAt().isEmpty()) {
            out.put("updatedAt", task.getCreatedAt());
        }
        out.put(
                "frameworkMeta",
                task.getMetadata() == null || task.getMetadata().isEmpty()
                        ? Map.of()
                        : task.getMetadata());
        return out;
    }

    // ─── 资源清单／定义／计划／子智能体任务 ───

    @Override
    public Mono<List<Inventory.SubagentInfo>> listSubagents() {
        return Mono.fromCallable(
                () -> {
                    AgentRuntimeSource src = runtimeSource;
                    if (src == null) {
                        throw new UnsupportedOperationException(
                                "subagent-inventory is not supported by this adapter");
                    }
                    return src.listSubagents(requireAgent());
                });
    }

    @Override
    public Mono<List<Inventory.WorkspaceInfo>> listWorkspaces() {
        return Mono.fromCallable(
                () -> {
                    AgentRuntimeSource src = runtimeSource;
                    if (src == null) {
                        throw new UnsupportedOperationException(
                                "workspace-inventory is not supported by this adapter");
                    }
                    return src.listWorkspaces(requireAgent());
                });
    }

    @Override
    public Mono<List<Map<String, Object>>> listSubagentTasks(String sessionId) {
        return Mono.fromCallable(
                () -> {
                    AgentRuntimeSource src = runtimeSource;
                    if (src == null) {
                        throw new UnsupportedOperationException(
                                "subagent-task-query is not supported by this adapter");
                    }
                    return src.listSubagentTasks(
                            requireAgent(), sessionId, sessionUsers.get(sessionId));
                });
    }

    @Override
    public Mono<Void> cancelSubagentTask(String sessionId, String taskId) {
        return Mono.fromCallable(
                        () -> {
                            AgentRuntimeSource src = runtimeSource;
                            if (src == null) {
                                throw new UnsupportedOperationException(
                                        "subagent-task-command is not supported by this adapter");
                            }
                            boolean ok =
                                    src.cancelSubagentTask(
                                            requireAgent(),
                                            sessionId,
                                            sessionUsers.get(sessionId),
                                            taskId);
                            if (!ok) {
                                throw new IllegalStateException(
                                        "subagent task not found or cancel failed: " + taskId);
                            }
                            return null;
                        })
                .then();
    }

    @Override
    public Mono<Void> setPlanMode(String sessionId, byte[] params) {
        return Mono.fromCallable(
                        () -> {
                            boolean active = parsePlanActive(params);
                            Agent target = requireAgent();
                            AgentRuntimeSource src = runtimeSource;
                            String userId = sessionUsers.get(sessionId);
                            if (src != null && src.setPlanMode(target, sessionId, userId, active)) {
                                return null;
                            }
                            AgentState state = resolveState(target, sessionId);
                            if (state == null) {
                                throw new IllegalStateException(
                                        "agentscope-java: no state for session " + sessionId);
                            }
                            state.getPlanModeContext().setPlanActive(active);
                            saveState(target, userId, sessionId);
                            return null;
                        })
                .then();
    }

    private static boolean parsePlanActive(byte[] params) {
        if (params == null || params.length == 0) {
            return true;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = JSON.readValue(params, Map.class);
            Object active = map.get("active");
            if (active instanceof Boolean b) {
                return b;
            }
            if (active != null) {
                return Boolean.parseBoolean(active.toString());
            }
            Object mode = map.get("mode");
            if (mode != null) {
                String m = mode.toString().toLowerCase();
                return !"exit".equals(m) && !"off".equals(m) && !"false".equals(m);
            }
        } catch (Exception e) {
            // 按进入模式处理
        }
        return true;
    }

    private static void saveState(Agent target, String userId, String sessionId) {
        ReActAgent react = reactOf(target);
        if (react == null) {
            return;
        }
        try {
            react.saveAgentState(userId, sessionId);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "controlplane: saveAgentState failed", e);
        }
    }

    @Override
    public Map<String, Object> buildAgentConfig() {
        Agent target = agent;
        if (target == null) {
            return Map.of();
        }
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("name", target.getName() == null ? "" : target.getName());
        String description = target.getDescription();
        if (description != null && !description.isEmpty()) {
            cfg.put("description", description);
        }
        String sysPrompt = sysPromptOf(target);
        if (!sysPrompt.isEmpty()) {
            cfg.put(
                    "systemPrompt",
                    sysPrompt.length() > 8000 ? sysPrompt.substring(0, 8000) + "…" : sysPrompt);
        }
        String model = modelNameOf(target);
        if (!model.isEmpty()) {
            cfg.put("model", model);
        }
        String provider = modelProviderOf(target);
        if (!provider.isEmpty()) {
            cfg.put("modelProvider", provider);
        }
        ReActAgent react = reactOf(target);
        if (react != null) {
            cfg.put("maxIters", react.getMaxIters());
            cfg.put("maxTurns", react.getMaxIters());
        }
        List<ContextSnapshot.ToolInfo> tools = toolsOf(target);
        List<Map<String, Object>> toolRows = new ArrayList<>(tools.size());
        for (ContextSnapshot.ToolInfo t : tools) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", t.name());
            if (t.description() != null && !t.description().isEmpty()) {
                row.put("description", t.description());
            }
            toolRows.add(row);
        }
        cfg.put("tools", toolRows);
        cfg.put("sources", List.of("builder"));
        AgentRuntimeSource src = runtimeSource;
        if (src != null) {
            src.enrichAgentConfig(target, cfg);
        }
        return cfg;
    }

    // ─── 状态解析 ───

    private Agent requireAgent() {
        Agent target = agent;
        if (target == null) {
            throw new IllegalStateException("agentscope-java: adapter is not attached");
        }
        return target;
    }

    /**
     * 解析 {@code sessionId} 的状态槽位。{@code ReActAgent} 以 {@code (userId, sessionId)} 索引状态，因此并发下必须使用首次看到会话时记录的用户，才能访问正确槽位。
     */
    private AgentState resolveState(Agent target, String sessionId) {
        ReActAgent react = reactOf(target);
        if (react != null) {
            String userId = sessionUsers.get(sessionId);
            return react.getAgentState(userId, sessionId);
        }
        return target.getAgentState();
    }

    /**
     * 返回持有每会话状态的 {@code ReActAgent}，解开一层委托。Harness 智能体包装内部 {@code ReActAgent}，并通过 {@code getDelegate()} 暴露；此处反射解析，避免在适配路径直接依赖 agentscope-harness。若不解包，包装智能体会回退到单一默认状态槽位，只要有多个会话就会读取错误会话。
     */
    private static ReActAgent reactOf(Agent target) {
        if (target instanceof ReActAgent react) {
            return react;
        }
        if (target == null) {
            return null;
        }
        try {
            Object delegate = target.getClass().getMethod("getDelegate").invoke(target);
            return delegate instanceof ReActAgent react ? react : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.log(Level.FINE, "controlplane: no ReActAgent delegate on " + target.getClass(), e);
            return null;
        }
    }

    private static String sysPromptOf(Agent target) {
        ReActAgent react = reactOf(target);
        return react == null ? "" : react.getSysPrompt();
    }

    private static String modelNameOf(Agent target) {
        ReActAgent react = reactOf(target);
        if (react != null && react.getModel() != null) {
            try {
                String name = react.getModel().getModelName();
                return name == null ? "" : name;
            } catch (RuntimeException e) {
                return "";
            }
        }
        return "";
    }

    private static String modelProviderOf(Agent target) {
        ReActAgent react = reactOf(target);
        if (react == null || react.getModel() == null) {
            return "";
        }
        try {
            Object model = react.getModel();
            try {
                Object provider = model.getClass().getMethod("getModelProvider").invoke(model);
                if (provider != null && !provider.toString().isBlank()) {
                    return provider.toString();
                }
            } catch (ReflectiveOperationException ignored) {
                // 继续后续分支
            }
            return model.getClass().getSimpleName();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * 仅中断 {@code sessionId} 中正在执行的轮次。{@code Agent.interrupt()} 触发默认状态槽位；智能体服务多个会话时，这可能错误中止无关会话。
     */
    private void interruptSession(String sessionId) {
        Agent target = requireAgent();
        ReActAgent react = reactOf(target);
        if (react == null) {
            target.interrupt();
            return;
        }
        react.interrupt(sessionUsers.get(sessionId), sessionId);
    }

    private String summaryOf(AgentState state) {
        for (Msg msg : state.getContext()) {
            if (COMPACTION_SUMMARY_NAME.equals(msg.getName())) {
                return textOf(msg);
            }
        }
        String summary = state.getSummary();
        return summary == null ? "" : summary;
    }

    private static int contextWindowOf(Agent target) {
        ReActAgent react = reactOf(target);
        if (react != null && react.getModel() != null) {
            try {
                return react.getModel().getContextWindowSize();
            } catch (RuntimeException e) {
                return 0;
            }
        }
        return 0;
    }

    static List<ContextSnapshot.ToolInfo> toolsOf(Agent target) {
        return toolsOf(target, null);
    }

    static List<ContextSnapshot.ToolInfo> toolsOf(Agent target, List<String> activatedGroups) {
        Toolkit toolkit = target == null ? null : target.getToolkit();
        if (toolkit == null) {
            return List.of();
        }
        List<ToolSchema> schemas;
        try {
            if (activatedGroups == null || activatedGroups.isEmpty()) {
                schemas = toolkit.getToolSchemas();
            } else {
                schemas = toolkit.getToolSchemas(activatedGroups);
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        List<ContextSnapshot.ToolInfo> tools = new ArrayList<>(schemas.size());
        for (ToolSchema schema : schemas) {
            tools.add(
                    new ContextSnapshot.ToolInfo(
                            schema.getName(), schema.getDescription(), schema.getParameters()));
        }
        return tools;
    }

    // ─── Msg 辅助方法 ───

    static String roleOf(Msg msg) {
        if (firstBlock(msg, ToolResultBlock.class) != null) {
            return SessionEvent.ROLE_TOOL;
        }
        MsgRole role = msg.getRole();
        if (role == MsgRole.USER) {
            return SessionEvent.ROLE_USER;
        }
        if (role == MsgRole.SYSTEM) {
            return SessionEvent.ROLE_SYSTEM;
        }
        return SessionEvent.ROLE_ASSISTANT;
    }

    static String textOf(Msg msg) {
        if (msg == null) {
            return "";
        }
        String text = msg.getTextContent();
        if (text != null && !text.isEmpty()) {
            return text;
        }
        ToolResultBlock result = firstBlock(msg, ToolResultBlock.class);
        return result != null ? blocksText(result.getOutput()) : "";
    }

    static String blocksText(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof io.agentscope.core.message.TextBlock text) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(text.getText());
            }
        }
        return sb.toString();
    }

    private static long parseMsgTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(timestamp).toEpochMilli();
        } catch (RuntimeException ignored) {
            // Msg 某些路径使用 "yyyy-MM-dd HH:mm:ss.SSS"，保持未设置而不是失败。
            return 0L;
        }
    }

    static <T extends ContentBlock> T firstBlock(Msg msg, Class<T> type) {
        if (msg == null || msg.getContent() == null) {
            return null;
        }
        for (ContentBlock block : msg.getContent()) {
            if (type.isInstance(block)) {
                return type.cast(block);
            }
        }
        return null;
    }
}
