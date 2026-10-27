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
package io.agentscope.harness.agent.observation;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.observation.ActionObservation;
import io.agentscope.core.state.State;

/**
 * 由运行时管理的证据。原始结果与会话状态具有相同的访问和保留边界。
 */
public record StoredActionObservation(ActionObservation observation, ToolResultBlock result)
        implements State {}
