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

/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.agentscope.extensions.controlplane.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.extensions.controlplane.model.AgentTaskAssignment;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * 由应用显式启用，使每次执行尝试使用不可变的控制平面定义。
 */
@FunctionalInterface
public interface WorkspaceAgentFactory {
    /**
 * 创建由本次尝试独占的新智能体，包括其工具和凭据策略。
 */
    HarnessAgent create(JsonNode definition, AgentTaskAssignment assignment);
}
