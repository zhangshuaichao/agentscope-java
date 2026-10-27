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
package io.agentscope.harness.agent.filesystem.spec;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 声明式沙箱文件系统配置。
 *
 * <p>与 {@code AbstractFilesystem} 不同，此类型不是运行时文件系统实现，
 * 只描述如何在构建阶段创建由沙箱支持的文件系统。
 */
public abstract class SandboxFilesystemSpec {

    private static final List<String> DEFAULT_WORKSPACE_PROJECTION_ROOTS =
            List.of("AGENTS.md", "skills", "subagents", "knowledge", ".skills-cache");

    private IsolationScope isolationScope;
    private SandboxSnapshotSpec snapshotSpecOverride;
    private SandboxExecutionGuard executionGuard;
    private boolean workspaceProjectionEnabled = true;
    private List<String> workspaceProjectionRoots = DEFAULT_WORKSPACE_PROJECTION_ROOTS;

    protected abstract SandboxClient<?> createClient();

    protected abstract SandboxClientOptions clientOptions();

    protected abstract SandboxSnapshotSpec snapshotSpec();

    protected abstract WorkspaceSpec workspaceSpec();

    public SandboxFilesystemSpec isolationScope(IsolationScope scope) {
        this.isolationScope = scope;
        return this;
    }

    public IsolationScope getIsolationScope() {
        return isolationScope;
    }

    public SandboxFilesystemSpec snapshotSpec(SandboxSnapshotSpec snapshotSpec) {
        this.snapshotSpecOverride = snapshotSpec;
        return this;
    }

    public SandboxSnapshotSpec getSnapshotSpecOverride() {
        return snapshotSpecOverride;
    }

    /**
     * 设置 {@link SandboxExecutionGuard}，将同一隔离槽位中的并发执行串行化。
     *
     * <p>仅适用于 {@link io.agentscope.harness.agent.IsolationScope#AGENT} 和
     * {@link io.agentscope.harness.agent.IsolationScope#GLOBAL} 作用域，
     * 否则多个调用方可能竞争同一持久化状态。为 {@code null}（默认）时不设置守卫，
     * 保留现有无锁行为。
     *
     * @param executionGuard 要应用的守卫；{@code null} 表示不使用
     * @return 此配置对象
     */
    public SandboxFilesystemSpec executionGuard(SandboxExecutionGuard executionGuard) {
        this.executionGuard = executionGuard;
        return this;
    }

    public SandboxExecutionGuard getExecutionGuard() {
        return executionGuard;
    }

    public SandboxFilesystemSpec workspaceProjectionEnabled(boolean enabled) {
        this.workspaceProjectionEnabled = enabled;
        return this;
    }

    public SandboxFilesystemSpec workspaceProjectionRoots(List<String> includeRoots) {
        this.workspaceProjectionRoots =
                includeRoots != null
                        ? List.copyOf(includeRoots)
                        : DEFAULT_WORKSPACE_PROJECTION_ROOTS;
        return this;
    }

    public final SandboxContext toSandboxContext(Path hostWorkspaceRoot) {
        SandboxClient<?> client =
                Objects.requireNonNull(createClient(), "sandbox client is required");
        WorkspaceSpec withProjection = buildWorkspaceSpecWithProjection(hostWorkspaceRoot);
        return SandboxContext.builder()
                .client(client)
                .clientOptions(clientOptions())
                .snapshotSpec(snapshotSpecOverride != null ? snapshotSpecOverride : snapshotSpec())
                .workspaceSpec(withProjection)
                .isolationScope(isolationScope)
                .build();
    }

    public final SandboxContext toSandboxContext() {
        return toSandboxContext(null);
    }

    private WorkspaceSpec buildWorkspaceSpecWithProjection(Path hostWorkspaceRoot) {
        WorkspaceSpec base = workspaceSpec();
        WorkspaceSpec effective = base != null ? base.copy() : new WorkspaceSpec();
        if (!workspaceProjectionEnabled || hostWorkspaceRoot == null) {
            return effective;
        }
        WorkspaceProjectionEntry projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot(hostWorkspaceRoot.toAbsolutePath().normalize().toString());
        projection.setIncludeRoots(workspaceProjectionRoots);

        Map<String, WorkspaceEntry> entries = new LinkedHashMap<>(effective.getEntries());
        entries.put("__workspace_projection__", projection);
        effective.setEntries(entries);
        return effective;
    }
}
