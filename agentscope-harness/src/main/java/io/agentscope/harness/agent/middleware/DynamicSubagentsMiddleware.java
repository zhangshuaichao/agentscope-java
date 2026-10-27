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
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import io.agentscope.harness.agent.tool.TaskTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * {@link SubagentsMiddleware} 的动态版本，在每次推理时重新解析已注册的子智能体集合，
 * 通过工作区 {@link AbstractFilesystem} 支持按用户隔离，例如
 * {@code CompositeFilesystem} 将用户作用域写入路由到远程 Store。
 *
 * <p><strong>两层加载</strong>，保持旧版 {@code DynamicSubagentsHook} 的覆盖语义：
 * <ol>
 *   <li><em>第 1 层（覆盖层）</em> —— {@code filesystem.glob("*.md", "subagents")}
 *       加逐文件 {@code filesystem.read}。后端透明应用 {@code NamespaceFactory}，
 *       使每个用户只看到属于自己的存储分区。
 *   <li><em>第 2 层（基础层）</em> —— {@code AgentSpecLoader.loadFromDirectory}
 *       直接读取本地工作区的 {@code subagents/} 目录。
 *   <li><em>合并</em> —— 第 1 层同名条目覆盖第 2 层。
 *       程序化（建造者注册的）条目保留为静态前缀，但同名动态声明也会覆盖它们。
 * </ol>
 */
public class DynamicSubagentsMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(DynamicSubagentsMiddleware.class);

    private static final String SUBAGENTS_DIR = "subagents";
    private static final String SUBAGENT_GLOB = "*.md";

    private final List<SubagentEntry> staticEntries;
    private final AbstractFilesystem filesystem;
    private final Path mainWorkspace;
    private final Function<SubagentDeclaration, SubagentFactory> factoryBuilder;
    private final DefaultAgentManager agentManager;
    private volatile Object subagentTool;
    private final TaskTool taskTool;
    private final TaskRepository taskRepository;

    public DynamicSubagentsMiddleware(
            List<SubagentEntry> staticEntries,
            AbstractFilesystem filesystem,
            Path mainWorkspace,
            Function<SubagentDeclaration, SubagentFactory> factoryBuilder,
            DefaultAgentManager agentManager,
            Object subagentTool,
            TaskRepository taskRepository) {
        this.staticEntries = List.copyOf(staticEntries != null ? staticEntries : List.of());
        this.filesystem = filesystem;
        this.mainWorkspace = mainWorkspace;
        this.factoryBuilder = factoryBuilder;
        this.agentManager = agentManager;
        java.util.Objects.requireNonNull(taskRepository, "taskRepository");
        this.taskRepository = taskRepository;
        this.subagentTool =
                subagentTool != null
                        ? subagentTool
                        : new AgentSpawnTool(agentManager, taskRepository, 0);
        this.taskTool = new TaskTool(taskRepository);
    }

    /** 仅声明所需扩展点；子类若重写更多钩子，必须扩展此集合。 */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_REASONING);
    }

    /**
     * 将网关桥接器接入内部 {@link AgentSpawnTool}，使新建子智能体可暴露为用户可寻址的线程。
     * 仅在中间件拥有 {@link DefaultAgentManager} 时生效。
     */
    public DynamicSubagentsMiddleware setGatewayBridge(
            io.agentscope.harness.agent.gateway.SubagentGatewayBridge bridge) {
        if (agentManager == null) {
            return this;
        }
        // 修改活动工具上的桥接器，而不是替换工具：编排时，工具集将
        // agent_spawn 绑定到 getTools() 返回的 AgentSpawnTool 实例，
        // 因此在此创建新实例永远不会被调用，线程暴露也就会
        // 静默失效。
        if (this.subagentTool instanceof AgentSpawnTool ast) {
            ast.setGatewayBridge(bridge);
        } else {
            this.subagentTool = new AgentSpawnTool(agentManager, taskRepository, 0, bridge);
        }
        return this;
    }

    /**
     * 返回可重新物化子智能体的内部 {@link DefaultAgentManager}；
     * 没有自行拥有的管理器时为 {@code null}。
     * 用于连接网关物化器，实现跨节点恢复已暴露的子智能体。
     */
    public DefaultAgentManager getAgentManager() {
        return agentManager;
    }

    /**
     * 返回此中间件向智能体工具集贡献的工具实例。
     * 调用方负责在编排阶段将它们注册到工具集。
     */
    public List<Object> getTools() {
        return List.of(subagentTool, taskTool);
    }

    public TaskRepository getTaskRepository() {
        return taskRepository;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        List<SubagentEntry> merged = reloadEntries(rc);
        if (agentManager != null) {
            agentManager.replaceAgents(merged);
        }
        Msg stateMsg =
                SubagentsMiddleware.buildSubagentStateMsg(
                        merged, SubagentsMiddleware.buildTaskSummary(taskRepository, rc));
        List<Msg> rebuilt =
                SubagentsMiddleware.prependToSystemMessage(
                        input.messages(), SubagentsMiddleware.renderSubagentSection(merged, false));
        if (stateMsg != null) {
            rebuilt = new ArrayList<>(rebuilt);
            rebuilt.add(stateMsg);
        }
        return next.apply(new ReasoningInput(rebuilt, input.tools(), input.options()));
    }

    private List<SubagentEntry> reloadEntries(RuntimeContext rc) {
        // ---- 第 2 层（基础层）：扫描本地工作区 ----
        Map<String, SubagentDeclaration> declsByName = new LinkedHashMap<>();
        Path subagentsDir = mainWorkspace != null ? mainWorkspace.resolve(SUBAGENTS_DIR) : null;
        if (subagentsDir != null && Files.isDirectory(subagentsDir)) {
            for (SubagentDeclaration d :
                    AgentSpecLoader.loadFromDirectory(subagentsDir, mainWorkspace)) {
                declsByName.put(d.getName(), d);
            }
        }

        // ---- 第 1 层（覆盖层）：带命名空间的文件系统 ----
        if (filesystem != null) {
            for (SubagentDeclaration d : loadDeclarationsViaFilesystem(rc)) {
                declsByName.put(d.getName(), d);
            }
        }

        // ---- 物化工厂 ----
        List<SubagentEntry> dynamicEntries = new ArrayList<>(declsByName.size());
        for (SubagentDeclaration decl : declsByName.values()) {
            if (factoryBuilder == null) {
                continue;
            }
            try {
                SubagentFactory factory = factoryBuilder.apply(decl);
                if (factory == null) {
                    continue;
                }
                dynamicEntries.add(
                        new SubagentEntry(decl.getName(), decl.getDescription(), factory, decl));
            } catch (Exception e) {
                log.warn(
                        "Failed to build factory for declared subagent '{}': {}",
                        decl.getName(),
                        e.getMessage());
            }
        }

        // ---- 合并：静态 + 动态，同名冲突时动态条目优先 ----
        Map<String, SubagentEntry> combined = new LinkedHashMap<>();
        for (SubagentEntry e : staticEntries) {
            combined.put(e.name(), e);
        }
        for (SubagentEntry e : dynamicEntries) {
            combined.put(e.name(), e);
        }
        return List.copyOf(combined.values());
    }

    private List<SubagentDeclaration> loadDeclarationsViaFilesystem(RuntimeContext rc) {
        GlobResult glob;
        try {
            glob = filesystem.glob(rc, SUBAGENT_GLOB, SUBAGENTS_DIR);
        } catch (Exception e) {
            log.debug("Filesystem glob for subagents failed: {}", e.getMessage());
            return Collections.emptyList();
        }
        if (!glob.isSuccess() || glob.matches() == null || glob.matches().isEmpty()) {
            return Collections.emptyList();
        }

        List<SubagentDeclaration> decls = new ArrayList<>();
        for (FileInfo fi : glob.matches()) {
            String path = fi.path();
            if (path == null || path.isBlank()) {
                continue;
            }
            String fileName = extractFileName(path);
            if (!fileName.endsWith(".md")) {
                continue;
            }
            String name = fileName.substring(0, fileName.length() - 3);
            if (name.isEmpty()) {
                continue;
            }
            try {
                ReadResult rr = filesystem.read(rc, path, 0, 0);
                if (!rr.isSuccess() || rr.fileData() == null || rr.fileData().content() == null) {
                    continue;
                }
                SubagentDeclaration decl =
                        AgentSpecLoader.parse(rr.fileData().content(), name, mainWorkspace);
                if (decl != null) {
                    decls.add(decl);
                }
            } catch (Exception e) {
                log.warn("Failed to load subagent declaration from '{}': {}", path, e.getMessage());
            }
        }
        return decls;
    }

    private static String extractFileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
