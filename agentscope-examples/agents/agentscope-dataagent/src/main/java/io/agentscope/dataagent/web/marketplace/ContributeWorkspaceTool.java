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
package io.agentscope.dataagent.web.marketplace;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.dataagent.web.persistence.jpa.ContributionEntity;
import io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 向智能体提供的工作区贡献提案工具，提议将文件提升到共享工作区。记录 {@code PENDING} 的 {@link ContributionEntity}，管理员批准后才将载荷写入 {@code shared/agents/<targetAgentId>/<type>/<path>}。<p>不同于原实现，<em>不要求</em>模型内联文件内容：模型只在 {@code source_paths} 中列出工作区相对源路径，工具通过 {@link WorkspaceManagerFactory} 从调用方沙箱读取字节。避免模型工具调用序列化时截断大载荷，也让管理员看到贡献者磁盘上的确切内容。<p>多文件技能包（{@code target_type=skill}）使用逗号分隔的多个 {@code source_paths}，各文件按 basename 放入包中；含子目录的布局更适合使用网页文件树选择器。<p>由 {@link ContributionToolRegistrar} 在启动时以单例注册到内置 {@code data-agent} 主智能体。 */
public final class ContributeWorkspaceTool {

    private final MarketContributionService service;
    private final WorkspaceManagerFactory workspaceFactory;

    public ContributeWorkspaceTool(
            MarketContributionService service, WorkspaceManagerFactory workspaceFactory) {
        this.service = Objects.requireNonNull(service, "service");
        this.workspaceFactory = Objects.requireNonNull(workspaceFactory, "workspaceFactory");
    }

    @Tool(
            name = "contribute_to_workspace",
            description =
                    """
                    Nominate one or more workspace files (skill, sub-agent, memory snippet, \
                    AGENTS.md, or knowledge document) for promotion to the agent's shared \
                    workspace, so other users of the same agent can benefit from them. The tool \
                    reads the file content from the caller's sandbox; do NOT inline content into \
                    arguments. Submits a PENDING contribution that an admin must approve before \
                    it is materialised. Ask the user before calling this tool. Returns \
                    "ok: contribution #N submitted, awaiting admin approval" on success, or an \
                    error string starting with "error:".\
                    """)
    public String contribute(
            @ToolParam(
                            name = "source_user_id",
                            description =
                                    "Identity of the user whose workspace this artifact came from."
                                            + " Take this from the active session context.")
                    String sourceUserId,
            @ToolParam(
                            name = "source_agent_id",
                            description =
                                    "Agent id the source files live under. The tool reads from"
                                            + " this user's sandbox of this agent.")
                    String sourceAgentId,
            @ToolParam(
                            name = "target_type",
                            description =
                                    "One of: skill | subagent | memory | agents_md | knowledge")
                    String targetType,
            @ToolParam(
                            name = "target_path",
                            description =
                                    "Where the artifact should land under"
                                        + " shared/agents/<targetAgentId>/<targetType>/. For a"
                                        + " skill, the bundle directory name. For subagent / memory"
                                        + " / knowledge, the file path. For agents_md, the literal"
                                        + " 'AGENTS.md'.")
                    String targetPath,
            @ToolParam(
                            name = "source_paths",
                            description =
                                    "Comma-separated list of workspace-relative source paths to"
                                            + " harvest from the caller's sandbox. For single-file"
                                            + " target types pass exactly one path; for skill"
                                            + " bundles pass one path per file.")
                    String sourcePaths,
            @ToolParam(
                            name = "target_agent_id",
                            description = "Agent id to promote into; defaults to source_agent_id.",
                            required = false)
                    String targetAgentId,
            @ToolParam(
                            name = "rationale",
                            description =
                                    "One- or two-sentence explanation for the reviewing admin.",
                            required = false)
                    String rationale) {
        try {
            List<FileEntry> payload =
                    harvestFiles(sourceUserId, sourceAgentId, targetType, sourcePaths);
            ContributionEntity saved =
                    service.submit(
                            sourceUserId,
                            sourceAgentId,
                            targetAgentId,
                            targetType,
                            targetPath,
                            rationale,
                            payload);
            return "ok: contribution #" + saved.getId() + " submitted, awaiting admin approval";
        } catch (IllegalArgumentException e) {
            return "error: " + e.getMessage();
        } catch (RuntimeException e) {
            return "error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** 从调用方沙箱读取逗号分隔的每个源路径，构建 FileEntry 列表。多文件技能包的 relPath 使用 basename；单文件目标的 relPath 为空，由服务路由到相应默认位置。 */
    private List<FileEntry> harvestFiles(
            String sourceUserId, String sourceAgentId, String targetType, String sourcePaths) {
        if (sourcePaths == null || sourcePaths.isBlank()) {
            throw new IllegalArgumentException("source_paths must contain at least one path");
        }
        String[] rawPaths = sourcePaths.split(",");
        List<String> cleaned = new ArrayList<>(rawPaths.length);
        for (String raw : rawPaths) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            cleaned.add(trimmed);
        }
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("source_paths must contain at least one path");
        }
        boolean isSkillBundle = ContributionEntity.TARGET_SKILL.equals(targetType);
        if (!isSkillBundle && cleaned.size() != 1) {
            throw new IllegalArgumentException(
                    "target_type "
                            + targetType
                            + " accepts exactly one source path (got "
                            + cleaned.size()
                            + ")");
        }

        WorkspaceManager wm = workspaceFactory.forAgent(sourceUserId, sourceAgentId);
        RuntimeContext rc = RuntimeContext.builder().userId(sourceUserId).build();
        List<FileEntry> out = new ArrayList<>(cleaned.size());
        for (String path : cleaned) {
            String content = wm.readManagedWorkspaceFileUtf8(rc, path);
            if (content == null || content.isEmpty()) {
                throw new IllegalArgumentException("source file is empty or unreadable: " + path);
            }
            String relPath = isSkillBundle ? Paths.get(path).getFileName().toString() : "";
            out.add(new FileEntry(relPath, content));
        }
        return out;
    }
}
