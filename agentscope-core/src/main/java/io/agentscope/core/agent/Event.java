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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.message.Msg;

/**
 * 智能体流式执行期间发出的事件。
 *
 * <p>事件通过明确的类型标识和每条消息的完成状态，以结构化方式呈现智能体执行过程。
 *
 * <p><b>用法示例：</b>
 * <pre>{@code
 * agent.stream(userMsg, options)
 *     .subscribe(event -> {
 *         switch (event.getType()) {
 *             case REASONING -> {
 *                 if (event.isLast()) {
 *                     System.out.println("✓ Reasoning complete");
 *                 } else {
 *                     System.out.print("...");  // Progress indicator
 *                 }
 *             }
 *             case TOOL_RESULT -> {
 *                 System.out.println("Tool: " + event.getMessage().getTextContent());
 *             }
 *         }
 *     });
 * }</pre>
 *
 * @deprecated 自 2.0.0 起弃用。这是 v1 的粗粒度事件类型，保留用于已弃用的 {@code stream(...)} API
 * 的返回值，以及 harness、AGUI、A2A、chat-completions-web 和 Kotlin 扩展模块使用的传输格式。
 * 新代码应通过 {@code ReActAgent#streamEvents(...)} 使用 {@code io.agentscope.core.event.AgentEvent}，
 * 后者提供细粒度事件层次（28 种类型化事件，覆盖完整智能体生命周期及 HITL 流程）。
 * 依赖此类的内部模块迁移到 {@code AgentEvent} 后，本类将标记为 {@code forRemoval = true}。
 */
@Deprecated(since = "2.0.0")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Event {

    private final EventType type;
    private final Msg message;
    private final boolean isLast;

    /**
     * 当此事件由父级 {@code stream()} 调用中嵌套的子智能体发出时，标识其来源（子）智能体。
     * 顶层智能体自身发出的事件为 {@code null}。
     */
    private final EventSource source;

    /**
     * 创建新事件（顶层智能体，不带来源）。
     *
     * @param type 事件类型（REASONING、TOOL_RESULT 等）
     * @param message 消息内容
     * @param isLast 此事件是否为最后一条／完整消息
     */
    public Event(EventType type, Msg message, boolean isLast) {
        this(type, message, isLast, null);
    }

    /**
     * 创建可选携带来源的新事件。
     *
     * @param type 事件类型
     * @param message 消息内容
     * @param isLast 是否为最后一条／完整消息
     * @param source 来源子智能体；顶层智能体为 {@code null}
     */
    @JsonCreator
    public Event(
            @JsonProperty("type") EventType type,
            @JsonProperty("message") Msg message,
            @JsonProperty("isLast") boolean isLast,
            @JsonProperty("source") EventSource source) {
        this.type = type;
        this.message = message;
        this.isLast = isLast;
        this.source = source;
    }

    /**
     * 返回附加了指定来源的事件副本，不修改原始事件（不可变复制）。
     *
     * @param source 来源子智能体描述符
     * @return 已设置 {@code source} 的新 Event
     */
    public Event withSource(EventSource source) {
        return new Event(this.type, this.message, this.isLast, source);
    }

    /**
     * 获取事件类型。
     *
     * <p>据此判断消息种类及其处理方式。
     *
     * @return 事件类型
     */
    public EventType getType() {
        return type;
    }

    /**
     * 获取消息内容。
     *
     * <p>消息包含实际数据，详情可查看 {@link Msg#getRole()}、{@link Msg#getContent()} 等字段。
     *
     * @return 消息
     */
    public Msg getMessage() {
        return message;
    }

    /**
     * 检查此事件是否为最后一条／完整消息。
     *
     * <p><b>返回值：</b>
     * <ul>
     * <li>{@code true}：完整消息或最终片段。可以安全地持久化、作为最终结果展示或触发下游处理。</li>
     * <li>{@code false}：中间片段。后续还会有使用相同消息 ID 的事件，适用于实时界面更新。</li>
     * </ul>
     *
     * <p><b>流式行为：</b>流式事件（例如大模型流式输出）会使用相同的 {@link Msg#getId()} 发出多条事件。
     *
     * <p><b>非流式行为：</b>非流式场景只产生一条事件，且 {@code isLast} 始终为 {@code true}。
     *
     *
     *  <pre>
     * Event(type=REASONING, msg(id="abc", content=[...]), isLast=false)
     * Event(type=REASONING, msg(id="abc", content=[...]), isLast=false)
     * Event(type=REASONING, msg(id="abc", content=[...]), isLast=true)  ← Final
     * </pre>
     *
     *  <pre>{@code
     * agent.stream(userMsg, options)
     *     .subscribe(event -> {
     *         if (event.isLast()) {
     *             // Complete message - safe to persist or process
     *             database.save(event.getMessage());
     *         } else {
     *             // Intermediate chunk - update UI
     *             ui.append(event.getMessage().getTextContent());
     *         }
     *     });
     * }</pre>
     */
    @JsonProperty("isLast")
    public boolean isLast() {
        return isLast;
    }

    /**
     * 返回来源子智能体描述符；顶层智能体发出的事件返回 {@code null}。
     *
     * <p>消费者无需带外元数据，即可据此将子智能体事件路由到正确的界面卡片或日志通道。
     *
     * @return 事件来源；顶层智能体为 {@code null}
     */
    public EventSource getSource() {
        return source;
    }

    /**
     * 获取消息 ID（委托给 {@link Msg#getId()}）。
     *
     * <p>具有相同消息 ID 的事件属于同一条逻辑消息，可据此将流式片段分组。
     *
     * @return 消息 ID
     */
    public String getMessageId() {
        return message.getId();
    }

    @Override
    public String toString() {
        if (source != null) {
            return String.format(
                    "Event{type=%s, isLast=%s, msgId=%s, contentBlocks=%d, source=%s}",
                    type, isLast, message.getId(), message.getContent().size(), source);
        }
        return String.format(
                "Event{type=%s, isLast=%s, msgId=%s, contentBlocks=%d}",
                type, isLast, message.getId(), message.getContent().size());
    }
}
