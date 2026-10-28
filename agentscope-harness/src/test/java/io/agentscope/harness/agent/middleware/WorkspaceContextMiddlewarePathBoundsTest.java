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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 确保系统提示词告诉 LLM 可用的绝对路径、当前模式的边界行为，以及额外允许根目录位置；智能体无需先触发 {@code SecurityException} 才知道文件系统可达范围。 */
class WorkspaceContextMiddlewarePathBoundsTest {

    private final List<WorkspaceManager> openManagers = new ArrayList<>();

    @AfterEach
    void closeOpenManagers() {
        // 释放 SQLite 句柄，使 Windows 上的 @TempDir 能删除工作区。
        for (WorkspaceManager wm : openManagers) {
            wm.close();
        }
        openManagers.clear();
    }

    private WorkspaceManager track(WorkspaceManager wm) {
        openManagers.add(wm);
        return wm;
    }

    @Test
    void localOverlay_promptListsProjectWorkspaceAndModeBoundary(
            @TempDir Path project, @TempDir Path workspace) {
        AbstractFilesystem fs =
                new LocalFilesystemSpec().project(project).toFilesystem(workspace, null);
        WorkspaceManager wm = track(new WorkspaceManager(workspace, fs));
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        String prompt = WorkspacePromptTestSupport.render(mw, null, "BASE\n");
        assertNotNull(prompt);
        // 项目与工作区说明行
        assertTrue(prompt.contains("Project (the user's source tree"));
        assertTrue(prompt.contains(project.toAbsolutePath().toString()));
        assertTrue(prompt.contains("Workspace (your home base"));
        assertTrue(prompt.contains(workspace.toAbsolutePath().toString()));
        // 明确显示模式
        assertTrue(
                prompt.contains("Path access policy: ROOTED"),
                () -> "Mode boundary not mentioned in prompt: " + prompt);
        // 说明边界行为
        assertTrue(prompt.contains("reject absolute paths outside the roots above"));
        // 未配置 additionalRoots 时，不应出现 "Additional roots:" 行
        assertFalse(prompt.contains("Additional roots:"));
    }

    @Test
    void localOverlay_addRoot_isAdvertised(
            @TempDir Path project, @TempDir Path workspace, @TempDir Path shared) {
        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .project(project)
                        .addRoot(shared)
                        .toFilesystem(workspace, null);
        WorkspaceManager wm = track(new WorkspaceManager(workspace, fs));
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        String prompt = WorkspacePromptTestSupport.render(mw, null, "BASE\n");
        assertNotNull(prompt);
        assertTrue(prompt.contains("Additional roots: " + shared.toAbsolutePath()));
    }

    @Test
    void localOverlay_sessionIsolationAdvertisesEffectiveWorkspace(
            @TempDir Path project, @TempDir Path workspace) {
        NamespaceFactory namespaceFactory = IsolationScope.SESSION.toNamespaceFactory();
        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .project(project)
                        .toFilesystem(workspace, namespaceFactory);
        WorkspaceManager wm = track(new WorkspaceManager(workspace, fs, null, namespaceFactory));
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);
        RuntimeContext rc = RuntimeContext.builder().sessionId("session-1").build();

        String prompt = WorkspacePromptTestSupport.render(mw, rc, "BASE\n");

        assertNotNull(prompt);
        Path effectiveWorkspace = workspace.resolve("session-1").toAbsolutePath();
        assertTrue(prompt.contains("The workspace directory is: " + effectiveWorkspace + "\n"));
        assertTrue(
                prompt.contains(
                        "Workspace (your home base — memory, sessions, skills, runtime data): "
                                + effectiveWorkspace
                                + "\n"));
        assertFalse(
                prompt.contains(
                        "The workspace directory is: " + workspace.toAbsolutePath() + "\n"));
        assertTrue(prompt.contains(project.toAbsolutePath().toString()));
        assertFalse(prompt.contains("Additional roots: " + workspace.toAbsolutePath()));
    }

    @Test
    void localOverlay_unrestrictedMode_describesEscapeHatch(
            @TempDir Path project, @TempDir Path workspace) {
        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .project(project)
                        .mode(LocalFsMode.UNRESTRICTED)
                        .toFilesystem(workspace, null);
        WorkspaceManager wm = track(new WorkspaceManager(workspace, fs));
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        String prompt = WorkspacePromptTestSupport.render(mw, null, "BASE\n");
        assertNotNull(prompt);
        assertTrue(
                prompt.contains("UNRESTRICTED"), () -> "UNRESTRICTED mode not surfaced: " + prompt);
    }
}
