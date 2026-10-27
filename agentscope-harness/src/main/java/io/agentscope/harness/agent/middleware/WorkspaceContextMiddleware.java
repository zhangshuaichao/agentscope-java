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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.context.ContextItem;
import io.agentscope.harness.agent.context.WorkspaceContextMaterials;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.ProjectAwareOverlay;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 通过 {@link #onSystemPrompt(Agent, RuntimeContext, String)} 收集工作区指令和参考材料，
 * 供最终请求渲染使用。
 *
 * <p>每次 {@code call()} 执行一次，类似旧版 {@code WorkspaceContextHook}
 * 在 {@code PreCallEvent} 上触发的方式。
 *
 * <p>记忆相关指导和参考材料加载，受与 Harness 记忆工具／钩子相同的建造者开关
 * （{@code disableMemoryTools} / {@code disableMemoryHooks}）控制，
 * 避免提示模型使用已经关闭的能力。
 *
 * <p>【学习提示】工作区材料按调用加载，最终模型请求则可在每轮推理时重新装配。
 * 不要把“本次调用读取了一次文件”误认为“每轮都重新读取文件”，也不要将参考材料自动提升为系统指令。
 */
public class WorkspaceContextMiddleware implements HarnessRuntimeMiddleware {

    private static final String SESSION_CONTEXT_SECTION_TEMPLATE =
            """
            ## Runtime Environment
            You are the %s agent.
            The host operating system is: %s
            The workspace directory is: %s
            The project's temporary directory is: %s
            %s
            """;

    private static final String DOMAIN_KNOWLEDGE_GUIDANCE =
            """
            ## Domain Knowledge
            Use the available knowledge reference material in HARNESS_CONTEXT for specs, procedures, schemas, and domain facts relevant to the task.
            """;

    private static final String KNOWLEDGE_FILES_GUIDANCE =
            """
            The knowledge file catalog lists reference paths under `knowledge/`. Use the catalog in HARNESS_CONTEXT when present; if omitted for budget, inspect `knowledge/` with targeted tools. Read only the paths you need with read_file, grep, or glob.
            """;

    private static final String MEMORY_RECALL_GUIDANCE =
            """
            ## Memory Recall
            Before answering questions about prior work, decisions, dates, people, or preferences: \
            run memory_search on MEMORY.md + memory/*.md, then memory_get for needed lines. \
            Include Source: <path#line> citations when helpful.
            """;

    private static final String MEMORY_PERSISTENCE_HEADER =
            """
            ## Memory Persistence
            You have a persistent MEMORY.md. Update it proactively when:
            - User shares preferences, project context, or decisions
            - Important outcomes or action items are established
            """;

    private static final String MEMORY_PERSISTENCE_HEADER_HOOKS_ONLY =
            """
            ## Memory Persistence
            You have a persistent MEMORY.md that the harness maintains automatically.
            """;

    private static final String MEMORY_SAVE_TOOL_GUIDANCE =
            """
            Use the **memory_save** tool to persist memories — it atomically updates \
            both MEMORY.md and the daily ledger. Do NOT use write_file or edit_file on \
            MEMORY.md or any path under memory/ — always use memory_save instead.
            """;

    private static final String MEMORY_WRITE_FILE_GUARD =
            """
            Do NOT use write_file or edit_file on MEMORY.md or any path under memory/ — \
            the harness owns those files.
            """;

    private static final String MEMORY_AUTO_EXTRACT_GUIDANCE =
            "Memory is also automatically extracted at conversation end.\n";

    private static final String TRUNCATION_NOTICE_WITH_SEARCH =
            "\n\n... (memory truncated — use memory_search for older entries) ...\n";

    private static final String TRUNCATION_NOTICE_PLAIN = "\n\n... (memory truncated) ...\n";

    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 8000;

    private final WorkspaceManager workspaceManager;
    private final String agentName;
    private final String environmentMemory;
    private final int maxContextTokens;
    private final boolean disableMemoryTools;
    private final boolean disableMemoryHooks;
    private List<String> additionalContextFiles = List.of();
    private boolean artifactDeliveryEnabled = false;

    public WorkspaceContextMiddleware(WorkspaceManager workspaceManager) {
        this(workspaceManager, "HarnessAgent", null, DEFAULT_MAX_CONTEXT_TOKENS, false, false);
    }

    public WorkspaceContextMiddleware(WorkspaceManager workspaceManager, int maxContextTokens) {
        this(workspaceManager, "HarnessAgent", null, maxContextTokens, false, false);
    }

    public WorkspaceContextMiddleware(
            WorkspaceManager workspaceManager,
            String agentName,
            String environmentMemory,
            int maxContextTokens) {
        this(workspaceManager, agentName, environmentMemory, maxContextTokens, false, false);
    }

    public WorkspaceContextMiddleware(
            WorkspaceManager workspaceManager,
            String agentName,
            String environmentMemory,
            int maxContextTokens,
            boolean disableMemoryTools,
            boolean disableMemoryHooks) {
        this.workspaceManager = workspaceManager;
        this.agentName = agentName != null && !agentName.isBlank() ? agentName : "HarnessAgent";
        this.environmentMemory = environmentMemory;
        this.maxContextTokens = maxContextTokens;
        this.disableMemoryTools = disableMemoryTools;
        this.disableMemoryHooks = disableMemoryHooks;
    }

    /** 仅声明所需扩展点；子类若重写更多钩子，必须扩展此集合。 */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_SYSTEM_PROMPT);
    }

    public void setAdditionalContextFiles(List<String> files) {
        this.additionalContextFiles = files != null ? files : List.of();
    }

    /** 此中间件是否禁用记忆工具（影响提示指导）。 */
    public boolean isDisableMemoryTools() {
        return disableMemoryTools;
    }

    /** 此中间件是否禁用记忆钩子（影响提示指导）。 */
    public boolean isDisableMemoryHooks() {
        return disableMemoryHooks;
    }

    /**
     * 是否配置了 {@link io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget}
     * 且暴露 {@code deliver_artifact} 工具。为 {@code true} 时，工作区段落的沙箱分支
     * 会要求模型使用该工具；为 {@code false} 时，说明文件无法离开沙箱。
     */
    public void setArtifactDeliveryEnabled(boolean artifactDeliveryEnabled) {
        this.artifactDeliveryEnabled = artifactDeliveryEnabled;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        return Mono.fromCallable(
                        () -> {
                            RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
                            String base = currentPrompt != null ? currentPrompt : "";
                            collectWorkspaceMaterials(rc);
                            return base;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void collectWorkspaceMaterials(RuntimeContext rc) {
        String agentsContent = composeAgentsContent(workspaceManager.readAgentsMd(rc));
        boolean includeMemoryContext = includeMemoryContext();
        String memoryContent =
                includeMemoryContext ? workspaceManager.readMemoryMd(rc).strip() : "";
        String knowledgeContent = workspaceManager.readKnowledgeMd(rc).strip();
        List<Path> knowledgeFiles = workspaceManager.listKnowledgeFiles(rc);
        Path workspace = workspaceManager.getWorkspace();
        AbstractFilesystem filesystem = workspaceManager.getFilesystem();
        Path effectiveWorkspace =
                detectLocalUpper(filesystem) != null
                        ? workspaceManager.resolveRuntimeDataPath(rc, "")
                        : workspace;
        String sessionContext = buildSessionContextSection(effectiveWorkspace);

        String knowledgeBlock = buildKnowledgeBlock(knowledgeContent, knowledgeFiles, workspace);
        String additionalBlock = buildAdditionalContextBlock(rc);

        int fixedTokens =
                estimateTokens(sessionContext)
                        + estimateTokens(agentsContent)
                        + estimateTokens(knowledgeBlock)
                        + estimateTokens(additionalBlock);
        if (includeMemoryContext) {
            int memoryTokens = estimateTokens(memoryContent);
            int available = maxContextTokens - fixedTokens;
            if (available <= 0) {
                memoryContent = "";
            } else if (memoryTokens > available) {
                memoryContent = truncateToTokenBudget(memoryContent, available);
            }
        }

        String workspaceParagraph =
                buildWorkspaceParagraph(
                        workspace, effectiveWorkspace, filesystem, artifactDeliveryEnabled);
        WorkspaceContextMaterials.register(
                rc,
                List.of(
                        ContextItem.instruction(
                                "project_rules", "workspace:AGENTS.md", agentsContent),
                        ContextItem.instruction(
                                "working_principles",
                                "harness:workspace-guidance",
                                buildGuidance(knowledgeContent, knowledgeFiles)),
                        ContextItem.instruction(
                                "environment",
                                "harness:workspace-environment",
                                sessionContext + "\n" + workspaceParagraph),
                        ContextItem.runtimeState(
                                "session_state",
                                "harness:session-state",
                                false,
                                buildSessionStateSection(rc)),
                        new ContextItem("memory", "workspace:MEMORY.md", memoryContent),
                        new ContextItem("knowledge", "workspace:knowledge", knowledgeBlock),
                        new ContextItem(
                                "additional", "workspace:additional-files", additionalBlock)));
    }

    /** AGENTS.md 内容保留头部的截断上限，单位为 UTF-8 字节。 */
    private static final int MAX_AGENTS_MD_BYTES = 100 * 1024;

    /**
     * 将规则正文限制为 {@link #MAX_AGENTS_MD_BYTES} 个 UTF-8 字节，
     * 并在前面加入适用范围说明，让说明与规则相伴，而不是散落在远处的块中。
     */
    private static String composeAgentsContent(String raw) {
        String content = truncateUtf8(raw.strip(), MAX_AGENTS_MD_BYTES);
        if (content.isEmpty()) {
            return "";
        }
        return "AGENTS.md defines persona and local conventions — honor them when consistent"
                + " with safety and policy.\n\n"
                + content;
    }

    /**
     * 保留头部至 {@code maxBytes} 个 UTF-8 字节，并追加可见的截断标记。
     * 编码到固定大小缓冲区；编码器会在放不下的码点之前停止，
     * 因此保留内容可以完整解码，不会切断字符。
     */
    private static String truncateUtf8(String content, int maxBytes) {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
        ByteBuffer out = ByteBuffer.allocate(maxBytes);
        if (encoder.encode(CharBuffer.wrap(content), out, true).isUnderflow()) {
            return content;
        }
        return new String(out.array(), 0, out.position(), StandardCharsets.UTF_8)
                + "\n\n[File truncated: AGENTS.md]";
    }

    /**
     * 注入 {@code MEMORY.md}，除非记忆工具与记忆钩子均被禁用；
     * 两者都禁用时，Harness 记忆能力完全关闭，该文件不应再进入模型上下文。
     */
    private boolean includeMemoryContext() {
        return !(disableMemoryTools && disableMemoryHooks);
    }

    private String buildGuidance(String knowledgeContent, List<Path> knowledgeFiles) {
        StringBuilder sb = new StringBuilder();
        if (!knowledgeContent.isBlank() || !knowledgeFiles.isEmpty()) {
            sb.append(DOMAIN_KNOWLEDGE_GUIDANCE.strip()).append("\n\n");
            if (!knowledgeFiles.isEmpty()) {
                sb.append(KNOWLEDGE_FILES_GUIDANCE.strip()).append("\n\n");
            }
        }
        if (!disableMemoryTools) {
            sb.append(MEMORY_RECALL_GUIDANCE.strip()).append("\n\n");
        }
        String persistence = buildMemoryPersistenceGuidance();
        if (!persistence.isBlank()) {
            sb.append(persistence.strip()).append("\n\n");
        }
        return sb.toString().stripTrailing() + "\n";
    }

    private String buildMemoryPersistenceGuidance() {
        if (disableMemoryTools && disableMemoryHooks) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!disableMemoryTools) {
            sb.append(MEMORY_PERSISTENCE_HEADER.strip()).append("\n");
            sb.append(MEMORY_SAVE_TOOL_GUIDANCE.strip()).append("\n");
        } else {
            sb.append(MEMORY_PERSISTENCE_HEADER_HOOKS_ONLY.strip()).append("\n");
            sb.append(MEMORY_WRITE_FILE_GUARD.strip()).append("\n");
        }
        if (!disableMemoryHooks) {
            sb.append(MEMORY_AUTO_EXTRACT_GUIDANCE.strip()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 构建 {@code ## Workspace} 段落，按活动文件系统类型分支，
     * 确保 LLM 看到的描述与实际部署环境一致。
     *
     * <ul>
     *   <li><b>本地 overlay</b>（{@link OverlayFilesystem} 包装
     *       {@link LocalFilesystemWithShell}）—— 分两行渲染 Project 和 Workspace，
     *       并描述 overlay／shell 语义。
     *   <li><b>沙箱</b>（未被 overlay 包装的 {@link AbstractSandboxFilesystem}）——
     *       描述隔离容器视图及宿主机文件如何进入容器。
     *   <li><b>远程</b>（{@link CompositeFilesystem}）—— 描述基于分布式存储的工作区，
     *       并说明不存在可回退访问的宿主机文件系统。
     *   <li><b>其他</b> —— 对普通 {@link io.agentscope.harness.agent.filesystem.local.LocalFilesystem}
     *       或未知类型，使用旧版单行“工作目录为 X”形式。
     * </ul>
     */
    private static String buildWorkspaceParagraph(
            Path workspace,
            Path effectiveWorkspace,
            AbstractFilesystem fs,
            boolean artifactDeliveryEnabled) {
        StringBuilder sb = new StringBuilder("## Workspace\n");
        LocalFilesystemWithShell localUpper = detectLocalUpper(fs);
        Path project = localUpper != null ? localUpper.getShellCwd() : null;
        if (project != null) {
            sb.append("Project (the user's source tree you're assisting with): ")
                    .append(project.toAbsolutePath())
                    .append("\n");
            sb.append("Workspace (your home base — memory, sessions, skills, runtime data): ")
                    .append(effectiveWorkspace.toAbsolutePath())
                    .append("\n");
            List<Path> extraRoots = extraRootsOf(localUpper, project, workspace);
            if (!extraRoots.isEmpty()) {
                sb.append("Additional roots: ");
                for (int i = 0; i < extraRoots.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(extraRoots.get(i).toAbsolutePath());
                }
                sb.append("\n");
            }
            LocalFsMode mode = localUpper.getMode();
            sb.append("Path access policy: ")
                    .append(describeMode(mode))
                    .append(". File tools reject absolute paths outside the roots above")
                    .append(mode == LocalFsMode.ROOTED ? " with a security error" : "")
                    .append(".\n");
            if (fs instanceof ProjectAwareOverlay) {
                sb.append(
                        "File tools write project files (code, configs, etc.) to the project"
                                + " directory. Workspace metadata paths (memory, sessions, skills,"
                                + " agents, knowledge) are written to the workspace.\n");
            } else {
                sb.append(
                        "Relative paths resolve under the workspace and read-fall-back to"
                                + " the project (overlay copy-on-write).\n");
            }
            sb.append("Shell commands run with `pwd` set to the project directory.\n");
        } else if (fs instanceof AbstractSandboxFilesystem sandbox
                && !(fs instanceof OverlayFilesystem)) {
            sb.append("Sandbox root: /workspace (container id: ")
                    .append(sandbox.id())
                    .append(")\n");
            if (artifactDeliveryEnabled) {
                sb.append(
                        "Files are isolated inside this container. The host filesystem is not"
                                + " directly accessible — see the File Isolation Notice below for"
                                + " how to deliver files out of the sandbox.\n");
            } else {
                sb.append(
                        "Files are isolated inside this container. The host filesystem is not"
                                + " accessible and there is no mechanism for moving files across"
                                + " the boundary.\n");
            }
        } else if (fs instanceof CompositeFilesystem) {
            sb.append("Distributed workspace template root: ")
                    .append(workspace.toAbsolutePath())
                    .append("\n");
            sb.append(
                    "Runtime data (MEMORY.md, sessions, tasks, skills) lives in a shared remote"
                            + " store, not on the local host. Reads of project-authored template"
                            + " files fall back to the workspace template root above.\n");
        } else {
            sb.append("Your working directory is: ")
                    .append(workspace.toAbsolutePath())
                    .append("\n");
            sb.append(
                    "Treat this directory as the single global workspace for file operations"
                            + " unless explicitly instructed otherwise.\n");
        }
        if (artifactDeliveryEnabled
                && fs instanceof AbstractSandboxFilesystem
                && !(fs instanceof OverlayFilesystem)) {
            sb.append(
                    "**File Isolation Notice**\n"
                            + "Files inside this container are isolated from the host filesystem"
                            + " and are not directly accessible from outside. If your work"
                            + " produces any final deliverables—such as documents, reports,"
                            + " images, spreadsheets, archives, audio/video files, code"
                            + " artifacts, or similar—you **must** call deliver_artifact"
                            + " automatically when you finish the task to export them to their"
                            + " configured external destination. Deliver it silently: do not ask"
                            + " the user whether they want it delivered — the tool call itself lets"
                            + " the user see and retrieve the artifact directly, so do not mention"
                            + " the delivery or the deliver_artifact tool in your reply.\n"
                            + "\n"
                            + "**Important Notes**:\n"
                            + "- Only deliver the final output of your task. **Do not** deliver"
                            + " temporary files, working copies, internal intermediate files, or"
                            + " any sensitive information (e.g., credentials, keys, personal"
                            + " data).\n"
                            + "- Do not simply print the file path as a reference; the user"
                            + " cannot access your container's filesystem directly.\n");
        }
        return sb.toString();
    }

    /**
     * 尽力获取上层 {@link LocalFilesystemWithShell}：当 {@code fs} 是由
     * {@code LocalFilesystemSpec} 创建的 overlay 时返回它，否则为 {@code null}。
     * 用于提取提示中的项目／模式／策略元数据，避免将这些信息泄漏到其他文件系统类型。
     */
    private static LocalFilesystemWithShell detectLocalUpper(AbstractFilesystem fs) {
        if (fs instanceof OverlayFilesystem ov
                && ov.getUpper() instanceof LocalFilesystemWithShell lfs) {
            return lfs;
        }
        return null;
    }

    /**
     * 除项目和工作区（LLM 已能看到）之外的额外允许根目录。
     * 过滤精确匹配项及祖先目录，使提示聚焦于真正额外的位置。
     */
    private static List<Path> extraRootsOf(
            LocalFilesystemWithShell upper, Path project, Path workspace) {
        PathPolicy policy = upper.getPathPolicy();
        if (policy == null || policy.isEmpty()) {
            return List.of();
        }
        Path projectAbs = project.toAbsolutePath().normalize();
        Path workspaceAbs = workspace.toAbsolutePath().normalize();
        List<Path> extras = new java.util.ArrayList<>();
        for (Path root : policy.roots()) {
            if (root.equals(projectAbs) || root.equals(workspaceAbs)) {
                continue;
            }
            extras.add(root);
        }
        return extras;
    }

    private static String describeMode(LocalFsMode mode) {
        if (mode == null) {
            return "ROOTED (default)";
        }
        return switch (mode) {
            case SANDBOXED -> "SANDBOXED (all paths anchored to the workspace; `..` blocked)";
            case ROOTED -> "ROOTED (absolute paths accepted only inside the roots above)";
            case UNRESTRICTED -> "UNRESTRICTED (absolute paths pass through unchanged)";
        };
    }

    private String buildSessionContextSection(Path workspace) {
        String platform = System.getProperty("os.name") + " " + System.getProperty("os.version");
        String tempDir = System.getProperty("java.io.tmpdir");
        String environmentMemoryPart =
                environmentMemory != null && !environmentMemory.isBlank() ? environmentMemory : "";

        return String.format(
                        SESSION_CONTEXT_SECTION_TEMPLATE,
                        agentName,
                        platform,
                        workspace.toAbsolutePath(),
                        tempDir,
                        environmentMemoryPart)
                .strip();
    }

    /**
     * 用于尾部运行时通道的每次调用易变事实：会话 ID 和解析后的日期。
     * 两者独立于智能体身份和指令变化（长会话可能跨越午夜），
     * 因此不能放入字节稳定的系统头部。
     */
    private String buildSessionStateSection(RuntimeContext rc) {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE MMM d, yyyy"));
        StringBuilder sb = new StringBuilder();
        if (rc != null && rc.getSessionId() != null) {
            sb.append("AgentStateStore session id: ").append(rc.getSessionId()).append('\n');
        }
        sb.append("Today's date: ")
                .append(today)
                .append(" (resolved at the start of this conversation turn)\n");
        return sb.toString();
    }

    private static String indentByTwo(String text) {
        return text.lines().map(line -> "  " + line).collect(Collectors.joining("\n"));
    }

    private String buildAdditionalContextBlock(RuntimeContext rc) {
        if (additionalContextFiles.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String relPath : additionalContextFiles) {
            String content = workspaceManager.readManagedWorkspaceFileUtf8(rc, relPath);
            if (content != null && !content.isBlank()) {
                String tag = relPath.replace("/", "_").replace(".", "_").toLowerCase();
                sb.append("  <").append(tag).append(">\n");
                sb.append(indentByTwo(content.strip())).append("\n");
                sb.append("  </").append(tag).append(">\n");
            }
        }
        return sb.toString();
    }

    private static int estimateTokens(String text) {
        return text == null || text.isEmpty() ? 0 : text.length() / 4;
    }

    private String truncateToTokenBudget(String text, int maxTokens) {
        int maxChars = maxTokens * 4;
        if (text.length() <= maxChars) {
            return text;
        }
        String notice =
                disableMemoryTools ? TRUNCATION_NOTICE_PLAIN : TRUNCATION_NOTICE_WITH_SEARCH;
        if (notice.length() >= maxChars) {
            return text.substring(0, maxChars);
        }
        return text.substring(0, maxChars - notice.length()) + notice;
    }

    private String buildKnowledgeBlock(
            String knowledgeContent, List<Path> knowledgeFiles, Path workspace) {
        StringBuilder sb = new StringBuilder();

        if (!knowledgeContent.isBlank()) {
            sb.append(knowledgeContent.strip()).append("\n");
        }

        if (!knowledgeFiles.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append("Knowledge files:\n");
            sb.append(
                    knowledgeFiles.stream()
                            .map(f -> "- " + workspace.relativize(f))
                            .collect(Collectors.joining("\n")));
            sb.append("\n");
        }

        return sb.toString();
    }
}
