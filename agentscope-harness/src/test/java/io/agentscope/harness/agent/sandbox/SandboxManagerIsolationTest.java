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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SandboxManagerIsolationTest {

    private static final String AGENT_ID = "test-agent";
    private static final String STATE_JSON = "{\"sessionId\":\"s42\"}";

    @Mock SandboxClient<SandboxClientOptions> client;
    @Mock SessionSandboxStateStore stateStore;
    @Mock Sandbox freshSandbox;
    @Mock Sandbox resumedSandbox;
    @Mock Sandbox externalSandbox;
    @Mock SandboxState externalState;
    @Mock SandboxState resumedState;
    @Mock SandboxSnapshotSpec snapshotSpec;

    SandboxManager manager;

    @BeforeEach
    void setUp() {
        manager = new SandboxManager(client, stateStore, AGENT_ID);
    }

    // ---- 优先级 1：用户管理的外部会话 ----

    @Test
    void priority1_externalSession_usedDirectly() throws Exception {
        SandboxContext ctx = SandboxContext.builder().externalSandbox(externalSandbox).build();

        SandboxAcquireResult result = manager.acquire(ctx, null);

        assertSame(externalSandbox, result.getSandbox());
        assertEquals(false, result.isSelfManaged());
        verify(stateStore, never()).load(any());
    }

    // ---- 优先级 2：显式会话状态 ----

    @Test
    void priority2_externalSessionState_resumedDirectly() throws Exception {
        when(externalState.getSessionId()).thenReturn("explicit-id");
        when(client.resume(externalState)).thenReturn(resumedSandbox);

        SandboxContext ctx = SandboxContext.builder().externalSandboxState(externalState).build();

        SandboxAcquireResult result = manager.acquire(ctx, null);

        assertSame(resumedSandbox, result.getSandbox());
        assertEquals(true, result.isSelfManaged());
        verify(stateStore, never()).load(any());
    }

    // ---- 优先级 3：状态存储命中，会话作用域 ----

    @Test
    void priority3_stateStoreHit_resumesSession() throws Exception {
        when(stateStore.load(any())).thenReturn(Optional.of(STATE_JSON));
        when(client.deserializeState(STATE_JSON, null)).thenReturn(resumedState);
        when(client.resume(resumedState)).thenReturn(resumedSandbox);

        RuntimeContext rtx = RuntimeContext.builder().sessionId("sess-1").build();
        SandboxContext sCtx =
                SandboxContext.builder().isolationScope(IsolationScope.SESSION).build();

        SandboxAcquireResult result = manager.acquire(sCtx, rtx);

        assertSame(resumedSandbox, result.getSandbox());
        assertEquals(true, result.isSelfManaged());
        verify(client, never()).create(any(), any(), any());
    }

    // ---- 优先级 3：状态存储未命中，转优先级 4 新建 ----

    @Test
    void priority3_stateStoreMiss_createsFreshSession() throws Exception {
        when(stateStore.load(any())).thenReturn(Optional.empty());
        when(client.create(any(), any(), any())).thenReturn(freshSandbox);

        RuntimeContext rtx = RuntimeContext.builder().sessionId("sess-2").build();
        SandboxContext sCtx =
                SandboxContext.builder()
                        .isolationScope(IsolationScope.SESSION)
                        .snapshotSpec(snapshotSpec)
                        .build();

        SandboxAcquireResult result = manager.acquire(sCtx, rtx);

        assertSame(freshSandbox, result.getSandbox());
        verify(client).create(any(), any(), any());
    }

    // ---- 优先级 4：无会话键，作用域键为空，新建 ----

    @Test
    void noScopeKey_createsFreshSession() throws Exception {
        when(client.create(any(), any(), any())).thenReturn(freshSandbox);

        RuntimeContext rtx = RuntimeContext.builder().build(); // 没有 userId 或 sessionId。
        SandboxContext sCtx = SandboxContext.builder().build(); // scope 为 null，默认 USER。

        SandboxAcquireResult result = manager.acquire(sCtx, rtx);

        assertSame(freshSandbox, result.getSandbox());
        verify(stateStore, never()).load(any());
    }

    // ---- USER 作用域 ----

    @Test
    void userScope_withUserId_loadsFromStore() throws Exception {
        when(stateStore.load(any())).thenReturn(Optional.of(STATE_JSON));
        when(client.deserializeState(STATE_JSON, null)).thenReturn(resumedState);
        when(client.resume(resumedState)).thenReturn(resumedSandbox);

        RuntimeContext rtx = RuntimeContext.builder().userId("user-42").build();
        SandboxContext sCtx = SandboxContext.builder().isolationScope(IsolationScope.USER).build();

        SandboxAcquireResult result = manager.acquire(sCtx, rtx);

        assertSame(resumedSandbox, result.getSandbox());
    }

    @Test
    void priority3_stateStoreHit_passesSnapshotSpecToDeserialize() throws Exception {
        when(stateStore.load(any())).thenReturn(Optional.of(STATE_JSON));
        when(client.deserializeState(STATE_JSON, snapshotSpec)).thenReturn(resumedState);
        when(client.resume(resumedState)).thenReturn(resumedSandbox);

        RuntimeContext rtx = RuntimeContext.builder().sessionId("sess-1").build();
        SandboxContext sCtx =
                SandboxContext.builder()
                        .isolationScope(IsolationScope.SESSION)
                        .snapshotSpec(snapshotSpec)
                        .build();

        SandboxAcquireResult result = manager.acquire(sCtx, rtx);

        assertSame(resumedSandbox, result.getSandbox());
        verify(client).deserializeState(STATE_JSON, snapshotSpec);
    }

    // ---- 优先级 3 恢复失败，优先级 4 新建时保留已持久化快照 ID ----

    @Test
    void priority3_resumeFails_freshCreateCarriesOverPersistedSnapshotId() throws Exception {
        SandboxSnapshot persistedSnapshot = mock(SandboxSnapshot.class);
        SandboxSnapshot freshSnapshot = mock(SandboxSnapshot.class);
        SandboxSnapshot carriedOverSnapshot = mock(SandboxSnapshot.class);
        SandboxState freshState = mock(SandboxState.class);
        when(persistedSnapshot.getId()).thenReturn("persisted-id");
        when(freshSnapshot.getId()).thenReturn("fresh-id");
        when(resumedState.getSnapshot()).thenReturn(persistedSnapshot);
        when(freshSandbox.getState()).thenReturn(freshState);
        when(freshState.getSnapshot()).thenReturn(freshSnapshot);
        when(snapshotSpec.build("persisted-id")).thenReturn(carriedOverSnapshot);

        SandboxAcquireResult result = acquireAfterFailedResume();

        assertSame(freshSandbox, result.getSandbox());
        verify(freshState).setSnapshot(carriedOverSnapshot);
    }

    @Test
    void priority3_resumeFails_noPersistedSnapshot_keepsFreshSnapshot() throws Exception {
        SandboxState freshState = mock(SandboxState.class);
        when(freshSandbox.getState()).thenReturn(freshState);

        SandboxAcquireResult result = acquireAfterFailedResume();

        assertSame(freshSandbox, result.getSandbox());
        verify(freshState, never()).setSnapshot(any());
        verify(snapshotSpec, never()).build(any());
    }

    /**
     * 会话作用域获取：持久化状态存在，但恢复失败（声明／Pod 已消失）。
     */
    private SandboxAcquireResult acquireAfterFailedResume() throws Exception {
        when(stateStore.load(any())).thenReturn(Optional.of(STATE_JSON));
        when(client.deserializeState(STATE_JSON, snapshotSpec)).thenReturn(resumedState);
        when(client.resume(resumedState)).thenThrow(new RuntimeException("claim gone"));
        when(client.create(any(), any(), any())).thenReturn(freshSandbox);

        RuntimeContext rtx = RuntimeContext.builder().sessionId("sess-1").build();
        SandboxContext sCtx =
                SandboxContext.builder()
                        .isolationScope(IsolationScope.SESSION)
                        .snapshotSpec(snapshotSpec)
                        .build();
        return manager.acquire(sCtx, rtx);
    }

    @Test
    void userScope_withUserId_acquiresExecutionGuardWithUserKey() throws Exception {
        AtomicReference<SandboxIsolationKey> capturedKey = new AtomicReference<>();
        SandboxExecutionGuard guard =
                key -> {
                    capturedKey.set(key);
                    return SandboxLease.noop();
                };
        manager = new SandboxManager(client, stateStore, AGENT_ID, guard);
        when(stateStore.load(any())).thenReturn(Optional.empty());
        when(client.create(any(), any(), any())).thenReturn(freshSandbox);

        RuntimeContext rtx = RuntimeContext.builder().userId("user-42").build();
        SandboxContext sCtx = SandboxContext.builder().isolationScope(IsolationScope.USER).build();

        SandboxAcquireResult result = manager.acquire(sCtx, rtx);

        assertSame(freshSandbox, result.getSandbox());
        assertEquals(IsolationScope.USER, capturedKey.get().getScope());
        assertEquals("user-42", capturedKey.get().getValue());
    }

    @Test
    void userScope_missingUserId_createsFreshSession() throws Exception {
        when(client.create(any(), any(), any())).thenReturn(freshSandbox);

        RuntimeContext rtx = RuntimeContext.builder().build(); // 没有 userId。
        SandboxContext sCtx = SandboxContext.builder().isolationScope(IsolationScope.USER).build();

        SandboxAcquireResult result = manager.acquire(sCtx, rtx);

        assertSame(freshSandbox, result.getSandbox());
        verify(stateStore, never()).load(any());
    }

    // ---- AGENT 作用域 ----

    @Test
    void agentScope_alwaysHasScopeKey() throws Exception {
        when(stateStore.load(any())).thenReturn(Optional.empty());
        when(client.create(any(), any(), any())).thenReturn(freshSandbox);

        SandboxContext sCtx = SandboxContext.builder().isolationScope(IsolationScope.AGENT).build();

        SandboxAcquireResult result = manager.acquire(sCtx, null);

        assertNotNull(result.getSandbox());
        verify(stateStore).load(any()); // 已解析作用域键并查询存储。
    }

    // ---- GLOBAL 作用域 ----

    @Test
    void globalScope_alwaysHasScopeKey() throws Exception {
        when(stateStore.load(any())).thenReturn(Optional.empty());
        when(client.create(any(), any(), any())).thenReturn(freshSandbox);

        SandboxContext sCtx =
                SandboxContext.builder().isolationScope(IsolationScope.GLOBAL).build();

        SandboxAcquireResult result = manager.acquire(sCtx, null);

        assertNotNull(result.getSandbox());
        verify(stateStore).load(any());
    }

    // ---- persistState ----

    @Test
    void persistState_savesJsonForResolvedScopeKey() throws Exception {
        SandboxState state = mock(SandboxState.class);
        when(state.getSessionId()).thenReturn("sid");
        Sandbox sandbox = mock(Sandbox.class);
        when(sandbox.getState()).thenReturn(state);
        when(client.serializeState(state)).thenReturn(STATE_JSON);
        SandboxAcquireResult result = SandboxAcquireResult.selfManaged(sandbox);

        RuntimeContext rtx = RuntimeContext.builder().sessionId("sess-p").build();
        SandboxContext sCtx =
                SandboxContext.builder().isolationScope(IsolationScope.SESSION).build();

        manager.persistState(result, sCtx, rtx);

        verify(stateStore).save(any(), any());
    }

    @Test
    void persistState_missingScopeKey_skipped() throws Exception {
        Sandbox sandbox = mock(Sandbox.class);
        when(sandbox.getState()).thenReturn(mock(SandboxState.class));
        SandboxAcquireResult result = SandboxAcquireResult.selfManaged(sandbox);

        RuntimeContext rtx = RuntimeContext.builder().build(); // 没有会话键。
        SandboxContext sCtx = SandboxContext.builder().build(); // 默认 SESSION 作用域。

        manager.persistState(result, sCtx, rtx);

        verify(stateStore, never()).save(any(), any());
    }

    // ---- clearState ----

    @Test
    void clearState_deletesFromStore() throws Exception {
        RuntimeContext rtx = RuntimeContext.builder().sessionId("sess-c").build();
        SandboxContext sCtx =
                SandboxContext.builder().isolationScope(IsolationScope.SESSION).build();

        manager.clearState(sCtx, rtx);

        verify(stateStore).delete(any());
    }
}
