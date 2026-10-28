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
package io.agentscope.builder.web.managed;

import io.agentscope.harness.agent.sandbox.Sandbox;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 由 Environment Worker（调用 Worker REST API 的远程 Worker）注册的活动 {@code sessionId -> Sandbox} 句柄。
 * <p>{@link HandsLeaseService} 在 {@link EnvironmentWorkQueue} 入队后查询该注册表；旧版本地沙箱兼容路径可将已注册沙箱作为优先级 1 的 {@code externalSandbox} 接入轮次的 {@link io.agentscope.harness.agent.sandbox.SandboxContext}。当前事件驱动 Worker 不依赖此注册表；其完整生命周期由 Worker 管理。
 */
@Component
public class ExternalSandboxRegistry {

    private final ConcurrentHashMap<String, Sandbox> sandboxes = new ConcurrentHashMap<>();

    /**
     * 注册或替换会话的活动沙箱。
     */
    public void register(String sessionId, Sandbox sandbox) {
        sandboxes.put(sessionId, sandbox);
    }

    /**
     * 返回会话的活动沙箱，未注册时为 {@code null}。
     */
    public Sandbox get(String sessionId) {
        return sandboxes.get(sessionId);
    }

    /**
     * 移除并返回会话的活动沙箱，未注册时为 {@code null}。
     */
    public Sandbox remove(String sessionId) {
        return sandboxes.remove(sessionId);
    }

    /**
     * 返回当前已注册沙箱数量，供指标与诊断使用。
     */
    public int size() {
        return sandboxes.size();
    }
}
