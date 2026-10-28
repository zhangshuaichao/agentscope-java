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
package io.agentscope.dataagent.web.catalog;

import io.agentscope.core.model.Model;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.config.AgentConfigEntry;
import io.agentscope.dataagent.runtime.gateway.HarnessGateway;
import io.agentscope.dataagent.web.auth.UserStore;
import io.agentscope.dataagent.web.auth.UserStore.UserRecord;
import io.agentscope.dataagent.web.scaffold.WorkspaceScaffolder;
import io.agentscope.dataagent.web.share.AgentAclService;
import io.agentscope.dataagent.web.template.TemplateRegistry;
import io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory;
import io.agentscope.harness.agent.HarnessAgent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

/** 智能体目录业务逻辑：合并从 {@code agentscope.json} 加载的全局定义与逐用户自定义定义，并按需动态实例化用户智能体。<h2>可见性规则</h2><ul><li><b>全局智能体</b>（{@code scope = "global"}）：在 {@code agentscope.json} 定义，启动时注册到 {@link HarnessGateway}。所有用户可列举和交互，每用户对话通过 {@code (userId, agentId)} 会话键隔离。<li><b>用户自定义智能体</b>（{@code scope = "user"}）：通过用户定义存储持久化；旧布局曾使用 {@code .agentscope/users/{userId}/agents.json}。所有者管理，也可按共享授权向他人开放。首次使用时动态构建，以 {@code uca-{userId}-{agentId}} 注册到网关。</ul> */
@Service
public class AgentCatalogService {

    private static final Logger log = LoggerFactory.getLogger(AgentCatalogService.class);

    /** 用户自定义智能体注册到网关时的 ID 前缀。 */
    public static final String UCA_PREFIX = "uca-";

    private final DataAgentBootstrap builderBootstrap;
    private final UserAgentDefinitionStore store;
    private final Model model;
    private final io.agentscope.dataagent.web.toolbus.ToolEventBus toolEventBus;
    private final TemplateRegistry templateRegistry;
    private final WorkspaceManagerFactory workspaceManagerFactory;
    private final UserStore userStore;
    private final AgentAclService aclService;

    /** 动态网关智能体 ID 的运行中缓存。键为 {@code {userId}/{agentId}}，值为网关 ID（例如 {@code uca-{userId}-{agentId}}）。 */
    private final ConcurrentHashMap<String, String> registeredUcaIds = new ConcurrentHashMap<>();

    public AgentCatalogService(
            DataAgentBootstrap builderBootstrap,
            UserAgentDefinitionStore store,
            Optional<Model> modelOpt,
            io.agentscope.dataagent.web.toolbus.ToolEventBus toolEventBus,
            TemplateRegistry templateRegistry,
            WorkspaceManagerFactory workspaceManagerFactory,
            UserStore userStore,
            AgentAclService aclService) {
        this.builderBootstrap = builderBootstrap;
        this.store = store;
        this.model = modelOpt.orElse(null);
        this.toolEventBus = toolEventBus;
        this.templateRegistry = templateRegistry;
        this.workspaceManagerFactory = workspaceManagerFactory;
        this.userStore = userStore;
        this.aclService = aclService;
    }

    // -----------------------------------------------------------------
    // 查询
    // -----------------------------------------------------------------

    /**
     * 列出给定用户可见的全部定义：全局、自有自定义，以及通过 {@link
     * io.agentscope.dataagent.web.share.AgentShareGrant} 共享给该用户的自定义智能体（USER 或 WORKSPACE 受让方）。
     * <p>全局始终在前；用户智能体按所有者插入顺序排列。相同 ID 折叠为首个匹配项，
     * 在自定义项中通常是用户自有副本。
     */
    public List<AgentDefinition> listVisible(String userId) {
        List<AgentDefinition> result = new ArrayList<>(globalDefinitions());
        Map<String, AgentDefinition> visibleUserAgents = new LinkedHashMap<>();
        // 先处理自有智能体，使其在与被共享项 ID 冲突时优先。
        for (AgentDefinition def : userDefinitions(userId)) {
            visibleUserAgents.put(def.id(), def);
        }
        // 再处理其他所有者的智能体，经 ACL 过滤。
        for (UserRecord owner : userStore.listAll()) {
            if (owner.userId().equals(userId)) continue;
            for (UserAgentDefinitionStore.StoredEntry e : store.list(owner.userId())) {
                AgentDefinition def = e.toDefinition(owner.userId());
                if (aclService.tierFor(userId, def) != null) {
                    visibleUserAgents.putIfAbsent(def.id(), def);
                }
            }
        }
        result.addAll(visibleUserAgents.values());
        return result;
    }

    /** 按 ID 查找单个可见定义，先检查全局，再检查自定义（自有或被共享）。 */
    public Optional<AgentDefinition> findVisible(String userId, String agentId) {
        return listVisible(userId).stream().filter(d -> d.id().equals(agentId)).findFirst();
    }

    /** 返回用户自定义智能体的所有者；全局或未知 ID 返回 {@link Optional#empty()}。共享、克隆及 EDIT 委托变更流程据此解析存储命名空间。 */
    public Optional<String> findOwnerOf(String agentId) {
        if (isGlobal(agentId)) return Optional.empty();
        for (UserRecord owner : userStore.listAll()) {
            if (store.findById(owner.userId(), agentId).isPresent()) {
                return Optional.of(owner.userId());
            }
        }
        return Optional.empty();
    }

    /** 跨所有者按 ID 查找用户自定义智能体的存储条目。 */
    public Optional<UserAgentDefinitionStore.StoredEntry> findStoredEntry(String agentId) {
        for (UserRecord owner : userStore.listAll()) {
            Optional<UserAgentDefinitionStore.StoredEntry> e =
                    store.findById(owner.userId(), agentId);
            if (e.isPresent()) return e;
        }
        return Optional.empty();
    }

    /** ID 指向全局（项目级）智能体时返回 {@code true}。 */
    public boolean isGlobal(String agentId) {
        return builderBootstrap.agents().containsKey(agentId);
    }

    // -----------------------------------------------------------------
    // 变更（仅用户自定义智能体）
    // -----------------------------------------------------------------

    /** 为给定用户创建自定义智能体定义。 */
    public AgentDefinition createUserAgent(String userId, AgentCreateRequest req) {
        validateRequest(req);

        String id =
                sanitizeId(
                        req.id() != null && !req.id().isBlank()
                                ? req.id()
                                : UUID.randomUUID().toString().replace("-", "").substring(0, 8));

        if (store.findById(userId, id).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Agent with id '" + id + "' already exists");
        }
        if (isGlobal(id)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Agent id '" + id + "' conflicts with a global agent");
        }

        long now = System.currentTimeMillis();
        String workspacePath = normalizeWorkspacePathInput(req.workspacePath());
        if (workspacePath == null) {
            workspacePath = id + WORKSPACE_DIR_SUFFIX;
        }
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
                        req.skillsAllow(),
                        req.skillsDeny(),
                        now,
                        now,
                        null, // shares — 新智能体默认不共享
                        AgentDefinition.RUN_AS_INVOKER,
                        null,
                        workspacePath,
                        req.skillRepositories(),
                        req.sandboxMode(),
                        req.sandboxScope());
        store.save(userId, entry);
        log.info("User '{}' created custom agent '{}'", userId, id);

        // 工作区脚手架：同时提供模板与 AI 草稿时，模板优先；否则
        // 回退到 WorkspaceScaffolder 默认值。失败仅记录日志，不回滚
        // 保存，因为工作区可随时从目录配置重新生成。
        Path workspace = userWorkspacePath(userId, entry);
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
                    "Failed to scaffold workspace for user-custom agent '{}/{}' at {}: {}",
                    userId,
                    id,
                    workspace,
                    e.getMessage());
        }

        return entry.toDefinition(userId);
    }

    private Path userWorkspacePath(String userId, UserAgentDefinitionStore.StoredEntry entry) {
        return workspaceManagerFactory.resolveAgentDataPath(entry.workspacePath(), entry.id());
    }

    /** 自动追加到用户工作区路径最后一段的后缀。 */
    static final String WORKSPACE_DIR_SUFFIX = "-workspace";

    /** 修剪用户工作区路径输入。空白返回 {@code null}，让运行时解析器回退到智能体 ID。绝对路径原样传递；相对路径含 {@code ..} 穿越段时拒绝。末段尚未以 {@code -workspace} 结尾时追加后缀，保持一致磁盘命名约定。 */
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
        Path fileName = p.getFileName();
        if (fileName == null) {
            return trimmed;
        }
        String leaf = fileName.toString();
        if (leaf.endsWith(WORKSPACE_DIR_SUFFIX)) {
            return trimmed;
        }
        String suffixed = leaf + WORKSPACE_DIR_SUFFIX;
        Path parent = p.getParent();
        Path rebuilt = parent != null ? parent.resolve(suffixed) : Paths.get(suffixed);
        return rebuilt.toString();
    }

    /** 将 AI 建议具体化到工作区：从 {@code (name, description, sysPrompt)} 生成 {@code AGENTS.md}，从 {@code suggestedTools} 生成 {@code tools.json}，为每个 {@code suggestedSkills} 项写技能文件，为每个 {@code suggestedSubagents} 项写子智能体文件，并创建 {@code memory/.gitkeep}。 */
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

        // tools.json
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

        // 技能
        if (draft.suggestedSkills() != null) {
            for (NamedFile sk : draft.suggestedSkills()) {
                if (sk == null || sk.name() == null || sk.name().isBlank()) continue;
                Path skillDir = workspace.resolve("skills").resolve(sanitizeName(sk.name()));
                Files.createDirectories(skillDir);
                writeIfMissing(
                        skillDir.resolve("SKILL.md"), sk.content() != null ? sk.content() : "");
            }
        }

        // 子智能体
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

    /** 更新已有用户自定义定义；传入的是所有者存储身份，调用端负责检查所有权或委托 EDIT 权限。 */
    public AgentDefinition updateUserAgent(String userId, String agentId, AgentCreateRequest req) {
        validateRequest(req);
        UserAgentDefinitionStore.StoredEntry existing =
                store.findById(userId, agentId)
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
                        req.skillsAllow() != null ? req.skillsAllow() : existing.skillsAllow(),
                        req.skillsDeny() != null ? req.skillsDeny() : existing.skillsDeny(),
                        existing.createdAt(),
                        now,
                        existing.shares(), // 共享通过共享 API 管理，不属于设置修改
                        existing.runAs() != null
                                ? existing.runAs()
                                : AgentDefinition.RUN_AS_INVOKER,
                        existing.forkOf(),
                        existing.workspacePath(), // workspacePath 只可在创建时设置
                        req.skillRepositories() != null
                                ? req.skillRepositories()
                                : existing.skillRepositories(),
                        req.sandboxMode() != null ? req.sandboxMode() : existing.sandboxMode(),
                        req.sandboxScope() != null ? req.sandboxScope() : existing.sandboxScope());
        store.save(userId, updated);

        // 淘汰网关注册缓存，使下一次对话读取新定义。
        registeredUcaIds.remove(ucaCacheKey(userId, agentId));

        log.info("User '{}' updated custom agent '{}'", userId, agentId);
        return updated.toDefinition(userId);
    }

    /**
     * 在 {@code newOwnerId} 命名空间创建 {@code (srcOwnerId, srcAgentId)} 的克隆。
     * 复制设置（名称、描述、系统提示词、工具、技能、身份），并标记 {@code forkOf = srcAgentId}。
     * 共享、会话与通道绑定初始为空，参见计划 §5。
     * <p>调用方负责调用 {@link
     * io.agentscope.dataagent.web.util.WorkspaceCopier#copy} 复制文件；此方法只写目录条目。
     * @param requestedId 可选目标 ID；空白或在新所有者命名空间已占用时生成短随机 ID
     * @param requestedName 可选显示名，默认为源名称加上 " (copy)"
     */
    public StoredEntryAndDefinition prepareClone(
            String srcOwnerId,
            String srcAgentId,
            String newOwnerId,
            String requestedId,
            String requestedName) {
        UserAgentDefinitionStore.StoredEntry src =
                store.findById(srcOwnerId, srcAgentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Source agent not found: " + srcAgentId));

        String id;
        if (requestedId != null && !requestedId.isBlank()) {
            id = sanitizeId(requestedId);
            if (store.findById(newOwnerId, id).isPresent() || isGlobal(id)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Agent id '" + id + "' already taken");
            }
        } else {
            id = uniqueIdInNamespace(newOwnerId, srcAgentId);
        }

        String name =
                requestedName != null && !requestedName.isBlank()
                        ? requestedName
                        : (src.name() != null ? src.name() + " (copy)" : id);

        long now = System.currentTimeMillis();
        UserAgentDefinitionStore.StoredEntry clone =
                new UserAgentDefinitionStore.StoredEntry(
                        id,
                        name,
                        src.description(),
                        src.sysPrompt(),
                        src.model(),
                        src.maxIters(),
                        src.toolsAllow(),
                        src.toolsDeny(),
                        src.identityName(),
                        src.identityEmoji(),
                        src.groupChatMentionPatterns(),
                        src.groupChatRequireMention(),
                        src.skillsAllow(),
                        src.skillsDeny(),
                        now,
                        now,
                        null, // shares — 克隆默认不共享
                        src.runAs(),
                        srcAgentId, // forkOf：来源智能体：来源智能体：来源智能体：来源智能体
                        id + WORKSPACE_DIR_SUFFIX, // workspacePath — 克隆使用自身 ID 加
                        // 后缀
                        src.skillRepositories(),
                        src.sandboxMode(),
                        src.sandboxScope());
        store.save(newOwnerId, clone);
        log.info(
                "User '{}' cloned agent '{}/{}' as '{}/{}'",
                newOwnerId,
                srcOwnerId,
                srcAgentId,
                newOwnerId,
                id);
        return new StoredEntryAndDefinition(clone, clone.toDefinition(newOwnerId));
    }

    private String uniqueIdInNamespace(String owner, String preferredBase) {
        String base = sanitizeId(preferredBase);
        if (!store.findById(owner, base).isPresent() && !isGlobal(base)) {
            return base + "-copy";
        }
        for (int i = 0; i < 16; i++) {
            String candidate =
                    base + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
            if (!store.findById(owner, candidate).isPresent() && !isGlobal(candidate)) {
                return candidate;
            }
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Could not allocate a unique agent id for clone");
    }

    /** 新克隆条目及其 API 视图的载体。 */
    public record StoredEntryAndDefinition(
            UserAgentDefinitionStore.StoredEntry entry, AgentDefinition definition) {}

    /** 删除用户自定义定义；调用端应先验证所有者或 EDIT 授权，再传入所有者存储身份。 */
    public void deleteUserAgent(String userId, String agentId) {
        if (!store.delete(userId, agentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: " + agentId);
        }
        registeredUcaIds.remove(ucaCacheKey(userId, agentId));
        log.info("User '{}' deleted custom agent '{}'", userId, agentId);
    }

    /** 移除 {@code (userId, agentId)} 的 UCA 注册缓存，使下一次聊天根据当前 {@link UserAgentDefinitionStore} 条目重建 {@link HarnessAgent}。供智能体已实例化后修改 tools.json、skills/ 等运行时资源的控制器使用。 */
    public void invalidateUca(String userId, String agentId) {
        if (userId == null || agentId == null) return;
        registeredUcaIds.remove(ucaCacheKey(userId, agentId));
    }

    /** 解析 {@code (userId, agentId)} 对应的运行中 {@link HarnessAgent}，首次访问时构建并注册 UCA。全局智能体直接返回引导阶段实例；用户不可见时为 {@code null}。供需检查网关实际路由目标的运行时状态（技能仓库、工具包）的控制器使用。 */
    public HarnessAgent getRunningAgent(String userId, String agentId) {
        if (agentId == null) return null;
        if (isGlobal(agentId)) {
            return builderBootstrap.agents().get(agentId);
        }
        if (findVisible(userId, agentId).isEmpty()) {
            return null;
        }
        String gatewayId = resolveGatewayAgentId(userId, agentId);
        return builderBootstrap.gateway().findAgent(gatewayId);
    }

    // -----------------------------------------------------------------
    // 网关路由支持
    // -----------------------------------------------------------------

    /**
     * 解析向指定智能体路由聊天消息时使用的网关 ID。
     * <ul>
     *   <li>全局智能体：原样返回 ID，因已在网关注册。</li>
     *   <li>用户智能体：确保已构建并注册，再返回命名空间网关 ID（{@code uca-{userId}-{agentId}}）。</li>
     * </ul>
     * @throws ResponseStatusException 智能体对用户不可见时为 404
     */
    public String resolveGatewayAgentId(String userId, String agentId) {
        if (isGlobal(agentId)) {
            return agentId;
        }

        UserAgentDefinitionStore.StoredEntry entry =
                store.findById(userId, agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found or not accessible: " + agentId));

        String cacheKey = ucaCacheKey(userId, agentId);
        return registeredUcaIds.computeIfAbsent(cacheKey, k -> buildAndRegisterUca(userId, entry));
    }

    /** 返回 {@link #resolveGatewayAgentId} 将生成的网关 ID，但不构建或注册智能体。适合只需要 ID 格式的只读查找，如会话过滤、审计检查。 */
    public String peekGatewayAgentId(String userId, String agentId) {
        if (agentId == null) return null;
        if (isGlobal(agentId)) return agentId;
        return UCA_PREFIX + userId + "-" + agentId;
    }

    // -----------------------------------------------------------------
    // 内部实现
    // -----------------------------------------------------------------

    private List<AgentDefinition> globalDefinitions() {
        Map<String, AgentConfigEntry> fileAgents = builderBootstrap.loadedConfig().getAgents();
        List<AgentDefinition> result = new ArrayList<>();
        for (Map.Entry<String, HarnessAgent> e : builderBootstrap.agents().entrySet()) {
            String id = e.getKey();
            AgentConfigEntry cfg = fileAgents != null ? fileAgents.get(id) : null;
            String name = cfg != null && cfg.getName() != null ? cfg.getName() : id;
            String desc = cfg != null ? cfg.getDescription() : null;

            // HarnessAgent 未暴露公共 getToolkit()；报告标准内置工具。
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
            AgentConfigEntry.SkillsConfig sk = cfg != null ? cfg.getSkills() : null;

            result.add(
                    new AgentDefinition(
                            id,
                            name,
                            desc,
                            null, // 全局目录不暴露 sysPrompt
                            cfg != null ? cfg.getModel() : null,
                            cfg != null ? cfg.getMaxIters() : null,
                            toolNames,
                            tc != null ? tc.getAllow() : null,
                            tc != null ? tc.getDeny() : null,
                            ic != null ? ic.getName() : null,
                            ic != null ? ic.getEmoji() : null,
                            gc != null ? gc.getMentionPatterns() : null,
                            gc != null ? gc.getRequireMention() : null,
                            sk != null ? sk.getAllow() : null,
                            sk != null ? sk.getDeny() : null,
                            AgentDefinition.SCOPE_GLOBAL,
                            null,
                            0L,
                            0L,
                            null, // shares — 全局智能体不单独共享
                            AgentDefinition.RUN_AS_INVOKER,
                            null, // forkOf
                            cfg != null ? cfg.getWorkspace() : null, // 映射运行时工作区
                            null, // sandboxMode — 全局智能体遵循平台默认值
                            null, // sandboxScope：沙箱隔离范围：沙箱隔离范围
                            null)); // tierForCurrentUser — 由控制器填充
        }
        return result;
    }

    private List<AgentDefinition> userDefinitions(String userId) {
        return store.list(userId).stream().map(e -> e.toDefinition(userId)).toList();
    }

    private String buildAndRegisterUca(String userId, UserAgentDefinitionStore.StoredEntry entry) {
        String gatewayAgentId = UCA_PREFIX + userId + "-" + entry.id();

        Path workspace = userWorkspacePath(userId, entry);

        HarnessAgent.Builder b = HarnessAgent.builder();

        String name = entry.name() != null ? entry.name() : entry.id();
        b.name(name);

        if (entry.description() != null) {
            b.description(entry.description());
        }
        if (entry.sysPrompt() != null) {
            b.sysPrompt(entry.sysPrompt());
        }
        if (entry.maxIters() != null) {
            b.maxIters(entry.maxIters());
        }
        // 模型优先使用逐智能体覆盖值，回退到引导层模型。
        if (entry.model() != null && !entry.model().isBlank()) {
            b.model(entry.model());
        } else if (model != null) {
            b.model(model);
        }
        b.workspace(workspace);

        // 分层技能仓库：工作区覆盖层隐式存在；按保存定义的顺序
        // 追加显式仓库项，较早项在技能重名时优先。
        if (entry.skillRepositories() != null && !entry.skillRepositories().isEmpty()) {
            var repos =
                    io.agentscope.dataagent.runtime.config.SkillRepositorySupport.createAll(
                            workspace, entry.skillRepositories());
            if (!repos.isEmpty()) {
                b.skillRepositories(repos);
            }
        }

        // 预先向此自定义智能体工具包加入出站发送工具，使其能
        // 主动向已注册 IM 通道发送消息；实际约束由 HTTP 入口的认证/权限
        // 策略及 OutboundService 的通道路由检查共同决定。
        io.agentscope.core.tool.Toolkit ucaToolkit = new io.agentscope.core.tool.Toolkit();
        ucaToolkit.registerTool(
                new io.agentscope.dataagent.runtime.outbound.OutboundTool(
                        builderBootstrap.channelManager()));
        b.toolkit(ucaToolkit);

        // 注入 ToolNotificationMiddleware，使用户智能体也发布工具调用事件。
        b.middleware(
                new io.agentscope.dataagent.web.toolbus.ToolNotificationMiddleware(toolEventBus));

        HarnessAgent agent = b.build();

        HarnessGateway gateway = builderBootstrap.gateway();
        gateway.registerAgent(gatewayAgentId, agent);

        log.info(
                "Registered user-custom agent in gateway: userId={}, agentId={}, gatewayId={}",
                userId,
                entry.id(),
                gatewayAgentId);

        return gatewayAgentId;
    }

    private static String ucaCacheKey(String userId, String agentId) {
        return userId + "/" + agentId;
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

    /** 创建或更新用户自定义智能体的请求体。 */
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
            List<String> skillsAllow,
            List<String> skillsDeny,
            String workspacePath,
            String templateId,
            AgentDraft aiDraft,
            List<io.agentscope.dataagent.runtime.config.SkillRepositoryConfigEntry>
                    skillRepositories,
            String sandboxMode,
            String sandboxScope) {}

    /** 创建请求可附带的 AI 草稿，含建议配置及可选技能/子智能体文件，用于新工作区脚手架，由 {@link #createUserAgent(String, AgentCreateRequest)} 接入。 */
    public record AgentDraft(
            String name,
            String description,
            String sysPrompt,
            List<String> suggestedTools,
            List<NamedFile> suggestedSkills,
            List<NamedFile> suggestedSubagents) {}

    /** 带名称的文件（例如 Markdown 技能或子智能体定义）。 */
    public record NamedFile(String name, String content) {}
}
