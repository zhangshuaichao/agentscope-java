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
package io.agentscope.harness.agent.filesystem.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxAware;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxFileTransfer;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将执行委托给活动 {@link Sandbox} 的 {@link BaseSandboxFilesystem}。
 *
 * <p>每个智能体 bean 只创建一次的稳定代理。每次调用的活动 {@link Sandbox} 由 {@link io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware}
 * 按<em>调用</em>绑定到该调用的 {@link RuntimeContext}，并在此通过 {@link #requireSandbox(RuntimeContext)} 解析。
 * 每次调用的绑定优先，这保证同一智能体 bean 上不同会话的并发调用相互隔离（问题 #2490）。
 * 旧的 {@code volatile sandbox} 字段仅作为尽力而为的回退，供无上下文、使用共享空 {@link RuntimeContext} 解析文件系统的内部调用方使用
 * （例如不携带每次调用绑定的 {@link io.agentscope.harness.agent.bus.WorkspaceMessageBus}）。
 * 中间件仍在获取时通过 {@link #setSandbox}、释放时通过 {@link #clearSandboxIfCurrent} 维护该字段，
 * 所以并发下它仍是后写入者胜出，不能依赖它实现隔离。
 */
public class SandboxBackedFilesystem extends BaseSandboxFilesystem implements SandboxAware {

    private static final Logger log = LoggerFactory.getLogger(SandboxBackedFilesystem.class);

    private final String fsId;
    private volatile Sandbox sandbox;

    public SandboxBackedFilesystem() {
        this.fsId = "sandbox-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public synchronized void setSandbox(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public Sandbox getSandbox() {
        return sandbox;
    }

    /**
     * 仅当回退字段 {@code sandbox} 仍指向 {@code expected} 时才清空。
     * {@link io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware} 在释放时调用它，防止结束的调用清空并发兄弟调用的回退绑定（问题 #2490）。
     *
     * @param expected 此调用获取时绑定的沙箱
     */
    public synchronized void clearSandboxIfCurrent(Sandbox expected) {
        if (this.sandbox == expected) {
            this.sandbox = null;
        }
    }

    @Override
    public String id() {
        return fsId;
    }

    @Override
    public ExecuteResponse execute(
            RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        Sandbox active = requireSandbox(runtimeContext);
        try {
            ExecResult result = active.exec(runtimeContext, command, timeoutSeconds);
            return new ExecuteResponse(
                    result.combinedOutput(), result.exitCode(), result.truncated());
        } catch (SandboxException.ExecTimeoutException e) {
            return new ExecuteResponse(e.getMessage(), 124, false);
        } catch (SandboxException.ExecException e) {
            String combined =
                    (e.getStdout() != null ? e.getStdout() : "")
                            + (e.getStderr() != null && !e.getStderr().isBlank()
                                    ? "\n" + e.getStderr()
                                    : "");
            return new ExecuteResponse(combined, e.getExitCode(), false);
        } catch (Exception e) {
            log.error("[sandbox-fs] execute failed: {}", command, e);
            return new ExecuteResponse("Internal sandbox error: " + e.getMessage(), -1, false);
        }
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        Sandbox active = requireSandbox(runtimeContext);
        List<FileUploadResponse> results = new ArrayList<>(files.size());

        for (Map.Entry<String, byte[]> file : files) {
            String path = file.getKey();
            byte[] content = file.getValue();
            if (content == null) {
                results.add(FileUploadResponse.fail(path, "File content must not be null"));
                continue;
            }

            if (active instanceof SandboxFileTransfer transfer) {
                String transferPath = null;
                try {
                    transferPath = resolveTransferPath(active, path);
                } catch (IllegalArgumentException e) {
                    // 与归档回退使用相同契约：无效路径使当前文件失败。
                    log.warn("[sandbox-fs] uploadFiles failed for path: {}", path, e);
                    results.add(FileUploadResponse.fail(path, e.getMessage()));
                    continue;
                } catch (IOException e) {
                    log.debug(
                            "[sandbox-fs] Workspace root unavailable, keeping archive fallback: {}",
                            path);
                }
                if (transferPath != null && transfer.supportsFileTransfer(transferPath)) {
                    try {
                        transfer.uploadFile(transferPath, content);
                        results.add(FileUploadResponse.success(path));
                    } catch (Exception e) {
                        log.warn("[sandbox-fs] native upload failed for path: {}", path, e);
                        results.add(FileUploadResponse.fail(path, e.getMessage()));
                    }
                    continue;
                }
            }

            try {
                byte[] archive = buildSingleFileArchive(active, path, content);
                try (InputStream archiveStream = new ByteArrayInputStream(archive)) {
                    active.hydrateWorkspace(archiveStream);
                }
                results.add(FileUploadResponse.success(path));
            } catch (Exception e) {
                log.warn("[sandbox-fs] uploadFiles failed for path: {}", path, e);
                results.add(FileUploadResponse.fail(path, e.getMessage()));
            }
        }

        return results;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        Sandbox active = requireSandbox(runtimeContext);
        List<FileDownloadResponse> results = new ArrayList<>(paths.size());

        for (String path : paths) {
            // 与上传不同，读取保留原始路径探测：没有会引发竞争的共享临时状态，
            // 而下面的执行回退仍只需一次往返。
            if (active instanceof SandboxFileTransfer transfer
                    && transfer.supportsFileTransfer(path)) {
                try {
                    results.add(FileDownloadResponse.success(path, transfer.downloadFile(path)));
                } catch (Exception e) {
                    log.warn("[sandbox-fs] native download failed for path: {}", path, e);
                    results.add(FileDownloadResponse.fail(path, e.getMessage()));
                }
                continue;
            }

            try {
                String escapedPath = shellSingleQuote(path);
                String cmd = "base64 " + escapedPath;

                ExecResult result = active.exec(runtimeContext, cmd, null);
                if (result.ok()) {
                    if (result.truncated()) {
                        results.add(
                                FileDownloadResponse.fail(
                                        path, "File download output was truncated by the sandbox"));
                        continue;
                    }
                    // MIME 解码器容忍 GNU `base64` 输出中的换行。
                    byte[] decoded =
                            Base64.getMimeDecoder()
                                    .decode(result.stdout() != null ? result.stdout() : "");
                    results.add(FileDownloadResponse.success(path, decoded));
                } else {
                    results.add(FileDownloadResponse.fail(path, result.combinedOutput()));
                }
            } catch (SandboxException.ExecException e) {
                String combined =
                        (e.getStdout() != null ? e.getStdout() : "")
                                + (e.getStderr() != null && !e.getStderr().isBlank()
                                        ? "\n" + e.getStderr()
                                        : "");
                results.add(FileDownloadResponse.fail(path, combined));
            } catch (Exception e) {
                log.warn("[sandbox-fs] downloadFiles failed for path: {}", path, e);
                results.add(FileDownloadResponse.fail(path, e.getMessage()));
            }
        }

        return results;
    }

    /**
     * 解析当前调用绑定的 {@link Sandbox}，优先使用 {@code runtimeContext} 携带的每次调用绑定
     * （不同会话并行调用时并发安全，问题 #2490）；对于直接调用 {@link #setSandbox} 且不传递每次调用上下文的调用方，回退到旧的 {@code sandbox} 字段。
     */
    private Sandbox requireSandbox(RuntimeContext runtimeContext) {
        Sandbox s = null;
        if (runtimeContext != null) {
            SandboxAcquireResult bound = runtimeContext.get(SandboxAcquireResult.class);
            if (bound != null) {
                if (bound.isReleased()) {
                    throw new SandboxException.SandboxConfigurationException(
                            "Sandbox acquisition has been released for this call");
                }
                s = bound.getSandbox();
            }
        }
        if (s == null) {
            s = sandbox;
        }
        if (s == null) {
            throw new SandboxException.SandboxConfigurationException(
                    "No active sandbox — sandbox filesystem used outside of a call context");
        }
        return s;
    }

    private String shellSingleQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * 构建相对于工作区根目录的单文件 tar 归档。
     */
    private byte[] buildSingleFileArchive(Sandbox active, String path, byte[] content)
            throws IOException {
        if (content == null) {
            throw new IOException("File content must not be null");
        }

        String archivePath = resolveArchivePath(active, path);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(output)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            TarArchiveEntry entry = new TarArchiveEntry(archivePath);
            entry.setSize(content.length);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
            tar.finish();
        }
        return output.toByteArray();
    }

    /**
     * 将上传路径限制在工作区内，并转换为归档路径。
     */
    private String resolveArchivePath(Sandbox active, String path) throws IOException {
        AbstractFilesystem.validatePath(path);
        String normalized = normalizeUploadPath(path);

        if (normalized.startsWith("/")) {
            String workspaceRoot = resolveWorkspaceRoot(active);
            String rootPrefix = "/".equals(workspaceRoot) ? "/" : workspaceRoot + "/";
            if (!normalized.startsWith(rootPrefix)) {
                throw new IOException("Upload path is outside the sandbox workspace: " + path);
            }
            normalized = normalized.substring(rootPrefix.length());
        }

        if (normalized.isBlank()) {
            throw new IOException("Upload path must identify a file: " + path);
        }
        return normalized;
    }

    /**
     * 规范化上传路径并解析为沙箱内绝对路径，使工作区相对路径可使用原生单文件传输
     * （{@link SandboxFileTransfer#uploadFile}），而不是归档恢复回退。
     * 无效路径抛出 {@link IllegalArgumentException}；调用方只让当前文件失败，与归档回退的验证契约一致。
     */
    private String resolveTransferPath(Sandbox active, String path) throws IOException {
        AbstractFilesystem.validatePath(path);
        String normalized = normalizeUploadPath(path);
        if (normalized.startsWith("/")) {
            return normalized;
        }
        return resolveWorkspaceRoot(active) + "/" + normalized;
    }

    /**
     * 规范化分隔符，并移除开头的 {@code ./} 片段。
     */
    private static String normalizeUploadPath(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    /**
     * 解析用于转换绝对上传路径的规范化工作区根目录。
     */
    private String resolveWorkspaceRoot(Sandbox active) throws IOException {
        SandboxState state = active.getState();
        WorkspaceSpec workspaceSpec = state != null ? state.getWorkspaceSpec() : null;
        String root = workspaceSpec != null ? workspaceSpec.getRoot() : null;
        if (root == null || root.isBlank()) {
            throw new IOException("Sandbox workspace root is unavailable");
        }

        String normalized = root.replace('\\', '/');
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.startsWith("/")) {
            throw new IOException("Sandbox workspace root must be absolute: " + root);
        }
        return normalized;
    }
}
