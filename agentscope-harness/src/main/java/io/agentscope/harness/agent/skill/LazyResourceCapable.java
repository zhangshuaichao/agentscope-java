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
package io.agentscope.harness.agent.skill;

import io.agentscope.core.agent.RuntimeContext;

/**
 * 可选标记接口，供 {@link io.agentscope.core.skill.repository.AgentSkillRepository} 实现在内存 {@code AgentSkill.resources} 映射之外提供 {@link SkillResources} 访问器。
 *
 * <p>harness 技能运行时通过 {@code instanceof} 检测此接口；若实现了它，则为每个技能请求一个 {@link SkillResources}，在 {@code load_skill_through_path} 未命中内存映射时回退使用。因此 {@link WorkspaceSkillRepository} 可以将技能保留在磁盘或沙箱中按需读取，而无需注册时预加载全部内容。
 *
 * <p>已将全部资源预加载到 {@code AgentSkill.resources} 的仓库（例如采用预加载模式的 core {@code FileSystemSkillRepository}、{@code ClasspathSkillRepository} 及多数第三方市场扩展）不需要此接口，因为内存映射已覆盖全部路径。
 */
public interface LazyResourceCapable {

    /**
     * 返回指定技能的懒加载资源访问器。
     *
     * <p>返回的访问器必须捕获或遵循给定 {@code ctx}，以保证跨调用的用户命名空间正确。
     *
     * @param skillName 技能的 {@code name}（不是 {@code skillId}）
     * @param ctx 当前运行时上下文，不会为 {@code null}（无上下文时调用方传入 {@link RuntimeContext#empty()}）
     * @return 此技能资源树的访问器，不会为 {@code null}；技能不属于此仓库时返回 {@link SkillResources#empty()}
     */
    SkillResources resourcesFor(String skillName, RuntimeContext ctx);
}
