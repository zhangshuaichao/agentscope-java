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
package io.agentscope.claw2.web.scaffold;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 从类路径 {@code scaffold/default/} 复制资源到目标目录，为默认 claw 智能体建立工作区，仅在文件缺失时写入。
 *
 * <p>生成布局均位于传入的 {@code workspace} 根目录下：
 *
 * <ul>
 *   <li>{@code AGENTS.md}：根据 {@code scaffold/default/AGENTS.md.template} 生成，将 {@code displayName} / {@code sysPrompt} 替换到 {@code {{NAME}}} 与 {@code {{SYSPROMPT}}} 占位符。
 *   <li>{@code tools.json}、{@code skills/example-skill/SKILL.md}、{@code subagents/README.md}：从 {@code scaffold/default/} 下对应资源原样复制。
 *   <li>{@code skills/}、{@code subagents/}、{@code memory/}：空目录，使用户添加内容前 UI 文件树也显示标准位置。
 *   <li>{@code memory/.gitkeep}：空标记文件，使工作区经 Git 克隆后仍保留 {@code memory/} 目录。
 * </ul>
 *
 * <p>除 AGENTS.md 占位符外，内容均保存在磁盘资源中，而非 Java 字符串。定制默认内容应修改 {@code src/main/resources/scaffold/default/} 资源，不必修改 Java 代码。目标工作区中已存在的文件不会覆盖，因此重复调用时已有内容保持不变。
 *
 * <p>用户选择的研究助手、客服等起始模板另见 {@link io.agentscope.claw2.web.template.TemplateRegistry}；这是 UI 中显式选择的路径，与自动脚手架分离。
 */
public final class WorkspaceScaffolder {

    private static final String RESOURCE_ROOT = "scaffold/default";

    /** 从 {@code scaffold/default/<rel>} 原样复制到 {@code workspace/<rel>} 的文件。此列表应保持精简，因为每个新工作区都会包含这些内容；更丰富的起始包应优先由用户选择的 {@link io.agentscope.claw2.web.template.TemplateRegistry} 提供。 */
    private static final List<String> VERBATIM_RESOURCES =
            List.of("tools.json", "skills/example-skill/SKILL.md", "subagents/README.md");

    private WorkspaceScaffolder() {}

    /**
     * 为智能体建立工作区目录。可重复调用：只创建尚不存在的文件。
     *
     * @param workspace 目标工作区目录；不存在时创建
     * @param displayName 替换到 AGENTS.md 标题的可读智能体名称
     * @param sysPrompt 替换到 AGENTS.md 的可选系统提示词正文（可为 {@code null}）
     */
    public static void scaffold(Path workspace, String displayName, String sysPrompt)
            throws IOException {
        Files.createDirectories(workspace);
        Files.createDirectories(workspace.resolve("skills"));
        Files.createDirectories(workspace.resolve("subagents"));
        Files.createDirectories(workspace.resolve("memory"));

        writeIfMissing(workspace.resolve("AGENTS.md"), renderAgentsMd(displayName, sysPrompt));
        for (String rel : VERBATIM_RESOURCES) {
            writeIfMissing(workspace.resolve(rel), readResource(RESOURCE_ROOT + "/" + rel));
        }
        // 空文件不携带内容，无需加入资源树；
        // {@code .gitkeep} 只需存在，即可让 {@code git} 跟踪原本为空的
        // {@code memory/} 目录。
        writeIfMissing(workspace.resolve("memory").resolve(".gitkeep"), "");
    }

    /** 加载 AGENTS.md 模板资源，将智能体身份替换到 {@code {{NAME}}} / {@code {{SYSPROMPT}}} 占位符。输入为空白时分别回退为 {@code "agent"} 和 {@code "You are a helpful assistant."}，使未提供定制值的调用方也能生成内容。 */
    private static String renderAgentsMd(String displayName, String sysPrompt) throws IOException {
        String name = (displayName == null || displayName.isBlank()) ? "agent" : displayName;
        String prompt =
                (sysPrompt == null || sysPrompt.isBlank())
                        ? "You are a helpful assistant."
                        : sysPrompt.trim();
        String template = readResource(RESOURCE_ROOT + "/AGENTS.md.template");
        return template.replace("{{NAME}}", name).replace("{{SYSPROMPT}}", prompt);
    }

    /** 将 UTF-8 类路径资源加载为字符串；缺失时抛出 {@link IOException}，使资源重命名或打包遗漏等错误在启动时明确暴露，而不是静默生成空工作区。 */
    private static String readResource(String classpathPath) throws IOException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = WorkspaceScaffolder.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(classpathPath)) {
            if (in == null) {
                throw new IOException(
                        "Missing scaffold resource on classpath: '"
                                + classpathPath
                                + "' (expected under src/main/resources/"
                                + classpathPath
                                + ")");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void writeIfMissing(Path file, String content) throws IOException {
        if (Files.exists(file)) return;
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
