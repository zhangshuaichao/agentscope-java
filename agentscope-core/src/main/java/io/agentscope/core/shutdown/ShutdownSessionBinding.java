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
package io.agentscope.core.shutdown;

import io.agentscope.core.state.AgentStateStore;
import java.util.Objects;

/**
 * 用于持久化关闭检查点的 AgentStateStore 绑定。
 *
 * @deprecated 自 2.0.0 起弃用。请改用 {@link ShutdownStateSaver}，并通过
 *     {@link GracefulShutdownManager#bindStateSaver} 绑定。
 */
@Deprecated(since = "2.0.0", forRemoval = true)
public record ShutdownSessionBinding(AgentStateStore stateStore, String userId, String sessionId) {

    public ShutdownSessionBinding {
        Objects.requireNonNull(stateStore, "stateStore cannot be null");
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
    }
}
