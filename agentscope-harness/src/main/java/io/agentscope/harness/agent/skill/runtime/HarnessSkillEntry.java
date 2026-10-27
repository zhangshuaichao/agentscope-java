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

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.harness.agent.skill.SkillResources;

/**
 * 针对单次调用包装 {@link AgentSkill}，携带不应放入 core 数据类型的 harness 专属元数据。
 *
 * @param skill 底层技能（非 null）
 * @param lazyResources 来源仓库实现 {@link io.agentscope.harness.agent.skill.LazyResourceCapable} 时使用的资源访问器；仅有内存 {@code skill.resources} 映射时为 {@code null}
 * @param filesRoot 通过 Shell 执行技能脚本的绝对根路径；无 Shell 工具（提示于是省略 {@code <files-root>}），或仓库内容不在 Shell 可访问的文件系统上时，可以为 {@code null}
 */
@SuppressWarnings("deprecation")
public record HarnessSkillEntry(AgentSkill skill, SkillResources lazyResources, String filesRoot) {

    public HarnessSkillEntry {
        if (skill == null) {
            throw new IllegalArgumentException("skill must not be null");
        }
    }

    /**
     * 供尚未接入 Shell 模式的运行时层使用的便捷方法。
     */
    public static HarnessSkillEntry of(AgentSkill skill, SkillResources lazyResources) {
        return new HarnessSkillEntry(skill, lazyResources, null);
    }
}
