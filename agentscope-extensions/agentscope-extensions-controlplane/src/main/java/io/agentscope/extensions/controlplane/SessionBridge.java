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
package io.agentscope.extensions.controlplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.controlplane.proto.ConversationTurnCommand;
import io.agentscope.controlplane.proto.ConversationTurnReport;
import io.agentscope.controlplane.proto.EventReportAck;
import io.agentscope.controlplane.proto.ExecutionAttemptCommand;
import io.agentscope.controlplane.proto.ExecutionAttemptReport;
import io.agentscope.controlplane.proto.SessionEventMsg;
import io.agentscope.controlplane.proto.SessionSnapshot;
import io.agentscope.extensions.controlplane.model.AgentTaskAssignment;
import io.agentscope.extensions.controlplane.model.ContextSnapshot;
import io.agentscope.extensions.controlplane.model.ContextTracker;
import io.agentscope.extensions.controlplane.model.Inventory;
import io.agentscope.extensions.controlplane.model.MessagePage;
import io.agentscope.extensions.controlplane.model.SessionEvent;
import io.agentscope.extensions.controlplane.transport.ContractHttpServer;
import io.agentscope.extensions.controlplane.transport.ContractProvider;
import io.agentscope.extensions.controlplane.transport.GrpcTransport;
import io.agentscope.extensions.controlplane.transport.HttpSelfRegistration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import reactor.core.publisher.Mono;

/**
 * 位于框架适配器与控制平面之间的上报引擎。
 *
 * <p>负责适配器无需关心的工作：序号分配、二级事件持久发件箱、增量上下文跟踪、一级聚合、去抖后的四级推送、资源清单、两个通道的命令分发以及进程内契约服务器。
 *
 * <p><b>旁路原则：</b>各上报路径自行处理失败，不得传播进智能体对话路径。
 */
public final class SessionBridge implements ContractProvider, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(SessionBridge.class.getName());

    public static final String SDK_VERSION = "0.1.0";

    /**
 * 契约级别 3：发现、会话和命令；更细的功能门控按能力进行。
 */
    public static final int CONTRACT_LEVEL = 3;

    /**
     * 控制平面契约中的阶段词汇。列表之外的值不计入任何分组，否则控制台会报告活动会话数为零。
     */
    private static final String PHASE_ACTIVE = "active";

    private static final String PHASE_IDLE = "idle";
    private static final String PHASE_COMPRESSING = "compressing";
    private static final String PHASE_ARCHIVED = "archived";
    private static final String PHASE_TERMINATED = "terminated";

    private static final long LEVEL1_INTERVAL_MS = 10_000L;
    private static final long EVENT_FLUSH_INTERVAL_MS = 5_000L;
    private static final int EVENT_BATCH_SIZE = 20;
    private static final long EVENT_ACK_TIMEOUT_MS = 15_000L;
    private static final long CONTEXT_PUSH_COOLDOWN_MS = 30_000L;
    private static final long INVENTORY_INTERVAL_MS = 30_000L;
    private static final long ATTEMPT_HEARTBEAT_INTERVAL_MS = 15_000L;
    private static final Duration ADAPTER_CALL_TIMEOUT = Duration.ofSeconds(10);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ControlPlaneConfig config;
    private final Object lock = new Object();

    private final Map<String, ContextTracker> trackers = new ConcurrentHashMap<>();
    private final Map<String, String> phases = new ConcurrentHashMap<>();

    /**
 * 仅在忙碌状态已知时存在（轮次 START/END 或中间件标记），绝不伪造 false。
 */
    private final Map<String, Boolean> busyFlags = new ConcurrentHashMap<>();

    /**
 * 首次与最近活动的纪元毫秒时间，分别上报为 startedAt/lastActiveAt。
 */
    private final Map<String, Long> startedAt = new ConcurrentHashMap<>();

    private final Map<String, Long> lastActiveAt = new ConcurrentHashMap<>();

    private final Map<String, Integer> sequences = new ConcurrentHashMap<>();
    private final Map<String, Long> lastContextPush = new ConcurrentHashMap<>();
    private final EventJournal eventJournal;

    private String inFlightEventReportId;
    private long inFlightEventReportAt;

    private FrameworkAdapter adapter;
    private GrpcTransport grpc;
    private ContractHttpServer http;
    private HttpSelfRegistration httpRegister;
    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService attemptHeartbeatScheduler;
    private volatile boolean started;

    public SessionBridge(ControlPlaneConfig config) {
        this.config = config;
        EventJournal journal = null;
        if (config.enableEvents() && config.startGrpc()) {
            try {
                journal = new EventJournal(config);
                sequences.putAll(journal.latestSequences());
            } catch (IOException e) {
                LOG.log(
                        Level.SEVERE,
                        "controlplane: event journal cannot be opened; event reporting is disabled",
                        e);
            }
        }
        eventJournal = journal;
    }

    // ─── 适配器挂载 ───

    /**
 * 将 {@code adapter} 挂载到 {@code target}，必须在 {@link #start()} 前调用。
 */
    public SessionBridge attach(Object target, FrameworkAdapter adapter) {
        if (!adapter.canHandle(target)) {
            throw new IllegalArgumentException(
                    adapter.frameworkName() + " adapter cannot handle " + target.getClass());
        }
        this.adapter = adapter;
        adapter.attach(target, this::onEvent);
        adapter.onBridgeAttached(this);
        return this;
    }

    public FrameworkAdapter getAdapter() {
        return adapter;
    }

    public ControlPlaneConfig getConfig() {
        return config;
    }

    /**
     * 返回最近一次成功自注册签发的稳定 Catalog 身份。应用可将该值中的一次性凭据保存到应用自有秘密存储中。
     */
    public HttpSelfRegistration.RegisteredIdentity getRegisteredIdentity() {
        return httpRegister == null ? null : httpRegister.identity();
    }

    /**
 * 实际契约 HTTP 端口，配置端口为 0 时尤其重要。
 */
    public int getContractPort() {
        return http != null ? http.getPort() : config.contractHttpPort();
    }

    // ─── 生命周期 ───

    public synchronized SessionBridge start() {
        if (started) {
            return this;
        }
        started = true;

        if (config.startHttp()) {
            try {
                http =
                        new ContractHttpServer(
                                config.contractHttpHost(),
                                config.contractHttpPort(),
                                this,
                                config.internalToken());
                http.start();
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "controlplane: contract HTTP server failed to bind", e);
            }
        }

        if (config.startHttpRegister()) {
            String bootstrapToken = config.internalToken();
            if ((bootstrapToken == null || bootstrapToken.isBlank())
                    && config.registrationCredential().isBlank()) {
                LOG.warning(
                        "controlplane: registration enabled but neither bootstrap token nor"
                                + " registration credential is configured; skipping");
            } else {
                String baseUrl = resolvePublicBaseUrl();
                httpRegister =
                        new HttpSelfRegistration(
                                config.controlPlaneHttp(),
                                bootstrapToken,
                                config.registrationCredential(),
                                config.agentKey(),
                                config.tenant(),
                                config.namespace(),
                                config.instanceKey(),
                                baseUrl,
                                frameworkName(),
                                frameworkName(),
                                CONTRACT_LEVEL,
                                List.copyOf(capabilities()),
                                15_000L);
                httpRegister.start();
            }
        }

        if (config.startGrpc()) {
            HttpSelfRegistration.RegisteredIdentity registered =
                    httpRegister == null ? null : httpRegister.identity();
            String agentId = registered == null ? config.agentId() : registered.agentId();
            String bindingId = registered == null ? config.bindingId() : registered.bindingId();
            String instanceKey =
                    registered == null ? config.instanceKey() : registered.instanceKey();
            long generation = registered == null ? config.generation() : registered.generation();
            String credential =
                    registered == null
                            ? (!config.registrationCredential().isBlank()
                                    ? config.registrationCredential()
                                    : config.internalToken())
                            : registered.registrationCredential();
            if (agentId.isBlank() || bindingId.isBlank() || generation <= 0) {
                started = false;
                throw new IllegalStateException(
                        "controlplane: ASDP requires a successful registration or an explicit"
                                + " stable identity");
            }
            grpc =
                    new GrpcTransport(
                            config.controlPlane(),
                            credential,
                            agentId,
                            config.agentKey(),
                            bindingId,
                            config.tenant(),
                            config.namespace(),
                            instanceKey,
                            generation,
                            frameworkName(),
                            SDK_VERSION,
                            capabilities(),
                            config.sessionAffinity());
            grpc.setSessionCommandHandler(
                    (sessionId, command, params) -> dispatchCommand(sessionId, command, params));
            grpc.setExecutionAttemptHandler(this::onExecutionAttempt);
            grpc.setConversationTurnHandler(this::onConversationTurn);
            grpc.setEventAckHandler(this::onEventAck);
            if (httpRegister != null) {
                httpRegister.setIdentityListener(
                        identity ->
                                grpc.updateIdentity(
                                        identity.registrationCredential(),
                                        identity.agentId(),
                                        identity.bindingId(),
                                        identity.instanceKey(),
                                        identity.generation()));
            }
            grpc.start();
        }

        scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "controlplane-bridge");
                            t.setDaemon(true);
                            return t;
                        });
        // 执行尝试租约影响正确性，其心跳必须与尽力而为的
        // 上报隔离。资源清单收集可能遍历大工作区，绝不能长期饿死
        // 活动执行的心跳，导致控制平面并发重试同一执行。
        attemptHeartbeatScheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "controlplane-attempt-heartbeat");
                            t.setDaemon(true);
                            return t;
                        });
        scheduler.scheduleWithFixedDelay(
                guarded(this::flushEvents),
                EVENT_FLUSH_INTERVAL_MS,
                EVENT_FLUSH_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(
                guarded(this::reportLevel1),
                LEVEL1_INTERVAL_MS,
                LEVEL1_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
        // 连接后立即上报资源清单，之后低频刷新。
        scheduler.scheduleWithFixedDelay(
                guarded(this::reportInventory), 0L, INVENTORY_INTERVAL_MS, TimeUnit.MILLISECONDS);
        return this;
    }

    private String resolvePublicBaseUrl() {
        if (config.publicBaseUrl() != null && !config.publicBaseUrl().isBlank()) {
            String u = config.publicBaseUrl().trim();
            while (u.endsWith("/")) {
                u = u.substring(0, u.length() - 1);
            }
            return u;
        }
        int port = getContractPort();
        return "http://localhost:" + port;
    }

    @Override
    public synchronized void close() {
        if (!started) {
            return;
        }
        started = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (attemptHeartbeatScheduler != null) {
            attemptHeartbeatScheduler.shutdownNow();
            attemptHeartbeatScheduler = null;
        }
        if (grpc != null) {
            grpc.close();
            grpc = null;
        }
        if (httpRegister != null) {
            httpRegister.close();
            httpRegister = null;
        }
        if (http != null) {
            http.close();
            http = null;
        }
        if (adapter != null) {
            try {
                adapter.detach();
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "controlplane: adapter detach failed", e);
            }
        }
    }

    // ─── 能力 ───

    public Set<String> capabilities() {
        Set<String> caps = new TreeSet<>(Set.of("session-reporting", "context-reporting"));
        if (eventJournal != null) {
            caps.add("event-reporting");
        }
        if (adapter != null) {
            caps.addAll(adapter.capabilities());
        }
        return caps;
    }

    // ─── 事件接收 ───

    /**
 * 适配器回调：分配序号、更新视图并触发上报。
 */
    /**
 * 仅在本地日志已强制落盘记录后，才确认持久化原生导出。
 */
    public void onCommittedEvent(String source, long nativeSeq, SessionEvent event) {
        synchronized (lock) {
            if (eventJournal == null)
                throw new IllegalStateException("ControlPlane durable journal unavailable");
            if (eventJournal.sourceWatermark(source) >= nativeSeq) return;
            int seq = sequences.getOrDefault(event.getSessionId(), 0) + 1;
            event.setSeq(seq);
            try {
                eventJournal.append(event.toProto());
            } catch (IOException error) {
                throw new IllegalStateException("ControlPlane journal commit failed", error);
            }
            sequences.put(event.getSessionId(), seq);
            trackers.computeIfAbsent(
                            event.getSessionId(), id -> new ContextTracker(id, frameworkName()))
                    .onEvent(event);
            touch(event.getSessionId());
        }
    }

    public void onEvent(SessionEvent event) {
        String sessionId = event.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        boolean flushNeeded;
        boolean hashChanged;
        boolean compaction;
        synchronized (lock) {
            int seq = sequences.merge(sessionId, 1, Integer::sum);
            event.setSeq(seq);

            ContextTracker tracker =
                    trackers.computeIfAbsent(
                            sessionId, id -> new ContextTracker(id, frameworkName()));
            hashChanged = tracker.onEvent(event);

            touch(sessionId);

            if (SessionEvent.SESSION_START.equals(event.getEventType())) {
                phases.put(sessionId, PHASE_ACTIVE);
                busyFlags.put(sessionId, true);
            } else if (SessionEvent.SESSION_END.equals(event.getEventType())) {
                // 已结束的对话仍可恢复，因此标记为空闲而非终止；
                // 终止仅用于运行时真正移除的会话。
                phases.put(sessionId, PHASE_IDLE);
                busyFlags.put(sessionId, false);
            }

            if (eventJournal != null) {
                try {
                    eventJournal.append(event.toProto());
                } catch (IOException e) {
                    // 不破坏智能体对话，但必须显著报告持久性失败。
                    // 有意不复用该序号。
                    LOG.log(Level.SEVERE, "controlplane: failed to persist event journal", e);
                }
                flushNeeded = eventJournal.size() >= EVENT_BATCH_SIZE;
            } else {
                flushNeeded = false;
            }
            compaction = SessionEvent.COMPACTION.equals(event.getEventType());
        }

        if (flushNeeded) {
            flushEvents();
        }
        if (compaction) {
            pushContext(sessionId, true);
        } else if (hashChanged) {
            pushContext(sessionId, false);
        }
    }

    /**
 * 让适配器使用事件流不携带的数据初始化跟踪器。
 */
    public void describeSession(
            String sessionId,
            String systemPrompt,
            List<ContextSnapshot.ToolInfo> tools,
            int maxTokens) {
        describeSession(sessionId, systemPrompt, tools, maxTokens, null);
    }

    /**
 * 让适配器初始化跟踪器，并在已知时包含模型名。
 */
    public void describeSession(
            String sessionId,
            String systemPrompt,
            List<ContextSnapshot.ToolInfo> tools,
            int maxTokens,
            String model) {
        synchronized (lock) {
            ContextTracker tracker =
                    trackers.computeIfAbsent(
                            sessionId, id -> new ContextTracker(id, frameworkName()));
            phases.putIfAbsent(sessionId, PHASE_IDLE);
            if (systemPrompt != null) {
                tracker.setSystemPrompt(systemPrompt);
            }
            if (tools != null) {
                tracker.setTools(tools);
            }
            if (maxTokens > 0) {
                tracker.setMaxTokens(maxTokens);
            }
            if (model != null && !model.isEmpty()) {
                tracker.setModel(model);
            }
        }
    }

    /**
 * 保存会话最近一次由中间件组合完成的系统提示。
 */
    public void setEffectiveSystemPrompt(String sessionId, String prompt) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        synchronized (lock) {
            ContextTracker tracker =
                    trackers.computeIfAbsent(
                            sessionId, id -> new ContextTracker(id, frameworkName()));
            tracker.setEffectiveSystemPrompt(prompt);
        }
    }

    /**
 * 会话最近的有效系统提示；不存在则为空。
 */
    public String effectiveSystemPrompt(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return "";
        }
        synchronized (lock) {
            ContextTracker tracker = trackers.get(sessionId);
            return tracker == null ? "" : tracker.getEffectiveSystemPrompt();
        }
    }

    /**
     * 通过驱动 {@code phase} 记录轮次是否正在进行：忙碌时为 {@code active}，结束时为 {@code idle}。一级响应的 {@code busy} 由 phase 推导。
     */
    public void setBusy(String sessionId, boolean busy) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        busyFlags.put(sessionId, busy);
        String current = phases.get(sessionId);
        if (PHASE_COMPRESSING.equals(current)
                || PHASE_ARCHIVED.equals(current)
                || PHASE_TERMINATED.equals(current)) {
            // 轮次钩子不得覆盖正在压缩或终态。
            touch(sessionId);
            return;
        }
        phases.put(sessionId, busy ? PHASE_ACTIVE : PHASE_IDLE);
        touch(sessionId);
    }

    /**
 * 显式设置运行阶段，例如正在压缩。
 */
    public void setPhase(String sessionId, String phase) {
        if (sessionId == null || sessionId.isEmpty() || phase == null || phase.isEmpty()) {
            return;
        }
        phases.put(sessionId, phase);
        boolean active = PHASE_ACTIVE.equals(phase);
        busyFlags.put(sessionId, active);
        touch(sessionId);
    }

    /**
 * 更新一级 {@code contextPressure} 使用的上下文窗口占用量。
 */
    public void setContextUsedTokens(String sessionId, int usedTokens) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        synchronized (lock) {
            ContextTracker tracker = trackers.get(sessionId);
            if (tracker != null) {
                tracker.setContextUsedTokens(usedTokens);
            }
        }
    }

    private void touch(String sessionId) {
        long now = System.currentTimeMillis();
        startedAt.putIfAbsent(sessionId, now);
        lastActiveAt.put(sessionId, now);
    }

    private static String isoOrNull(Long epochMillis) {
        return epochMillis == null
                ? null
                : DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis));
    }

    /**
 * 完全省略未知字段；契约将缺失键解释为“未上报”。
 */
    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    // ─── 第二级 ───

    private void flushEvents() {
        if (grpc == null || eventJournal == null) {
            return;
        }
        List<SessionEventMsg> payload;
        String reportId;
        synchronized (lock) {
            if (eventJournal.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            if (inFlightEventReportId != null
                    && now - inFlightEventReportAt < EVENT_ACK_TIMEOUT_MS) {
                return;
            }
            reportId = UUID.randomUUID().toString();
            payload = eventJournal.first(EVENT_BATCH_SIZE);
            inFlightEventReportId = reportId;
            inFlightEventReportAt = now;
        }
        if (!grpc.reportEvents(reportId, payload)) {
            synchronized (lock) {
                if (reportId.equals(inFlightEventReportId)) {
                    inFlightEventReportId = null;
                }
            }
        }
    }

    private void onEventAck(EventReportAck ack) {
        boolean hasMore;
        synchronized (lock) {
            if (ack == null
                    || inFlightEventReportId == null
                    || !inFlightEventReportId.equals(ack.getReportId())) {
                return;
            }
            Map<String, Integer> committed = new LinkedHashMap<>();
            ack.getCommittedList()
                    .forEach(
                            cursor ->
                                    committed.merge(
                                            cursor.getSessionId(),
                                            cursor.getCommittedSeq(),
                                            Math::max));
            try {
                eventJournal.acknowledge(committed);
            } catch (IOException e) {
                LOG.log(
                        Level.SEVERE,
                        "controlplane: failed to checkpoint event acknowledgement",
                        e);
                // 保持批次待发送。控制平面按 (session, seq)
                // 幂等处理，因此可以安全重放。
            }
            inFlightEventReportId = null;
            hasMore = !eventJournal.isEmpty();
        }
        if (!ack.getError().isEmpty()) {
            LOG.log(
                    Level.FINE,
                    "controlplane: event report partially committed: {0}",
                    ack.getError());
        }
        if (hasMore && ack.getError().isEmpty()) {
            flushEvents();
        }
    }

    // ─── 第四级 ───

    private void pushContext(String sessionId, boolean force) {
        if (grpc == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastContextPush.get(sessionId);
        if (!force && last != null && now - last < CONTEXT_PUSH_COOLDOWN_MS) {
            return;
        }
        ContextTracker tracker = trackers.get(sessionId);
        if (tracker == null) {
            return;
        }
        lastContextPush.put(sessionId, now);
        ContextSnapshot snapshot;
        synchronized (lock) {
            snapshot = tracker.snapshot();
        }
        grpc.reportContext(snapshot.toProto());
    }

    // ─── 第一级 ───

    private void reportLevel1() {
        if (grpc == null) {
            return;
        }
        grpc.reportSessions(buildLevel1());
    }

    private List<SessionSnapshot> buildLevel1() {
        String framework = frameworkName();
        String version = frameworkVersion();
        List<SessionSnapshot> out = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<String, ContextTracker> entry : trackers.entrySet()) {
                ContextTracker tracker = entry.getValue();
                int usedForPressure = tracker.getContextUsedTokens();
                double pressure =
                        tracker.getMaxTokens() > 0 && usedForPressure > 0
                                ? (double) usedForPressure / tracker.getMaxTokens()
                                : 0.0;
                out.add(
                        SessionSnapshot.newBuilder()
                                .setSessionId(entry.getKey())
                                .setPhase(phases.getOrDefault(entry.getKey(), PHASE_IDLE))
                                .setMessageCount(tracker.getMessageCount())
                                .setPromptTokens(tracker.getTokensIn())
                                .setCompletionTokens(tracker.getTokensOut())
                                .setContextPressure(pressure)
                                .setFramework(framework)
                                .setFrameworkVersion(version)
                                .setContextHash(tracker.getContextHash())
                                .setIsCompacted(tracker.isCompacted())
                                .setEffectiveMessageCount(tracker.getEffectiveMessageCount())
                                .build());
            }
        }
        return out;
    }

    // ─── 资源清单 ───

    private void reportInventory() {
        if (grpc == null || adapter == null) {
            return;
        }
        int active = (int) phases.values().stream().filter(PHASE_ACTIVE::equals).count();
        List<Inventory.SubagentInfo> subagents = awaitOrDefault(adapter.listSubagents(), List.of());
        List<Inventory.WorkspaceInfo> workspaces =
                awaitOrDefault(adapter.listWorkspaces(), List.of());
        Inventory inventory =
                new Inventory(
                        subagents, workspaces, new Inventory.InstanceHealth(true, "", active));
        grpc.reportInventory(inventory.toProto());
    }

    // ─── AgentTask 投递（ASDP 下行）───

    private void onExecutionAttempt(ExecutionAttemptCommand command) {
        LOG.log(
                Level.FINE,
                "controlplane: downstream ExecutionAttempt attempt={0} task={1}",
                new Object[] {command.getAttemptId(), command.getAgentTaskId()});
        reportAttempt(command, "ack");
        if ("cancel".equals(command.getCommand())) {
            reportAttempt(command, "cancelled");
            return;
        }
        if (adapter == null) {
            return;
        }
        reportAttempt(command, "start");
        ScheduledFuture<?> heartbeat = startAttemptHeartbeat(command);
        adapter.handleAgentTask(
                        new AgentTaskAssignment(
                                command.getAttemptId(),
                                command.getAgentTaskId(),
                                command.getRunId(),
                                command.getNodeId(),
                                command.getGeneration(),
                                command.getCommand(),
                                command.getContextUrl(),
                                command.getTaskToken(),
                                command.getAttemptToken(),
                                executionSessionId(command),
                                command.getPayload().toByteArray(),
                                command.getTimestamp()))
                .doFinally(
                        ignored -> {
                            if (heartbeat != null) {
                                heartbeat.cancel(false);
                            }
                        })
                .subscribe(
                        ignored -> {},
                        error ->
                                LOG.log(
                                        Level.WARNING,
                                        "controlplane: ExecutionAttempt delivery failed attempt="
                                                + command.getAttemptId(),
                                        error));
    }

    static String executionSessionId(ExecutionAttemptCommand command) {
        if (command.getRuntimeBinding().isEmpty()) {
            return "";
        }
        try {
            return JSON.readTree(command.getRuntimeBinding().toByteArray())
                    .path("sessionId")
                    .asText("");
        } catch (IOException ignored) {
            LOG.log(
                    Level.WARNING,
                    "controlplane: ExecutionAttempt runtime binding is not valid JSON for task {0}",
                    command.getAgentTaskId());
            return "";
        }
    }

    private ScheduledFuture<?> startAttemptHeartbeat(ExecutionAttemptCommand command) {
        ScheduledExecutorService current = attemptHeartbeatScheduler;
        if (current == null || current.isShutdown()) {
            return null;
        }
        return current.scheduleWithFixedDelay(
                guarded(() -> reportAttempt(command, "heartbeat")),
                ATTEMPT_HEARTBEAT_INTERVAL_MS,
                ATTEMPT_HEARTBEAT_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    private void reportAttempt(ExecutionAttemptCommand command, String action) {
        if (grpc == null) {
            return;
        }
        grpc.reportExecutionAttempt(
                ExecutionAttemptReport.newBuilder()
                        .setAttemptId(command.getAttemptId())
                        .setAgentTaskId(command.getAgentTaskId())
                        .setRunId(command.getRunId())
                        .setNodeId(command.getNodeId())
                        .setGeneration(command.getGeneration())
                        .setAction(action)
                        .setAttemptToken(command.getAttemptToken())
                        .build());
    }

    // ─── 命令分发（ASDP 推送和 HTTP 均进入此处）───

    private void dispatchCommand(String sessionId, String command, byte[] params) {
        if (adapter == null) {
            throw new UnsupportedOperationException("no framework adapter attached");
        }
        adapter.handleCommand(sessionId, command, params).block(ADAPTER_CALL_TIMEOUT);
    }

    private void onConversationTurn(ConversationTurnCommand command) {
        if (grpc == null || adapter == null) {
            return;
        }
        reportConversationTurn(command, "accepted", null, null);
        String content;
        try {
            content = readNotice(command.getInput().toByteArray());
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("conversation input.message is required");
            }
        } catch (RuntimeException e) {
            reportConversationTurn(command, "failed", null, e);
            return;
        }
        long remainingMillis = command.getDeadline() - System.currentTimeMillis();
        if (remainingMillis <= 0) {
            reportConversationTurn(
                    command,
                    "failed",
                    null,
                    new IllegalStateException("conversation deadline expired"));
            return;
        }
        reportConversationTurn(command, "started", null, null);
        Mono.defer(() -> adapter.runConversationTurn(command.getSessionId(), content))
                .filter(reply -> !reply.isBlank())
                .switchIfEmpty(
                        Mono.error(
                                new IllegalStateException(
                                        "conversation completed without an assistant reply")))
                .flatMap(
                        reply ->
                                Mono.fromCallable(
                                        () -> JSON.writeValueAsString(Map.of("content", reply))))
                .timeout(Duration.ofMillis(remainingMillis))
                .subscribe(
                        reply -> reportConversationTurn(command, "completed", reply, null),
                        error -> reportConversationTurn(command, "failed", null, error));
    }

    private void reportConversationTurn(
            ConversationTurnCommand command, String action, String payload, Throwable error) {
        if (grpc == null) {
            return;
        }
        ConversationTurnReport.Builder report =
                ConversationTurnReport.newBuilder()
                        .setInvocationId(command.getInvocationId())
                        .setConversationId(command.getConversationId())
                        .setTurnId(command.getTurnId())
                        .setSessionId(command.getSessionId())
                        .setGeneration(command.getGeneration())
                        .setAction(action);
        if (payload != null) {
            report.setPayload(com.google.protobuf.ByteString.copyFromUtf8(payload));
        }
        if (error != null) {
            String message =
                    error.getMessage() == null
                            ? error.getClass().getSimpleName()
                            : error.getMessage();
            report.setErrorCode("conversation_failed").setErrorMessage(message);
        }
        grpc.reportConversationTurn(report.build());
    }

    // ─── 契约数据提供者 ───

    @Override
    public Map<String, Object> info() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", config.agentKey());
        out.put("runtime", frameworkName());
        out.put("version", frameworkVersion());
        out.put("sdkVersion", SDK_VERSION);
        out.put("contractLevel", CONTRACT_LEVEL);
        out.put("capabilities", List.copyOf(capabilities()));
        out.put("port", getContractPort());
        if (!config.sessionAffinity().isEmpty()) {
            out.put("sessionAffinity", config.sessionAffinity());
        }
        if (adapter != null) {
            Map<String, Object> agentConfig = adapter.buildAgentConfig();
            if (agentConfig != null && !agentConfig.isEmpty()) {
                out.put("agentConfig", agentConfig);
            }
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> sessions() {
        String framework = frameworkName();
        String version = frameworkVersion();
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<String, ContextTracker> entry : trackers.entrySet()) {
                String sessionId = entry.getKey();
                ContextTracker tracker = entry.getValue();
                int totalTokens = tracker.getTokensIn() + tracker.getTokensOut();
                int usedForPressure = tracker.getContextUsedTokens();
                Map<String, Object> session = new LinkedHashMap<>();
                session.put("id", sessionId);
                session.put("phase", phases.getOrDefault(sessionId, PHASE_IDLE));
                Boolean busy = busyFlags.get(sessionId);
                if (busy == null) {
                    busy = PHASE_ACTIVE.equals(phases.getOrDefault(sessionId, PHASE_IDLE));
                }
                session.put("busy", busy);
                if (!tracker.getModel().isEmpty()) {
                    session.put("model", tracker.getModel());
                }
                putIfPresent(session, "startedAt", isoOrNull(startedAt.get(sessionId)));
                putIfPresent(session, "lastActiveAt", isoOrNull(lastActiveAt.get(sessionId)));
                session.put("messageCount", tracker.getMessageCount());
                Map<String, Object> tokenUsage = new LinkedHashMap<>();
                tokenUsage.put("promptTokens", tracker.getTokensIn());
                tokenUsage.put("completionTokens", tracker.getTokensOut());
                tokenUsage.put("totalTokens", totalTokens);
                if (tracker.getMaxTokens() > 0) {
                    tokenUsage.put("maxTokens", tracker.getMaxTokens());
                }
                session.put("tokenUsage", tokenUsage);
                session.put(
                        "contextPressure",
                        tracker.getMaxTokens() > 0 && usedForPressure > 0
                                ? (double) usedForPressure / tracker.getMaxTokens()
                                : 0.0);
                Map<String, Object> taskSummary = resolveTaskSummary(sessionId);
                if (taskSummary != null) {
                    session.put("taskSummary", taskSummary);
                }
                session.put("framework", framework);
                if (!version.isEmpty()) {
                    session.put("frameworkVersion", version);
                }
                session.put("contextHash", tracker.getContextHash());
                if (tracker.isCompacted()) {
                    session.put("isCompacted", true);
                }
                session.put("effectiveMessageCount", tracker.getEffectiveMessageCount());
                out.add(session);
            }
        }
        return out;
    }

    @Override
    public Map<String, Object> sessionState(String sessionId) {
        ContextTracker tracker = requireTracker(sessionId);
        int totalTokens = tracker.getTokensIn() + tracker.getTokensOut();
        int maxTokens = tracker.getMaxTokens();
        // 仅窗口占用量，绝不回退为生命周期累计用量（tokensIn+tokensOut）。
        int usedForPressure = tracker.getContextUsedTokens();
        Map<String, Object> pressure = new LinkedHashMap<>();
        pressure.put("usedTokens", usedForPressure);
        pressure.put("maxTokens", maxTokens);
        pressure.put(
                "ratio",
                maxTokens > 0 && usedForPressure > 0 ? (double) usedForPressure / maxTokens : 0.0);

        Map<String, Object> tokenUsage = new LinkedHashMap<>();
        tokenUsage.put("promptTokens", tracker.getTokensIn());
        tokenUsage.put("completionTokens", tracker.getTokensOut());
        tokenUsage.put("totalTokens", totalTokens);
        if (maxTokens > 0) {
            tokenUsage.put("maxTokens", maxTokens);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("id", sessionId);
        out.put("phase", phases.getOrDefault(sessionId, PHASE_IDLE));
        Boolean busy = busyFlags.get(sessionId);
        if (busy == null) {
            busy = PHASE_ACTIVE.equals(phases.getOrDefault(sessionId, PHASE_IDLE));
        }
        out.put("busy", busy);
        if (!tracker.getModel().isEmpty()) {
            out.put("model", tracker.getModel());
        }
        putIfPresent(out, "startedAt", isoOrNull(startedAt.get(sessionId)));
        putIfPresent(out, "lastActiveAt", isoOrNull(lastActiveAt.get(sessionId)));
        out.put("messageCount", tracker.getMessageCount());
        out.put("tokenUsage", tokenUsage);
        out.put("contextPressure", pressure);
        if (tracker.isCompacted()) {
            out.put("isCompacted", true);
        }
        Map<String, Object> taskSummary = resolveTaskSummary(sessionId);
        if (taskSummary != null) {
            out.put("taskSummary", taskSummary);
        }
        out.put("framework", frameworkName());
        return out;
    }

    @Override
    public Map<String, Object> context(String sessionId) {
        // 适配器读取框架权威的实时状态；读取失败时，
        // 才回退使用跟踪器从事件推导的视图。
        ContextSnapshot snapshot = null;
        if (adapter != null) {
            try {
                snapshot = adapter.extractContext(sessionId).block(ADAPTER_CALL_TIMEOUT);
            } catch (UnsupportedOperationException e) {
                throw e;
            } catch (RuntimeException e) {
                LOG.log(
                        Level.FINE,
                        "controlplane: extractContext failed, falling back to tracker",
                        e);
            }
        }
        if (snapshot == null) {
            snapshot = requireTracker(sessionId).snapshot();
        } else if (snapshot.getMessages().isEmpty() && !trackers.containsKey(sessionId)) {
            throw new NotFoundException("session not found: " + sessionId);
        }
        snapshot.refreshHash();
        return snapshot.toJsonMap();
    }

    @Override
    public Map<String, Object> messages(String sessionId, int offset, int limit) {
        if (adapter == null) {
            throw new UnsupportedOperationException("no framework adapter attached");
        }
        MessagePage page =
                adapter.listMessages(sessionId, offset, limit).block(ADAPTER_CALL_TIMEOUT);
        if (page == null || (page.total() == 0 && !trackers.containsKey(sessionId))) {
            throw new NotFoundException("session not found: " + sessionId);
        }
        return page.toJsonMap();
    }

    @Override
    public List<Map<String, Object>> subagents() {
        if (adapter == null) {
            throw new UnsupportedOperationException("no framework adapter attached");
        }
        List<Inventory.SubagentInfo> items = adapter.listSubagents().block(ADAPTER_CALL_TIMEOUT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Inventory.SubagentInfo item :
                items == null ? List.<Inventory.SubagentInfo>of() : items) {
            out.add(item.toJsonMap());
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> workspaces() {
        if (adapter == null) {
            throw new UnsupportedOperationException("no framework adapter attached");
        }
        List<Inventory.WorkspaceInfo> items = adapter.listWorkspaces().block(ADAPTER_CALL_TIMEOUT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Inventory.WorkspaceInfo item :
                items == null ? List.<Inventory.WorkspaceInfo>of() : items) {
            out.add(item.toJsonMap());
        }
        return out;
    }

    @Override
    public void compress(String sessionId) {
        if (Boolean.TRUE.equals(busyFlags.get(sessionId))
                || PHASE_ACTIVE.equals(phases.getOrDefault(sessionId, PHASE_IDLE))) {
            throw new BusyException("session is busy");
        }
        setPhase(sessionId, PHASE_COMPRESSING);
        try {
            dispatchCommand(sessionId, FrameworkAdapter.COMMAND_COMPRESS, null);
            setPhase(sessionId, PHASE_IDLE);
        } catch (RuntimeException e) {
            setPhase(sessionId, PHASE_IDLE);
            throw e;
        }
    }

    @Override
    public void terminate(String sessionId) {
        dispatchCommand(sessionId, FrameworkAdapter.COMMAND_TERMINATE, null);
        setPhase(sessionId, PHASE_TERMINATED);
    }

    @Override
    public void abort(String sessionId) {
        dispatchCommand(sessionId, FrameworkAdapter.COMMAND_ABORT, null);
        setBusy(sessionId, false);
    }

    @Override
    public Map<String, Object> tasks(String sessionId) {
        if (adapter == null) {
            throw new UnsupportedOperationException("no framework adapter attached");
        }
        List<Map<String, Object>> items;
        try {
            items = adapter.listTasks(sessionId).block(ADAPTER_CALL_TIMEOUT);
        } catch (UnsupportedOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            if (!trackers.containsKey(sessionId)) {
                throw new NotFoundException("session not found: " + sessionId);
            }
            throw e;
        }
        if ((items == null || items.isEmpty()) && !trackers.containsKey(sessionId)) {
            throw new NotFoundException("session not found: " + sessionId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tasks", items == null ? List.of() : items);
        return out;
    }

    @Override
    public Map<String, Object> subagentTasks(String sessionId) {
        if (adapter == null) {
            throw new UnsupportedOperationException("no framework adapter attached");
        }
        List<Map<String, Object>> items =
                adapter.listSubagentTasks(sessionId).block(ADAPTER_CALL_TIMEOUT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tasks", items == null ? List.of() : items);
        return out;
    }

    @Override
    public void cancelSubagentTask(String sessionId, String taskId) {
        if (adapter == null) {
            throw new UnsupportedOperationException("no framework adapter attached");
        }
        adapter.cancelSubagentTask(sessionId, taskId).block(ADAPTER_CALL_TIMEOUT);
    }

    @Override
    public void planMode(String sessionId, byte[] body) {
        if (adapter == null) {
            throw new UnsupportedOperationException("no framework adapter attached");
        }
        adapter.setPlanMode(sessionId, body).block(ADAPTER_CALL_TIMEOUT);
    }

    @Override
    public void postMessage(String sessionId, byte[] body) {
        if (adapter == null) {
            throw new UnsupportedOperationException("no framework adapter attached");
        }
        if (Boolean.TRUE.equals(busyFlags.get(sessionId))
                || PHASE_ACTIVE.equals(phases.getOrDefault(sessionId, PHASE_IDLE))) {
            throw new BusyException("session is busy");
        }
        String content = readNotice(body);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content is required");
        }
        adapter.injectUserMessage(sessionId, content).block(ADAPTER_CALL_TIMEOUT);
    }

    private static String readNotice(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return "";
        }
        try {
            var root = JSON.readTree(payload);
            String content = root.path("content").asText("");
            if (content.isEmpty()) {
                content = root.path("message").asText("");
            }
            return content.isEmpty() ? root.toString() : content;
        } catch (IOException e) {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    @Override
    public Map<String, Object> exportTranscript(String sessionId) {
        Map<String, Object> page = messages(sessionId, 0, Integer.MAX_VALUE);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("format", "json");
        out.put("messages", page.getOrDefault("messages", List.of()));
        return out;
    }

    @Override
    public String sessionPhase(String sessionId) {
        return phases.getOrDefault(sessionId, PHASE_IDLE);
    }

    // ─── 辅助方法 ───

    private Map<String, Object> resolveTaskSummary(String sessionId) {
        if (adapter == null) {
            return null;
        }
        try {
            List<Map<String, Object>> tasks =
                    adapter.listTasks(sessionId).block(ADAPTER_CALL_TIMEOUT);
            return summarizeTasks(tasks);
        } catch (UnsupportedOperationException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Map<String, Object> summarizeTasks(List<Map<String, Object>> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return null;
        }
        int pending = 0;
        int inProgress = 0;
        int completed = 0;
        for (Map<String, Object> task : tasks) {
            Object state = task.get("state");
            String wire = state == null ? "" : state.toString();
            switch (wire) {
                case "in_progress" -> inProgress++;
                case "completed" -> completed++;
                default -> pending++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", tasks.size());
        summary.put("pending", pending);
        summary.put("inProgress", inProgress);
        summary.put("completed", completed);
        return summary;
    }

    private ContextTracker requireTracker(String sessionId) {
        ContextTracker tracker = trackers.get(sessionId);
        if (tracker == null) {
            throw new NotFoundException("session not found: " + sessionId);
        }
        return tracker;
    }

    private String frameworkName() {
        return adapter == null ? "" : adapter.frameworkName();
    }

    private String frameworkVersion() {
        if (adapter == null) {
            return "";
        }
        try {
            String version = adapter.frameworkVersion();
            return version == null ? "" : version;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static <T> T awaitOrDefault(Mono<T> mono, T fallback) {
        try {
            T value = mono.block(ADAPTER_CALL_TIMEOUT);
            return value == null ? fallback : value;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                // 上报失败不得终止调度器或传播到智能体。
                LOG.log(Level.FINE, "controlplane: scheduled report failed", e);
            }
        };
    }
}
