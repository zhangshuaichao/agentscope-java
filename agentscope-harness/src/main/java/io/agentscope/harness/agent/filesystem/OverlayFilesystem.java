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
package io.agentscope.harness.agent.filesystem;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分层文件系统：在共享“下层”之上覆盖用户专属“上层”，提供写时复制语义。
 *
 * <p>读取先检查上层，再回退到下层；写入始终面向上层。
 * 这样可以按用户定制共享内容（例如技能、子智能体），而不修改共享原件。
 *
 * <p>合并语义：
 *
 * <ul>
 *   <li>{@code ls} — 合并两层；路径冲突时上层条目优先
 *   <li>{@code read} — 先上层，后下层
 *   <li>{@code write}/{@code edit} — 始终写上层（写时复制）
 *   <li>{@code delete} — 仅从上层删除；不能删除共享层文件
 *   <li>{@code grep}/{@code glob} — 搜索两层；路径冲突时上层结果覆盖下层结果
 *   <li>{@code exists} — 任一层存在即为 true
 * </ul>
 */
public class OverlayFilesystem implements AbstractFilesystem {

    @Override
    public io.agentscope.core.session.AtomicSessionStorage sessionStorage(RuntimeContext rc) {
        return upper.sessionStorage(rc);
    }

    private final AbstractFilesystem upper;
    private final AbstractFilesystem lower;

    /**
     * 创建覆盖文件系统。
     *
     * @param upper 用户专属层（可读写）；冲突时优先
     * @param lower 共享层（从此覆盖层看为只读）
     */
    public OverlayFilesystem(AbstractFilesystem upper, AbstractFilesystem lower) {
        if (upper == null) {
            throw new IllegalArgumentException("upper layer must not be null");
        }
        if (lower == null) {
            throw new IllegalArgumentException("lower layer must not be null");
        }
        this.upper = upper;
        this.lower = lower;
    }

    /**
     * 返回上层（可写层）。
     */
    public AbstractFilesystem upper() {
        return upper;
    }

    /**
     * 返回下层（共享层）。
     */
    public AbstractFilesystem lower() {
        return lower;
    }

    /**
     * 构建覆盖层；当 {@code upper} 支持 Shell 执行时自动暴露该能力。
     * 当 {@code upper instanceof AbstractSandboxFilesystem} 时返回实现
     * {@link AbstractSandboxFilesystem} 的子类，否则返回普通 {@link OverlayFilesystem}。
     *
     * <p>{@code upper} 可能带 Shell 能力时（例如
     * {@link io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell}），
     * 优先使用此工厂而不是构造方法；这样 {@code ReActAgent.Builder} 等调用方的
     * {@code instanceof AbstractSandboxFilesystem} 检查穿过覆盖层后仍然有效。
     *
     * @param upper 用户专属层（可读写）；冲突时优先
     * @param lower 共享层（从此覆盖层看为只读）
     * @return 根据 {@code upper} 能力返回普通或感知 Shell 的覆盖层
     */
    public static AbstractFilesystem of(AbstractFilesystem upper, AbstractFilesystem lower) {
        if (upper instanceof AbstractSandboxFilesystem shellUpper) {
            return new ShellAwareOverlay(shellUpper, lower);
        }
        return new OverlayFilesystem(upper, lower);
    }

    /**
     * 将 Shell {@link #execute} 和 {@link #id} 委托给支持 Shell 的上层存储的覆盖子类。
     * 文件系统操作继承 {@link OverlayFilesystem} 的标准覆盖语义。
     */
    private static final class ShellAwareOverlay extends OverlayFilesystem
            implements AbstractSandboxFilesystem {

        private final AbstractSandboxFilesystem shellBackend;

        ShellAwareOverlay(AbstractSandboxFilesystem upper, AbstractFilesystem lower) {
            super(upper, lower);
            this.shellBackend = upper;
        }

        @Override
        public String id() {
            return shellBackend.id();
        }

        @Override
        public ExecuteResponse execute(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            return shellBackend.execute(runtimeContext, command, timeoutSeconds);
        }
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        LsResult upperResult = upper.ls(runtimeContext, path);
        LsResult lowerResult = lower.ls(runtimeContext, path);

        if (!upperResult.isSuccess() && !lowerResult.isSuccess()) {
            return lowerResult;
        }

        Map<String, FileInfo> merged = new LinkedHashMap<>();
        if (lowerResult.isSuccess() && lowerResult.entries() != null) {
            for (FileInfo fi : lowerResult.entries()) {
                merged.put(fi.path(), fi);
            }
        }
        if (upperResult.isSuccess() && upperResult.entries() != null) {
            for (FileInfo fi : upperResult.entries()) {
                merged.put(fi.path(), fi);
            }
        }
        return LsResult.success(new ArrayList<>(merged.values()));
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        if (upper.exists(runtimeContext, filePath)) {
            return upper.read(runtimeContext, filePath, offset, limit);
        }
        return lower.read(runtimeContext, filePath, offset, limit);
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        return upper.write(runtimeContext, filePath, content);
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        if (upper.exists(runtimeContext, filePath)) {
            return upper.edit(runtimeContext, filePath, oldString, newString, replaceAll);
        }
        if (lower.exists(runtimeContext, filePath)) {
            ReadResult src = lower.read(runtimeContext, filePath, 0, Integer.MAX_VALUE);
            if (!src.isSuccess()) {
                return EditResult.fail("Cannot read shared file for copy-on-write: " + filePath);
            }
            upper.write(runtimeContext, filePath, src.fileData().content());
            return upper.edit(runtimeContext, filePath, oldString, newString, replaceAll);
        }
        return EditResult.fail("File not found: " + filePath);
    }

    @Override
    public GrepResult grep(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        GrepResult upperResult = upper.grep(runtimeContext, pattern, path, glob);
        GrepResult lowerResult = lower.grep(runtimeContext, pattern, path, glob);

        if (!upperResult.isSuccess() && !lowerResult.isSuccess()) {
            return lowerResult;
        }

        Map<String, GrepMatch> merged = new LinkedHashMap<>();
        if (lowerResult.isSuccess() && lowerResult.matches() != null) {
            for (GrepMatch m : lowerResult.matches()) {
                merged.put(m.path() + ":" + m.line(), m);
            }
        }
        if (upperResult.isSuccess() && upperResult.matches() != null) {
            for (GrepMatch m : upperResult.matches()) {
                merged.put(m.path() + ":" + m.line(), m);
            }
        }
        return GrepResult.success(new ArrayList<>(merged.values()));
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        GlobResult upperResult = upper.glob(runtimeContext, pattern, path);
        GlobResult lowerResult = lower.glob(runtimeContext, pattern, path);

        if (!upperResult.isSuccess() && !lowerResult.isSuccess()) {
            return lowerResult;
        }

        Map<String, FileInfo> merged = new LinkedHashMap<>();
        if (lowerResult.isSuccess() && lowerResult.matches() != null) {
            for (FileInfo fi : lowerResult.matches()) {
                merged.put(fi.path(), fi);
            }
        }
        if (upperResult.isSuccess() && upperResult.matches() != null) {
            for (FileInfo fi : upperResult.matches()) {
                merged.put(fi.path(), fi);
            }
        }
        return GlobResult.success(new ArrayList<>(merged.values()));
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        return upper.uploadFiles(runtimeContext, files);
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        List<FileDownloadResponse> results = new ArrayList<>();
        for (String path : paths) {
            if (upper.exists(runtimeContext, path)) {
                results.addAll(upper.downloadFiles(runtimeContext, List.of(path)));
            } else {
                results.addAll(lower.downloadFiles(runtimeContext, List.of(path)));
            }
        }
        return results;
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        if (upper.exists(runtimeContext, path)) {
            return upper.delete(runtimeContext, path);
        }
        if (lower.exists(runtimeContext, path)) {
            return WriteResult.fail("Cannot delete shared file: " + path);
        }
        return WriteResult.ok(path);
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        if (upper.exists(runtimeContext, fromPath)) {
            return upper.move(runtimeContext, fromPath, toPath);
        }
        if (lower.exists(runtimeContext, fromPath)) {
            ReadResult src = lower.read(runtimeContext, fromPath, 0, Integer.MAX_VALUE);
            if (!src.isSuccess()) {
                return WriteResult.fail("Cannot read source for move: " + fromPath);
            }
            return upper.write(runtimeContext, toPath, src.fileData().content());
        }
        return WriteResult.fail("Source not found: " + fromPath);
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        return upper.exists(runtimeContext, path) || lower.exists(runtimeContext, path);
    }

    /**
     * 返回用户专属层（可读写）。
     */
    public AbstractFilesystem getUpper() {
        return upper;
    }

    /**
     * 返回共享层（通过此覆盖层访问时只读）。
     */
    public AbstractFilesystem getLower() {
        return lower;
    }
}
