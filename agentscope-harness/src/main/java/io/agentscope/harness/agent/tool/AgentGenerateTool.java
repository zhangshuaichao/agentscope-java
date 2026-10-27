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
package io.agentscope.harness.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentSpecGenerator;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 根据自然语言描述生成新子智能体规格的工具，可将其持久化到
 * {@code subagents/<name>.md}（由 {@link io.agentscope.harness.agent.middleware.DynamicSubagentsMiddleware}
 * 在下一推理步骤加载），或返回 Markdown 供人工审阅。
 *
 * <p>包装 {@link SubagentSpecGenerator}，负责名称验证、与实时
 * {@link DefaultAgentManager} 注册表的冲突检测，以及试运行处理。
 *
 * <p>{@link io.agentscope.harness.agent.middleware.SubagentsMiddleware} 默认不注册此工具；
 * 调用方必须通过 {@code SubagentsMiddleware#enableAgentGenerateTool(...)} 显式启用。
 */
public class AgentGenerateTool {

    private static final Logger log = LoggerFactory.getLogger(AgentGenerateTool.class);

    /**
     * 子智能体 ID 必须为 kebab-case 标识符，与 {@code AgentSpecLoader} 的要求一致。
     */
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z][a-z0-9-]{0,62}");

    private final SubagentSpecGenerator generator;
    private final DefaultAgentManager agentManager;
    private final AbstractFilesystem filesystem;

    public AgentGenerateTool(
            SubagentSpecGenerator generator,
            DefaultAgentManager agentManager,
            AbstractFilesystem filesystem) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.agentManager = Objects.requireNonNull(agentManager, "agentManager");
        this.filesystem = filesystem;
    }

    @Tool(
            name = "agent_generate",
            description =
                    "Generate a new subagent spec from a natural-language description. Validates"
                        + " the LLM output against the SubagentDeclaration schema and writes it to"
                        + " subagents/<name>.md so DynamicSubagentsMiddleware picks it up on the"
                        + " next reasoning step. Use dry_run=true to preview the markdown without"
                        + " writing.")
    public Mono<ToolResultBlock> agentGenerate(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "name",
                            description =
                                    "Kebab-case subagent id (e.g. code-reviewer). Must not"
                                            + " collide with an existing agent.")
                    String name,
            @ToolParam(
                            name = "description",
                            description =
                                    "What the agent should do — its purpose, expected output, and"
                                            + " when to use it.")
                    String description,
            @ToolParam(
                            name = "dry_run",
                            description =
                                    "If true, return the generated markdown without persisting it"
                                            + " (default false).",
                            required = false)
                    Boolean dryRun) {

        if (name == null || name.isBlank()) {
            return Mono.just(ToolResultBlock.error("name is required"));
        }
        String trimmed = name.trim();
        if (!NAME_PATTERN.matcher(trimmed).matches()) {
            return Mono.just(
                    ToolResultBlock.error(
                            "name '"
                                    + trimmed
                                    + "' is not a valid kebab-case identifier (lowercase, digits,"
                                    + " '-')"));
        }
        if (description == null || description.isBlank()) {
            return Mono.just(ToolResultBlock.error("description is required"));
        }
        if (agentManager.hasAgent(trimmed)) {
            return Mono.just(ToolResultBlock.error("agent '" + trimmed + "' already exists"));
        }
        boolean dry = Boolean.TRUE.equals(dryRun);

        return generator
                .generateAndValidate(
                        description,
                        trimmed,
                        agentManager.getAgentFactories().keySet(),
                        agentManager.getWorkspaceManager() != null
                                ? agentManager.getWorkspaceManager().getWorkspace()
                                : null)
                .map(
                        spec -> {
                            if (dry) {
                                return ToolResultBlock.success(
                                        "dry_run=true; spec below was NOT persisted\n\n"
                                                + spec.markdown());
                            }
                            if (filesystem == null) {
                                return ToolResultBlock.error(
                                        "no filesystem configured for AgentGenerateTool — cannot"
                                                + " persist spec. Use dry_run=true to preview.");
                            }
                            String path = "subagents/" + trimmed + ".md";
                            WriteResult wr =
                                    filesystem.write(runtimeContext, path, spec.markdown());
                            if (!wr.isSuccess()) {
                                log.warn(
                                        "Failed to write generated subagent spec to {}: {}",
                                        path,
                                        wr.error());
                                return ToolResultBlock.error(
                                        "write failed for "
                                                + path
                                                + ": "
                                                + wr.error()
                                                + "\n\nGenerated spec:\n"
                                                + spec.markdown());
                            }
                            return ToolResultBlock.success(
                                    "Wrote subagent spec to "
                                            + wr.path()
                                            + "\n\n"
                                            + spec.markdown());
                        })
                .onErrorResume(
                        e -> {
                            String msg =
                                    e.getMessage() != null
                                            ? e.getMessage()
                                            : e.getClass().getSimpleName();
                            log.warn("agent_generate failed for name={}: {}", trimmed, msg);
                            return Mono.just(ToolResultBlock.error(msg));
                        });
    }
}
