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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 显式启用的 #3555 真实沙箱检查：仅环境设置 E2B_API_KEY
// 时运行，遵循仓库端到端测试约定。密钥从环境
// 读取，绝不记录日志。
@EnabledIfEnvironmentVariable(named = "E2B_API_KEY", matches = ".+")
class E2bNativeSnapshotRestoreIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(E2bNativeSnapshotRestoreIntegrationTest.class);

    /** 会话持久化 {@link E2bPersistenceMode#NATIVE_SNAPSHOT}，按 {@code SandboxManager.release} 的停止后关闭顺序释放，再恢复时必须保留工作区；真实服务还必须接受快照 ID 作为新沙箱模板。这是 #3555 修复依赖、mock 无法证明的前提。 */
    @Test
    void nativeSnapshotRestore_bringsWorkspaceBackFromSnapshot() throws Exception {
        Path snapshotDir = Files.createTempDirectory("e2b-native-restore-");
        E2bSandboxClientOptions opt = options();
        E2bSandboxClient client = new E2bSandboxClient(opt, null);
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.setRoot(opt.getWorkspaceRoot());
        String marker = opt.getWorkspaceRoot() + "/membership.txt";

        Sandbox first = null;
        Sandbox resumed = null;
        String firstId = null;
        String snapshotId = null;
        try {
            first = client.create(spec, new LocalSnapshotSpec(snapshotDir), null);
            first.start();
            firstId = ((E2bSandboxState) first.getState()).getSandboxId();
            assertNotNull(firstId, "create must yield a sandbox id");

            String expected = "agentscope-" + UUID.randomUUID();
            exec(first, "printf '%s' '" + expected + "' > " + marker);
            assertEquals(expected, exec(first, "cat " + marker));

            // 与 SandboxManager.release 相同：先持久化快照，再终止。
            first.stop();
            E2bSandboxState persisted = (E2bSandboxState) first.getState();
            assertEquals(1, persisted.getSnapshotIds().size(), "stop must record one snapshot");
            snapshotId = persisted.getSnapshotIds().get(0);
            first.shutdown();
            assertNull(persisted.getSandboxId(), "shutdown must clear the killed sandbox id");

            // 像下一次调用一样从序列化状态恢复。
            SandboxState restored = client.deserializeState(client.serializeState(persisted));
            resumed = client.resume(restored);
            resumed.start();

            String resumedId = ((E2bSandboxState) resumed.getState()).getSandboxId();
            assertNotNull(resumedId, "resume must create a replacement sandbox");
            assertNotEquals(firstId, resumedId, "the killed sandbox must not be reused");
            assertEquals(
                    expected,
                    exec(resumed, "cat " + marker),
                    "the workspace must come back from the native snapshot");

            // 被终止的沙箱应真实消失，而非仅从状态中删除。
            final String deadId = firstId;
            E2bPlatformHttp platform = new E2bPlatformHttp(opt);
            assertThrows(
                    Exception.class,
                    () -> platform.connectSandbox(deadId, opt.getSandboxTimeoutSeconds()),
                    "the replaced sandbox must be deleted");
        } finally {
            shutdownQuietly(resumed);
            shutdownQuietly(first);
            deleteSnapshotQuietly(opt, snapshotId);
            deleteRecursivelyQuietly(snapshotDir);
        }
    }

    private static E2bSandboxClientOptions options() {
        E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
        opt.setApiKey(System.getenv("E2B_API_KEY"));
        opt.setTemplateId(System.getProperty("E2B_TEMPLATE_ID", "base"));
        opt.setPersistenceMode(E2bPersistenceMode.NATIVE_SNAPSHOT);
        opt.setMaxRetries(1);
        opt.setReadTimeoutSeconds(180);
        return opt;
    }

    private static String exec(Sandbox sandbox, String command) throws Exception {
        ExecResult result = sandbox.exec(null, command, 120);
        assertTrue(
                result.ok(), () -> "command failed [" + command + "]: " + result.combinedOutput());
        return result.stdout() != null ? result.stdout() : "";
    }

    private static void shutdownQuietly(Sandbox sandbox) {
        if (sandbox == null) {
            return;
        }
        try {
            sandbox.shutdown();
        } catch (Exception e) {
            log.warn("cleanup: shutdown failed: {}", e.getMessage());
        }
    }

    private static void deleteSnapshotQuietly(E2bSandboxClientOptions opt, String snapshotId) {
        if (snapshotId == null) {
            return;
        }
        try {
            new E2bPlatformHttp(opt).deleteSnapshot(snapshotId);
        } catch (Exception e) {
            log.warn("cleanup: delete snapshot {} failed: {}", snapshotId, e.getMessage());
        }
    }

    private static void deleteRecursivelyQuietly(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (Exception ignored) {
                                    // 尽力清理快照目录
                                }
                            });
        } catch (Exception e) {
            log.warn("cleanup: remove {} failed: {}", dir, e.getMessage());
        }
    }
}
