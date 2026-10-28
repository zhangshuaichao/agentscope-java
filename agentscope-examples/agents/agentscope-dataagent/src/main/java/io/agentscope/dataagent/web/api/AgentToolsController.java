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
package io.agentscope.dataagent.web.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.dataagent.web.audit.ActivityEvent;
import io.agentscope.dataagent.web.audit.AgentActivityStore;
import io.agentscope.dataagent.web.catalog.AgentCatalogService;
import io.agentscope.dataagent.web.catalog.AgentDefinition;
import io.agentscope.dataagent.web.catalog.UserAgentDefinitionStore;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.share.AgentAclService.Tier;
import io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 多租户平台中智能体的工具管理端点。URL 与请求结构对应 claw 的 {@code AgentToolsController}，
 * 但每个入口都经 {@link AgentAccessGuard} 检查；工作区 I/O 通过 {@link WorkspaceManagerFactory}
 * 使用运行时对应的拥有者/智能体命名空间，而非任意调用者的本地目录。
 * <p>端点：
 * <ul>
 * <li>{@code GET /active} — 当前工具列表，要求 RUN。用空操作模型构造临时 {@link HarnessAgent}，
 * 工作区来自 {@link WorkspaceManagerFactory#forAgent}（全局智能体使用 {@code forGlobalAgent}），
 * 因此按实际运行命名空间检查 {@code tools.json} 中的 MCP 服务配置。
 * <li>{@code GET /config} — 读取 {@code workspace/tools.json}，要求 RUN。
 * <li>{@code PUT /config} — 覆写 {@code workspace/tools.json}，要求 EDIT；记录
 * {@link ActivityEvent.Action#EDIT_SETTINGS}，并使 UCA 注册缓存失效，后续聊天重建智能体。
 * <li>{@code GET /catalog/builtins} — 框架内置工具静态目录，要求 RUN。
 * <li>{@code GET /catalog/mcp-servers} — 来自 {@code classpath:catalog/mcp-servers.json} 的内置 MCP 服务模板，要求 RUN。
 * </ul>
 */
@RestController
@RequestMapping("/api/agents/{agentId}/tools")
public class AgentToolsController {

    private static final Logger log = LoggerFactory.getLogger(AgentToolsController.class);

    /**
     * 框架内置工具的标准列表，对应 {@code HarnessAgent.Builder.build()} 的注册项。
     * 用于 {@code /active} 的来源归类及 {@code /catalog/builtins} 的响应。
     */
    private static final List<BuiltinToolInfo> BUILTIN_TOOLS =
            List.of(
                    new BuiltinToolInfo(
                            "read_file", "Read a file from the workspace.", "filesystem"),
                    new BuiltinToolInfo(
                            "write_file",
                            "Write or overwrite a file in the workspace.",
                            "filesystem"),
                    new BuiltinToolInfo(
                            "edit_file", "Apply a diff to an existing file.", "filesystem"),
                    new BuiltinToolInfo(
                            "list_files", "List files under a directory.", "filesystem"),
                    new BuiltinToolInfo(
                            "grep_files", "Search file contents with a regex.", "filesystem"),
                    new BuiltinToolInfo(
                            "glob_files", "Find files matching a glob pattern.", "filesystem"),
                    new BuiltinToolInfo(
                            "memory_search",
                            "Semantic search across the agent's long-term memory.",
                            "memory"),
                    new BuiltinToolInfo(
                            "memory_get", "Fetch a specific memory entry by id.", "memory"),
                    new BuiltinToolInfo(
                            "session_search",
                            "Search prior session transcripts for context.",
                            "memory"),
                    new BuiltinToolInfo(
                            "execute",
                            "Execute a shell command (sandbox / local-shell modes only).",
                            "shell"));

    private static final Set<String> BUILTIN_NAMES;

    static {
        BUILTIN_NAMES = new HashSet<>();
        for (BuiltinToolInfo b : BUILTIN_TOOLS) BUILTIN_NAMES.add(b.id());
    }

    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .enable(SerializationFeature.INDENT_OUTPUT);

    private final AgentAccessGuard guard;
    private final AgentActivityStore activity;
    private final AgentCatalogService catalogService;
    private final WorkspaceManagerFactory workspaceFactory;
    private final List<McpCatalogEntry> mcpCatalog;

    public AgentToolsController(
            AgentAccessGuard guard,
            AgentActivityStore activity,
            AgentCatalogService catalogService,
            WorkspaceManagerFactory workspaceFactory) {
        this.guard = guard;
        this.activity = activity;
        this.catalogService = catalogService;
        this.workspaceFactory = workspaceFactory;
        this.mcpCatalog = loadMcpCatalog();
    }

    // -----------------------------------------------------------------
    // 当前工具列表
    // -----------------------------------------------------------------

    @GetMapping("/active")
    public Mono<ActiveToolsResponse> active(@PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.RUN);
                    return introspect(userId, agentId);
                });
    }

    private ActiveToolsResponse introspect(String userId, String agentId) {
        List<String> warnings = new ArrayList<>();
        WorkspaceManager wsm = resolveWorkspaceManager(userId, agentId);
        try {
            HarnessAgent agent =
                    HarnessAgent.builder()
                            .name("__tools_introspect__")
                            .model(new NoopModel())
                            .workspace(wsm.getWorkspace())
                            .abstractFilesystem(wsm.getFilesystem())
                            .build();
            List<ToolSchema> schemas = agent.getDelegate().getToolkit().getToolSchemas();
            List<ActiveTool> tools = new ArrayList<>();
            for (ToolSchema s : schemas) {
                String source = BUILTIN_NAMES.contains(s.getName()) ? "built-in" : "mcp";
                tools.add(new ActiveTool(s.getName(), s.getDescription(), source));
            }
            return new ActiveToolsResponse(tools, warnings);
        } catch (Exception e) {
            log.warn("Transient agent build failed for {}/{}: {}", userId, agentId, e.getMessage());
            warnings.add(
                    "Live introspection failed ("
                            + e.getMessage()
                            + "). Showing config-only view.");
            return configOnlyView(wsm, warnings);
        }
    }

    private ActiveToolsResponse configOnlyView(WorkspaceManager wsm, List<String> warnings) {
        ToolsConfig cfg = readConfig(wsm);
        List<ActiveTool> tools = new ArrayList<>();
        Set<String> deny =
                cfg != null && cfg.getDeny() != null ? new HashSet<>(cfg.getDeny()) : Set.of();
        Set<String> allow =
                cfg != null && cfg.getAllow() != null && !cfg.getAllow().isEmpty()
                        ? new HashSet<>(cfg.getAllow())
                        : null;
        for (BuiltinToolInfo b : BUILTIN_TOOLS) {
            if (deny.contains(b.id())) continue;
            if (allow != null && !allow.contains(b.id())) continue;
            tools.add(new ActiveTool(b.id(), b.description(), "built-in"));
        }
        if (cfg != null && cfg.getMcpServers() != null) {
            for (Map.Entry<String, McpServerConfig> e : cfg.getMcpServers().entrySet()) {
                tools.add(
                        new ActiveTool(
                                e.getKey(),
                                "MCP server (" + e.getValue().getTransport() + ")",
                                "mcp"));
            }
        }
        return new ActiveToolsResponse(tools, warnings);
    }

    // -----------------------------------------------------------------
    // tools.json 读写
    // -----------------------------------------------------------------

    @GetMapping("/config")
    public Mono<ToolsConfig> getConfig(@PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.RUN);
                    WorkspaceManager wsm = resolveWorkspaceManager(userId, agentId);
                    ToolsConfig cfg = readConfig(wsm);
                    return cfg != null ? cfg : new ToolsConfig();
                });
    }

    @PutMapping("/config")
    public Mono<ToolsConfig> putConfig(
            @PathVariable String agentId, @RequestBody ToolsConfig body, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    if (body == null) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "Request body is required");
                    }
                    AgentDefinition def = guard.require(userId, agentId, Tier.EDIT);
                    validate(body);
                    WorkspaceManager wsm = resolveWorkspaceManager(userId, agentId);
                    String json;
                    try {
                        json = MAPPER.writeValueAsString(body);
                    } catch (IOException e) {
                        throw new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                "Failed to serialize tools.json: " + e.getMessage());
                    }
                    wsm.writeUtf8WorkspaceRelative(
                            RuntimeContext.empty(), "tools.json", json + "\n");
                    String ownerId =
                            def.ownerId() != null
                                    ? def.ownerId()
                                    : catalogService.findOwnerOf(agentId).orElse(userId);
                    activity.record(
                            ownerId,
                            agentId,
                            activity.actor(userId),
                            ActivityEvent.Action.EDIT_SETTINGS,
                            "tools.json",
                            Map.of(
                                    "allowCount",
                                            body.getAllow() != null ? body.getAllow().size() : 0,
                                    "denyCount", body.getDeny() != null ? body.getDeny().size() : 0,
                                    "mcpCount",
                                            body.getMcpServers() != null
                                                    ? body.getMcpServers().size()
                                                    : 0));
                    catalogService.invalidateUca(ownerId, agentId);
                    return body;
                });
    }

    private ToolsConfig readConfig(WorkspaceManager wsm) {
        try {
            String raw = wsm.readManagedWorkspaceFileUtf8(RuntimeContext.empty(), "tools.json");
            if (raw == null || raw.isBlank()) return null;
            return MAPPER.readValue(raw, ToolsConfig.class);
        } catch (Exception e) {
            log.debug("tools.json missing or unreadable: {}", e.getMessage());
            return null;
        }
    }

    private static void validate(ToolsConfig cfg) {
        if (cfg.getAllow() != null) {
            for (String n : cfg.getAllow()) {
                if (n == null || n.isBlank()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "allow contains a blank entry");
                }
            }
        }
        if (cfg.getDeny() != null) {
            for (String n : cfg.getDeny()) {
                if (n == null || n.isBlank()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "deny contains a blank entry");
                }
            }
        }
        if (cfg.getMcpServers() != null) {
            for (Map.Entry<String, McpServerConfig> e : cfg.getMcpServers().entrySet()) {
                String name = e.getKey();
                McpServerConfig s = e.getValue();
                if (name == null || name.isBlank()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "mcpServers contains a blank server name");
                }
                if (s == null) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "mcpServers." + name + " is null");
                }
                String t = s.getTransport();
                if (t == null || t.isBlank()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "mcpServers." + name + ": transport is required");
                }
                String tl = t.toLowerCase(Locale.ROOT);
                switch (tl) {
                    case "stdio" -> {
                        if (s.getCommand() == null || s.getCommand().isBlank()) {
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST,
                                    "mcpServers." + name + ": stdio requires 'command'");
                        }
                    }
                    case "sse", "http", "streamable-http", "streamablehttp" -> {
                        if (s.getUrl() == null || s.getUrl().isBlank()) {
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST,
                                    "mcpServers." + name + ": " + tl + " requires 'url'");
                        }
                    }
                    default ->
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST,
                                    "mcpServers."
                                            + name
                                            + ": unsupported transport '"
                                            + t
                                            + "' (expected stdio, sse, http)");
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // 工具目录
    // -----------------------------------------------------------------

    @GetMapping("/catalog/builtins")
    public Mono<List<BuiltinToolInfo>> catalogBuiltins(
            @PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.RUN);
                    return BUILTIN_TOOLS;
                });
    }

    @GetMapping("/catalog/mcp-servers")
    public Mono<List<McpCatalogEntry>> catalogMcpServers(
            @PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.RUN);
                    return mcpCatalog;
                });
    }

    // -----------------------------------------------------------------
    // 辅助方法
    // -----------------------------------------------------------------

    /**
     * 构造智能体运行时使用的 {@link WorkspaceManager}。
     * 全局智能体使用当前用户区分运行数据，共享工作区内容来自本地磁盘根目录。
     * 用户自定义智能体通过 {@link AgentCatalogService#findOwnerOf} 查找拥有者，
     * 确保共享访问者的写入仍进入原拥有者命名空间，与网关注册一致。
     */
    private WorkspaceManager resolveWorkspaceManager(String userId, String agentId) {
        if (catalogService.isGlobal(agentId)) {
            return workspaceFactory.forGlobalAgent(userId, agentId);
        }
        String ownerId = catalogService.findOwnerOf(agentId).orElse(userId);
        Optional<UserAgentDefinitionStore.StoredEntry> entry =
                catalogService.findStoredEntry(agentId);
        String workspacePath =
                entry.map(UserAgentDefinitionStore.StoredEntry::workspacePath).orElse(null);
        return workspaceFactory.forAgent(ownerId, agentId, workspacePath);
    }

    private static List<McpCatalogEntry> loadMcpCatalog() {
        ClassPathResource r = new ClassPathResource("catalog/mcp-servers.json");
        if (!r.exists()) {
            log.warn("catalog/mcp-servers.json not found on classpath; MCP catalog is empty.");
            return List.of();
        }
        try (InputStream in = r.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            String json = new String(bytes, StandardCharsets.UTF_8);
            McpCatalogEntry[] arr = MAPPER.readValue(json, McpCatalogEntry[].class);
            return List.of(arr);
        } catch (Exception e) {
            log.warn("Failed to load catalog/mcp-servers.json: {}", e.getMessage());
            return List.of();
        }
    }

    // -----------------------------------------------------------------
    // 数据传输对象（DTO）
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActiveTool(String name, String description, String source) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ActiveToolsResponse(List<ActiveTool> tools, List<String> warnings) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BuiltinToolInfo(String id, String description, String group) {}

    /**
     * 从 {@code classpath:catalog/mcp-servers.json} 加载的内置 MCP 服务模板，
     * 结构对应 {@link McpServerConfig} 并附带少量界面元数据。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record McpCatalogEntry(
            String id,
            String name,
            String description,
            String transport,
            String url,
            String command,
            List<String> args,
            Map<String, String> env,
            Map<String, String> headers,
            Map<String, String> queryParams,
            List<String> requiredEnv,
            String docsUrl) {}

    /**
     * 仅为工具模式检查提供的空操作模型，此流程不会调用 {@link #stream}。
     */
    private static final class NoopModel implements Model {
        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.empty();
        }

        @Override
        public String getModelName() {
            return "noop";
        }
    }
}
