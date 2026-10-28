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

/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.agentscope.extensions.controlplane.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.extensions.controlplane.model.AgentTaskAssignment;
import io.agentscope.extensions.controlplane.transport.CollaborationClient;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * ASDP AgentTask 投递的默认 Java 外部智能体执行器。
 */
public final class HarnessAgentTaskStarter implements AgentTaskStarter {

    private static final Logger LOG = Logger.getLogger(HarnessAgentTaskStarter.class.getName());

    private final Supplier<HarnessAgent> agent;
    private final CollaborationClient collaboration;
    private WorkspaceAgentFactory workspaceFactory;

    public HarnessAgentTaskStarter withWorkspaceFactory(WorkspaceAgentFactory factory) {
        this.workspaceFactory = Objects.requireNonNull(factory);
        return this;
    }

    public boolean consumesWorkspaceDefinition() {
        return workspaceFactory != null;
    }

    private final Set<String> acceptedEvents = ConcurrentHashMap.newKeySet();

    public HarnessAgentTaskStarter(
            Supplier<HarnessAgent> agent, CollaborationClient collaboration) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.collaboration = Objects.requireNonNull(collaboration, "collaboration");
    }

    @Override
    public Mono<Void> start(AgentTaskAssignment assignment) {
        return Mono.fromRunnable(() -> execute(assignment))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private void execute(AgentTaskAssignment assignment) {
        require(assignment.agentTaskId(), "agentTaskId");
        require(assignment.taskToken(), "taskToken");
        String eventKey = assignment.attemptId() + ":" + assignment.generation();
        if (!acceptedEvents.add(eventKey)) {
            return;
        }

        long version = 0;
        HarnessAgent ownedAgent = null;
        HarnessAgent executionAgent = null;
        RuntimeContext executionContext = null;
        try {
            JsonNode envelope =
                    collaboration.taskContext(assignment.agentTaskId(), assignment.taskToken());
            version = envelope.path("task").path("version").asLong();
            List<String> inputIds = inputIds(envelope);
            if (!inputIds.isEmpty()) {
                collaboration.acknowledge(
                        assignment.agentTaskId(), assignment.taskToken(), inputIds);
            }
            JsonNode running = ensureRunning(assignment, envelope, version);
            version = running.path("task").path("version").asLong(version);
            envelope = running;

            HarnessAgent runtimeAgent;
            if (workspaceFactory != null) {
                JsonNode definition =
                        envelope.path("task").path("runtimeBinding").path("definition");
                if (!definition.isObject()
                        || definition.path("definitionDigest").asText().isBlank())
                    throw new IllegalStateException(
                            "Dispatch has no immutable Workspace definition");
                ownedAgent = workspaceFactory.create(definition.deepCopy(), assignment);
                runtimeAgent =
                        Objects.requireNonNull(ownedAgent, "Workspace factory returned no agent");
                collaboration.workspaceApplied(
                        assignment.agentTaskId(),
                        assignment.taskToken(),
                        definition.path("definitionDigest").asText());
            } else runtimeAgent = agent.get();
            executionAgent = runtimeAgent;
            registerCollaborationTools(runtimeAgent, assignment, availableActions(envelope));

            String payload = new String(assignment.payload(), StandardCharsets.UTF_8);
            String prompt =
                    "AgentTask "
                            + assignment.agentTaskId()
                            + " is ready. The JSON below contains the authoritative Run input,"
                            + " Issue, discussion inputs, Team role, and artifacts. Complete the"
                            + " requested work and return a concise result; use CollaborationClient"
                            + " for fresh reads, progress comments, artifacts, or child Issues. The"
                            + " available CollaborationClient actions are registered as tools with"
                            + " the exact names shown in availableActions. The adapter owns"
                            + " task.complete and task.fail; do not call them. Before returning,"
                            + " call task.submit_result with an explicit business outcome and the"
                            + " actual deliverable. A promise to do work later is not completion."
                            + " Check the tool capabilities before delegating: spawning a subagent"
                            + " does not add missing web access."
                            + roleInstructions(envelope, inputIds)
                            + "\ncontextUrl="
                            + nullToEmpty(assignment.contextUrl())
                            + "\n"
                            + modelContext(envelope)
                            + "\nActual tool names available to this agent: "
                            + runtimeAgent.getToolkit().getToolNames()
                            + (payload.isBlank() ? "" : "\neventPayload=" + safePayload(payload));
            Msg kickoff = Msg.builder().role(MsgRole.USER).textContent(prompt).build();
            String sessionId =
                    assignment.sessionId() == null || assignment.sessionId().isBlank()
                            ? assignment.agentTaskId()
                            : assignment.sessionId();
            AgentTaskOutcome.State outcomeState = new AgentTaskOutcome.State();
            RuntimeContext context =
                    RuntimeContext.builder()
                            .sessionId(sessionId)
                            .put("agentTaskManaged", Boolean.TRUE)
                            .put(
                                    io.agentscope.harness.agent.subagent.task.TaskRepository
                                            .SUPPRESS_COMPLETION_CALLBACK,
                                    Boolean.TRUE)
                            .put(
                                    "teamLeader",
                                    envelope.path("task").path("leaderTask").asBoolean(false))
                            .put(AgentTaskOutcome.State.class, outcomeState)
                            .put(
                                    AgentTaskToolContext.class,
                                    new AgentTaskToolContext(
                                            assignment.agentTaskId(), assignment.taskToken()))
                            .build();
            executionContext = context;
            AgentTaskOutcome outcome =
                    runToOutcome(runtimeAgent, kickoff, context, assignment, outcomeState);
            if (outcomeState.isTerminalCommitted()) return;
            JsonNode latest =
                    collaboration.taskContext(assignment.agentTaskId(), assignment.taskToken());
            version = latest.path("task").path("version").asLong(version);
            collaboration.finish(
                    assignment.agentTaskId(),
                    assignment.taskToken(),
                    version,
                    outcome.outcome(),
                    outcome.reason(),
                    outcome.result(),
                    inputIds,
                    List.of());
            LOG.log(Level.INFO, "AgentTask completed: {0}", assignment.agentTaskId());
        } catch (RuntimeException e) {
            try {
                version =
                        collaboration
                                .taskContext(assignment.agentTaskId(), assignment.taskToken())
                                .path("task")
                                .path("version")
                                .asLong(version);
                collaboration.fail(
                        assignment.agentTaskId(),
                        assignment.taskToken(),
                        version,
                        "agent_failed",
                        e.getMessage());
            } catch (RuntimeException reportError) {
                e.addSuppressed(reportError);
            }
            acceptedEvents.remove(eventKey);
            throw e;
        } finally {
            if (executionAgent != null
                    && executionContext != null
                    && executionAgent.getTaskRepository() != null) {
                var repo = executionAgent.getTaskRepository();
                for (var task :
                        repo.listTasks(executionContext, executionContext.getSessionId(), null)) {
                    if (!task.getTaskStatus().isTerminal())
                        repo.cancelTask(
                                executionContext,
                                executionContext.getSessionId(),
                                task.getTaskId());
                }
            }
            if (ownedAgent != null) ownedAgent.close();
        }
    }

    private AgentTaskOutcome runToOutcome(
            HarnessAgent runtimeAgent,
            Msg kickoff,
            RuntimeContext context,
            AgentTaskAssignment assignment,
            AgentTaskOutcome.State state) {
        Msg next = kickoff;
        int corrections = 0;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(10);
        String partial = "";
        for (int turn = 0; turn < 16 && System.nanoTime() < deadline; turn++) {
            Msg response = callWithApprovals(runtimeAgent, next, context, assignment);
            if (state.isTerminalCommitted()) return null;
            AgentTaskOutcome outcome = state.take();
            if (response == null
                    || (response.getGenerateReason() != null
                            && response.getGenerateReason() != GenerateReason.MODEL_STOP
                            && response.getGenerateReason() != GenerateReason.STRUCTURED_OUTPUT)) {
                return new AgentTaskOutcome(
                        "blocked",
                        outcome == null ? partial : outcome.result(),
                        "runtime_stopped: "
                                + (response == null
                                        ? "empty response"
                                        : response.getGenerateReason()),
                        List.of());
            }
            if (outcome == null) {
                if (corrections++ > 0)
                    return new AgentTaskOutcome(
                            "blocked",
                            partial,
                            "missing_outcome: the agent returned text without submitting an"
                                    + " explicit task outcome",
                            List.of());
                next =
                        message(
                                "The turn ended without a business outcome. Do the remaining work,"
                                    + " or call task.submit_result with blocked and the concrete"
                                    + " missing capability. Do not submit a plan or waiting promise"
                                    + " as successful research.");
                continue;
            }
            if (!outcome.result().isBlank()) partial = outcome.result();
            var repo = runtimeAgent.getTaskRepository();
            var tasks =
                    repo == null
                            ? List.<io.agentscope.harness.agent.subagent.task.BackgroundTask>of()
                            : List.copyOf(repo.listTasks(context, context.getSessionId(), null));
            if (outcome.outcome().equals("succeeded")
                    && tasks.stream().anyMatch(t -> !t.getTaskStatus().isTerminal())) {
                if (corrections++ > 0)
                    return new AgentTaskOutcome(
                            "blocked",
                            partial,
                            "uncollected_dependencies: required background tasks are still running",
                            List.of());
                next =
                        message(
                                "There are unfinished background tasks. Collect their actual"
                                        + " results or submit waiting with their real IDs. This"
                                        + " AgentTask cannot succeed yet.");
                continue;
            }
            if (!outcome.outcome().equals("waiting")) return outcome;
            // 控制平面委托使用持久 AgentTask ID，而非本地会话任务。
            // 其等待端点在接受交接前验证实际待处理工作。
            if (Boolean.TRUE.equals(context.get("teamLeader"))
                    && outcome.pendingTaskIds().stream()
                            .allMatch(
                                    id ->
                                            repo == null
                                                    || repo.getTask(
                                                                    context,
                                                                    context.getSessionId(),
                                                                    id)
                                                            == null)) return outcome;
            List<io.agentscope.harness.agent.subagent.task.BackgroundTask> pending =
                    new ArrayList<>();
            for (String id : outcome.pendingTaskIds()) {
                var task = repo == null ? null : repo.getTask(context, context.getSessionId(), id);
                if (task == null)
                    return new AgentTaskOutcome(
                            "blocked",
                            partial,
                            "dependency_not_found: " + id + " is not a task in this session",
                            List.of());
                pending.add(task);
            }
            // 保留执行所有权；此处不创建网关聊天轮次或新的 Issue。
            while (pending.stream().anyMatch(t -> !t.getTaskStatus().isTerminal())) {
                if (System.nanoTime() >= deadline)
                    return new AgentTaskOutcome(
                            "blocked",
                            partial,
                            "dependency_wait_timeout: background work did not converge within the"
                                    + " execution budget",
                            List.of());
                try {
                    for (var task : pending) task.waitForCompletion(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "AgentTask interrupted while waiting for dependencies", e);
                }
                // 验证此尝试仍获授权，并刷新持久任务句柄。
                collaboration.taskContext(assignment.agentTaskId(), assignment.taskToken());
                pending =
                        outcome.pendingTaskIds().stream()
                                .map(id -> repo.getTask(context, context.getSessionId(), id))
                                .toList();
                if (pending.stream().anyMatch(Objects::isNull))
                    return new AgentTaskOutcome(
                            "blocked",
                            partial,
                            "dependency_not_found: a waiting task disappeared",
                            List.of());
            }
            StringBuilder results =
                    new StringBuilder(
                            "The dependencies have reached terminal states. Validate their contents"
                                    + " against the original objective before submitting your"
                                    + " outcome.\n");
            var taskTool = new io.agentscope.harness.agent.tool.TaskTool(repo);
            for (String id : outcome.pendingTaskIds()) {
                taskTool.taskOutput(context, id, false, 0L).getOutput().stream()
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .map(TextBlock::getText)
                        .forEach(results::append);
                results.append('\n');
            }
            next = message(results.toString());
        }
        return new AgentTaskOutcome(
                "blocked",
                partial,
                "execution_budget_exceeded: no validated business outcome within the adapter"
                        + " continuation budget",
                List.of());
    }

    private Msg callWithApprovals(
            HarnessAgent agent, Msg input, RuntimeContext context, AgentTaskAssignment assignment) {
        Msg response = agent.call(input, context).block();
        for (int round = 0;
                response != null
                        && response.getGenerateReason() == GenerateReason.PERMISSION_ASKING;
                round++) {
            if (round >= 32)
                throw new IllegalStateException("runtime approval round limit exceeded");
            List<ToolUseBlock> pending = response.getContentBlocks(ToolUseBlock.class);
            if (pending.isEmpty())
                throw new IllegalStateException(
                        "PERMISSION_ASKING response contains no tool calls");
            List<ConfirmResult> confirmations = new ArrayList<>();
            for (ToolUseBlock call : pending) {
                var decision =
                        collaboration.awaitRuntimeToolApproval(
                                assignment.agentTaskId(),
                                assignment.taskToken(),
                                call.getId(),
                                call.getName(),
                                call.getInput());
                confirmations.add(
                        new ConfirmResult(decision.allow(), call, null, decision.denyMessage()));
            }
            response =
                    agent.call(
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .textContent("Human tool approval decisions received")
                                            .metadata(
                                                    Map.of(
                                                            Msg.METADATA_CONFIRM_RESULTS,
                                                            confirmations))
                                            .build(),
                                    context)
                            .block();
        }
        return response;
    }

    private static Msg message(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static JsonNode modelContext(JsonNode node) {
        JsonNode copy = node.deepCopy();
        stripCredentials(copy);
        return copy;
    }

    private static String safePayload(String payload) {
        try {
            return modelContext(
                            io.agentscope.extensions.controlplane.transport.ControlPlaneHttpClient
                                    .mapper()
                                    .readTree(payload))
                    .toString();
        } catch (java.io.IOException e) {
            return "[Non-JSON dispatch metadata omitted; use task context above.]";
        }
    }

    private static void stripCredentials(JsonNode node) {
        if (node.isObject()) {
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            object.remove(List.of("taskToken", "attemptToken", "headers", "apiKey", "apiToken"));
        }
        if (node.isContainerNode()) node.forEach(HarnessAgentTaskStarter::stripCredentials);
    }

    private void registerCollaborationTools(
            HarnessAgent runtimeAgent,
            AgentTaskAssignment assignment,
            Set<String> availableActions) {
        Object toolkit = runtimeAgent.getToolkit();
        synchronized (toolkit) {
            if (!runtimeAgent.getToolkit().getToolNames().contains("task.submit_result")) {
                runtimeAgent.getToolkit().registerTool(new AgentTaskOutcomeTool());
            }
            for (JsonNode definition :
                    collaboration.tools(assignment.agentTaskId(), assignment.taskToken())) {
                String name = definition.path("name").asText();
                // 启动器拥有物理任务生命周期。暴露这两个操作会
                // 与适配器带隔离令牌的完成／失败上报竞争。
                if ("task.complete".equals(name) || "task.fail".equals(name)) {
                    continue;
                }
                if (!availableActions.contains(name)) {
                    continue;
                }
                if (!runtimeAgent.getToolkit().getToolNames().contains(name)) {
                    runtimeAgent
                            .getToolkit()
                            .registerAgentTool(
                                    new AgentTaskCollaborationTool(collaboration, definition));
                }
            }
        }
    }

    static String roleInstructions(JsonNode envelope, List<String> inputIds) {
        JsonNode task = envelope.path("task");
        if (task.path("teamId").asText().isBlank()) {
            return " Complete the requested work and return the result.";
        }
        if (!task.path("leaderTask").asBoolean(false)) {
            return " You are a Team worker, not its coordinator. Do not create or accept child"
                    + " Issues and do not call run.node.complete, run.node.fail, or run.replan."
                    + " Complete only the assigned work and submit its result using"
                    + " task.submit_result; the adapter will complete this AgentTask.";
        }
        if (inputIds.isEmpty()) {
            return " You are the Team leader's initial task. If you delegate child work, return"
                    + " immediately after issue.child.create succeeds by calling"
                    + " task.submit_result with waiting, a reason and the returned AgentTask"
                    + " IDs; do not wait through local session/task tools and do not call"
                    + " run.node.complete yet. The control plane will deliver a fresh leader"
                    + " follow-up when a worker result arrives. If no work is delegated, call"
                    + " run.node.complete after your own work converges. Returning text alone"
                    + " never completes a Team coordinator.";
        }
        return " You are a Team leader follow-up with new worker inputs. Validate the supplied"
                + " result, call issue.accept and wait for its result, then make a separate"
                + " run.node.complete call only when every child Issue and worker node has"
                + " converged. Never send those mutations in parallel. run.node.complete also"
                + " completes this leader AgentTask; do not call task.complete afterwards."
                + " Returning text alone never completes a Team coordinator.";
    }

    private static Set<String> availableActions(JsonNode envelope) {
        Set<String> actions = new HashSet<>();
        for (JsonNode action : envelope.path("availableActions")) {
            String name = action.asText();
            if (!name.isBlank()) {
                actions.add(name);
            }
        }
        return actions;
    }

    /**
     * ASDP 在调用框架适配器之前上报物理 Attempt 启动。该上报通常在同一控制平面事务中将逻辑 AgentTask 转为 {@code running}，因此第二次任务启动会因乐观版本检查失败。保留 HTTP 启动作为回退，供尚未观察到 ASDP 上报的传输路径使用。
     */
    JsonNode ensureRunning(
            AgentTaskAssignment assignment, JsonNode envelope, long expectedVersion) {
        if ("running".equals(envelope.path("task").path("status").asText())) {
            return envelope;
        }
        try {
            collaboration.start(assignment.agentTaskId(), assignment.taskToken(), expectedVersion);
            // task.start 有意返回精简的 {task: ...} 响应。始终刷新
            // 权威上下文，避免 Issue、讨论输入、Team、制品和操作
            // 在这个依赖时序的回退路径中丢失。
            return collaboration.taskContext(assignment.agentTaskId(), assignment.taskToken());
        } catch (CollaborationClient.CollaborationHttpException e) {
            if (e.status() != 409) {
                throw e;
            }
            // ASDP 启动与此回退可能竞争。只有新读取的带隔离令牌状态
            // 证明同一个任务已在运行时，冲突才能视为成功。
            JsonNode refreshed =
                    collaboration.taskContext(assignment.agentTaskId(), assignment.taskToken());
            if ("running".equals(refreshed.path("task").path("status").asText())) {
                return refreshed;
            }
            throw e;
        }
    }

    private static List<String> inputIds(JsonNode envelope) {
        List<String> ids = new ArrayList<>();
        for (JsonNode item : envelope.path("inputs")) {
            String id = item.path("input").path("id").asText();
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
