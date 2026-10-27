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
package io.agentscope.harness.agent.skill.runtime;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>【学习提示】共享的是无状态加载工具，不是技能可见性。目录绑定在请求 RuntimeContext 上；同一个工具对象可服务多个用户，但查询时必须使用当前调用目录。提示中隐藏技能与加载器拒绝访问应保持一致。
 *
 * 聚合单例 {@link SkillLoadTool} 和 {@link SkillPromptBuilder}。{@link io.agentscope.harness.agent.middleware.HarnessSkillMiddleware} 持有一个实例，并将每个过滤后的 {@link SkillCatalog} 安装到对应调用的 {@link RuntimeContext} 中。
 *
 * <p>{@code load_skill_through_path} 工具在构建智能体时准备好，后续安装时幂等检查。普通工具调用从各自的 {@code RuntimeContext} 解析目录，因此并发会话无法覆盖或观察彼此经过过滤的技能视图。独立的兼容目录仅保留给已弃用的无上下文调用方。
 */
public final class SkillRuntime {

    private static final Logger log = LoggerFactory.getLogger(SkillRuntime.class);

    private final RuntimeContext legacyContext = RuntimeContext.empty();
    private final AtomicReference<SkillCatalog> legacyCatalogRef =
            new AtomicReference<>(SkillCatalog.empty());
    private final SkillLoadTool loadTool;
    private final SkillPromptBuilder promptBuilder;

    public SkillRuntime() {
        this(new SkillPromptBuilder());
    }

    public SkillRuntime(SkillPromptBuilder promptBuilder) {
        this.promptBuilder = promptBuilder != null ? promptBuilder : new SkillPromptBuilder();
        this.loadTool = new SkillLoadTool(legacyCatalogRef, false);
    }

    /**
     * 返回通过已弃用的无上下文 API 安装的目录。
     *
     * @deprecated 请使用 {@link #currentCatalog(RuntimeContext)}，让目录查找限定在请求范围内。
     */
    @Deprecated(since = "2.2.0")
    public SkillCatalog currentCatalog() {
        return currentCatalog(legacyContext);
    }

    /**
     * 返回绑定到 {@code context} 的目录；未绑定时返回空目录。
     */
    public SkillCatalog currentCatalog(RuntimeContext context) {
        return catalogFor(context);
    }

    static SkillCatalog catalogFor(RuntimeContext context) {
        if (context == null) {
            return SkillCatalog.empty();
        }
        SkillCatalog catalog = context.get(SkillCatalog.class);
        return catalog != null ? catalog : SkillCatalog.empty();
    }

    /**
     * 底层无状态工具实例。常规流程请使用 {@link #install(SkillCatalog, RuntimeContext, Toolkit)}。
     */
    public AgentTool loadTool() {
        return loadTool;
    }

    /**
     * 确保技能加载工具以无分组工具形式存在。
     *
     * <p>Harness 在智能体工具包仍处于组装阶段时调用此方法，因此 {@code ReActAgent.Builder} 创建的工具包副本在首次模型调用前就已包含该工具。加载器有意不分组：无论会话持久化的激活分组列表如何，无分组工具始终可见，包括早于加载器创建的会话。
     */
    public void prepareToolkit(Toolkit toolkit) {
        if (toolkit == null) {
            return;
        }
        try {
            AgentTool existing = toolkit.getTool(SkillLoadTool.TOOL_NAME);
            if (existing == loadTool) {
                return;
            }
            if (existing != null) {
                toolkit.removeTool(SkillLoadTool.TOOL_NAME);
            }
            toolkit.registration().agentTool(loadTool).apply();
        } catch (Exception e) {
            log.warn("Failed to register {}: {}", SkillLoadTool.TOOL_NAME, e.getMessage());
        }
    }

    /**
     * 将目录绑定到当前调用，并确保工具包中已注册加载工具（幂等）。
     *
     * <p>目录有意存放在 {@code context} 中，而不是共享运行时中。这是 {@link SkillLoadTool} 使用的授权边界：被当前调用过滤目录排除的技能无法加载，即使另一会话暴露了它。
     *
     * @param catalog 本次调用快照；传入 {@link SkillCatalog#empty()} 可清空可见性
     * @param context 当前调用上下文，可以为 {@code null}，此时不暴露目录
     * @param toolkit 要安装到的工具包，可以为 {@code null}（此时只更新目录）
     */
    public void install(SkillCatalog catalog, RuntimeContext context, Toolkit toolkit) {
        if (context != null) {
            context.put(SkillCatalog.class, catalog != null ? catalog : SkillCatalog.empty());
        }
        prepareToolkit(toolkit);
    }

    /**
     * 为旧的无上下文调用方安装目录。
     *
     * <p>保留此重载以兼容源码和二进制。只有调用 {@link SkillLoadTool} 时未提供 {@link RuntimeContext}，才使用此目录；带上下文的调用绝不回退到它，从而保持隔离。
     *
     * @deprecated 请使用 {@link #install(SkillCatalog, RuntimeContext, Toolkit)}。
     */
    @Deprecated(since = "2.2.0")
    public void install(SkillCatalog catalog, Toolkit toolkit) {
        SkillCatalog normalized = catalog != null ? catalog : SkillCatalog.empty();
        legacyCatalogRef.set(normalized);
        install(normalized, legacyContext, toolkit);
    }

    /**
     * 渲染 {@code <available_skills>} 块，并在适用时追加代码执行提示。
     */
    public String renderPrompt(SkillCatalog catalog, SkillFilter filter) {
        return promptBuilder.render(
                catalog != null ? catalog : SkillCatalog.empty(),
                filter != null ? filter : SkillFilter.all());
    }
}
