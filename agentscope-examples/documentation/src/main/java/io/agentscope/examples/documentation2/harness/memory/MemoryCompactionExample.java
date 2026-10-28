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
package io.agentscope.examples.documentation2.harness.memory;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * MemoryCompactionExample：演示双层记忆与对话压缩。
 * <ol><li>{@code CompactionConfig} 使用较低阈值 {@code triggerMessages=6, keepMessages=2}，少量轮次后触发。
 * <li>{@code MemoryConfig} 配置 {@code flushTrigger=ALWAYS}，每次调用后将事实提取到 {@code memory/YYYY-MM-DD.md}。
 * <li>打印压缩前后上下文大小，观察摘要效果。</ol>
 * <p>运行：
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.memory.MemoryCompactionExample
 * </pre>
 */
public class MemoryCompactionExample {

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Memory — Compaction & Long-Term Memory Flush");
        System.out.println("=".repeat(60) + "\n");

        Path workspace = Files.createTempDirectory("agentscope-memory-example");

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("memory-demo")
                        .sysPrompt("You are a helpful assistant. Keep answers under two sentences.")
                        .model("dashscope:qwen-plus")
                        .workspace(workspace)
                        .compaction(
                                CompactionConfig.builder()
                                        .triggerMessages(6)
                                        .keepMessages(2)
                                        // keepTokens 默认动态模式（-1），中间件根据
                                        // 模型上下文窗口计算较大的 token 预算
                                        // （例如约 8k），总是超过此简短演示对话，
                                        // 因此按消息数量保留的窗口
                                        // keepMessages 不会生效，压缩也不会触发。
                                        // 设置 keepTokens(0) 强制按消息数保留，
                                        // 使压缩在数轮后实际触发。
                                        .keepTokens(0)
                                        .build())
                        .memory(
                                MemoryConfig.builder()
                                        .flushTrigger(MemoryConfig.FlushTrigger.always())
                                        .consolidationMinGap(Duration.ofSeconds(5))
                                        .build())
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("memory-demo-session").build();

        String[] questions = {
            "The capital of France is Paris. Remember this fact.",
            "The population of Tokyo is about 14 million. Remember this.",
            "What is the tallest building in the world?",
            "Who wrote the novel '1984'?",
            "What programming language was created by James Gosling?",
        };

        // ── 发送多轮消息以触发压缩 ──

        for (int i = 0; i < questions.length; i++) {
            System.out.printf("── Turn %d ──%n", i + 1);
            System.out.println("User: " + questions[i]);

            AgentState stateBefore = agent.getDelegate().getAgentState(ctx);
            int sizeBefore = (stateBefore != null) ? stateBefore.getContext().size() : 0;

            Msg reply = agent.call(new UserMessage(questions[i]), ctx).block();
            System.out.println("Agent: " + (reply != null ? reply.getTextContent() : "(null)"));

            AgentState stateAfter = agent.getDelegate().getAgentState(ctx);
            int sizeAfter = (stateAfter != null) ? stateAfter.getContext().size() : 0;

            System.out.printf("  context: %d → %d messages", sizeBefore, sizeAfter);
            if (sizeAfter < sizeBefore) {
                System.out.print(" ★ compaction fired!");
            }
            System.out.println();

            if (stateAfter != null
                    && stateAfter.getSummary() != null
                    && !stateAfter.getSummary().isEmpty()) {
                String summary = stateAfter.getSummary();
                if (summary.length() > 120) {
                    summary = summary.substring(0, 117) + "...";
                }
                System.out.println("  summary: " + summary);
            }
            System.out.println();
        }

        // 稍等异步记忆刷新／整合完成写入，
        // 再从磁盘读回生成的文件。
        Thread.sleep(3000);

        // ── 检查生成的记忆文件 ──

        System.out.println("── Memory files on disk ──\n");

        // 记忆文件写入由 RuntimeContext 派生的运行数据命名空间，
        // 默认 IsolationScope.USER 在没有 userId 时回退到
        // sessionId，例如 <workspace>/<sessionId>/memory/。通过智能体
        // WorkspaceManager 解析路径，确保读取位置与写入一致，
        // 而不是假定文件直接位于工作区根目录。
        Path memoryDir = agent.getWorkspaceManager().getMemoryDir(ctx);
        if (Files.isDirectory(memoryDir)) {
            Files.list(memoryDir).sorted().forEach(p -> System.out.println("  " + p.getFileName()));
        } else {
            System.out.println("  (memory/ directory not yet created)");
        }

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        Path dailyLog = memoryDir.resolve(today + ".md");
        if (Files.exists(dailyLog)) {
            String content = Files.readString(dailyLog);
            System.out.println("\n── " + dailyLog.getFileName() + " content ──");
            if (content.length() > 500) {
                System.out.println(content.substring(0, 500) + "\n... (truncated)");
            } else {
                System.out.println(content);
            }
        }

        Path memoryMd = agent.getWorkspaceManager().resolveRuntimeDataPath(ctx, "MEMORY.md");
        if (Files.exists(memoryMd)) {
            String content = Files.readString(memoryMd);
            System.out.println("\n── MEMORY.md content ──");
            if (content.length() > 500) {
                System.out.println(content.substring(0, 500) + "\n... (truncated)");
            } else {
                System.out.println(content);
            }
        }

        System.out.println("\nWorkspace: " + workspace);
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Done.");
        System.out.println("=".repeat(60));
    }
}
