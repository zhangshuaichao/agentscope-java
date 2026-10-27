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
package io.agentscope.core.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 标识智能体流式执行期间 {@link Event} 的来源。
 *
 * <p>子智能体在父智能体的 {@code stream()} 调用内同步运行时，其产生的每个 {@link Event}
 * 都携带 {@code EventSource}，编码事件生产智能体的完整逻辑路径。
 * 这对应 LangGraph streaming v2 的<em>命名空间／路径</em>概念，使界面、适配器和日志消费者
 * 能区分顶层智能体与任意嵌套子智能体的事件。
 *
 * <p>顶层（父）智能体发出的事件满足 {@code source == null}。
 *
 * <h2>字段含义</h2>
 * <table border="1">
 * <caption>EventSource 字段</caption>
 * <tr><th>字段</th><th>含义</th></tr>
 * <tr><td>{@code agentKey}</td><td>已启动智能体实例的不透明运行时句柄，例如
 * {@code "agent:researcher:uuid"}。大模型将此值传回 {@code agent_send}，以寻址先前启动的智能体。</td></tr>
 * <tr><td>{@code agentId}</td><td>已注册的子智能体类型标识符（工作区 {@code subagents/}
 * 目录中去掉 {@code .md} 后缀的文件名），例如 {@code "researcher"}。</td></tr>
 * <tr><td>{@code agentName}</td><td>便于阅读的子智能体显示名称；未设置时可以为 null。</td></tr>
 * <tr><td>{@code sessionId}</td><td>此次子智能体调用的唯一会话 ID；
 * 对同一已启动实例进行 {@code agent_send} 后续调用时保持不变。</td></tr>
 * <tr><td>{@code parentSessionId}</td><td>发起 {@code agent_spawn} 的父智能体的 AgentStateStore ID。</td></tr>
 * <tr><td>{@code taskId}</td><td>预留字段；仅当子智能体作为后台任务运行时非空
 * （异步流式尚未实现，见下文扩展点）。</td></tr>
 * <tr><td>{@code depth}</td><td>嵌套深度：{@code 1} 为顶层智能体直接子级，{@code 2} 为孙级，以此类推。</td></tr>
 * <tr><td>{@code path}</td><td>从根到生产者、以斜杠分隔的调用层次，例如
 * {@code "main/researcher"} 或 {@code "main/planner/sub-executor"}；按 {@code "/"} 分割可恢复层次。</td></tr>
 * </table>
 *
 * <h2>路径约定</h2>
 * <p>{@code path} 是形如 {@code "main/researcher"} 或 {@code "main/planner/sub-executor"}
 * 的斜杠分隔字符串。根段为父智能体会话 ID（回退值为 {@code "main"}）；
 * 后续各段为对应深度子智能体的 {@code agentId}。消费者按 {@code "/"} 分割即可重建调用层次。
 *
 * <h2>扩展点（尚未实现）</h2>
 * <ul>
 * <li><b>异步任务流：</b>子智能体作为异步后台任务运行时填入 {@code taskId}。
 * 任务仓库可为每个任务附加独立的 {@link SubagentEventBus}，
 * 使父级 {@code Flux} 在原始请求上下文结束后仍能累积事件。</li>
 * <li><b>远程子智能体流：</b>Agent Protocol 的 {@code GET /tasks/{taskId}/events} SSE 端点
 * 可传递远程子智能体事件。收到事件后，先附加根据远程智能体元数据构建的 {@code EventSource}，
 * 再转发到本地父级 {@code FluxSink}。</li>
 * <li><b>AG-UI／Web 适配器：</b>消费者可检查 {@code source.path} 和 {@code source.depth}，
 * 渲染分层卡片或可折叠追踪面板，提供类似 LangGraph v2 流式处理的界面体验。</li>
 * </ul>
 *
 * <h2>示例</h2>
 * <pre>{@code
 * EventSource src = EventSource.builder()
 *     .agentKey("agent:researcher:uuid-1")
 *     .agentId("researcher")
 *     .agentName("ResearcherAgent")
 *     .sessionId("sub-uuid-1")
 *     .parentSessionId("parent-session")
 *     .depth(1)
 *     .path("main/researcher")
 *     .build();
 *
 * // Extend for a grandchild agent:
 * EventSource grandchild = src.withAppendedPath("sub-executor");
 * // grandchild.getDepth() == 2, grandchild.getPath() == "main/researcher/sub-executor"
 * }</pre>
 */
/**
 * @deprecated 自 2.0.0 起弃用。仅供 v1 {@link Event} 流将事件归属到已启动的子智能体。
 * 新 {@code io.agentscope.core.event.AgentEvent} 流的子智能体来源通道已列入 v2 路线图；
 * 实现后，{@code EventSource} 将被标记为 {@code forRemoval = true}。
 */
@Deprecated(since = "2.0.0")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class EventSource {

    /**
     * 标识已启动智能体实例的不透明键（例如 {@code "agent:researcher:uuid"}）。
     */
    private final String agentKey;

    /**
     * 已注册的智能体类型标识符（例如 {@code "researcher"}）。
     */
    private final String agentId;

    /**
     * 便于阅读的智能体名称。
     */
    private final String agentName;

    /**
     * 本次子智能体调用使用的 AgentStateStore ID。
     */
    private final String sessionId;

    /**
     * 启动此子智能体的父智能体的 AgentStateStore ID。
     */
    private final String parentSessionId;

    /**
     * 本次子智能体调用关联后台任务时使用的可选任务 ID（为未来异步流式支持预留）。
     */
    private final String taskId;

    /**
     * 嵌套深度：1 表示顶层智能体的直接子级，2 表示孙级，以此类推。
     */
    private final int depth;

    /**
     * 从顶层智能体到本事件生产者、以斜杠分隔的路径，例如 {@code "main/researcher"}。
     */
    private final String path;

    private EventSource(Builder builder) {
        this.agentKey = builder.agentKey;
        this.agentId = builder.agentId;
        this.agentName = builder.agentName;
        this.sessionId = builder.sessionId;
        this.parentSessionId = builder.parentSessionId;
        this.taskId = builder.taskId;
        this.depth = builder.depth;
        this.path = builder.path;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 返回新的 {@code EventSource}：向 {@code path} 追加 {@code segment}，并将 {@code depth} 加一。
     * 其他所有字段均从当前实例复制。
     */
    public EventSource withAppendedPath(String segment) {
        String newPath = (path == null || path.isEmpty()) ? segment : path + "/" + segment;
        return builder()
                .agentKey(agentKey)
                .agentId(agentId)
                .agentName(agentName)
                .sessionId(sessionId)
                .parentSessionId(parentSessionId)
                .taskId(taskId)
                .depth(depth + 1)
                .path(newPath)
                .build();
    }

    public String getAgentKey() {
        return agentKey;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getAgentName() {
        return agentName;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getParentSessionId() {
        return parentSessionId;
    }

    public String getTaskId() {
        return taskId;
    }

    public int getDepth() {
        return depth;
    }

    public String getPath() {
        return path;
    }

    @Override
    public String toString() {
        return "EventSource{path='" + path + "', depth=" + depth + ", agentId='" + agentId + "'}";
    }

    /**
     * {@link EventSource} 的构建器。
     */
    public static final class Builder {

        private String agentKey;
        private String agentId;
        private String agentName;
        private String sessionId;
        private String parentSessionId;
        private String taskId;
        private int depth = 1;
        private String path;

        private Builder() {}

        public Builder agentKey(String agentKey) {
            this.agentKey = agentKey;
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder parentSessionId(String parentSessionId) {
            this.parentSessionId = parentSessionId;
            return this;
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder depth(int depth) {
            this.depth = depth;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public EventSource build() {
            return new EventSource(this);
        }
    }
}
