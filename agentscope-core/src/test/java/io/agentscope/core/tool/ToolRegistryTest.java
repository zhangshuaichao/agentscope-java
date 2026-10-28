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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ToolRegistryTest {

    private ToolRegistry registry;
    private AgentTool mockTool1;
    private AgentTool mockTool2;
    private RegisteredToolFunction registered1;
    private RegisteredToolFunction registered2;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();

        // 创建模拟工具
        mockTool1 = createMockTool("tool1", "Description 1");
        mockTool2 = createMockTool("tool2", "Description 2");

        // 创建已注册包装器
        registered1 = new RegisteredToolFunction(mockTool1, null, null);
        registered2 = new RegisteredToolFunction(mockTool2, null, "mcpClient1");
    }

    private AgentTool createMockTool(String name, String description) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public Map<String, Object> getParameters() {
                return new HashMap<>();
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(ToolResultBlock.text("result"));
            }
        };
    }

    @Test
    void testRegisterTool() {
        // 执行
        registry.registerTool("tool1", mockTool1, registered1);

        // 断言
        assertEquals(mockTool1, registry.getTool("tool1"));
        assertEquals(registered1, registry.getRegisteredTool("tool1"));
        assertTrue(registry.getToolNames().contains("tool1"));
    }

    @Test
    void testRegisterMultipleTools() {
        // 执行
        registry.registerTool("tool1", mockTool1, registered1);
        registry.registerTool("tool2", mockTool2, registered2);

        // 断言
        assertEquals(2, registry.getToolNames().size());
        assertEquals(mockTool1, registry.getTool("tool1"));
        assertEquals(mockTool2, registry.getTool("tool2"));
    }

    @Test
    void testGetToolNotFound() {
        // 执行
        AgentTool result = registry.getTool("nonexistent");

        // 断言
        assertNull(result);
    }

    @Test
    void testGetRegisteredToolNotFound() {
        // 执行
        RegisteredToolFunction result = registry.getRegisteredTool("nonexistent");

        // 断言
        assertNull(result);
    }

    @Test
    void testGetToolNames() {
        // 准备
        registry.registerTool("tool1", mockTool1, registered1);
        registry.registerTool("tool2", mockTool2, registered2);

        // 执行
        Set<String> names = registry.getToolNames();

        // 断言
        assertEquals(2, names.size());
        assertTrue(names.contains("tool1"));
        assertTrue(names.contains("tool2"));
    }

    @Test
    void testGetToolNamesEmpty() {
        // 执行
        Set<String> names = registry.getToolNames();

        // 断言
        assertTrue(names.isEmpty());
    }

    @Test
    void testGetAllRegisteredTools() {
        // 准备
        registry.registerTool("tool1", mockTool1, registered1);
        registry.registerTool("tool2", mockTool2, registered2);

        // 执行
        Map<String, RegisteredToolFunction> allTools = registry.getAllRegisteredTools();

        // 断言
        assertEquals(2, allTools.size());
        assertEquals(registered1, allTools.get("tool1"));
        assertEquals(registered2, allTools.get("tool2"));
    }

    @Test
    void testGetAllRegisteredToolsReturnsNewMap() {
        // 准备
        registry.registerTool("tool1", mockTool1, registered1);

        // 执行
        Map<String, RegisteredToolFunction> map1 = registry.getAllRegisteredTools();
        Map<String, RegisteredToolFunction> map2 = registry.getAllRegisteredTools();

        // 断言
        assertNotSame(map1, map2, "Should return a new map each time");
        assertEquals(map1, map2, "But maps should be equal");
    }

    @Test
    void testRemoveTool() {
        // 准备
        registry.registerTool("tool1", mockTool1, registered1);
        registry.registerTool("tool2", mockTool2, registered2);

        // 执行
        registry.removeTool("tool1");

        // 断言
        assertNull(registry.getTool("tool1"));
        assertNull(registry.getRegisteredTool("tool1"));
        assertEquals(1, registry.getToolNames().size());
        assertTrue(registry.getToolNames().contains("tool2"));
    }

    @Test
    void testRemoveNonexistentTool() {
        // 准备
        registry.registerTool("tool1", mockTool1, registered1);

        // 执行并断言：不应抛出异常
        assertDoesNotThrow(() -> registry.removeTool("nonexistent"));

        // 验证原工具仍存在
        assertEquals(mockTool1, registry.getTool("tool1"));
    }

    @Test
    void testRemoveTools() {
        // 准备
        registry.registerTool("tool1", mockTool1, registered1);
        registry.registerTool("tool2", mockTool2, registered2);
        AgentTool mockTool3 = createMockTool("tool3", "Description 3");
        RegisteredToolFunction registered3 = new RegisteredToolFunction(mockTool3, null, null);
        registry.registerTool("tool3", mockTool3, registered3);

        // 执行
        registry.removeTools(Set.of("tool1", "tool3"));

        // 断言
        assertNull(registry.getTool("tool1"));
        assertNull(registry.getTool("tool3"));
        assertEquals(mockTool2, registry.getTool("tool2"));
        assertEquals(1, registry.getToolNames().size());
    }

    @Test
    void testRemoveToolsWithEmptySet() {
        // 准备
        registry.registerTool("tool1", mockTool1, registered1);

        // 执行
        registry.removeTools(Set.of());

        // 断言
        assertEquals(mockTool1, registry.getTool("tool1"));
        assertEquals(1, registry.getToolNames().size());
    }

    @Test
    void testRemoveToolsWithNonexistentNames() {
        // 准备
        registry.registerTool("tool1", mockTool1, registered1);

        // 执行并断言
        assertDoesNotThrow(() -> registry.removeTools(Set.of("nonexistent1", "nonexistent2")));

        // 验证原工具仍存在
        assertEquals(mockTool1, registry.getTool("tool1"));
    }

    @Test
    void testOverwriteTool() {
        // 准备
        registry.registerTool("tool1", mockTool1, registered1);
        AgentTool newTool = createMockTool("tool1", "New Description");
        RegisteredToolFunction newRegistered = new RegisteredToolFunction(newTool, null, null);

        // 执行
        registry.registerTool("tool1", newTool, newRegistered);

        // 断言
        assertEquals(newTool, registry.getTool("tool1"));
        assertEquals(newRegistered, registry.getRegisteredTool("tool1"));
        assertEquals("New Description", registry.getTool("tool1").getDescription());
        assertEquals(1, registry.getToolNames().size());
    }

    @Test
    void testConcurrentAccess() {
        // 测试正确使用 ConcurrentHashMap
        // 从多个线程注册工具
        Thread t1 =
                new Thread(
                        () -> {
                            for (int i = 0; i < 100; i++) {
                                AgentTool tool = createMockTool("tool_t1_" + i, "Desc " + i);
                                RegisteredToolFunction reg =
                                        new RegisteredToolFunction(tool, null, null);
                                registry.registerTool("tool_t1_" + i, tool, reg);
                            }
                        });

        Thread t2 =
                new Thread(
                        () -> {
                            for (int i = 0; i < 100; i++) {
                                AgentTool tool = createMockTool("tool_t2_" + i, "Desc " + i);
                                RegisteredToolFunction reg =
                                        new RegisteredToolFunction(tool, null, null);
                                registry.registerTool("tool_t2_" + i, tool, reg);
                            }
                        });

        // 执行
        t1.start();
        t2.start();

        // 等待线程完成
        assertDoesNotThrow(
                () -> {
                    t1.join();
                    t2.join();
                });

        // 断言
        assertEquals(200, registry.getToolNames().size());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException register null name tool")
    void testRegisterNullNameTool() {
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.registerTool(null, mockTool1, registered1));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException register empty name tool")
    void testRegisterEmptyNameTool() {
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.registerTool("", mockTool1, registered1));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException register blank name tool")
    void testRegisterBlankNameTool() {
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.registerTool("  ", mockTool1, registered1));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException remove null name tool")
    void testRemoveNullNameTool() {
        assertThrows(IllegalArgumentException.class, () -> registry.removeTool(null));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException remove empty name tool")
    void testRemoveEmptyNameTool() {
        assertThrows(IllegalArgumentException.class, () -> registry.removeTool(""));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException remove blank name tool")
    void testRemoveBlankNameTool() {
        assertThrows(IllegalArgumentException.class, () -> registry.removeTool("  "));
    }

    @Test
    @DisplayName("Should return null get null name tool")
    void testGetNullNameTool() {
        assertNull(registry.getTool(null));
    }

    @Test
    @DisplayName("Should return null get empty name tool")
    void testGetEmptyNameTool() {
        assertNull(registry.getTool(""));
    }

    @Test
    @DisplayName("Should return null get blank name tool")
    void testGetBlankNameTool() {
        assertNull(registry.getTool("  "));
    }
}
