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
package io.agentscope.core.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.agentscope.core.message.Msg;
import io.agentscope.core.permission.PermissionContextState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 每个智能体独立持有的可变运行状态。
 *
 * <p>保存单个智能体在对话中途恢复所需的信息：对话缓冲区、滚动摘要、
 * 每轮回复的标识符、权限／工具／任务子上下文，以及当前推理轮次的计数器。
 *
 * <p>{@link #context} 列表通过 {@link #getContext()} 暴露防御性副本，
 * 通过 {@link #contextMutable()} 暴露可直接修改的实时引用；
 * 这一方式与 {@link TaskContextState} 一致。存储层可以替换整个
 * {@link AgentState} 实例，也可以原地修改内部集合。
 *
 * <p>{@code summary} 使用自由文本 {@link String} 表示；待未来上下文压缩策略
 * 需要时，再引入更丰富的结构化形式。
 */
@JsonPropertyOrder({
    "session_id",
    "user_id",
    "summary",
    "context",
    "reply_id",
    "cur_iter",
    "shutdown_interrupted",
    "permission_context",
    "tool_context",
    "tasks_context",
    "plan_mode_context"
})
public final class AgentState implements State {

    private final String sessionId;
    private final String userId;
    private String summary;
    private final List<Msg> context;
    private String replyId;
    private int curIter;
    private boolean shutdownInterrupted;
    private PermissionContextState permissionContext;
    private final ToolContextState toolContext;
    private final TaskContextState tasksContext;
    private final PlanModeContextState planModeContext;

    private AgentState(Builder builder) {
        this.sessionId = builder.sessionId == null ? newHex() : builder.sessionId;
        this.userId = builder.userId;
        this.summary = builder.summary == null ? "" : builder.summary;
        this.context = new ArrayList<>(builder.context);
        this.replyId = builder.replyId == null ? newHex() : builder.replyId;
        this.curIter = builder.curIter;
        this.shutdownInterrupted = builder.shutdownInterrupted;
        this.permissionContext =
                builder.permissionContext == null
                        ? PermissionContextState.builder().build()
                        : builder.permissionContext;
        this.toolContext =
                builder.toolContext == null
                        ? ToolContextState.builder().build()
                        : builder.toolContext;
        this.tasksContext =
                builder.tasksContext == null ? new TaskContextState() : builder.tasksContext;
        this.planModeContext =
                builder.planModeContext == null
                        ? new PlanModeContextState()
                        : builder.planModeContext;
    }

    @JsonCreator
    static AgentState fromJson(
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("summary") String summary,
            @JsonProperty("context") List<Msg> context,
            @JsonProperty("reply_id") String replyId,
            @JsonProperty("cur_iter") Integer curIter,
            @JsonProperty("shutdown_interrupted") Boolean shutdownInterrupted,
            @JsonProperty("permission_context") PermissionContextState permissionContext,
            @JsonProperty("tool_context") ToolContextState toolContext,
            @JsonProperty("tasks_context") TaskContextState tasksContext,
            @JsonProperty("plan_mode_context") PlanModeContextState planModeContext) {
        Builder b = builder();
        if (sessionId != null) {
            b.sessionId(sessionId);
        }
        if (userId != null) {
            b.userId(userId);
        }
        if (summary != null) {
            b.summary(summary);
        }
        if (context != null) {
            b.context(context);
        }
        if (replyId != null) {
            b.replyId(replyId);
        }
        if (curIter != null) {
            b.curIter(curIter);
        }
        if (shutdownInterrupted != null) {
            b.shutdownInterrupted(shutdownInterrupted);
        }
        if (permissionContext != null) {
            b.permissionContext(permissionContext);
        }
        if (toolContext != null) {
            b.toolContext(toolContext);
        }
        if (tasksContext != null) {
            b.tasksContext(tasksContext);
        }
        if (planModeContext != null) {
            b.planModeContext(planModeContext);
        }
        return b.build();
    }

    private static String newHex() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @JsonProperty("session_id")
    public String getSessionId() {
        return sessionId;
    }

    /** 允许为空；{@code null} 表示匿名或单租户上下文。 */
    @JsonProperty("user_id")
    public String getUserId() {
        return userId;
    }

    @JsonProperty("summary")
    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary == null ? "" : summary;
    }

    /** 对话缓冲区的防御性副本。 */
    @JsonProperty("context")
    // 【学习提示】先复制再包装为不可修改列表，调用者既不能修改原列表，
    // 也不会通过这个返回值观察到后续列表增删；这不是 Msg 元素的深拷贝。
    public List<Msg> getContext() {
        return Collections.unmodifiableList(new ArrayList<>(context));
    }

    /** 供组件原地追加或替换消息的可变实时引用。 */
    // 【学习提示】这里有意暴露真实列表供框架更新状态；它不是只读查询接口。
    // 调用方应遵守所属会话的执行／并发边界，不能因 getContext() 安全就认为此处同样安全。
    public List<Msg> contextMutable() {
        return context;
    }

    @JsonProperty("reply_id")
    public String getReplyId() {
        return replyId;
    }

    public void setReplyId(String replyId) {
        this.replyId = replyId == null ? newHex() : replyId;
    }

    @JsonProperty("cur_iter")
    public int getCurIter() {
        return curIter;
    }

    public void setCurIter(int curIter) {
        this.curIter = curIter;
    }

    @JsonProperty("shutdown_interrupted")
    public boolean isShutdownInterrupted() {
        return shutdownInterrupted;
    }

    public void setShutdownInterrupted(boolean shutdownInterrupted) {
        this.shutdownInterrupted = shutdownInterrupted;
    }

    @JsonProperty("permission_context")
    public PermissionContextState getPermissionContext() {
        return permissionContext;
    }

    /**
     * 替换此会话的权限上下文，用于在运行时改变评估模式
     * （例如切换到 {@link io.agentscope.core.permission.PermissionMode#BYPASS}）。
     * 如果调用方按会话缓存了 {@code PermissionEngine}，则必须在调用后重新构建该引擎。
     *
     * @param permissionContext 新的权限上下文（不可为 null）
     */
    public void setPermissionContext(PermissionContextState permissionContext) {
        this.permissionContext =
                java.util.Objects.requireNonNull(
                        permissionContext, "permissionContext must not be null");
    }

    @JsonProperty("tool_context")
    public ToolContextState getToolContext() {
        return toolContext;
    }

    @JsonProperty("tasks_context")
    public TaskContextState getTasksContext() {
        return tasksContext;
    }

    @JsonProperty("plan_mode_context")
    public PlanModeContextState getPlanModeContext() {
        return planModeContext;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 将此状态序列化为格式化的 JSON 字符串。
     *
     * <p>供外部存储将完整智能体状态持久化为单个 JSON 文档
     * （例如 {@code agent_state.json}）。
     */
    public String toJson() {
        return io.agentscope.core.util.JsonUtils.getJsonCodec().toPrettyJson(this);
    }

    /**
     * 从 {@link #toJson()} 生成的 JSON 字符串中反序列化 {@link AgentState}。
     */
    public static AgentState fromJsonString(String json) {
        return io.agentscope.core.util.JsonUtils.getJsonCodec().fromJson(json, AgentState.class);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AgentState other)) {
            return false;
        }
        return curIter == other.curIter
                && shutdownInterrupted == other.shutdownInterrupted
                && Objects.equals(sessionId, other.sessionId)
                && Objects.equals(userId, other.userId)
                && Objects.equals(summary, other.summary)
                && Objects.equals(context, other.context)
                && Objects.equals(replyId, other.replyId)
                && Objects.equals(permissionContext, other.permissionContext)
                && Objects.equals(toolContext, other.toolContext)
                && Objects.equals(tasksContext, other.tasksContext)
                && Objects.equals(planModeContext, other.planModeContext);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                sessionId,
                userId,
                summary,
                context,
                replyId,
                curIter,
                shutdownInterrupted,
                permissionContext,
                toolContext,
                tasksContext,
                planModeContext);
    }

    @Override
    public String toString() {
        return "AgentState{sessionId="
                + sessionId
                + ", replyId="
                + replyId
                + ", curIter="
                + curIter
                + ", shutdownInterrupted="
                + shutdownInterrupted
                + ", contextSize="
                + context.size()
                + '}';
    }

    public static final class Builder {
        private String sessionId;
        private String userId;
        private String summary;
        private List<Msg> context = new ArrayList<>();
        private String replyId;
        private int curIter;
        private boolean shutdownInterrupted;
        private PermissionContextState permissionContext;
        private ToolContextState toolContext;
        private TaskContextState tasksContext;
        private PlanModeContextState planModeContext;

        private Builder() {}

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /** 允许为空；{@code null} 表示匿名或单租户上下文。 */
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public Builder context(List<Msg> context) {
            this.context = context == null ? new ArrayList<>() : new ArrayList<>(context);
            return this;
        }

        public Builder addMessage(Msg message) {
            Objects.requireNonNull(message, "message must not be null");
            this.context.add(message);
            return this;
        }

        public Builder replyId(String replyId) {
            this.replyId = replyId;
            return this;
        }

        public Builder curIter(int curIter) {
            this.curIter = curIter;
            return this;
        }

        public Builder shutdownInterrupted(boolean shutdownInterrupted) {
            this.shutdownInterrupted = shutdownInterrupted;
            return this;
        }

        public Builder permissionContext(PermissionContextState permissionContext) {
            this.permissionContext = permissionContext;
            return this;
        }

        public Builder toolContext(ToolContextState toolContext) {
            this.toolContext = toolContext;
            return this;
        }

        public Builder tasksContext(TaskContextState tasksContext) {
            this.tasksContext = tasksContext;
            return this;
        }

        public Builder planModeContext(PlanModeContextState planModeContext) {
            this.planModeContext = planModeContext;
            return this;
        }

        public AgentState build() {
            return new AgentState(this);
        }
    }
}
