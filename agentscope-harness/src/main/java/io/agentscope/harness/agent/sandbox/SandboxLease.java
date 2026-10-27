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

/**
 * 表示已持有沙箱隔离槽位执行权的句柄。
 *
 * <p>由 {@link SandboxExecutionGuard#tryEnter} 返回。无论调用成功还是失败，Harness 都在 {@link SandboxManager#release} 完成后自动关闭租约，因此实现无需操心清理顺序。
 *
 * <p>实现必须幂等：多次调用 {@link #close()} 必须安全。
 */
public interface SandboxLease extends AutoCloseable {

    /**
     * 释放此租约持有的执行权。
     *
     * <p>不得抛出异常。释放侧错误应由实现内部记录日志。
     */
    @Override
    void close();

    /**
     * 返回 {@link #close()} 不执行操作的空操作租约，供默认 {@link SandboxExecutionGuard#noop()} 实现使用。
     */
    static SandboxLease noop() {
        return NoopSandboxLease.INSTANCE;
    }

    /**
 * 单例空操作实现。
 */
    final class NoopSandboxLease implements SandboxLease {

        static final NoopSandboxLease INSTANCE = new NoopSandboxLease();

        private NoopSandboxLease() {}

        @Override
        public void close() {}
    }
}
