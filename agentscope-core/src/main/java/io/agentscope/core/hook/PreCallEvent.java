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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 智能体开始处理之前触发的事件。
 *
 * <p><b>可修改：</b>是，通过 {@link #setInputMessages(List)}
 *
 * <p><b>上下文：</b>
 * <ul>
 *   <li>{@link #getAgent()}：智能体实例</li>
 *   <li>{@link #getMemory()}：处理本次调用前的智能体现有记忆或对话历史</li>
 *   <li>{@link #getInputMessages()}：输入给智能体的消息（可修改）</li>
 * </ul>
 *
 * <p><b>用途：</b>
 * <ul>
 *   <li>记录智能体执行的开始</li>
 *   <li>初始化执行专用资源</li>
 *   <li>跟踪智能体调用指标</li>
 *   <li>在智能体处理前过滤或修改输入消息</li>
 * </ul>
 *
 * @deprecated 自 2.0.0 起弃用。请改用 {@link io.agentscope.core.middleware.MiddlewareBase}。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public final class PreCallEvent extends HookEvent {

    private List<Msg> inputMessages;

    /**
     * PreCallEvent 的构造器。
     *
     * @param agent 智能体实例（不可为 null）
     * @param inputMessages 输入给智能体的消息（不可为 null）
     * @throws NullPointerException 当 agent 或 inputMessages 为 null 时抛出
     */
    public PreCallEvent(Agent agent, List<Msg> inputMessages) {
        super(HookEventType.PRE_CALL, agent);
        this.inputMessages =
                new ArrayList<>(
                        Objects.requireNonNull(inputMessages, "inputMessages cannot be null"));
    }

    /**
     * 获取智能体调用的输入消息。
     *
     * @return 输入消息
     */
    public List<Msg> getInputMessages() {
        return inputMessages;
    }

    /**
     * 修改智能体调用的输入消息。
     *
     * @param inputMessages 新消息列表（不可为 null）
     * @throws NullPointerException 当 inputMessages 为 null 时抛出
     */
    public void setInputMessages(List<Msg> inputMessages) {
        this.inputMessages = Objects.requireNonNull(inputMessages, "inputMessages cannot be null");
    }
}
