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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * {@link HarnessAgent} 的集成式示例：真实磁盘工作区、完整构建器装配，以及端到端 {@link #call(Msg, RuntimeContext)} 路径。
 *
 * <p>测试在 {@link TempDir} 中创建的示例工作区布局：
 *
 * <pre>
 * workspace/
 * ├── AGENTS.md              # persona / local rules
 * ├── MEMORY.md              # optional long-term scratch (loaded into &lt;memory_context&gt;)
 * ├── knowledge/
 * │   └── KNOWLEDGE.md       # optional domain summary
 * └── subagents/
 *     ├── helper.md          # YAML front matter + body as sys prompt
 *     └── reviewer.md        # second spec example
 * </pre>
 *
 * <p>上图中文对照：AGENTS.md 保存角色设定与本地规则；MEMORY.md 是可选的长期记忆草稿，加载到 memory_context 段落；knowledge/KNOWLEDGE.md 是可选的领域摘要。subagents/helper.md 使用 YAML 前置元数据和正文定义系统提示词，reviewer.md 展示第二份子智能体定义。
 *
 * <p>使用占位 {@link Model}，无需 API 密钥。{@code integration} 标签便于在 IDE 或以后配置 {@code groups} 时通过 JUnit Platform 筛选。
 */
@Tag("integration")
@HarnessQuiescence
class HarnessAgentIntegrationExampleTest {

    @TempDir Path workspace;

    /** 创建上方布局、构建主智能体并运行一轮，断言占位回复；捕获的模型输入用于检查会话、子智能体文档和工作区文件已进入 LLM 消息列表。 */
    @Test
    void example_fullWorkspace_singleTurn_seesSessionSubagentsAndWorkspaceContext()
            throws Exception {
        String agentsPersona = "INTEGRATION_AGENTS_PERSONA_001";
        String memoryNote = "INTEGRATION_MEMORY_NOTE_002";
        String knowledgeLine = "INTEGRATION_KNOWLEDGE_LINE_003";
        String helperSubId = "integration-helper-sub";
        String reviewerSubId = "integration-md-sub";

        Files.createDirectories(workspace);
        Files.writeString(
                workspace.resolve(WorkspaceConstants.AGENTS_MD),
                "# Agent\n\n" + agentsPersona + "\n");
        Files.writeString(workspace.resolve(WorkspaceConstants.MEMORY_MD), memoryNote);

        Path knowledgeDir = workspace.resolve(WorkspaceConstants.KNOWLEDGE_DIR);
        Files.createDirectories(knowledgeDir);
        Files.writeString(knowledgeDir.resolve(WorkspaceConstants.KNOWLEDGE_MD), knowledgeLine);

        Path subagentsDir = workspace.resolve("subagents");
        Files.createDirectories(subagentsDir);
        // 去掉 .md 的文件名即子智能体名称；前置元数据无需 name: 字段
        Files.writeString(
                subagentsDir.resolve(helperSubId + ".md"),
                """
                ---
                description: First markdown-defined helper for integration example
                ---
                Reply with YAML_OK only.
                """);
        Files.writeString(
                subagentsDir.resolve(reviewerSubId + ".md"),
                """
                ---
                description: Second markdown-defined helper for integration example
                maxIters: 5
                ---

                You only reply MD_OK.
                """);

        Model model = stubModel("integration-main-reply");
        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("integration-main")
                        .description("integration example main agent")
                        .sysPrompt("You are the main agent in an integration test.")
                        .model(model)
                        .workspace(workspace)
                        .build()) {
            Msg reply =
                    agent.call(
                                    userText("Run the integration scenario."),
                                    RuntimeContext.builder()
                                            .sessionId("integration-session-1")
                                            .build())
                            .block();

            assertTrue(reply.getTextContent().contains("integration-main-reply"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
            verify(model, atLeast(1)).stream(captor.capture(), any(), any());
            String combined =
                    captor.getAllValues().stream()
                            .map(HarnessAgentIntegrationExampleTest::joinAllText)
                            .filter(s -> s.contains("## Runtime Environment"))
                            .findFirst()
                            .orElse("");

            assertTrue(
                    combined.contains("## Runtime Environment"),
                    "Runtime environment should be injected; model saw: "
                            + captor.getAllValues().stream()
                                    .map(HarnessAgentIntegrationExampleTest::joinAllText)
                                    .toList());
            // 最终构建器分别渲染工作区指令与引用。
            assertTrue(
                    combined.contains("## Domain Knowledge") || combined.contains("## Workspace"),
                    "expected workspace guidance sections");
            assertTrue(combined.contains("<project_rules "));
            assertTrue(
                    combined.contains(agentsPersona),
                    "AGENTS.md should appear under workspace hook");
            assertTrue(combined.contains("memory_context") || combined.contains("MEMORY.md"));
            assertTrue(combined.contains(memoryNote));
            assertTrue(
                    combined.contains("domain_knowledge_context")
                            || combined.contains("KNOWLEDGE.md"));
            assertTrue(combined.contains(knowledgeLine));
            assertTrue(
                    combined.contains("## Subagents") || combined.contains("Subagents:"),
                    "subagent list should be injected into the system prompt");
            assertTrue(combined.contains("`" + helperSubId + "`"));
            assertTrue(combined.contains("`" + reviewerSubId + "`"));
        }
    }

    /** 使用与构建器相同的工作区发现机制，获取 {@code subagents/} 下 Markdown 定义 ID 对应的 {@link SubagentEntry}，执行 {@code factory().create()} 和 {@link Agent#call(List)}，验证委托的 {@link HarnessAgent} 已采用定义中的名称和提示词。 */
    @Test
    void example_subagentFactory_markdownSpec_runsChildHarnessAgent() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve(WorkspaceConstants.AGENTS_MD), "# root\n");

        // 去掉 .md 的文件名即子智能体名称；前置元数据无需 name: 字段
        String childId = "integration-child-spawn";
        Path subagentsDir = workspace.resolve("subagents");
        Files.createDirectories(subagentsDir);
        Files.writeString(
                subagentsDir.resolve(childId + ".md"),
                """
                ---
                description: Child agent for factory integration
                ---
                Child system prompt marker INTEGRATION_CHILD_SYS
                """);

        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse childChunk =
                new ChatResponse(
                        "child-id",
                        List.of(TextBlock.builder().text("integration-child-reply").build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(childChunk));

        List<SubagentEntry> entries =
                HarnessAgent.builder()
                        .model(model)
                        .workspace(workspace)
                        .buildSubagentEntries(workspace);

        SubagentEntry child =
                entries.stream()
                        .filter(e -> childId.equals(e.name()))
                        .findFirst()
                        .orElseThrow(
                                () -> new AssertionError("missing subagent entry: " + childId));

        Agent createdSubagent = child.factory().create(RuntimeContext.empty());
        try (HarnessAgent sub = assertInstanceOf(HarnessAgent.class, createdSubagent)) {
            assertEquals(childId, sub.getName());

            Msg subReply = sub.call(List.of(userText("task for child"))).block();
            assertTrue(subReply.getTextContent().contains("integration-child-reply"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
            verify(model, atLeast(1)).stream(captor.capture(), any(), any());
            boolean childSysSeen =
                    captor.getAllValues().stream()
                            .map(HarnessAgentIntegrationExampleTest::joinAllText)
                            .anyMatch(s -> s.contains("INTEGRATION_CHILD_SYS"));
            assertTrue(
                    childSysSeen,
                    "child HarnessAgent should use spec sysPrompt in its system prompt bundle");
        }
    }

    private static Msg userText(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static String joinAllText(List<Msg> msgs) {
        return msgs.stream().map(Msg::getTextContent).collect(Collectors.joining("\n"));
    }

    private static Model stubModel(String assistantText) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text(assistantText).build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }
}
