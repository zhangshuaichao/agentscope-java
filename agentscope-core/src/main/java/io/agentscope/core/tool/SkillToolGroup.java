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
package io.agentscope.core.tool;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 绑定特定技能的 {@link ToolGroup}。使用关联技能时，应同时激活此工具组。
 *
 * <p>{@link #getDescription()} 在基础描述后添加提醒，告知模型绑定技能激活时必须启用此工具组。
 *
 * <p>作用域默认为 {@link ToolGroupScope#META}，使智能体能通过 {@code reset_equipped_tools} 管理它。
 *
 * <p><b>使用示例：</b>
 *
 * <pre>{@code
 * SkillToolGroup group = SkillToolGroup.skillBuilder()
 *     .name("data_analysis_tools")
 *     .description("Tools for data analysis")
 *     .activateOnSkill("data_analysis")
 *     .active(false)
 *     .build();
 * toolkit.registerToolGroup(group);
 * }</pre>
 */
public class SkillToolGroup extends ToolGroup {

    private final String activateOnSkill;
    private final String rawDescription;

    private SkillToolGroup(Builder builder) {
        super(
                builder.name,
                builder.description,
                builder.active,
                ToolGroupScope.META,
                builder.tools);
        this.activateOnSkill =
                Objects.requireNonNull(builder.activateOnSkill, "activateOnSkill cannot be null");
        this.rawDescription = builder.description != null ? builder.description : "";
    }

    /**
     * 返回工具组绑定的技能名称。
     *
     * @return 技能名称（永不为 null）
     */
    public String getActivateOnSkill() {
        return activateOnSkill;
    }

    /**
     * 返回包含技能绑定提醒的增强描述。
     *
     * @return 基础描述与技能激活提醒
     */
    @Override
    public String getDescription() {
        return rawDescription
                + "\n\n**IMPORTANT: This tool group MUST be activated whenever the skill '"
                + activateOnSkill
                + "' is in use.**";
    }

    @Override
    public ToolGroup copy() {
        SkillToolGroup copy =
                SkillToolGroup.skillBuilder()
                        .name(getName())
                        .description(rawDescription)
                        .active(isActive())
                        .activateOnSkill(activateOnSkill)
                        .tools(getTools())
                        .build();
        return copy;
    }

    /**
     * 创建用于构造 SkillToolGroup 实例的构建器。
     *
     * @return 新的 SkillToolGroup 构建器实例
     */
    public static Builder skillBuilder() {
        return new Builder();
    }

    /**
     * 用于构造 SkillToolGroup 实例的构建器。
     */
    public static class Builder {

        private String name;
        private String description = "";
        private boolean active = false;
        private String activateOnSkill;
        private Set<String> tools = new HashSet<>();

        /**
         * 设置工具组名称。
         *
         * @param name 组名（必填）
         * @return 当前构建器，支持链式调用
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 设置工具组基础描述。
         *
         * @param description 组描述
         * @return 当前构建器，支持链式调用
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * 设置初始激活状态。
         *
         * @param active true 表示激活，false 表示未激活（默认）
         * @return 当前构建器，支持链式调用
         */
        public Builder active(boolean active) {
            this.active = active;
            return this;
        }

        /**
         * 设置工具组绑定的技能。
         *
         * @param skillName 技能名称（必填）
         * @return 当前构建器，支持链式调用
         */
        public Builder activateOnSkill(String skillName) {
            this.activateOnSkill = skillName;
            return this;
        }

        /**
         * 设置组内初始工具集合。
         *
         * @param tools 工具名称（进行防御性复制）
         * @return 当前构建器，支持链式调用
         */
        public Builder tools(Set<String> tools) {
            this.tools = new HashSet<>(tools);
            return this;
        }

        /**
         * 使用配置构建 SkillToolGroup。
         *
         * @return 新的 SkillToolGroup 实例
         * @throws NullPointerException 当 name 或 activateOnSkill 为 null 时
         */
        public SkillToolGroup build() {
            return new SkillToolGroup(this);
        }
    }
}
