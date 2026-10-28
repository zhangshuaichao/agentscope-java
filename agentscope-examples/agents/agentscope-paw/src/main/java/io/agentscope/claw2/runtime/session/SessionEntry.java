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
package io.agentscope.claw2.runtime.session;

/**
 * 已注册受管会话的内部元数据，由 {@link SessionAgentManager} 使用。
 *
 * @param gateKey 网关用于将此会话映射回通道上下文的路由键；仅 {@link SessionKind#MAIN} 会话填充，可为 null
 */
public record SessionEntry(
        String sessionKey,
        String agentId,
        String sessionId,
        String label,
        SessionKind kind,
        String spawnedBy,
        int spawnDepth,
        long createdAtMs,
        long lastActivityMs,
        String sessionFilePath,
        String spawnRunId,
        String gateKey) {

    /**
     * 不含 gateKey 的向后兼容构造方法。
     */
    public SessionEntry(
            String sessionKey,
            String agentId,
            String sessionId,
            String label,
            SessionKind kind,
            String spawnedBy,
            int spawnDepth,
            long createdAtMs,
            long lastActivityMs,
            String sessionFilePath,
            String spawnRunId) {
        this(
                sessionKey,
                agentId,
                sessionId,
                label,
                kind,
                spawnedBy,
                spawnDepth,
                createdAtMs,
                lastActivityMs,
                sessionFilePath,
                spawnRunId,
                null);
    }
}
