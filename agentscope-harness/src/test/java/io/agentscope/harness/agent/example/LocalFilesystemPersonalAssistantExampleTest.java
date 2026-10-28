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
package io.agentscope.harness.agent.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * 示例：使用 {@link LocalFilesystemWithShell} 的完全本地个人助理模式。
 *
 * <h2>背景</h2>
 * <p>此模式适合运行于开发者机器或单进程环境的<em>个人本地助理</em>：
 * <ul>
 *   <li>全部文件 I/O 直接访问磁盘上的本地工作区目录。</li>
 *   <li>Shell 命令通过 {@code ProcessBuilder} 在该目录执行。</li>
 *   <li>无沙箱容器、分布式存储或外部依赖。</li>
 *   <li>状态以磁盘文件形式自然跨调用保留。</li>
 *   <li>改变 {@link RuntimeContext} 中的 {@code userId} 或 {@code sessionId} <em>不会</em>将 I/O 重定向到其他位置，工作区目录始终相同。</li>
 * </ul>
 * <h2>取舍</h2>
 * <ul>
 *   <li><b>优点</b>：无需基础设施，立即可用，完全本地控制。</li>
 *   <li><b>缺点</b>：用户或会话之间不隔离，不支持水平扩展。</li>
 * </ul>
 * <p>通过 {@link HarnessAgent.Builder#abstractFilesystem} 配置指向所需工作区目录的 {@link LocalFilesystemWithShell} 实例。
 */
@HarnessQuiescence
class LocalFilesystemPersonalAssistantExampleTest {

    @TempDir Path workspace;

    /**
     * 演示一次调用写入工作区的文件保存在磁盘上，后续调用仍可读取，这是本地模式最基本的特征。
     */
    @Test
    void localFilesystem_filesPersistAcrossCalls() throws Exception {
        Files.createDirectories(workspace);

        // 使用 LocalFilesystemWithShell 存储构建智能体。
        // 没有分布式存储或沙箱，全部操作直接访问磁盘。
        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("my-local-assistant")
                        .model(stubModel("done"))
                        .workspace(workspace.toAbsolutePath().normalize().toString())
                        .abstractFilesystem(new LocalFilesystemWithShell(workspace))
                        .build()) {

            // 调用 1：通过工作区管理器写笔记。有意使用普通用户文件，
            // 因为 MEMORY.md 由智能体管理，记忆整合会在调用后异步改写它，
            // 所以其字节内容无法保证跨调用稳定。
            agent.call(userMsg("first call"), ctx("session-1", "alice")).block();
            agent.getWorkspaceManager()
                    .writeUtf8WorkspaceRelative(
                            RuntimeContext.empty(), "notes.md", "# Notes\n- item 1");

            // 调用 1 后文件存在于磁盘。
            Path notesFile = workspace.resolve("notes.md");
            assertTrue(
                    Files.isRegularFile(notesFile), "notes.md should exist on disk after call 1");
            String content = Files.readString(notesFile, StandardCharsets.UTF_8);
            assertTrue(content.contains("item 1"), "notes.md content should be persisted on disk");

            // 调用 2：相同工作区、不同会话，文件仍在。
            agent.call(userMsg("second call"), ctx("session-2", "alice")).block();
            assertTrue(
                    Files.isRegularFile(notesFile),
                    "notes.md should still exist on disk in call 2");
            assertEquals(
                    content,
                    Files.readString(notesFile, StandardCharsets.UTF_8),
                    "notes.md content should be unchanged after call 2");
        }
    }

    /**
     * 演示本地模式下改变 {@code userId} 或 {@code sessionId} 不会将 I/O 重定向到不同位置。
     *
     * <p>这是与沙箱／远程模式的关键区别：此处工作区目录固定，所有用户和会话共享同一磁盘位置。
     */
    @Test
    void localFilesystem_workspaceIsNotPartitionedByUserOrSession() throws Exception {
        Files.createDirectories(workspace);

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("my-local-assistant")
                        .model(stubModel("done"))
                        .workspace(workspace.toAbsolutePath().normalize().toString())
                        .abstractFilesystem(new LocalFilesystemWithShell(workspace))
                        .build()) {

            // Alice 在她的会话中写入。
            agent.call(userMsg("alice here"), ctx("session-alice", "alice")).block();
            agent.getWorkspaceManager()
                    .writeUtf8WorkspaceRelative(
                            RuntimeContext.empty(), "shared.txt", "alice was here");

            // Bob 以不同 userId 调用，仍读取相同工作区。
            agent.call(userMsg("bob here"), ctx("session-bob", "bob")).block();
            Path sharedFile = workspace.resolve("shared.txt");
            assertTrue(
                    Files.isRegularFile(sharedFile),
                    "shared.txt written by alice should be visible in the same workspace, "
                            + "regardless of userId or sessionId");
        }
    }

    /**
     * 演示底层工作区目录是普通本地文件路径，可与智能体并行使用标准 Java I/O 读写文件。
     */
    @Test
    void localFilesystem_directDiskAccessFromHostProcess() throws Exception {
        Files.createDirectories(workspace);

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("my-local-assistant")
                        .model(stubModel("done"))
                        .workspace(workspace.toAbsolutePath().normalize().toString())
                        .abstractFilesystem(new LocalFilesystemWithShell(workspace))
                        .build()) {

            // 从主机进程写文件，模拟用户向工作区
            // 放入文档。
            Path doc = workspace.resolve("document.txt");
            Files.writeString(doc, "Host-written document content");

            // 智能体通过工作区管理器可以看到该文件。
            agent.call(userMsg("check document"), ctx("s1", "user")).block();
            String read =
                    agent.getWorkspaceManager()
                            .readManagedWorkspaceFileUtf8(RuntimeContext.empty(), "document.txt");
            assertNotNull(
                    read, "agent should be able to read files written directly to the workspace");
            assertTrue(read.contains("Host-written"), "agent should see the host-written content");
        }
    }

    // -------------------------------------------------------------------------
    // 辅助方法
    // -------------------------------------------------------------------------

    private static RuntimeContext ctx(String sessionId, String userId) {
        return RuntimeContext.builder().sessionId(sessionId).userId(userId).build();
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static Model stubModel(String text) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text(text).build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }
}
