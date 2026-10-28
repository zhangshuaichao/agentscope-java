/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.documentation2.skill;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.GitSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.harness.agent.middleware.AgentTraceMiddleware;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

/**
 * 演示从远程 Git 仓库加载技能。{@link GitSkillRepository} 克隆到本地临时目录并读取；每次读操作轻量检查远程引用，HEAD 变化时才 pull。
 * <p>使用公开仓库 <a href="https://github.com/agentscope-ai/skills">https://github.com/agentscope-ai/skills</a>。运行：
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.skill.GitSkillRepositoryExample
 * </pre>
 */
public class GitSkillRepositoryExample {

    private static final String SKILLS_REPO_URL = "https://github.com/agentscope-ai/skills";

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Git Skill Repository Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "This example demonstrates loading skills from a remote Git repository.\n"
                        + "Repository: "
                        + SKILLS_REPO_URL);
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        // ── 创建 Git 技能仓库 ──
        // 仓库克隆到临时目录，读取时自动同步。
        // JVM 退出或调用 close() 时清理临时目录。
        try (GitSkillRepository skillRepo = new GitSkillRepository(SKILLS_REPO_URL)) {

            // ── 列出可用技能 ──
            System.out.println("Syncing skills from remote repository...\n");
            List<String> skillNames = skillRepo.getAllSkillNames();
            System.out.println("Available skills (" + skillNames.size() + "):");
            for (String name : skillNames) {
                AgentSkill skill = skillRepo.getSkill(name);
                String desc =
                        skill != null && skill.getDescription() != null
                                ? skill.getDescription()
                                : "(no description)";
                System.out.println("  - " + name + ": " + desc);
            }

            if (skillNames.isEmpty()) {
                System.out.println("  (no skills found in repository)");
                System.out.println(
                        "\nHint: skills should be in subdirectories with a SKILL.md entry file.");
                return;
            }

            // ── 使用 Git 技能仓库构建智能体 ──
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("SkillAgent")
                            .sysPrompt(
                                    "You are a helpful assistant. Use available skills to help"
                                            + " the user.")
                            .model(
                                    DashScopeChatModel.builder()
                                            .apiKey(apiKey)
                                            .modelName("qwen-max")
                                            .stream(true)
                                            .enableThinking(false)
                                            .formatter(new DashScopeChatFormatter())
                                            .build())
                            .toolkit(new Toolkit())
                            .skillRepository(skillRepo)
                            .middleware(new AgentTraceMiddleware())
                            .build();

            // ── 交互聊天 ──
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            System.out.println("\nChat started. Type 'exit' to quit.\n");

            while (true) {
                System.out.print("You: ");
                String input = reader.readLine();
                if (input == null || input.trim().equalsIgnoreCase("exit")) {
                    System.out.println("\nGoodbye!");
                    break;
                }
                if (input.isBlank()) {
                    continue;
                }
                Msg userMsg = new UserMessage(input.trim());
                System.out.print("\nAgent: ");
                agent.streamEvents(userMsg)
                        .doOnNext(
                                event -> {
                                    if (event instanceof TextBlockDeltaEvent e) {
                                        System.out.print(e.getDelta());
                                    }
                                })
                        .blockLast();
                System.out.println("\n");
            }
        }
    }
}
