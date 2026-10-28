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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * 端到端测试：默认模式 3（{@code LocalFilesystemWithShell}）下的逐用户命名空间隔离。
 *
 * <p>在 {@link RuntimeContext} 设置 {@code userId} 时，默认 HarnessAgent 构建器（未显式设置 {@code abstractFilesystem(...)}）创建带 {@code NamespaceFactory} 的 {@code LocalFilesystemWithShell}，为路径添加 userId 前缀。本测试验证：
 * <ul>
 *   <li>运行数据（MEMORY.md、memory/、sessions、tasks）写入 {@code workspace/<userId>/}。</li>
 *   <li>不同用户看到隔离的数据。</li>
 *   <li>工作区根目录不出现重复数据，即无未加命名空间的 {@code workspace/agents/}。</li>
 *   <li>Glob/ls/grep 返回可再次传入读操作的路径，不会重复添加命名空间。</li>
 * </ul>
 */
@HarnessQuiescence
class LocalFilesystemUserIsolationExampleTest {

    @TempDir Path workspace;

    /**
     * 以 alice 身份写 MEMORY.md，验证文件落到 {@code workspace/alice/MEMORY.md}，且 bob 命名空间为空。
     */
    @Test
    void memoryIsolation_aliceAndBobSeeOwnData() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test Agent\n");

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .build()) {

            // Alice 写 MEMORY.md。
            agent.call(userMsg("alice here"), ctx("s1", "alice")).block();
            agent.getWorkspaceManager()
                    .writeUtf8WorkspaceRelative(
                            RuntimeContext.builder().userId("alice").build(),
                            "MEMORY.md",
                            "alice's notes");

            // 验证 alice 数据位于 workspace/alice/。
            Path aliceMemory = workspace.resolve("alice/MEMORY.md");
            assertTrue(
                    Files.isRegularFile(aliceMemory), "MEMORY.md should be under alice/ namespace");
            assertEquals("alice's notes", Files.readString(aliceMemory, StandardCharsets.UTF_8));

            // 验证工作区根目录不存在 MEMORY.md。
            assertFalse(
                    Files.isRegularFile(workspace.resolve("MEMORY.md")),
                    "MEMORY.md should NOT exist at workspace root when userId isolation is active");

            // 验证 bob 命名空间为空。
            assertFalse(
                    Files.exists(workspace.resolve("bob/MEMORY.md")),
                    "bob's namespace should be empty");
        }
    }

    /**
     * 验证 glob 结果可正确往返使用：glob → read → delete。返回路径不得包含命名空间前缀，使传给 read/delete 时能正确重新添加前缀。
     */
    @Test
    void globRoundTrip_pathsAreRoundTrippable() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test\n");

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .build()) {

            // 以 alice 身份写文件。
            agent.call(userMsg("hi"), ctx("s1", "alice")).block();
            AbstractFilesystem fs = agent.getWorkspaceManager().getFilesystem();
            RuntimeContext rt = RuntimeContext.builder().userId("alice").build();

            fs.uploadFiles(
                    rt,
                    List.of(
                            Map.entry("MEMORY.md", "notes".getBytes(StandardCharsets.UTF_8)),
                            Map.entry(
                                    "memory/2024-01-01.md",
                                    "daily".getBytes(StandardCharsets.UTF_8)),
                            Map.entry(
                                    "docs/readme.txt", "hello".getBytes(StandardCharsets.UTF_8))));

            // 在根层 glob 查找 *.md，应找到 MEMORY.md、memory/2024-01-01.md。
            GlobResult glob = fs.glob(rt, "*.md", "/");
            assertTrue(glob.isSuccess());
            assertFalse(glob.matches().isEmpty(), "glob should find .md files");

            for (FileInfo fi : glob.matches()) {
                String path = fi.path();
                // 路径不应包含命名空间前缀 "alice/"。
                assertFalse(
                        path.startsWith("alice/"),
                        "glob result path should not contain namespace prefix: " + path);

                // 往返使用：读取 glob 返回的路径。
                ReadResult read = fs.read(rt, path, 0, 0);
                assertTrue(
                        read.isSuccess(), "read() should succeed with glob-returned path: " + path);
                assertNotNull(read.fileData(), "fileData should not be null for: " + path);
            }

            // 在子目录内执行 glob。
            GlobResult memGlob = fs.glob(rt, "*.md", "memory/");
            assertTrue(memGlob.isSuccess());
            assertFalse(memGlob.matches().isEmpty(), "glob in memory/ should find files");
            for (FileInfo fi : memGlob.matches()) {
                ReadResult read = fs.read(rt, fi.path(), 0, 0);
                assertTrue(read.isSuccess(), "read() should succeed for: " + fi.path());
            }
        }
    }

    /**
     * 验证 grep 结果可正确往返使用。
     */
    @Test
    void grepRoundTrip_pathsAreRoundTrippable() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test\n");

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .build()) {

            agent.call(userMsg("hi"), ctx("s1", "alice")).block();
            AbstractFilesystem fs = agent.getWorkspaceManager().getFilesystem();
            RuntimeContext rt = RuntimeContext.empty();

            fs.uploadFiles(
                    rt,
                    List.of(
                            Map.entry(
                                    "memory/notes.md",
                                    "important finding".getBytes(StandardCharsets.UTF_8))));

            GrepResult grep = fs.grep(rt, "important", ".", "*.md");
            assertTrue(grep.isSuccess());
            assertFalse(grep.matches().isEmpty(), "grep should find the pattern");

            for (var match : grep.matches()) {
                String path = match.path();
                assertFalse(
                        path.startsWith("alice/"),
                        "grep result path should not contain namespace prefix: " + path);

                ReadResult read = fs.read(rt, path, 0, 0);
                assertTrue(
                        read.isSuccess(), "read() should succeed for grep-returned path: " + path);
            }
        }
    }

    /**
     * 验证 ls 结果可正确往返使用。
     */
    @Test
    void lsRoundTrip_pathsAreRoundTrippable() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test\n");

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .build()) {

            agent.call(userMsg("hi"), ctx("s1", "alice")).block();
            AbstractFilesystem fs = agent.getWorkspaceManager().getFilesystem();
            RuntimeContext rt = RuntimeContext.empty();

            fs.uploadFiles(
                    rt,
                    List.of(
                            Map.entry("docs/a.txt", "aaa".getBytes(StandardCharsets.UTF_8)),
                            Map.entry("docs/b.txt", "bbb".getBytes(StandardCharsets.UTF_8))));

            var ls = fs.ls(rt, "docs");
            assertTrue(ls.isSuccess());
            assertFalse(ls.entries().isEmpty(), "ls should list files in docs/");

            for (FileInfo fi : ls.entries()) {
                String path = fi.path();
                assertFalse(
                        path.startsWith("alice/"),
                        "ls result path should not contain namespace prefix: " + path);

                if (!path.endsWith("/")) {
                    ReadResult read = fs.read(rt, path, 0, 0);
                    assertTrue(
                            read.isSuccess(),
                            "read() should succeed for ls-returned path: " + path);
                }
            }
        }
    }

    /**
     * 验证会话数据写入用户命名空间，而非工作区根目录。
     */
    @Test
    void sessionData_writtenUnderUserNamespace() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test\n");

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .build()) {

            agent.call(userMsg("conversation start"), ctx("session-1", "alice")).block();

            // 通过工作区管理器写会话数据。
            agent.getWorkspaceManager()
                    .writeUtf8WorkspaceRelative(
                            RuntimeContext.builder().userId("alice").build(),
                            "agents/assistant/sessions/session-1.jsonl",
                            "session data");

            // 验证会话数据位于 alice 命名空间。
            Path aliceSessionFile =
                    workspace.resolve("alice/agents/assistant/sessions/session-1.jsonl");
            assertTrue(
                    Files.isRegularFile(aliceSessionFile),
                    "AgentStateStore file should be under alice/ namespace");

            // 验证工作区根目录无会话文件。
            Path rootSessionFile = workspace.resolve("agents/assistant/sessions/session-1.jsonl");
            assertFalse(
                    Files.isRegularFile(rootSessionFile),
                    "AgentStateStore file should NOT exist at workspace root");
        }
    }

    /**
     * 验证 getSessionDir 返回考虑命名空间的路径。
     */
    @Test
    void sessionDir_isNamespaceAware() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test\n");

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .build()) {

            agent.call(userMsg("hi"), ctx("s1", "alice")).block();

            Path sessionDir =
                    agent.getWorkspaceManager()
                            .getSessionDir(
                                    RuntimeContext.builder().userId("alice").build(), "assistant");
            assertTrue(
                    sessionDir.toString().contains("alice"),
                    "getSessionDir should include namespace: " + sessionDir);
        }
    }

    /**
     * 验证 getMemoryDir 返回考虑命名空间的路径。
     */
    @Test
    void memoryDir_isNamespaceAware() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test\n");

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .build()) {

            agent.call(userMsg("hi"), ctx("s1", "alice")).block();

            Path memDir =
                    agent.getWorkspaceManager()
                            .getMemoryDir(RuntimeContext.builder().userId("alice").build());
            assertTrue(
                    memDir.toString().contains("alice"),
                    "getMemoryDir should include namespace: " + memDir);
        }
    }

    /**
     * 验证工作区根目录不出现未加命名空间的重复用户数据。
     *
     * <p>默认 {@code AgentStateStore} 现为以 {@code ~/.agentscope/state/<agentId>/} 为根的 {@code JsonFileAgentStateStore}，所以 {@code agent_state.json} 不再位于工作区内。孤儿扫描协调使用进程内 {@code PeriodicGate}，因此单用户调用后 {@code workspace/agents/} 不应包含普通文件。
     */
    @Test
    void noDuplicateDataAtWorkspaceRoot() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test\n");

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .build()) {

            agent.call(userMsg("hi"), ctx("s1", "alice")).block();

            // 写入记忆与会话数据。
            agent.getWorkspaceManager()
                    .writeUtf8WorkspaceRelative(
                            RuntimeContext.builder().userId("alice").build(),
                            "MEMORY.md",
                            "memory content");
            agent.getWorkspaceManager()
                    .writeUtf8WorkspaceRelative(
                            RuntimeContext.builder().userId("alice").build(),
                            "memory/note.md",
                            "note");

            // 工作区根目录应只有预先存在的共享配置文件 AGENTS.md，
            // 命名空间隔离的运行数据应位于 alice/ 下。
            try (Stream<Path> rootEntries = Files.list(workspace)) {
                List<String> rootNames =
                        rootEntries
                                .map(p -> p.getFileName().toString())
                                .filter(n -> !n.equals("AGENTS.md") && !n.equals("alice"))
                                .toList();
                assertTrue(
                        rootNames.isEmpty(),
                        "Only AGENTS.md and alice/ should exist at workspace root, but found: "
                                + rootNames);
            }

            // agent_state.json 已迁移到 ~/.agentscope/state/<agentId>/，不再位于
            // 工作区内，因此 agents/ 下出现任何普通文件都属于回归。
            Path rootAgents = workspace.resolve("agents");
            if (Files.isDirectory(rootAgents)) {
                try (Stream<Path> walk = Files.walk(rootAgents)) {
                    List<String> unexpected =
                            walk.filter(Files::isRegularFile)
                                    .map(
                                            p ->
                                                    rootAgents
                                                            .relativize(p)
                                                            .toString()
                                                            .replace('\\', '/'))
                                    .toList();
                    assertTrue(
                            unexpected.isEmpty(),
                            "workspace/agents/ should contain no regular files but found: "
                                    + unexpected);
                }
            }
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
