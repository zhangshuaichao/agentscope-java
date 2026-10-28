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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolGroupManagerTest {

    private ToolGroupManager manager;

    @BeforeEach
    void setUp() {
        manager = new ToolGroupManager();
    }

    @Test
    void testCreateToolGroupWithAllParameters() {
        // 执行
        manager.createToolGroup("analytics", "Analytics tools", true);

        // 断言
        ToolGroup group = manager.getToolGroup("analytics");
        assertNotNull(group);
        assertEquals("analytics", group.getName());
        assertEquals("Analytics tools", group.getDescription());
        assertTrue(group.isActive());
        assertTrue(manager.getActiveGroups().contains("analytics"));
    }

    @Test
    void testCreateToolGroupDefaultActive() {
        // 执行
        manager.createToolGroup("search", "Search tools");

        // 断言
        ToolGroup group = manager.getToolGroup("search");
        assertNotNull(group);
        assertTrue(group.isActive());
        assertTrue(manager.getActiveGroups().contains("search"));
    }

    @Test
    void testCreateToolGroupInactive() {
        // 执行
        manager.createToolGroup("admin", "Admin tools", false);

        // 断言
        ToolGroup group = manager.getToolGroup("admin");
        assertNotNull(group);
        assertFalse(group.isActive());
        assertFalse(manager.getActiveGroups().contains("admin"));
    }

    @Test
    void testCreateDuplicateToolGroup() {
        // 准备
        manager.createToolGroup("analytics", "Analytics tools");

        // 执行并断言
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> manager.createToolGroup("analytics", "Different description"));

        assertTrue(exception.getMessage().contains("already exists"));
        assertTrue(exception.getMessage().contains("analytics"));
    }

    @Test
    void testUpdateToolGroupsActivate() {
        // 准备
        manager.createToolGroup("group1", "Group 1", false);
        manager.createToolGroup("group2", "Group 2", false);

        // 执行
        manager.updateToolGroups(List.of("group1", "group2"), true);

        // 断言
        assertTrue(manager.getToolGroup("group1").isActive());
        assertTrue(manager.getToolGroup("group2").isActive());
        assertTrue(manager.getActiveGroups().contains("group1"));
        assertTrue(manager.getActiveGroups().contains("group2"));
    }

    @Test
    void testUpdateToolGroupsDeactivate() {
        // 准备
        manager.createToolGroup("group1", "Group 1", true);
        manager.createToolGroup("group2", "Group 2", true);

        // 执行
        manager.updateToolGroups(List.of("group1"), false);

        // 断言
        assertFalse(manager.getToolGroup("group1").isActive());
        assertTrue(manager.getToolGroup("group2").isActive());
        assertFalse(manager.getActiveGroups().contains("group1"));
        assertTrue(manager.getActiveGroups().contains("group2"));
    }

    @Test
    void testUpdateNonexistentToolGroup() {
        // 执行并断言
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> manager.updateToolGroups(List.of("nonexistent"), true));

        assertTrue(exception.getMessage().contains("does not exist"));
        assertTrue(exception.getMessage().contains("nonexistent"));
    }

    @Test
    void testRemoveToolGroups() {
        // 准备
        manager.createToolGroup("group1", "Group 1");
        manager.createToolGroup("group2", "Group 2");
        manager.addToolToGroup("group1", "tool1");
        manager.addToolToGroup("group1", "tool2");
        manager.addToolToGroup("group2", "tool3");

        // 执行
        Set<String> removedTools = manager.removeToolGroups(List.of("group1", "group2"));

        // 断言
        assertEquals(3, removedTools.size());
        assertTrue(removedTools.contains("tool1"));
        assertTrue(removedTools.contains("tool2"));
        assertTrue(removedTools.contains("tool3"));
        assertNull(manager.getToolGroup("group1"));
        assertNull(manager.getToolGroup("group2"));
        assertFalse(manager.getActiveGroups().contains("group1"));
        assertFalse(manager.getActiveGroups().contains("group2"));
    }

    @Test
    void testRemoveNonexistentToolGroup() {
        // 准备
        manager.createToolGroup("group1", "Group 1");

        // 执行：不应抛出异常，只记录警告
        Set<String> removedTools = manager.removeToolGroups(List.of("nonexistent", "group1"));

        // 断言
        assertNotNull(removedTools);
        assertNull(manager.getToolGroup("group1"));
    }

    @Test
    void testGetActivatedNotesEmpty() {
        // 执行
        String notes = manager.getActivatedNotes();

        // 断言
        assertTrue(notes.contains("No tool groups"));
    }

    @Test
    void testGetActivatedNotesWithGroups() {
        // 准备
        manager.createToolGroup("analytics", "Analytics tools", true);
        manager.createToolGroup("search", "Search tools", true);
        manager.createToolGroup("admin", "Admin tools", false);

        // 执行
        String notes = manager.getActivatedNotes();

        // 断言
        assertTrue(notes.contains("Activated tool groups"));
        assertTrue(notes.contains("analytics"));
        assertTrue(notes.contains("Analytics tools"));
        assertTrue(notes.contains("search"));
        assertTrue(notes.contains("Search tools"));
        assertFalse(notes.contains("admin"));
    }

    @Test
    void testValidateGroupExists() {
        // 准备
        manager.createToolGroup("existing", "Existing group");

        // 执行并断言：不应抛出异常
        manager.validateGroupExists("existing");
    }

    @Test
    void testValidateGroupDoesNotExist() {
        // 执行并断言
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> manager.validateGroupExists("nonexistent"));

        assertTrue(exception.getMessage().contains("does not exist"));
        assertTrue(exception.getMessage().contains("nonexistent"));
    }

    @Test
    void testIsActiveToolUngrouped() {
        // 执行并断言
        assertTrue(manager.isActiveTool("ungroupedTool"));
    }

    @Test
    void testIsActiveToolInactive() {
        // 准备
        manager.createToolGroup("inactive", "Inactive group", false);
        manager.addToolToGroup("inactive", "tool1");

        // 执行并断言
        assertFalse(manager.isActiveTool("tool1"));
    }

    @Test
    void testIsInActiveGroupToolNotAnyTool() {
        // 准备
        manager.createToolGroup("group1", "Group 1", true);

        // 执行并断言
        assertTrue(manager.isActiveTool("ungroupedTool"));
    }

    @Test
    void testIsActiveGroupActive() {
        // 准备
        manager.createToolGroup("active", "Active group", true);

        // 执行并断言
        assertTrue(manager.isActiveGroup("active"));
    }

    @Test
    void testIsActiveGroupInactive() {
        // 准备
        manager.createToolGroup("inactive", "Inactive group", false);

        // 执行并断言
        assertFalse(manager.isActiveGroup("inactive"));
    }

    @Test
    void testIsActiveGroupNonexistent() {
        // 执行并断言
        assertFalse(manager.isActiveGroup("nonexistent"));
    }

    @Test
    void testAddToolToGroup() {
        // 准备
        manager.createToolGroup("group1", "Group 1");

        // 执行
        manager.addToolToGroup("group1", "tool1");
        manager.addToolToGroup("group1", "tool2");

        // 断言
        ToolGroup group = manager.getToolGroup("group1");
        assertTrue(group.containsTool("tool1"));
        assertTrue(group.containsTool("tool2"));
    }

    @Test
    void testAddToolToNonexistentGroup() {
        // 执行：不应抛出异常，也不应改变状态
        manager.addToolToGroup("nonexistent", "tool1");

        // 断言
        assertNull(manager.getToolGroup("nonexistent"));
    }

    @Test
    void testRemoveToolFromGroup() {
        // 准备
        manager.createToolGroup("group1", "Group 1");
        manager.addToolToGroup("group1", "tool1");
        manager.addToolToGroup("group1", "tool2");

        // 执行
        manager.removeToolFromGroup("group1", "tool1");

        // 断言
        ToolGroup group = manager.getToolGroup("group1");
        assertFalse(group.containsTool("tool1"));
        assertTrue(group.containsTool("tool2"));
    }

    @Test
    void testRemoveToolFromNonexistentGroup() {
        // 执行：不应抛出异常，也不应改变状态
        manager.removeToolFromGroup("nonexistent", "tool1");

        // 断言
        assertNull(manager.getToolGroup("nonexistent"));
    }

    @Test
    void testGetToolGroupNames() {
        // 准备
        manager.createToolGroup("group1", "Group 1");
        manager.createToolGroup("group2", "Group 2");
        manager.createToolGroup("group3", "Group 3");

        // 执行
        Set<String> names = manager.getToolGroupNames();

        // 断言
        assertEquals(3, names.size());
        assertTrue(names.contains("group1"));
        assertTrue(names.contains("group2"));
        assertTrue(names.contains("group3"));
    }

    @Test
    void testGetToolGroupNamesEmpty() {
        // 执行
        Set<String> names = manager.getToolGroupNames();

        // 断言
        assertTrue(names.isEmpty());
    }

    @Test
    void testGetActiveGroups() {
        // 准备
        manager.createToolGroup("active1", "Active 1", true);
        manager.createToolGroup("active2", "Active 2", true);
        manager.createToolGroup("inactive", "Inactive", false);

        // 执行
        List<String> activeGroups = manager.getActiveGroups();

        // 断言
        assertEquals(2, activeGroups.size());
        assertTrue(activeGroups.contains("active1"));
        assertTrue(activeGroups.contains("active2"));
        assertFalse(activeGroups.contains("inactive"));
    }

    @Test
    void testGetActiveGroupsEmpty() {
        // 准备
        manager.createToolGroup("inactive", "Inactive", false);

        // 执行
        List<String> activeGroups = manager.getActiveGroups();

        // 断言
        assertTrue(activeGroups.isEmpty());
    }

    @Test
    void testSetActiveGroups() {
        // 准备
        manager.createToolGroup("group1", "Group 1", false);
        manager.createToolGroup("group2", "Group 2", false);
        manager.createToolGroup("group3", "Group 3", true);

        // 执行
        manager.setActiveGroups(List.of("group1", "group2"));

        // 断言
        List<String> activeGroups = manager.getActiveGroups();
        assertEquals(2, activeGroups.size());
        assertTrue(activeGroups.contains("group1"));
        assertTrue(activeGroups.contains("group2"));

        // 检查组被标记为活动
        assertTrue(manager.getToolGroup("group1").isActive());
        assertTrue(manager.getToolGroup("group2").isActive());
    }

    @Test
    void testSetActiveGroupsWithNonexistent() {
        // 准备
        manager.createToolGroup("group1", "Group 1", false);

        // 执行：不应抛出异常，只忽略不存在的项
        manager.setActiveGroups(List.of("group1", "nonexistent"));

        // 断言
        List<String> activeGroups = manager.getActiveGroups();
        assertEquals(2, activeGroups.size());
        assertTrue(activeGroups.contains("group1"));
        assertTrue(activeGroups.contains("nonexistent"));
        assertTrue(manager.getToolGroup("group1").isActive());
    }

    @Test
    void testGetToolGroup() {
        // 准备
        manager.createToolGroup("group1", "Group 1");

        // 执行
        ToolGroup group = manager.getToolGroup("group1");

        // 断言
        assertNotNull(group);
        assertEquals("group1", group.getName());
        assertEquals("Group 1", group.getDescription());
    }

    @Test
    void testGetToolGroupNonexistent() {
        // 执行
        ToolGroup group = manager.getToolGroup("nonexistent");

        // 断言
        assertNull(group);
    }

    @Test
    void testUpdatePreventsDuplicatesInActiveGroups() {
        // 准备
        manager.createToolGroup("group1", "Group 1", true);

        // 执行：多次激活同一个工具组
        manager.updateToolGroups(List.of("group1"), true);
        manager.updateToolGroups(List.of("group1"), true);

        // 断言：不应产生重复项
        List<String> activeGroups = manager.getActiveGroups();
        assertEquals(1, activeGroups.stream().filter(g -> g.equals("group1")).count());
    }

    @Test
    void testCreateToolGroupWithScope() {
        // 执行
        manager.createToolGroup("meta_group", "META group", true, ToolGroupScope.META);
        manager.createToolGroup("ext_group", "EXTERNAL group", true, ToolGroupScope.EXTERNAL);

        // 断言
        assertEquals(ToolGroupScope.META, manager.getToolGroup("meta_group").getScope());
        assertEquals(ToolGroupScope.EXTERNAL, manager.getToolGroup("ext_group").getScope());
    }

    @Test
    void testCreateToolGroupDefaultScope() {
        // 执行
        manager.createToolGroup("default_group", "Default scope");

        // 断言：默认作用域为 META
        assertEquals(ToolGroupScope.META, manager.getToolGroup("default_group").getScope());
    }

    @Test
    void testGetMetaGroupNames() {
        // 准备
        manager.createToolGroup("meta1", "META 1", true, ToolGroupScope.META);
        manager.createToolGroup("meta2", "META 2", false, ToolGroupScope.META);
        manager.createToolGroup("ext1", "EXTERNAL 1", true, ToolGroupScope.EXTERNAL);

        // 执行
        Set<String> metaNames = manager.getMetaGroupNames();

        // 断言
        assertEquals(2, metaNames.size());
        assertTrue(metaNames.contains("meta1"));
        assertTrue(metaNames.contains("meta2"));
        assertFalse(metaNames.contains("ext1"));
    }

    @Test
    void testReplaceMetaActiveGroups() {
        // 准备
        manager.createToolGroup("meta1", "META 1", true, ToolGroupScope.META);
        manager.createToolGroup("meta2", "META 2", true, ToolGroupScope.META);
        manager.createToolGroup("meta3", "META 3", false, ToolGroupScope.META);

        // 执行替换：只有 meta3 应保持激活
        manager.replaceMetaActiveGroups(List.of("meta3"));

        // 断言
        assertFalse(manager.getToolGroup("meta1").isActive());
        assertFalse(manager.getToolGroup("meta2").isActive());
        assertTrue(manager.getToolGroup("meta3").isActive());
    }

    @Test
    void testReplaceMetaActiveGroupsPreservesExternal() {
        // 准备
        manager.createToolGroup("meta1", "META 1", true, ToolGroupScope.META);
        manager.createToolGroup("ext1", "EXTERNAL 1", true, ToolGroupScope.EXTERNAL);

        // 执行：用空列表替换 META 工具组
        manager.replaceMetaActiveGroups(List.of());

        // 断言：停用 META，保留 EXTERNAL
        assertFalse(manager.getToolGroup("meta1").isActive());
        assertTrue(manager.getToolGroup("ext1").isActive());
    }

    @Test
    void testSetActiveGroupsDeactivatesOthers() {
        // 准备
        manager.createToolGroup("group1", "Group 1", true);
        manager.createToolGroup("group2", "Group 2", true);
        manager.createToolGroup("group3", "Group 3", true);

        // 执行：只将 group1 设为激活
        manager.setActiveGroups(List.of("group1"));

        // 断言：group2 与 group3 都应停用
        assertTrue(manager.getToolGroup("group1").isActive());
        assertFalse(manager.getToolGroup("group2").isActive());
        assertFalse(manager.getToolGroup("group3").isActive());
        assertEquals(1, manager.getActiveGroups().size());
    }

    @Test
    void testGetNotesOnlyShowsMetaGroups() {
        // 准备
        manager.createToolGroup("meta1", "META tools", true, ToolGroupScope.META);
        manager.createToolGroup("ext1", "EXTERNAL tools", true, ToolGroupScope.EXTERNAL);

        // 执行
        String notes = manager.getNotes();

        // 断言：不应包含 EXTERNAL 工具组
        assertTrue(notes.contains("meta1"));
        assertFalse(notes.contains("ext1"));
    }

    @Test
    void testComplexScenario() {
        // 准备
        manager.createToolGroup("analytics", "Analytics tools", true);
        manager.createToolGroup("search", "Search tools", true);
        manager.createToolGroup("admin", "Admin tools", false);

        manager.addToolToGroup("analytics", "chart");
        manager.addToolToGroup("analytics", "report");
        manager.addToolToGroup("search", "google");

        // 执行：停用 analytics，激活 admin
        manager.updateToolGroups(List.of("analytics"), false);
        manager.updateToolGroups(List.of("admin"), true);

        // 断言
        assertFalse(manager.isActiveGroup("analytics"));
        assertTrue(manager.isActiveGroup("search"));
        assertTrue(manager.isActiveGroup("admin"));
        assertFalse(manager.isActiveTool("chart"));
        assertTrue(manager.isActiveTool("google"));

        List<String> activeGroups = manager.getActiveGroups();
        assertEquals(2, activeGroups.size());
        assertTrue(activeGroups.contains("search"));
        assertTrue(activeGroups.contains("admin"));

        // 移除 analytics
        Set<String> removedTools = manager.removeToolGroups(List.of("analytics"));
        assertEquals(2, removedTools.size());
        assertTrue(removedTools.contains("chart"));
        assertTrue(removedTools.contains("report"));

        assertNull(manager.getToolGroup("analytics"));
        assertEquals(2, manager.getToolGroupNames().size());
    }
}
