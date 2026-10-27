/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.state;

/**
 * 可持久化状态对象的标记接口。
 *
 * <p>实现此接口的类可以由 {@link
 * io.agentscope.core.state.AgentStateStore} 实现序列化并存储。对于简单状态对象，
 * 建议使用 Java Record。
 *
 * <p>现有领域对象（例如 {@link io.agentscope.core.message.Msg}）可直接实现此接口，
 * 避免额外的转换开销。
 *
 * @see io.agentscope.core.state.AgentStateStore
 */
public interface State {}
