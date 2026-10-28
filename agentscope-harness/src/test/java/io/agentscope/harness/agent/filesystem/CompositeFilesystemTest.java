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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证 {@link CompositeFilesystem#uploadFiles} 如实传递各后端逐文件响应（成功或失败），而不是将全部结果包装成成功。
 */
class CompositeFilesystemTest {

    private static final RuntimeContext CTX = RuntimeContext.empty();

    @TempDir Path tmp;

    @Test
    void uploadFiles_propagatesBackendFailure() {
        AbstractFilesystem failingBackend = mock(AbstractFilesystem.class);
        when(failingBackend.uploadFiles(any(), anyList()))
                .thenReturn(List.of(FileUploadResponse.fail("/notes.md", "disk full")));

        AbstractFilesystem defaultBackend = mock(AbstractFilesystem.class);

        CompositeFilesystem composite =
                new CompositeFilesystem(defaultBackend, Map.of("/memories/", failingBackend));

        List<FileUploadResponse> resp =
                composite.uploadFiles(
                        CTX,
                        List.of(
                                Map.entry(
                                        "/memories/notes.md",
                                        "hello".getBytes(StandardCharsets.UTF_8))));

        assertEquals(1, resp.size());
        FileUploadResponse r = resp.get(0);
        assertFalse(r.isSuccess(), "backend failure must propagate to caller");
        assertEquals(
                "/memories/notes.md",
                r.path(),
                "response path must be the caller's view, not the backend-internal path");
        assertEquals("disk full", r.error(), "backend error message must propagate verbatim");
    }

    @Test
    void uploadFiles_propagatesBackendSuccess() {
        AbstractFilesystem store = mock(AbstractFilesystem.class);
        when(store.uploadFiles(any(), anyList()))
                .thenReturn(List.of(FileUploadResponse.success("/notes.md")));

        AbstractFilesystem defaultBackend = mock(AbstractFilesystem.class);

        CompositeFilesystem composite =
                new CompositeFilesystem(defaultBackend, Map.of("/memories/", store));

        List<FileUploadResponse> resp =
                composite.uploadFiles(
                        CTX,
                        List.of(
                                Map.entry(
                                        "/memories/notes.md",
                                        "hello".getBytes(StandardCharsets.UTF_8))));

        assertEquals(1, resp.size());
        FileUploadResponse r = resp.get(0);
        assertTrue(r.isSuccess());
        assertEquals("/memories/notes.md", r.path());
    }

    /**
     * 技能市场安装缺陷的回归测试：{@code AgentSkillsController} 通过 {@code WorkspaceManager.writeUtf8WorkspaceRelative} 写入（去掉前导斜杠，以 {@code "skills/foo/SKILL.md"} 上传），随后立即通过 {@link AbstractFilesystem} 契约使用带前导斜杠的 {@code "/skills/foo/SKILL.md"} 回读。无论前导斜杠约定如何，两次调用必须路由到同一后端；否则读取落到默认存储并失败，Mono 不发出值，前端遇到空响应体出错。
     */
    @Test
    void routes_normalizeLeadingSlash_betweenWriteAndRead() throws Exception {
        Path workspace = tmp.resolve("workspace");
        Path skillsBacking = tmp.resolve("skills-backing");
        Files.createDirectories(workspace);
        Files.createDirectories(skillsBacking);

        LocalFilesystem defaultBackend = new LocalFilesystem(workspace, false, 10, null);
        LocalFilesystem skillsBackend = new LocalFilesystem(skillsBacking, true, 10, null);

        // 注册路由时不带前导斜杠，与 RemoteFilesystemSpec 的约定一致。
        CompositeFilesystem composite =
                new CompositeFilesystem(defaultBackend, Map.of("skills/", skillsBackend));

        // 按 WorkspaceManager 风格写入，不带前导斜杠。
        List<FileUploadResponse> upload =
                composite.uploadFiles(
                        CTX,
                        List.of(
                                Map.entry(
                                        "skills/foo/SKILL.md",
                                        "hello".getBytes(StandardCharsets.UTF_8))));
        assertTrue(upload.get(0).isSuccess(), "upload should succeed via prefix route");

        // 按 AbstractFilesystem 契约读取，带前导斜杠，必须命中同一存储。
        ReadResult viaSlash = composite.read(CTX, "/skills/foo/SKILL.md", 0, Integer.MAX_VALUE);
        assertTrue(viaSlash.isSuccess(), "leading-slash read must route to skills backend");
        assertEquals("hello", viaSlash.fileData().content());

        // 不带前导斜杠的形式也必须继续可用。
        ReadResult viaNoSlash = composite.read(CTX, "skills/foo/SKILL.md", 0, Integer.MAX_VALUE);
        assertTrue(viaNoSlash.isSuccess());
        assertEquals("hello", viaNoSlash.fileData().content());

        // exists() 对两种约定必须给出一致结果。
        assertTrue(composite.exists(CTX, "/skills/foo/SKILL.md"));
        assertTrue(composite.exists(CTX, "skills/foo/SKILL.md"));
    }

    /**
     * 工作区文件树缺陷的回归测试：平台 {@code AgentWorkspaceController#tree} 等列出整个工作区的调用方使用 {@code glob("**&#47;*", "/")}。Java NIO 的 {@code PathMatcher} 对 {@code **&#47;*} 要求至少一个分隔符，因此仅靠递归匹配器无法匹配根层精确文件路由（如 {@code AGENTS.md} / {@code MEMORY.md} / {@code tools.json}）。组合文件系统必须提供去掉前导 {@code **&#47;} 的直接匹配后备逻辑，使深度为 1 的条目也可见，否则工作区树 UI 会静默遗漏这些文件。
     */
    @Test
    void globRecursiveRoot_includesExactFileRoutes() throws Exception {
        Path workspace = tmp.resolve("workspace");
        Files.createDirectories(workspace);
        // 被精确文件路由覆盖的工作区根层模板文件。
        Files.writeString(workspace.resolve("AGENTS.md"), "# Agents");
        Files.writeString(workspace.resolve("MEMORY.md"), "# Memory");
        Files.writeString(workspace.resolve("tools.json"), "{}");
        // 另加入带子文件的前缀路由目录，确保两种形式共存。
        Path skillsDir = workspace.resolve("skills");
        Files.createDirectories(skillsDir.resolve("example"));
        Files.writeString(skillsDir.resolve("example").resolve("SKILL.md"), "skill");

        LocalFilesystem defaultBackend = new LocalFilesystem(workspace, false, 10, null);
        // 精确文件路由由以工作区为根的 virtualMode LocalFilesystem 支持，
        // 所以 `exists("/AGENTS.md")` 解析到 `workspace/AGENTS.md`，对应
        // {@link
        // io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec#exactFileOverlay}.
        LocalFilesystem workspaceTemplate = new LocalFilesystem(workspace, true, 10, null);
        LocalFilesystem skillsRoute = new LocalFilesystem(skillsDir, true, 10, null);

        // 保留声明顺序，使精确文件路由（"AGENTS.md" 等）在相关迭代中
        // 排在前缀路由之前。
        Map<String, AbstractFilesystem> routes = new LinkedHashMap<>();
        routes.put("AGENTS.md", workspaceTemplate);
        routes.put("MEMORY.md", workspaceTemplate);
        routes.put("tools.json", workspaceTemplate);
        routes.put("skills/", skillsRoute);
        CompositeFilesystem composite = new CompositeFilesystem(defaultBackend, routes);

        GlobResult gr = composite.glob(CTX, "**/*", "/");
        assertTrue(gr.isSuccess(), "recursive root glob must succeed");
        List<String> paths = gr.matches().stream().map(FileInfo::path).collect(Collectors.toList());

        // 根层精确文件路由必须出现在文件树中，这正是
        // 工作区树 UI 展示且此前回归缺陷遗漏的内容。
        assertTrue(paths.contains("AGENTS.md"), "AGENTS.md missing from glob result: " + paths);
        assertTrue(paths.contains("MEMORY.md"), "MEMORY.md missing from glob result: " + paths);
        assertTrue(paths.contains("tools.json"), "tools.json missing from glob result: " + paths);
        // 前缀路由内容必须继续可用。
        assertTrue(
                paths.stream().anyMatch(p -> p.endsWith("skills/example/SKILL.md")),
                "prefix-route child missing: " + paths);

        // 基本检查：更精确的 glob 仍正确过滤，根层 *.json
        // 匹配 tools.json 而不匹配 AGENTS.md。
        GlobResult jsonGlob = composite.glob(CTX, "**/*.json", "/");
        List<String> jsonPaths =
                jsonGlob.matches().stream().map(FileInfo::path).collect(Collectors.toList());
        assertTrue(jsonPaths.contains("tools.json"));
        assertFalse(
                jsonPaths.contains("AGENTS.md"), "AGENTS.md must not match *.json: " + jsonPaths);
    }
}
