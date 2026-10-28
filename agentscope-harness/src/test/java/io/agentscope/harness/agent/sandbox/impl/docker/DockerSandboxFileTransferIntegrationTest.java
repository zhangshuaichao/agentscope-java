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
package io.agentscope.harness.agent.sandbox.impl.docker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 针对真实 Docker 守护进程，测试基于 {@code docker cp} 文件传输的真实端到端往返。
 *
 * <p>测试创建短生命周期 alpine 容器，验证大于 1 MB 的二进制块经上传、下载后逐字节一致，以及空格、相对路径和绝对路径处理正确；需要可访问的 Docker 守护进程。
 *
 * <p>通过 {@code -Ddocker.it=true} 系统属性启用，使没有可用 Docker 环境的 CI 默认跳过这些测试：
 *
 * <pre>{@code
 * mvn -pl agentscope-harness -Ddocker.it=true -Dtest=DockerSandboxFileTransferIntegrationTest test
 * }</pre>
 */
@EnabledIfSystemProperty(named = "docker.it", matches = "true")
class DockerSandboxFileTransferIntegrationTest {

    /**
     * 镜像必须提供 {@code tar} 与可运行 shell；alpine 体积小且足够使用。
     */
    private static final String TEST_IMAGE = "alpine:latest";

    private DockerSandbox sandbox;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(dockerDaemonReachable(), "Docker daemon is not reachable");

        DockerSandboxState state = new DockerSandboxState();
        state.setSessionId("it-" + Long.toHexString(System.nanoTime()));
        state.setContainerId(""); // 为空，强制 start() 创建新容器。
        state.setWorkspaceRoot("/workspace");
        state.setImage(TEST_IMAGE);
        state.setContainerOwned(true);
        state.setWorkspaceSpec(new WorkspaceSpec());

        sandbox = new DockerSandbox(state);
        sandbox.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (sandbox != null) {
            sandbox.shutdown();
        }
    }

    @Test
    void uploadsAndDownloadsBinaryLargerThanOneMegabyteBitForBit() throws Exception {
        // 1.25 MiB 伪随机字节，不易压缩，用于测试原始字节往返。
        byte[] payload = new byte[1_250_000];
        new Random(42L).nextBytes(payload);

        String path = "/workspace/blob/roundtrip.bin";
        assertTrue(sandbox.supportsFileTransfer(path));

        sandbox.uploadFile(path, payload);
        byte[] downloaded = sandbox.downloadFile(path);

        assertArrayEquals(payload, downloaded, "uploaded and downloaded bytes must be identical");
    }

    @Test
    void relativePathCreatesNestedDirectoriesAndRoundTrips() throws Exception {
        byte[] payload = "relative-under-workspace".getBytes();

        // 相对路径：resolveContainerPath 添加工作区根目录前缀。
        String path = "deep/nested/folder/notes/report.txt";
        sandbox.uploadFile(path, payload);
        byte[] downloaded = sandbox.downloadFile(path);

        assertArrayEquals(payload, downloaded);
    }

    @Test
    void pathWithSpacesAndQuoteCharactersRoundTrips() throws Exception {
        byte[] payload = "space and quote handling".getBytes();

        String path = "/workspace/my report file/with \"quotes\" and spaces.bin";
        sandbox.uploadFile(path, payload);
        byte[] downloaded = sandbox.downloadFile(path);

        assertArrayEquals(payload, downloaded);
    }

    @Test
    void absoluteProjectedPathRoundTrips() throws Exception {
        byte[] payload = "absolute path payload".getBytes();

        String path = "/workspace/top-level.bin";
        sandbox.uploadFile(path, payload);
        byte[] downloaded = sandbox.downloadFile(path);

        assertArrayEquals(payload, downloaded);
    }

    @Test
    void uploadWithLeadingDotSlashIsAccepted() throws Exception {
        byte[] payload = "dot-slash prefix".getBytes();

        // resolveContainerPath 去掉前导 "./" 段。
        String path = "./relative/dot-file.txt";
        sandbox.uploadFile(path, payload);
        byte[] downloaded = sandbox.downloadFile(path);

        assertArrayEquals(payload, downloaded);
    }

    @Test
    void downloadOfMissingFileFailsWithSandboxRuntimeException() {
        // docker cp 源不存在时退出码非零，runDockerCliBlocking 抛异常。
        assertThrows(Exception.class, () -> sandbox.downloadFile("/workspace/does-not-exist.bin"));
    }

    @Test
    void transfersLeaveNoTempFilesBehind() throws Exception {
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        byte[] payload = "cleanup check".getBytes();

        // 成功上传、下载，以及失败下载（Docker 退出码非零），都必须
        // 通过 finally 块清理临时文件。
        sandbox.uploadFile("/workspace/cleanup/ok.bin", payload);
        sandbox.downloadFile("/workspace/cleanup/ok.bin");
        assertThrows(Exception.class, () -> sandbox.downloadFile("/workspace/cleanup/missing.bin"));

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tempDir)) {
            boolean leftover =
                    StreamSupport.stream(stream.spliterator(), false)
                            .anyMatch(
                                    p -> {
                                        String name = p.getFileName().toString();
                                        return name.startsWith("agentscope-docker-upload-")
                                                || name.startsWith("agentscope-docker-download-");
                                    });
            assertFalse(leftover, "docker cp scratch files must be removed");
        }
    }

    @Test
    void supportsFileTransferRejectsRootAndOutsideWorkspace() {
        assertFalse(sandbox.supportsFileTransfer("/workspace"));
        assertFalse(sandbox.supportsFileTransfer("/workspace/"));
        assertFalse(sandbox.supportsFileTransfer("/etc/passwd"));
        assertFalse(sandbox.supportsFileTransfer("/workspace/../etc/passwd"));
        assertTrue(sandbox.supportsFileTransfer("/workspace/a.txt"));
        assertTrue(sandbox.supportsFileTransfer("a.txt"));
    }

    @Test
    void workspaceRootSlashAllowsWholeFilesystem() throws Exception {
        // 第二个沙箱工作区根目录为 "/"，所有路径都在范围内。
        DockerSandbox other = null;
        try {
            DockerSandboxState rootState = new DockerSandboxState();
            rootState.setSessionId("it-root-" + Long.toHexString(System.nanoTime()));
            rootState.setContainerId("");
            rootState.setWorkspaceRoot("/");
            rootState.setImage(TEST_IMAGE);
            rootState.setContainerOwned(true);
            rootState.setWorkspaceSpec(new WorkspaceSpec());

            other = new DockerSandbox(rootState);
            other.start();

            String path = "/tmp/root-workspace-check.bin";
            assertTrue(other.supportsFileTransfer(path), "root workspace must accept /tmp path");
            byte[] payload = "root workspace".getBytes();
            other.uploadFile(path, payload);
            assertArrayEquals(payload, other.downloadFile(path));
        } finally {
            if (other != null) {
                other.shutdown();
            }
        }
    }

    /**
     * 当前测试进程能否访问真实 Docker 守护进程。
     */
    private static boolean dockerDaemonReachable() {
        try {
            Process process =
                    new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                            .inheritIO()
                            .redirectErrorStream(true)
                            .start();
            boolean exited = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            return exited && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
