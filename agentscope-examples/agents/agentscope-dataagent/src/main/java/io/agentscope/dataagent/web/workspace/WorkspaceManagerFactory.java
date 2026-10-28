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
package io.agentscope.dataagent.web.workspace;

import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * 构建 {@link WorkspaceManager}，其文件系统由 {@link UserSandboxRegistry} 提供的逐 {@code (userId,
 * agentId)} 活跃 {@link Sandbox} 支持。
 * <p>相同 {@link Sandbox} 实例同时服务智能体运行时（网关注入 {@code
 * SandboxContext.externalSandbox}）及浏览器控制器的工作区操作。每用户独立容器实现隔离，
 * 不使用路径前缀命名空间，也没有会跨租户泄漏内容的共享 {@code LocalFilesystem} 层。
 * <p>{@link WorkspaceManager#getWorkspace()} 返回的 {@link Path} 是宿主侧标签
 * （用于显示、审计及 RuntimeContext 元数据），<em>不是</em>文件数据实际磁盘位置。
 * 实际读写通过 {@link SharedSandboxFilesystem} 进入容器中的 {@code /workspace}。
 */
public final class WorkspaceManagerFactory {

    private final UserSandboxRegistry registry;

    public WorkspaceManagerFactory(UserSandboxRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** 返回用户范围智能体的 {@link WorkspaceManager}，等同于以 {@code workspacePath=null} 调用 {@link #forAgent(String, String, String)}。 */
    public WorkspaceManager forAgent(String ownerId, String agentId) {
        return forAgent(ownerId, agentId, null);
    }

    /** 返回用户范围智能体的 {@link WorkspaceManager}。借用（首次时启动）逐 {@code (ownerId, agentId)} 沙箱；返回的 {@link WorkspaceManager} 经 {@link SharedSandboxFilesystem} 读写该沙箱。 */
    public WorkspaceManager forAgent(String ownerId, String agentId, String workspacePath) {
        validateSegment("ownerId", ownerId);
        validateSegment("agentId", agentId);
        Sandbox sb = registry.borrow(ownerId, agentId);
        Path dataPath = resolveAgentDataPath(workspacePath, agentId);
        return new WorkspaceManager(dataPath, new SharedSandboxFilesystem(sb));
    }

    /** 返回指定用户访问全局智能体时的 {@link WorkspaceManager}。等同于 {@link #forAgent(String, String, String)}：使用沙箱后，文件系统层不再区分全局/用户类型，因为沙箱本身以 {@code (userId, agentId)} 为键。保留独立入口以提高调用可读性。 */
    public WorkspaceManager forGlobalAgent(String userId, String agentId) {
        return forAgent(userId, agentId, null);
    }

    /** 参见 {@link #forGlobalAgent(String, String)}。 */
    public WorkspaceManager forGlobalAgent(String userId, String agentId, String workspacePath) {
        return forAgent(userId, agentId, workspacePath);
    }

    /** 返回不带 {@link WorkspaceManager} 包装的逐 {@code (userId, agentId)} 原始 {@link AbstractFilesystem}，适合在用户隔离工作区中枚举/复制文件（尤其审计活动日志及 {@link io.agentscope.dataagent.web.util.WorkspaceCopier}）。 */
    public AbstractFilesystem userDataFs(String ownerId, String agentId, String workspacePath) {
        validateSegment("ownerId", ownerId);
        validateSegment("agentId", agentId);
        return new SharedSandboxFilesystem(registry.borrow(ownerId, agentId));
    }

    /** {@link #userDataFs(String, String, String)} 返回路径的前缀。沙箱隔离使整个容器属于单个用户，故前缀仅为容器根（{@code "/"}）。保留此 API 避免迁移时强制修改调用方。 */
    public String userDataPathPrefix(String ownerId, String agentId, String workspacePath) {
        validateSegment("ownerId", ownerId);
        validateSegment("agentId", agentId);
        return "/";
    }

    /** 将用户提供的工作区路径解析为绝对宿主侧数据根标签，沿用沙箱化之前的规则，使 {@link WorkspaceManager#getWorkspace()} 继续返回相同审计/界面标签。<ul><li>{@code workspacePath} 为 null 或空白时使用 {@code fallbackAgentId}。<li>绝对路径规范化后返回。<li>已位于 {@code ${cwd}/.agentscope/} 下的相对路径直接使用。<li>其他相对路径以 {@code ${cwd}/.agentscope/} 为基准解析。</ul> */
    public Path resolveAgentDataPath(String workspacePath, String fallbackAgentId) {
        String raw =
                (workspacePath != null && !workspacePath.isBlank())
                        ? workspacePath.trim()
                        : fallbackAgentId;
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "workspacePath and fallbackAgentId are both null/blank");
        }
        Path p = Paths.get(raw);
        if (p.isAbsolute()) {
            return p.normalize();
        }
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path agentScopeBase = cwd.resolve(".agentscope").normalize();
        Path resolvedAgainstCwd = cwd.resolve(p).normalize();
        if (resolvedAgainstCwd.startsWith(agentScopeBase)) {
            return resolvedAgainstCwd;
        }
        return agentScopeBase.resolve(p).normalize();
    }

    private static void validateSegment(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be null or blank");
        }
        if (value.contains("/") || value.contains("\\") || value.contains("..")) {
            throw new IllegalArgumentException(
                    label + " must not contain path separators or '..': " + value);
        }
    }
}
