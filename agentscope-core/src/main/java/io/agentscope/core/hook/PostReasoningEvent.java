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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.ToolValidator;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 推理完成后触发的事件。
 *
 * <p><b>可修改：</b>是，通过 {@link #setReasoningMessage(Msg)}
 *
 * <p><b>上下文：</b>
 * <ul>
 *   <li>{@link #getAgent()}：智能体实例</li>
 *   <li>{@link #getMemory()}：智能体记忆</li>
 *   <li>{@link #getModelName()}：模型名称</li>
 *   <li>{@link #getGenerateOptions()}：生成选项</li>
 *   <li>{@link #getReasoningMessage()}：推理结果（可修改）</li>
 * </ul>
 *
 * <p><b>注意：</b>消息内容可能包含文本块、思考块和工具调用块。
 * 在智能体处理工具调用前，可以修改其中任意内容。
 *
 * <p><b>用途：</b>
 * <ul>
 *   <li>执行前过滤或修改工具调用</li>
 *   <li>添加／移除内容块</li>
 *   <li>修改文本或思考内容</li>
 *   <li>添加元数据</li>
 *   <li>通过 {@link #stopAgent()} 请求停止智能体，以便人工审核</li>
 * </ul>
 *
 * @deprecated 自 2.0.0 起弃用。请改用 {@link io.agentscope.core.middleware.MiddlewareBase}。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public final class PostReasoningEvent extends ReasoningEvent {

    private Msg reasoningMessage;
    private boolean stopRequested = false;
    private List<Msg> gotoReasoningMsgs = null;

    /**
     * PostReasoningEvent 的构造器。
     *
     * @param agent 智能体实例（不可为 null）
     * @param modelName 模型名称（不可为 null）
     * @param generateOptions 生成选项（可为 null）
     * @param reasoningMessage 推理结果消息（可为 null）
     */
    public PostReasoningEvent(
            Agent agent, String modelName, GenerateOptions generateOptions, Msg reasoningMessage) {
        super(HookEventType.POST_REASONING, agent, modelName, generateOptions);
        this.reasoningMessage = reasoningMessage;
    }

    /**
     * 获取 LLM 的推理结果消息。
     *
     * @return 推理消息，可能为 null
     */
    public Msg getReasoningMessage() {
        return reasoningMessage;
    }

    /**
     * 修改推理结果消息。
     *
     * @param reasoningMessage 新的推理消息
     */
    public void setReasoningMessage(Msg reasoningMessage) {
        this.reasoningMessage = reasoningMessage;
    }

    /**
     * 请求智能体在本次推理阶段后停止。
     *
     * <p>调用后，智能体会返回当前包含 ToolUseBlocks 的消息，
     * 而不继续执行工具。用户随后可审核待处理的
     * 工具调用，并通过无参调用 {@code agent.call()} 恢复执行。
     *
     * <p>这支持人在回路场景：敏感或重要工具调用
     * 执行前需要用户确认。
     */
    public void stopAgent() {
        this.stopRequested = true;
    }

    /**
     * 检查是否已请求停止。
     *
     * @return 调用过 {@link #stopAgent()} 时为 true，否则为 false
     */
    public boolean isStopRequested() {
        return stopRequested;
    }

    /**
     * 请求返回推理阶段，不添加任何消息。
     *
     * <p>仅在推理消息中不存在待处理 ToolUse 块时有效。
     * 若存在待处理 ToolUse 块，会抛出 {@link IllegalStateException}，
     * 因为必须先提供 ToolResult 消息。
     *
     * @throws IllegalStateException 存在待处理 ToolUse 块时抛出
     */
    public void gotoReasoning() {
        gotoReasoning(List.of());
    }

    /**
     * 请求携带单条消息返回推理阶段。
     *
     * <p>如果推理消息包含 ToolUse 块，提供的消息必须包含
     * 匹配的 ToolResult 块。也可以包含额外消息（例如提示或提醒）。
     *
     * @param msg 推理前要添加到记忆的消息（例如 ToolResult 或提示消息）
     * @throws IllegalStateException ToolResult 验证失败时抛出
     */
    public void gotoReasoning(Msg msg) {
        gotoReasoning(List.of(msg));
    }

    /**
     * 请求携带多条消息返回推理阶段。
     *
     * <p>验证规则：
     * <ul>
     *   <li>无待处理 ToolUse 时：消息原样添加（可以是提示／提醒）</li>
     *   <li>存在待处理 ToolUse 时：消息必须包含匹配的 ToolResult 块</li>
     * </ul>
     *
     * @param msgs 推理前要添加到记忆的消息
     * @throws IllegalStateException ToolResult 验证失败时抛出
     */
    public void gotoReasoning(List<Msg> msgs) {
        ToolValidator.validateToolResultMatch(reasoningMessage, msgs);
        this.gotoReasoningMsgs = new ArrayList<>(msgs);
    }

    /**
     * 检查是否已请求返回推理阶段。
     *
     * @return 调用过任意 gotoReasoning 方法时为 true，否则为 false
     */
    public boolean isGotoReasoningRequested() {
        return gotoReasoningMsgs != null;
    }

    /**
     * 获取返回推理阶段前要添加的消息。
     *
     * @return 要添加的消息；未调用 gotoReasoning 时为 null
     */
    public List<Msg> getGotoReasoningMsgs() {
        return gotoReasoningMsgs;
    }
}
