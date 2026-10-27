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
package io.agentscope.harness.agent.skill.curator;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import java.util.List;

/**
 * 按调用决定向模型暴露哪些技能的运行时门控。接在 {@code DynamicSkillMiddleware} 组合仓库视图之后，因此能在候选技能进入系统提示前看到最终列表。
 *
 * <p>本包的标准实现：
 * <ul>
 * <li>{@link EnvironmentFilter}：环境匹配（作用于全部技能）。</li>
 * <li>{@link CanaryFilter}：以 userId 为键的百分比灰度发布（仅智能体创建的技能）。</li>
 * <li>{@link AllowListFilter}：显式名称允许名单（仅智能体创建的技能）。</li>
 * <li>{@link CompositeFilter}：以 AND 语义串联其他过滤器。</li>
 * </ul>
 */
@SuppressWarnings("deprecation")
public interface SkillVisibilityFilter {

    /**
     * 将技能列表过滤为当前运行时上下文可见的子集。
     */
    List<AgentSkill> filter(List<AgentSkill> all, RuntimeContext ctx);
}
