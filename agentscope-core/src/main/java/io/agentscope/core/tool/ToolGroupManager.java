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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 管理工具组及其激活状态。
 */
class ToolGroupManager {

    private static final Logger logger = LoggerFactory.getLogger(ToolGroupManager.class);

    private final Map<String, ToolGroup> toolGroups = new ConcurrentHashMap<>(); // 工具组 -> 工具
    private final Map<String, Set<String>> tools = new ConcurrentHashMap<>(); // 工具 -> 工具组
    private volatile List<String> activeGroups = new CopyOnWriteArrayList<>();

    /**
     * 创建工具组并记录到管理器。
     *
     * @param groupName 工具组名称
     * @param description 供智能体理解的工具组描述
     * @param active 工具组是否默认激活
     * @throws IllegalArgumentException 当组已存在时
     */
    public void createToolGroup(String groupName, String description, boolean active) {
        createToolGroup(groupName, description, active, ToolGroupScope.META);
    }

    /**
     * 使用显式作用域创建工具组。
     *
     * @param groupName 工具组名称
     * @param description 供智能体理解的工具组描述
     * @param active 工具组是否默认激活
     * @param scope 由元工具管理（{@link ToolGroupScope#META}）还是开发者代码管理（{@link ToolGroupScope#EXTERNAL}）
     * @throws IllegalArgumentException 当组已存在时
     */
    public void createToolGroup(
            String groupName, String description, boolean active, ToolGroupScope scope) {
        if (toolGroups.containsKey(groupName)) {
            throw new IllegalArgumentException(
                    String.format("Tool group '%s' already exists", groupName));
        }

        ToolGroup group =
                ToolGroup.builder()
                        .name(groupName)
                        .description(description)
                        .active(active)
                        .scope(scope)
                        .build();

        toolGroups.put(groupName, group);

        if (active && !activeGroups.contains(groupName)) {
            activeGroups.add(groupName);
        }

        logger.info("Created tool group '{}' (scope={}): {}", groupName, scope, description);
    }

    /**
     * 创建默认激活状态为 true、作用域为 META 的工具组。
     *
     * @param groupName 工具组名称
     * @param description 供智能体理解的工具组描述
     * @throws IllegalArgumentException 当组已存在时
     */
    public void createToolGroup(String groupName, String description) {
        createToolGroup(groupName, description, true, ToolGroupScope.META);
    }

    /**
     * 注册预先构建的 ToolGroup 实例（包括 {@link SkillToolGroup} 等子类）。
     *
     * @param group 要注册的工具组
     * @throws IllegalArgumentException 当同名组已存在时
     */
    public void registerToolGroup(ToolGroup group) {
        String groupName = group.getName();
        if (toolGroups.containsKey(groupName)) {
            throw new IllegalArgumentException(
                    String.format("Tool group '%s' already exists", groupName));
        }
        toolGroups.put(groupName, group);
        if (group.isActive() && !activeGroups.contains(groupName)) {
            activeGroups.add(groupName);
        }
        logger.info(
                "Registered tool group '{}' (scope={}): {}",
                groupName,
                group.getScope(),
                group.getDescription());
    }

    /**
     * 创建绑定特定技能的 {@link SkillToolGroup}。
     *
     * @param groupName 工具组名称
     * @param description 工具组描述
     * @param active 工具组是否默认激活
     * @param activateOnSkill 此组绑定的技能名称
     * @throws IllegalArgumentException 当组已存在时
     */
    public void createSkillToolGroup(
            String groupName, String description, boolean active, String activateOnSkill) {
        SkillToolGroup group =
                SkillToolGroup.skillBuilder()
                        .name(groupName)
                        .description(description)
                        .active(active)
                        .activateOnSkill(activateOnSkill)
                        .build();
        registerToolGroup(group);
    }

    /**
     * 查找 {@code activateOnSkill} 与给定技能名匹配的全部 {@link SkillToolGroup} 实例。
     *
     * @param skillName 要匹配的技能名称
     * @return 匹配的组名列表（永不为 null，可以为空）
     */
    public List<String> findSkillToolGroupsByActivateOnSkill(String skillName) {
        if (skillName == null) {
            return List.of();
        }
        return toolGroups.values().stream()
                .filter(
                        g ->
                                g instanceof SkillToolGroup stg
                                        && skillName.equals(stg.getActivateOnSkill()))
                .map(ToolGroup::getName)
                .toList();
    }

    /**
     * 更新工具组激活状态。
     *
     * @param groupNames 要更新的工具组名称列表
     * @param active 激活还是停用
     * @throws IllegalArgumentException 当任意组不存在时
     */
    public void updateToolGroups(List<String> groupNames, boolean active) {
        for (String groupName : groupNames) {
            ToolGroup group = toolGroups.get(groupName);
            if (group == null) {
                throw new IllegalArgumentException(
                        String.format("Tool group '%s' does not exist", groupName));
            }

            group.setActive(active);

            if (active) {
                if (!activeGroups.contains(groupName)) {
                    activeGroups.add(groupName);
                }
            } else {
                activeGroups.remove(groupName);
            }

            logger.info("Tool group '{}' active status set to: {}", groupName, active);
        }
    }

    /**
     * 移除工具组。注意：调用方负责移除关联工具。
     *
     * @param groupNames 要移除的工具组名称列表
     * @return 被移除组原先包含的工具名称集合
     */
    public Set<String> removeToolGroups(List<String> groupNames) {
        Set<String> toolsToRemove = new HashSet<>();

        for (String groupName : groupNames) {
            ToolGroup group = toolGroups.remove(groupName);
            if (group == null) {
                logger.warn("Tool group '{}' does not exist, skipping removal", groupName);
                continue;
            }

            // 收集此组内的工具
            Set<String> groupTools = group.getTools();

            // 从工具索引移除组映射
            for (String toolName : groupTools) {
                if (removeGroupFromToolIndex(toolName, groupName)) {
                    toolsToRemove.add(toolName);
                }
            }

            // 从激活组中移除
            activeGroups.remove(groupName);

            logger.info(
                    "Removed tool group '{}' with {} tools", groupName, group.getTools().size());
        }

        return toolsToRemove;
    }

    /**
     * 获取激活工具组的说明，供用户或智能体查看。
     *
     * @return 描述激活工具组的格式化字符串
     */
    public String getActivatedNotes() {
        if (activeGroups.isEmpty()) {
            return "No tool groups are currently activated.";
        }

        StringBuilder notes = new StringBuilder("Activated tool groups:\n");
        for (String groupName : activeGroups) {
            ToolGroup group = toolGroups.get(groupName);
            if (group != null) {
                notes.append(String.format("- %s: %s\n", groupName, group.getDescription()));
            }
        }
        return notes.toString();
    }

    /**
     * 获取 META 作用域工具组的说明，供智能体查看。
     *
     * <p>只包含 {@link ToolGroupScope#META} 组，因为 {@link ToolGroupScope#EXTERNAL} 组对元工具不可见。
     *
     * @return 描述 META 作用域工具组的格式化字符串
     */
    public String getNotes() {
        StringBuilder activatedNotes = new StringBuilder("Activated tool groups:\n");
        StringBuilder inactiveNotes = new StringBuilder("Inactive tool groups:\n");
        boolean hasActivatedGroup = false;
        boolean hasInactiveGroup = false;
        for (ToolGroup group : toolGroups.values()) {
            if (group.getScope() != ToolGroupScope.META) {
                continue;
            }
            if (group.isActive()) {
                hasActivatedGroup = true;
                activatedNotes.append(
                        String.format("- %s: %s\n", group.getName(), group.getDescription()));
            } else {
                hasInactiveGroup = true;
                inactiveNotes.append(
                        String.format("- %s: %s\n", group.getName(), group.getDescription()));
            }
        }

        if (!hasActivatedGroup) {
            activatedNotes.append("No tool groups are currently activated.\n");
        }

        if (!hasInactiveGroup) {
            inactiveNotes.append("No tool groups are currently inactive.\n");
        }

        activatedNotes.append(inactiveNotes);
        return activatedNotes.toString();
    }

    /**
     * 验证工具组存在。
     *
     * @param groupName 要验证的组名
     * @throws IllegalArgumentException 当组不存在时
     */
    public void validateGroupExists(String groupName) {
        if (!toolGroups.containsKey(groupName)) {
            throw new IllegalArgumentException(
                    String.format("Tool group '%s' does not exist", groupName));
        }
    }

    /**
     * 检查组是否激活。
     *
     * @param groupName 组名
     * @return 组存在且已激活时为 true
     */
    public boolean isActiveGroup(String groupName) {
        if (groupName == null) {
            return false;
        }
        ToolGroup group = toolGroups.get(groupName);
        return group != null && group.isActive();
    }

    /**
     * 检查工具是否位于任意激活组中。
     *
     * <p>工具不属于任何组时，默认视为激活。
     *
     * <p><b>旧单会话 API：</b>读取共享激活标志。逐调用路径使用 {@link #isActiveTool(String, Collection)}。
     *
     * @param toolName 工具名称
     * @return 未分组或至少属于一个激活组时为 true
     */
    public boolean isActiveTool(String toolName) {
        if (toolName == null) {
            return false;
        }
        Set<String> groups = tools.get(toolName);
        if (groups == null || groups.isEmpty()) {
            return true;
        }
        for (String groupName : groups) {
            if (isActiveGroup(groupName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查工具是否位于显式提供的任意激活组中。
     *
     * <p>这是 {@link #isActiveTool(String)} 的逐调用、无状态版本：调用方使用自己会话状态中的激活组集合解析可用性，而非读取并发修改的共享标志。{@code activeGroups} 为 {@code null} 时，回退到 {@link #isActiveGroup(String)} 读取共享标志。
     *
     * <p>工具不属于任何组时，默认视为激活。
     *
     * @param toolName 工具名称
     * @param activeGroups 视为激活的组名（可为 {@code null}）
     * @return 未分组或至少属于一个给定激活组时为 true
     */
    public boolean isActiveTool(String toolName, Collection<String> activeGroups) {
        if (toolName == null) {
            return false;
        }
        Set<String> groups = tools.get(toolName);
        if (groups == null || groups.isEmpty()) {
            return true;
        }
        for (String g : groups) {
            if (activeGroups != null ? activeGroups.contains(g) : isActiveGroup(g)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查工具是否属于任意组。
     *
     * @param toolName 工具名称
     * @return 至少属于一个组时为 true
     */
    public boolean isGroupedTool(String toolName) {
        if (toolName == null) {
            return false;
        }
        Set<String> groups = tools.get(toolName);
        return groups != null && !groups.isEmpty();
    }

    /**
     * 获取所有 {@link ToolGroupScope#META} 作用域工具组的名称。
     *
     * @return META 作用域工具组名称集合
     */
    public Set<String> getMetaGroupNames() {
        Set<String> metaNames = new HashSet<>();
        for (ToolGroup group : toolGroups.values()) {
            if (group.getScope() == ToolGroupScope.META) {
                metaNames.add(group.getName());
            }
        }
        return metaNames;
    }

    /**
     * 用给定列表替换 {@link ToolGroupScope#META} 组的激活集合。
     *
     * <p>停用 {@code toActivate} 之外的全部 META 组，不影响 {@link ToolGroupScope#EXTERNAL} 组。
     *
     * <p>旧单会话 API，会修改共享激活标志。框架现在将相同替换语义应用到逐会话 {@code ToolContextState}（{@code replaceActivatedGroups}），不再调用此方法。
     *
     * @param toActivate 要保持激活的组名（必须均为 META 作用域）
     */
    public void replaceMetaActiveGroups(List<String> toActivate) {
        // 停用全部 META 作用域组
        for (ToolGroup group : toolGroups.values()) {
            if (group.getScope() == ToolGroupScope.META && group.isActive()) {
                group.setActive(false);
                activeGroups.remove(group.getName());
            }
        }

        // 激活指定组
        for (String name : toActivate) {
            ToolGroup group = toolGroups.get(name);
            if (group != null && group.getScope() == ToolGroupScope.META) {
                group.setActive(true);
                if (!activeGroups.contains(name)) {
                    activeGroups.add(name);
                }
            }
        }
    }

    /**
     * 获取属于激活组的全部工具。
     *
     * @return 激活组内的工具名称集合
     */
    public Set<String> getActiveToolNames() {
        Set<String> activeTools = new HashSet<>();
        for (ToolGroup group : toolGroups.values()) {
            if (group.isActive()) {
                activeTools.addAll(group.getTools());
            }
        }
        return activeTools;
    }

    /**
     * 根据显式提供的激活组名称集合计算激活工具名称，不依赖共享的逐组 {@code active} 标志。
     *
     * <p>这是 {@link #getActiveToolNames()} 的逐调用、无状态版本：调用方（例如 {@code ReActAgent} 在 {@code call} 期间）根据自己 {@code (userId, sessionId)} 槽位的激活组解析工具范围，不修改或读取共享激活标志，因此不同槽位上的并发调用互不干扰。
     *
     * @param activeGroups 视为激活的组名（null 或空集合仅产生未分组工具）
     * @return 至少属于一个给定组的工具名称集合
     */
    public Set<String> getActiveToolNames(Collection<String> activeGroups) {
        Set<String> activeTools = new HashSet<>();
        if (activeGroups == null) {
            return activeTools;
        }
        for (String groupName : activeGroups) {
            ToolGroup group = toolGroups.get(groupName);
            if (group != null) {
                activeTools.addAll(group.getTools());
            }
        }
        return activeTools;
    }

    /**
     * 将工具添加到组。
     *
     * @param groupName 组名
     * @param toolName 工具名称
     */
    public void addToolToGroup(String groupName, String toolName) {
        ToolGroup group = toolGroups.get(groupName);
        if (group != null) {
            group.addTool(toolName);
            tools.computeIfAbsent(toolName, key -> ConcurrentHashMap.newKeySet()).add(groupName);
        }
    }

    /**
     * 从组中移除工具。
     *
     * @param groupName 组名
     * @param toolName 工具名称
     */
    public void removeToolFromGroup(String groupName, String toolName) {
        ToolGroup group = toolGroups.get(groupName);
        if (group != null) {
            group.removeTool(toolName);
            removeGroupFromToolIndex(toolName, groupName);
        }
    }

    /**
     * 获取全部工具组名称。
     *
     * @return 全部工具组名称集合
     */
    public Set<String> getToolGroupNames() {
        return new HashSet<>(toolGroups.keySet());
    }

    /**
     * 获取激活工具组名称。
     *
     * @return 激活组名称列表
     */
    public List<String> getActiveGroups() {
        return new ArrayList<>(activeGroups);
    }

    /**
     * 设置激活组（用于状态恢复）。
     *
     * <p>这是完全替换：先停用所有现有组，再仅激活指定组。
     *
     * @param activeGroups 要标记为激活的组名列表
     */
    public void setActiveGroups(List<String> activeGroups) {
        // 先停用全部组
        for (ToolGroup group : toolGroups.values()) {
            group.setActive(false);
        }

        this.activeGroups = new CopyOnWriteArrayList<>(activeGroups);

        // 激活指定组
        for (String groupName : activeGroups) {
            ToolGroup group = toolGroups.get(groupName);
            if (group != null) {
                group.setActive(true);
            }
        }
    }

    /**
     * 按名称获取工具组。
     *
     * @param groupName 工具组名称
     * @return ToolGroup；未找到时为 null
     */
    public ToolGroup getToolGroup(String groupName) {
        return toolGroups.get(groupName);
    }

    /**
     * 将此管理器的全部工具组及激活状态复制到另一个管理器。
     *
     * <p>深复制工具组，使目标拥有独立激活标志；{@link Toolkit#copy()} 用于构建期智能体隔离。
     *
     * @param target 接收工具组副本的目标管理器
     */
    void copyTo(ToolGroupManager target) {
        target.tools.clear();
        target.toolGroups.clear();
        for (Map.Entry<String, ToolGroup> entry : toolGroups.entrySet()) {
            target.toolGroups.put(entry.getKey(), entry.getValue().copy());
        }

        for (Map.Entry<String, Set<String>> entry : tools.entrySet()) {
            target.tools.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }

        // 复制 activeGroups 列表
        target.activeGroups = new CopyOnWriteArrayList<>(this.activeGroups);
    }

    private boolean removeGroupFromToolIndex(String toolName, String groupName) {
        Set<String> groupNames = tools.get(toolName);
        if (groupNames == null) {
            return false;
        }
        groupNames.remove(groupName);
        if (groupNames.isEmpty()) {
            tools.remove(toolName);
            return true;
        }
        return false;
    }
}
