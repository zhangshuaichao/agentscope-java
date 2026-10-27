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
 * 仅在技能旁挂记录的 {@code environments} 列表包含已配置环境（{@code prod}、{@code staging} 等）时允许该技能。不同于灰度/允许名单过滤器，此过滤器作用于<em>全部</em>技能，包括已有技能，以统一智能体生成与手工技能的部署环境行为。
 *
 * <p>没有旁挂记录的手工技能无条件通过。预期由有权限的运维人员在审核后手工为旁挂记录设置 {@code environments: [prod]}。
 */
@SuppressWarnings("deprecation")
public class EnvironmentFilter implements SkillVisibilityFilter {

    private final String env;
    private final SkillUsageStore usageStore;

    public EnvironmentFilter(String env, SkillUsageStore usageStore) {
        this.env = env != null ? env : "prod";
        this.usageStore = usageStore;
    }

    @Override
    public List<AgentSkill> filter(List<AgentSkill> all, RuntimeContext ctx) {
        if (all == null || all.isEmpty() || usageStore == null) {
            return all == null ? List.of() : all;
        }
        List<AgentSkill> out = new ArrayList<>(all.size());
        for (AgentSkill skill : all) {
            if (skill == null || skill.getName() == null) {
                continue;
            }
            SkillUsageRecord rec = usageStore.get(skill.getName()).orElse(null);
            if (rec == null) {
                // 无旁挂记录：外部或已有技能，直接放行。
                out.add(skill);
                continue;
            }
            List<String> envs = rec.environments();
            if (envs == null || envs.isEmpty() || envs.contains(env)) {
                out.add(skill);
            }
            // 否则过滤（例如 environments=[draft] 永远不匹配 env=prod）。
        }
        return out;
    }
}
