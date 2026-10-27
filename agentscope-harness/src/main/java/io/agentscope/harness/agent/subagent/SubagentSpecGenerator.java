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
package io.agentscope.harness.agent.subagent;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * 由 LLM 驱动的 {@link SubagentDeclaration} Markdown 规格生成器。
 *
 * <p>根据自由文本描述的智能体用途，提示所提供的 {@link Model}
 * 输出符合 {@link AgentSpecLoader} schema 的带 YAML 前置元数据的 Markdown 文档。
 * 输出可通过 {@link AgentSpecLoader#parse} 重新解析。
 *
 * <p>这是 opencode 的 {@code Agent.generate} 流程
 * （{@code opencode/packages/opencode/src/agent/agent.ts}）的 Java 对应实现。
 * 用于从 CLI 脚手架调用，或在实时会话中由
 * {@link io.agentscope.harness.agent.tool.AgentGenerateTool} 调用。
 *
 * <p>复用 {@link io.agentscope.harness.agent.memory.compaction.ConversationCompactor}
 * 进行单次 LLM 调用时采用的 {@code model.stream(messages, null, null).reduce(...)}
 * 模式，使行为与 Harness 中其他内部 LLM 使用方保持一致。
 */
public final class SubagentSpecGenerator {

    private static final String PROMPT_TEMPLATE =
            """
            You are designing a subagent specification for the agentscope-java framework.

            User description of the agent's purpose:
            ---
            %s
            ---

            Existing agent ids (DO NOT reuse, even with different casing): %s

            Produce a Markdown document with YAML frontmatter that matches this schema:

            ---
            description: <one sentence describing when the orchestrator should delegate to this agent>
            mode: subagent
            hidden: false
            # Optional model hyperparameters (omit to inherit parent):
            # temperature: <0.0..2.0>
            # top_p: <0.0..1.0>
            # steps: <positive integer, default 10>
            # tools: [<inherited tool name>, ...]   # optional allowlist; empty = inherit all
            ---

            <System-prompt body in Markdown: describe the agent's role, capabilities, output
             format, and constraints. Keep the body focused — this becomes the subagent's sysPrompt.>

            Output rules:
            - Return ONLY the markdown document. No prose, no code fences, no commentary.
            - Do NOT include a `name:` field — the caller derives the name from the filename.
            - The description must be at most 200 characters.
            - The body must not be empty when no workspace.path is specified.
            """;

    private final Model model;

    public SubagentSpecGenerator(Model model) {
        this.model = Objects.requireNonNull(model, "model");
    }

    /**
     * 生成新子智能体的 Markdown 规格，不执行验证。除非需要在交给
     * {@link AgentSpecLoader} 前检查或调整 Markdown，否则优先使用
     * {@link #generateAndValidate}。
     *
     * @param description 对智能体应做什么的自由文本描述
     * @param existingIds 已注册的名称；会要求模型避免使用它们
     * @return 模型返回的原始 Markdown（去除首尾空白，除此之外不处理）
     */
    public Mono<String> generateMarkdown(String description, Collection<String> existingIds) {
        String existingList =
                existingIds == null || existingIds.isEmpty()
                        ? "(none)"
                        : String.join(", ", existingIds);
        String prompt = String.format(PROMPT_TEMPLATE, description, existingList);
        List<Msg> input =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text(prompt).build())
                                .build());
        return model.stream(input, null, null)
                .reduce(
                        new StringBuilder(),
                        (sb, resp) -> {
                            if (resp.getContent() != null) {
                                for (ContentBlock block : resp.getContent()) {
                                    if (block instanceof TextBlock tb && tb.getText() != null) {
                                        sb.append(tb.getText());
                                    }
                                }
                            }
                            return sb;
                        })
                .map(StringBuilder::toString)
                .map(String::strip);
    }

    /**
     * 生成 Markdown 规格，并通过 {@link AgentSpecLoader#parse} 重新解析以验证。
     * LLM 输出格式错误（缺少前置元数据结束标记、缺少必填的 {@code description} 等）
     * 时发出 {@link IllegalStateException}。
     *
     * @param description 自由文本描述
     * @param agentName 赋给已解析声明的名称（也会与 {@code existingIds} 一同保留，避免冲突）
     * @param existingIds 现有智能体 ID
     * @param mainWorkspace 用于解析相对 {@code workspace.path} 的工作区根目录；
     *     可以为 {@code null}
     * @return 原始 Markdown 与已解析 {@link SubagentDeclaration} 组成的二元组
     */
    public Mono<GeneratedSpec> generateAndValidate(
            String description,
            String agentName,
            Collection<String> existingIds,
            Path mainWorkspace) {
        return generateMarkdown(description, existingIds)
                .map(
                        md -> {
                            SubagentDeclaration decl =
                                    AgentSpecLoader.parse(md, agentName, mainWorkspace);
                            if (decl == null) {
                                throw new IllegalStateException(
                                        "LLM produced a malformed subagent spec for '"
                                                + agentName
                                                + "'; could not parse frontmatter");
                            }
                            return new GeneratedSpec(md, decl);
                        });
    }

    /**
     * 原始 Markdown 与已解析 {@link SubagentDeclaration} 的组合。
     */
    public record GeneratedSpec(String markdown, SubagentDeclaration declaration) {}
}
