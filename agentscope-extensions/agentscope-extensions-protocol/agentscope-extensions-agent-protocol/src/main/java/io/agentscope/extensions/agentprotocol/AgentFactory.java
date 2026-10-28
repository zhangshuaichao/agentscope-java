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

import io.agentscope.harness.agent.HarnessAgent;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 解析执行 Agent Protocol 任务的 {@link HarnessAgent}。
 *
 * <p>每轮运行调用一次：首次通过 {@code POST /tasks} 提交时，以及每次通过 {@code POST /tasks/{id}/resume} 恢复时，都会传入完整的 {@link AgentRequest}，包含提交的 {@code context} 映射。因此实现可以按 {@code agentId}、租户或任意自定义上下文属性进行路由，也可以为每个任务创建新智能体。
 *
 * <p>注册此类型的 bean 可覆盖默认工厂；默认工厂始终返回单个 {@link HarnessAgent} bean：
 *
 * <pre>{@code
 * @Bean
 * AgentFactory agentFactory(Map<String, HarnessAgent> agentsByName) {
 *     return request -> agentsByName.getOrDefault(request.agentId(), agentsByName.get("default"));
 * }
 * }</pre>
 *
 * <p>并发任务应每次返回独立实例（如 prototype 作用域的 bean），或者为共享智能体配置 {@code checkRunning(false)}。
 */
@FunctionalInterface
public interface AgentFactory {

    /** 返回本轮运行的智能体。不得返回 {@code null}；若无法提供，应抛出异常，使任务以错误状态失败。 */
    HarnessAgent create(AgentRequest request);

    /** 始终返回同一智能体、忽略请求的工厂。 */
    static AgentFactory fixed(HarnessAgent agent) {
        Objects.requireNonNull(agent, "agent");
        return request -> agent;
    }

    /** 由 supplier 提供智能体、忽略请求的工厂。 */
    static AgentFactory of(Supplier<HarnessAgent> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        return request -> supplier.get();
    }
}
