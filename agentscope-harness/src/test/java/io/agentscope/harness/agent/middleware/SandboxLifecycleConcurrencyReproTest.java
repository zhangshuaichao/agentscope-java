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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Issue #2490 的并发沙箱绑定回归场景。
 *
 * <p>该问题最初发生在 {@link SandboxLifecycleMiddleware} 与 {@link SandboxBackedFilesystem} 对同一智能体使用单槽状态时：一个 {@code currentAcquireResult} 引用与一个 {@code volatile Sandbox} 字段。框架允许同一智能体上不同 {@code (userId, sessionId)} 并行调用，{@code AgentBase.serializeOnKey} 只串行化同会话，因此不同会话可能竞争共享槽。
 *
 * <p>测试驱动确定性交错 {@code A.acquire → B.acquire → A.use → A.release}，断言每次调用使用自己的沙箱，A 释放时仅关闭 A，而 B 仍能工作。原文所指当时 {@code main} 的单槽实现违反这些不变量；此测试用于保护逐调用绑定行为，并非声明当前版本测试结果。未使用 {@code sleep} 或时序猜测。
 */
class SandboxLifecycleConcurrencyReproTest {

    @Test
    void differentSessionsMustNotShareSandboxBinding() {
        SandboxBackedFilesystem proxy = new SandboxBackedFilesystem();

        RecordingSandbox sandboxA = new RecordingSandbox("s1");
        RecordingSandbox sandboxB = new RecordingSandbox("s2");
        RecordingLease leaseA = new RecordingLease();
        RecordingLease leaseB = new RecordingLease();

        Map<String, RecordingSandbox> sandboxBySession =
                new ConcurrentHashMap<>(Map.of("s1", sandboxA, "s2", sandboxB));
        Map<String, RecordingLease> leaseBySession =
                new ConcurrentHashMap<>(Map.of("s1", leaseA, "s2", leaseB));

        SandboxManager manager = fakeManager(sandboxBySession, leaseBySession);
        SandboxLifecycleMiddleware mw = new SandboxLifecycleMiddleware(manager, proxy);

        RuntimeContext ctxA = callContext("s1");
        RuntimeContext ctxB = callContext("s2");

        // 调用 A（会话 s1）为本轮获取沙箱。
        mw.acquireForCall(ctxA);

        // 调用 B（会话 s2）同时获取沙箱；serializeOnKey 不会限制
        // s1 与 s2 并行，因此同一智能体 bean 上两轮可以同时在途。
        mw.acquireForCall(ctxB);

        // 不变量 1：隔离；各调用必须在自己的沙箱执行。
        // 原单槽实现中 A 的执行会被路由到 B 的沙箱并返回 "s2"，触发失败。
        assertEquals(
                "s1",
                proxy.execute(ctxA, "whoami", null).output(),
                "call A must execute against its own sandbox (s1), not another session's");
        assertEquals(
                "s2",
                proxy.execute(ctxB, "whoami", null).output(),
                "call B must execute against its own sandbox (s2)");

        // B 仍运行时，A 完成本轮并释放。
        mw.releaseForCall(ctxA);

        // 不变量 2：释放只影响发起释放的调用。
        // 原单槽实现中 A 会误关闭 B 并泄漏 A，触发失败。
        assertTrue(sandboxA.stopped, "A's release must tear down A's own sandbox");
        assertTrue(leaseA.closed, "A's release must close A's own lease");
        assertFalse(sandboxB.stopped, "A's release must NOT tear down B's still-live sandbox");
        assertFalse(leaseB.closed, "A's release must NOT close B's lease");

        // 不变量 3：A 释放后 B 仍可工作。
        // 原单槽实现中 A 的释放清空共享槽，导致 B 看到
        // "No active sandbox" 并触发失败。
        assertEquals(
                "s2",
                proxy.execute(ctxB, "whoami", null).output(),
                "call B must still run against its own sandbox after A released");
        assertThrows(
                SandboxException.SandboxConfigurationException.class,
                () -> proxy.execute(ctxA, "whoami", null),
                "released A must not fall back to the still-running B sandbox");
    }

    private static RuntimeContext callContext(String sessionId) {
        SandboxContext sandboxContext = SandboxContext.builder().build();
        return RuntimeContext.builder()
                .sessionId(sessionId)
                .put(SandboxContext.class, sandboxContext)
                .build();
    }

    /** {@link SandboxManager} 测试替身：{@code acquire} 为每个会话返回不同假沙箱，{@code release} 记录请求的拆除操作。所有用到的方法均已覆写，因此不使用客户端和状态存储依赖。 */
    private static SandboxManager fakeManager(
            Map<String, RecordingSandbox> sandboxBySession,
            Map<String, RecordingLease> leaseBySession) {
        SandboxClient<?> client = mock(SandboxClient.class);
        SessionSandboxStateStore stateStore = mock(SessionSandboxStateStore.class);
        return new SandboxManager(client, stateStore, "repro-agent") {
            @Override
            public SandboxAcquireResult acquire(
                    SandboxContext sandboxContext, RuntimeContext runtimeContext) {
                String sid = runtimeContext.getSessionId();
                return SandboxAcquireResult.selfManaged(
                        sandboxBySession.get(sid), leaseBySession.get(sid));
            }

            @Override
            public void release(SandboxAcquireResult result) {
                if (result != null && result.getSandbox() instanceof RecordingSandbox rs) {
                    rs.stopped = true;
                }
            }

            @Override
            public void persistState(
                    SandboxAcquireResult result,
                    SandboxContext sandboxContext,
                    RuntimeContext runtimeContext) {
                // 复现场景中无操作
            }
        };
    }

    /** 最小 {@link Sandbox}：记录拆除操作，并从 exec 回显会话 ID。 */
    private static final class RecordingSandbox implements Sandbox {

        private final String sessionId;
        volatile boolean stopped;

        RecordingSandbox(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void start() {}

        @Override
        public void stop() {
            stopped = true;
        }

        @Override
        public void close() {
            stopped = true;
        }

        @Override
        public boolean isRunning() {
            return !stopped;
        }

        @Override
        public SandboxState getState() {
            return null;
        }

        @Override
        public ExecResult exec(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            return new ExecResult(0, sessionId, "", false);
        }

        @Override
        public InputStream persistWorkspace() {
            return InputStream.nullInputStream();
        }

        @Override
        public void hydrateWorkspace(InputStream archive) {}
    }

    /** 记录是否已关闭的 {@link SandboxLease}。 */
    private static final class RecordingLease implements SandboxLease {

        volatile boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }
}
