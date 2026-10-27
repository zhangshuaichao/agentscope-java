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
package io.agentscope.harness.agent.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 管理当前调用的 {@link Sandbox} 实例生命周期。
 *
 * <p>获取优先级：{@link SandboxContext#getExternalSandbox()} &gt; {@link SandboxContext#getExternalSandboxState()} &gt; 持久化的 {@link SandboxState} &gt; {@link SandboxClient#create}。
 * 存在持久化状态但恢复失败时，新建回退保留持久化快照 ID，使工作区从旧归档恢复，而非悄然重置。
 *
 * <p>配置 {@link SandboxExecutionGuard} 时，管理器在恢复／创建沙箱前，为存在的隔离键获取执行 {@link SandboxLease}。
 * 租约通过 {@link SandboxAcquireResult} 携带，由调用方（{@link io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware}）在 {@link #release} 之后关闭，覆盖整个调用窗口。
 *
 * <p>优先级 1（外部沙箱）和优先级 2（外部沙箱状态）跳过保护器，因为调用方在外部管理该沙箱。
 */
public class SandboxManager {

    private static final Logger log = LoggerFactory.getLogger(SandboxManager.class);

    private final SandboxClient<?> client;
    private final SessionSandboxStateStore stateStore;
    private final String agentId;
    private final SandboxExecutionGuard executionGuard;

    public SandboxManager(
            SandboxClient<?> client, SessionSandboxStateStore stateStore, String agentId) {
        this(client, stateStore, agentId, SandboxExecutionGuard.noop());
    }

    public SandboxManager(
            SandboxClient<?> client,
            SessionSandboxStateStore stateStore,
            String agentId,
            SandboxExecutionGuard executionGuard) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
        this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
        this.executionGuard =
                executionGuard != null ? executionGuard : SandboxExecutionGuard.noop();
    }

    public SandboxAcquireResult acquire(
            SandboxContext sandboxContext, RuntimeContext runtimeContext) throws Exception {
        // 优先级 1：用户提供的沙箱，不应用保护器
        if (sandboxContext.getExternalSandbox() != null) {
            Sandbox external = sandboxContext.getExternalSandbox();
            log.debug(
                    "[sandbox] Priority 1: using user-managed sandbox: {}",
                    external.getState() != null ? external.getState().getSessionId() : "?");
            return SandboxAcquireResult.userManaged(external);
        }

        // 优先级 2：用户提供的状态，不应用保护器
        if (sandboxContext.getExternalSandboxState() != null) {
            Sandbox sandbox = client.resume(sandboxContext.getExternalSandboxState());
            log.debug(
                    "[sandbox] Priority 2: resuming from explicit state: {}",
                    sandboxContext.getExternalSandboxState().getSessionId());
            return SandboxAcquireResult.selfManaged(sandbox);
        }

        // 优先级 3 / 4：Harness 管理，存在作用域键时应用保护器
        Optional<SandboxIsolationKey> scopeKey =
                SandboxIsolationKey.resolve(
                        sandboxContext.getIsolationScope(), runtimeContext, agentId);

        SandboxLease lease = SandboxLease.noop();
        if (scopeKey.isPresent()) {
            log.debug("[sandbox] Acquiring execution guard for scope {}", scopeKey.get());
            lease = executionGuard.tryEnter(scopeKey.get());
        }

        try {
            String persistedSnapshotId = null;
            if (scopeKey.isPresent()) {
                try {
                    Optional<String> stateJson = stateStore.load(scopeKey.get());
                    if (stateJson.isPresent()) {
                        log.debug(
                                "[sandbox] Priority 3: resuming from persisted state (scope={})",
                                scopeKey.get());
                        SandboxState state =
                                client.deserializeState(
                                        stateJson.get(), sandboxContext.getSnapshotSpec());
                        // 用当前应用配置覆盖过期的 WorkspaceSpec
                        if (sandboxContext.getWorkspaceSpec() != null) {
                            state.setWorkspaceSpec(sandboxContext.getWorkspaceSpec().copy());
                        }
                        if (state.getSnapshot() != null) {
                            persistedSnapshotId = state.getSnapshot().getId();
                        }
                        Sandbox sandbox = client.resume(state);
                        return SandboxAcquireResult.selfManaged(sandbox, lease);
                    }
                } catch (Exception e) {
                    log.warn(
                            "[sandbox] Failed to load persisted state for scope {}, falling through"
                                    + " to fresh create: {}",
                            scopeKey.get(),
                            e.getMessage(),
                            e);
                }
            }

            log.debug("[sandbox] Priority 4: creating new sandbox");
            WorkspaceSpec spec =
                    sandboxContext.getWorkspaceSpec() != null
                            ? sandboxContext.getWorkspaceSpec().copy()
                            : new WorkspaceSpec();

            @SuppressWarnings("unchecked")
            SandboxClient<SandboxClientOptions> typedClient =
                    (SandboxClient<SandboxClientOptions>) client;
            Sandbox sandbox =
                    typedClient.create(
                            spec,
                            sandboxContext.getSnapshotSpec(),
                            sandboxContext.getClientOptions());
            carryOverPersistedSnapshotId(
                    sandbox, persistedSnapshotId, sandboxContext.getSnapshotSpec());
            return SandboxAcquireResult.selfManaged(sandbox, lease);

        } catch (Exception e) {
            // 获取失败时必须释放保护器，因为调用方不会收到结果
            lease.close();
            throw e;
        }
    }

    /**
     * 在恢复失败后回退新建时保持工作区连续性：让新沙箱指向此作用域持久化的快照 ID，使 {@link Sandbox#start()} 从旧归档恢复工作区，而非悄然重置；{@link Sandbox#stop()} 也会覆盖同一归档，而不留下孤立归档。快照根据当前规范重建，因此无论持久化状态如何反序列化，都携带已绑定客户端。
     */
    private static void carryOverPersistedSnapshotId(
            Sandbox sandbox, String persistedSnapshotId, SandboxSnapshotSpec snapshotSpec) {
        SandboxState state = sandbox.getState();
        if (persistedSnapshotId == null
                || persistedSnapshotId.isBlank()
                || snapshotSpec == null
                || state == null
                || state.getSnapshot() == null
                || persistedSnapshotId.equals(state.getSnapshot().getId())) {
            return;
        }
        log.info(
                "[sandbox] Resume fell back to fresh create; carrying over snapshot id {} (fresh"
                        + " id was {})",
                persistedSnapshotId,
                state.getSnapshot().getId());
        state.setSnapshot(snapshotSpec.build(persistedSnapshotId));
    }

    public void release(SandboxAcquireResult result) {
        if (result == null) {
            return;
        }
        Sandbox sandbox = result.getSandbox();
        if (sandbox == null) {
            return;
        }

        // 用户管理的沙箱（优先级 1）由调用方拥有，Harness 不得
        // 停止、生成快照或关闭它们，因为调用方依赖沙箱跨越
        // 多次获取／释放周期持续存活（例如注册表为每个用户复用同一容器，
        // 供浏览器路径和后续智能体轮次使用）。
        if (!result.isSelfManaged()) {
            return;
        }

        try {
            sandbox.stop();
        } catch (Exception e) {
            log.warn("[sandbox] Sandbox stop failed: {}", e.getMessage(), e);
        }

        try {
            sandbox.shutdown();
        } catch (Exception e) {
            log.warn("[sandbox] Sandbox shutdown failed: {}", e.getMessage(), e);
        }
    }

    public void persistState(
            SandboxAcquireResult result,
            SandboxContext sandboxContext,
            RuntimeContext runtimeContext) {
        if (result == null || result.getSandbox() == null) {
            return;
        }
        // 用户管理的沙箱自行负责持久化（注册表拥有
        // 生命周期）。通过 Harness 状态存储写入会重复跟踪状态，
        // 并可能与调用方的快照策略冲突。
        if (!result.isSelfManaged()) {
            return;
        }
        SandboxState state = result.getSandbox().getState();
        if (state == null) {
            return;
        }

        Optional<SandboxIsolationKey> scopeKey =
                SandboxIsolationKey.resolve(
                        sandboxContext != null ? sandboxContext.getIsolationScope() : null,
                        runtimeContext,
                        agentId);
        if (scopeKey.isEmpty()) {
            log.debug("[sandbox] No scope key available, skipping state persistence");
            return;
        }

        try {
            String json = client.serializeState(state);
            stateStore.save(scopeKey.get(), json);
            log.debug(
                    "[sandbox] Persisted sandbox state for scope {}: sessionId={}",
                    scopeKey.get(),
                    state.getSessionId());
        } catch (Exception e) {
            log.warn("[sandbox] Failed to persist sandbox state: {}", e.getMessage(), e);
        }
    }

    public void clearState(SandboxContext sandboxContext, RuntimeContext runtimeContext) {
        Optional<SandboxIsolationKey> scopeKey =
                SandboxIsolationKey.resolve(
                        sandboxContext != null ? sandboxContext.getIsolationScope() : null,
                        runtimeContext,
                        agentId);
        if (scopeKey.isEmpty()) {
            return;
        }

        try {
            stateStore.delete(scopeKey.get());
        } catch (Exception e) {
            log.warn("[sandbox] Failed to clear sandbox state: {}", e.getMessage(), e);
        }
    }
}
