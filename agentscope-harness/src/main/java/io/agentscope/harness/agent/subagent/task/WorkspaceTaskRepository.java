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
package io.agentscope.harness.agent.subagent.task;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.coordination.LocalPeriodicGate;
import io.agentscope.harness.agent.coordination.PeriodicGate;
import io.agentscope.harness.agent.subagent.protocol.RemotePendingConfirm;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于工作区的 {@link TaskRepository}，使用 {@link WorkspaceManager} 作为
 * 任务状态的权威事实来源，同时维护内存 {@link BackgroundTask} 句柄，
 * 为当前节点上运行的任务提供本地性能优化层。
 *
 * <p>存储布局：{@code agents/<parentAgentId>/tasks/<sessionId>.json}，
 * 内容为 {@code taskId → TaskRecord} 的 JSON 映射，与会话存储方式一致。
 * 使用 {@code RemoteFilesystemSpec} 的分布式部署会自动将此路径路由到共享存储，
 * 使任意节点都能查看任务状态。沙箱模式下注入的文件系统是单次调用代理，
 * 调用之间没有活动沙箱，因此任务记录持久化到宿主工作区，而不是沙箱内部；
 * 参见 {@code WorkspaceManager} 中的任务记录路由。
 *
 * <p>内存 {@code localTasks} 映射以 {@code "<sessionId>:<taskId>"} 为键，
 * 使同一进程中共存的多个会话保持隔离。
 *
 * <p>分布式语义：
 * <ul>
 *   <li>任务执行固定在发起节点（调用 {@link #putTask} 的节点）。
 *   <li>任意节点可通过 {@link #getTask} 或 {@link #listTasks} 读取任务状态；
 *       没有本地 future 时回退到工作区记录。
 *   <li>非发起节点上的 {@code block=true} 平稳降级为读取最新持久化终态，不会挂起等待。
 *   <li>取消操作在工作区存储中设置 {@link TaskRecord#isCancelRequested()} 标志；
 *       发起节点在调用子智能体前检查此标志，进行尽力而为的取消。
 *   <li>远程 {@link TaskRunSpec.RemoteTaskRunSpec} 任务使用
 *       {@link RemoteSubagentTransport}（默认 Agent Protocol），并持久化
 *       {@link TaskRecord#getRemoteBaseUrl()} 以支持跨节点恢复。
 *   <li>孤立任务清理由 {@link PeriodicGate} 限频：跨副本去重使用
 *       {@link io.agentscope.harness.agent.coordination.StoreBackedPeriodicGate}，
 *       单进程部署使用 {@link LocalPeriodicGate}（默认）。
 *       若可用，优先选择控制平面托管的 {@link TaskRepository}：该路径只由 leader
 *       执行清理，完全不使用此工作区仓库。
 * </ul>
 */
public class WorkspaceTaskRepository implements TaskRepository {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceTaskRepository.class);

    private static final String TRANSPORT_AGENT_PROTOCOL = AgentProtocolTransport.TYPE;

    /**
     * 远程任务等待用户确认时使用的较短固定轮询间隔。
     */
    private static final long AWAITING_CONFIRM_POLL_MS = 750L;

    private volatile long awaitingConfirmPollMs = AWAITING_CONFIRM_POLL_MS;

    /**
     * 心跳为活动本地任务刷新 {@code lastUpdatedAt} 的间隔（秒）。
     * 必须显著小于 {@link #ORPHAN_TIMEOUT_MINUTES}，避免误判为孤立任务。
     */
    static final int HEARTBEAT_INTERVAL_SECONDS = 30;

    /**
     * 非终态本地任务允许缺少心跳的时长（分钟）；超过后清理器将其标记为
     * {@link TaskStatus#FAILED}。应为 {@link #HEARTBEAT_INTERVAL_SECONDS}
     * 的数倍，以容忍短暂延迟。
     */
    static final int ORPHAN_TIMEOUT_MINUTES = 10;

    /**
     * 孤立任务清理器扫描全部工作区任务记录的间隔（分钟）。
     */
    static final int SWEEP_INTERVAL_MINUTES = 5;

    private final WorkspaceManager workspaceManager;
    private final String parentAgentId;
    private final PeriodicGate periodicGate;
    private volatile RemoteSubagentTransport transport;

    /**
     * 内存本地任务句柄。以 {@code "<sessionId>:<taskId>"} 为键，
     * 使同一 JVM 进程中活动的多个父会话保持隔离。
     */
    private final Map<String, BackgroundTask> localTasks = new ConcurrentHashMap<>();

    /**
     * 将 {@code localKey} 映射到 {@code sessionId}，使心跳无需解析组合键字符串
     * 即可遍历正在运行的任务。
     */
    private final Map<String, String> localTaskSessionIds = new ConcurrentHashMap<>();

    /**
     * 将 {@code localKey} 映射到 {@code putTask} 时捕获的 {@link RuntimeContext}，
     * 使后台 future 和心跳线程能在发起用户的命名空间下持久化任务状态。
     */
    private final Map<String, RuntimeContext> localTaskContexts = new ConcurrentHashMap<>();

    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final ScheduledExecutorService maintenanceScheduler;

    private volatile TaskCompletionCallback completionCallback;

    public WorkspaceTaskRepository(WorkspaceManager workspaceManager, String parentAgentId) {
        this(workspaceManager, parentAgentId, new LocalPeriodicGate());
    }

    /**
     * 使用显式 {@link PeriodicGate} 创建仓库，对孤立任务清理进行限频。
     *
     * <p>存在共享 {@code BaseStore} 时，传入
     * {@link io.agentscope.harness.agent.coordination.StoreBackedPeriodicGate}，
     * 使每个间隔只有一个副本执行清理。
     */
    public WorkspaceTaskRepository(
            WorkspaceManager workspaceManager, String parentAgentId, PeriodicGate periodicGate) {
        this(
                workspaceManager,
                parentAgentId,
                Executors.newCachedThreadPool(
                        r -> {
                            Thread t = new Thread(r);
                            t.setDaemon(true);
                            t.setName("ws-task-" + t.getId());
                            return t;
                        }),
                true,
                true,
                periodicGate);
    }

    public WorkspaceTaskRepository(
            WorkspaceManager workspaceManager, String parentAgentId, ExecutorService executor) {
        this(workspaceManager, parentAgentId, executor, false, true, new LocalPeriodicGate());
    }

    /**
     * 仅用于测试的工厂，不启动后台心跳或孤立任务清理线程。
     *
     * <p>单元测试直接调用 {@link #heartbeat()} 和 {@link #sweepOrphanedTasks}；
     * 启用维护调度器会在较慢 CI 主机（尤其 Windows）上产生偶发竞争。
     */
    public static WorkspaceTaskRepository forTests(
            WorkspaceManager workspaceManager, String parentAgentId) {
        ExecutorService testExecutor =
                Executors.newCachedThreadPool(
                        r -> {
                            Thread t = new Thread(r, "ws-task-test");
                            t.setDaemon(true);
                            return t;
                        });
        // 测试辅助方法自行创建执行器，因此仓库必须拥有并负责关闭它。
        return new WorkspaceTaskRepository(
                workspaceManager,
                parentAgentId,
                testExecutor,
                true,
                false,
                new LocalPeriodicGate());
    }

    /**
     * 仅用于测试的工厂，使用调用方提供的 {@link PeriodicGate}，不启动维护线程。
     */
    public static WorkspaceTaskRepository forTests(
            WorkspaceManager workspaceManager, String parentAgentId, PeriodicGate periodicGate) {
        ExecutorService testExecutor =
                Executors.newCachedThreadPool(
                        r -> {
                            Thread t = new Thread(r, "ws-task-test");
                            t.setDaemon(true);
                            return t;
                        });
        return new WorkspaceTaskRepository(
                workspaceManager, parentAgentId, testExecutor, true, false, periodicGate);
    }

    /**
     * 仅用于测试的工厂，使用调用方提供的执行器，不启动维护线程。
     */
    public static WorkspaceTaskRepository forTests(
            WorkspaceManager workspaceManager, String parentAgentId, ExecutorService executor) {
        return new WorkspaceTaskRepository(
                workspaceManager, parentAgentId, executor, false, false, new LocalPeriodicGate());
    }

    private WorkspaceTaskRepository(
            WorkspaceManager workspaceManager,
            String parentAgentId,
            ExecutorService executor,
            boolean ownsExecutor,
            boolean enableMaintenance,
            PeriodicGate periodicGate) {
        this.workspaceManager = workspaceManager;
        this.parentAgentId = parentAgentId != null ? parentAgentId : "HarnessAgent";
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        this.periodicGate = periodicGate != null ? periodicGate : new LocalPeriodicGate();
        this.transport = new AgentProtocolTransport();
        if (enableMaintenance) {
            ScheduledExecutorService scheduler =
                    Executors.newSingleThreadScheduledExecutor(
                            r -> {
                                Thread t = new Thread(r);
                                t.setDaemon(true);
                                t.setName("ws-task-maint-" + t.getId());
                                return t;
                            });
            scheduler.scheduleAtFixedRate(
                    this::heartbeat,
                    HEARTBEAT_INTERVAL_SECONDS,
                    HEARTBEAT_INTERVAL_SECONDS,
                    TimeUnit.SECONDS);
            // 将首次清理时间随机分散到 [0, SWEEP_INTERVAL_MINUTES)，
            // 分散多个节点同时启动时的负载。
            long sweepJitterSeconds =
                    ThreadLocalRandom.current().nextLong(SWEEP_INTERVAL_MINUTES * 60L);
            scheduler.scheduleAtFixedRate(
                    this::sweepOrphanedTasksDefault,
                    sweepJitterSeconds,
                    SWEEP_INTERVAL_MINUTES * 60L,
                    TimeUnit.SECONDS);
            this.maintenanceScheduler = scheduler;
        } else {
            this.maintenanceScheduler = null;
        }
    }

    @Override
    public void setCompletionCallback(TaskCompletionCallback callback) {
        this.completionCallback = callback;
    }

    /**
     * 仅用于测试的钩子，注入假的 {@link RemoteSubagentTransport}，
     * 使远程任务测试绕过真实 HTTP 调用。
     */
    void setTransport(RemoteSubagentTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /**
     * 仅用于测试的钩子，缩短等待确认时的轮询间隔，避免多轮确认轮询测试
     * 承担生产环境 750ms 的时间粒度。应在 {@link #putTask} 启动轮询循环前调用。
     */
    void setAwaitingConfirmPollMs(long pollMs) {
        this.awaitingConfirmPollMs = Math.max(1L, pollMs);
    }

    @Override
    public BackgroundTask putTask(
            RuntimeContext rc,
            String taskId,
            String subAgentId,
            String sessionId,
            TaskRunSpec spec) {
        RuntimeContext capturedRc = rc != null ? rc : RuntimeContext.empty();
        TaskRecord record = new TaskRecord(taskId, subAgentId, parentAgentId, sessionId, null);
        record.setStatus(TaskStatus.PENDING);
        if (spec instanceof TaskRunSpec.RemoteTaskRunSpec remote) {
            record.setTransportType(TRANSPORT_AGENT_PROTOCOL);
            record.setRemoteBaseUrl(remote.baseUrl());
            record.setRemoteHeaders(
                    remote.headers() == null
                            ? null
                            : Collections.unmodifiableMap(remote.headers()));
        }
        persistRecord(capturedRc, sessionId, record);

        String localKey = localKey(sessionId, taskId);
        CompletableFuture<String> future;

        if (spec instanceof TaskRunSpec.AdoptedTaskRunSpec adopted) {
            // 【学习提示】接管的是已有执行，而不是重新执行任务；若再次提交 supplier，
            // 同步转异步的超时边界可能导致工具副作用重复发生。
            // future 已经在运行（由超时的同步执行提升而来）。
            // 跳过向执行器提交；只连接状态跟踪回调。
            future = adopted.future();
            updateStatus(capturedRc, sessionId, taskId, TaskStatus.RUNNING, null, null);
            final String sid = sessionId;
            future.whenComplete(
                    (result, err) -> {
                        if (err != null) {
                            Throwable cause =
                                    err instanceof java.util.concurrent.CompletionException
                                            ? err.getCause()
                                            : err;
                            String errMsg =
                                    cause != null && cause.getMessage() != null
                                            ? cause.getMessage()
                                            : (cause != null
                                                    ? cause.getClass().getSimpleName()
                                                    : err.getClass().getSimpleName());
                            updateStatus(capturedRc, sid, taskId, TaskStatus.FAILED, null, errMsg);
                            fireCompletionCallback(capturedRc, taskId, subAgentId, sid, null);
                        } else {
                            updateStatus(
                                    capturedRc, sid, taskId, TaskStatus.COMPLETED, result, null);
                            fireCompletionCallback(capturedRc, taskId, subAgentId, sid, result);
                        }
                    });
        } else if (spec instanceof TaskRunSpec.LocalTaskRunSpec local) {
            future =
                    CompletableFuture.supplyAsync(
                            () ->
                                    runLocalSupplier(
                                            capturedRc,
                                            sessionId,
                                            taskId,
                                            subAgentId,
                                            local.execution()),
                            executor);
        } else if (spec instanceof TaskRunSpec.RemoteTaskRunSpec remote) {
            future =
                    CompletableFuture.supplyAsync(
                            () ->
                                    runRemoteTask(
                                            capturedRc,
                                            sessionId,
                                            taskId,
                                            subAgentId,
                                            remote,
                                            true),
                            executor);
        } else {
            throw new IllegalArgumentException("Unsupported TaskRunSpec: " + spec.getClass());
        }

        BackgroundTask bgTask = new BackgroundTask(taskId, subAgentId, future);
        localTasks.put(localKey, bgTask);
        localTaskSessionIds.put(localKey, sessionId != null ? sessionId : "");
        localTaskContexts.put(localKey, capturedRc);
        return bgTask;
    }

    private String runLocalSupplier(
            RuntimeContext rc,
            String sessionId,
            String taskId,
            String subAgentId,
            Supplier<String> taskExecution) {
        Optional<TaskRecord> latest =
                workspaceManager.readTaskRecord(rc, parentAgentId, sessionId, taskId);
        if (latest.isPresent() && latest.get().isCancelRequested()) {
            markCancelled(rc, sessionId, taskId);
            return null;
        }

        updateStatus(rc, sessionId, taskId, TaskStatus.RUNNING, null, null);
        try {
            String result = taskExecution.get();
            Optional<TaskRecord> afterRun =
                    workspaceManager.readTaskRecord(rc, parentAgentId, sessionId, taskId);
            if (afterRun.isPresent() && afterRun.get().isCancelRequested()) {
                markCancelled(rc, sessionId, taskId);
                return null;
            }
            updateStatus(rc, sessionId, taskId, TaskStatus.COMPLETED, result, null);
            fireCompletionCallback(rc, taskId, subAgentId, sessionId, result);
            return result;
        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            updateStatus(rc, sessionId, taskId, TaskStatus.FAILED, null, errMsg);
            fireCompletionCallback(rc, taskId, subAgentId, sessionId, null);
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    private String runRemoteTask(
            RuntimeContext rc,
            String sessionId,
            String taskId,
            String subAgentId,
            TaskRunSpec.RemoteTaskRunSpec remote,
            boolean submitRemote) {
        RemoteTarget target = new RemoteTarget(remote.baseUrl(), remote.headers());
        try {
            Optional<TaskRecord> latest =
                    workspaceManager.readTaskRecord(rc, parentAgentId, sessionId, taskId);
            if (latest.isPresent() && latest.get().isCancelRequested()) {
                markCancelled(rc, sessionId, taskId);
                return null;
            }
            if (submitRemote) {
                RemoteSubmitContext context =
                        remote.context() != null ? remote.context() : RemoteSubmitContext.empty();
                transport.submit(target, taskId, subAgentId, remote.input(), context);
                updateStatus(rc, sessionId, taskId, TaskStatus.RUNNING, null, null);
            }
            String result = pollRemoteUntilDone(rc, sessionId, taskId, target);
            if (result != null) {
                fireCompletionCallback(rc, taskId, subAgentId, sessionId, result);
            }
            return result;
        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            updateStatus(rc, sessionId, taskId, TaskStatus.FAILED, null, errMsg);
            fireCompletionCallback(rc, taskId, subAgentId, sessionId, null);
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    private String pollRemoteUntilDone(
            RuntimeContext rc, String sessionId, String taskId, RemoteTarget target)
            throws Exception {
        int attempt = 0;
        boolean wasAwaitingConfirm = false;
        while (!Thread.currentThread().isInterrupted()) {
            Optional<TaskRecord> wr =
                    workspaceManager.readTaskRecord(rc, parentAgentId, sessionId, taskId);
            if (wr.isPresent() && wr.get().isCancelRequested()) {
                try {
                    transport.cancel(target, taskId);
                } catch (Exception ex) {
                    log.debug("Remote cancel after local cancel flag: {}", ex.getMessage());
                }
                markCancelled(rc, sessionId, taskId);
                return null;
            }
            RemoteTaskStatus st = transport.getStatus(target, taskId);
            if (st.isAwaitingConfirm()) {
                wasAwaitingConfirm = true;
                updateAwaitingConfirm(rc, sessionId, taskId, true, st.pendingConfirms());
                Thread.sleep(awaitingConfirmPollMs);
                continue;
            }
            if (wasAwaitingConfirm) {
                updateAwaitingConfirm(rc, sessionId, taskId, false, null);
                wasAwaitingConfirm = false;
            }
            if (st.isTerminalSuccess()) {
                String result = transport.waitForResult(target, taskId, 120);
                updateStatus(rc, sessionId, taskId, TaskStatus.COMPLETED, result, null);
                return result;
            }
            if (st.isTerminalFailure()) {
                String err = st.error() != null ? st.error() : "remote task error";
                updateStatus(rc, sessionId, taskId, TaskStatus.FAILED, null, err);
                throw new RuntimeException(err);
            }
            if (st.isCancelled()) {
                markCancelled(rc, sessionId, taskId);
                return null;
            }
            // pending、running、空状态：使用指数退避继续轮询
            long sleepMs = Math.min(5_000L, 200L * (1L << Math.min(attempt++, 4)));
            Thread.sleep(sleepMs);
        }
        Thread.currentThread().interrupt();
        markCancelled(rc, sessionId, taskId);
        return null;
    }

    /**
     * 持久化 {@link TaskRecord#isAwaitingConfirm()} 与
     * {@link TaskRecord#getPendingConfirms()}，不改动 {@link TaskRecord#getStatus()}；
     * 暂停等待用户确认时，任务保持 {@code RUNNING}。记录已达终态时不执行操作；
     * 等待标志和待确认列表未变化时也不执行操作（避免在等待确认期间每次轮询
     * 都重写工作区 JSON）。
     */
    private void updateAwaitingConfirm(
            RuntimeContext rc,
            String sessionId,
            String taskId,
            boolean awaiting,
            List<RemotePendingConfirm> pendingConfirms) {
        Optional<TaskRecord> existing =
                workspaceManager.readTaskRecord(rc, parentAgentId, sessionId, taskId);
        if (existing.isEmpty()) {
            return;
        }
        TaskRecord record = existing.get();
        if (record.getStatus() != null && record.getStatus().isTerminal()) {
            return;
        }
        List<RemotePendingConfirm> nextPending = awaiting ? pendingConfirms : null;
        if (record.isAwaitingConfirm() == awaiting
                && pendingConfirmsUnchanged(record.getPendingConfirms(), nextPending)) {
            return;
        }
        record.setAwaitingConfirm(awaiting);
        record.setPendingConfirms(nextPending);
        persistRecord(rc, sessionId, record);
    }

    private static boolean pendingConfirmsUnchanged(
            List<RemotePendingConfirm> current, List<RemotePendingConfirm> next) {
        if (current == null || current.isEmpty()) {
            return next == null || next.isEmpty();
        }
        if (next == null || next.isEmpty()) {
            return false;
        }
        return current.equals(next);
    }

    @Override
    public BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId) {
        BackgroundTask local = localTasks.get(localKey(sessionId, taskId));
        if (local != null) {
            return local;
        }
        // 回退到工作区记录，构造合成的已完成／失败 BackgroundTask
        Optional<TaskRecord> record =
                workspaceManager.readTaskRecord(
                        rc != null ? rc : RuntimeContext.empty(), parentAgentId, sessionId, taskId);
        return record.map(r -> syntheticTask(rc != null ? rc : RuntimeContext.empty(), r))
                .orElse(null);
    }

    @Override
    public Collection<BackgroundTask> listTasks(
            RuntimeContext rc, String sessionId, TaskStatus filter) {
        RuntimeContext effRc = rc != null ? rc : RuntimeContext.empty();
        Collection<TaskRecord> records =
                workspaceManager.listTaskRecords(effRc, parentAgentId, sessionId);

        List<BackgroundTask> result = new ArrayList<>();
        for (TaskRecord wsRecord : records) {
            String key = localKey(sessionId, wsRecord.getTaskId());
            BackgroundTask local = localTasks.get(key);
            // 有本地句柄时使用它（实时状态）；否则回退到工作区记录。
            // 绝不使用旧本地句柄的 RUNNING 状态覆盖工作区中的终态
            // 记录。
            BackgroundTask effective;
            if (local != null && !wsRecord.getStatus().isTerminal()) {
                effective = local;
            } else {
                effective = syntheticTask(effRc, wsRecord);
            }
            if (filter == null || effective.getTaskStatus() == filter) {
                result.add(effective);
            }
        }
        return result;
    }

    @Override
    public boolean cancelTask(RuntimeContext rc, String sessionId, String taskId) {
        RuntimeContext effRc = rc != null ? rc : RuntimeContext.empty();
        boolean found = false;

        BackgroundTask local = localTasks.get(localKey(sessionId, taskId));
        if (local != null) {
            found = true;
        }

        // 始终将 cancelRequested 标志写入工作区，以进行跨节点协调
        Optional<TaskRecord> existing = persistCancellation(effRc, sessionId, taskId);
        if (existing.isPresent()) {
            TaskRecord snapshot = existing.get();
            boolean agentProtocol =
                    snapshot.isAgentProtocolTransport() && snapshot.getRemoteBaseUrl() != null;

            // 完成监听器必须观察到已持久化的取消状态，而不是仍在运行的记录。
            if (local != null) local.cancel(true);

            if (agentProtocol) {
                try {
                    transport.cancel(
                            new RemoteTarget(
                                    snapshot.getRemoteBaseUrl(), snapshot.getRemoteHeaders()),
                            taskId);
                } catch (Exception e) {
                    log.warn("Remote cancel failed for task {}: {}", taskId, e.getMessage());
                }
            }
            return true;
        }

        if (local != null) local.cancel(true);
        return found;
    }

    // ---- 阶段 B-3 推送投递 -----------------------------------------------------------

    @Override
    public List<TaskDelivery> findPendingDeliveries(RuntimeContext rc, String sessionId) {
        RuntimeContext effRc = rc != null ? rc : RuntimeContext.empty();
        Collection<TaskRecord> records =
                workspaceManager.listTaskRecords(effRc, parentAgentId, sessionId);
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<TaskRecord> ordered = new ArrayList<>(records);
        ordered.sort(
                Comparator.comparing(
                        TaskRecord::getLastUpdatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        List<TaskDelivery> out = new ArrayList<>();
        for (TaskRecord r : ordered) {
            if (r.getStatus() == null || !r.getStatus().isTerminal()) continue;
            if (r.isDelivered()) continue;
            out.add(
                    new TaskDelivery(
                            r.getTaskId(),
                            r.getSubAgentId(),
                            r.getStatus(),
                            r.getResult(),
                            r.getErrorMessage(),
                            r.getLastUpdatedAt()));
        }
        return out;
    }

    /**
     * 在持久化记录上写入 {@code deliveredAt}。此操作幂等：第一次非 null 写入生效；
     * 后续调用直接退出，不访问工作区存储。使用独立的读取－修改－写入路径，
     * 而不是经过 {@link #updateStatus}，避免心跳／孤立任务清理器通过
     * RUNNING／FAILED 写入意外覆盖此字段（这些路径围绕状态相关字段重建记录）。
     */
    @Override
    public void markDelivered(RuntimeContext rc, String sessionId, String taskId) {
        RuntimeContext effRc = rc != null ? rc : RuntimeContext.empty();
        Optional<TaskRecord> existing =
                workspaceManager.readTaskRecord(effRc, parentAgentId, sessionId, taskId);
        if (existing.isEmpty()) return;
        TaskRecord r = existing.get();
        if (r.getDeliveredAt() != null) return; // 已经投递
        r.setDeliveredAt(Instant.now());
        persistRecord(effRc, sessionId, r);
    }

    @Override
    public boolean isDelivered(RuntimeContext rc, String sessionId, String taskId) {
        RuntimeContext effRc = rc != null ? rc : RuntimeContext.empty();
        return workspaceManager
                .readTaskRecord(effRc, parentAgentId, sessionId, taskId)
                .map(TaskRecord::isDelivered)
                .orElse(false);
    }

    @Override
    public void shutdown() {
        if (maintenanceScheduler != null) {
            maintenanceScheduler.shutdown();
            try {
                if (!maintenanceScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    maintenanceScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                maintenanceScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (ownsExecutor && executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 为仍在运行的每个活动本地任务刷新工作区中的 {@code lastUpdatedAt}。
     * 按固定计划调用，使孤立任务清理器能够区分真正运行的任务与发起节点已消失的任务。
     *
     * <p>包可见，便于单元测试直接调用。
     */
    void heartbeat() {
        localTasks.forEach(
                (key, task) -> {
                    if (!task.isCompleted()) {
                        String sid = localTaskSessionIds.get(key);
                        if (sid == null) {
                            return;
                        }
                        RuntimeContext rc = localTaskContexts.get(key);
                        if (rc == null) {
                            rc = RuntimeContext.empty();
                        }
                        try {
                            updateStatus(rc, sid, task.getTaskId(), TaskStatus.RUNNING, null, null);
                        } catch (Exception e) {
                            log.debug(
                                    "Heartbeat update failed for task {}: {}",
                                    task.getTaskId(),
                                    e.getMessage());
                        }
                    }
                });
    }

    /**
     * 包可见入口，供需要调用默认清理路径的单元测试使用。
     */
    void sweepOrphanedTasksDefault_forTest() {
        sweepOrphanedTasksDefault();
    }

    private void sweepOrphanedTasksDefault() {
        Duration sweepInterval = Duration.ofSeconds(SWEEP_INTERVAL_MINUTES * 60L);
        // 通过 PeriodicGate 限频：单进程使用 LocalPeriodicGate，
        // 跨副本去重使用 StoreBacked。清理本身幂等，因此丢失执行资格不会造成危害。
        if (!periodicGate.tryClaim("task-sweep:" + parentAgentId, sweepInterval)) {
            log.debug("Skipping orphan sweep for {} — periodic gate denied claim", parentAgentId);
            return;
        }

        // recentWindow = 2 × 孤立超时 + 1 个清理周期。
        // 2 倍系数确保不会漏掉刚好位于孤立阈值的任务
        // （文件可能恰好写于 orphanTimeout 之前）。
        // 额外 1 个周期的缓冲用于处理时钟偏差和清理调度抖动。
        Duration orphanTimeout = Duration.ofMinutes(ORPHAN_TIMEOUT_MINUTES);
        Duration recentWindow = orphanTimeout.multipliedBy(2).plus(sweepInterval);
        sweepOrphanedTasks(orphanTimeout, recentWindow);
    }

    /**
     * 扫描此智能体的全部持久化任务记录；非终态、非远程任务的
     * {@code lastUpdatedAt} 早于 {@code orphanTimeout} 时，将其标记为
     * {@link TaskStatus#FAILED}。
     *
     * <p>任务的发起节点在写入终态前故障，导致 {@link #heartbeat()} 停止时，
     * 认为任务已经<em>孤立</em>。排除远程（{@code agent-protocol}）任务，
     * 因为它们的活性由远程端点决定，而不是由此节点的心跳决定。
     *
     * <p>支持 CAS 的远程存储会在冲突时重新检查已持久化的活性信息，
     * 避免与扫描并发的心跳被过时的孤立判定覆盖。
     *
     * <p>包可见，便于单元测试直接调用。
     *
     * @param orphanTimeout 非终态本地任务在被视为孤立之前允许缺少心跳的时长
     * @param recentWindow 为兼容性保留；维护包含旧文件，因为停机时长可能超过近期文件窗口
     */
    void sweepOrphanedTasks(Duration orphanTimeout, Duration recentWindow) {
        sweepOrphanedTasks(orphanTimeout, recentWindow, Instant.now());
    }

    /**
     * 使用指定清理时间清理孤立任务。
     *
     * <p>包可见，使测试无需依赖平台时钟分辨率即可验证超时边界。
     */
    void sweepOrphanedTasks(Duration orphanTimeout, Duration recentWindow, Instant sweepTime) {
        try {
            Collection<WorkspaceManager.ScopedTaskRecord> all =
                    workspaceManager.listTaskRecordsForMaintenance(parentAgentId);
            Instant threshold = sweepTime.minus(orphanTimeout);
            for (WorkspaceManager.ScopedTaskRecord scoped : all) {
                TaskRecord record = scoped.record();
                if (record.getStatus() == null || record.getStatus().isTerminal()) {
                    continue;
                }
                if (record.isAgentProtocolTransport()) {
                    // 远程任务通过轮询自行处理活性。
                    continue;
                }
                Instant lastUpdated = record.getLastUpdatedAt();
                // 任务一到达超时边界就视为过期。除符合超时契约外，
                // 这还避免系统时钟为最后更新时间和本次清理返回相同时间点时，
                // 零超时任务仍保持 RUNNING。
                if (lastUpdated == null || lastUpdated.isAfter(threshold)) {
                    continue;
                }
                String sid = record.getParentSessionId();
                String tid = record.getTaskId();
                // 此节点仍持有活动本地 future 时跳过：心跳仍然有效。
                String key = localKey(sid, tid);
                BackgroundTask local = localTasks.get(key);
                if (local != null && !local.isCompleted()) {
                    continue;
                }
                RuntimeContext updateRc = scoped.context();
                long staleSecs = Duration.between(lastUpdated, sweepTime).getSeconds();
                String orphanMsg =
                        String.format(
                                "executor lost: no heartbeat for %d seconds (last updated: %s)",
                                staleSecs, lastUpdated);
                if (workspaceManager.failOrphanedTask(
                        updateRc, parentAgentId, sid, tid, threshold, orphanMsg)) {
                    log.warn(
                            "Orphaned task {} (session {}) last updated {}s ago, marked FAILED",
                            tid,
                            sid,
                            staleSecs);
                }
            }
        } catch (Exception e) {
            log.warn("Orphan sweeper encountered an error: {}", e.getMessage());
        }
    }

    // ---- 私有辅助方法 ----

    private static String localKey(String sessionId, String taskId) {
        String s = sessionId != null ? sessionId : "_";
        return s + ":" + taskId;
    }

    private void persistRecord(RuntimeContext rc, String sessionId, TaskRecord record) {
        try {
            workspaceManager.writeTaskRecord(rc, parentAgentId, sessionId, record);
        } catch (Exception e) {
            log.warn(
                    "Failed to persist task record {} for session {}: {}",
                    record.getTaskId(),
                    sessionId,
                    e.getMessage());
        }
    }

    // 将本地状态变更与取消操作串行化。否则，工作线程若在取消操作的
    // 读写之间启动，可能在完成监听器运行前恢复 RUNNING。
    private synchronized Optional<TaskRecord> persistCancellation(
            RuntimeContext rc, String sessionId, String taskId) {
        Optional<TaskRecord> existing =
                workspaceManager.readTaskRecord(rc, parentAgentId, sessionId, taskId);
        existing.ifPresent(
                record -> {
                    record.setCancelRequested(true);
                    if (!record.getStatus().isTerminal()) record.setStatus(TaskStatus.CANCELLED);
                    persistRecord(rc, sessionId, record);
                });
        return existing;
    }

    private synchronized void updateStatus(
            RuntimeContext rc,
            String sessionId,
            String taskId,
            TaskStatus status,
            String result,
            String error) {
        // 【学习提示】这里的 synchronized 只串行化同一仓库实例内的更新，
        // 不等价于跨进程锁；学习分布式一致性时还需沿 WorkspaceManager 检查存储层语义。
        Optional<TaskRecord> existing =
                workspaceManager.readTaskRecord(rc, parentAgentId, sessionId, taskId);
        // 保护 1：终态不可变，绝不将 COMPLETED、FAILED 或 CANCELLED
        // 覆盖为其他状态。这能防止仍在运行的线程迟到的 COMPLETED／FAILED 写入
        // 覆盖 cancelTask() 并发设置的 CANCELLED 状态。
        if (existing.isPresent()
                && existing.get().getStatus() != null
                && existing.get().getStatus().isTerminal()) {
            return;
        }
        // 保护 2：若已请求取消，但工作区记录尚未到达终态
        // （例如心跳与 cancelTask() 竞争），则拒绝持久化非终态更新。
        // 这会阻止心跳将 RUNNING 写回刚由 cancelTask()
        // 设置 cancelRequested 标志的记录。
        if (!status.isTerminal() && existing.isPresent() && existing.get().isCancelRequested()) {
            return;
        }
        TaskRecord record =
                existing.orElseGet(
                        () -> {
                            TaskRecord r = new TaskRecord();
                            r.setTaskId(taskId);
                            r.setParentAgentId(parentAgentId);
                            r.setParentSessionId(sessionId);
                            return r;
                        });
        record.setStatus(status);
        if (result != null) {
            record.setResult(result);
        }
        if (error != null) {
            record.setErrorMessage(error);
        }
        persistRecord(rc, sessionId, record);
    }

    private void markCancelled(RuntimeContext rc, String sessionId, String taskId) {
        updateStatus(rc, sessionId, taskId, TaskStatus.CANCELLED, null, null);
    }

    /**
     * 从持久化 {@link TaskRecord} 构造合成的 {@link BackgroundTask}。
     * 其 future 已完成（或失败／取消），以反映存储的终态。
     */
    private BackgroundTask syntheticTask(RuntimeContext rc, TaskRecord record) {
        CompletableFuture<String> future;
        switch (record.getStatus()) {
            case COMPLETED -> future = CompletableFuture.completedFuture(record.getResult());
            case FAILED -> {
                future = new CompletableFuture<>();
                future.completeExceptionally(
                        new RuntimeException(
                                record.getErrorMessage() != null
                                        ? record.getErrorMessage()
                                        : "Task failed"));
            }
            case CANCELLED -> {
                future = new CompletableFuture<>();
                future.cancel(false);
            }
            default -> {
                if (record.isAgentProtocolTransport()
                        && record.getRemoteBaseUrl() != null
                        && !record.getStatus().isTerminal()) {
                    // 缓存在 localTasks 中，避免反复调用 getTask/listTasks
                    // 为同一个远程任务启动多个并发轮询器。
                    String sid =
                            record.getParentSessionId() != null ? record.getParentSessionId() : "";
                    String lk = localKey(sid, record.getTaskId());
                    RuntimeContext capturedRc = rc != null ? rc : RuntimeContext.empty();
                    BackgroundTask cached =
                            localTasks.computeIfAbsent(
                                    lk,
                                    k -> {
                                        CompletableFuture<String> f =
                                                CompletableFuture.supplyAsync(
                                                        () ->
                                                                runRemoteTask(
                                                                        capturedRc,
                                                                        sid,
                                                                        record.getTaskId(),
                                                                        record.getSubAgentId(),
                                                                        new TaskRunSpec
                                                                                .RemoteTaskRunSpec(
                                                                                record
                                                                                        .getRemoteBaseUrl(),
                                                                                record
                                                                                                        .getRemoteHeaders()
                                                                                                != null
                                                                                        ? record
                                                                                                .getRemoteHeaders()
                                                                                        : Map.of(),
                                                                                record
                                                                                        .getSubAgentId(),
                                                                                ""),
                                                                        false),
                                                        executor);
                                        return new BackgroundTask(
                                                record.getTaskId(), record.getSubAgentId(), f);
                                    });
                    localTaskSessionIds.putIfAbsent(lk, sid);
                    localTaskContexts.putIfAbsent(lk, capturedRc);
                    return cached;
                } else {
                    // 状态为 PENDING 或 RUNNING 但没有本地 future：跨节点本地任务。
                    future = new CompletableFuture<>();
                }
            }
        }
        return new BackgroundTask(record.getTaskId(), record.getSubAgentId(), future);
    }

    private void fireCompletionCallback(
            RuntimeContext rc, String taskId, String subAgentId, String sessionId, String result) {
        TaskCompletionCallback cb = this.completionCallback;
        if (cb == null
                || (rc != null && Boolean.TRUE.equals(rc.get(SUPPRESS_COMPLETION_CALLBACK)))) {
            return;
        }
        try {
            cb.onCompleted(rc, taskId, subAgentId, sessionId, result);
        } catch (Exception e) {
            log.warn("TaskCompletionCallback failed for task {}: {}", taskId, e.getMessage(), e);
        }
    }
}
