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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 从 {@link SandboxManager} 获取 {@link Sandbox} 的结果。
 *
 * <p>两种所有权模式：
 * <ul>
 *   <li><b>自行管理</b>（{@code selfManaged=true}）：SDK 创建沙箱并负责完整生命周期，每次智能体调用后都会调用 {@code stop()} 和 {@code shutdown()}。
 *   <li><b>用户管理</b>（{@code selfManaged=false}）：调用方注入已有沙箱。上游旧说明称 SDK 只调用 {@code stop()}，不调用 {@code shutdown()}。
 * </ul>
 *
 * <p>【学习提示】用户管理模式应以 SandboxManager.release 当前实现为准：它直接返回，连 stop/snapshot 也不执行，避免干扰外部所有者的生命周期与持久化策略。
 *
 * <p>配置 {@link SandboxExecutionGuard} 时，结果还携带在恢复／创建沙箱之前获取的 {@link SandboxLease}。
 * Harness 在 {@link SandboxManager#release} 完成后关闭租约，覆盖整个调用窗口。
 */
public final class SandboxAcquireResult {

    private final Sandbox sandbox;
    private final boolean selfManaged;
    private final SandboxLease lease;
    private final AtomicBoolean released = new AtomicBoolean();

    private SandboxAcquireResult(Sandbox sandbox, boolean selfManaged, SandboxLease lease) {
        this.sandbox = sandbox;
        this.selfManaged = selfManaged;
        this.lease = lease != null ? lease : SandboxLease.noop();
    }

    /**
 * 创建带保护租约的自行管理结果（SDK 拥有完整生命周期）。
 */
    public static SandboxAcquireResult selfManaged(Sandbox sandbox, SandboxLease lease) {
        return new SandboxAcquireResult(sandbox, true, lease);
    }

    /**
 * 创建无保护器的自行管理结果（SDK 拥有完整生命周期）。
 */
    public static SandboxAcquireResult selfManaged(Sandbox sandbox) {
        return new SandboxAcquireResult(sandbox, true, SandboxLease.noop());
    }

    /**
 * 创建用户管理结果（调用方拥有生命周期；当前管理器释放时不调用 stop）。
 */
    public static SandboxAcquireResult userManaged(Sandbox sandbox) {
        return new SandboxAcquireResult(sandbox, false, SandboxLease.noop());
    }

    public Sandbox getSandbox() {
        return sandbox;
    }

    /**
 * 在 RuntimeContext 副本间共享，防止已结束调用复用其沙箱。
 */
    public boolean isReleased() {
        return released.get();
    }

    /**
 * 仅认领一次清理责任，即使多个上下文副本持有此次获取结果也是如此。
 */
    public boolean beginRelease() {
        return released.compareAndSet(false, true);
    }

    /**
 * SDK 拥有完整沙箱生命周期时返回 {@code true}。
 */
    public boolean isSelfManaged() {
        return selfManaged;
    }

    /**
     * 返回本次调用持有的 {@link SandboxLease}；未配置保护器时返回空操作租约。Harness 在 {@link SandboxManager#release} 完成后关闭租约。
     */
    public SandboxLease getLease() {
        return lease;
    }
}
