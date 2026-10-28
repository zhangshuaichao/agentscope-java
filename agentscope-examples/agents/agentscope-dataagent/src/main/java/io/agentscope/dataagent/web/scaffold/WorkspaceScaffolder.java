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
package io.agentscope.dataagent.web.scaffold;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 写入界面编辑的 LangSmith-Fleet 风格工作区布局：{@code
 * AGENTS.md}、{@code tools.json}、示例技能及 {@code subagents/}/{@code memory/} 目录。
 * 不覆盖已有文件，在已填充工作区调用时，对已有内容不做修改。
 * <p>模板刻意给出明确约定，让新用户无需先读外部文档即可理解工作区结构。
 */
public final class WorkspaceScaffolder {

    private WorkspaceScaffolder() {}

    /**
     * 生成智能体工作区目录。可安全重复调用：仅创建尚不存在的文件。
     * @param workspace 目标工作区目录，不存在时创建
     * @param displayName 用于生成 AGENTS.md 标题的可读智能体名称
     * @param sysPrompt 写入 AGENTS.md 的可选系统提示词正文，可为 {@code null}
     */
    public static void scaffold(Path workspace, String displayName, String sysPrompt)
            throws IOException {
        Files.createDirectories(workspace);
        Files.createDirectories(workspace.resolve("skills"));
        Files.createDirectories(workspace.resolve("subagents"));
        Files.createDirectories(workspace.resolve("memory"));

        writeIfMissing(workspace.resolve("AGENTS.md"), agentsMd(displayName, sysPrompt));
        writeIfMissing(workspace.resolve("tools.json"), toolsJson());
        writeIfMissing(
                workspace.resolve("skills").resolve("example-skill").resolve("SKILL.md"),
                exampleSkillMd());
        writeIfMissing(workspace.resolve("subagents").resolve("README.md"), subagentsReadme());
        writeIfMissing(workspace.resolve("memory").resolve(".gitkeep"), "");
    }

    private static void writeIfMissing(Path file, String content) throws IOException {
        if (Files.exists(file)) return;
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String agentsMd(String displayName, String sysPrompt) {
        String name = (displayName == null || displayName.isBlank()) ? "agent" : displayName;
        String prompt =
                (sysPrompt == null || sysPrompt.isBlank())
                        ? "You are a helpful assistant."
                        : sysPrompt.trim();
        return """
        # %s

        %s

        ## How this folder works

        This folder *is* the agent. Anything you put here is picked up at runtime — there is
        no separate config to keep in sync.

        - **`AGENTS.md`** — this file. Edit the system prompt and behavioral rules in
          place; they are loaded on the next session.
        - **`tools.json`** — declare which built-in tools the agent may call. See the
          generated file for the available tool ids and an `allow` / `deny` example.
        - **`skills/`** — each subfolder is a skill: a Markdown playbook the agent can
          invoke by name. A starter `example-skill/SKILL.md` is included.
        - **`subagents/`** — sub-agent definitions for delegated work. See
          `subagents/README.md`.
        - **`memory/`** — long-term memory store managed by the runtime; you usually do
          not edit it by hand.

        ## Authoring tips

        - Keep this prompt focused on *what the agent does* and *how it should behave*.
          Push examples, schemas, and one-shot instructions into skills.
        - When you change tools or skills, current sessions keep their old wiring until
          reset; new sessions pick up the change immediately.
        """
                .formatted(name, prompt);
    }

    private static String toolsJson() {
        return """
        {
          "// description": "Builder tools allowlist for this agent. Remove this file to allow all tools.",
          "// available":   ["read_file", "write_file", "edit_file", "list_files", "grep_files", "glob_files", "execute", "memory_search", "memory_get", "session_search", "session_list", "session_history"],
          "allow": [
            "read_file",
            "write_file",
            "edit_file",
            "list_files",
            "grep_files",
            "glob_files"
          ],
          "deny": []
        }
        """;
    }

    private static String exampleSkillMd() {
        return """
        # Example Skill

        A skill is a named playbook the agent can invoke by referring to this file. The
        folder name (`example-skill`) is the skill id.

        ## When to use

        Describe the situations in which the agent should reach for this skill. Be
        concrete — the runtime feeds this section back to the agent when it is selecting
        between skills.

        ## Steps

        1. State the inputs the skill needs.
        2. Describe the work — what files to read, what to write, what to summarize.
        3. State the expected output format.

        Delete this file once you have authored your own skills.
        """;
    }

    private static String subagentsReadme() {
        return """
        # Subagents

        Each `*.md` file in this directory defines a subagent the parent agent may
        delegate to. The file name (without extension) is the subagent id; the front
        matter declares its system prompt and tool allowlist.

        Example skeleton:

        ```markdown
        ---
        name: researcher
        description: Investigates a question and returns a written summary.
        tools: [read_file, grep_files, glob_files]
        ---

        You are a research specialist. Stay focused on the task you receive; do not
        edit files directly.
        ```

        Delete this README once you have added real subagents.
        """;
    }
}
