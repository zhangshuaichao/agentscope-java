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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolGroupTest {

    @Test
    void testBuilderWithAllFields() {
        // 执行
        ToolGroup group =
                ToolGroup.builder()
                        .name("testGroup")
                        .description("Test Description")
                        .active(false)
                        .tools(Set.of("tool1", "tool2"))
                        .build();

        // 断言
        assertEquals("testGroup", group.getName());
        assertEquals("Test Description", group.getDescription());
        assertFalse(group.isActive());
        assertEquals(2, group.getTools().size());
        assertTrue(group.getTools().contains("tool1"));
        assertTrue(group.getTools().contains("tool2"));
    }

    @Test
    void testBuilderWithMinimalFields() {
        // 执行
        ToolGroup group = ToolGroup.builder().name("testGroup").build();

        // 断言
        assertEquals("testGroup", group.getName());
        assertEquals("", group.getDescription());
        assertTrue(group.isActive(), "Should default to active");
        assertTrue(group.getTools().isEmpty());
    }

    @Test
    void testBuilderNullName() {
        // 执行并断言
        assertThrows(NullPointerException.class, () -> ToolGroup.builder().build());
    }

    @Test
    void testBuilderNullDescription() {
        // 执行
        ToolGroup group = ToolGroup.builder().name("testGroup").description(null).build();

        // 断言
        assertEquals("", group.getDescription(), "Null description should default to empty string");
    }

    @Test
    void testSetActive() {
        // 准备
        ToolGroup group = ToolGroup.builder().name("testGroup").active(true).build();

        // 执行
        group.setActive(false);

        // 断言
        assertFalse(group.isActive());

        // 执行 again
        group.setActive(true);

        // 断言
        assertTrue(group.isActive());
    }

    @Test
    void testAddTool() {
        // 准备
        ToolGroup group = ToolGroup.builder().name("testGroup").build();

        // 执行
        group.addTool("tool1");
        group.addTool("tool2");

        // 断言
        assertEquals(2, group.getTools().size());
        assertTrue(group.containsTool("tool1"));
        assertTrue(group.containsTool("tool2"));
    }

    @Test
    void testAddDuplicateTool() {
        // 准备
        ToolGroup group = ToolGroup.builder().name("testGroup").build();

        // 执行
        group.addTool("tool1");
        group.addTool("tool1"); // 重复项

        // 断言
        assertEquals(1, group.getTools().size(), "Set should not contain duplicates");
        assertTrue(group.containsTool("tool1"));
    }

    @Test
    void testRemoveTool() {
        // 准备
        ToolGroup group =
                ToolGroup.builder().name("testGroup").tools(Set.of("tool1", "tool2")).build();

        // 执行
        group.removeTool("tool1");

        // 断言
        assertEquals(1, group.getTools().size());
        assertFalse(group.containsTool("tool1"));
        assertTrue(group.containsTool("tool2"));
    }

    @Test
    void testRemoveNonexistentTool() {
        // 准备
        ToolGroup group = ToolGroup.builder().name("testGroup").tools(Set.of("tool1")).build();

        // 执行
        group.removeTool("nonexistent");

        // 断言
        assertEquals(1, group.getTools().size());
        assertTrue(group.containsTool("tool1"));
    }

    @Test
    void testContainsTool() {
        // 准备
        ToolGroup group =
                ToolGroup.builder().name("testGroup").tools(Set.of("tool1", "tool2")).build();

        // 执行并断言
        assertTrue(group.containsTool("tool1"));
        assertTrue(group.containsTool("tool2"));
        assertFalse(group.containsTool("tool3"));
        assertFalse(group.containsTool("nonexistent"));
    }

    @Test
    void testGetToolsReturnsDefensiveCopy() {
        // 准备
        ToolGroup group = ToolGroup.builder().name("testGroup").tools(Set.of("tool1")).build();

        // 执行
        Set<String> tools1 = group.getTools();
        tools1.add("tool2"); // 修改返回集合

        // 断言
        assertFalse(
                group.containsTool("tool2"),
                "Modifications to returned set should not affect group");
        assertEquals(1, group.getTools().size());
    }

    @Test
    void testBuilderToolsCreatesDefensiveCopy() {
        // 准备
        Set<String> originalTools = new HashSet<>(Set.of("tool1"));

        // 执行
        ToolGroup group = ToolGroup.builder().name("testGroup").tools(originalTools).build();
        originalTools.add("tool2"); // 修改原始集合

        // 断言
        assertFalse(
                group.containsTool("tool2"),
                "Modifications to original set should not affect group");
        assertEquals(1, group.getTools().size());
    }

    @Test
    void testBuilderChaining() {
        // 执行
        ToolGroup group =
                ToolGroup.builder()
                        .name("testGroup")
                        .description("Description")
                        .active(false)
                        .tools(Set.of("tool1"))
                        .build();

        // 断言
        assertEquals("testGroup", group.getName());
        assertEquals("Description", group.getDescription());
        assertFalse(group.isActive());
        assertEquals(1, group.getTools().size());
    }

    @Test
    void testEmptyDescription() {
        // 执行
        ToolGroup group = ToolGroup.builder().name("testGroup").description("").build();

        // 断言
        assertEquals("", group.getDescription());
    }

    @Test
    void testActiveDefaultValue() {
        // 执行
        ToolGroup group = ToolGroup.builder().name("testGroup").build();

        // 断言
        assertTrue(group.isActive(), "Default active value should be true");
    }

    @Test
    void testAddToolAfterConstruction() {
        // 准备
        ToolGroup group = ToolGroup.builder().name("testGroup").build();

        // 执行
        group.addTool("tool1");
        group.addTool("tool2");
        group.addTool("tool3");

        // 断言
        assertEquals(3, group.getTools().size());
        assertTrue(group.containsTool("tool1"));
        assertTrue(group.containsTool("tool2"));
        assertTrue(group.containsTool("tool3"));
    }

    @Test
    void testRemoveAllTools() {
        // 准备
        ToolGroup group =
                ToolGroup.builder().name("testGroup").tools(Set.of("tool1", "tool2")).build();

        // 执行
        group.removeTool("tool1");
        group.removeTool("tool2");

        // 断言
        assertTrue(group.getTools().isEmpty());
    }

    @Test
    void testMixedOperations() {
        // 准备
        ToolGroup group = ToolGroup.builder().name("testGroup").tools(Set.of("tool1")).build();

        // 执行
        group.addTool("tool2");
        group.addTool("tool3");
        group.removeTool("tool1");
        group.setActive(false);

        // 断言
        assertEquals(2, group.getTools().size());
        assertFalse(group.containsTool("tool1"));
        assertTrue(group.containsTool("tool2"));
        assertTrue(group.containsTool("tool3"));
        assertFalse(group.isActive());
    }
}
