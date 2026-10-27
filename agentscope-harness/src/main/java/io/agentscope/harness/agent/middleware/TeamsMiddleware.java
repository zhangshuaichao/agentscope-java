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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.team.TeamClient;
import io.agentscope.harness.agent.team.TeamContext;
import io.agentscope.harness.agent.team.TeamTask;
import io.agentscope.harness.agent.team.TeamWakeups;
import io.agentscope.harness.agent.tool.TeamTool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 注入 AgentTeams 上下文，并暴露按角色裁剪能力的 {@link TeamTool}：
 * 稳定的团队指导进入系统提示头部，易变状态（成员状态、看板摘要、缓冲通知）
 * 随合成尾部消息传递，保持头部在各步骤间字节稳定。
 *
 * <p>可选的 {@link #wireMessageBus(MessageBus, String)} 将邮箱唤醒推入
 * {@link InboxMiddleware} 使用的会话收件箱路径。
 */
public final class TeamsMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(TeamsMiddleware.class);

    /** sessionId → 中间件，用于将 ASDP TeamEvent 桥接为唤醒。 */
    private static final ConcurrentHashMap<String, TeamsMiddleware> BY_SESSION =
            new ConcurrentHashMap<>();

    /**
     * "team|role" → 中间件，使只包含成员名称的 TeamEvent
     * 也能在会话 ID 绑定前或没有会话 ID 时到达会话。
     */
    private static final ConcurrentHashMap<String, TeamsMiddleware> BY_MEMBER =
            new ConcurrentHashMap<>();

    private final TeamClient teamClient;
    private final TeamContext teamContext;
    private final TeamTool teamTool;

    private static final Duration BOARD_PROBE_TIMEOUT = Duration.ofSeconds(5);

    /** 团队启动时由控制面分配的负责人名册名称。 */
    private static final String LEAD = "lead";

    /** 记录为失败原因的结束消息最大长度；保留摘要，而非完整对话。 */
    private static final int MAX_CLOSING_REASON = 4000;

    /** 在负责人看板摘要中回显的单项任务结果最大长度。 */
    private static final int MAX_DIGEST_RESULT = 600;

    /** 本轮开始时的看板，为负责人渲染。工作成员或空看板时为空。 */
    private volatile String boardDigest = "";

    /** 因尚未绑定会话或收件箱推送失败而缓冲的通知。 */
    private final Queue<String> pendingNotices = new ConcurrentLinkedQueue<>();

    private volatile MessageBus messageBus;
    private volatile String agentId = "main";
    private volatile String boundSessionId;

    static {
        // 让 TeamClient 实现能够按成员名称唤醒成员；它们无法
        // 自行解析运行时会话 ID。
        TeamWakeups.register(TeamsMiddleware::wakeupTeamMember);
    }

    public TeamsMiddleware(TeamClient teamClient, TeamContext teamContext) {
        this.teamClient = Objects.requireNonNull(teamClient, "teamClient");
        this.teamContext = Objects.requireNonNull(teamContext, "teamContext");
        this.teamTool = new TeamTool(teamClient, teamContext);
        String memberKey = memberKey(teamContext.teamName(), teamContext.myRole());
        if (memberKey != null) {
            BY_MEMBER.put(memberKey, this);
        }
    }

    /** 仅声明所需扩展点；子类若重写更多钩子，必须扩展此集合。 */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_AGENT, ExtensionPoint.ON_REASONING);
    }

    /** 以运行时会话 ID 注册此中间件，用于 TeamEvent 投递。 */
    public void bindSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String prev = this.boundSessionId;
        if (prev != null && !prev.equals(sessionId)) {
            BY_SESSION.remove(prev, this);
        }
        this.boundSessionId = sessionId;
        BY_SESSION.put(sessionId, this);
    }

    /** 为已构建的中间件实例注册 {@code sessionId}。 */
    public static void registerSession(String sessionId, TeamsMiddleware middleware) {
        if (middleware != null) {
            middleware.bindSession(sessionId);
        }
    }

    /**
     * 移除已永久消失会话的全部注册项，防止后续团队复用相同团队名和成员名时，
     * 唤醒已死亡的会话。仅在会话被删除时调用，不能在仅仅空闲时调用。
     *
     * <p>只有成员注册项仍指向当前实例时才移除；若成员已在新会话下重建，
     * 新实例已经替换了该项。
     */
    public static void unregisterSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        TeamsMiddleware mw = BY_SESSION.remove(sessionId);
        if (mw == null) {
            return;
        }
        if (sessionId.equals(mw.boundSessionId)) {
            mw.boundSessionId = null;
        }
        String memberKey = memberKey(mw.teamContext.teamName(), mw.teamContext.myRole());
        if (memberKey != null) {
            BY_MEMBER.remove(memberKey, mw);
        }
    }

    /** 唤醒绑定到 {@code sessionId} 的队友；未知时返回 false。 */
    public static boolean wakeupSession(String sessionId) {
        return wakeupSession(sessionId, null);
    }

    /** 唤醒绑定到 {@code sessionId} 的队友，并把 {@code notice} 注入其下一轮。 */
    public static boolean wakeupSession(String sessionId, String notice) {
        TeamsMiddleware mw = sessionId == null ? null : BY_SESSION.get(sessionId);
        if (mw == null) {
            return false;
        }
        mw.notifyWakeup(sessionId, notice);
        return true;
    }

    /** 按团队与成员名称唤醒队友；未知时返回 false。 */
    public static boolean wakeupTeamMember(String teamName, String memberName) {
        return wakeupTeamMember(teamName, memberName, null);
    }

    /** 按团队与成员名称唤醒队友，并把 {@code notice} 注入其下一轮。 */
    public static boolean wakeupTeamMember(String teamName, String memberName, String notice) {
        String key = memberKey(teamName, memberName);
        TeamsMiddleware mw = key == null ? null : BY_MEMBER.get(key);
        if (mw == null) {
            return false;
        }
        mw.notifyWakeup(mw.boundSessionId, notice);
        return true;
    }

    private static String memberKey(String teamName, String memberName) {
        if (teamName == null || teamName.isBlank() || memberName == null || memberName.isBlank()) {
            return null;
        }
        return teamName + "|" + memberName;
    }

    /** 构建时要注册到智能体工具集的工具。 */
    public List<Object> getTools() {
        return List.of(teamTool);
    }

    public TeamContext teamContext() {
        return teamContext;
    }

    public TeamClient teamClient() {
        return teamClient;
    }

    /**
     * 连接邮箱唤醒。将 {@code agentId} 传给 {@link MessageBus#enqueueWakeup}，
     * 让网关能够为空闲轮次路由。
     */
    public void wireMessageBus(MessageBus messageBus, String agentId) {
        this.messageBus = messageBus;
        if (agentId != null && !agentId.isBlank()) {
            this.agentId = agentId;
        }
    }

    /** 通知此队友会话有团队事件到达（消息／任务进入终态）。 */
    public void notifyWakeup(String sessionId) {
        notifyWakeup(sessionId, null);
    }

    /**
     * 通知此队友会话，并注入 {@code notice}，让被唤醒的轮次从事件内容而非空提示开始。
     * 可从适配器或团队客户端钩子安全调用。
     *
     * <p>尚未绑定会话时先缓冲通知，在下一次推理时注入，
     * 避免与会话绑定竞争的事件丢失。
     */
    public void notifyWakeup(String sessionId, String notice) {
        MessageBus bus = this.messageBus;
        final String sid = sessionId == null || sessionId.isBlank() ? boundSessionId : sessionId;
        final String text = renderNotice(notice);
        if (bus == null || sid == null || sid.isBlank()) {
            pendingNotices.add(text);
            return;
        }
        try {
            Map<String, Object> hint =
                    Map.of(
                            "type",
                            "hint",
                            "id",
                            UUID.randomUUID().toString().replace("-", ""),
                            "hint",
                            text,
                            "source",
                            "team_event");
            bus.inboxPush(sid, hint)
                    .then(bus.enqueueWakeup("", sid, agentId))
                    .subscribe(
                            null,
                            err -> {
                                log.debug("team wakeup failed session={}: {}", sid, err.toString());
                                pendingNotices.add(text);
                            });
        } catch (RuntimeException e) {
            log.debug("team wakeup error: {}", e.toString());
            pendingNotices.add(text);
        }
    }

    private String renderNotice(String notice) {
        String team = teamContext.teamName() == null ? "" : teamContext.teamName();
        String role = teamContext.myRole() == null ? "" : teamContext.myRole();
        String body =
                notice == null || notice.isBlank()
                        ? "A team event arrived. Call listMessages and listTasks to see the current"
                                + " board and mailbox."
                        : notice;
        return "<system-reminder>Team event for "
                + role
                + " in team "
                + team
                + ":\n\n"
                + body
                + "</system-reminder>";
    }

    /**
     * 在负责人轮次开始时提供当时的看板，在工作成员轮次结束时结算其任务。
     * 负责人的任务不受影响，因为负责人面向可以继续对话的用户，
     * 因此其一轮结束并不代表整个执行结束。
     */
    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (teamContext.isLead()) {
            return Mono.fromRunnable(this::refreshBoardDigest)
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(
                            e -> {
                                log.debug("board digest refresh failed: {}", e.toString());
                                return Mono.empty();
                            })
                    .thenMany(Flux.defer(() -> next.apply(input)));
        }
        final RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        return next.apply(input)
                .concatWith(
                        Mono.<AgentEvent>fromRunnable(() -> settleOwnedTasks(agent, rc))
                                .subscribeOn(Schedulers.boundedElastic())
                                .onErrorResume(
                                        e -> {
                                            log.debug(
                                                    "team turn-end settlement failed: {}",
                                                    e.toString());
                                            return Mono.empty();
                                        }));
    }

    private void settleOwnedTasks(Agent agent, RuntimeContext rc) {
        settleOwnedTasks(lastAssistantText(agent, rc));
    }

    /**
     * 将此成员仍在执行的全部任务标记为失败，以其结束消息作为原因。
     *
     * <p>工作成员的一轮就是完整执行：被唤醒时运行，轮次结束时停止，
     * 没有人类向它回复，也没有它自行发起的后续轮次。
     * 因此，此时未结算的任务已无人处理；若仍保留进行中状态，
     * 会误导看板，让负责人等待永远不会到达的结果。
     * 标记失败会记录成员最后的话并通知负责人，由负责人决定回答、重试或放弃。
     *
     * <p>【学习提示】角色决定生命周期语义：负责人可跨用户轮次继续，工作成员结束则需结算在途任务。
     * 但仅分配、尚未开始执行的任务仍待唤醒，不能混同为本轮执行失败。
     */
    void settleOwnedTasks(String closing) {
        String me = teamContext.myRole();
        if (me == null || me.isBlank() || LEAD.equals(me)) {
            return;
        }
        String reason =
                closing == null || closing.isEmpty()
                        ? "Ended its turn without reporting a result."
                        : closing;
        for (TeamTask task : tasksInProgressBy(me)) {
            try {
                teamClient
                        .failTask(
                                teamContext.resolvedNamespace(),
                                teamContext.teamName(),
                                task.taskId(),
                                reason)
                        .block(BOARD_PROBE_TIMEOUT);
            } catch (RuntimeException e) {
                log.debug("settling task {} at turn end failed: {}", task.taskId(), e.toString());
            }
        }
    }

    /**
     * 此成员在本轮实际执行的任务。仅被分配给它的任务仍处于待处理状态，
     * 等待自己的唤醒，不能算作本轮未能完成的任务。
     */
    private List<TeamTask> tasksInProgressBy(String me) {
        List<TeamTask> tasks =
                teamClient
                        .listTasks(teamContext.resolvedNamespace(), teamContext.teamName())
                        .block(BOARD_PROBE_TIMEOUT);
        if (tasks == null) {
            return List.of();
        }
        return tasks.stream()
                .filter(t -> me.equals(t.owner()) && TeamTask.IN_PROGRESS.equals(t.state()))
                .toList();
    }

    private static String lastAssistantText(Agent agent, RuntimeContext rc) {
        AgentState state = RuntimeContext.resolveAgentState(rc, agent);
        if (state == null) {
            return "";
        }
        List<Msg> messages = state.getContext();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg == null || msg.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            String text = extractText(msg).trim();
            if (text.isEmpty()) {
                continue;
            }
            return text.length() > MAX_CLOSING_REASON
                    ? text.substring(0, MAX_CLOSING_REASON) + "… (truncated)"
                    : text;
        }
        return "";
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        String section = renderTeamGuidance(teamContext);
        String buffered = drainPendingNotices();
        List<Msg> rebuilt = prependToSystemMessage(input.messages(), section);
        Msg stateMsg = buildTeamStateMsg(teamContext, boardDigest, buffered);
        if (stateMsg != null) {
            rebuilt = new ArrayList<>(rebuilt);
            rebuilt.add(stateMsg);
        }
        return next.apply(new ReasoningInput(rebuilt, input.tools(), input.options()));
    }

    /** 每轮读取看板一次，使同一轮的每一步渲染相同摘要。 */
    private void refreshBoardDigest() {
        List<TeamTask> tasks;
        try {
            tasks =
                    teamClient
                            .listTasks(teamContext.resolvedNamespace(), teamContext.teamName())
                            .block(BOARD_PROBE_TIMEOUT);
        } catch (RuntimeException e) {
            log.debug("board probe for digest failed: {}", e.toString());
            return;
        }
        boardDigest = renderBoardDigest(tasks, teamContext.myRole());
    }

    /**
     * 为负责人渲染完整看板：各任务内容、拥有者及已结算任务的结果。
     *
     * <p>否则负责人只能逐条通知获知任务情况，并轮询其余部分，
     * 容易忘记自己承担的工作，也无法判断目标是否完成。
     * 包含结果是因为最终总结需要依据这些结果编写。
     */
    static String renderBoardDigest(List<TeamTask> tasks, String me) {
        if (tasks == null || tasks.isEmpty()) {
            return "";
        }
        List<TeamTask> mineOpen = new ArrayList<>();
        boolean allSettled = true;
        StringBuilder sb = new StringBuilder("\n**Board** (start of this turn):\n");
        for (TeamTask t : tasks) {
            boolean terminal = TeamTask.isTerminal(t.state());
            allSettled &= terminal;
            String owner = t.owner() == null || t.owner().isBlank() ? "unassigned" : t.owner();
            sb.append("- ")
                    .append(t.taskId())
                    .append(" — ")
                    .append(nullToEmpty(t.subject()))
                    .append(" [")
                    .append(nullToEmpty(t.state()))
                    .append(", ")
                    .append(owner)
                    .append(']');
            if (terminal && t.result() != null && !t.result().isBlank()) {
                sb.append("\n  ").append(abbreviate(t.result(), MAX_DIGEST_RESULT));
            }
            sb.append('\n');
            if (!terminal && me != null && me.equals(t.owner())) {
                mineOpen.add(t);
            }
        }
        if (!mineOpen.isEmpty()) {
            sb.append("\n**You own ")
                    .append(mineOpen.size())
                    .append(" unsettled task(s): ")
                    .append(String.join(", ", mineOpen.stream().map(TeamTask::taskId).toList()))
                    .append(".** Nobody else will do them. Do the work in this turn and call")
                    .append(" completeTask with the result, hand each one to a teammate with")
                    .append(" assignTask, or call failTask — do not end the turn still owning")
                    .append(" them.\n");
        }
        if (allSettled) {
            sb.append(
                    "\n**Every task is settled.** Write the consolidated answer to the objective"
                            + " now, using the results above, and then call completeTeam. Do not"
                            + " create more tasks unless something is genuinely missing.\n");
        }
        return sb.toString();
    }

    private static String abbreviate(String text, int max) {
        String flat = text.strip().replace("\n", " ");
        return flat.length() > max ? flat.substring(0, max) + "…" : flat;
    }

    /**
     * 报告共享看板是否仍有非终态任务，让阻塞等待知道队友仍在工作，
     * 即使本会话没有自己的后台子智能体任务。
     */
    public boolean hasOutstandingTeamWork() {
        try {
            List<TeamTask> tasks =
                    teamClient
                            .listTasks(teamContext.resolvedNamespace(), teamContext.teamName())
                            .block(BOARD_PROBE_TIMEOUT);
            if (tasks == null) {
                return false;
            }
            return tasks.stream().anyMatch(t -> !TeamTask.isTerminal(t.state()));
        } catch (RuntimeException e) {
            log.debug("team board probe failed: {}", e.toString());
            return false;
        }
    }

    /** 消费未能通过收件箱投递的通知。 */
    String drainPendingNotices() {
        if (pendingNotices.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String notice;
        while ((notice = pendingNotices.poll()) != null) {
            sb.append('\n').append(notice).append('\n');
        }
        return sb.toString();
    }

    /**
     * 渲染完整团队段落：稳定指导和成员名册。也用于 BYO {@code
     * team_join} 启动。中间件本身仅把 {@link #renderTeamGuidance} 放入系统头部，
     * 名册随尾部消息发送，因为成员状态属于运行时状态。
     */
    public static String renderTeamSection(TeamContext ctx) {
        return renderTeamGuidance(ctx) + renderRoster(ctx);
    }

    /** 渲染按配置稳定的团队指导：身份、目标、规则、角色约束。 */
    private static String renderTeamGuidance(TeamContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## Agent Team\n\n");
        sb.append("You are participating in Agent Team **")
                .append(nullToEmpty(ctx.teamName()))
                .append("**");
        if (ctx.isLead()) {
            sb.append(" as the **lead**");
        } else {
            sb.append(" as teammate **").append(nullToEmpty(ctx.myRole())).append("**");
        }
        sb.append(".\n\n");
        if (ctx.objective() != null && !ctx.objective().isBlank()) {
            sb.append("**Objective:** ").append(ctx.objective()).append("\n\n");
        }
        sb.append(
                "Coordinate **only** via registered tools whose names match the actions below (for"
                    + " example `createTask`, `sendMessage`) or the unified `team` tool with"
                    + " parameter `action`. Never invent XML tags, ✿TEAM✿ markers, or prose-only"
                    + " fake calls — those are not executed.\n");
        sb.append(
                "When you reply to a teammate, address them by their roster name — sendMessage to"
                        + " your own name is rejected, and it would only wake you again instead of"
                        + " reaching them.\n");
        if (ctx.availableActions() != null && !ctx.availableActions().isEmpty()) {
            sb.append("Allowed tool / action names for your role: ")
                    .append(String.join(", ", ctx.availableActions()))
                    .append(".\n");
        }
        if (ctx.isLead()) {
            sb.append(
                    "As lead: create and assign tasks, spawn/shutdown teammates when available,"
                            + " approve/reject plans, and call completeTeam when the objective is"
                            + " done.\n");
            sb.append(
                    "Teammate outcomes reach you automatically: every completeTask / failTask and"
                        + " every member failure arrives as a team notification that wakes you, and"
                        + " the board below is refreshed at the start of each of your turns. So do"
                        + " not busy-poll listTasks in a loop — if you have nothing to do until a"
                        + " teammate reports back, call wait_async_results, or end your turn and"
                        + " you will be woken. Use listTasks only for detail the digest omits, such"
                        + " as a full result.\n");
            sb.append(
                    "Never end a turn owning a task you have not settled. Announcing that you will"
                        + " work on something is not work: there is no later turn unless a"
                        + " notification wakes you. Either do the work now and call completeTask,"
                        + " or assign the task to a teammate and wait for their report.\n");
            sb.append(
                    "You own the final answer. When every task is settled, do not just relay the"
                            + " individual results: reason over the whole board, resolve"
                            + " disagreements between teammates, state the conclusion the objective"
                            + " asked for, and only then call completeTeam.\n");
        } else {
            sb.append(
                    "As worker: call listClaimableTasks (or listTasks) first — it includes tasks"
                        + " assigned to you, not only the open board. Then claimTask before product"
                        + " tools. You may omit expected_version on claimTask.\n");
            sb.append(
                    "An empty board is normal — it usually means the lead has not assigned you"
                        + " anything yet. Do not start solving the team objective on your own and"
                        + " do not poll in a loop: just end your turn. Every assignment arrives as"
                        + " a team notification that wakes you with the task id and"
                        + " description.\n");
            sb.append(
                    "Always close the loop: call completeTask with a result summary when you"
                        + " finish, or failTask with a reason when you cannot. Both notify the lead"
                        + " automatically — the result text you pass is what the lead sees, so make"
                        + " it self-contained. Never finish a task silently, and use sendMessage"
                        + " for anything the lead needs before the task is done.\n");
            sb.append(
                    "**This turn is your whole execution, and there is no human in this session.**"
                        + " Nobody reads your prose, so never ask the user a question, request"
                        + " documents or credentials, or say you will continue later. When a tool"
                        + " is unavailable, a credential is missing, or the request is ambiguous,"
                        + " either proceed with what you can and report the gap in your"
                        + " completeTask result, or call failTask with the reason. Any task you"
                        + " still own when the turn ends is failed for you, with your closing"
                        + " message recorded as the reason — so settle it yourself and keep control"
                        + " of what the lead reads.\n");
        }
        TeamContext.RecoveryContext recovery = ctx.recoveryContext();
        if (recovery != null) {
            sb.append("\n**Recovery context:** you were restarted");
            if (recovery.restartCount() > 0) {
                sb.append(" (restart #").append(recovery.restartCount()).append(')');
            }
            if (recovery.previousSessionId() != null && !recovery.previousSessionId().isBlank()) {
                sb.append("; previous session ").append(recovery.previousSessionId());
            }
            sb.append(".\n");
            if (recovery.interruptedTask() != null) {
                sb.append("- Interrupted task: ")
                        .append(nullToEmpty(recovery.interruptedTask().id()))
                        .append(" — ")
                        .append(nullToEmpty(recovery.interruptedTask().subject()))
                        .append('\n');
            }
            if (recovery.completedTasks() != null && !recovery.completedTasks().isEmpty()) {
                sb.append("- Completed before crash:\n");
                for (TeamContext.CompletedTask t : recovery.completedTasks()) {
                    sb.append("  - ")
                            .append(nullToEmpty(t.id()))
                            .append(": ")
                            .append(nullToEmpty(t.subject()))
                            .append('\n');
                }
            }
            if (recovery.recentMessages() != null && !recovery.recentMessages().isEmpty()) {
                sb.append("- Recent team messages:\n");
                for (TeamContext.RecentMessage m : recovery.recentMessages()) {
                    sb.append("  - ")
                            .append(nullToEmpty(m.from()))
                            .append(": ")
                            .append(nullToEmpty(m.content()))
                            .append('\n');
                }
            }
        }
        return sb.toString();
    }

    /**
     * 渲染成员名册快照。成员状态随队友工作而变化，
     * 因此放入尾部状态消息，而非系统头部。上下文无成员时为空。
     */
    private static String renderRoster(TeamContext ctx) {
        if (ctx.members() == null || ctx.members().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(
                "**Roster** (use the member name in bold for every owner / member / to"
                        + " argument — never the agent name):\n");
        for (TeamContext.MemberSnapshot m : ctx.members()) {
            sb.append("- **")
                    .append(nullToEmpty(m.name()))
                    .append("** — agent ")
                    .append(nullToEmpty(m.agentRef()))
                    .append(", ")
                    .append(nullToEmpty(m.status()))
                    .append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * 构建携带易变团队状态的合成尾部消息：成员名册快照、负责人的看板摘要，
     * 以及尚未绑定会话期间缓冲的通知。各部分都为空时返回 {@code null}。
     *
     * <p>角色和元数据与 Harness 提醒消息一致：布局将其放在对话之后，
     * 压缩时不会把合成消息加入持久化摘要。
     */
    private static Msg buildTeamStateMsg(TeamContext ctx, String boardDigest, String notices) {
        String roster = renderRoster(ctx);
        boolean hasDigest = !boardDigest.isEmpty();
        boolean hasNotices = !notices.isEmpty();
        if (roster.isEmpty() && !hasDigest && !hasNotices) {
            return null;
        }
        StringBuilder sb = new StringBuilder("<RUNTIME_STATE>\n");
        if (!roster.isEmpty()) {
            sb.append(roster.stripTrailing()).append('\n');
        }
        if (hasDigest) {
            sb.append(boardDigest.stripTrailing()).append('\n');
        }
        if (hasNotices) {
            sb.append(notices.stripTrailing()).append('\n');
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
                                "team_state"))
                .build();
    }

    static List<Msg> prependToSystemMessage(List<Msg> messages, String extra) {
        if (messages == null || messages.isEmpty() || extra == null || extra.isEmpty()) {
            return messages;
        }
        List<Msg> out = new ArrayList<>(messages.size());
        boolean applied = false;
        for (Msg msg : messages) {
            if (!applied && msg != null && msg.getRole() == MsgRole.SYSTEM) {
                String text = extractText(msg);
                out.add(
                        Msg.builder()
                                .role(MsgRole.SYSTEM)
                                .content(TextBlock.builder().text(text + extra).build())
                                .build());
                applied = true;
            } else {
                out.add(msg);
            }
        }
        if (!applied) {
            List<Msg> withSys = new ArrayList<>(messages.size() + 1);
            withSys.add(
                    Msg.builder()
                            .role(MsgRole.SYSTEM)
                            .content(TextBlock.builder().text(extra).build())
                            .build());
            withSys.addAll(messages);
            return withSys;
        }
        return out;
    }

    private static String extractText(Msg msg) {
        if (msg.getContent() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        msg.getContent()
                .forEach(
                        b -> {
                            if (b instanceof TextBlock tb && tb.getText() != null) {
                                sb.append(tb.getText());
                            }
                        });
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
