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
package io.agentscope.extensions.controlplane.store;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.controlplane.ControlPlaneConfig;
import io.agentscope.extensions.controlplane.transport.ControlPlaneHttpClient;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.bus.AsyncToolRegistry;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import java.util.Objects;

/**
 * 控制平面托管存储组件工厂（{@code /api/v1/dp/*}）。
 *
 * <p>有意不包含 {@link AgentStateStore}，调用方必须通过 {@link #withAgentStateStore(AgentStateStore)} 提供自己的实现。
 * <pre>{@code
 * ControlPlaneStores cp = ControlPlaneStores.fromEnv();
 * HarnessAgent.builder()
 *     .distributedStore(cp.withAgentStateStore(redisStore.agentStateStore()))
 *     .filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.USER))
 *     .build();
 * }</pre>
 */
public final class ControlPlaneStores {

    private final ControlPlaneHttpClient http;
    private final String agentName;
    private final String namespace;

    private ControlPlaneStores(ControlPlaneHttpClient http, String agentName, String namespace) {
        this.http = Objects.requireNonNull(http, "http");
        this.agentName = Objects.requireNonNull(agentName, "agentName");
        this.namespace = (namespace == null || namespace.isBlank()) ? "default" : namespace;
    }

    /**
     * 根据环境变量创建工厂：{@code CONTROL_PLANE_HTTP}、{@code BUILDER_INTERNAL_TOKEN}、{@code CONTROL_PLANE_AGENT_NAME}、{@code CONTROL_PLANE_NAMESPACE}。
     */
    public static ControlPlaneStores fromEnv() {
        String httpBase = requireEnv("CONTROL_PLANE_HTTP");
        String token = requireEnv("BUILDER_INTERNAL_TOKEN");
        String agentName = requireEnv("CONTROL_PLANE_AGENT_NAME");
        String namespace = envOr("CONTROL_PLANE_NAMESPACE", "default");
        return create(httpBase, token, agentName, namespace);
    }

    /**
     * 使用显式连接设置创建工厂。
     *
     * @param httpBase 控制平面 HTTP 基础 URL
     * @param token 用于 {@code X-Builder-Internal-Token} 的内部令牌
     * @param agentName 逻辑智能体名称
     * @param namespace 租户／Kubernetes 命名空间
     */
    public static ControlPlaneStores create(
            String httpBase, String token, String agentName, String namespace) {
        return new ControlPlaneStores(
                new ControlPlaneHttpClient(httpBase, token), agentName, namespace);
    }

    /**
     * 复用 {@link ControlPlaneConfig} 的连接设置创建工厂。
     *
     * @param config 控制平面桥接配置，必须设置 {@code controlPlaneHttp} 和令牌
     */
    public static ControlPlaneStores from(ControlPlaneConfig config) {
        Objects.requireNonNull(config, "config");
        if (config.controlPlaneHttp() == null || config.controlPlaneHttp().isBlank()) {
            throw new IllegalArgumentException("controlPlaneHttp is required");
        }
        if (config.internalToken() == null || config.internalToken().isBlank()) {
            throw new IllegalArgumentException("internalToken is required");
        }
        return create(
                config.controlPlaneHttp(),
                config.internalToken(),
                config.agentKey(),
                config.namespace());
    }

    /**
 * 返回由控制平面支撑的 {@link BaseStore}。
 */
    public BaseStore baseStore() {
        return new ControlPlaneBaseStore(http, agentName, namespace);
    }

    /**
 * 返回由控制平面支撑的 {@link SandboxExecutionGuard}。
 */
    public SandboxExecutionGuard sandboxExecutionGuard() {
        return new ControlPlaneSandboxExecutionGuard(http, agentName, namespace);
    }

    /**
 * 返回由控制平面支撑的 {@link SandboxSnapshotSpec}。
 */
    public SandboxSnapshotSpec sandboxSnapshotSpec() {
        return new ControlPlaneSnapshotSpec(http, agentName, namespace);
    }

    /**
     * 返回由控制平面支撑的 {@link MessageBus}；托管存储端点不可达时返回 {@code null}，使 {@code HarnessAgent} 可回退到 {@code WorkspaceMessageBus}。
     */
    public MessageBus messageBus() {
        if (!hostedStoreReachable()) {
            return null;
        }
        return new ControlPlaneMessageBus(http, agentName, namespace);
    }

    /**
     * 返回由控制平面支撑的 {@link AsyncToolRegistry}；托管存储端点不可达时返回 {@code null}，使 {@code HarnessAgent} 可回退到工作区注册表。
     */
    public AsyncToolRegistry asyncToolRegistry() {
        if (!hostedStoreReachable()) {
            return null;
        }
        return new ControlPlaneAsyncToolRegistry(http, agentName, namespace);
    }

    /**
 * 返回由控制平面支撑的 {@link TaskRepository}，不可达时为 {@code null}。
 */
    public TaskRepository taskRepository() {
        if (!hostedStoreReachable()) {
            return null;
        }
        return new ControlPlaneTaskRepository(http, agentName, namespace, agentName);
    }

    /**
     * 返回由控制平面支撑的 {@link SessionTurnGate}，用于分布式逐会话轮次串行化。
     *
     * <p>通过 {@link #withAgentStateStore(AgentStateStore)} 接线时，调用方应将 {@link io.agentscope.core.ReActAgent} 的 {@code conflictPolicy} 配置为 {@code FAIL}。
     */
    public SessionTurnGate sessionTurnGate() {
        return new ControlPlaneSessionTurnGate(http, agentName, namespace);
    }

    /**
     * 将托管组件与用户提供的 {@link AgentStateStore} 组合，构建 {@link DistributedStore}。
     *
     * @param stateStore 必需的智能体会话状态后端
     * @return 组合式分布存储
     */
    public DistributedStore withAgentStateStore(AgentStateStore stateStore) {
        Objects.requireNonNull(stateStore, "stateStore");
        return DistributedStore.builder()
                .agentStateStore(stateStore)
                .baseStore(baseStore())
                .sandboxSnapshotSpec(sandboxSnapshotSpec())
                .sandboxExecutionGuard(sandboxExecutionGuard())
                .messageBus(messageBus())
                .asyncToolRegistry(asyncToolRegistry())
                .taskRepository(taskRepository())
                .sessionTurnGate(sessionTurnGate())
                .build();
    }

    /**
 * 探测 {@code GET /api/v1/dp/healthz}，失败视为不可达。
 */
    private boolean hostedStoreReachable() {
        try {
            ControlPlaneHttpClient.Response resp = http.send("GET", "/api/v1/dp/healthz", null);
            return resp.status() >= 200 && resp.status() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private static String requireEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Required environment variable missing: " + name);
        }
        return v.trim();
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        return v.trim();
    }
}
