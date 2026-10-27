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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.AbstractBaseSandbox;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxFileTransfer;
import io.agentscope.harness.agent.sandbox.WorkspaceMountSupport;
import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在容器内运行命令的 Docker {@link io.agentscope.harness.agent.sandbox.Sandbox}。
 *
 * <p>通过 {@link ProcessBuilder} 使用 {@code docker} 命令行，无需依赖 docker-java 库。宿主机必须能访问 Docker 守护进程。
 *
 * <h2>容器生命周期</h2>
 * <ul>
 *   <li>{@link #start()}：容器不存在则创建并启动；已存在但停止则重启；已运行则复用。</li>
 *   <li>{@link #stop()}：持久化工作区快照（若已配置），容器继续运行。</li>
 *   <li>{@link #shutdown()}：若为自行管理，则停止并移除容器。</li>
 * </ul>
 *
 * <h2>工作区操作</h2>
 * <ul>
 *   <li>执行：{@code docker exec -w <root> <containerId> sh -c <command>}</li>
 *   <li>归档：{@code docker exec <containerId> tar -cf - -C <root> .}</li>
 *   <li>恢复：{@code docker exec -i <containerId> tar -xf - -C <root>}</li>
 *   <li>上传／下载（{@link SandboxFileTransfer}）：通过 {@code docker cp} 中转宿主临时文件，文件字节不经过 exec 参数或标准输出捕获上限。通过词法验证限定在工作区子树；上传文件权限为 0644，与归档恢复回退一致。不解析沙箱内符号链接，隔离边界是容器，而非该子树。</li>
 * </ul>
 *
 * <p>【学习提示】本类通过子进程和流执行阻塞 I/O；“沙箱”描述执行位置，并不意味着 Java 调用自动非阻塞。挂载、网络及额外 docker run 参数也会影响实际隔离边界。
 */
public class DockerSandbox extends AbstractBaseSandbox implements SandboxFileTransfer {

    private static final Logger log = LoggerFactory.getLogger(DockerSandbox.class);

    private static final int OUTPUT_TRUNCATE_BYTES = 512 * 1024; // 每个流 512 KB
    private static final int CONTAINER_START_TIMEOUT_SECONDS = 60;
    private static final int CONTAINER_STOP_TIMEOUT_SECONDS = 30;
    private static final int TAR_TIMEOUT_SECONDS = 120;

    private final DockerSandboxState dockerState;

    public DockerSandbox(DockerSandboxState state) {
        super(state);
        this.dockerState = state;
    }

    /**
     * 执行标准四分支工作区启动逻辑前，确保底层 Docker 容器正在运行。
     *
     * @throws Exception 容器无法启动时抛出
     */
    @Override
    public void start() throws Exception {
        doEnsureContainerRunning();
        super.start();
    }

    /**
     * 若容器为自行管理，则停止并移除它。
     *
     * @throws Exception 无法停止或移除容器时抛出
     */
    @Override
    public void shutdown() throws Exception {
        String containerId = dockerState.getContainerId();
        if (containerId == null || containerId.isBlank()) {
            return;
        }
        if (!dockerState.isContainerOwned()) {
            log.debug(
                    "[sandbox-docker] Skipping shutdown: container is user-managed: {}",
                    containerId);
            return;
        }
        try {
            runDockerCliBlocking(
                    CONTAINER_STOP_TIMEOUT_SECONDS * 2,
                    "docker",
                    "stop",
                    "--time=" + CONTAINER_STOP_TIMEOUT_SECONDS,
                    containerId);
            log.debug("[sandbox-docker] Container stopped: {}", containerId);
        } catch (Exception e) {
            log.warn(
                    "[sandbox-docker] Failed to stop container {}: {}",
                    containerId,
                    e.getMessage());
        }
        try {
            runDockerCliBlocking(30, "docker", "rm", "--force", containerId);
            log.debug("[sandbox-docker] Container removed: {}", containerId);
        } catch (Exception e) {
            log.warn(
                    "[sandbox-docker] Failed to remove container {}: {}",
                    containerId,
                    e.getMessage());
        }
    }

    @Override
    protected ExecResult doExec(RuntimeContext runtimeContext, String command, int timeoutSeconds)
            throws Exception {
        String containerId = dockerState.getContainerId();
        String workspaceRoot = dockerState.getWorkspaceRoot();

        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("exec");
        cmd.add("-w");
        cmd.add(workspaceRoot);
        cmd.add(containerId);
        cmd.add("sh");
        cmd.add("-c");
        cmd.add(command);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process process = pb.start();

        ExecutorService drainer =
                Executors.newFixedThreadPool(
                        2,
                        r -> {
                            Thread t =
                                    new Thread(
                                            r,
                                            "sandbox-docker-drain-" + dockerState.getSessionId());
                            t.setDaemon(true);
                            return t;
                        });

        Future<String> stdoutFuture =
                drainer.submit(() -> readStream(process.getInputStream(), OUTPUT_TRUNCATE_BYTES));
        Future<String> stderrFuture =
                drainer.submit(() -> readStream(process.getErrorStream(), OUTPUT_TRUNCATE_BYTES));
        drainer.shutdown();

        boolean exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            drainer.shutdownNow();
            throw new SandboxException.ExecTimeoutException(command, timeoutSeconds);
        }

        String stdout = stdoutFuture.get();
        String stderr = stderrFuture.get();
        int exitCode = process.exitValue();

        boolean truncated =
                stdout.length() >= OUTPUT_TRUNCATE_BYTES
                        || stderr.length() >= OUTPUT_TRUNCATE_BYTES;
        ExecResult result = new ExecResult(exitCode, stdout, stderr, truncated);
        if (!result.ok()) {
            throw new SandboxException.ExecException(exitCode, stdout, stderr);
        }
        return result;
    }

    @Override
    protected InputStream doPersistWorkspace() throws Exception {
        String containerId = dockerState.getContainerId();
        String workspaceRoot = dockerState.getWorkspaceRoot();

        List<String> tarCmd = new ArrayList<>();
        tarCmd.add("docker");
        tarCmd.add("exec");
        tarCmd.add(containerId);
        tarCmd.add("tar");
        tarCmd.addAll(
                WorkspaceMountSupport.tarExcludeArgsForBindMounts(getState().getWorkspaceSpec()));
        tarCmd.add("-cf");
        tarCmd.add("-");
        tarCmd.add("-C");
        tarCmd.add(workspaceRoot);
        tarCmd.add(".");
        ProcessBuilder pb = new ProcessBuilder(tarCmd);

        Process process = pb.start();

        ExecutorService stderrDrainer =
                Executors.newSingleThreadExecutor(
                        r -> {
                            Thread t =
                                    new Thread(
                                            r,
                                            "sandbox-docker-tar-stderr-"
                                                    + dockerState.getSessionId());
                            t.setDaemon(true);
                            return t;
                        });
        Future<String> stderrFuture =
                stderrDrainer.submit(
                        () -> readStream(process.getErrorStream(), OUTPUT_TRUNCATE_BYTES));
        stderrDrainer.shutdown();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        process.getInputStream().transferTo(buffer);

        boolean exited = process.waitFor(TAR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                    "docker tar command timed out for container: " + containerId);
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String stderr = stderrFuture.get();
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                    "docker tar command failed (exit=" + exitCode + "): " + stderr);
        }

        return new ByteArrayInputStream(buffer.toByteArray());
    }

    @Override
    protected void doHydrateWorkspace(InputStream archive) throws Exception {
        String containerId = dockerState.getContainerId();
        String workspaceRoot = dockerState.getWorkspaceRoot();

        // 确保容器内工作区目录存在
        runDockerCliBlocking(30, "docker", "exec", containerId, "mkdir", "-p", workspaceRoot);

        // 通过 docker exec 标准输入将 tar 归档传入容器
        ProcessBuilder pb =
                new ProcessBuilder(
                        "docker",
                        "exec",
                        "-i",
                        containerId,
                        "tar",
                        "-xf",
                        "-",
                        "-C",
                        workspaceRoot);

        Process process = pb.start();

        ExecutorService ioExecutor =
                Executors.newFixedThreadPool(
                        2,
                        r -> {
                            Thread t =
                                    new Thread(
                                            r,
                                            "sandbox-docker-hydrate-" + dockerState.getSessionId());
                            t.setDaemon(true);
                            return t;
                        });

        // 在后台向进程标准输入写入归档
        Future<?> writeFuture =
                ioExecutor.submit(
                        () -> {
                            try (OutputStream stdin = process.getOutputStream()) {
                                archive.transferTo(stdin);
                            } catch (IOException e) {
                                log.warn(
                                        "[sandbox-docker] Error writing archive to container stdin",
                                        e);
                            }
                            return null;
                        });

        Future<String> stderrFuture =
                ioExecutor.submit(
                        () -> readStream(process.getErrorStream(), OUTPUT_TRUNCATE_BYTES));
        ioExecutor.shutdown();

        writeFuture.get(TAR_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        boolean exited = process.waitFor(TAR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            ioExecutor.shutdownNow();
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                    "docker tar extract timed out for container: " + containerId);
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String stderr = stderrFuture.get();
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                    "docker tar extract failed (exit=" + exitCode + "): " + stderr);
        }
    }

    @Override
    protected void doSetupWorkspace() throws Exception {
        String containerId = dockerState.getContainerId();
        String workspaceRoot = dockerState.getWorkspaceRoot();
        runDockerCliBlocking(30, "docker", "exec", containerId, "mkdir", "-p", workspaceRoot);
    }

    @Override
    protected void doDestroyWorkspace() throws Exception {
        String containerId = dockerState.getContainerId();
        String workspaceRoot = dockerState.getWorkspaceRoot();
        if (containerId != null && !containerId.isBlank()) {
            try {
                runDockerCliBlocking(30, "docker", "exec", containerId, "rm", "-rf", workspaceRoot);
            } catch (Exception e) {
                log.warn(
                        "[sandbox-docker] Failed to destroy workspace {} in container {}: {}",
                        workspaceRoot,
                        containerId,
                        e.getMessage());
            }
        }
    }

    @Override
    protected String getWorkspaceRoot() {
        return dockerState.getWorkspaceRoot();
    }

    @Override
    public boolean supportsFileTransfer(String path) {
        try {
            requireTransferPath(path);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public void uploadFile(String path, byte[] content) throws Exception {
        if (content == null) {
            throw new IllegalArgumentException("File content must not be null");
        }
        String containerPath = requireTransferPath(path);
        Path temp = Files.createTempFile("agentscope-docker-upload-", ".bin");
        applyUploadFileMode(temp);
        try {
            Files.write(temp, content);
            int slash = containerPath.lastIndexOf('/');
            String parent = slash > 0 ? containerPath.substring(0, slash) : "/";
            runDockerCliBlocking(
                    30, "docker", "exec", dockerState.getContainerId(), "mkdir", "-p", parent);
            runDockerCliBlocking(
                    TAR_TIMEOUT_SECONDS,
                    "docker",
                    "cp",
                    temp.toString(),
                    dockerState.getContainerId() + ":" + containerPath);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public byte[] downloadFile(String path) throws Exception {
        String containerPath = requireTransferPath(path);
        Path temp = Files.createTempFile("agentscope-docker-download-", ".bin");
        try {
            runDockerCliBlocking(
                    TAR_TIMEOUT_SECONDS,
                    "docker",
                    "cp",
                    dockerState.getContainerId() + ":" + containerPath,
                    temp.toString());
            return Files.readAllBytes(temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private String requireTransferPath(String path) {
        if (dockerState.getContainerId() == null || dockerState.getContainerId().isBlank()) {
            throw new IllegalArgumentException("Docker container is unavailable");
        }
        String resolved = resolveContainerPath(path);
        String root = normalizeAbsolutePath(dockerState.getWorkspaceRoot());
        if (root == null
                || resolved.equals(root)
                || !resolved.startsWith("/".equals(root) ? "/" : root + "/")) {
            throw new IllegalArgumentException("Path is outside the sandbox workspace: " + path);
        }
        return resolved;
    }

    private String resolveContainerPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path must identify a file");
        }
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        String[] parts = normalized.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (((i > 0 || !normalized.startsWith("/")) && part.isEmpty())
                    || ".".equals(part)
                    || "..".equals(part)) {
                throw new IllegalArgumentException("Path contains traversal segments: " + path);
            }
        }
        if (path.startsWith("/") || normalized.startsWith("/")) {
            return normalizeAbsolutePath(normalized);
        }
        String root = normalizeAbsolutePath(dockerState.getWorkspaceRoot());
        if (root == null) {
            throw new IllegalArgumentException("Sandbox workspace root is unavailable");
        }
        return "/".equals(root) ? "/" + normalized : root + "/" + normalized;
    }

    private static String normalizeAbsolutePath(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 将上传临时文件权限设为 0644，使保留源权限的 {@code docker cp} 与归档回退的 tar 默认权限一致，而不是沿用 {@code createTempFile} 的 0600；容器以非 root 用户运行时仍须能读取上传文件。不支持 POSIX 权限的文件系统（如 Windows）跳过此步骤，因为没有权限模式可保留。
     */
    private static void applyUploadFileMode(Path temp) throws IOException {
        try {
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException e) {
            // 此文件系统没有 POSIX 权限，无需对齐。
        }
    }

    // -----------------------------------------------------------------
    // 容器管理
    // -----------------------------------------------------------------

    /**
     * 确保 Docker 容器正在运行。
     *
     * <p>优先顺序：
     * <ol>
     *   <li>容器存在且运行中：复用。</li>
     *   <li>容器存在但已停止：重新启动。</li>
     *   <li>容器缺失或未知：创建新容器，重置 {@code workspaceRootReady}，强制完整重新初始化工作区。</li>
     * </ol>
     */
    private void doEnsureContainerRunning() throws Exception {
        String containerId = dockerState.getContainerId();

        if (containerId != null && !containerId.isBlank()) {
            ContainerState state = inspectContainerState(containerId);
            if (state == ContainerState.RUNNING) {
                log.debug("[sandbox-docker] Container already running: {}", containerId);
                return;
            } else if (state == ContainerState.STOPPED) {
                log.debug("[sandbox-docker] Restarting stopped container: {}", containerId);
                runDockerCliBlocking(
                        CONTAINER_START_TIMEOUT_SECONDS, "docker", "start", containerId);
                return;
            }
            // 未找到容器，继续创建新容器
            log.warn("[sandbox-docker] Container {} not found, creating a new one", containerId);
            dockerState.setWorkspaceRootReady(false);
        }

        createAndStartContainer();
    }

    private void createAndStartContainer() throws Exception {
        String containerName = "agentscope-sandbox-" + dockerState.getSessionId();
        dockerState.setContainerName(containerName);

        List<String> cmd = buildDockerRunCommand(containerName);

        log.debug(
                "[sandbox-docker] Creating container: image={}, name={}",
                dockerState.getImage(),
                containerName);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process process = pb.start();

        ExecutorService drainer =
                Executors.newFixedThreadPool(
                        2,
                        r -> {
                            Thread t =
                                    new Thread(
                                            r,
                                            "sandbox-docker-create-" + dockerState.getSessionId());
                            t.setDaemon(true);
                            return t;
                        });

        Future<String> stdoutFuture =
                drainer.submit(() -> readStream(process.getInputStream(), 64 * 1024));
        Future<String> stderrFuture =
                drainer.submit(() -> readStream(process.getErrorStream(), 64 * 1024));
        drainer.shutdown();

        boolean exited = process.waitFor(CONTAINER_START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            drainer.shutdownNow();
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "docker run timed out for image: " + dockerState.getImage());
        }

        int exitCode = process.exitValue();
        String stdout = stdoutFuture.get().trim();
        String stderr = stderrFuture.get().trim();

        if (exitCode != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "docker run failed (exit=" + exitCode + "): " + stderr);
        }

        // 标准输出是新容器 ID
        String newContainerId = stdout.isBlank() ? null : stdout;
        if (newContainerId == null) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "docker run produced no container ID. stderr: " + stderr);
        }

        dockerState.setContainerId(newContainerId);
        log.info(
                "[sandbox-docker] Container started: id={}, name={}",
                newContainerId,
                containerName);
    }

    private List<String> buildDockerRunCommand(String containerName) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("-d");
        cmd.add("--name");
        cmd.add(containerName);

        // 清单中的环境变量
        Map<String, String> manifestEnv =
                getState().getWorkspaceSpec() != null
                        ? getState().getWorkspaceSpec().getEnvironment()
                        : null;
        if (manifestEnv != null) {
            for (Map.Entry<String, String> entry : manifestEnv.entrySet()) {
                cmd.add("-e");
                cmd.add(entry.getKey() + "=" + entry.getValue());
            }
        }

        // 内存限制
        if (dockerState.getMemorySizeBytes() != null) {
            cmd.add("--memory=" + dockerState.getMemorySizeBytes());
        }

        // CPU 限制
        if (dockerState.getCpuCount() != null) {
            cmd.add("--cpus=" + dockerState.getCpuCount());
        }

        // 暴露端口（宿主与容器使用相同端口号映射）
        if (dockerState.getExposedPorts() != null) {
            for (int port : dockerState.getExposedPorts()) {
                cmd.add("-p");
                cmd.add(port + ":" + port);
            }
        }

        String network = dockerState.getNetwork();
        cmd.add("--network=" + (network == null || network.isBlank() ? "none" : network));

        if (dockerState.getAdditionalRunArgs() != null) {
            cmd.addAll(dockerState.getAdditionalRunArgs());
        }

        if (getState().getWorkspaceSpec() != null) {
            for (Map.Entry<String, WorkspaceEntry> e :
                    getState().getWorkspaceSpec().getEntries().entrySet()) {
                if (e.getValue() instanceof BindMountEntry bm) {
                    String host = WorkspaceMountSupport.normalizedHostPath(bm.getHostPath());
                    if (host.isEmpty()) {
                        log.warn(
                                "[sandbox-docker] Skipping bind mount at key {}: blank hostPath",
                                e.getKey());
                        continue;
                    }
                    String containerPath =
                            WorkspaceMountSupport.containerMountPath(
                                    dockerState.getWorkspaceRoot(), e.getKey());
                    String mode = bm.isReadOnly() ? "ro" : "rw";
                    cmd.add("-v");
                    cmd.add(host + ":" + containerPath + ":" + mode);
                }
            }
        }

        cmd.add(dockerState.getImage());
        // 通过空闲 Shell 循环保持容器存活。安装 SIGTERM 陷阱，
        // 让 sleep 在后台运行并使用 `wait`，使 PID 1（sh）在 `docker stop` 时及时退出，
        // 而不是阻塞在前台 `sleep`，直到停止超时强制发送
        // SIGKILL。仅依赖 `sh` 与整数 `sleep`，因此兼容
        // 不支持 `sleep infinity` 的精简镜像（例如 BusyBox/Alpine）。
        cmd.add("sh");
        cmd.add("-c");
        cmd.add("trap 'exit 0' TERM; while :; do sleep 3600 & wait $!; done");

        return cmd;
    }

    /**
     * 检查容器，返回运行中、已停止或未知状态。
     */
    private ContainerState inspectContainerState(String containerId) {
        try {
            ProcessBuilder pb =
                    new ProcessBuilder(
                            "docker", "inspect", "-f", "{{.State.Running}}", containerId);
            Process process = pb.start();

            Future<String> stdoutFuture;
            ExecutorService drainer =
                    Executors.newSingleThreadExecutor(
                            r -> {
                                Thread t = new Thread(r, "sandbox-docker-inspect");
                                t.setDaemon(true);
                                return t;
                            });
            stdoutFuture = drainer.submit(() -> readStream(process.getInputStream(), 1024));
            drainer.shutdown();

            boolean exited = process.waitFor(10, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                return ContainerState.UNKNOWN;
            }
            if (process.exitValue() != 0) {
                return ContainerState.UNKNOWN;
            }
            String output = stdoutFuture.get().trim();
            return "true".equals(output) ? ContainerState.RUNNING : ContainerState.STOPPED;
        } catch (Exception e) {
            log.debug(
                    "[sandbox-docker] Failed to inspect container {}: {}",
                    containerId,
                    e.getMessage());
            return ContainerState.UNKNOWN;
        }
    }

    /**
     * 运行 Docker 命令行并阻塞至完成。
     *
     * @param timeoutSeconds 最大等待时间
     * @param command 命令及参数
     * @throws SandboxException.SandboxRuntimeException 命令失败或超时时抛出
     */
    // 为测试开放，使单元测试可拦截 docker 命令行往返
    // （上传／下载临时文件流程），无需真实 Docker 守护进程。
    void runDockerCliBlocking(int timeoutSeconds, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();

        ExecutorService drainer =
                Executors.newFixedThreadPool(
                        2,
                        r -> {
                            Thread t = new Thread(r, "sandbox-docker-cli");
                            t.setDaemon(true);
                            return t;
                        });
        Future<String> stderrFuture =
                drainer.submit(() -> readStream(process.getErrorStream(), 64 * 1024));
        drainer.submit(() -> readStream(process.getInputStream(), 64 * 1024));
        drainer.shutdown();

        boolean exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            drainer.shutdownNow();
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "docker command timed out: " + command[0] + " " + command[1]);
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String stderr = stderrFuture.get();
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "docker command failed (exit=" + exitCode + "): " + stderr);
        }
    }

    /**
     * 将 InputStream 读取为 String，在 {@code maxBytes} 处截断。
     */
    private static String readStream(InputStream in, int maxBytes) {
        try {
            byte[] buf = new byte[maxBytes];
            int total = 0;
            int read;
            while (total < maxBytes && (read = in.read(buf, total, maxBytes - total)) != -1) {
                total += read;
            }
            // 排空剩余字节，避免阻塞
            if (total == maxBytes) {
                //noinspection ResultOfMethodCallIgnored
                in.skip(Long.MAX_VALUE);
            }
            return new String(buf, 0, total, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
 * 由 {@code docker inspect} 确定的容器状态。
 */
    private enum ContainerState {
        RUNNING,
        STOPPED,
        UNKNOWN
    }
}
