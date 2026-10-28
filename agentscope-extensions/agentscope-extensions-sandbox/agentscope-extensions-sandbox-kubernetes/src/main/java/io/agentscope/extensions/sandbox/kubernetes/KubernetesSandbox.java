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
package io.agentscope.extensions.sandbox.kubernetes;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.sandbox.kubernetes.client.Sandbox;
import io.agentscope.extensions.sandbox.kubernetes.client.model.ExecutionResult;
import io.agentscope.harness.agent.sandbox.AbstractBaseSandbox;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxFileTransfer;
import io.agentscope.harness.agent.sandbox.WorkspaceMountSupport;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通过 Java 客户端 SDK 由 agent-sandbox CRD 支持的 Harness {@link io.agentscope.harness.agent.sandbox.Sandbox}。
 *
 * <p>假定运行时镜像符合 AgentScope 运行时契约（见沙箱文档）：{@code POST /execute} 用 POSIX shell 解释命令，文件 API（{@code /upload} / {@code /download}）以已知基础目录为根。
 *
 * <p>配置 {@code fileApiBaseDir} 时，工作区归档（持久化/恢复）通过运行时文件 API（{@code files().read()} / {@code files().write()}）传输；为空白时，回退为通过 {@code exec} 传输 base64 载荷。Harness 文件系统请求的单文件传输（{@link SandboxFileTransfer}），路径位于 {@code fileApiBaseDir} 下时也通过文件 API。
 *
 * <p>【学习提示】该适配器假定镜像遵守 shell 与文件 API 契约，但不会因此建立完整安全隔离；网络策略、RBAC、Pod 权限和租户边界仍属于集群部署责任。文件 API 路径不适用时回退到 exec 是传输选择，不是拒绝访问策略，不能把基础目录检查当作通用文件访问授权。
 */
public class KubernetesSandbox extends AbstractBaseSandbox implements SandboxFileTransfer {

    private static final Logger log = LoggerFactory.getLogger(KubernetesSandbox.class);

    /**
     * 临时归档的相对目录（位于运行时文件 API 基础目录下）。
     */
    private static final String TMP_DIR_REL = ".agentscope-tmp";

    private final KubernetesSandboxState k8sState;
    private final Sandbox sdkSandbox;

    public KubernetesSandbox(KubernetesSandboxState state, Sandbox sdkSandbox) {
        super(state);
        this.k8sState = state;
        this.sdkSandbox = sdkSandbox;
    }

    @Override
    public void shutdown() throws Exception {
        if (!k8sState.isClaimOwned()) {
            log.debug(
                    "[sandbox-k8s] Claim {} not owned, closing connection only",
                    k8sState.getClaimName());
            sdkSandbox.closeConnection();
            return;
        }
        try {
            sdkSandbox.terminate();
            log.debug(
                    "[sandbox-k8s] Terminated claim {} in {}",
                    k8sState.getClaimName(),
                    k8sState.getNamespace());
        } catch (Exception e) {
            log.warn(
                    "[sandbox-k8s] Failed to terminate claim {} in {}: {}",
                    k8sState.getClaimName(),
                    k8sState.getNamespace(),
                    e.getMessage());
        }
    }

    @Override
    protected ExecResult doExec(RuntimeContext runtimeContext, String command, int timeoutSeconds)
            throws Exception {
        requireActiveConnection();
        if (command == null || command.isBlank()) {
            throw new SandboxException.ExecException(
                    2, "", "empty command rejected by KubernetesSandbox");
        }
        String wrapped =
                "cd " + shellQuote(k8sState.getWorkspaceRoot()) + " && (\n" + command + "\n)";
        ExecutionResult result =
                sdkSandbox.commands().run(wrapped, Duration.ofSeconds(Math.max(timeoutSeconds, 1)));

        boolean truncated = false;
        String stdout = result.getStdout() != null ? result.getStdout() : "";
        String stderr = result.getStderr() != null ? result.getStderr() : "";

        ExecResult execResult = new ExecResult(result.getExitCode(), stdout, stderr, truncated);
        if (!execResult.ok()) {
            throw new SandboxException.ExecException(result.getExitCode(), stdout, stderr);
        }
        return execResult;
    }

    @Override
    protected InputStream doPersistWorkspace() throws Exception {
        requireActiveConnection();
        String root = k8sState.getWorkspaceRoot();
        StringBuilder tarArgs = new StringBuilder();
        // 文件 API 以工作区为根时，临时归档可能位于工作区内；
        // 绝不能让归档包含自身。
        tarArgs.append("--exclude=./").append(TMP_DIR_REL).append(' ');
        for (String ex :
                WorkspaceMountSupport.tarExcludeArgsForBindMounts(k8sState.getWorkspaceSpec())) {
            tarArgs.append(ex).append(' ');
        }

        if (fileApiEnabled()) {
            return persistViaFileApi(root, tarArgs.toString());
        }
        return persistViaExec(root, tarArgs.toString());
    }

    private InputStream persistViaFileApi(String root, String tarArgs) throws Exception {
        String tmpRel = TMP_DIR_REL + "/" + tempArchiveName("ws-persist") + ".tar";
        String tmpAbs = fileApiPath(tmpRel);
        String tarCmd =
                "mkdir -p "
                        + shellQuote(fileApiPath(TMP_DIR_REL))
                        + " && tar "
                        + tarArgs
                        + "-cf "
                        + shellQuote(tmpAbs)
                        + " -C "
                        + shellQuote(root)
                        + " .";
        ExecutionResult result = sdkSandbox.commands().run(tarCmd);
        if (result.getExitCode() != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                    "tar failed (exit=" + result.getExitCode() + "): " + result.getStderr());
        }
        try {
            byte[] tarBytes = sdkSandbox.files().read(tmpRel);
            return new ByteArrayInputStream(tarBytes);
        } finally {
            cleanupTempFile(tmpAbs);
        }
    }

    private InputStream persistViaExec(String root, String tarArgs) throws Exception {
        String b64Script = "tar " + tarArgs + "-cf - -C " + shellQuote(root) + " . | base64";
        ExecutionResult result = sdkSandbox.commands().run(b64Script);
        if (result.getExitCode() != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                    "tar+base64 failed (exit=" + result.getExitCode() + "): " + result.getStderr());
        }
        // MIME 解码器容忍 GNU `base64` 的自动换行输出。
        byte[] tarBytes = java.util.Base64.getMimeDecoder().decode(result.getStdout().trim());
        return new ByteArrayInputStream(tarBytes);
    }

    @Override
    protected void doHydrateWorkspace(InputStream archive) throws Exception {
        requireActiveConnection();
        String root = k8sState.getWorkspaceRoot();
        sdkSandbox.commands().run("mkdir -p " + shellQuote(root));

        byte[] tarBytes = archive.readAllBytes();
        if (fileApiEnabled()) {
            hydrateViaFileApi(root, tarBytes);
        } else {
            hydrateViaExec(root, tarBytes);
        }
    }

    private void hydrateViaFileApi(String root, byte[] tarBytes) throws Exception {
        String tmpRel = TMP_DIR_REL + "/" + tempArchiveName("ws-hydrate") + ".tar";
        String tmpAbs = fileApiPath(tmpRel);
        sdkSandbox.files().write(tmpRel, tarBytes);
        try {
            extractArchive(tmpAbs, root);
        } finally {
            cleanupTempFile(tmpAbs);
        }
    }

    private void hydrateViaExec(String root, byte[] tarBytes) throws Exception {
        String b64 = java.util.Base64.getEncoder().encodeToString(tarBytes);
        String tmpTar = "/tmp/" + tempArchiveName("ws-hydrate") + ".tar";

        String writeCmd = "echo '" + b64 + "' | base64 -d > " + tmpTar;
        ExecutionResult writeResult = sdkSandbox.commands().run(writeCmd);
        if (writeResult.getExitCode() != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                    "Failed to write archive to sandbox: " + writeResult.getStderr());
        }
        try {
            extractArchive(tmpTar, root);
        } finally {
            cleanupTempFile(tmpTar);
        }
    }

    private void extractArchive(String tarPath, String root) throws Exception {
        String extractCmd = "tar -xf " + shellQuote(tarPath) + " -C " + shellQuote(root);
        ExecutionResult extractResult = sdkSandbox.commands().run(extractCmd);
        if (extractResult.getExitCode() != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                    "tar extract failed (exit="
                            + extractResult.getExitCode()
                            + "): "
                            + extractResult.getStderr());
        }
    }

    private void cleanupTempFile(String path) {
        try {
            sdkSandbox.commands().run("rm -f " + shellQuote(path));
        } catch (Exception ignored) {
            log.debug("[sandbox-k8s] Failed to clean up temp archive {}", path);
        }
    }

    @Override
    protected void doSetupWorkspace() throws Exception {
        sdkSandbox.commands().run("mkdir -p " + shellQuote(k8sState.getWorkspaceRoot()));
    }

    @Override
    protected void doDestroyWorkspace() throws Exception {
        try {
            sdkSandbox.commands().run("rm -rf " + shellQuote(k8sState.getWorkspaceRoot()));
        } catch (Exception e) {
            log.warn(
                    "[sandbox-k8s] rm -rf {} failed: {}",
                    k8sState.getWorkspaceRoot(),
                    e.getMessage());
        }
    }

    @Override
    protected String getWorkspaceRoot() {
        return k8sState.getWorkspaceRoot();
    }

    /**
     * 返回底层 SDK 沙箱句柄。
     *
     * @return SDK 沙箱
     */
    public Sandbox getSdkSandbox() {
        return sdkSandbox;
    }

    @Override
    public boolean supportsFileTransfer(String absolutePath) {
        return toFileApiRelative(absolutePath) != null;
    }

    @Override
    public void uploadFile(String absolutePath, byte[] content) throws Exception {
        requireActiveConnection();
        String rel = requireFileApiRelative(absolutePath);
        int slash = absolutePath.lastIndexOf('/');
        if (slash > 0) {
            // 运行时文件 API 不保证创建父目录。
            ExecutionResult mkdir =
                    sdkSandbox
                            .commands()
                            .run("mkdir -p " + shellQuote(absolutePath.substring(0, slash)));
            if (mkdir.getExitCode() != 0) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_START_ERROR,
                        "mkdir failed for " + absolutePath + ": " + mkdir.getStderr());
            }
        }
        sdkSandbox.files().write(rel, content);
    }

    @Override
    public byte[] downloadFile(String absolutePath) throws Exception {
        requireActiveConnection();
        return sdkSandbox.files().read(requireFileApiRelative(absolutePath));
    }

    private void requireActiveConnection() {
        if (!sdkSandbox.isActive()) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.SANDBOX_CONNECTION_CLOSED,
                    "Kubernetes sandbox connection has been closed");
        }
    }

    /**
     * 将沙箱绝对路径映射为文件 API 相对路径；文件 API 禁用、路径在基础目录外或含路径穿越段时返回 null，这些路径改由 exec 传输策略处理。
     */
    private String toFileApiRelative(String absolutePath) {
        if (!fileApiEnabled() || absolutePath == null) {
            return null;
        }
        String base = k8sState.getFileApiBaseDir();
        String normBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (!absolutePath.startsWith(normBase + "/")) {
            return null;
        }
        String rel = absolutePath.substring(normBase.length() + 1);
        if (rel.isEmpty()) {
            return null;
        }
        for (String part : rel.split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                return null;
            }
        }
        return rel;
    }

    private String requireFileApiRelative(String absolutePath) {
        String rel = toFileApiRelative(absolutePath);
        if (rel == null) {
            throw new IllegalArgumentException(
                    "Path not transferable via the runtime file API: " + absolutePath);
        }
        return rel;
    }

    private boolean fileApiEnabled() {
        String base = k8sState.getFileApiBaseDir();
        return base != null && !base.isBlank();
    }

    private String fileApiPath(String relative) {
        String base = k8sState.getFileApiBaseDir();
        return (base.endsWith("/") ? base : base + "/") + relative;
    }

    private String sessionHash() {
        return Integer.toHexString(Math.abs(k8sState.getSessionId().hashCode()));
    }

    /**
     * 每次传输使用唯一临时归档名。同一沙箱的并发恢复/持久化不能共用名称：若使用确定性名称，一个调用的 {@code rm -f} 清理可能在另一个调用上传后、{@code tar -xf} 前删除其归档，导致解压报 "Cannot open: No such file or directory"。
     */
    private String tempArchiveName(String prefix) {
        return prefix + "-" + sessionHash() + "-" + UUID.randomUUID();
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }
}
