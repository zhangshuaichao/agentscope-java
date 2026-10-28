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
package io.agentscope.extensions.sandbox.e2b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class E2bSandboxRecoveryTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void recreateClearsStaleWorkspaceProjectionState() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("connect failed"));
        server.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                "{\"sandboxID\":\"new-sandbox\","
                                        + "\"domain\":\"new.e2b.app\","
                                        + "\"envdAccessToken\":\"new-token\"}"));
        E2bSandboxState state = stateForResume();

        invokeEnsureSandbox(new E2bSandbox(state, options()));

        assertEquals("new-sandbox", state.getSandboxId());
        assertFalse(state.isWorkspaceRootReady());
        assertNull(state.getWorkspaceProjectionHash());
        RecordedRequest connect = server.takeRequest();
        RecordedRequest create = server.takeRequest();
        assertEquals("/sandboxes/old-sandbox/connect", connect.getPath());
        assertEquals("/sandboxes", create.getPath());
    }

    @Test
    void reconnectPreservesWorkspaceProjectionState() throws Exception {
        server.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"sandboxID\":\"old-sandbox\"}"));
        E2bSandboxState state = stateForResume();

        invokeEnsureSandbox(new E2bSandbox(state, options()));

        assertEquals("old-sandbox", state.getSandboxId());
        assertTrue(state.isWorkspaceRootReady());
        assertEquals("old-projection", state.getWorkspaceProjectionHash());
        assertEquals("/sandboxes/old-sandbox/connect", server.takeRequest().getPath());
    }

    @Test
    void startFallsBackToTemplateWhenNativeSnapshotIsMissing() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("connect failed"));
        server.enqueue(new MockResponse().setResponseCode(404).setBody("snapshot not found"));
        server.enqueue(sandboxResponse("fresh-sandbox"));
        server.enqueue(envdStartResponse());
        E2bSandboxState state = stateForResumeWithNativeSnapshot("snap-123");

        new E2bSandbox(state, startOptions()).start();

        assertEquals(4, server.getRequestCount());
        RecordedRequest connect = server.takeRequest();
        RecordedRequest snapshotCreate = server.takeRequest();
        RecordedRequest templateCreate = server.takeRequest();
        RecordedRequest setupWorkspace = server.takeRequest();
        assertEquals("/sandboxes/old-sandbox/connect", connect.getPath());
        assertEquals("/sandboxes", snapshotCreate.getPath());
        assertTrue(snapshotCreate.getBody().readUtf8().contains("snap-123"));
        assertEquals("/sandboxes", templateCreate.getPath());
        assertTrue(templateCreate.getBody().readUtf8().contains("\"base\""));
        assertEquals("/process.Process/Start", setupWorkspace.getPath());
        assertEquals("fresh-sandbox", state.getSandboxId());
        assertTrue(state.isWorkspaceRootReady());
    }

    @Test
    void startFailsOnSnapshotCreateServerErrorWithoutTemplateFallback() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("connect failed"));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("snapshot unavailable"));
        E2bSandboxState state = stateForResumeWithNativeSnapshot("snap-123");

        SandboxException.SandboxRuntimeException failure =
                assertThrows(
                        SandboxException.SandboxRuntimeException.class,
                        () -> new E2bSandbox(state, startOptions()).start());

        assertEquals(SandboxErrorCode.SNAPSHOT_RESTORE_ERROR, failure.getErrorCode());
        assertTrue(failure.getMessage().contains("snap-123"));
        assertNotNull(failure.getCause());
        assertEquals(2, server.getRequestCount());
        assertEquals("/sandboxes/old-sandbox/connect", server.takeRequest().getPath());
        assertEquals("/sandboxes", server.takeRequest().getPath());
        assertEquals("old-sandbox", state.getSandboxId());
        assertTrue(state.isWorkspaceRootReady());
        assertEquals("old-projection", state.getWorkspaceProjectionHash());
    }

    @Test
    void startFailsWhenSnapshotArchiveCannotBeRestoredWithoutTemplateFallback() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("connect failed"));
        E2bSandboxState state = stateForResumeWithNativeSnapshot("snap-123");
        state.setSnapshot(
                new FakeSnapshot(nativeArchive("snap-123"), true) {
                    @Override
                    public InputStream restore() throws Exception {
                        throw new IOException("snapshot store unreachable");
                    }
                });

        SandboxException.SandboxRuntimeException failure =
                assertThrows(
                        SandboxException.SandboxRuntimeException.class,
                        () -> new E2bSandbox(state, startOptions()).start());

        assertEquals(SandboxErrorCode.SNAPSHOT_RESTORE_ERROR, failure.getErrorCode());
        assertTrue(failure.getMessage().contains("fake-snapshot"));
        assertEquals(1, server.getRequestCount());
        assertEquals("/sandboxes/old-sandbox/connect", server.takeRequest().getPath());
        assertEquals("old-sandbox", state.getSandboxId());
        assertTrue(state.isWorkspaceRootReady());
        assertEquals("old-projection", state.getWorkspaceProjectionHash());
    }

    @Test
    void startFailsWhenSnapshotRestorabilityCheckFailsWithoutTemplateFallback() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("connect failed"));
        E2bSandboxState state = stateForResumeWithNativeSnapshot("snap-123");
        state.setSnapshot(
                new FakeSnapshot(nativeArchive("snap-123"), true) {
                    @Override
                    public boolean isRestorable() throws Exception {
                        throw new IOException("snapshot store unreachable");
                    }
                });

        SandboxException.SandboxRuntimeException failure =
                assertThrows(
                        SandboxException.SandboxRuntimeException.class,
                        () -> new E2bSandbox(state, startOptions()).start());

        assertEquals(SandboxErrorCode.SNAPSHOT_RESTORE_ERROR, failure.getErrorCode());
        assertTrue(failure.getMessage().contains("fake-snapshot"));
        assertEquals(1, server.getRequestCount());
        assertEquals("/sandboxes/old-sandbox/connect", server.takeRequest().getPath());
        assertEquals("old-sandbox", state.getSandboxId());
        assertTrue(state.isWorkspaceRootReady());
        assertEquals("old-projection", state.getWorkspaceProjectionHash());
    }

    @Test
    void startFailsWhenNativeSnapshotReferenceIsCorruptWithoutTemplateFallback() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("connect failed"));
        E2bSandboxState state = stateForResumeWithNativeSnapshot("snap-123");
        byte[] corruptArchive =
                (new String(E2bSnapshotRefs.MAGIC_PREFIX, StandardCharsets.UTF_8)
                                + "{\"snapshot_id\":\"\"}")
                        .getBytes(StandardCharsets.UTF_8);
        state.setSnapshot(new FakeSnapshot(corruptArchive, true));

        SandboxException.SandboxRuntimeException failure =
                assertThrows(
                        SandboxException.SandboxRuntimeException.class,
                        () -> new E2bSandbox(state, startOptions()).start());

        assertEquals(SandboxErrorCode.SNAPSHOT_RESTORE_ERROR, failure.getErrorCode());
        assertTrue(failure.getMessage().contains("fake-snapshot"));
        assertEquals(1, server.getRequestCount());
        assertEquals("/sandboxes/old-sandbox/connect", server.takeRequest().getPath());
        assertEquals("old-sandbox", state.getSandboxId());
        assertTrue(state.isWorkspaceRootReady());
        assertEquals("old-projection", state.getWorkspaceProjectionHash());
    }

    @Test
    void startFallsBackToTemplateWhenSnapshotIsConfirmedNotRestorable() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("connect failed"));
        server.enqueue(sandboxResponse("fresh-sandbox"));
        server.enqueue(envdStartResponse());
        E2bSandboxState state = stateForResumeWithNativeSnapshot("snap-123");
        state.setSnapshot(new FakeSnapshot(nativeArchive("snap-123"), false));

        new E2bSandbox(state, startOptions()).start();

        assertEquals(3, server.getRequestCount());
        assertEquals("/sandboxes/old-sandbox/connect", server.takeRequest().getPath());
        RecordedRequest templateCreate = server.takeRequest();
        assertEquals("/sandboxes", templateCreate.getPath());
        assertTrue(templateCreate.getBody().readUtf8().contains("\"base\""));
        RecordedRequest workspaceSetup = server.takeRequest();
        assertEquals("/process.Process/Start", workspaceSetup.getPath());
        assertTrue(envdCommand(workspaceSetup).contains("mkdir -p"));
        assertEquals("fresh-sandbox", state.getSandboxId());
    }

    @Test
    void missingSnapshotForcesColdStartWhenPreviousWorkspaceWasReady() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("snapshot not found"));
        server.enqueue(sandboxResponse("fresh-sandbox"));
        server.enqueue(envdStartResponse());
        E2bSandboxState state = stateWithNativeSnapshot("snap-123");
        state.setWorkspaceRootReady(true);
        state.setWorkspaceProjectionHash("old-projection");

        new E2bSandbox(state, startOptions()).start();

        assertEquals(3, server.getRequestCount());
        RecordedRequest snapshotCreate = server.takeRequest();
        assertEquals("/sandboxes", snapshotCreate.getPath());
        assertTrue(snapshotCreate.getBody().readUtf8().contains("snap-123"));
        assertEquals("/sandboxes", server.takeRequest().getPath());
        RecordedRequest workspaceSetup = server.takeRequest();
        assertEquals("/process.Process/Start", workspaceSetup.getPath());
        assertTrue(envdCommand(workspaceSetup).contains("mkdir -p"));
        assertEquals("fresh-sandbox", state.getSandboxId());
        assertTrue(state.isWorkspaceRootReady());
        assertNull(state.getWorkspaceProjectionHash());
    }

    /** TAR 路径没有原生快照可恢复，因此从模板创建的沙箱必须加载已存归档，即使持久状态仍将旧工作区标为就绪。跳过加载会从空工作区启动，并在下次停止时覆盖正确归档。 */
    @Test
    void tarResumeHydratesSnapshotWhenPreviousWorkspaceWasReady() throws Exception {
        server.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        if ("/sandboxes".equals(request.getPath())) {
                            return sandboxResponse("fresh-sandbox");
                        }
                        if (request.getPath().startsWith("/files")) {
                            return new MockResponse().setResponseCode(200);
                        }
                        return envdStartResponse();
                    }
                });
        E2bSandboxState state = stateWithTarSnapshot("tar-bytes".getBytes(StandardCharsets.UTF_8));
        state.setWorkspaceRootReady(true);
        state.setWorkspaceProjectionHash("old-projection");

        new E2bSandbox(state, startOptions()).start();

        List<RecordedRequest> requests = recordedRequests();
        assertFalse(requests.isEmpty(), "start() must create a sandbox");
        RecordedRequest templateCreate = requests.get(0);
        assertEquals("/sandboxes", templateCreate.getPath());
        assertTrue(templateCreate.getBody().readUtf8().contains("\"base\""));
        List<String> paths = requests.stream().map(RecordedRequest::getPath).toList();
        // 已恢复：归档上传到新沙箱并解包。
        assertTrue(paths.stream().anyMatch(p -> p.startsWith("/files?path=")), "paths: " + paths);
        List<String> commands =
                requests.stream()
                        .filter(r -> "/process.Process/Start".equals(r.getPath()))
                        .map(
                                r -> {
                                    try {
                                        return envdCommand(r);
                                    } catch (Exception e) {
                                        throw new IllegalStateException(e);
                                    }
                                })
                        .toList();
        assertTrue(
                commands.stream().anyMatch(c -> c.contains("mkdir -p")), "commands: " + commands);
        assertTrue(commands.stream().anyMatch(c -> c.contains("tar -xf")), "commands: " + commands);
        // 不应出现修复前的工作区保留探测，即不能进入分支 A。
        assertFalse(
                commands.stream().anyMatch(c -> c.contains("test -d")), "commands: " + commands);
        assertEquals("fresh-sandbox", state.getSandboxId());
        assertTrue(state.isWorkspaceRootReady());
    }

    @Test
    void startPropagatesConfigurationFailureWithoutRelabelingItAsSnapshotLoss() throws Exception {
        E2bSandboxState state = stateForResumeWithNativeSnapshot("snap-123");
        E2bSandboxClientOptions noApiKey = startOptions();
        noApiKey.setApiKey("  ");

        SandboxException.SandboxConfigurationException failure =
                assertThrows(
                        SandboxException.SandboxConfigurationException.class,
                        () -> new E2bSandbox(state, noApiKey).start());

        assertEquals(SandboxErrorCode.CONFIGURATION_ERROR, failure.getErrorCode());
        assertEquals(0, server.getRequestCount(), "a missing key fails before any HTTP call");
        assertEquals("old-sandbox", state.getSandboxId());
        assertTrue(state.isWorkspaceRootReady());
        assertEquals("old-projection", state.getWorkspaceProjectionHash());
    }

    @Test
    void startRestoresSnapshotAfterConnectFailureWithoutReopeningSnapshotStore() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("connect failed"));
        server.enqueue(sandboxResponse("restored-sandbox"));
        server.enqueue(envdStartResponse());
        E2bSandboxState state = stateForResumeWithNativeSnapshot("snap-123");
        FakeSnapshot snapshot = (FakeSnapshot) state.getSnapshot();

        new E2bSandbox(state, startOptions()).start();

        assertEquals(3, server.getRequestCount(), "snapshot restore must create one sandbox");
        RecordedRequest connect = server.takeRequest();
        RecordedRequest create = server.takeRequest();
        RecordedRequest setupWorkspace = server.takeRequest();
        assertEquals("/sandboxes/old-sandbox/connect", connect.getPath());
        assertEquals("/sandboxes", create.getPath());
        String createBody = create.getBody().readUtf8();
        assertTrue(createBody.contains("snap-123"), "create must target the snapshot id");
        assertFalse(createBody.contains("\"base\""), "must not create from the template");
        assertEquals("/process.Process/Start", setupWorkspace.getPath());
        assertEquals(1, snapshot.restoreCount(), "the peeked archive must be reused");
        assertEquals("restored-sandbox", state.getSandboxId());
        assertTrue(state.isWorkspaceRootReady());
    }

    /** 反向路径：连接的沙箱早于快照，不能短路；start 必须从快照重建并终止被替换沙箱。 */
    @Test
    void startRebuildsConnectedSandboxWhenSnapshotIsNewer() throws Exception {
        server.enqueue(sandboxResponse("old-sandbox"));
        server.enqueue(envdStartResponse());
        server.enqueue(sandboxResponse("rebuilt-sandbox"));
        server.enqueue(new MockResponse().setResponseCode(204));
        E2bSandboxState state = stateForResumeWithNativeSnapshot("snap-123");
        state.setWorkspaceRootReady(false);
        state.setWorkspaceProjectionHash(null);

        new E2bSandbox(state, startOptions()).start();

        assertEquals(4, server.getRequestCount(), "start must rebuild and kill the old sandbox");
        assertEquals("/sandboxes/old-sandbox/connect", server.takeRequest().getPath());
        assertEquals("/process.Process/Start", server.takeRequest().getPath());
        RecordedRequest rebuild = server.takeRequest();
        assertEquals("/sandboxes", rebuild.getPath());
        assertTrue(rebuild.getBody().readUtf8().contains("snap-123"));
        assertEquals("/sandboxes/old-sandbox", server.takeRequest().getPath());
        assertEquals("rebuilt-sandbox", state.getSandboxId());
        assertTrue(state.isWorkspaceRootReady());
    }

    /** 终止成功后清除失效沙箱 ID，使下一次恢复不必先做必然 404 的 connect 才创建替代实例。 */
    @Test
    void shutdownClearsSandboxIdAfterKill() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));
        E2bSandboxState state = stateForResume();

        new E2bSandbox(state, options()).shutdown();

        assertEquals("/sandboxes/old-sandbox", server.takeRequest().getPath());
        assertNull(state.getSandboxId(), "a killed sandbox must not leave a dead id behind");
    }

    /** 终止失败时沙箱可能仍存活，必须保留 ID，让下次恢复重试连接。 */
    @Test
    void shutdownKeepsSandboxIdWhenKillFails() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("kill failed"));
        E2bSandboxState state = stateForResume();

        try {
            new E2bSandbox(state, options()).shutdown();
        } catch (Exception expected) {
            // 终止失败向外传播，重点是 ID 保留。
        }

        assertEquals("/sandboxes/old-sandbox", server.takeRequest().getPath());
        assertEquals("old-sandbox", state.getSandboxId());
    }

    private static MockResponse sandboxResponse(String sandboxId) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                        "{\"sandboxID\":\""
                                + sandboxId
                                + "\",\"domain\":\"new.e2b.app\","
                                + "\"envdAccessToken\":\"new-token\"}");
    }

    /** 从记录的 envd {@code process.Process/Start} 请求提取 shell 脚本。 */
    private static String envdCommand(RecordedRequest request) throws Exception {
        Buffer body = request.getBody();
        body.readByte();
        long payloadLength = body.readInt() & 0xffffffffL;
        return body.readUtf8(payloadLength);
    }

    /** 收集服务端收到的请求。{@code start()} 同步发出全部请求，因此服务端计数已稳定，无需等待。 */
    private List<RecordedRequest> recordedRequests() throws Exception {
        List<RecordedRequest> requests = new ArrayList<>();
        for (int i = 0, count = server.getRequestCount(); i < count; i++) {
            requests.add(server.takeRequest(1, TimeUnit.SECONDS));
        }
        return requests;
    }

    private static MockResponse envdStartResponse() {
        byte[] payload = "{\"event\":{\"end\":{\"exitCode\":0}}}".getBytes(StandardCharsets.UTF_8);
        Buffer body =
                new Buffer()
                        .writeByte(0)
                        .writeInt(payload.length)
                        .write(payload)
                        .writeByte(2)
                        .writeInt(0);
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/connect+json")
                .setBody(body);
    }

    private E2bSandboxClientOptions options() {
        E2bSandboxClientOptions options = new E2bSandboxClientOptions();
        options.setApiBaseUrl(server.url("/").toString());
        options.setApiKey("test-key");
        options.setMaxRetries(1);
        return options;
    }

    private E2bSandboxClientOptions startOptions() {
        E2bSandboxClientOptions options = options();
        options.setCodec(E2bCodec.JSON);
        options.setEnvdSandboxUrl(server.url("/").toString());
        return options;
    }

    private static E2bSandboxState stateForResume() {
        E2bSandboxState state = new E2bSandboxState();
        state.setWorkspaceSpec(new WorkspaceSpec());
        state.setSandboxId("old-sandbox");
        state.setWorkspaceRootReady(true);
        state.setWorkspaceProjectionHash("old-projection");
        return state;
    }

    /** 可恢复会话：失效沙箱 ID 与可恢复原生快照配对。 */
    private static E2bSandboxState stateForResumeWithNativeSnapshot(String snapshotId) {
        E2bSandboxState state = stateForResume();
        state.setPersistenceMode(E2bPersistenceMode.NATIVE_SNAPSHOT);
        try {
            state.setSnapshot(new FakeSnapshot(nativeArchive(snapshotId), true));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return state;
    }

    /** 没有沙箱 ID、持有可恢复原生快照的新会话。 */
    private static E2bSandboxState stateWithNativeSnapshot(String snapshotId) {
        E2bSandboxState state = new E2bSandboxState();
        state.setWorkspaceSpec(new WorkspaceSpec());
        state.setSandboxId(null);
        state.setWorkspaceRootReady(false);
        state.setPersistenceMode(E2bPersistenceMode.NATIVE_SNAPSHOT);
        try {
            state.setSnapshot(new FakeSnapshot(nativeArchive(snapshotId), true));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return state;
    }

    /** 没有沙箱 ID、持久 TAR 归档可恢复的新会话。 */
    private static E2bSandboxState stateWithTarSnapshot(byte[] archive) {
        E2bSandboxState state = new E2bSandboxState();
        state.setWorkspaceSpec(new WorkspaceSpec());
        state.setSandboxId(null);
        state.setWorkspaceRootReady(false);
        state.setPersistenceMode(E2bPersistenceMode.TAR);
        state.setSnapshot(new FakeSnapshot(archive, true));
        return state;
    }

    private static byte[] nativeArchive(String snapshotId) throws Exception {
        return E2bSnapshotRefs.encodeSnapshotId(snapshotId);
    }

    private static void invokeEnsureSandbox(E2bSandbox sandbox) throws Exception {
        Method method = E2bSandbox.class.getDeclaredMethod("ensureSandbox");
        method.setAccessible(true);
        try {
            method.invoke(sandbox);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    /** 基于内存归档的最小 {@link SandboxSnapshot}。 */
    private static class FakeSnapshot implements SandboxSnapshot {

        private final byte[] archive;
        private final boolean restorable;
        private int restoreCount;

        FakeSnapshot(byte[] archive, boolean restorable) {
            this.archive = archive;
            this.restorable = restorable;
        }

        @Override
        public void persist(InputStream workspaceArchive) {
            throw new UnsupportedOperationException("read-only fixture");
        }

        @Override
        public InputStream restore() throws Exception {
            restoreCount++;
            return new ByteArrayInputStream(archive);
        }

        @Override
        public boolean isRestorable() throws Exception {
            return restorable;
        }

        @Override
        public String getId() {
            return "fake-snapshot";
        }

        @Override
        public String getType() {
            return "fake";
        }

        int restoreCount() {
            return restoreCount;
        }
    }
}
