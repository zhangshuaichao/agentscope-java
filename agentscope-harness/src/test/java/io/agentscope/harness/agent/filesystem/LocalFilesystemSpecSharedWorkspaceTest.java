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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 本地模式主机／应用读写不对称（#3245）的回归测试：上层为相对路径添加逐用户命名空间前缀，导致主机写到工作区根目录的文件对智能体工具不可见，而智能体写入落到 {@code {workspace}/{userId}/} 下。
 */
class LocalFilesystemSpecSharedWorkspaceTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    @TempDir Path workspace;
    @TempDir Path project;

    @Test
    void sharedLocalWorkspaceResolvesWorkspaceRootWithoutUserPrefix() throws Exception {
        Path uploaded = workspace.resolve("uploads/result.md");
        Files.createDirectories(uploaded.getParent());
        Files.writeString(uploaded, "host content");

        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .project(project)
                        .sharedLocalWorkspace(true)
                        .toFilesystem(workspace, rc -> List.of("local-user"));

        ReadResult read = fs.read(RT, "uploads/result.md", 0, 0);
        assertTrue(read.isSuccess(), () -> "host-written file must be readable: " + read.error());
        assertTrue(read.fileData().content().contains("host content"));

        assertTrue(fs.write(RT, "out/agent.md", "from agent").isSuccess());
        assertTrue(Files.isRegularFile(workspace.resolve("out/agent.md")));
        assertFalse(Files.exists(workspace.resolve("local-user")));
    }

    @Test
    void defaultKeepsPerUserPrefix() {
        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .project(project)
                        .toFilesystem(workspace, rc -> List.of("local-user"));

        assertTrue(fs.write(RT, "out/agent.md", "from agent").isSuccess());
        assertTrue(Files.isRegularFile(workspace.resolve("local-user/out/agent.md")));
    }

    @Test
    void projectWritableWithSharedLocalWorkspaceRoutesWritesWithoutUserPrefix() {
        // 固定覆盖 ProjectAwareOverlay 分支：实际生效的 null 命名空间工厂
        // 同时传入 projectFs 和覆盖层构造方法（#3247 审查）。
        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .project(project)
                        .projectWritable(true)
                        .sharedLocalWorkspace(true)
                        .toFilesystem(workspace, rc -> List.of("local-user"));

        // 非工作区写入路由到项目根目录，不添加 {userId}/ 前缀目录。
        assertTrue(fs.write(RT, "docs/report.md", "shared write").isSuccess());
        assertTrue(Files.isRegularFile(project.resolve("docs/report.md")));
        assertFalse(Files.exists(project.resolve("local-user")));

        // 工作区元数据写入仍落到共享工作区根目录。
        assertTrue(fs.write(RT, "MEMORY.md", "mem").isSuccess());
        assertTrue(Files.isRegularFile(workspace.resolve("MEMORY.md")));
        assertFalse(Files.exists(workspace.resolve("local-user")));
    }
}
