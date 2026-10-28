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
package io.agentscope.extensions.agentprotocol;

import io.agentscope.harness.agent.subagent.protocol.RemoteAgentEvent;
import reactor.core.publisher.Flux;

/**
 * Agent Protocol 任务 SSE 端点使用的事件总线抽象。
 *
 * <p>实现负责为每个任务分配单调递增序号、发布事件，并重放 {@code fromSeq} 之后的事件。默认实现 {@link AgentProtocolTaskEventBus} 在进程内存中保存重放缓冲。需要跨实例流式传输的应用可提供共享实现（如 Redis Streams 适配器），并将其注册为 {@code AgentProtocolEventBus} bean。
 */
public interface AgentProtocolEventBus {

    /** 为任务发布事件，并返回已赋予协议字段的事件。 */
    RemoteAgentEvent publish(String taskId, RemoteAgentEvent event);

    /** 订阅任务事件流，仅重放序号大于 {@code fromSeq} 的事件。 */
    Flux<RemoteAgentEvent> subscribe(String taskId, long fromSeq);

    /** 完成并释放终态任务的事件流。 */
    void complete(String taskId);
}
