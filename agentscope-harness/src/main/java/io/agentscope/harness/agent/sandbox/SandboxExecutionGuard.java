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

import io.agentscope.harness.agent.IsolationScope;

/**
 * 沙箱执行槽位的可插拔并发保护器。
 *
 * <p>保护器控制给定 {@link SandboxIsolationKey} 允许的并发执行数量。默认 {@link #noop()} 不施加限制，以保持现有行为。
 *
 * <p>此扩展点主要适用于 {@link IsolationScope#USER}、{@link IsolationScope#AGENT} 和 {@link IsolationScope#GLOBAL} 作用域；多个并发调用方否则可能竞争同一持久状态槽位（后写入者胜出）。提供保护器可串行化这些调用方，无需修改外围基础设施。
 *
 * <p>实现可使用任何后端，例如 JVM 信号量、Redis {@code SET NX} 租约、ZooKeeper、数据库咨询锁等，但必须线程安全。
 *
 * <h2>用法</h2>
 * <pre>{@code
 * SandboxExecutionGuard guard = key -> {
 *     redisClient.set(key.toString(), token, SetArgs.Builder.nx().px(30_000));
 *     return () -> redisClient.eval(LUA_RELEASE_SCRIPT, key.toString(), token);
 * };
 *
 * HarnessAgent.builder()
 *     .filesystem(new DockerFilesystemSpec()
 *         .isolationScope(IsolationScope.AGENT)
 *         .executionGuard(guard))
 *     ...
 *     .build();
 * }</pre>
 *
 * <h2>生命周期</h2>
 * <p>Harness 在获取／恢复沙箱前调用 {@link #tryEnter}，并在 {@link SandboxManager#release} 完成后关闭返回的 {@link SandboxLease}，
 * 因此保护覆盖整个调用窗口：{@code acquire → start → (call) → stop → release → lease.close()}。
 *
 * <p>【学习提示】默认保护器不会串行化执行；隔离键只是定位状态槽位。生产级分布式租约还需处理获取失败、到期与续租，不能把上面的简化示例当作完整锁协议。
 */
@FunctionalInterface
public interface SandboxExecutionGuard {

    /**
     * 获取给定隔离键的执行权，阻塞直到槽位可用或调用线程被中断。
     *
     * <p>必须关闭返回的 {@link SandboxLease} 以释放槽位。Harness 自动处理，调用方无需显式关闭租约。
     *
     * @param key 标识待保护沙箱槽位的隔离键
     * @return 关闭时释放执行权的租约
     * @throws InterruptedException 等待槽位时被中断则抛出
     */
    SandboxLease tryEnter(SandboxIsolationKey key) throws InterruptedException;

    /**
     * 返回默认空操作保护器：始终立即允许执行，返回的 {@link SandboxLease} 也不执行操作。这是内置默认值，无需配置。
     */
    static SandboxExecutionGuard noop() {
        return NoopSandboxExecutionGuard.INSTANCE;
    }

    /**
 * 单例空操作实现。
 */
    final class NoopSandboxExecutionGuard implements SandboxExecutionGuard {

        static final NoopSandboxExecutionGuard INSTANCE = new NoopSandboxExecutionGuard();

        private NoopSandboxExecutionGuard() {}

        @Override
        public SandboxLease tryEnter(SandboxIsolationKey key) {
            return SandboxLease.noop();
        }
    }
}
