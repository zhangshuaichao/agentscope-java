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
import java.util.ArrayList;
import java.util.List;

/**
 * 仅限制智能体编写技能的可见性过滤器基类。用户编写、技能中心安装或已有技能（旁挂记录中 {@code createdBy == null}）原样通过，避免 {@link CanaryFilter} 意外隐藏智能体拥有者发布的手工技能。
 *
 * <p>子类通过 {@link #shouldPassForAgentCreated} 处理智能体跟踪的技能。
 */
@SuppressWarnings("deprecation")
public abstract class AbstractAgentCreatedFilter implements SkillVisibilityFilter {

    protected final SkillUsageStore usageStore;

    protected AbstractAgentCreatedFilter(SkillUsageStore usageStore) {
        this.usageStore = usageStore;
    }

    @Override
    public final List<AgentSkill> filter(List<AgentSkill> all, RuntimeContext ctx) {
        if (all == null || all.isEmpty()) {
            return all == null ? List.of() : all;
        }
        List<AgentSkill> out = new ArrayList<>(all.size());
        for (AgentSkill skill : all) {
            if (skill == null || skill.getName() == null) {
                continue;
            }
            SkillUsageRecord rec =
                    usageStore != null ? usageStore.get(skill.getName()).orElse(null) : null;
            if (rec == null || !"agent".equals(rec.createdBy())) {
                // 直接放行外部或已有技能，不做门控。
                out.add(skill);
                continue;
            }
            if (shouldPassForAgentCreated(skill, rec, ctx)) {
                out.add(skill);
            }
        }
        return out;
    }

    /**
     * 返回 {@code true} 使智能体编写的技能在当前上下文可见，返回 {@code false} 将其过滤。保证 {@code rec} 非 null，且 {@code createdBy == "agent"}。
     */
    protected abstract boolean shouldPassForAgentCreated(
            AgentSkill skill, SkillUsageRecord rec, RuntimeContext ctx);
}
