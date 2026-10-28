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
package io.agentscope.claw2.web.catalog;

import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.runtime.config.AgentConfigEntry;
import io.agentscope.claw2.runtime.gateway.HarnessGateway;
import io.agentscope.claw2.web.scaffold.WorkspaceScaffolder;
import io.agentscope.claw2.web.template.TemplateRegistry;
import io.agentscope.claw2.web.toolbus.ToolEventBus;
import io.agentscope.claw2.web.toolbus.ToolNotificationMiddleware;
import io.agentscope.core.model.Model;
import io.agentscope.core.session.SessionLogStore;
import io.agentscope.harness.agent.HarnessAgent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 本地智能体目录的业务逻辑，没有多用户或共享权限模型；此处可见的智能体都供本地用户使用。
 *
 * <h2>智能体类型</h2>
 *
 * <ul>
 *   <li><b>内置智能体：</b>定义在 {@code ${clawHome}/agentscope.json}，启动时注册到 {@link HarnessGateway}。公开其定义，但不允许通过此服务编辑 JSON 条目。
 *   <li><b>自定义智能体：</b>存储在 {@code ${clawHome}/agents.json}，用户可创建、更新和删除。首次对话时动态构建并以 {@code agentId} 直接注册到网关，不加命名空间前缀。
 * </ul>
 */
@Service
public class AgentCatalogService {

    private static final Logger log = LoggerFactory.getLogger(AgentCatalogService.class);

    private final ClawBootstrap bootstrap;
    private final UserAgentDefinitionStore store;
    private final Model model;
    private final ToolEventBus toolEventBus;
    private final TemplateRegistry templateRegistry;
    private final Optional<SessionLogStore> sessionLogStore;

    /** 本次运行期间已构建并注册到网关的自定义智能体 ID。 */
    private final ConcurrentHashMap<String, String> registeredCustomIds = new ConcurrentHashMap<>();

    public AgentCatalogService(
            ClawBootstrap bootstrap,
            UserAgentDefinitionStore store,
            Optional<Model> modelOpt,
            ToolEventBus toolEventBus,
            TemplateRegistry templateRegistry,
            Optional<SessionLogStore> sessionLogStore) {
        this.bootstrap = bootstrap;
        this.store = store;
        this.model = modelOpt.orElse(null);
        this.toolEventBus = toolEventBus;
        this.templateRegistry = templateRegistry;
        this.sessionLogStore = sessionLogStore != null ? sessionLogStore : Optional.empty();
    }

    // -----------------------------------------------------------------
    // 查询
    // -----------------------------------------------------------------

    /** 列出本地目录中的全部智能体：先内置，再按插入顺序列出自定义项。 */
    public List<AgentDefinition> list() {
        List<AgentDefinition> out = new ArrayList<>(builtinDefinitions());
        for (UserAgentDefinitionStore.StoredEntry e : store.list()) {
            out.add(e.toDefinition());
        }
        return out;
    }

    /** 按 ID 查找单个智能体（内置或自定义）。 */
    public Optional<AgentDefinition> find(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return Optional.empty();
        }
        return list().stream().filter(d -> d.id().equals(agentId)).findFirst();
    }

    /** 按 ID 返回自定义智能体存储条目（若存在）。 */
    public Optional<UserAgentDefinitionStore.StoredEntry> findStoredEntry(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return Optional.empty();
        }
        return store.findById(agentId);
    }

    /** ID 指向内置智能体时返回 {@code true}。 */
    public boolean isBuiltin(String agentId) {
        return agentId != null && bootstrap.agents().containsKey(agentId);
    }

    // -----------------------------------------------------------------
    // 修改操作（仅自定义智能体）
    // -----------------------------------------------------------------

    /** 创建自定义智能体定义。 */
    public AgentDefinition createAgent(AgentCreateRequest req) {
        validateRequest(req);

        String id =
                sanitizeId(
                        req.id() != null && !req.id().isBlank()
                                ? req.id()
                                : UUID.randomUUID().toString().replace("-", "").substring(0, 8));

        if (store.findById(id).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Agent with id '" + id + "' already exists");
        }
        if (isBuiltin(id)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Agent id '" + id + "' conflicts with a built-in agent");
        }

        long now = System.currentTimeMillis();
        String workspacePath = normalizeWorkspacePathInput(req.workspacePath());

        UserAgentDefinitionStore.StoredEntry entry =
                new UserAgentDefinitionStore.StoredEntry(
                        id,
                        req.name() != null ? req.name() : id,
                        req.description(),
                        req.sysPrompt(),
                        req.model(),
                        req.maxIters(),
                        req.toolsAllow(),
                        req.toolsDeny(),
                        req.identityName(),
                        req.identityEmoji(),
                        req.groupChatMentionPatterns(),
                        req.groupChatRequireMention(),
                        req.sandboxMode(),
                        req.sandboxScope(),
                        req.skillsAllow(),
                        req.skillsDeny(),
                        now,
                        now,
                        workspacePath);
        store.save(entry);
        log.info("Created custom agent '{}'", id);

        // 搭建工作区；模板优先于 AI 草稿，否则使用默认脚手架。
        Path workspace = workspacePath(entry);
        try {
            if (req.templateId() != null && !req.templateId().isBlank()) {
                boolean ok = templateRegistry.instantiate(req.templateId(), workspace);
                if (!ok) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Unknown templateId: " + req.templateId());
                }
            } else if (req.aiDraft() != null) {
                writeDraftFiles(workspace, req.aiDraft(), entry);
            } else {
                WorkspaceScaffolder.scaffold(workspace, entry.name(), entry.sysPrompt());
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            log.warn(
                    "Failed to scaffold workspace for custom agent '{}' at {}: {}",
                    id,
                    workspace,
                    e.getMessage());
        }

        return entry.toDefinition();
    }

    /** 更新已有自定义智能体定义。 */
    public AgentDefinition updateAgent(String agentId, AgentCreateRequest req) {
        validateRequest(req);
        if (isBuiltin(agentId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Built-in agents cannot be edited via the catalog API");
        }
        UserAgentDefinitionStore.StoredEntry existing =
                store.findById(agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));

        long now = System.currentTimeMillis();
        UserAgentDefinitionStore.StoredEntry updated =
                new UserAgentDefinitionStore.StoredEntry(
                        agentId,
                        req.name() != null ? req.name() : existing.name(),
                        req.description() != null ? req.description() : existing.description(),
                        req.sysPrompt() != null ? req.sysPrompt() : existing.sysPrompt(),
                        req.model() != null ? req.model() : existing.model(),
                        req.maxIters() != null ? req.maxIters() : existing.maxIters(),
                        req.toolsAllow() != null ? req.toolsAllow() : existing.toolsAllow(),
                        req.toolsDeny() != null ? req.toolsDeny() : existing.toolsDeny(),
                        req.identityName() != null ? req.identityName() : existing.identityName(),
                        req.identityEmoji() != null
                                ? req.identityEmoji()
                                : existing.identityEmoji(),
                        req.groupChatMentionPatterns() != null
                                ? req.groupChatMentionPatterns()
                                : existing.groupChatMentionPatterns(),
                        req.groupChatRequireMention() != null
                                ? req.groupChatRequireMention()
                                : existing.groupChatRequireMention(),
                        req.sandboxMode() != null ? req.sandboxMode() : existing.sandboxMode(),
                        req.sandboxScope() != null ? req.sandboxScope() : existing.sandboxScope(),
                        req.skillsAllow() != null ? req.skillsAllow() : existing.skillsAllow(),
                        req.skillsDeny() != null ? req.skillsDeny() : existing.skillsDeny(),
                        existing.createdAt(),
                        now,
                        existing.workspacePath()); // workspacePath 只允许在创建时指定
        store.save(updated);

        // 清除缓存的网关注册，使下一次对话按新定义重新构建。
        registeredCustomIds.remove(agentId);

        log.info("Updated custom agent '{}'", agentId);
        return updated.toDefinition();
    }

    /** 删除自定义智能体定义；内置智能体不可删除。 */
    public void deleteAgent(String agentId) {
        if (isBuiltin(agentId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Built-in agents cannot be deleted");
        }
        if (!store.delete(agentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: " + agentId);
        }
        registeredCustomIds.remove(agentId);
        log.info("Deleted custom agent '{}'", agentId);
    }

    // -----------------------------------------------------------------
    // 网关路由支持
    // -----------------------------------------------------------------

    /**
     * 解析向指定智能体发送聊天消息时使用的网关智能体 ID。内置和自定义智能体均以自身 ID 注册；自定义智能体在首次调用时延迟构建并注册。
     *
     * @throws ResponseStatusException 智能体不存在时返回 404
     */
    public String resolveGatewayAgentId(String agentId) {
        if (isBuiltin(agentId)) {
            return agentId;
        }
        UserAgentDefinitionStore.StoredEntry entry =
                store.findById(agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));

        return registeredCustomIds.computeIfAbsent(agentId, k -> buildAndRegisterCustom(entry));
    }

    // -----------------------------------------------------------------
    // 内部实现
    // -----------------------------------------------------------------

    private List<AgentDefinition> builtinDefinitions() {
        Map<String, AgentConfigEntry> fileAgents = bootstrap.loadedConfig().getAgents();
        List<AgentDefinition> result = new ArrayList<>();
        for (Map.Entry<String, HarnessAgent> e : bootstrap.agents().entrySet()) {
            String id = e.getKey();
            AgentConfigEntry cfg = fileAgents != null ? fileAgents.get(id) : null;
            String name = cfg != null && cfg.getName() != null ? cfg.getName() : id;
            String desc = cfg != null ? cfg.getDescription() : null;

            // HarnessAgent 没有公开 getToolkit()，因此报告标准内置工具。
            List<String> toolNames =
                    List.of(
                            "filesystem",
                            "shell_execute",
                            "memory_search",
                            "memory_get",
                            "session_search");

            AgentConfigEntry.ToolsConfig tc = cfg != null ? cfg.getTools() : null;
            AgentConfigEntry.IdentityConfig ic = cfg != null ? cfg.getIdentity() : null;
            AgentConfigEntry.GroupChatConfig gc = cfg != null ? cfg.getGroupChat() : null;
            AgentConfigEntry.SandboxConfig sc = cfg != null ? cfg.getSandbox() : null;
            AgentConfigEntry.SkillsConfig sk = cfg != null ? cfg.getSkills() : null;

            result.add(
                    new AgentDefinition(
                            id,
                            name,
                            desc,
                            null, // 目录不公开内置智能体的 sysPrompt
                            cfg != null ? cfg.getModel() : null,
                            cfg != null ? cfg.getMaxIters() : null,
                            toolNames,
                            tc != null ? tc.getAllow() : null,
                            tc != null ? tc.getDeny() : null,
                            ic != null ? ic.getName() : null,
                            ic != null ? ic.getEmoji() : null,
                            gc != null ? gc.getMentionPatterns() : null,
                            gc != null ? gc.getRequireMention() : null,
                            sc != null ? sc.getMode() : null,
                            sc != null ? sc.getScope() : null,
                            sk != null ? sk.getAllow() : null,
                            sk != null ? sk.getDeny() : null,
                            0L,
                            0L,
                            cfg != null ? cfg.getWorkspace() : null,
                            true));
        }
        return result;
    }

    private String buildAndRegisterCustom(UserAgentDefinitionStore.StoredEntry entry) {
        Path workspace = workspacePath(entry);

        HarnessAgent.Builder b = HarnessAgent.builder();
        String name = entry.name() != null ? entry.name() : entry.id();
        b.name(name);
        b.agentId(entry.id());
        if (entry.description() != null) b.description(entry.description());
        if (entry.sysPrompt() != null) b.sysPrompt(entry.sysPrompt());
        if (entry.maxIters() != null) b.maxIters(entry.maxIters());
        if (entry.model() != null && !entry.model().isBlank()) {
            b.model(entry.model());
        } else if (model != null) {
            b.model(model);
        }
        b.workspace(workspace);
        b.middleware(new ToolNotificationMiddleware(toolEventBus));
        sessionLogStore.ifPresent(b::sessionLogStore);

        HarnessAgent agent = b.build();
        HarnessGateway gateway = bootstrap.gateway();
        gateway.registerAgent(entry.id(), agent);

        log.info("Registered custom agent in gateway: agentId={}", entry.id());
        return entry.id();
    }

    /** 解析自定义智能体的磁盘工作区路径。若条目提供 {@code workspacePath}，则相对于 {@code clawHome} 解析（绝对路径原样保留）；否则使用默认 {@code ${clawHome}/agents/{id}/workspace} 布局。 */
    private Path workspacePath(UserAgentDefinitionStore.StoredEntry entry) {
        Path clawHome = bootstrap.clawHome();
        if (entry.workspacePath() != null && !entry.workspacePath().isBlank()) {
            Path p = Paths.get(entry.workspacePath());
            return p.isAbsolute() ? p.normalize() : clawHome.resolve(p).normalize();
        }
        return ClawBootstrap.defaultAgentWorkspace(clawHome, entry.id());
    }

    /** 去除用户工作区路径两端空白；空白输入返回 {@code null}，由运行时解析默认值。绝对路径原样通过，相对路径拒绝任何 {@code ..} 遍历段。 */
    static String normalizeWorkspacePathInput(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        Path p = Paths.get(trimmed);
        if (!p.isAbsolute()) {
            for (Path seg : p) {
                if ("..".equals(seg.toString())) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Relative workspace path must not contain '..' segments");
                }
            }
        }
        return trimmed;
    }

    /** 将 AI 建议的智能体落盘到工作区：由 {@code (name, description, sysPrompt)} 生成 {@code AGENTS.md}，由 {@code suggestedTools} 生成 {@code tools.json}，为每个 {@code suggestedSkills} 与 {@code suggestedSubagents} 条目生成技能／子智能体文件，并创建 {@code memory/.gitkeep}。 */
    private static void writeDraftFiles(
            Path workspace, AgentDraft draft, UserAgentDefinitionStore.StoredEntry entry)
            throws IOException {
        Files.createDirectories(workspace);
        Files.createDirectories(workspace.resolve("skills"));
        Files.createDirectories(workspace.resolve("subagents"));
        Files.createDirectories(workspace.resolve("memory"));

        String displayName =
                draft.name() != null && !draft.name().isBlank()
                        ? draft.name()
                        : (entry.name() != null ? entry.name() : entry.id());
        String description =
                draft.description() != null && !draft.description().isBlank()
                        ? draft.description()
                        : (entry.description() != null ? entry.description() : "");
        String sysPrompt =
                draft.sysPrompt() != null && !draft.sysPrompt().isBlank()
                        ? draft.sysPrompt()
                        : (entry.sysPrompt() != null
                                ? entry.sysPrompt()
                                : "You are a helpful assistant.");

        StringBuilder agentsMd = new StringBuilder();
        agentsMd.append("# ").append(displayName).append("\n\n");
        if (!description.isEmpty()) {
            agentsMd.append("> ").append(description.trim()).append("\n\n");
        }
        agentsMd.append(sysPrompt.trim()).append("\n");
        writeIfMissing(workspace.resolve("AGENTS.md"), agentsMd.toString());

        if (draft.suggestedTools() != null && !draft.suggestedTools().isEmpty()) {
            StringBuilder tools = new StringBuilder();
            tools.append("{\n  \"allow\": [\n");
            for (int i = 0; i < draft.suggestedTools().size(); i++) {
                String t = draft.suggestedTools().get(i);
                if (t == null) continue;
                tools.append("    \"").append(escapeJson(t)).append("\"");
                if (i < draft.suggestedTools().size() - 1) tools.append(",");
                tools.append("\n");
            }
            tools.append("  ],\n  \"deny\": []\n}\n");
            writeIfMissing(workspace.resolve("tools.json"), tools.toString());
        }

        if (draft.suggestedSkills() != null) {
            for (NamedFile sk : draft.suggestedSkills()) {
                if (sk == null || sk.name() == null || sk.name().isBlank()) continue;
                Path skillDir = workspace.resolve("skills").resolve(sanitizeName(sk.name()));
                Files.createDirectories(skillDir);
                writeIfMissing(
                        skillDir.resolve("SKILL.md"), sk.content() != null ? sk.content() : "");
            }
        }

        if (draft.suggestedSubagents() != null) {
            for (NamedFile sa : draft.suggestedSubagents()) {
                if (sa == null || sa.name() == null || sa.name().isBlank()) continue;
                Path file = workspace.resolve("subagents").resolve(sanitizeName(sa.name()) + ".md");
                writeIfMissing(file, sa.content() != null ? sa.content() : "");
            }
        }

        writeIfMissing(workspace.resolve("memory").resolve(".gitkeep"), "");
    }

    private static String sanitizeName(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9_-]", "-").toLowerCase();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void writeIfMissing(Path file, String content) throws IOException {
        if (Files.exists(file)) return;
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(
                    tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void validateRequest(AgentCreateRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body required");
        }
        if (req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'name' is required");
        }
    }

    private static String sanitizeId(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9_-]", "-").toLowerCase();
    }

    // -----------------------------------------------------------------
    // 请求数据传输对象
    // -----------------------------------------------------------------

    /** 创建或更新自定义智能体的请求体。 */
    public record AgentCreateRequest(
            String id,
            String name,
            String description,
            String sysPrompt,
            String model,
            Integer maxIters,
            List<String> toolsAllow,
            List<String> toolsDeny,
            String identityName,
            String identityEmoji,
            List<String> groupChatMentionPatterns,
            Boolean groupChatRequireMention,
            String sandboxMode,
            String sandboxScope,
            List<String> skillsAllow,
            List<String> skillsDeny,
            String workspacePath,
            String templateId,
            AgentDraft aiDraft) {}

    /** 创建请求携带的可选 AI 生成草稿。 */
    public record AgentDraft(
            String name,
            String description,
            String sysPrompt,
            List<String> suggestedTools,
            List<NamedFile> suggestedSkills,
            List<NamedFile> suggestedSubagents) {}

    /** 具有名称的文件，如 Markdown 技能或子智能体定义。 */
    public record NamedFile(String name, String content) {}
}
