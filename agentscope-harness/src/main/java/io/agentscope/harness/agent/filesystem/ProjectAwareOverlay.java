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
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 覆盖层变体：把非工作区路径的写入路由到<em>项目</em>目录，
 * 而工作区元数据（记忆、会话、技能等）仍保存在上层工作区。
 *
 * <p>仅由 {@link io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec}
 * 在设置 {@code projectWritable(true)} 时创建。其他文件系统配置
 * （{@code RemoteFilesystemSpec}、{@code SandboxFilesystemSpec}）不受影响。
 *
 * <p>每个路由路径首先规范化为智能体可见的相对形式：以工作区为根的绝对路径
 * （例如 {@code <workspace>/src/Foo.java}）会去除工作区根前缀；若配置命名空间作用域，
 * 还会去除调用方的命名空间前缀，因此同一路径的绝对与相对写法在各操作中行为相同。
 * 工作区根之外的绝对路径保持原样，绝不会被归类为工作区元数据。
 *
 * <p>读取保留标准覆盖语义：先查上层工作区，再回退到下层项目。
 * Shell {@code execute()} 与之前一样委托给上层。
 */
public class ProjectAwareOverlay extends OverlayFilesystem implements AbstractSandboxFilesystem {

    private static final Set<String> WORKSPACE_PREFIXES =
            Set.of(
                    "MEMORY.md",
                    "memory",
                    "AGENTS.md",
                    "agents",
                    "skills",
                    "knowledge",
                    "rules",
                    "tools.json",
                    "subagents",
                    "plans",
                    ".index",
                    ".skills-cache",
                    "large_tool_results");

    private final AbstractSandboxFilesystem shellBackend;
    private final LocalFilesystem projectFs;
    private final Path workspaceRoot;
    private final NamespaceFactory namespaceFactory;

    /**
     * @param upper 支持 Shell 的工作区文件系统（可读写，以工作区为根）
     * @param lower 只读项目文件系统（覆盖层的回退后端）
     * @param projectFs 用于非工作区写入的可写项目文件系统
     * @param workspaceRoot 工作区绝对路径，用于分类绝对路径
     * @param namespaceFactory 限定工作区及项目文件系统作用域的命名空间工厂；
     *     用于从工作区根下的绝对路径去除调用方命名空间前缀；未配置作用域时为 {@code null}
     */
    public ProjectAwareOverlay(
            AbstractSandboxFilesystem upper,
            AbstractFilesystem lower,
            LocalFilesystem projectFs,
            Path workspaceRoot,
            NamespaceFactory namespaceFactory) {
        super(upper, lower);
        this.shellBackend = upper;
        this.projectFs = projectFs;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.namespaceFactory = namespaceFactory;
    }

    /**
     * 不感知命名空间的变体：工作区根下的绝对路径保留命名空间片段，
     * 在启用命名空间作用域时可能被错误路由。
     *
     * @deprecated 请使用 {@link #ProjectAwareOverlay(AbstractSandboxFilesystem, AbstractFilesystem,
     *     LocalFilesystem, Path, NamespaceFactory)}，使启用命名空间作用域时，
     *     工作区根下的绝对路径与其相对写法解析一致
     * @param upper 支持 Shell 的工作区文件系统（可读写，以工作区为根）
     * @param lower 只读项目文件系统（覆盖层的回退后端）
     * @param projectFs 用于非工作区写入的可写项目文件系统
     * @param workspaceRoot 工作区绝对路径，用于分类绝对路径
     */
    @Deprecated
    public ProjectAwareOverlay(
            AbstractSandboxFilesystem upper,
            AbstractFilesystem lower,
            LocalFilesystem projectFs,
            Path workspaceRoot) {
        this(upper, lower, projectFs, workspaceRoot, null);
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

    // ==================== 写入路由 ====================

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        String target = toWorkspaceRelativePath(runtimeContext, filePath);
        if (isWorkspacePath(runtimeContext, filePath)) {
            return upper().write(runtimeContext, target, content);
        }
        return projectFs.write(runtimeContext, target, content);
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        String target = toWorkspaceRelativePath(runtimeContext, filePath);
        if (isWorkspacePath(runtimeContext, filePath)) {
            return super.edit(runtimeContext, target, oldString, newString, replaceAll);
        }
        if (projectFs.exists(runtimeContext, target)) {
            return projectFs.edit(runtimeContext, target, oldString, newString, replaceAll);
        }
        // 回退：文件可能仅存在于上层（在启用 projectWritable 前写入）。
        return super.edit(runtimeContext, target, oldString, newString, replaceAll);
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        String target = toWorkspaceRelativePath(runtimeContext, path);
        if (isWorkspacePath(runtimeContext, path)) {
            return super.delete(runtimeContext, target);
        }
        if (projectFs.exists(runtimeContext, target)) {
            return projectFs.delete(runtimeContext, target);
        }
        return super.delete(runtimeContext, target);
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        List<Map.Entry<String, byte[]>> workspaceFiles = new ArrayList<>();
        List<Map.Entry<String, byte[]>> projectFiles = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : files) {
            if (isWorkspacePath(runtimeContext, entry.getKey())) {
                workspaceFiles.add(entry);
            } else {
                projectFiles.add(
                        Map.entry(
                                toWorkspaceRelativePath(runtimeContext, entry.getKey()),
                                entry.getValue()));
            }
        }
        List<FileUploadResponse> results = new ArrayList<>();
        if (!workspaceFiles.isEmpty()) {
            results.addAll(upper().uploadFiles(runtimeContext, workspaceFiles));
        }
        if (!projectFiles.isEmpty()) {
            results.addAll(projectFs.uploadFiles(runtimeContext, projectFiles));
        }
        return results;
    }

    // ==================== 读取／搜索／移动路由 ====================

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        return super.read(
                runtimeContext, toWorkspaceRelativePath(runtimeContext, filePath), offset, limit);
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        return super.exists(runtimeContext, toWorkspaceRelativePath(runtimeContext, path));
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        List<String> targets =
                paths.stream().map(path -> toWorkspaceRelativePath(runtimeContext, path)).toList();
        return super.downloadFiles(runtimeContext, targets);
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        return super.ls(runtimeContext, toWorkspaceRelativePath(runtimeContext, path));
    }

    @Override
    public GrepResult grep(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        return super.grep(
                runtimeContext, pattern, toWorkspaceRelativePath(runtimeContext, path), glob);
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        return super.glob(runtimeContext, pattern, toWorkspaceRelativePath(runtimeContext, path));
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        return super.move(
                runtimeContext,
                toWorkspaceRelativePath(runtimeContext, fromPath),
                toWorkspaceRelativePath(runtimeContext, toPath));
    }

    // ==================== 路径分类 ====================

    boolean isWorkspacePath(RuntimeContext runtimeContext, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return true;
        }
        String normalized = normalizeSeparators(filePath);
        String rel = workspaceRelative(runtimeContext, normalized);
        if (rel != null) {
            return matchesWorkspacePrefix(rel);
        }
        if (Path.of(normalized).isAbsolute()) {
            return false;
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return matchesWorkspacePrefix(normalized);
    }

    private boolean matchesWorkspacePrefix(String rel) {
        for (String prefix : WORKSPACE_PREFIXES) {
            if (rel.equals(prefix) || rel.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回位于 {@link #workspaceRoot} 下的绝对路径所对应的智能体可见相对形式：
     * 去除工作区根和调用方命名空间前缀，使同一路径的绝对和相对写法可以互换。
     * 当 {@code normalized} 为相对路径、等于工作区根或位于工作区根之外时返回 {@code
     * null}。
     */
    private String workspaceRelative(RuntimeContext runtimeContext, String normalized) {
        Path absolute = Path.of(normalized);
        if (!absolute.isAbsolute()) {
            return null;
        }
        Path normalizedPath = absolute.normalize();
        if (!normalizedPath.startsWith(workspaceRoot)) {
            return null;
        }
        String rel = stripNamespacePrefix(runtimeContext, workspaceRoot.relativize(normalizedPath));
        return rel.isEmpty() ? null : rel;
    }

    /**
     * 当工作区相对路径以调用方命名空间前缀开头时，移除该前缀；
     * 未启用命名空间作用域或前缀不匹配时，路径保持原样。
     */
    private String stripNamespacePrefix(RuntimeContext runtimeContext, Path workspaceRelative) {
        String rel = workspaceRelative.toString().replace('\\', '/');
        if (namespaceFactory == null) {
            return rel;
        }
        List<String> namespace = namespaceFactory.getNamespace(runtimeContext);
        if (namespace == null || namespace.isEmpty()) {
            return rel;
        }
        String prefix = String.join("/", namespace);
        return rel.startsWith(prefix + "/") ? rel.substring(prefix.length() + 1) : rel;
    }

    /**
     * 将工作区根下的绝对路径改写为智能体可见的相对形式（见 {@link
     * #workspaceRelative}）；其他路径保持原样。全部路由操作都通过此方法处理输入，
     * 使同一路径的两种写法在工作区、项目和覆盖层中解析一致。
     */
    private String toWorkspaceRelativePath(RuntimeContext runtimeContext, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return filePath;
        }
        String rel = workspaceRelative(runtimeContext, normalizeSeparators(filePath));
        return rel != null ? rel : filePath;
    }

    private static String normalizeSeparators(String filePath) {
        return filePath.replace('\\', '/').strip();
    }
}
