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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 决定提示中允许包含哪些技能的不可变过滤器。
 *
 * <p>两种使用模式：
 * <ul>
 * <li><b>独立模式</b>（构建器层）：{@link #all()}、{@link #none()}、{@link #only}、{@link #except} 表达完整策略。</li>
 * <li><b>叠加模式</b>（RuntimeContext 层）：{@link #enable}、{@link #disable} 表达局部覆盖，通过 {@link #overlay} 与基础过滤器合并。</li>
 * </ul>
 *
 * <p>未配置 {@code SkillFilter} 时，默认行为为 {@link #all()}，即启用全部技能。
 *
 * <p><b>用法示例：</b>
 * <pre>{@code
 * // Builder level: only allow two skills
 * SkillFilter base = SkillFilter.only("skill_a", "skill_b");
 *
 * // Runtime: disable one of them for this call
 * SkillFilter runtime = SkillFilter.disable("skill_b");
 *
 * // Merge: skill_a enabled, skill_b disabled
 * SkillFilter effective = base.overlay(runtime);
 * }</pre>
 */
public class SkillFilter {

    private enum Mode {
        ALL,
        NONE,
        WHITELIST,
        BLACKLIST,
        OVERLAY_ENABLE,
        OVERLAY_DISABLE
    }

    private final Mode mode;
    private final Set<String> skillNames;

    private SkillFilter(Mode mode, Set<String> skillNames) {
        this.mode = mode;
        this.skillNames =
                skillNames != null
                        ? Collections.unmodifiableSet(new HashSet<>(skillNames))
                        : Set.of();
    }

    // ==================== 独立模式工厂方法 ====================

    /**
     * 启用全部技能（未配置过滤器时的默认行为）。
     */
    public static SkillFilter all() {
        return new SkillFilter(Mode.ALL, null);
    }

    /**
     * 禁用全部技能。
     */
    public static SkillFilter none() {
        return new SkillFilter(Mode.NONE, null);
    }

    /**
     * 允许名单：仅允许指定名称的技能。
     */
    public static SkillFilter only(String... skillNames) {
        return new SkillFilter(Mode.WHITELIST, Set.of(skillNames));
    }

    /**
     * 拒绝名单：允许指定名称之外的所有技能。
     */
    public static SkillFilter except(String... skillNames) {
        return new SkillFilter(Mode.BLACKLIST, Set.of(skillNames));
    }

    // ==================== 叠加模式工厂方法 ====================

    /**
     * 叠加规则：显式启用这些技能（其他技能不变）。
     */
    public static SkillFilter enable(String... skillNames) {
        return new SkillFilter(Mode.OVERLAY_ENABLE, Set.of(skillNames));
    }

    /**
     * 叠加规则：显式禁用这些技能（其他技能不变）。
     */
    public static SkillFilter disable(String... skillNames) {
        return new SkillFilter(Mode.OVERLAY_DISABLE, Set.of(skillNames));
    }

    // ==================== 查询 ====================

    /**
     * 判断此过滤器是否允许给定技能。
     *
     * @param skillName 要检查的技能名称
     * @return 允许时为 true
     */
    public boolean isAllowed(String skillName) {
        return switch (mode) {
            case ALL -> true;
            case NONE -> false;
            case WHITELIST -> skillNames.contains(skillName);
            case BLACKLIST -> !skillNames.contains(skillName);
            case OVERLAY_ENABLE -> skillNames.contains(skillName);
            case OVERLAY_DISABLE -> !skillNames.contains(skillName);
        };
    }

    /**
     * 判断此过滤器是否为叠加规则（局部覆盖），而不是独立过滤器。
     *
     * @return 是叠加过滤器时为 true
     */
    public boolean isOverlay() {
        return mode == Mode.OVERLAY_ENABLE || mode == Mode.OVERLAY_DISABLE;
    }

    // ==================== 合并 ====================

    /**
     * 将当前（基础）过滤器与运行时叠加规则合并。
     *
     * <p>叠加规则明确提及的技能采用叠加规则的决策，未提及的技能回退到当前基础过滤器。
     *
     * <p>若 {@code runtimeOverlay} 为 null，则保留当前过滤器；若它不是叠加过滤器，则完全替换当前过滤器。
     *
     * @param runtimeOverlay 单次调用的覆盖规则（可为 null）
     * @return 生效的过滤器
     */
    public SkillFilter overlay(SkillFilter runtimeOverlay) {
        if (runtimeOverlay == null) {
            return this;
        }
        if (!runtimeOverlay.isOverlay()) {
            return runtimeOverlay;
        }
        return merged(this, runtimeOverlay);
    }

    private static SkillFilter merged(SkillFilter base, SkillFilter overlay) {
        return new SkillFilter(Mode.ALL, null) {
            @Override
            public boolean isAllowed(String skillName) {
                if (overlay.skillNames.contains(skillName)) {
                    return overlay.isAllowed(skillName);
                }
                return base.isAllowed(skillName);
            }

            @Override
            public boolean isOverlay() {
                return false;
            }
        };
    }
}
