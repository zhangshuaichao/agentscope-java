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

/**
 * 摘要生成完成后触发的事件。
 *
 * <p><b>可修改：</b>是，通过 {@link #setSummaryMessage(Msg)}
 *
 * <p><b>上下文：</b>
 * <ul>
 *   <li>{@link #getAgent()}：智能体实例</li>
 *   <li>{@link #getMemory()}：智能体记忆</li>
 *   <li>{@link #getModelName()}：模型名称</li>
 *   <li>{@link #getGenerateOptions()}：生成选项</li>
 *   <li>{@link #getSummaryMessage()}：摘要结果（可修改）</li>
 * </ul>
 *
 * <p><b>注意：</b>此事件在摘要生成之后触发，使钩子可以
 * 在最终摘要消息返回之前修改它。
 *
 * <p><b>用途：</b>
 * <ul>
 *   <li>过滤或修改摘要内容</li>
 *   <li>为摘要消息添加元数据</li>
 *   <li>记录摘要结果</li>
 *   <li>通过 {@link #stopAgent()} 请求停止智能体</li>
 * </ul>
 *
 * @deprecated 自 2.0.0 起弃用。请改用 {@link io.agentscope.core.middleware.MiddlewareBase}。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public final class PostSummaryEvent extends SummaryEvent {

    private Msg summaryMessage;
    private boolean stopRequested = false;

    /**
     * PostSummaryEvent 的构造器。
     *
     * @param agent 智能体实例（不可为 null）
     * @param modelName 模型名称（不可为 null）
     * @param generateOptions 生成选项（可为 null）
     * @param summaryMessage 摘要结果消息（可为 null）
     */
    public PostSummaryEvent(
            Agent agent, String modelName, GenerateOptions generateOptions, Msg summaryMessage) {
        super(HookEventType.POST_SUMMARY, agent, modelName, generateOptions);
        this.summaryMessage = summaryMessage;
    }

    /**
     * 获取摘要结果消息。
     *
     * @return 摘要消息，可能为 null
     */
    public Msg getSummaryMessage() {
        return summaryMessage;
    }

    /**
     * 修改摘要结果消息。
     *
     * @param summaryMessage 新的摘要消息
     */
    public void setSummaryMessage(Msg summaryMessage) {
        this.summaryMessage = summaryMessage;
    }

    /**
     * 请求智能体在本次摘要阶段后停止。
     *
     * <p>调用后，智能体会将摘要消息作为最终结果返回。
     * 这主要是为了与其他事件类型保持一致；摘要通常已是
     * 最后阶段，因此主要充当日志或指标用途的信号。
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
}
