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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceScaffolderTest {

    @TempDir Path tmp;

    @Test
    void writesExpectedLayout() throws Exception {
        WorkspaceScaffolder.scaffold(tmp, "Helper", "You answer questions about cats.");

        // 标准目录始终创建，使 UI 文件树展示统一布局。
        assertThat(tmp.resolve("skills")).isDirectory();
        assertThat(tmp.resolve("subagents")).isDirectory();
        assertThat(tmp.resolve("memory")).isDirectory();

        // 默认初始内容来自 {@code src/main/resources/scaffold/default/}，
        // 按原样复制到工作区；这些断言防止资源漏打包
        // 或资源到工作区的复制链断裂。
        assertThat(tmp.resolve("AGENTS.md")).exists();
        assertThat(tmp.resolve("tools.json")).exists();
        assertThat(tmp.resolve("skills/example-skill/SKILL.md")).exists();
        assertThat(tmp.resolve("subagents/README.md")).exists();
        assertThat(tmp.resolve("memory/.gitkeep")).exists();

        // AGENTS.md 从 {@code AGENTS.md.template} 渲染，传入身份
        // 替换 {@code {{NAME}}}／{@code {{SYSPROMPT}}} 占位符。
        String agentsMd = Files.readString(tmp.resolve("AGENTS.md"));
        assertThat(agentsMd).contains("# Helper");
        assertThat(agentsMd).contains("You answer questions about cats.");
        assertThat(agentsMd).contains("`tools.json`");
        assertThat(agentsMd).contains("`skills/`");
        // 占位符必须完全替换；残留 {@code {{...}}} 表示模板
        // 仅被复制而没有真正渲染。
        assertThat(agentsMd).doesNotContain("{{NAME}}");
        assertThat(agentsMd).doesNotContain("{{SYSPROMPT}}");

        String toolsJson = Files.readString(tmp.resolve("tools.json"));
        assertThat(toolsJson).contains("\"allow\"");
        assertThat(toolsJson).contains("read_file");
        // Claw 的 tools.json 比 builder 版本多出 {@code mcpServers}，
        // 断言类路径资源仍包含它，确保运行时 MCP 装配。
        assertThat(toolsJson).contains("\"mcpServers\"");

        // 抽查复制后仍有可识别初始内容，
        // 便于想定制默认值的操作者找到来源。
        String exampleSkill = Files.readString(tmp.resolve("skills/example-skill/SKILL.md"));
        assertThat(exampleSkill).contains("Example Skill");
        // Claw 示例技能保留 YAML 前置信息块（{@code name:}／{@code
        // description:}），
        // 让解析器无需额外修改即可加载。
        assertThat(exampleSkill).contains("name: example-skill");
        String subagentsReadme = Files.readString(tmp.resolve("subagents/README.md"));
        assertThat(subagentsReadme).contains("Subagents");
    }

    @Test
    void doesNotOverwriteExistingFiles() throws Exception {
        Files.createDirectories(tmp);
        Files.writeString(tmp.resolve("AGENTS.md"), "# Custom\nedited by user\n");

        WorkspaceScaffolder.scaffold(tmp, "Helper", "default");

        String agentsMd = Files.readString(tmp.resolve("AGENTS.md"));
        assertThat(agentsMd).isEqualTo("# Custom\nedited by user\n");
        // tools.json 原本不存在，仍应由初始化生成。
        assertThat(tmp.resolve("tools.json")).exists();
    }
}
