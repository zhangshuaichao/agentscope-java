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
package io.agentscope.harness.agent.filesystem.spec;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 非沙箱“组合”文件系统模式的配置描述。
 *
 * <p>此配置创建 {@link CompositeFilesystem}，组合以下后端：
 *
 * <ul>
 *   <li>普通 {@link LocalFilesystem}（无 Shell），处理工作区本地、非受管文件；
 *   <li>按路由划分的 {@link RemoteFilesystem} 实例，处理跨节点路径（记忆、技能、
 *       子智能体、知识、会话、任务）。每条路由使用自己的存储命名空间片段，防止跨路由键冲突。
 * </ul>
 *
 * <p>默认后端是 {@link LocalFilesystem}，而非 {@link LocalFilesystemWithShell}，
 * 因此此模式有意不提供 Shell 执行；需要 Shell 时请使用沙箱文件系统配置或
 * {@link LocalFilesystemWithShell}。
 *
 * <p>默认共享路由（每条都获得隔离的存储命名空间片段）：
 *
 * <ul>
 *   <li>{@code AGENTS.md}、{@code MEMORY.md} → 片段 {@code root}
 *   <li>{@code memory/} → 片段 {@code memory}
 *   <li>{@code skills/} → 片段 {@code skills}
 *   <li>{@code subagents/} → 片段 {@code subagents}
 *   <li>{@code knowledge/} → 片段 {@code knowledge}
 *   <li>{@code plans/} → 片段 {@code plans}
 *   <li>{@code agents/<agentId>/sessions/} → 片段 {@code sessions}
 *   <li>{@code agents/<agentId>/tasks/} → 片段 {@code tasks}
 * </ul>
 *
 * <p>共享文件的存储命名空间由 {@link #isolationScope(IsolationScope)} 控制，与沙箱隔离语义一致：
 *
 * <ul>
 *   <li>{@link IsolationScope#SESSION} — 每个会话一个命名空间</li>
 *   <li>{@link IsolationScope#USER}（默认）— 每个用户一个命名空间，跨会话共享</li>
 *   <li>{@link IsolationScope#AGENT} — 每个智能体一个命名空间，跨所有用户共享</li>
 *   <li>{@link IsolationScope#GLOBAL} — 单个全局命名空间</li>
 * </ul>
 */
public class RemoteFilesystemSpec {

    private BaseStore store;
    private final Set<String> extraSharedPrefixes = new LinkedHashSet<>();
    private String anonymousUserId = "_default";
    private IsolationScope isolationScope = IsolationScope.USER;
    private WorkspaceIndex workspaceIndex = null;
    private boolean sharedLocalWorkspace = false;

    /**
     * 创建远程文件系统配置，将存储解析推迟到
     * {@link io.agentscope.harness.agent.DistributedStore#baseStore()}。
     *
     * <p>与 {@code HarnessAgent.builder().distributedStore(store)} 搭配时，
     * 构建期间自动注入存储。没有分布式存储时，构建阶段抛出 {@link IllegalStateException}。
     */
    public RemoteFilesystemSpec() {
        this.store = null;
    }

    public RemoteFilesystemSpec(BaseStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        this.store = store;
    }

    /**
     * 返回是否已通过构造方法或注入设置存储。
     */
    public boolean hasStore() {
        return store != null;
    }

    /**
     * 返回已配置的 {@link BaseStore}，尚未设置时为 {@code null}。
     *
     * <p>存在远程文件系统但没有完整 {@code DistributedStore} 时，
     * 供协调组件装配（例如 {@code PeriodicGate}）使用。
     */
    public BaseStore store() {
        return store;
    }

    /**
     * 尚未设置存储时注入存储。由构建器在自动装配阶段调用。
     */
    public void injectStoreIfAbsent(BaseStore store) {
        if (this.store == null) {
            this.store = store;
        }
    }

    /**
     * 添加一条路由到共享存储的工作区相对前缀。
     *
     * <p>例如：{@code knowledge/}、{@code prompts/}。
     */
    public RemoteFilesystemSpec addSharedPrefix(String prefix) {
        if (prefix != null && !prefix.isBlank()) {
            extraSharedPrefixes.add(normalizePrefix(prefix));
        }
        return this;
    }

    /**
     * 设置运行时 {@code userId} 缺失或为空白时使用的回退用户标识。
     */
    public RemoteFilesystemSpec anonymousUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("anonymous user id must not be blank");
        }
        this.anonymousUserId = userId;
        return this;
    }

    /**
     * 设置隔离作用域，控制共享文件的存储命名空间。
     *
     * <p>与沙箱 {@link io.agentscope.harness.agent.sandbox.SandboxContext} 的隔离语义一致。
     * 默认 {@link IsolationScope#USER}。
     *
     * @param scope 隔离作用域
     * @return 此配置对象
     */
    public RemoteFilesystemSpec isolationScope(IsolationScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("isolation scope must not be null");
        }
        this.isolationScope = scope;
        return this;
    }

    public IsolationScope getIsolationScope() {
        return isolationScope;
    }

    /**
     * 设置用于加速远程文件系统读取（ls/glob/exists/grep）的工作区索引。
     * 未设置时，远程文件系统回退为全量存储扫描。
     */
    public RemoteFilesystemSpec workspaceIndex(WorkspaceIndex index) {
        this.workspaceIndex = index;
        return this;
    }

    /**
     * 为 {@code true} 时，默认后端（未路由到共享前缀的所有路径）直接服务工作区根：
     * 不附加按用户划分的命名空间前缀，并采用沙箱式路径解析。
     * 宿主应用通过普通 {@code java.nio} 写到工作区根的文件
     * （例如 {@code uploads/<sessionId>/result.md}）可由 {@code read_file}／{@code list_files}／
     * {@code grep_files} 访问；工作区之外的绝对路径会被拒绝，不能向上遍历到文件系统根。
     * 默认命名空间后端会把此类读取解析到宿主未写入的 {@code {workspace}/{userId}/...}，
     * 而 UNRESTRICTED 解析允许 {@code list_files} 向上访问到驱动器根。
     *
     * <p>共享前缀路由（{@code memory/}、{@code skills/} 等）仍保留按用户划分的存储命名空间；
     * 仅默认后端改变。
     *
     * <p><b>不适合多租户工作区：</b>共享模式关闭工作区根的按用户命名空间隔离；
     * 配置到同一工作区目录的用户／会话会读取并覆盖彼此文件。
     * 此选项用于宿主直接向工作区写入上传内容的单租户集成。
     *
     * <p>默认 {@code false}，保留带命名空间的默认后端（#3245）。
     *
     * @param shared 默认后端是否服务共享工作区根
     * @return 此配置对象
     */
    public RemoteFilesystemSpec sharedLocalWorkspace(boolean shared) {
        this.sharedLocalWorkspace = shared;
        return this;
    }

    /**
     * 构建此配置描述的组合文件系统。
     *
     * <ul>
     *   <li>默认后端：{@link LocalFilesystem}（无 Shell），按用户划分命名空间
     *   <li>共享<b>前缀</b>路由（{@code memory/}、{@code skills/}、{@code subagents/}、
     *       {@code knowledge/}、{@code plans/}、{@code agents/<id>/sessions/}、
     *       {@code agents/<id>/tasks/}，以及额外的 {@code addSharedPrefix}）：
     *       包装为 {@link OverlayFilesystem}，<em>上层</em>是按用户划分并持久化到
     *       {@link BaseStore} 的 {@link RemoteFilesystem}，<em>下层</em>是以
     *       {@code workspace.resolve(<routeDir>)} 为根的只读 {@link LocalFilesystem}。
     *       因此 {@code <workspace>/skills/}、{@code <workspace>/subagents/} 等脚手架模板
     *       内容作为基线可见；按用户进行的编辑通过写时复制进入远程存储，并在后续读取时覆盖模板。
     *   <li>{@code AGENTS.md}、{@code MEMORY.md}、{@code tools.json} 精确文件路由：
     *       包装为 {@link OverlayFilesystem}，<em>上层</em>是 {@code root} 片段的
     *       {@link RemoteFilesystem}，<em>下层</em>是以工作区为根的只读 {@link LocalFilesystem}，
     *       使工作区根的脚手架模板文件作为基线可见。{@link CompositeFilesystem}
     *       列目录或执行 glob 时不递归进入精确文件路由，而是对覆盖层做一次 {@code exists} 检查，
     *       任一层存在即可满足。
     * </ul>
     */
    public AbstractFilesystem toFilesystem(
            Path workspace, String agentId, NamespaceFactory localNamespaceFactory) {
        if (store == null) {
            throw new IllegalStateException(
                    "RemoteFilesystemSpec has no BaseStore. Either pass one via the constructor"
                            + " or configure a DistributedStore on the HarnessAgent builder.");
        }
        String effectiveAgentId = agentId == null || agentId.isBlank() ? "HarnessAgent" : agentId;
        // sharedLocalWorkspace：与下方工作区模板层采用相同构造方式——共享
        // 根目录，不加命名空间前缀，并阻止路径穿越（#3245）。
        AbstractFilesystem local =
                sharedLocalWorkspace
                        ? new LocalFilesystem(workspace, true, 10, null)
                        : new LocalFilesystem(workspace, false, 10, localNamespaceFactory);

        // 下方精确文件覆盖层使用的工作区根只读模板视图。下层在能力上
        // 暴露整个工作区，但 CompositeFilesystem 不递归进入
        // 精确文件路由（只做单键 exists/read），因此此路由访问不到额外暴露的路径。
        LocalFilesystem workspaceTemplate = new LocalFilesystem(workspace, true, 10, null);

        Map<String, AbstractFilesystem> routes = new LinkedHashMap<>();
        routes.put("AGENTS.md", exactFileOverlay("root", effectiveAgentId, workspaceTemplate));
        routes.put("MEMORY.md", exactFileOverlay("root", effectiveAgentId, workspaceTemplate));
        routes.put("tools.json", exactFileOverlay("root", effectiveAgentId, workspaceTemplate));
        routes.put(
                "memory/", overlayRoute(workspace.resolve("memory"), "memory", effectiveAgentId));
        routes.put(
                "skills/", overlayRoute(workspace.resolve("skills"), "skills", effectiveAgentId));
        routes.put(
                "subagents/",
                overlayRoute(workspace.resolve("subagents"), "subagents", effectiveAgentId));
        routes.put(
                "knowledge/",
                overlayRoute(workspace.resolve("knowledge"), "knowledge", effectiveAgentId));
        routes.put("plans/", overlayRoute(workspace.resolve("plans"), "plans", effectiveAgentId));
        routes.put(
                "agents/" + effectiveAgentId + "/sessions/",
                overlayRoute(
                        workspace.resolve("agents").resolve(effectiveAgentId).resolve("sessions"),
                        "sessions",
                        effectiveAgentId));
        routes.put(
                "agents/" + effectiveAgentId + "/tasks/",
                overlayRoute(
                        workspace.resolve("agents").resolve(effectiveAgentId).resolve("tasks"),
                        "tasks",
                        effectiveAgentId));
        for (String extra : extraSharedPrefixes) {
            String segment = routeSegmentFromPrefix(extra);
            routes.put(extra, overlayRoute(workspace.resolve(segment), segment, effectiveAgentId));
        }
        return new CompositeFilesystem(local, routes);
    }

    /**
     * 为工作区前缀路由构建 {@link OverlayFilesystem}。
     * 上层是由 {@link BaseStore} 支持的按用户划分的 {@link RemoteFilesystem}；
     * 下层是以 {@code localTemplateDir} 为根的只读 {@link LocalFilesystem}，
     * 使脚手架模板内容作为基线可见。下层使用 {@code virtualMode=true}，
     * 因此报告的路径锚定于自身根目录，符合 {@link CompositeFilesystem} 路由重映射的预期。
     */
    private OverlayFilesystem overlayRoute(
            Path localTemplateDir, String routeSegment, String agentId) {
        RemoteFilesystem upper = remoteForRoute(routeSegment, agentId);
        LocalFilesystem lower = new LocalFilesystem(localTemplateDir, true, 10, null);
        return new OverlayFilesystem(upper, lower);
    }

    /**
     * 为精确文件路由（例如 {@code AGENTS.md}）构建 {@link OverlayFilesystem}。
     * 上层是 {@code root} 命名空间片段中按用户划分的 {@link RemoteFilesystem}；
     * 下层是共享工作区根的 {@link LocalFilesystem}，使脚手架模板文件
     * （{@code workspace/<filename>}）作为基线可见。
     */
    private OverlayFilesystem exactFileOverlay(
            String routeSegment, String agentId, LocalFilesystem workspaceTemplate) {
        RemoteFilesystem upper = remoteForRoute(routeSegment, agentId);
        return new OverlayFilesystem(upper, workspaceTemplate);
    }

    private RemoteFilesystem remoteForRoute(String routeSegment, String agentId) {
        NamespaceFactory base = storeNamespace(agentId);
        NamespaceFactory extended =
                rc -> {
                    List<String> ns = new ArrayList<>(base.getNamespace(rc));
                    ns.add(routeSegment);
                    return ns;
                };
        return new RemoteFilesystem(store, extended).withIndex(workspaceIndex);
    }

    private static String routeSegmentFromPrefix(String normalizedPrefix) {
        String segment = normalizedPrefix;
        while (segment.endsWith("/")) {
            segment = segment.substring(0, segment.length() - 1);
        }
        return segment.isEmpty() ? "extra" : segment;
    }

    private NamespaceFactory storeNamespace(String agentId) {
        return rc -> {
            String uid = rc != null ? rc.getUserId() : null;
            String sid = rc != null ? rc.getSessionId() : null;

            return switch (isolationScope) {
                case SESSION -> {
                    String effectiveSid = (sid != null && !sid.isBlank()) ? sid : "default";
                    yield List.of("agents", agentId, "sessions", effectiveSid);
                }
                case USER -> {
                    String effectiveUid = (uid != null && !uid.isBlank()) ? uid : anonymousUserId;
                    yield List.of("agents", agentId, "users", effectiveUid);
                }
                case AGENT -> List.of("agents", agentId, "shared");
                case GLOBAL -> List.of("global");
            };
        };
    }

    private static String normalizePrefix(String prefix) {
        String normalized = prefix.replace('\\', '/').strip();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}
