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
package io.agentscope.extensions.agentprotocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.protocol.RemoteAgentEvent;
import io.agentscope.harness.agent.subagent.protocol.RemoteConfirmDecision;
import io.agentscope.harness.agent.subagent.protocol.RemoteEventCodec;
import io.agentscope.harness.agent.subagent.protocol.RemoteEventType;
import io.agentscope.harness.agent.subagent.protocol.RemotePendingConfirm;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 在内存中保存执行句柄，并在控制平面持久化 {@link TaskRecord}。
 *
 * <p>每轮运行通过 {@link AgentFactory} 解析 {@link HarnessAgent}。工厂接收提交上下文，因此可以按任务路由。并发执行时应每次返回新实例（如 Spring prototype 作用域的 bean）；共享实例需要配置 {@code checkRunning(false)}。
 *
 * <p>任务元数据经 {@link ProtocolTaskRepository} 存入独立控制平面路径，而非执行智能体的工作区。调用方的 {@code context.attributes} 通过 {@link RuntimeContext} 传入本轮运行；其注入方式由 {@link RuntimeContextCustomizer} 控制。
 */
public final class AgentProtocolTaskStore {

    private static final Logger log = LoggerFactory.getLogger(AgentProtocolTaskStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 智能体暂停等待 HITL 确认时返回的哨兵值。 */
    static final String AWAITING_CONFIRM_SENTINEL = "__awaiting_confirm__";

    private final AgentFactory agentFactory;
    private final ProtocolTaskRepository taskRepository;
    private final AgentProtocolEventBus eventBus;
    private final AgentProtocolProperties properties;
    private final List<RuntimeContextCustomizer> runtimeContextCustomizers;
    private final ExecutorService executor =
            Executors.newCachedThreadPool(
                    r -> {
                        Thread t = new Thread(r);
                        t.setDaemon(true);
                        t.setName("agent-protocol-task-" + t.getId());
                        return t;
                    });

    private final Map<String, CompletableFuture<String>> futures = new ConcurrentHashMap<>();
    private final Map<String, SubmitContext> submitContexts = new ConcurrentHashMap<>();

    /** 按任务串行化恢复状态转换，防止并发恢复重复启动运行。 */
    private final ConcurrentHashMap<String, Object> taskLocks = new ConcurrentHashMap<>();

    public AgentProtocolTaskStore(
            AgentFactory agentFactory,
            ProtocolTaskRepository taskRepository,
            AgentProtocolEventBus eventBus,
            AgentProtocolProperties properties) {
        this(agentFactory, taskRepository, eventBus, properties, List.of());
    }

    public AgentProtocolTaskStore(
            AgentFactory agentFactory,
            ProtocolTaskRepository taskRepository,
            AgentProtocolEventBus eventBus,
            AgentProtocolProperties properties,
            List<RuntimeContextCustomizer> runtimeContextCustomizers) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.eventBus = eventBus != null ? eventBus : new AgentProtocolTaskEventBus();
        this.properties = properties != null ? properties : new AgentProtocolProperties();
        this.runtimeContextCustomizers =
                runtimeContextCustomizers == null
                        ? List.of()
                        : List.copyOf(runtimeContextCustomizers);
    }

    public AgentProtocolTaskStore(
            AgentFactory agentFactory, ProtocolTaskRepository taskRepository) {
        this(
                agentFactory,
                taskRepository,
                new AgentProtocolTaskEventBus(),
                new AgentProtocolProperties());
    }

    public AgentProtocolTaskStore(
            HarnessAgent harnessAgent, ProtocolTaskRepository taskRepository) {
        this(AgentFactory.fixed(harnessAgent), taskRepository);
    }

    public AgentProtocolEventBus eventBus() {
        return eventBus;
    }

    /**
     * 启动后台任务。若检测到同 ID 的未完成执行句柄则直接返回，避免重复启动。
     *
     * @throws IllegalStateException 该任务 ID 已完成时抛出（HTTP 409）
     */
    public void submit(String taskId, String agentId, String input) {
        submit(taskId, agentId, input, null);
    }

    public void submit(String taskId, String agentId, String input, Map<String, Object> context) {
        Optional<TaskRecord> existing = taskRepository.find(taskId);
        if (existing.isPresent() && existing.get().getStatus().isTerminal()) {
            throw new IllegalStateException("task_id already finished: " + taskId);
        }
        // 【学习提示】这里的读取、检查和稍后的 put 不是原子操作；它只做实例内的重复提交检查，
        // 不能据此推断并发 submit 或多实例部署具备严格幂等性，持久化记录也不等于持久化执行句柄。
        CompletableFuture<String> prior = futures.get(taskId);
        if (prior != null && !prior.isDone()) {
            return;
        }

        SubmitContext ctx = SubmitContext.from(context);
        submitContexts.put(taskId, ctx);

        TaskRecord pending =
                new TaskRecord(
                        taskId,
                        agentId != null ? agentId : "default",
                        AgentProtocolConstants.PROTOCOL_AGENT_ID,
                        AgentProtocolConstants.PROTOCOL_SESSION_ID,
                        null);
        pending.setStatus(TaskStatus.PENDING);
        persist(pending);

        CompletableFuture<String> f =
                CompletableFuture.supplyAsync(
                        () -> runAgent(taskId, agentId, input, ctx, null), executor);
        futures.put(taskId, f);
        f.whenComplete((r, ex) -> futures.remove(taskId, f));
    }

    /**
     * 使用给定决策恢复暂停等待 HITL 确认的任务。
     *
     * <p>{@code awaiting_confirm} 记录是暂停信号：暂停的运行会在 future 完成前持久化该记录，之后不再执行智能体工作。因此即使该 future 仍在收尾，客户端也可以合法调用本方法（快速轮询 {@link #snapshot} 或收到 REQUIRE_CONFIRM 事件时可能处于此窗口）。最终依据是经逐任务锁内重新校验的记录；该锁也避免并发恢复重复启动下一轮。
     *
     * @throws IllegalStateException 任务并非等待确认状态时抛出
     */
    public void resume(String taskId, List<RemoteConfirmDecision> decisions) {
        if (!properties.isHitlEnabled()) {
            throw new IllegalStateException("HITL is disabled on this server");
        }
        // 【学习提示】恢复锁只在此 TaskStore 实例内生效；共享任务仓库不会自动提供跨进程互斥。
        Object lock = taskLocks.computeIfAbsent(taskId, k -> new Object());
        synchronized (lock) {
            Optional<TaskRecord> existing = taskRepository.find(taskId);
            if (existing.isEmpty() || !existing.get().isAwaitingConfirm()) {
                throw new IllegalStateException("task is not awaiting confirmation: " + taskId);
            }
            TaskRecord rec = existing.get();

            List<RemotePendingConfirm> pending =
                    rec.getPendingConfirms() != null ? rec.getPendingConfirms() : List.of();
            List<ConfirmResult> confirmResults = toConfirmResults(pending, decisions);

            rec.setAwaitingConfirm(false);
            rec.setPendingConfirms(null);
            rec.setStatus(TaskStatus.RUNNING);
            persist(rec);

            SubmitContext ctx = submitContexts.getOrDefault(taskId, SubmitContext.empty());
            String agentId = rec.getSubAgentId();
            String dummyInput = ""; // 恢复使用确认元数据，而不是新的用户提示词
            CompletableFuture<String> f =
                    CompletableFuture.supplyAsync(
                            () -> runAgent(taskId, agentId, dummyInput, ctx, confirmResults),
                            executor);
            futures.put(taskId, f);
            f.whenComplete((r, ex) -> futures.remove(taskId, f));
        }
    }

    private String runAgent(
            String taskId,
            String agentId,
            String input,
            SubmitContext submitCtx,
            List<ConfirmResult> confirmResults) {
        try {
            updateRunning(taskId, agentId);
            AgentRequest request =
                    new AgentRequest(
                            taskId,
                            agentId,
                            input,
                            submitCtx.userId(),
                            submitCtx.parentSessionId(),
                            confirmResults != null,
                            submitCtx.raw());
            RuntimeContext ctx = buildRuntimeContext(request);

            Msg.Builder msgBuilder =
                    Msg.builder().role(MsgRole.USER).textContent(input != null ? input : "");
            if (confirmResults != null && !confirmResults.isEmpty()) {
                Map<String, Object> meta = new HashMap<>();
                meta.put(Msg.METADATA_CONFIRM_RESULTS, confirmResults);
                msgBuilder.metadata(meta);
            }
            Msg msg = msgBuilder.build();

            HarnessAgent agent = agentFactory.create(request);
            if (agent == null) {
                throw new IllegalStateException(
                        "AgentFactory returned no agent for agent_id=" + agentId);
            }
            AtomicReference<Msg> resultRef = new AtomicReference<>();
            String detail = submitCtx.detail();

            Flux<AgentEvent> events = agent.streamEvents(msg, ctx);
            events.doOnNext(
                            event -> {
                                if (event instanceof AgentResultEvent resultEvent) {
                                    resultRef.set(resultEvent.getResult());
                                }
                                if (!properties.isStreamingEnabled()) {
                                    return;
                                }
                                RemoteEventCodec.fromAgentEvent(event)
                                        .filter(dto -> RemoteEventCodec.matchesDetail(dto, detail))
                                        .ifPresent(
                                                dto -> {
                                                    dto.setAgentId(agentId);
                                                    eventBus.publish(taskId, dto);
                                                });
                            })
                    .blockLast(Duration.ofHours(2));

            Msg reply = resultRef.get();
            if (reply != null
                    && reply.getGenerateReason() == GenerateReason.PERMISSION_ASKING
                    && properties.isHitlEnabled()) {
                List<RemotePendingConfirm> pending = extractPendingConfirms(reply);
                markAwaitingConfirm(taskId, agentId, pending);
                RemoteAgentEvent require = new RemoteAgentEvent();
                require.setType(RemoteEventType.REQUIRE_CONFIRM);
                require.setAgentId(agentId);
                require.setPendingConfirms(pending);
                require.setStatus("awaiting_confirm");
                eventBus.publish(taskId, require);
                return AWAITING_CONFIRM_SENTINEL;
            }

            String text = reply != null ? reply.getTextContent() : "";
            clearSubmitContext(taskId);
            update(taskId, TaskStatus.COMPLETED, text, null, agentId, false, null);
            publishStatus(taskId, agentId, "success", null);
            eventBus.complete(taskId);
            return text;
        } catch (Exception e) {
            String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Protocol task {} failed", taskId, e);
            clearSubmitContext(taskId);
            update(taskId, TaskStatus.FAILED, null, err, agentId, false, null);
            RemoteAgentEvent error = new RemoteAgentEvent();
            error.setType(RemoteEventType.RUN_ERROR);
            error.setAgentId(agentId);
            error.setError(err);
            error.setStatus("error");
            eventBus.publish(taskId, error);
            eventBus.complete(taskId);
            throw new RuntimeException(e);
        }
    }

    /**
     * 构建一轮运行的智能体上下文：会话／用户身份、放在 {@link AgentProtocolConstants#RUNTIME_CONTEXT_ATTRIBUTES_KEY} 下的调用方 {@code context.attributes}，以及已注册 {@link RuntimeContextCustomizer} 添加的内容。
     *
     * <p>默认将属性集中放在一个命名空间键下，避免调用方提供的名称覆盖框架读取的键（如 {@code agentId}）；只有显式通过 customizer 才将个别属性提升为独立键。
     */
    private RuntimeContext buildRuntimeContext(AgentRequest request) {
        RuntimeContext.Builder builder = RuntimeContext.builder().sessionId(request.taskId());
        if (request.userId() != null) {
            builder.userId(request.userId());
        }
        Map<String, Object> attributes = request.attributes();
        if (!attributes.isEmpty()) {
            builder.put(AgentProtocolConstants.RUNTIME_CONTEXT_ATTRIBUTES_KEY, attributes);
        }
        for (RuntimeContextCustomizer customizer : runtimeContextCustomizers) {
            customizer.customize(request, builder);
        }
        return builder.build();
    }

    private void publishStatus(String taskId, String agentId, String status, String error) {
        RemoteAgentEvent evt = new RemoteAgentEvent();
        evt.setType(RemoteEventType.STATUS);
        evt.setAgentId(agentId);
        evt.setStatus(status);
        evt.setError(error);
        eventBus.publish(taskId, evt);
    }

    private void markAwaitingConfirm(
            String taskId, String agentId, List<RemotePendingConfirm> pending) {
        Optional<TaskRecord> prev = taskRepository.find(taskId);
        TaskRecord r =
                prev.orElseGet(
                        () ->
                                new TaskRecord(
                                        taskId,
                                        agentId != null ? agentId : "default",
                                        AgentProtocolConstants.PROTOCOL_AGENT_ID,
                                        AgentProtocolConstants.PROTOCOL_SESSION_ID,
                                        null));
        r.setStatus(TaskStatus.RUNNING);
        r.setAwaitingConfirm(true);
        r.setPendingConfirms(pending);
        r.touch();
        persist(r);
    }

    private static List<RemotePendingConfirm> extractPendingConfirms(Msg reply) {
        List<RemotePendingConfirm> pending = new ArrayList<>();
        for (ToolUseBlock block : reply.getContentBlocks(ToolUseBlock.class)) {
            if (block.getState() == ToolCallState.ASKING) {
                pending.add(
                        new RemotePendingConfirm(
                                block.getId(), block.getName(), toJsonQuiet(block.getInput())));
            }
        }
        if (pending.isEmpty()) {
            // 若状态尚未写入标记，则回退为全部工具调用
            for (ToolUseBlock block : reply.getContentBlocks(ToolUseBlock.class)) {
                pending.add(
                        new RemotePendingConfirm(
                                block.getId(), block.getName(), toJsonQuiet(block.getInput())));
            }
        }
        return pending;
    }

    private static List<ConfirmResult> toConfirmResults(
            List<RemotePendingConfirm> pending, List<RemoteConfirmDecision> decisions) {
        Map<String, RemoteConfirmDecision> byId = new HashMap<>();
        if (decisions != null) {
            for (RemoteConfirmDecision d : decisions) {
                if (d.getToolCallId() != null) {
                    byId.put(d.getToolCallId(), d);
                }
            }
        }
        List<ConfirmResult> results = new ArrayList<>();
        for (RemotePendingConfirm p : pending) {
            RemoteConfirmDecision decision = byId.get(p.getToolCallId());
            boolean approved = decision != null && decision.isApproved();
            Map<String, Object> input = parseInputQuiet(p.getToolInputJson());
            ToolUseBlock toolCall =
                    ToolUseBlock.builder()
                            .id(p.getToolCallId())
                            .name(p.getToolName())
                            .input(input)
                            .build();
            results.add(
                    new ConfirmResult(
                            approved,
                            toolCall,
                            null,
                            decision == null ? null : decision.getReason()));
        }
        return results;
    }

    private void persist(TaskRecord r) {
        taskRepository.save(r);
    }

    private void updateRunning(String taskId, String agentId) {
        update(taskId, TaskStatus.RUNNING, null, null, agentId, false, null);
    }

    private void update(
            String taskId,
            TaskStatus status,
            String result,
            String error,
            String agentId,
            boolean awaitingConfirm,
            List<RemotePendingConfirm> pendingConfirms) {
        Optional<TaskRecord> prev = taskRepository.find(taskId);
        TaskRecord r =
                prev.orElseGet(
                        () ->
                                new TaskRecord(
                                        taskId,
                                        agentId != null ? agentId : "default",
                                        AgentProtocolConstants.PROTOCOL_AGENT_ID,
                                        AgentProtocolConstants.PROTOCOL_SESSION_ID,
                                        null));
        r.setSubAgentId(agentId != null ? agentId : r.getSubAgentId());
        r.setStatus(status);
        r.setAwaitingConfirm(awaitingConfirm);
        r.setPendingConfirms(pendingConfirms);
        if (result != null) {
            r.setResult(result);
        }
        if (error != null) {
            r.setErrorMessage(error);
        }
        persist(r);
    }

    public Map<String, Object> snapshot(String taskId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task_id", taskId);
        Optional<TaskRecord> rec = taskRepository.find(taskId);
        if (rec.isEmpty()) {
            m.put("status", "error");
            m.put("error", "task not found");
            return m;
        }
        TaskRecord r = rec.get();
        m.put("status", mapStatus(r));
        if (r.getErrorMessage() != null) {
            m.put("error", r.getErrorMessage());
        }
        if (r.getStatus() == TaskStatus.COMPLETED && r.getResult() != null) {
            m.put("result", r.getResult());
        }
        if (r.isAwaitingConfirm() && r.getPendingConfirms() != null) {
            m.put("pending_confirms", r.getPendingConfirms());
        }
        CompletableFuture<String> f = futures.get(taskId);
        if (f != null
                && !f.isDone()
                && !r.isAwaitingConfirm()
                && (r.getStatus() == TaskStatus.RUNNING || r.getStatus() == TaskStatus.PENDING)) {
            m.put("status", "running");
        }
        return m;
    }

    /** 阻塞到任务终结、进入等待确认状态或超时。等待确认并不代表任务终结；此时返回 {@code awaiting_confirm}，让客户端继续恢复任务。 */
    public Map<String, Object> waitFor(String taskId, long timeoutMs) throws Exception {
        long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;

        CompletableFuture<String> f = futures.get(taskId);
        if (f != null) {
            try {
                if (timeoutMs > 0) {
                    f.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                } else {
                    f.get();
                }
            } catch (java.util.concurrent.TimeoutException e) {
                Map<String, Object> tout = new LinkedHashMap<>();
                tout.put("status", "error");
                tout.put("error", "wait timeout after " + timeoutMs + " ms");
                return tout;
            } catch (Exception ignored) {
                // 下方会从工作区读取终态
            }
        }

        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            Optional<TaskRecord> rec = taskRepository.find(taskId);
            if (rec.isEmpty()) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("status", "error");
                err.put("error", "task not found");
                return err;
            }
            TaskRecord r = rec.get();
            if (r.isAwaitingConfirm()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", "awaiting_confirm");
                if (r.getPendingConfirms() != null) {
                    out.put("pending_confirms", r.getPendingConfirms());
                }
                return out;
            }
            if (r.getStatus().isTerminal()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", mapStatus(r));
                if (r.getStatus() == TaskStatus.COMPLETED) {
                    out.put("result", r.getResult() != null ? r.getResult() : "");
                } else if (r.getStatus() == TaskStatus.FAILED) {
                    out.put("error", r.getErrorMessage());
                }
                return out;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            long sleepMs =
                    Math.min(Math.min(5_000L, remaining), 200L * (1L << Math.min(attempt++, 4)));
            Thread.sleep(sleepMs);
        }

        Map<String, Object> tout = new LinkedHashMap<>();
        tout.put("status", "error");
        tout.put("error", "wait timeout after " + timeoutMs + " ms");
        return tout;
    }

    // 【学习提示】此路径取消 future 并写取消记录，但没有显式取消 Reactor 订阅或调用智能体中断接口；
    // 不应把状态 CANCELLED 理解为工具副作用已停止或回滚，仍在执行的 runAgent 也可能继续写状态。
    public void cancel(String taskId) {
        CompletableFuture<String> f = futures.get(taskId);
        if (f != null) {
            f.cancel(true);
        }
        Optional<TaskRecord> rec = taskRepository.find(taskId);
        if (rec.isPresent()) {
            TaskRecord r = rec.get();
            r.setCancelRequested(true);
            r.setAwaitingConfirm(false);
            if (!r.getStatus().isTerminal()) {
                r.setStatus(TaskStatus.CANCELLED);
            }
            persist(r);
        }
        eventBus.complete(taskId);
        clearSubmitContext(taskId);
    }

    /** 丢弃 {@code taskId} 的内存提交元数据。在 {@code awaiting_confirm} 状态下保留，以供 {@link #resume} 复用 detail／userId；仅在终态完成或取消时清理。 */
    private void clearSubmitContext(String taskId) {
        submitContexts.remove(taskId);
    }

    /** 供测试访问：是否仍保留 {@code taskId} 的提交上下文。 */
    boolean hasSubmitContext(String taskId) {
        return submitContexts.containsKey(taskId);
    }

    private static String mapStatus(TaskRecord r) {
        if (r.isAwaitingConfirm()) {
            return "awaiting_confirm";
        }
        return switch (r.getStatus()) {
            case PENDING -> "pending";
            case RUNNING -> "running";
            case COMPLETED -> "success";
            case FAILED -> "error";
            case CANCELLED -> "cancelled";
        };
    }

    private static String toJsonQuiet(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseInputQuiet(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = JSON.readValue(json, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> out = new HashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getKey() != null) {
                        out.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
                return out;
            }
            return Map.of("raw", parsed);
        } catch (JsonProcessingException e) {
            return Map.of("raw", json);
        }
    }

    /** 解析后的协议字段及提交的 {@code raw} 映射。映射会交给 {@link AgentFactory}，使自定义上下文键在提交和恢复的路由过程中均得以保留。 */
    private record SubmitContext(
            String userId, String parentSessionId, String detail, Map<String, Object> raw) {
        static SubmitContext empty() {
            return new SubmitContext(null, null, "status", Map.of());
        }

        static SubmitContext from(Map<String, Object> raw) {
            if (raw == null || raw.isEmpty()) {
                return empty();
            }
            String userId = stringVal(raw.get("user_id"));
            String parentSessionId = stringVal(raw.get("parent_session_id"));
            String detail = stringVal(raw.get("detail"));
            if (detail == null || detail.isBlank()) {
                detail = "status";
            }
            return new SubmitContext(userId, parentSessionId, detail, raw);
        }

        private static String stringVal(Object v) {
            return v == null ? null : String.valueOf(v);
        }
    }
}
