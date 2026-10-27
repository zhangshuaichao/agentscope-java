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
 * 表示带激活状态的命名工具组。
 *
 * <p>工具组将工具按逻辑类别组织，并动态控制可用性。只有激活组中的工具对智能体可用。
 *
 * <p><b>使用示例：</b>
 *
 * <pre>{@code
 * ToolGroup adminGroup = ToolGroup.builder()
 *     .name("admin")
 *     .description("Administrative tools")
 *     .active(false) // Start inactive
 *     .build();
 *
 * adminGroup.addTool("delete_user");
 * adminGroup.addTool("modify_permissions");
 * adminGroup.setActive(true); // Activate when needed
 * }</pre>
 */
public class ToolGroup {

    private final String name;
    private final String description;
    private boolean active;
    private final ToolGroupScope scope;
    private final Set<String> tools; // 此组内的工具名称

    /**
     * 供子类使用的受保护构造器。
     *
     * @param name 组名（必填）
     * @param description 组描述
     * @param active 初始激活状态
     * @param scope 组作用域
     * @param tools 初始工具名称集合
     */
    protected ToolGroup(
            String name,
            String description,
            boolean active,
            ToolGroupScope scope,
            Set<String> tools) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.description = description != null ? description : "";
        this.active = active;
        this.scope = scope;
        this.tools = new HashSet<>(tools);
    }

    private ToolGroup(Builder builder) {
        this(builder.name, builder.description, builder.active, builder.scope, builder.tools);
    }

    /**
     * 获取工具组名称。
     *
     * @return 组名（永不为 null）
     */
    public String getName() {
        return name;
    }

    /**
     * 获取工具组描述。
     *
     * @return 组描述（未设置时为空字符串）
     */
    public String getDescription() {
        return description;
    }

    /**
     * 检查工具组当前是否激活。
     *
     * @return 激活时为 true，否则为 false
     */
    public boolean isActive() {
        return active;
    }

    /**
     * 设置工具组激活状态。
     *
     * @param active true 表示激活，false 表示停用
     */
    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * 获取工具组作用域。
     *
     * @return 组作用域（{@link ToolGroupScope#META} 或 {@link ToolGroupScope#EXTERNAL}）
     */
    public ToolGroupScope getScope() {
        return scope;
    }

    /**
     * 获取组内工具的防御性副本。
     *
     * @return 包含工具名称的新集合
     */
    public Set<String> getTools() {
        return new HashSet<>(tools); // 防御性复制
    }

    /**
     * 向组内添加工具。
     *
     * @param toolName 要添加的工具名称
     */
    public void addTool(String toolName) {
        tools.add(toolName);
    }

    /**
     * 从组内移除工具。
     *
     * @param toolName 要移除的工具名称
     */
    public void removeTool(String toolName) {
        tools.remove(toolName);
    }

    /**
     * 检查组是否包含指定工具。
     *
     * @param toolName 要检查的工具名称
     * @return 工具在组内时为 true，否则为 false
     */
    public boolean containsTool(String toolName) {
        return tools.contains(toolName);
    }

    /**
     * 创建工具组的深副本，保留子类的具体类型。
     *
     * @return 状态相同的新 ToolGroup 或子类实例
     */
    public ToolGroup copy() {
        return ToolGroup.builder()
                .name(name)
                .description(description)
                .active(active)
                .scope(scope)
                .tools(tools)
                .build();
    }

    /**
     * 创建用于构造 ToolGroup 实例的构建器。
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 用于构造 ToolGroup 实例的构建器。
     */
    public static class Builder {

        private String name;
        private String description = "";
        private boolean active = true;
        private ToolGroupScope scope = ToolGroupScope.META;
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
         * 设置工具组描述。
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
         * @param active true 表示激活（默认），false 表示未激活
         * @return 当前构建器，支持链式调用
         */
        public Builder active(boolean active) {
            this.active = active;
            return this;
        }

        /**
         * 设置工具组作用域。
         *
         * @param scope {@link ToolGroupScope#META}（默认）由智能体管理，{@link ToolGroupScope#EXTERNAL} 由开发者管理
         * @return 当前构建器，支持链式调用
         */
        public Builder scope(ToolGroupScope scope) {
            this.scope = scope;
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
         * 使用配置构建 ToolGroup。
         *
         * @return 新的 ToolGroup 实例
         * @throws NullPointerException 当 name 为 null 时
         */
        public ToolGroup build() {
            return new ToolGroup(this);
        }
    }
}
