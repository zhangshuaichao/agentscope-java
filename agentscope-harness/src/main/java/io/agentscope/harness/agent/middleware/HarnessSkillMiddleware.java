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
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.RuntimeContextSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.context.ContextItem;
import io.agentscope.harness.agent.context.WorkspaceContextMaterials;
import io.agentscope.harness.agent.skill.LazyResourceCapable;
import io.agentscope.harness.agent.skill.SkillResources;
import io.agentscope.harness.agent.skill.curator.SkillVisibilityFilter;
import io.agentscope.harness.agent.skill.runtime.HarnessSkillEntry;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.RepoBound;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.StageResult;
import io.agentscope.harness.agent.skill.runtime.ShellPathPolicy;
import io.agentscope.harness.agent.skill.runtime.SkillCatalog;
import io.agentscope.harness.agent.skill.runtime.SkillRuntime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Harness 原生技能中间件，替代旧版 {@code DynamicSkillMiddleware} 子类，
 * 使用自行拥有 {@link SkillRuntime} 的独立实现。
 *
 * <p>每次 {@code onSystemPrompt} 阶段：
 * <ol>
 *   <li>从智能体解析当前 {@link RuntimeContext}。
 *   <li>按低到高优先级遍历仓库，以 {@code AgentSkill.name} 合并，后者优先。
 *   <li>应用可选的 {@link SkillVisibilityFilter}。
 *   <li>将第 1／第 2 层市场技能的资源暂存到
 *       {@code <wsRoot>/.skills-cache/<source-ns>/<skill>/}，通过 {@link MarketplaceStager} 完成。
 *   <li>构建 {@link SkillCatalog}，由 {@link HarnessSkillEntry} 组成，
 *       保留延迟资源和解析后的 {@code filesRoot}。
 *   <li>将目录绑定到当前 {@link RuntimeContext}，通过 {@link SkillRuntime} 完成，
 *       同时幂等地向运行时工具集注册 {@code load_skill_through_path}。
 *   <li>渲染 {@code <available_skills>} 提示块，将其注册为尾部运行时材料
 *       （{@code WorkspaceContextMaterials}），而不是追加到系统提示，
 *       使不同步骤之间的系统头部保持字节稳定。
 * </ol>
 *
 * <p><b>工具集说明：</b>构造时会在 {@link
 * io.agentscope.core.ReActAgent ReActAgent} 复制工具集之前，将加载工具注册为不分组工具，
 * 因此已持久化但活动组列表为空的会话仍能看到它。
 * 每次系统提示阶段也会检查活动工具集，继续支持直接使用中间件。
 *
 * <p>【学习提示】技能目录、资源暂存、实际加载工具是不同层次。
 * 目录用于发现；延迟访问减少不必要的资源加载；每次调用的隔离范围则决定缓存清理能触及哪些数据。
 */
@SuppressWarnings("deprecation")
public class HarnessSkillMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(HarnessSkillMiddleware.class);

    private final List<AgentSkillRepository> repositories;
    private final SkillFilter builderFilter;
    private final SkillVisibilityFilter visibilityFilter;
    private final MarketplaceStager stager;
    private final ShellPathPolicy shellPathPolicy;
    private final SkillRuntime runtime;
    private final Map<AgentSkillRepository, String> sourceNamespaces;
    private final Map<String, RepoBound> frozenSkills;
    private IsolationScope isolationScope;

    /**
     * 每次调用的缓存作用域，与 {@link IsolationScope} 已用于运行时数据的身份维度一致。
     * 共享作用域的调用共享 {@code .skills-cache} 子树，且只有这些调用能清理它；
     * 因此，一次调用的可见技能允许列表才能对清理所能触及的全部内容具有权威性。
     */
    private String scopeKeyFor(RuntimeContext ctx) {
        IsolationScope scope = isolationScope != null ? isolationScope : IsolationScope.USER;
        return switch (scope) {
            case USER -> {
                String uid = ctx != null ? ctx.getUserId() : null;
                if (uid != null && !uid.isBlank()) {
                    yield uid;
                }
                // 保持 IsolationScope.USER 文档所述的回退到会话身份的逻辑。
                // null 表示“没有可作为键的身份”，不同于恰好拼写为
                // 暂存器共享分区名称的真实身份。
                String sid = ctx != null ? ctx.getSessionId() : null;
                yield sid != null && !sid.isBlank() ? sid : null;
            }
            case SESSION -> {
                String sid = ctx != null ? ctx.getSessionId() : null;
                yield sid != null && !sid.isBlank() ? sid : null;
            }
            // 工作区已按智能体隔离，因此无需进一步分隔。
            case AGENT, GLOBAL -> null;
        };
    }

    public HarnessSkillMiddleware(List<AgentSkillRepository> repositories, Toolkit toolkit) {
        this(repositories, toolkit, null, null, null, ShellPathPolicy.noShell());
    }

    public HarnessSkillMiddleware(
            List<AgentSkillRepository> repositories, Toolkit toolkit, SkillFilter builderFilter) {
        this(repositories, toolkit, builderFilter, null, null, ShellPathPolicy.noShell());
    }

    public HarnessSkillMiddleware(
            List<AgentSkillRepository> repositories,
            Toolkit toolkit,
            SkillFilter builderFilter,
            SkillVisibilityFilter visibilityFilter) {
        this(
                repositories,
                toolkit,
                builderFilter,
                visibilityFilter,
                null,
                ShellPathPolicy.noShell());
    }

    /**
     * 完整构造方法。
     *
     * @param repositories 按组合顺序排列的仓库列表（低到高优先级）
     * @param toolkit 正在为智能体组装的工具集
     * @param builderFilter 构建智能体时传入的技能过滤器（可为 {@code null}）
     * @param visibilityFilter 可选的逐请求过滤器（金丝雀／允许列表）
     * @param stager 市场资源暂存器；{@code null} 完全禁用暂存
     * @param shellPathPolicy 按 shell 模式解析 {@code <files-root>} 的策略；不可为
     *     {@code null}，未注册 shell 工具时传入 {@link ShellPathPolicy#noShell()}
     */
    public HarnessSkillMiddleware(
            List<AgentSkillRepository> repositories,
            Toolkit toolkit,
            SkillFilter builderFilter,
            SkillVisibilityFilter visibilityFilter,
            MarketplaceStager stager,
            ShellPathPolicy shellPathPolicy) {
        this(
                repositories,
                toolkit,
                builderFilter,
                visibilityFilter,
                stager,
                shellPathPolicy,
                false);
    }

    /**
     * 创建在构造时仅捕获一次合并仓库视图的中间件。
     * 过滤仍按调用执行，延迟资源访问仍绑定到当前上下文。
     */
    public static HarnessSkillMiddleware frozen(
            List<AgentSkillRepository> repositories,
            Toolkit toolkit,
            SkillFilter builderFilter,
            SkillVisibilityFilter visibilityFilter,
            MarketplaceStager stager,
            ShellPathPolicy shellPathPolicy) {
        return new HarnessSkillMiddleware(
                repositories,
                toolkit,
                builderFilter,
                visibilityFilter,
                stager,
                shellPathPolicy,
                true);
    }

    private HarnessSkillMiddleware(
            List<AgentSkillRepository> repositories,
            Toolkit toolkit,
            SkillFilter builderFilter,
            SkillVisibilityFilter visibilityFilter,
            MarketplaceStager stager,
            ShellPathPolicy shellPathPolicy,
            boolean freezeRepositories) {
        this.repositories = repositories != null ? List.copyOf(repositories) : List.of();
        this.builderFilter = builderFilter != null ? builderFilter : SkillFilter.all();
        this.visibilityFilter = visibilityFilter;
        this.stager = stager;
        this.shellPathPolicy =
                shellPathPolicy != null ? shellPathPolicy : ShellPathPolicy.noShell();
        this.isolationScope = IsolationScope.USER;
        this.runtime = new SkillRuntime();
        // 在构建时一次性解析来源命名空间。中间件整个生命周期中的
        // 组合顺序固定，因此这种做法安全且避免每次调用重复工作。
        this.sourceNamespaces = MarketplaceStager.resolveSourceNamespaces(this.repositories);
        this.frozenSkills =
                freezeRepositories
                        ? Collections.unmodifiableMap(
                                new LinkedHashMap<>(mergeRepositories(RuntimeContext.empty())))
                        : null;
        this.runtime.prepareToolkit(toolkit);
    }

    /** 仅声明所需扩展点；子类若重写更多钩子，必须扩展此集合。 */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_SYSTEM_PROMPT);
    }

    /** 供测试／自省访问。 */
    public SkillRuntime runtime() {
        return runtime;
    }

    /**
     * 覆盖用于隔离 {@code .skills-cache} 子树的维度。
     * 默认为 {@link IsolationScope#USER}，与运行时数据的默认值一致。
     */
    public HarnessSkillMiddleware isolationScope(IsolationScope scope) {
        this.isolationScope = scope;
        return this;
    }

    /** 是否将仓库枚举固定为构造时的快照。 */
    public boolean isFrozen() {
        return frozenSkills != null;
    }

    /**
     * 预先将市场技能资源暂存到宿主机工作区的 {@code .skills-cache/}。
     * 应在沙箱启动<em>之前</em>调用，让本次工作区投影包含这些内容。
     * 可安全重复调用，因为暂存通过内容哈希保护而具备幂等性。
     *
     * @param ctx 每次调用的运行上下文
     */
    public void prestageMarketplaceSkills(RuntimeContext ctx) {
        if (stager == null) {
            return;
        }
        if (ctx == null) {
            ctx = RuntimeContext.empty();
        }
        Map<String, RepoBound> merged = skillsForCall(ctx);
        if (merged.isEmpty()) {
            return;
        }
        List<RepoBound> visible = applyVisibility(merged.values(), ctx);
        List<RepoBound> enabled = applySkillFilter(visible, effectiveFilter(ctx));
        if (!enabled.isEmpty()) {
            stager.stage(enabled, sourceNamespaces, scopeKeyFor(ctx));
        }
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        if (ctx == null) {
            ctx = RuntimeContext.empty();
        }

        Toolkit agentToolkit = agent != null ? agent.getToolkit() : null;

        Map<String, RepoBound> merged = skillsForCall(ctx);
        if (merged.isEmpty()) {
            runtime.install(SkillCatalog.empty(), ctx, agentToolkit);
            registerSkillsMaterial(ctx, "");
            return Mono.just(currentPrompt);
        }

        List<RepoBound> visible = applyVisibility(merged.values(), ctx);
        List<RepoBound> enabled = applySkillFilter(visible, effectiveFilter(ctx));
        if (enabled.isEmpty()) {
            runtime.install(SkillCatalog.empty(), ctx, agentToolkit);
            registerSkillsMaterial(ctx, "");
            return Mono.just(currentPrompt);
        }

        Map<String, StageResult> staged =
                stager != null
                        ? stager.stage(enabled, sourceNamespaces, scopeKeyFor(ctx))
                        : Map.of();

        List<HarnessSkillEntry> entries = new ArrayList<>(enabled.size());
        for (RepoBound bound : enabled) {
            SkillResources lazy = null;
            if (bound.repo() instanceof LazyResourceCapable lrc) {
                try {
                    lazy = lrc.resourcesFor(bound.skill().getName(), ctx);
                } catch (Exception e) {
                    log.debug(
                            "resourcesFor({}) failed; continuing without lazy: {}",
                            bound.skill().getName(),
                            e.getMessage());
                }
            }
            StageResult stage = staged.getOrDefault(bound.skill().getName(), StageResult.NONE);
            String filesRoot = shellPathPolicy.resolve(bound.skill().getName(), stage);
            entries.add(new HarnessSkillEntry(bound.skill(), lazy, filesRoot));
        }

        SkillCatalog catalog = SkillCatalog.of(entries);
        runtime.install(catalog, ctx, agentToolkit);

        registerSkillsMaterial(ctx, runtime.renderPrompt(catalog, SkillFilter.all()));
        return Mono.just(currentPrompt);
    }

    /**
     * 将渲染后的 {@code <available_skills>} 块作为尾部运行时材料提供，而非追加到系统提示。
     * 会话运行期间目录可能因技能安装或移除而变化；系统头部字节需稳定以支持前缀缓存。
     * 没有可见技能时仍注册空白项，保持 sourceId 确定。
     * 对话历史压缩后，仍需该目录维持技能发现能力。
     */
    private static void registerSkillsMaterial(RuntimeContext ctx, String block) {
        WorkspaceContextMaterials.register(
                ctx, List.of(ContextItem.runtimeState("skills", "harness:skills", true, block)));
    }

    // ---------------------------------------------------------------------
    //  内部实现
    // ---------------------------------------------------------------------

    private Map<String, RepoBound> skillsForCall(RuntimeContext ctx) {
        return frozenSkills != null ? frozenSkills : mergeRepositories(ctx);
    }

    private SkillFilter effectiveFilter(RuntimeContext ctx) {
        return builderFilter.overlay(ctx != null ? ctx.get(SkillFilter.class) : null);
    }

    private List<RepoBound> applySkillFilter(
            java.util.Collection<RepoBound> input, SkillFilter filter) {
        if (input.isEmpty()) {
            return List.of();
        }
        SkillFilter effective = filter != null ? filter : SkillFilter.all();
        List<RepoBound> out = new ArrayList<>(input.size());
        for (RepoBound bound : input) {
            if (effective.isAllowed(bound.skill().getName())) {
                out.add(bound);
            }
        }
        return out;
    }

    /**
     * 按组合顺序合并各仓库的技能。相同 {@code AgentSkill.name} 的后续条目优先。
     * 同时记录胜出技能的来源仓库，供后续延迟资源访问和市场暂存使用。
     */
    private LinkedHashMap<String, RepoBound> mergeRepositories(RuntimeContext ctx) {
        LinkedHashMap<String, RepoBound> merged = new LinkedHashMap<>();
        for (AgentSkillRepository repo : repositories) {
            List<AgentSkill> skills;
            try {
                skills =
                        repo instanceof RuntimeContextSkillRepository contextRepository
                                ? contextRepository.getAllSkills(ctx)
                                : repo.getAllSkills();
            } catch (Exception e) {
                log.warn(
                        "Skill repository {} failed to load: {}",
                        repo.getClass().getSimpleName(),
                        e.getMessage());
                continue;
            }
            if (skills == null) {
                continue;
            }
            for (AgentSkill skill : skills) {
                if (skill == null || skill.getName() == null) {
                    continue;
                }
                merged.put(skill.getName(), new RepoBound(skill, repo));
            }
        }
        return merged;
    }

    private List<RepoBound> applyVisibility(
            java.util.Collection<RepoBound> input, RuntimeContext ctx) {
        if (visibilityFilter == null || input.isEmpty()) {
            return new ArrayList<>(input);
        }
        Map<AgentSkill, RepoBound> bySkill = new IdentityHashMap<>();
        List<AgentSkill> raw = new ArrayList<>(input.size());
        for (RepoBound rb : input) {
            bySkill.put(rb.skill(), rb);
            raw.add(rb.skill());
        }
        List<AgentSkill> filtered;
        try {
            filtered = visibilityFilter.filter(raw, ctx);
        } catch (Exception e) {
            log.warn(
                    "SkillVisibilityFilter {} failed; treating as pass-through: {}",
                    visibilityFilter.getClass().getSimpleName(),
                    e.getMessage());
            return new ArrayList<>(input);
        }
        if (filtered == null) {
            return new ArrayList<>(input);
        }
        List<RepoBound> out = new ArrayList<>(filtered.size());
        for (AgentSkill s : filtered) {
            RepoBound rb = bySkill.get(s);
            if (rb != null) {
                out.add(rb);
            }
        }
        return out;
    }
}
