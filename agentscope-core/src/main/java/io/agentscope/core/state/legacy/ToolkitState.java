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
package io.agentscope.core.state.legacy;

import io.agentscope.core.state.State;
import java.util.List;

/**
 * 用于反序列化 v1 {@code toolkit_activeGroups} 会话条目的数据结构。
 *
 * <p>保留此类型仅供 {@link io.agentscope.core.state.LegacyStateLoader} 读取 v1 会话，
 * 完成向 {@link io.agentscope.core.state.AgentState} 的一次性迁移。
 * 新写入的数据绝不使用此类型。
 */
public record ToolkitState(List<String> activeGroups) implements State {}
