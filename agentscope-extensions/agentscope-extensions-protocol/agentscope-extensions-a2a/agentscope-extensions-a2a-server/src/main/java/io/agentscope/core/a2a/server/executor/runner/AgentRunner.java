/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.a2a.server.executor.runner;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * 在 A2A 服务器中运行智能体的接口。
 *
 * <p>定义启动、停止和获取智能体消息的核心契约。
 *
 * <p>用于扩展 {@link io.agentscope.core.agent.Agent} 的实际处理逻辑，
 * 方法以 {@link Msg} 为输入、{@link AgentEvent} 为输出。
 * 开发者可以在调用 {@link io.agentscope.core.agent.Agent} 前预处理，
 * 或在调用 {@link io.agentscope.core.agent.Agent} 后进行后处理。
 */
public interface AgentRunner {

    /**
     * 获取构建 AgentCard 所需的智能体名称。
     *
     * @return 智能体名称
     */
    String getAgentName();

    /**
     * 获取构建 AgentCard 所需的智能体描述。
     *
     * @return 智能体描述
     */
    String getAgentDescription();

    /**
     * 开始处理智能体请求并流式输出。
     *
     * @param requestMessages 来自 A2A 客户端的消息
     * @param options 请求选项，例如本次请求的 taskId、sessionId、userId
     * @return 执行期间发出事件的 Flux
     */
    Flux<AgentEvent> streamEvents(List<Msg> requestMessages, AgentRequestOptions options);

    /**
     * 停止处理智能体请求。
     *
     * @param taskId 需要停止的请求任务 ID
     */
    void stop(String taskId);
}
