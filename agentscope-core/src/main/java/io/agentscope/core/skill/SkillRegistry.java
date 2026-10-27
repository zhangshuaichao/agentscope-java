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
package io.agentscope.core.skill;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 以技能 ID 为键存储 {@link AgentSkill} 的纯存储层。
 *
 * <p>技能激活状态不在这里保存，而是保存在 {@link io.agentscope.core.state.ToolContextState} 的会话级 {@code activatedGroups} 中。
 *
 * <p>作为纯存储层，此处假定参数非 null，校验由 {@code Toolkit} 层完成。
 */
class SkillRegistry {
    private static final Logger logger = LoggerFactory.getLogger(SkillRegistry.class);

    private final Map<String, AgentSkill> skills = new ConcurrentHashMap<>();

    // ==================== 注册 ====================

    /**
     * 注册技能，替换同 ID 的已有技能。重复注册相同 {@code (name, source)} 对（更新内容）是正常路径。标准名称字符集不含下划线，不同的组合不会共享 ID，因此下方冲突警告对合法技能不可达，仅用于防范未来放宽规则或调用方提供不匹配 ID。
     *
     * @param skillId 唯一技能标识（不能为 null）
     * @param skill 技能实现（不能为 null）
     */
    void registerSkill(String skillId, AgentSkill skill) {
        AgentSkill existing = skills.get(skillId);
        if (isAmbiguousIdCollision(existing, skill)) {
            logger.warn(
                    "Skill id '{}' was registered for name='{}', source='{}' but is being"
                            + " overwritten by a different skill name='{}', source='{}'. With the"
                            + " standard name rule this cannot happen; it indicates a naming-rule"
                            + " regression or a caller-supplied mismatched id.",
                    skillId,
                    existing.getName(),
                    existing.getSource(),
                    skill.getName(),
                    skill.getSource());
        }
        skills.put(skillId, skill);
    }

    /**
     * 判断将 {@code incoming} 注册到 {@code existing} 已占用的 ID 是否构成歧义冲突，即不同的 {@code (name, source)} 对映射到相同拼接 ID。重复注册同一组合（更新内容）不算冲突。
     *
     * @param existing 此 ID 当前已注册的技能（不存在时为 {@code null}）
     * @param incoming 即将注册到同一 ID 的技能
     * @return 覆盖有歧义时为 {@code true}，否则为 {@code false}
     */
    static boolean isAmbiguousIdCollision(AgentSkill existing, AgentSkill incoming) {
        return existing != null
                && (!existing.getName().equals(incoming.getName())
                        || !existing.getSource().equals(incoming.getSource()));
    }

    // ==================== 查询操作 ====================

    /**
     * 按 ID 获取技能。
     *
     * @param skillId 技能 ID（不能为 null）
     * @return 技能实例；未找到时为 null
     */
    AgentSkill getSkill(String skillId) {
        return skills.get(skillId);
    }

    /**
     * 获取全部技能 ID。
     *
     * @return 技能 ID 集合（不会为 null，可以为空）
     */
    Set<String> getSkillIds() {
        return new TreeSet<>(skills.keySet());
    }

    /**
     * 检查技能是否存在。
     *
     * @param skillId 技能 ID（不能为 null）
     * @return 存在时为 true，否则为 false
     */
    boolean exists(String skillId) {
        return skills.containsKey(skillId);
    }

    // ==================== 移除操作 ====================

    /**
     * 完全移除技能。
     *
     * @param skillId 技能 ID（不能为 null）
     */
    void removeSkill(String skillId) {
        skills.remove(skillId);
    }
}
