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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 真实 E2B 卷测试，仅设置 E2B_API_KEY 时运行。
// E2B_VOLUME_NAME 必须是账户已有卷名，无默认值；
// 未知名称会使平台返回含糊的 400。
// 各测试即使失败也终止自己创建的全部沙箱。
@EnabledIfEnvironmentVariable(named = "E2B_API_KEY", matches = ".+")
class E2bVolumeMountLiveTest {

    private static final Logger log = LoggerFactory.getLogger(E2bVolumeMountLiveTest.class);

    private static final String MOUNT_PATH = "/mnt/data";
    private static final int SANDBOX_TIMEOUT_SECONDS = 300;

    @Test
    void mountExistingVolumeIsVisibleInsideSandbox() throws Exception {
        String volume = volumeName();
        E2bSandboxClientOptions opt = baseOptions();
        E2bPlatformHttp platform = new E2bPlatformHttp(opt);
        E2bSandboxState state = newState();
        List<String> created = new java.util.ArrayList<>();
        try {
            createWithMounts(platform, state, opt, List.of(new E2bVolumeMount(volume, MOUNT_PATH)));
            created.add(state.getSandboxId());
            E2bEnvdProcessClient envd = new E2bEnvdProcessClient(opt);
            waitForEnvd(envd, state);

            ExecResult dir = envd.runShell(state, "/", "test -d " + MOUNT_PATH, 30);
            assertTrue(dir.ok(), "mount path should exist: " + dir);

            ExecResult df = envd.runShell(state, "/", "df " + MOUNT_PATH, 30);
            assertTrue(
                    df.ok() && df.stdout().contains(MOUNT_PATH),
                    "df should list the mount, got: " + df.stdout());

            ExecResult mounts = envd.runShell(state, "/", "cat /proc/mounts", 30);
            log.info("sandbox {} /proc/mounts:\n{}", state.getSandboxId(), mounts.stdout());
            assertTrue(
                    mounts.ok() && mounts.stdout().contains(MOUNT_PATH),
                    "/proc/mounts should contain " + MOUNT_PATH + ", got:\n" + mounts.stdout());
        } finally {
            killQuietly(platform, created);
        }
    }

    @Test
    void unknownVolumeNameSurfaces400WithBody() {
        E2bSandboxClientOptions opt = baseOptions();
        E2bPlatformHttp platform = new E2bPlatformHttp(opt);
        List<String> created = new java.util.ArrayList<>();
        try {
            SandboxException.SandboxRuntimeException e =
                    assertThrows(
                            SandboxException.SandboxRuntimeException.class,
                            () ->
                                    platform.createSandbox(
                                            "base",
                                            SANDBOX_TIMEOUT_SECONDS,
                                            List.of(
                                                    new E2bVolumeMount(
                                                            "no-such-volume-xyz-123", MOUNT_PATH)),
                                            null));
            log.info("unknown-volume error: {}", e.getMessage());
            assertTrue(e.getMessage().contains("400"), e.getMessage());
        } catch (Exception unexpected) {
            fail("unexpected checked exception: " + unexpected);
        } finally {
            killQuietly(platform, created);
        }
    }

    @Test
    void fileWrittenToVolumeSurvivesAcrossSandboxes() throws Exception {
        String volume = volumeName();
        String probeFile = MOUNT_PATH + "/live-probe-" + System.currentTimeMillis() + ".txt";
        String probeContent = "volume-persist-" + System.currentTimeMillis();
        E2bSandboxClientOptions opt = baseOptions();
        E2bPlatformHttp platform = new E2bPlatformHttp(opt);
        List<String> created = new java.util.ArrayList<>();
        try {
            // 沙箱 A：在卷上写标记文件。
            E2bSandboxState a = newState();
            createWithMounts(platform, a, opt, List.of(new E2bVolumeMount(volume, MOUNT_PATH)));
            created.add(a.getSandboxId());
            E2bEnvdProcessClient envd = new E2bEnvdProcessClient(opt);
            waitForEnvd(envd, a);
            ExecResult write =
                    envd.runShell(a, "/", "printf '%s' '" + probeContent + "' > " + probeFile, 60);
            assertTrue(write.ok(), "write to volume failed: " + write);
            platform.killSandbox(a.getSandboxId());
            log.info("sandbox A {} killed after write", a.getSandboxId());

            // 沙箱 B：使用同一卷，文件及内容必须仍在。
            E2bSandboxState b = newState();
            createWithMounts(platform, b, opt, List.of(new E2bVolumeMount(volume, MOUNT_PATH)));
            created.add(b.getSandboxId());
            waitForEnvd(envd, b);
            ExecResult read = envd.runShell(b, "/", "cat " + probeFile, 60);
            assertTrue(read.ok(), "read from volume failed: " + read);
            assertEquals(probeContent, read.stdout().trim());

            // 清理探测文件，保持共享卷整洁。
            ExecResult rm = envd.runShell(b, "/", "rm -f " + probeFile, 30);
            assertTrue(rm.ok(), "probe cleanup failed: " + rm);
        } finally {
            killQuietly(platform, created);
        }
    }

    @Test
    void workspaceOnVolumeEndToEndNoOpPersist() throws Exception {
        String volume = volumeName();
        String wsRoot = MOUNT_PATH + "/ws-live-" + System.currentTimeMillis();
        E2bSandboxClientOptions opt = baseOptions();
        opt.setWorkspaceRoot(wsRoot);
        opt.setVolumeMounts(List.of(new E2bVolumeMount(volume, MOUNT_PATH)));
        E2bPlatformHttp platform = new E2bPlatformHttp(opt);

        E2bSandboxState state = newState();
        WorkspaceSpec ws = new WorkspaceSpec();
        // 本地无操作根目录：entries 为空，WorkspaceSpecApplier 不操作本地文件。
        ws.setRoot(
                System.getProperty("java.io.tmpdir")
                        + "/e2b-live-noop-"
                        + System.currentTimeMillis());
        state.setWorkspaceSpec(ws);
        state.setWorkspaceRoot(wsRoot);
        state.setVolumeMounts(opt.getVolumeMounts());
        state.setWorkspaceOnVolume(true);

        List<String> created = new java.util.ArrayList<>();
        try {
            E2bSandbox sandbox = new E2bSandbox(state, opt);
            sandbox.start();
            created.add(state.getSandboxId());

            // 基于卷的工作区执行 TAR 持久化应无操作，返回空流。
            try (InputStream archive = sandbox.persistWorkspace()) {
                assertEquals(0, archive.readAllBytes().length, "persist should be a no-op");
            }

            // 标记文件位于卷上。
            E2bEnvdProcessClient envd = new E2bEnvdProcessClient(opt);
            ExecResult write = envd.runShell(state, wsRoot, "printf 'ws-live-ok' > marker.txt", 60);
            assertTrue(write.ok(), "write marker failed: " + write);
            sandbox.stop();

            // destroy 必须跳过 rm -rf，工作区目录仍应存在。
            invokeDoDestroyWorkspace(sandbox);
            ExecResult dir =
                    envd.runShell(
                            state,
                            "/",
                            "test -d " + wsRoot + " && cat " + wsRoot + "/marker.txt",
                            30);
            assertTrue(dir.ok(), "workspace should survive destroy, got: " + dir);
            assertEquals("ws-live-ok", dir.stdout().trim());

            platform.killSandbox(state.getSandboxId());
            log.info("sandbox {} killed", state.getSandboxId());

            // 在同一卷重建，标记仍在，无需恢复归档。
            E2bSandboxState resumed = newState();
            resumed.setWorkspaceSpec(ws);
            resumed.setWorkspaceRoot(wsRoot);
            resumed.setVolumeMounts(opt.getVolumeMounts());
            resumed.setWorkspaceOnVolume(true);
            E2bSandbox sandbox2 = new E2bSandbox(resumed, opt);
            sandbox2.start();
            created.add(resumed.getSandboxId());
            ExecResult read = envd.runShell(resumed, "/", "cat " + wsRoot + "/marker.txt", 60);
            assertTrue(read.ok(), "marker should survive sandbox recreation: " + read);
            assertEquals("ws-live-ok", read.stdout().trim());
            sandbox2.stop();

            // 显式从共享卷删除工作区目录以清理。
            ExecResult rm = envd.runShell(resumed, "/", "rm -rf " + wsRoot, 60);
            assertTrue(rm.ok(), "workspace cleanup failed: " + rm);
        } finally {
            killQuietly(platform, created);
        }
    }

    @Test
    void nativeSnapshotRestoreKeepsVolumeMounts() throws Exception {
        String volume = volumeName();
        Path snapDir = Files.createTempDirectory("e2b-live-snap-");
        String sessionId = UUID.randomUUID().toString();
        SandboxSnapshot snapshot = new LocalSnapshotSpec(snapDir.toString()).build(sessionId);

        E2bSandboxClientOptions opt = baseOptions();
        opt.setPersistenceMode(E2bPersistenceMode.NATIVE_SNAPSHOT);
        opt.setWorkspaceRoot("/home/user");
        opt.setVolumeMounts(List.of(new E2bVolumeMount(volume, MOUNT_PATH)));
        E2bPlatformHttp platform = new E2bPlatformHttp(opt);

        long ts = System.currentTimeMillis();
        String volFile = MOUNT_PATH + "/snap-probe-" + ts + ".txt";
        String volContent = "snap-vol-" + ts;
        String wsFile = "snap-ws-" + ts + ".txt";
        String wsContent = "snap-ws-" + ts;

        List<String> created = new java.util.ArrayList<>();
        List<String> snapshotIds = new java.util.ArrayList<>();
        try {
            // 沙箱 1：工作区文件走快照路径，卷文件走卷路径。
            E2bSandboxState s1 = newState();
            s1.setSessionId(sessionId);
            s1.setWorkspaceSpec(emptyLocalSpec("snap1"));
            s1.setWorkspaceRoot("/home/user");
            s1.setVolumeMounts(opt.getVolumeMounts());
            s1.setWorkspaceOnVolume(false);
            s1.setPersistenceMode(E2bPersistenceMode.NATIVE_SNAPSHOT);
            s1.setSnapshot(snapshot);
            E2bSandbox sb1 = new E2bSandbox(s1, opt);
            sb1.start();
            created.add(s1.getSandboxId());
            E2bEnvdProcessClient envd = new E2bEnvdProcessClient(opt);
            waitForEnvd(envd, s1);
            assertTrue(
                    envd.runShell(
                                    s1,
                                    "/home/user",
                                    "printf '%s' '" + wsContent + "' > " + wsFile,
                                    60)
                            .ok(),
                    "write workspace file failed");
            assertTrue(
                    envd.runShell(s1, "/", "printf '%s' '" + volContent + "' > " + volFile, 60)
                            .ok(),
                    "write volume file failed");
            sb1.stop(); // NATIVE_SNAPSHOT 持久化将标记存入 snapDir
            assertFalse(s1.getSnapshotIds().isEmpty(), "stop should record an E2B snapshot id");
            snapshotIds.addAll(s1.getSnapshotIds());
            platform.killSandbox(s1.getSandboxId());
            log.info("sandbox 1 {} killed after snapshot", s1.getSandboxId());

            // 沙箱 2：全新状态，分支 C 从快照标记恢复。
            E2bSandboxState s2 = newState();
            s2.setSessionId(sessionId);
            s2.setWorkspaceSpec(emptyLocalSpec("snap2"));
            s2.setWorkspaceRoot("/home/user");
            s2.setVolumeMounts(opt.getVolumeMounts());
            s2.setWorkspaceOnVolume(false);
            s2.setPersistenceMode(E2bPersistenceMode.NATIVE_SNAPSHOT);
            s2.setSnapshot(snapshot);
            s2.setWorkspaceRootReady(false);
            E2bSandbox sb2 = new E2bSandbox(s2, opt);
            sb2.start(); // hydrate 调用 restoreSandboxFromSnapshotTemplate，重新挂载卷
            created.add(s2.getSandboxId());
            assertNotEquals(s1.getSandboxId(), s2.getSandboxId(), "restore must create a sandbox");

            // 快照恢复后重新附加卷挂载。
            ExecResult mounts = envd.runShell(s2, "/", "cat /proc/mounts", 30);
            assertTrue(
                    mounts.ok() && mounts.stdout().contains(MOUNT_PATH),
                    "/proc/mounts should contain "
                            + MOUNT_PATH
                            + " after restore, got:\n"
                            + mounts.stdout());

            // 卷数据保留，卷生命周期独立于快照。
            ExecResult volRead = envd.runShell(s2, "/", "cat " + volFile, 60);
            assertTrue(volRead.ok(), "volume file should survive snapshot restore: " + volRead);
            assertEquals(volContent, volRead.stdout().trim());

            // 工作区文件从 E2B 原生快照恢复。
            ExecResult wsRead = envd.runShell(s2, "/home/user", "cat " + wsFile, 60);
            assertTrue(wsRead.ok(), "workspace file should come back via snapshot: " + wsRead);
            assertEquals(wsContent, wsRead.stdout().trim());

            // 清理共享卷上的探测文件。
            assertTrue(envd.runShell(s2, "/", "rm -f " + volFile, 30).ok(), "probe cleanup failed");
            sb2.stop(); // 再记录一个 E2B 快照 ID
            snapshotIds.addAll(s2.getSnapshotIds());
        } finally {
            killQuietly(platform, created);
            deleteSnapshotsQuietly(platform, snapshotIds);
            deleteRecursivelyQuietly(snapDir);
        }
    }

    @Test
    void nestedMountContentSurvivesNativeSnapshotRestoreUnchanged() throws Exception {
        String volume = volumeName();
        // 挂载嵌套在工作区根目录内部：工作区本身参与快照，
        // 挂载卷必须保持独立。
        String nestedMount = "/home/user/voldata";
        Path snapDir = Files.createTempDirectory("e2b-live-nested-");
        String sessionId = UUID.randomUUID().toString();
        SandboxSnapshot snapshot = new LocalSnapshotSpec(snapDir.toString()).build(sessionId);

        E2bSandboxClientOptions opt = baseOptions();
        opt.setPersistenceMode(E2bPersistenceMode.NATIVE_SNAPSHOT);
        opt.setWorkspaceRoot("/home/user");
        opt.setVolumeMounts(List.of(new E2bVolumeMount(volume, nestedMount)));
        E2bPlatformHttp platform = new E2bPlatformHttp(opt);

        long ts = System.currentTimeMillis();
        String volFile = nestedMount + "/nested-probe-" + ts + ".txt";

        List<String> created = new java.util.ArrayList<>();
        List<String> snapshotIds = new java.util.ArrayList<>();
        try {
            E2bSandboxState s1 = newState();
            s1.setSessionId(sessionId);
            s1.setWorkspaceSpec(emptyLocalSpec("nested1"));
            s1.setWorkspaceRoot("/home/user");
            s1.setVolumeMounts(opt.getVolumeMounts());
            s1.setWorkspaceOnVolume(false);
            s1.setPersistenceMode(E2bPersistenceMode.NATIVE_SNAPSHOT);
            s1.setSnapshot(snapshot);
            E2bSandbox sb1 = new E2bSandbox(s1, opt);
            sb1.start();
            created.add(s1.getSandboxId());
            E2bEnvdProcessClient envd = new E2bEnvdProcessClient(opt);
            waitForEnvd(envd, s1);

            // 卷上写 v1，然后创建快照。
            assertTrue(
                    envd.runShell(s1, "/", "printf 'nested-v1' > " + volFile, 60).ok(),
                    "write v1 failed");
            sb1.stop();
            assertFalse(s1.getSnapshotIds().isEmpty(), "stop should record an E2B snapshot id");
            snapshotIds.addAll(s1.getSnapshotIds());

            // 快照后修改卷，沙箱仍存活，因为 stop 不等于 kill。
            assertTrue(
                    envd.runShell(s1, "/", "printf 'nested-v2' > " + volFile, 60).ok(),
                    "write v2 failed");
            platform.killSandbox(s1.getSandboxId());

            // 从快照恢复时，卷应显示快照之后的 v2。
            // 若此处为 v1，则说明平台快照捕获了卷数据，
            // 并在恢复时错误回滚卷。
            E2bSandboxState s2 = newState();
            s2.setSessionId(sessionId);
            s2.setWorkspaceSpec(emptyLocalSpec("nested2"));
            s2.setWorkspaceRoot("/home/user");
            s2.setVolumeMounts(opt.getVolumeMounts());
            s2.setWorkspaceOnVolume(false);
            s2.setPersistenceMode(E2bPersistenceMode.NATIVE_SNAPSHOT);
            s2.setSnapshot(snapshot);
            s2.setWorkspaceRootReady(false);
            E2bSandbox sb2 = new E2bSandbox(s2, opt);
            sb2.start();
            created.add(s2.getSandboxId());

            ExecResult mounts = envd.runShell(s2, "/", "cat /proc/mounts", 30);
            assertTrue(
                    mounts.ok() && mounts.stdout().contains(nestedMount),
                    "/proc/mounts should contain " + nestedMount + " after restore");
            ExecResult volRead = envd.runShell(s2, "/", "cat " + volFile, 60);
            assertTrue(volRead.ok(), "volume file should be readable after restore: " + volRead);
            assertEquals(
                    "nested-v2",
                    volRead.stdout().trim(),
                    "volume content must NOT be rolled back by a native snapshot restore");

            assertTrue(envd.runShell(s2, "/", "rm -f " + volFile, 30).ok(), "probe cleanup failed");
            sb2.stop();
            snapshotIds.addAll(s2.getSnapshotIds());
        } finally {
            killQuietly(platform, created);
            deleteSnapshotsQuietly(platform, snapshotIds);
            deleteRecursivelyQuietly(snapDir);
        }
    }

    private static String volumeName() {
        String v = System.getenv("E2B_VOLUME_NAME");
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "E2B_VOLUME_NAME must be set to an existing E2B volume name for live tests");
        }
        return v;
    }

    private static E2bSandboxClientOptions baseOptions() {
        E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
        opt.setApiKey(System.getenv("E2B_API_KEY"));
        opt.setTemplateId("base");
        opt.setConnectTimeoutSeconds(30);
        opt.setReadTimeoutSeconds(180);
        opt.setMaxRetries(2);
        return opt;
    }

    private static E2bSandboxState newState() {
        E2bSandboxState state = new E2bSandboxState();
        state.setTemplateId("base");
        return state;
    }

    private static void createWithMounts(
            E2bPlatformHttp platform,
            E2bSandboxState state,
            E2bSandboxClientOptions opt,
            List<E2bVolumeMount> mounts)
            throws Exception {
        JsonNode node = platform.createSandbox("base", SANDBOX_TIMEOUT_SECONDS, mounts, null);
        platform.applySandboxFields(state, node);
        state.setSandboxDomain(opt.getDomain());
        log.info("created sandbox {}", state.getSandboxId());
    }

    private static void waitForEnvd(E2bEnvdProcessClient envd, E2bSandboxState state)
            throws Exception {
        long deadline = System.currentTimeMillis() + 90_000;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                ExecResult r = envd.runShell(state, "/", "echo ready", 30);
                if (r.ok() && r.stdout().contains("ready")) {
                    log.info("envd ready on {}", state.getSandboxId());
                    return;
                }
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(3000);
        }
        throw new IllegalStateException(
                "envd not ready on " + state.getSandboxId() + " after 90s", last);
    }

    private static void invokeDoDestroyWorkspace(E2bSandbox sandbox) throws Exception {
        Method m = E2bSandbox.class.getDeclaredMethod("doDestroyWorkspace");
        m.setAccessible(true);
        try {
            m.invoke(sandbox);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static void killQuietly(E2bPlatformHttp platform, List<String> sandboxIds) {
        for (String id : sandboxIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            try {
                platform.killSandbox(id);
                log.info("sandbox {} killed", id);
            } catch (Exception e) {
                log.warn("failed to kill sandbox {}: {}", id, e.getMessage());
            }
        }
    }

    private static void deleteSnapshotsQuietly(E2bPlatformHttp platform, List<String> snapshotIds) {
        // 所有从模板恢复的沙箱终止后模板才解锁，
        // 因此在 killQuietly 之后执行。
        for (String id : snapshotIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            try {
                platform.deleteSnapshot(id);
                log.info("snapshot template {} deleted", id);
            } catch (Exception e) {
                log.warn("failed to delete snapshot {}: {}", id, e.getMessage());
            }
        }
    }

    private static WorkspaceSpec emptyLocalSpec(String tag) {
        // entries 保持为空，使 WorkspaceSpecApplier 不操作本机。
        WorkspaceSpec ws = new WorkspaceSpec();
        ws.setRoot(
                System.getProperty("java.io.tmpdir")
                        + "/e2b-live-"
                        + tag
                        + "-"
                        + System.currentTimeMillis());
        return ws;
    }

    private static void deleteRecursivelyQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (Exception ignored) {
                                    // 尽力清理测试资源。
                                }
                            });
        } catch (Exception ignored) {
            // 尽力清理测试资源。
        }
    }
}
