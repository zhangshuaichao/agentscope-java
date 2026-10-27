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
package io.agentscope.core.session;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 基于原生历史提供只读快照与受隔离保护的管理性状态变更。 */
public final class SessionStateAccess {
    private static final Duration LEASE = Duration.ofMinutes(2);

    private SessionStateAccess() {}

    /** 独立的状态副本，以及恢复该副本所依据的已提交前缀。 */
    public record Snapshot(long asOfSeq, AgentState state) {}

    /** 读取一个已提交前缀，不获取写入者身份，也不改变会话历史。 */
    public static Snapshot read(SessionLog log, Supplier<AgentState> initialState) {
        SessionProjection projection = SessionProjection.read(log);
        AgentState state = projection.restore();
        return new Snapshot(projection.asOfSeq(), state == null ? initialState.get() : state);
    }

    /**
     * 持有原生写入者租约时，重新加载并修改状态，然后提交检查点。
     * 存在活跃执行或尚未解决的工具派发时，不允许管理性修改。
     */
    public static Snapshot update(
            SessionLog log,
            Supplier<AgentState> initialState,
            String reason,
            Consumer<AgentState> mutation) {
        Objects.requireNonNull(mutation, "mutation");
        return change(log, initialState, reason, null, mutation);
    }

    /**
     * 只有快照对应的已提交前缀仍是最新时，才保存显式编辑的快照。
     * 过时快照会被拒绝，绝不覆盖其他位置已完成的执行。
     */
    public static Snapshot replace(
            SessionLog log, Supplier<AgentState> initialState, String reason, Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        AgentState frozen = AgentState.fromJsonString(snapshot.state().toJson());
        return change(log, initialState, reason, new Snapshot(snapshot.asOfSeq(), frozen), null);
    }

    private static Snapshot change(
            SessionLog log,
            Supplier<AgentState> initialState,
            String reason,
            Snapshot replacement,
            Consumer<AgentState> mutation) {
        if (reason == null || reason.isBlank())
            throw new IllegalArgumentException("A state change reason is required");
        SessionLog.Writer writer = log.acquire(UUID.randomUUID().toString(), LEASE);
        try {
            SessionProjection projection = SessionProjection.read(log);
            if (!projection.uncertainToolCalls().isEmpty())
                throw new SessionLogException(
                        "Reconcile unresolved tool outcomes before changing session state");
            // 【学习提示】asOfSeq 是检查时看到的历史版本。替换前重新比较，
            // 防止旧快照覆盖其他执行刚提交的新状态，这是乐观并发控制的边界。
            if (replacement != null && replacement.asOfSeq() != projection.asOfSeq())
                throw new SessionLogException(
                        "Session state changed since inspection; reload or use an atomic update");
            AgentState current = projection.restore();
            if (current == null) current = initialState.get();
            String userId = current.getUserId();
            String sessionId = current.getSessionId();
            String beforeJson = current.toJson();
            var previousMessageIds = new HashSet<String>();
            for (Msg message : current.getContext()) previousMessageIds.add(message.getId());
            AgentState changed = replacement == null ? current : replacement.state();
            if (mutation != null) mutation.accept(changed);
            if (!Objects.equals(userId, changed.getUserId())
                    || !Objects.equals(sessionId, changed.getSessionId()))
                throw new IllegalArgumentException("State changes must preserve session identity");
            String stateJson = changed.toJson();
            List<SessionEvent> facts = new ArrayList<>();
            var before = JsonUtils.getJsonCodec().fromJson(beforeJson, Map.class);
            var after = JsonUtils.getJsonCodec().fromJson(stateJson, Map.class);
            boolean contextChanged = !Objects.equals(before.get("context"), after.get("context"));
            if (contextChanged && !SessionInteractions.pending(log).isEmpty())
                throw new SessionLogException(
                        "Resolve pending interactions before replacing context");
            if (contextChanged)
                append(
                        facts,
                        projection.asOfSeq(),
                        "context/replaced",
                        Map.of(
                                "reason",
                                reason,
                                "messages",
                                changed.getContext(),
                                "previousAsOfSeq",
                                projection.asOfSeq()));
            for (Msg message : changed.getContext()) {
                if (previousMessageIds.contains(message.getId())) continue;
                String type =
                        message.hasContentBlocks(ToolResultBlock.class)
                                ? "tool/result"
                                : message.getRole() == MsgRole.ASSISTANT
                                        ? "message/assistant"
                                        : message.getRole() == MsgRole.SYSTEM
                                                ? "message/system"
                                                : "message/user";
                append(facts, projection.asOfSeq(), type, Map.of("message", message));
            }
            for (String field :
                    List.of("tasks_context", "plan_mode_context", "permission_context")) {
                Object value = after.get(field);
                if (value != null && !Objects.equals(value, before.get(field)))
                    append(
                            facts,
                            projection.asOfSeq(),
                            field.equals("tasks_context")
                                    ? "task/changed"
                                    : field.equals("plan_mode_context")
                                            ? "plan/changed"
                                            : "permission/changed",
                            Map.of("state", value));
            }
            append(
                    facts,
                    projection.asOfSeq(),
                    "state/checkpoint",
                    Map.of("stateJson", stateJson, "projectorVersion", 1, "reason", reason));
            SessionLog.Head head =
                    log.commit(writer, UUID.randomUUID().toString(), projection.asOfSeq(), facts);
            return new Snapshot(head.seq(), AgentState.fromJsonString(stateJson));
        } finally {
            // 【学习提示】即使验证、变更或提交失败，也必须释放租约，避免阻塞后续执行。
            log.release(writer);
        }
    }

    private static void append(
            List<SessionEvent> facts, long previousSeq, String type, Object payload) {
        facts.add(
                new SessionEvent(
                        1,
                        UUID.randomUUID().toString(),
                        previousSeq + facts.size() + 1,
                        System.currentTimeMillis(),
                        type,
                        null,
                        null,
                        true,
                        JsonUtils.getJsonCodec().toJson(payload)));
    }
}
