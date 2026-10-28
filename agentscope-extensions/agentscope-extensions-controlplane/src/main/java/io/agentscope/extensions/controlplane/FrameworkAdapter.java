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

import io.agentscope.extensions.controlplane.model.AgentTaskAssignment;
import io.agentscope.extensions.controlplane.model.ContextSnapshot;
import io.agentscope.extensions.controlplane.model.Inventory;
import io.agentscope.extensions.controlplane.model.MessagePage;
import io.agentscope.extensions.controlplane.model.SessionEvent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import reactor.core.publisher.Mono;

/**
 * 每种智能体框架对应一个适配器，向 {@link SessionBridge} 提供统一的框架会话视图，对应 Python SDK 的 {@code FrameworkAdapter}。
 *
 * <p><b>旁路原则：</b>拦截只复制数据，不替换或阻塞框架主路径；上报失败被吞掉而不向上传播。接入观测不应改变智能体行为。
 *
 * <p>可选操作默认以 {@link UnsupportedOperationException} 失败。仅在 {@link #capabilities()} 声明真正实现的能力；控制平面按能力列表控制 UI，过度声明会生成无法工作的按钮。
 */
public interface FrameworkAdapter {

    String CAP_CONTEXT_QUERY = "context-query";
    String CAP_MESSAGE_QUERY = "message-query";
    String CAP_SUBAGENT_INVENTORY = "subagent-inventory";
    String CAP_WORKSPACE_INVENTORY = "workspace-inventory";
    String CAP_SESSION_COMMAND = "session-command";
    String CAP_SESSION_ABORT = "session-abort";
    String CAP_TASK_QUERY = "task-query";
    String CAP_SUBAGENT_TASK_QUERY = "subagent-task-query";
    String CAP_SUBAGENT_TASK_COMMAND = "subagent-task-command";
    String CAP_PLAN_MODE = "plan-mode";
    String CAP_AGENT_TASK = "agent-task";
    String CAP_EXPORT_TRANSCRIPT = "export-transcript";
    String CAP_CONVERSATION_INBOUND = "conversation-inbound";

    String COMMAND_COMPRESS = "compress";
    String COMMAND_TERMINATE = "terminate";
    String COMMAND_ABORT = "abort";

    /**
 * 每个快照上报的框架标识，例如 {@code agentscope-java}。
 */
    String frameworkName();

    /**
 * 框架版本；无法确定时为空字符串。
 */
    default String frameworkVersion() {
        return "";
    }

    /**
 * 此适配器能否观测 {@code target}；必须无副作用。
 */
    boolean canHandle(Object target);

    /**
     * 挂接到框架对象。会话活动通过 {@code emit} 发布，绝不能让它破坏框架主路径。
     */
    void attach(Object target, Consumer<SessionEvent> emit);

    /**
 * 移除拦截并恢复框架对象。
 */
    void detach();

    /**
     * 适配器挂载后调用，使其可通过 {@link SessionBridge#describeSession} 提供事件流无法携带的系统提示、工具列表、上下文窗口等元数据。
     */
    default void onBridgeAttached(SessionBridge bridge) {
        // 仅发出事件的适配器无需在此处理。
    }

    /**
 * 第四级：会话下一次模型调用实际使用的上下文。
 */
    Mono<ContextSnapshot> extractContext(String sessionId);

    /**
 * 第三级：包含已被压缩移除消息的完整历史。
 */
    default Mono<MessagePage> listMessages(String sessionId, int offset, int limit) {
        return Mono.error(unsupported("message-query"));
    }

    default Mono<List<Inventory.SubagentInfo>> listSubagents() {
        return Mono.error(unsupported("subagent-inventory"));
    }

    default Mono<List<Inventory.WorkspaceInfo>> listWorkspaces() {
        return Mono.error(unsupported("workspace-inventory"));
    }

    /**
     * {@code GET .../tasks} 的会话任务列表。每个映射遵循固定 Task 结构：{@code id}、{@code subject}、{@code state}、{@code owner}、{@code blockedBy}、{@code updatedAt}、{@code frameworkMeta}。
     */
    default Mono<List<Map<String, Object>>> listTasks(String sessionId) {
        return Mono.error(unsupported("task-query"));
    }

    /**
     * {@code GET .../subagent-tasks} 的后台子智能体任务（不是待办列表）。每个映射采用框架自身结构，典型键为 {@code id}、{@code status}、{@code subject}/{@code label}。
     */
    default Mono<List<Map<String, Object>>> listSubagentTasks(String sessionId) {
        return Mono.error(unsupported("subagent-task-query"));
    }

    /**
 * 取消一个后台子智能体任务，能力为 {@code subagent-task-command}。
 */
    default Mono<Void> cancelSubagentTask(String sessionId, String taskId) {
        return Mono.error(unsupported("subagent-task-command"));
    }

    /**
     * 为会话进入或退出计划模式，能力为 {@code plan-mode}。{@code params} 是包含 {@code "active": true|false} 的 JSON 对象。
     */
    default Mono<Void> setPlanMode(String sessionId, byte[] params) {
        return Mono.error(unsupported("plan-mode"));
    }

    /**
     * 向活动会话注入控制台／控制平面用户消息，必须使用框架官方的继续轮次 API。
     */
    default Mono<Void> injectUserMessage(String sessionId, String content) {
        return Mono.error(unsupported("inbound-message"));
    }

    /**
     * 运行控制平面对话轮次并返回最终助手回复。与消息注入不同，完成结果必须携带回答，而不只是确认收到输入。
     */
    default Mono<String> runConversationTurn(String sessionId, String content) {
        return Mono.error(unsupported("conversation-result"));
    }

    /**
 * {@code GET /agentscope/info} → {@code agentConfig} 的有效 Definition 快照。
 */
    default Map<String, Object> buildAgentConfig() {
        return Map.of();
    }

    /**
     * 对会话执行 {@link #COMMAND_COMPRESS}、{@link #COMMAND_TERMINATE} 或 {@link #COMMAND_ABORT}。
     */
    default Mono<Void> handleCommand(String sessionId, String command, byte[] params) {
        return Mono.error(unsupported("session-command"));
    }

    /**
 * 将下行 AgentTask 投递物化为隔离执行。
 */
    default Mono<Void> handleAgentTask(AgentTaskAssignment assignment) {
        return Mono.error(unsupported(CAP_AGENT_TASK));
    }

    /**
     * 此适配器提供的能力。推送侧能力（{@code session-reporting}、{@code event-reporting}、{@code context-reporting}）由桥接器添加。
     */
    default Set<String> capabilities() {
        return Set.of(CAP_CONTEXT_QUERY);
    }

    private static UnsupportedOperationException unsupported(String capability) {
        return new UnsupportedOperationException(capability + " is not supported by this adapter");
    }
}
