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
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.harness.agent.middleware.AgentTraceMiddleware;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * SkillWithToolGroupExample：结合 {@link FileSystemSkillRepository} 与 {@link io.agentscope.core.tool.SkillToolGroup}。
 * <p>{@link io.agentscope.core.tool.SkillToolGroup} 将工具组绑定到技能名；加载名称匹配 {@code activateOnSkill} 的技能后，工具组自动对模型可见。下方依次为模式、技能文件结构及运行命令：
 * <pre>
 *   toolkit.createSkillToolGroup("analysis-tools", "Data analysis tools", false, "data-analysis");
 *   toolkit.registration().tool(new AnalysisTools()).group("analysis-tools").apply();
 *   // 智能体加载 "data-analysis" SKILL.md → 通过 activateOnSkill 激活 "analysis-tools" 组
 * </pre>
 * <pre>
 * ---
 * name: data-analysis
 * description: Statistical data analysis skill
 * ---
 * Use analyze_data and summarize tools when processing datasets.
 * </pre>
 * <p>技能文件中文对照：name 是技能名 data-analysis；description 表示“统计数据分析技能”；正文要求处理数据集时使用 analyze_data 与 summarize 工具。原 YAML 及工具标识保留不变。
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.skill.SkillWithToolGroupExample
 * </pre>
 */
public class SkillWithToolGroupExample {

    /**
     * 包含 {@code data-analysis/SKILL.md} 入口的技能目录，按工作区布局调整。
     */
    private static final String SKILLS_DIR =
            "agentscope-examples/documentation/src/main/resources/skills";

    /**
     * 激活工具组的技能名，必须匹配 SKILL.md 的 {@code name:} 字段。
     */
    private static final String ACTIVATING_SKILL = "data-analysis";

    /**
     * 绑定技能的工具组名称，可任意命名；加载匹配 {@code activateOnSkill} 的技能时自动激活。
     */
    private static final String TOOL_GROUP = "data-analysis-tools";

    /**
     * 运行技能与工具组示例。
     * @param args 命令行参数，此处忽略
     * @throws Exception I/O 错误时
     */
    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Skill + ToolGroup Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates FileSystemSkillRepository combined with a SkillToolGroup.\n"
                        + "The '"
                        + TOOL_GROUP
                        + "' group activates automatically when the\n"
                        + "'"
                        + ACTIVATING_SKILL
                        + "' skill is loaded by the agent.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        Path skillsDir = Paths.get(SKILLS_DIR).toAbsolutePath().normalize();
        if (!Files.isDirectory(skillsDir)) {
            System.out.println("SKILLS_DIR not found: " + skillsDir);
            System.out.println(
                    "Please set SKILLS_DIR to a directory containing a "
                            + ACTIVATING_SKILL
                            + "/SKILL.md entry.");
            return;
        }

        // ── 1. 创建工具包及工具组 ──
        //
        // createSkillToolGroup(name, description, activeByDefault, activateOnSkill)
        // activateOnSkill：DynamicSkillMiddleware 加载同名技能时，
        // 该组激活，对模型可见。
        Toolkit toolkit = new Toolkit();
        toolkit.createSkillToolGroup(
                TOOL_GROUP,
                "Tools exposed when the '" + ACTIVATING_SKILL + "' skill is active",
                false, // 初始不活动，由技能激活，而非始终开启
                ACTIVATING_SKILL);

        // ── 2. 将工具注册到技能绑定组 ──
        toolkit.registration().tool(new DataTools()).group(TOOL_GROUP).apply();

        // ── 3. 注册始终活动、不分组的普通工具 ──
        toolkit.registerTool(new InfoTool());

        // ── 4. 组装技能仓库 ──
        //
        // FileSystemSkillRepository(baseDir, writeable)
        // writeable=false：技能只读，智能体不能创建新技能
        FileSystemSkillRepository skillRepo = new FileSystemSkillRepository(skillsDir, false);

        // ── 5. 构建智能体 ──
        //
        // .skillRepository(repo) 自动组装 DynamicSkillMiddleware，
        // 在调用中重建技能提示并激活已加载技能的 SkillToolGroup。
        // .enableMetaTool(true) 添加元工具，让智能体检查活动工具组。
        ReActAgent agent =
                ReActAgent.builder()
                        .name("SkillToolGroupAgent")
                        .sysPrompt(
                                "You are a helpful assistant. Use available skills and tools "
                                        + "as appropriate for the user's request.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .enableThinking(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .skillRepository(skillRepo)
                        .enableMetaTool(true)
                        .middleware(new AgentTraceMiddleware())
                        .build();

        System.out.println("Loaded skill repository: " + skillsDir);
        System.out.println(
                "SkillToolGroup '" + TOOL_GROUP + "' activates on skill: " + ACTIVATING_SKILL);
        System.out.println("\nTry these prompts:");
        System.out.println("  1. \"Analyze the numbers 5, 12, 3, 8, 15, 27\"");
        System.out.println("  2. \"What tools do you have?\"");
        System.out.println("  3. \"What time is it?\"");
        System.out.println("  4. \"Summarize the analysis and save it to a file\"");
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

    /**
     * 注册在技能绑定组中的工具，仅技能激活时可见。
     */
    public static class DataTools {

        /**
         * 分析数字列表，返回基本统计信息。
         * @param numbers 逗号分隔的数字
         * @return 统计摘要
         */
        @Tool(name = "analyze_data", description = "Compute basic statistics for a list of numbers")
        public String analyzeData(
                @ToolParam(name = "numbers", description = "Comma-separated numbers")
                        String numbers) {
            try {
                String[] parts = numbers.split(",");
                double[] vals = new double[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    vals[i] = Double.parseDouble(parts[i].trim());
                }
                double sum = 0, min = vals[0], max = vals[0];
                for (double v : vals) {
                    sum += v;
                    if (v < min) min = v;
                    if (v > max) max = v;
                }
                double avg = sum / vals.length;
                return String.format(
                        "Count: %d | Sum: %.2f | Min: %.2f | Max: %.2f | Avg: %.2f",
                        vals.length, sum, min, max, avg);
            } catch (NumberFormatException e) {
                return "Error: invalid input — provide comma-separated numbers";
            }
        }

        /**
         * 将文本摘要写入临时文件。
         * @param content 待写文本
         * @return 文件路径或错误消息
         */
        @Tool(name = "write_summary", description = "Write a summary to a temp file")
        public String writeSummary(
                @ToolParam(name = "content", description = "Summary text") String content) {
            try {
                Path tmp = Files.createTempFile("summary-", ".txt");
                Files.writeString(tmp, content);
                return "Summary written to: " + tmp;
            } catch (IOException e) {
                return "Error writing summary: " + e.getMessage();
            }
        }
    }

    /**
     * 普通工具，不受技能是否激活影响，始终可见。
     */
    public static class InfoTool {

        /**
         * 返回当前 UTC 时间戳。
         * @return ISO-8601 时间戳
         */
        @Tool(name = "get_timestamp", description = "Get the current UTC timestamp")
        public String getTimestamp() {
            return java.time.Instant.now().toString();
        }
    }
}
