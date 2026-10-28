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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 测试 {@link LocalFilesystem#read} 中三种 {@link LocalFsMode} 路径处理：SANDBOXED 拒绝逃逸的绝对路径，ROOTED 拒绝策略根目录之外的路径，UNRESTRICTED 接受任意绝对路径。
 */
class LocalFilesystemModeTest {

    @Test
    void edit_rejectsNullNewString_andEmptyOldString(@TempDir Path workspace) throws IOException {
        Path file = workspace.resolve("f.txt");
        Files.writeString(file, "Hello World");
        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.UNRESTRICTED, null, 10, null);
        RuntimeContext rt = RuntimeContext.empty();

        // null 不表示删除，应传入 ""；所有文件系统保持一致。
        EditResult nullNew = fs.edit(rt, file.toString(), "World", null, false);
        assertFalse(nullNew.isSuccess());
        assertTrue(nullNew.error().contains("must not be null"));
        assertEquals("Hello World", Files.readString(file), "file must be untouched");

        // "" 才表示真正删除，仍保留正常匹配次数。
        EditResult emptyNew = fs.edit(rt, file.toString(), "World", "", false);
        assertTrue(emptyNew.isSuccess(), () -> "expected success, got: " + emptyNew.error());
        assertEquals(1, emptyNew.occurrences());
        assertEquals("Hello ", Files.readString(file));

        // 空 oldString 会让出现次数统计无限循环。
        EditResult emptyOld = fs.edit(rt, file.toString(), "", "x", false);
        assertFalse(emptyOld.isSuccess());
        assertEquals("Hello ", Files.readString(file), "file must be untouched");
    }

    @Test
    void sandboxed_rejectsAbsolutePathOutsideRoot(@TempDir Path workspace, @TempDir Path other)
            throws IOException {
        Path outsideFile = other.resolve("secret.txt");
        Files.writeString(outsideFile, "leaked", StandardCharsets.UTF_8);

        LocalFilesystem fs = new LocalFilesystem(workspace, LocalFsMode.SANDBOXED, null, 10, null);
        ReadResult r = fs.read(RuntimeContext.empty(), outsideFile.toString(), 0, 0);
        assertFalse(r.isSuccess());
        assertNotNull(r.error());
        // SANDBOXED 将所有路径解析到工作区下；绝对路径变成根目录内
        // 不存在的相对路径。
        assertTrue(r.error().toLowerCase().contains("not found"));
    }

    @Test
    void rooted_acceptsAbsolutePathUnderPolicyRoot(@TempDir Path workspace, @TempDir Path project)
            throws IOException {
        Path projectFile = project.resolve("AGENTS.md");
        Files.writeString(projectFile, "hello", StandardCharsets.UTF_8);

        PathPolicy policy = PathPolicy.of(project, workspace);
        LocalFilesystem fs = new LocalFilesystem(workspace, LocalFsMode.ROOTED, policy, 10, null);

        ReadResult r = fs.read(RuntimeContext.empty(), projectFile.toString(), 0, 0);
        assertTrue(r.isSuccess(), () -> "expected success, got: " + r.error());
        assertEquals("hello", r.fileData().content());
    }

    @Test
    void rooted_rejectsAbsolutePathOutsideAllRoots(
            @TempDir Path workspace, @TempDir Path project, @TempDir Path forbidden)
            throws IOException {
        Path outsideFile = forbidden.resolve("secret.txt");
        Files.writeString(outsideFile, "leaked", StandardCharsets.UTF_8);

        PathPolicy policy = PathPolicy.of(project, workspace);
        LocalFilesystem fs = new LocalFilesystem(workspace, LocalFsMode.ROOTED, policy, 10, null);

        Throwable t =
                org.junit.jupiter.api.Assertions.assertThrows(
                        SecurityException.class,
                        () -> fs.read(RuntimeContext.empty(), outsideFile.toString(), 0, 0));
        assertTrue(t.getMessage().contains(outsideFile.toString()));
    }

    @Test
    void unrestricted_acceptsAnyAbsolutePath(@TempDir Path workspace, @TempDir Path elsewhere)
            throws IOException {
        Path file = elsewhere.resolve("note.txt");
        Files.writeString(file, "free", StandardCharsets.UTF_8);

        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.UNRESTRICTED, null, 10, null);

        ReadResult r = fs.read(RuntimeContext.empty(), file.toString(), 0, 0);
        assertTrue(r.isSuccess(), () -> "expected success, got: " + r.error());
        assertEquals("free", r.fileData().content());
    }

    @Test
    void rooted_workspaceItselfIsImplicitlyAllowed(@TempDir Path workspace) throws IOException {
        Path file = workspace.resolve("inside.txt");
        Files.writeString(file, "here", StandardCharsets.UTF_8);

        // 空策略：仅隐式接受 cwd 根目录。
        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);

        ReadResult r = fs.read(RuntimeContext.empty(), file.toString(), 0, 0);
        assertTrue(r.isSuccess());
        assertEquals("here", r.fileData().content());
    }

    @Test
    void legacy_booleanConstructor_mapsTrueToSandboxed(@TempDir Path workspace, @TempDir Path other)
            throws IOException {
        Path outsideFile = other.resolve("secret.txt");
        Files.writeString(outsideFile, "leaked", StandardCharsets.UTF_8);

        LocalFilesystem fs = new LocalFilesystem(workspace, true, 10, null);
        ReadResult r = fs.read(RuntimeContext.empty(), outsideFile.toString(), 0, 0);
        assertFalse(r.isSuccess(), "SANDBOXED should refuse outside absolute path");
    }

    @Test
    void legacy_booleanConstructor_mapsFalseToUnrestricted(
            @TempDir Path workspace, @TempDir Path elsewhere) throws IOException {
        Path file = elsewhere.resolve("note.txt");
        Files.writeString(file, "free", StandardCharsets.UTF_8);

        LocalFilesystem fs = new LocalFilesystem(workspace, false, 10, null);
        ReadResult r = fs.read(RuntimeContext.empty(), file.toString(), 0, 0);
        assertTrue(r.isSuccess(), "UNRESTRICTED should accept any absolute path");
        assertEquals("free", r.fileData().content());
    }

    // ==================== 命名空间与绝对路径测试 ====================

    private static final NamespaceFactory USER_NS = rc -> List.of("user-1");

    @Test
    void rooted_absolutePathNotCorruptedByNamespace(@TempDir Path workspace, @TempDir Path project)
            throws IOException {
        Path projectFile = project.resolve("Main.java");
        Files.writeString(projectFile, "class Main {}", StandardCharsets.UTF_8);

        PathPolicy policy = PathPolicy.of(project, workspace);
        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, policy, 10, USER_NS);
        RuntimeContext rc = RuntimeContext.builder().userId("user-1").build();

        ReadResult r = fs.read(rc, projectFile.toString(), 0, 0);
        assertTrue(
                r.isSuccess(), () -> "absolute path should resolve correctly, got: " + r.error());
        assertEquals("class Main {}", r.fileData().content());
    }

    @Test
    void rooted_absolutePathLsNotCorruptedByNamespace(
            @TempDir Path workspace, @TempDir Path project) throws IOException {
        Path subDir = project.resolve("src");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("App.java"), "class App {}", StandardCharsets.UTF_8);

        PathPolicy policy = PathPolicy.of(project, workspace);
        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, policy, 10, USER_NS);
        RuntimeContext rc = RuntimeContext.builder().userId("user-1").build();

        LsResult r = fs.ls(rc, subDir.toString());
        assertTrue(r.isSuccess());
        assertFalse(r.entries().isEmpty(), "ls should find files in the project directory");
    }

    @Test
    void rooted_relativePathStillNamespaced(@TempDir Path workspace) throws IOException {
        Path nsDir = workspace.resolve("user-1");
        Files.createDirectories(nsDir);
        Files.writeString(nsDir.resolve("notes.md"), "hello", StandardCharsets.UTF_8);

        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, USER_NS);
        RuntimeContext rc = RuntimeContext.builder().userId("user-1").build();

        ReadResult r = fs.read(rc, "notes.md", 0, 0);
        assertTrue(r.isSuccess(), () -> "relative path should be namespaced, got: " + r.error());
        assertEquals("hello", r.fileData().content());
    }

    // ==================== ROOTED 模式与前导 "/" 路径 ====================

    @Test
    void rooted_leadingSlashResolvesRelativeToWorkspace(@TempDir Path workspace)
            throws IOException {
        // 在工作区创建 "skills" 子目录。
        Path skillsDir = workspace.resolve("skills");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("tool.md"), "skill content", StandardCharsets.UTF_8);

        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);

        // "/skills" 应解析到 <workspace>/skills，而不是主机绝对路径。
        ReadResult r = fs.read(RuntimeContext.empty(), "/skills/tool.md", 0, 0);
        assertTrue(
                r.isSuccess(),
                () -> "leading '/' should resolve relative to workspace, got: " + r.error());
        assertEquals("skill content", r.fileData().content());
    }

    @Test
    void rooted_leadingSlashLsWorks(@TempDir Path workspace) throws IOException {
        // 创建含文件的 "skills" 子目录。
        Path skillsDir = workspace.resolve("skills");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("tool1.md"), "content1", StandardCharsets.UTF_8);
        Files.writeString(skillsDir.resolve("tool2.md"), "content2", StandardCharsets.UTF_8);

        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);

        // "/skills" 应列出 <workspace>/skills 内的文件。
        LsResult r = fs.ls(RuntimeContext.empty(), "/skills");
        assertTrue(r.isSuccess(), () -> "ls with leading '/' should work, got: " + r.error());
        assertFalse(r.entries().isEmpty(), "ls should find files in the skills directory");
        assertEquals(2, r.entries().size());
    }

    @Test
    void rooted_leadingSlashAloneResolvesToWorkspace(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("root.txt"), "root content", StandardCharsets.UTF_8);

        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);

        // "/" 应解析到工作区根目录。
        ReadResult r = fs.read(RuntimeContext.empty(), "/root.txt", 0, 0);
        assertTrue(r.isSuccess(), () -> "'/' should resolve to workspace root, got: " + r.error());
        assertEquals("root content", r.fileData().content());
    }

    @Test
    void rooted_leadingSlashWithNamespace(@TempDir Path workspace) throws IOException {
        // 存在命名空间时，"/skills" 仍应解析到 <workspace>/skills，而非命名空间内。
        Path skillsDir = workspace.resolve("skills");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("tool.md"), "global skill", StandardCharsets.UTF_8);

        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, USER_NS);
        RuntimeContext rc = RuntimeContext.builder().userId("user-1").build();

        // 绝对路径（以 "/" 开头）不应受命名空间限定。
        ReadResult r = fs.read(rc, "/skills/tool.md", 0, 0);
        assertTrue(
                r.isSuccess(), () -> "absolute path should not be namespaced, got: " + r.error());
        assertEquals("global skill", r.fileData().content());
    }

    @Test
    void rooted_leadingSlashAllowsLiteralDotDotInPathName(@TempDir Path workspace)
            throws IOException {
        Path dir = workspace.resolve("some..dir");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("note.txt"), "literal name", StandardCharsets.UTF_8);

        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);

        ReadResult r = fs.read(RuntimeContext.empty(), "/some..dir/note.txt", 0, 0);
        assertTrue(
                r.isSuccess(),
                () -> "literal '..' inside a path segment should be allowed, got: " + r.error());
        assertEquals("literal name", r.fileData().content());
    }

    @Test
    void rooted_leadingSlashPathTraversalRejected(@TempDir Path workspace) {
        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);

        // 应拒绝带前导 "/" 的路径遍历。
        assertThrows(
                IllegalArgumentException.class,
                () -> fs.read(RuntimeContext.empty(), "/../etc/passwd", 0, 0));
    }

    // ==================== 缺陷复现：ls 静默吞掉错误 ====================

    @Test
    void ls_nonExistentPath_shouldReturnFail(@TempDir Path workspace) {
        LocalFilesystem fs = new LocalFilesystem(workspace);
        LsResult r = fs.ls(RuntimeContext.empty(), "/this/path/does/not/exist");
        assertFalse(r.isSuccess(), "ls on non-existent path should fail");
    }

    @Test
    void ls_filePath_shouldReturnFail(@TempDir Path workspace) throws IOException {
        Path file = workspace.resolve("foo.txt");
        Files.writeString(file, "content");
        LocalFilesystem fs = new LocalFilesystem(workspace);
        LsResult r = fs.ls(RuntimeContext.empty(), file.toAbsolutePath().toString());
        assertFalse(r.isSuccess(), "ls on a file path should fail");
    }

    @Test
    void rooted_relativePathTraversalRejected(@TempDir Path base) throws IOException {
        Path workspace = base.resolve("workspace");
        Files.createDirectories(workspace);
        Path outsideFile = base.resolve("secret.txt");
        Files.writeString(outsideFile, "secret", StandardCharsets.UTF_8);
        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);

        assertThrows(
                IllegalArgumentException.class,
                () -> fs.read(RuntimeContext.empty(), "../secret.txt", 0, 0));
    }

    @Test
    void rooted_relativeUploadTraversalRejected(@TempDir Path base) throws IOException {
        Path workspace = base.resolve("workspace");
        Files.createDirectories(workspace);
        Path escaped = base.resolve("escape.txt");
        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);

        var responses =
                fs.uploadFiles(
                        RuntimeContext.empty(),
                        List.of(
                                Map.entry(
                                        "../escape.txt",
                                        "escape".getBytes(StandardCharsets.UTF_8))));

        assertEquals(1, responses.size());
        assertFalse(responses.get(0).isSuccess());
        assertEquals("permission_denied", responses.get(0).error());
        assertFalse(Files.exists(escaped));
    }

    @Test
    void rooted_relativeTraversalCannotEscapeUserNamespace(@TempDir Path workspace)
            throws IOException {
        Files.createDirectories(workspace.resolve("user-1"));
        Files.createDirectories(workspace.resolve("user-2"));
        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, USER_NS);
        RuntimeContext rc = RuntimeContext.builder().userId("user-1").build();

        var responses =
                fs.uploadFiles(
                        rc,
                        List.of(
                                Map.entry(
                                        "../user-2/escape.txt",
                                        "escape".getBytes(StandardCharsets.UTF_8))));

        assertEquals(1, responses.size());
        assertFalse(responses.get(0).isSuccess());
        assertFalse(Files.exists(workspace.resolve("user-2/escape.txt")));
    }

    @Test
    void rooted_literalDoubleDotInRelativeSegmentAllowed(@TempDir Path workspace)
            throws IOException {
        Path file = workspace.resolve("some..dir/note.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "literal name", StandardCharsets.UTF_8);
        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);

        ReadResult result = fs.read(RuntimeContext.empty(), "some..dir/note.txt", 0, 0);

        assertTrue(result.isSuccess());
        assertEquals("literal name", result.fileData().content());
    }
}
