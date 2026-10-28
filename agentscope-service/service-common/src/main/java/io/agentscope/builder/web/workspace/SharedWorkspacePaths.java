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
package io.agentscope.builder.web.workspace;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/** 解析从平台共享工作区根目录派生的磁盘路径，不负责组装文件系统实例。路由/组合职责属于 Harness；此工具仅让需要稳定旁路目录的平台功能（尤其是每用户市场 Git 克隆缓存和逐智能体工作区脚手架）推导可预测路径，而无需重写布局约定。 */
public final class SharedWorkspacePaths {

    private final Path workspaceRoot;

    public SharedWorkspacePaths(Path workspaceRoot) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
    }

    /** 返回承载所有共享工作区内容的磁盘根目录。调用方应追加自身路径段，而非直接遍历文件系统。 */
    /** 单个已认证会话的稳定、非用户可控目录。 */
    public Path resolveSessionDataPath(String ownerId, String sessionId) {
        if (ownerId == null || ownerId.isBlank() || sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("ownerId and sessionId are required");
        }
        String ownerKey =
                java.util
                        .UUID
                        .nameUUIDFromBytes(
                                ownerId.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString();
        String sessionKey =
                java.util
                        .UUID
                        .nameUUIDFromBytes(
                                sessionId.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString();
        return workspaceRoot
                .toAbsolutePath()
                .normalize()
                .resolve("sessions")
                .resolve(ownerKey)
                .resolve(sessionKey);
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    /** 将用户提供的智能体工作区路径解析为绝对磁盘数据根目录。<ul><li>{@code workspacePath} 为 null 或空白时，使用智能体 ID。<li>结果为绝对路径时，规范化后返回。<li>相对路径若以当前工作目录解析后已位于 {@code ${cwd}/.agentscope/} 下，则直接使用（避免用户输入 {@code .agentscope/foo} 时重复添加前缀）。<li>否则以 {@code ${cwd}/.agentscope/} 为基准解析。</ul><p>【学习提示】此方法负责路径布局，不是访问授权或沙箱隔离；允许绝对路径的行为不能被误认为强制限制在共享根目录内。 */
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
}
