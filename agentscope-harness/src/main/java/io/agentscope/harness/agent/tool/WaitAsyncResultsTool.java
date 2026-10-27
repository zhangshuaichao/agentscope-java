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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 阻塞等待异步结果的工具，可等待特定任务集合的完成屏障，
 * 或按旧版行为等待会话收件箱出现任意消息。
 *
 * <p>优先使用屏障模式（{@code task_ids} 或 {@code wait_all=true}）：
 * 等待集合全部到达终态时，此工具在返回值中嵌入每个任务的状态和结果，
 * 使模型无需等待结果回推即可立即继续推理。
 *
 * <p>未提供 {@code task_ids} / {@code wait_all} 时，保留旧版“任意收件箱消息”行为：
 * 只要出现<em>任意</em>收件箱消息就返回，并非等待全部完成的屏障。
 *
 * <p>通过以下保护避免无界阻塞：
 * <ul>
 *   <li>无论 LLM 提供何值，超时均限制在 {@value #MAX_TIMEOUT_SECONDS} 秒以内。
 *   <li>连续 {@value #MAX_CONSECUTIVE_EMPTY_WAITS} 次超时且无结果后，
 *       拒绝进一步阻塞等待，并引导 LLM 使用非阻塞替代方案。
 * </ul>
 */
public class WaitAsyncResultsTool {

    private static final Logger log = LoggerFactory.getLogger(WaitAsyncResultsTool.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private static final int MAX_CONSECUTIVE_EMPTY_WAITS = 2;
    private static final long POLL_INTERVAL_MS = 3000;

    private final MessageBus messageBus;
    private final TaskRepository taskRepository;
    private final LongSupplier currentTimeMillis;
    private final Sleeper sleeper;
    private final long pollIntervalMs;
    private final ConcurrentHashMap<String, AtomicInteger> consecutiveEmptyWaitsBySession =
            new ConcurrentHashMap<>();
    private volatile BooleanSupplier externalWorkProbe;

    public WaitAsyncResultsTool(MessageBus messageBus) {
        this(messageBus, null);
    }

    public WaitAsyncResultsTool(MessageBus messageBus, TaskRepository taskRepository) {
        this(
                messageBus,
                taskRepository,
                System::currentTimeMillis,
                Thread::sleep,
                POLL_INTERVAL_MS);
    }

    /**
     * 与 {@link #WaitAsyncResultsTool(MessageBus, TaskRepository)} 相同，
     * 但可为测试自定义轮询粒度：在预算中途完成的任务会在一个测试间隔后被发现，
     * 而不是等待一个生产间隔。
     */
    WaitAsyncResultsTool(
            MessageBus messageBus, TaskRepository taskRepository, long pollIntervalMs) {
        this(messageBus, taskRepository, System::currentTimeMillis, Thread::sleep, pollIntervalMs);
    }

    /**
     * 允许超时测试推进已逝时间，无需等待真实截止时刻。
     */
    WaitAsyncResultsTool(
            MessageBus messageBus,
            TaskRepository taskRepository,
            LongSupplier currentTimeMillis,
            Sleeper sleeper) {
        this(messageBus, taskRepository, currentTimeMillis, sleeper, POLL_INTERVAL_MS);
    }

    /**
     * 允许超时测试推进已逝时间而无需等待真实截止时刻，
     * 并缩短轮询粒度，使等待及时结束。
     */
    WaitAsyncResultsTool(
            MessageBus messageBus,
            TaskRepository taskRepository,
            LongSupplier currentTimeMillis,
            Sleeper sleeper,
            long pollIntervalMs) {
        this.messageBus = messageBus;
        this.taskRepository = taskRepository;
        this.currentTimeMillis = currentTimeMillis;
        this.sleeper = sleeper;
        this.pollIntervalMs = pollIntervalMs;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * 注册探针，用于检查子智能体任务仓库之外管理的未完成工作
     * （当前为 AgentTeams 队友）。否则，没有子智能体任务的负责人会在队友
     * 仍运行时被告知停止等待。
     */
    public WaitAsyncResultsTool setExternalWorkProbe(BooleanSupplier probe) {
        this.externalWorkProbe = probe;
        return this;
    }

    private boolean hasExternalWork() {
        BooleanSupplier probe = this.externalWorkProbe;
        if (probe == null) {
            return false;
        }
        try {
            return probe.getAsBoolean();
        } catch (RuntimeException e) {
            log.debug("external work probe failed: {}", e.toString());
            return false;
        }
    }

    @Tool(
            name = "wait_async_results",
            description =
                    "Wait for background async tool, subagent, or teammate results. "
                            + "Prefer barrier mode: task_ids waits until those specific tasks are "
                            + "all terminal and returns their results in this tool output; "
                            + "wait_all=true waits for the snapshot of currently running tasks "
                            + "(tasks created while waiting are not added) and also returns their "
                            + "results. "
                            + "Without task_ids and without wait_all, this is legacy inbox-any "
                            + "mode: returns when ANY inbox message arrives — not wait-all. For "
                            + "must-collect-all groups use task_ids or wait_all=true. "
                            + "Also covers AgentTeams teammate work via the external-work probe. "
                            + "Max timeout is 120 seconds. "
                            + "If you have already waited without results, use task_list or "
                            + "task_output(block=false) to check status instead of waiting again.",
            readOnly = true)
    public ToolResultBlock waitForResults(
            @ToolParam(
                            name = "timeout_seconds",
                            description =
                                    "Maximum seconds to wait. Default 60, max 120. "
                                            + "Values above 120 are clamped.")
                    Integer timeoutSeconds,
            @ToolParam(
                            name = "task_ids",
                            description =
                                    "Optional comma-separated task IDs to wait for. When set, the "
                                            + "tool returns only after all listed tasks are "
                                            + "terminal (or timeout), and embeds each task's "
                                            + "result in the response.",
                            required = false)
                    String taskIds,
            @ToolParam(
                            name = "wait_all",
                            description =
                                    "Optional barrier mode. When true and task_ids is empty, wait"
                                        + " for the snapshot of currently non-terminal tasks in"
                                        + " this session and embed their results. Tasks created"
                                        + " later are not added to the wait set. Not the same as"
                                        + " legacy no-arg inbox-any wait.",
                            required = false)
                    Boolean waitAll,
            RuntimeContext runtimeContext) {
        try {
            return doWaitForResults(timeoutSeconds, taskIds, waitAll, runtimeContext);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResultBlock.text("status: interrupted")
                    .withState(ToolResultState.INTERRUPTED);
        }
    }

    private ToolResultBlock doWaitForResults(
            Integer timeoutSeconds, String taskIds, Boolean waitAll, RuntimeContext runtimeContext)
            throws InterruptedException {

        String sessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
        if (sessionId == null) {
            return ToolResultBlock.text("Cannot wait: no session context available.")
                    .withState(ToolResultState.ERROR);
        }

        AtomicInteger emptyWaits =
                consecutiveEmptyWaitsBySession.computeIfAbsent(
                        sessionId, k -> new AtomicInteger(0));
        List<String> explicitTaskIds = parseTaskIds(taskIds);
        if (!explicitTaskIds.isEmpty() || Boolean.TRUE.equals(waitAll)) {
            return waitForTaskBarrier(
                    timeoutSeconds, runtimeContext, sessionId, explicitTaskIds, emptyWaits);
        }

        if (emptyWaits.get() >= MAX_CONSECUTIVE_EMPTY_WAITS) {
            Boolean hasMessages = messageBus.inboxHasMessages(sessionId).block();
            if (Boolean.TRUE.equals(hasMessages)) {
                log.info(
                        "wait_async_results: budget was exhausted but inbox now has messages,"
                                + " resetting counter, session={}",
                        sessionId);
                emptyWaits.set(0);
                return ToolResultBlock.success(
                        "Async results have arrived (inbox-any: at least one message). "
                                + "Continue reasoning — the results will be injected into your"
                                + " context automatically. Prefer wait_async_results(task_ids=...)"
                                + " or wait_all=true when you need every task in a group.");
            }
            log.info(
                    "wait_async_results: rejected — {} consecutive empty waits reached, session={}",
                    emptyWaits.get(),
                    sessionId);
            return ToolResultBlock.success(
                    "Wait budget exhausted: you have already waited "
                            + emptyWaits.get()
                            + " times without receiving results. "
                            + "Do NOT call wait_async_results again. Instead use task_list to"
                            + " check task status, or task_output(block=false) to poll for"
                            + " results without blocking.");
        }

        if (taskRepository != null) {
            Collection<BackgroundTask> tasks =
                    taskRepository.listTasks(runtimeContext, sessionId, null);
            boolean hasNonTerminal =
                    tasks.stream().anyMatch(task -> !task.getTaskStatus().isTerminal())
                            || hasExternalWork();
            if (!hasNonTerminal) {
                Boolean hasMessages = messageBus.inboxHasMessages(sessionId).block();
                if (!Boolean.TRUE.equals(hasMessages)) {
                    log.info(
                            "wait_async_results: all tasks terminal and inbox empty,"
                                    + " returning immediately, session={}",
                            sessionId);
                    if (tasks.isEmpty()) {
                        return ToolResultBlock.success(
                                "status: no_tasks\n"
                                        + "No background tasks exist in this session. An empty"
                                        + " completion inbox is not evidence of running work."
                                        + " Continue the assigned work or report the missing"
                                        + " capability; do not keep waiting.");
                    }
                    return ToolResultBlock.text(
                                    "All background tasks have completed and no pending results"
                                            + " in inbox. Use task_list to review results, or"
                                            + " task_output(task_id) to read a specific result.")
                            .withState(barrierState(List.copyOf(tasks)));
                }
            }
        }

        int timeout = normalizeTimeout(timeoutSeconds, sessionId);

        log.info(
                "wait_async_results: waiting up to {}s for any inbox message (legacy inbox-any),"
                        + " session={}",
                timeout,
                sessionId);

        long deadlineMs = currentTimeMillis.getAsLong() + (timeout * 1000L);

        while (true) {
            long remainingMs = deadlineMs - currentTimeMillis.getAsLong();
            if (remainingMs <= 0) {
                break;
            }
            Boolean hasMessages = messageBus.inboxHasMessages(sessionId).block();
            if (Boolean.TRUE.equals(hasMessages)) {
                log.info("wait_async_results: inbox has messages, session={}", sessionId);
                emptyWaits.set(0);
                return ToolResultBlock.success(
                        "Async results have arrived (inbox-any: at least one message). "
                                + "Continue reasoning — the results will be injected into your"
                                + " context automatically. Prefer wait_async_results(task_ids=...)"
                                + " or wait_all=true when you need every task in a group.");
            }
            // 将休眠限制在剩余预算内，确保工具不超出调用方的超时限制。
            sleepForPollInterval(remainingMs);
        }

        int emptyCount = emptyWaits.incrementAndGet();
        log.info(
                "wait_async_results: timeout after {}s (consecutive empty waits: {}), session={}",
                timeout,
                emptyCount,
                sessionId);
        return ToolResultBlock.success(
                "Timeout after "
                        + timeout
                        + "s. No async results yet (empty wait "
                        + emptyCount
                        + "/"
                        + MAX_CONSECUTIVE_EMPTY_WAITS
                        + "). "
                        + "Use task_list to check task status, or task_output(block=false) to"
                        + " poll without blocking.");
    }

    public ToolResultBlock waitForResults(Integer timeoutSeconds, RuntimeContext runtimeContext) {
        return waitForResults(timeoutSeconds, null, null, runtimeContext);
    }

    private ToolResultBlock waitForTaskBarrier(
            Integer timeoutSeconds,
            RuntimeContext runtimeContext,
            String sessionId,
            List<String> explicitTaskIds,
            AtomicInteger emptyWaits)
            throws InterruptedException {
        if (taskRepository == null) {
            return ToolResultBlock.text(
                            "Cannot wait for task completion: task repository is unavailable. "
                                    + "Use task_list or task_output(block=false) to check"
                                    + " status.")
                    .withState(ToolResultState.ERROR);
        }

        if (!explicitTaskIds.isEmpty()) {
            List<String> missingTaskIds =
                    explicitTaskIds.stream()
                            .filter(
                                    id ->
                                            taskRepository.getTask(runtimeContext, sessionId, id)
                                                    == null)
                            .toList();
            if (!missingTaskIds.isEmpty()) {
                return ToolResultBlock.text(
                                "status: not_found\nCannot wait: unknown task_ids "
                                        + missingTaskIds
                                        + ". No running work is implied. Use task_list to find"
                                        + " valid task IDs.")
                        .withState(ToolResultState.ERROR);
            }
        }

        List<String> waitSet =
                !explicitTaskIds.isEmpty()
                        ? explicitTaskIds
                        : snapshotNonTerminalTaskIds(runtimeContext, sessionId);
        if (waitSet.isEmpty()) {
            log.info(
                    "wait_async_results: wait_all snapshot empty,"
                            + " returning immediately, session={}",
                    sessionId);
            emptyWaits.set(0);
            return ToolResultBlock.success(
                    "No running background tasks found at wait start. Continue reasoning, "
                            + "or use task_list to review existing task status.");
        }

        ToolResultBlock completed = completeTaskBarrierIfReady(runtimeContext, sessionId, waitSet);
        if (completed != null) {
            emptyWaits.set(0);
            return completed;
        }

        ToolResultBlock rejected = rejectIfWaitBudgetExhausted(sessionId, emptyWaits);
        if (rejected != null) {
            return rejected;
        }

        int timeout = normalizeTimeout(timeoutSeconds, sessionId);
        log.info(
                "wait_async_results: waiting up to {}s for task barrier, session={}, tasks={}",
                timeout,
                sessionId,
                waitSet);

        long deadlineMs = currentTimeMillis.getAsLong() + (timeout * 1000L);
        while (true) {
            completed = completeTaskBarrierIfReady(runtimeContext, sessionId, waitSet);
            if (completed != null) {
                emptyWaits.set(0);
                return completed;
            }
            long remainingMs = deadlineMs - currentTimeMillis.getAsLong();
            if (remainingMs <= 0) {
                break;
            }
            sleepForPollInterval(remainingMs);
        }

        int emptyCount = emptyWaits.incrementAndGet();
        log.info(
                "wait_async_results: timeout after {}s waiting for tasks {}"
                        + " (consecutive empty waits: {}), session={}",
                timeout,
                waitSet,
                emptyCount,
                sessionId);
        return ToolResultBlock.success(
                "Timeout after "
                        + timeout
                        + "s. Requested background tasks are not all terminal yet (empty wait "
                        + emptyCount
                        + "/"
                        + MAX_CONSECUTIVE_EMPTY_WAITS
                        + "). "
                        + "Use task_list to check task status, or task_output(block=false) to"
                        + " poll without blocking.");
    }

    private ToolResultBlock completeTaskBarrierIfReady(
            RuntimeContext runtimeContext, String sessionId, List<String> waitSet) {
        List<BackgroundTask> terminalTasks = new ArrayList<>(waitSet.size());
        for (String taskId : waitSet) {
            BackgroundTask task = taskRepository.getTask(runtimeContext, sessionId, taskId);
            if (task == null) {
                log.info(
                        "wait_async_results: task barrier missing task {}, session={}",
                        taskId,
                        sessionId);
                return ToolResultBlock.text(
                                "Cannot wait: task_id "
                                        + taskId
                                        + " is no longer available. Use task_list to refresh"
                                        + " task status.")
                        .withState(ToolResultState.ERROR);
            }
            if (!task.getTaskStatus().isTerminal()) {
                return null;
            }
            terminalTasks.add(task);
        }
        log.info(
                "wait_async_results: task barrier complete, tasks={}, session={}",
                waitSet,
                sessionId);
        return formatBarrierResults(runtimeContext, sessionId, terminalTasks);
    }

    /**
     * 将每个终态任务的状态／结果嵌入工具返回值，使父级无需等待收件箱回推
     * 即可继续。将任务标记为已投递，避免重复提醒。
     */
    private ToolResultBlock formatBarrierResults(
            RuntimeContext runtimeContext, String sessionId, List<BackgroundTask> tasks) {
        StringBuilder sb = new StringBuilder();
        sb.append("All requested background tasks are terminal. Results are included below.")
                .append('\n');
        for (BackgroundTask task : tasks) {
            task.updateLastCheckedAt();
            if (task.isCompleted() && task.getTaskStatus().isTerminal()) {
                try {
                    taskRepository.markDelivered(runtimeContext, sessionId, task.getTaskId());
                } catch (RuntimeException ignore) {
                    // 尽力而为：失败只可能造成重复推送提醒。
                }
            }
            sb.append("---").append('\n');
            sb.append("task_id: ").append(task.getTaskId()).append('\n');
            if (task.getAgentId() != null) {
                sb.append("agent_id: ").append(task.getAgentId()).append('\n');
            }
            sb.append("status: ").append(task.getStatus()).append('\n');
            if (task.getResult() != null) {
                sb.append("Result:\n").append(task.getResult()).append('\n');
            } else if (task.getError() != null) {
                Exception err = task.getError();
                sb.append("Error:\n").append(err.getMessage()).append('\n');
                if (err.getCause() != null) {
                    sb.append("Cause: ").append(err.getCause().getMessage()).append('\n');
                }
            } else {
                sb.append("Task completed with no result.").append('\n');
            }
        }
        return ToolResultBlock.text(sb.toString().trim()).withState(barrierState(tasks));
    }

    private static ToolResultState barrierState(List<BackgroundTask> tasks) {
        boolean interrupted = false;
        for (BackgroundTask task : tasks) {
            if (task.getTaskStatus() == TaskStatus.FAILED) {
                return ToolResultState.ERROR;
            }
            if (task.getTaskStatus() == TaskStatus.CANCELLED) {
                interrupted = true;
            }
        }
        return interrupted ? ToolResultState.INTERRUPTED : ToolResultState.SUCCESS;
    }

    private ToolResultBlock rejectIfWaitBudgetExhausted(
            String sessionId, AtomicInteger emptyWaits) {
        if (emptyWaits.get() < MAX_CONSECUTIVE_EMPTY_WAITS) {
            return null;
        }
        Boolean hasMessages = messageBus.inboxHasMessages(sessionId).block();
        if (Boolean.TRUE.equals(hasMessages)) {
            log.info(
                    "wait_async_results: budget was exhausted but inbox now has messages,"
                            + " resetting counter, session={}",
                    sessionId);
            emptyWaits.set(0);
            return ToolResultBlock.success(
                    "Async results have arrived (inbox-any: at least one message). "
                            + "Continue reasoning — the results will be injected into your"
                            + " context automatically. Prefer wait_async_results(task_ids=...)"
                            + " or wait_all=true when you need every task in a group.");
        }
        log.info(
                "wait_async_results: rejected — {} consecutive empty waits reached, session={}",
                emptyWaits.get(),
                sessionId);
        return ToolResultBlock.success(
                "Wait budget exhausted: you have already waited "
                        + emptyWaits.get()
                        + " times without receiving results. "
                        + "Do NOT call wait_async_results again. Instead use task_list to"
                        + " check task status, or task_output(block=false) to poll for"
                        + " results without blocking.");
    }

    private int normalizeTimeout(Integer timeoutSeconds, String sessionId) {
        int raw =
                timeoutSeconds != null && timeoutSeconds > 0
                        ? timeoutSeconds
                        : DEFAULT_TIMEOUT_SECONDS;
        int timeout = Math.min(raw, MAX_TIMEOUT_SECONDS);
        if (raw > MAX_TIMEOUT_SECONDS) {
            log.info(
                    "wait_async_results: clamped timeout from {}s to {}s, session={}",
                    raw,
                    timeout,
                    sessionId);
        }
        return timeout;
    }

    private void sleepForPollInterval(long remainingMs) throws InterruptedException {
        sleeper.sleep(Math.min(pollIntervalMs, remainingMs));
    }

    private List<String> parseTaskIds(String taskIds) {
        if (taskIds == null || taskIds.isBlank()) {
            return List.of();
        }
        List<String> parsed = new ArrayList<>();
        for (String raw : taskIds.split(",")) {
            String id = raw.trim();
            if (!id.isEmpty()) {
                parsed.add(id);
            }
        }
        return parsed;
    }

    private List<String> snapshotNonTerminalTaskIds(RuntimeContext rc, String sessionId) {
        Collection<BackgroundTask> tasks = taskRepository.listTasks(rc, sessionId, null);
        return tasks.stream()
                .filter(t -> !t.getTaskStatus().isTerminal())
                .map(BackgroundTask::getTaskId)
                .toList();
    }
}
