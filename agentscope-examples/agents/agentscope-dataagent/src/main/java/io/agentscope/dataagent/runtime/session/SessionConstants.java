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
package io.agentscope.dataagent.runtime.session;

/** 托管会话/子智能体流程共享常量。 */
public final class SessionConstants {

    /** 最大允许派生深度，防止失控递归（主智能体为 0，子智能体为 1，再下一层为 2，依此类推）。 */
    public static final int MAX_SPAWN_DEPTH = 3;

    /** 工具拥有者为顶层 Harness（无父会话）时使用的合成请求者键。 */
    public static final String ROOT_REQUESTER_SESSION_KEY = "agent:main:root";

    private SessionConstants() {}

    /** 解析请求者键：显式父会话键或 {@link #ROOT_REQUESTER_SESSION_KEY}。 */
    public static String resolveRequesterKey(String parentSessionKey) {
        if (parentSessionKey != null && !parentSessionKey.isBlank()) {
            return parentSessionKey.trim();
        }
        return ROOT_REQUESTER_SESSION_KEY;
    }
}
