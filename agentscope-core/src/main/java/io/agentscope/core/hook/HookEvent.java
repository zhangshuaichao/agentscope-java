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
package io.agentscope.core.hook;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.memory.AgentStateMemoryView;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 所有钩子事件的基类。
 *
 * <p>这是密封类，只允许预定义的事件类型。
 * 这使 switch 表达式能够进行穷尽式模式匹配。
 *
 * <p>所有事件都提供公共上下文访问：
 * <ul>
 *   <li>{@link #getAgent()}：智能体实例</li>
 *   <li>{@link #getMemory()}：便捷访问智能体记忆（可能为 null）</li>
 *   <li>{@link #getType()}：事件类型</li>
 *   <li>{@link #getTimestamp()}：事件发生时间</li>
 * </ul>
 *
 * <p><b>系统消息生命周期：</b>每个事件携带统一的 {@code systemMsg} 字段，
 * 保存 LLM 可见的唯一 {@link MsgRole#SYSTEM} 消息。{@code ReActAgent}
 * 在事件生命周期中按以下方式管理该字段：
 * <ol>
 *   <li>每次 {@code call()} 开始时，从 {@code sysPrompt} 初始化，早于
 *       {@link PreCallEvent} 钩子的运行。</li>
 *   <li>{@link PreCallEvent} 钩子完成后，得到的系统消息被
 *       <em>冻结</em>为整个调用的基线。</li>
 *   <li>每次 {@link PreReasoningEvent}（以及 {@link PreSummaryEvent}）之前，冻结的基线
 *       会重新注入事件，使这些事件上的钩子始终从同一个干净基线开始，
 *       并可追加本轮专用内容。</li>
 *   <li>调用 {@code model.stream(...)} 前，事件最终的系统消息会
 *       作为首个元素置于 {@link PreReasoningEvent#getInputMessages()} 之前。</li>
 * </ol>
 *
 * <p>由于每个 {@link PreReasoningEvent} 都从冻结基线的新副本开始，
 * 每轮触发的钩子（例如子智能体指导）可以安全使用
 * {@link #appendSystemContent(String)}：内容只加入本轮副本，
 * 不会跨轮次累积。
 *
 * <p>钩子只能通过 {@link #setSystemMessage(Msg)}、
 * {@link #appendSystemContent(String)} 或 {@link #appendSystemContent(ContentBlock)} 修改系统消息。
 * 禁止将 {@link MsgRole#SYSTEM} 消息直接注入 {@code inputMessages}，
 * 否则运行时会抛出 {@link IllegalStateException}。
 *
 * <p><b>可修改性：</b>事件是否允许修改，取决于
 * 具体事件类是否提供 setter 方法。
 *
 * @see Hook
 * @see HookEventType
 * @deprecated 自 2.0.0 起弃用。请改用 {@link io.agentscope.core.middleware.MiddlewareBase}。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public abstract sealed class HookEvent
        permits PreCallEvent, PostCallEvent, ReasoningEvent, ActingEvent, SummaryEvent, ErrorEvent {

    private final HookEventType type;
    private final Agent agent;
    private final long timestamp;

    /**
     * 此事件的统一系统消息。钩子通过下方辅助方法读写该字段；
     * {@code ReActAgent} 在事件之间传播它，并在每次推理调用前
     * 将它置于 LLM 输入开头。
     */
    private Msg systemMsg;

    /**
     * HookEvent 的构造器。
     *
     * @param type 事件类型（不可为 null）
     * @param agent 智能体实例（不可为 null）
     * @throws NullPointerException 当 type 或 agent 为 null 时抛出
     */
    protected HookEvent(HookEventType type, Agent agent) {
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.agent = Objects.requireNonNull(agent, "agent cannot be null");
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 获取事件类型。
     *
     * @return 事件类型
     */
    public final HookEventType getType() {
        return type;
    }

    /**
     * 获取智能体实例。
     *
     * @return 智能体实例（绝不为 null）
     */
    public final Agent getAgent() {
        return agent;
    }

    /**
     * 获取事件创建时间戳。
     *
     * @return 时间戳（自纪元起的毫秒数）
     */
    public final long getTimestamp() {
        return timestamp;
    }

    /**
     * 便捷访问智能体对话上下文，以只读
     * {@link Memory} 视图包装。修改应针对运行中智能体状态上的
     * {@link io.agentscope.core.state.AgentState#contextMutable()} 进行。
     *
     * @return 只读 {@link Memory} 视图；事件的智能体不是
     *     {@link ReActAgent} 时为 {@code null}
     */
    public final Memory getMemory() {
        if (agent instanceof ReActAgent reactAgent) {
            return new AgentStateMemoryView(reactAgent::getAgentState);
        }
        return null;
    }

    // ==================== 系统消息 API ====================

    /**
     * 返回当前统一系统消息；尚未设置时为 {@code null}。
     *
     * <p>在 {@link PreCallEvent} 和 {@link PreReasoningEvent} 上，链中更早钩子的修改
     * 已经反映在此处。
     *
     * @return 系统消息，可能为 null
     */
    public final Msg getSystemMessage() {
        return systemMsg;
    }

    /**
     * 使用给定消息替换整个系统消息。
     *
     * <p>只需添加部分系统消息内容时，优先使用 {@link #appendSystemContent}；
     * 只有需要完全自定义系统消息时，才使用此方法。
     *
     * @param systemMsg 新的系统消息（可为 null，表示清除）
     */
    public final void setSystemMessage(Msg systemMsg) {
        this.systemMsg = systemMsg;
    }

    /**
     * 将给定文本作为新的 {@link TextBlock} 追加到系统消息末尾。
     *
     * <p>尚不存在系统消息时，会自动创建，角色为
     * {@link MsgRole#SYSTEM}，名称为 {@code "system"}。
     *
     * @param text 要追加的文本（不可为 null）
     */
    public final void appendSystemContent(String text) {
        Objects.requireNonNull(text, "text cannot be null");
        appendSystemContent(TextBlock.builder().text(text).build());
    }

    /**
     * 在系统消息末尾追加一个 {@link ContentBlock}。
     *
     * <p>尚不存在系统消息时，会自动创建，角色为
     * {@link MsgRole#SYSTEM}，名称为 {@code "system"}。
     *
     * @param block 要追加的内容块（不可为 null）
     */
    public final void appendSystemContent(ContentBlock block) {
        Objects.requireNonNull(block, "block cannot be null");
        if (systemMsg == null) {
            systemMsg = Msg.builder().name("system").role(MsgRole.SYSTEM).content(block).build();
        } else {
            List<ContentBlock> merged = new ArrayList<>(systemMsg.getContent());
            merged.add(block);
            systemMsg =
                    Msg.builder()
                            .id(systemMsg.getId())
                            .name(systemMsg.getName())
                            .role(MsgRole.SYSTEM)
                            .content(merged)
                            .metadata(systemMsg.getMetadata())
                            .timestamp(systemMsg.getTimestamp())
                            .build();
        }
    }
}
