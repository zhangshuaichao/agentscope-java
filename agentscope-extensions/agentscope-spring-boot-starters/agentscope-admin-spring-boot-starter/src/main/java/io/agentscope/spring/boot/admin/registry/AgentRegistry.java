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
package io.agentscope.spring.boot.admin.registry;

import io.agentscope.core.agent.Agent;
import java.util.List;
import java.util.Optional;

/**
 * 跟踪管理接口可检查或操作的活动智能体的 SPI。
 *
 * <p>AgentScope-Java 默认将 {@code ReActAgent} 暴露为原型作用域 bean；此 starter 启动时扫描一次注入映射，并不持续追踪之后创建的实例，因此应用应在构建长期存活智能体后调用 {@link #register(Agent)}，通常放在拥有它的 Service 的 {@code @PostConstruct} 或智能体工厂中，而不能仅依赖 {@code Map<String, Agent>} 资源扫描。
 *
 * <p>默认 {@code InMemoryAgentRegistry} 适合单节点；面向集群的部署可替换为基于 Nacos、Redis 或其他共享存储的实现。
 */
public interface AgentRegistry {

    void register(Agent agent);

    void unregister(String agentId);

    Optional<Agent> find(String agentId);

    /**
 * 按插入顺序返回全部已注册智能体。
 */
    List<Agent> list();

    int size();
}
