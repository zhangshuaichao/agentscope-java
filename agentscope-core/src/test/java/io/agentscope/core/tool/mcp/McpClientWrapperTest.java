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
package io.agentscope.core.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class McpClientWrapperTest {

    private TestMcpClientWrapper wrapper;

    @BeforeEach
    void setUp() {
        wrapper = new TestMcpClientWrapper("test-client");
    }

    @Test
    void testGetName() {
        assertEquals("test-client", wrapper.getName());
    }

    @Test
    void testInitializedFlag_DefaultFalse() {
        assertFalse(wrapper.isInitialized());
    }

    @Test
    void testInitializedFlag_AfterInitialize() {
        wrapper.initialize().block();
        assertTrue(wrapper.isInitialized());
    }

    @Test
    void testCachedTools_EmptyByDefault() {
        assertNotNull(wrapper.cachedTools);
        assertTrue(wrapper.cachedTools.isEmpty());
    }

    @Test
    void testGetCachedTool_NonExistent() {
        assertNull(wrapper.getCachedTool("non-existent-tool"));
    }

    @Test
    void testGetCachedTool_Exists() {
        // 向缓存添加工具
        McpSchema.Tool tool =
                McpSchema.Tool.builder("test-tool", Map.of("type", "object"))
                        .description("A test tool")
                        .build();
        wrapper.cachedTools.put("test-tool", tool);

        McpSchema.Tool retrieved = wrapper.getCachedTool("test-tool");
        assertNotNull(retrieved);
        assertEquals("test-tool", retrieved.name());
        assertEquals("A test tool", retrieved.description());
    }

    @Test
    void testCachedTools_MultipleTools() {
        // 添加多个工具
        McpSchema.Tool tool1 =
                McpSchema.Tool.builder("tool1", Map.of("type", "object"))
                        .description("First tool")
                        .build();
        McpSchema.Tool tool2 =
                McpSchema.Tool.builder("tool2", Map.of("type", "object"))
                        .description("Second tool")
                        .build();

        wrapper.cachedTools.put("tool1", tool1);
        wrapper.cachedTools.put("tool2", tool2);

        assertEquals(2, wrapper.cachedTools.size());
        assertNotNull(wrapper.getCachedTool("tool1"));
        assertNotNull(wrapper.getCachedTool("tool2"));
    }

    @Test
    void testConcurrentAccess_CachedTools() {
        // 测试使用线程安全 ConcurrentHashMap
        McpSchema.Tool tool =
                McpSchema.Tool.builder("concurrent-tool", Map.of("type", "object"))
                        .description("Test")
                        .build();

        // 不应抛 ConcurrentModificationException
        wrapper.cachedTools.put("concurrent-tool", tool);
        assertNotNull(wrapper.getCachedTool("concurrent-tool"));
    }

    @Test
    void testClose() {
        wrapper.initialize().block();
        assertTrue(wrapper.isInitialized());

        wrapper.close();

        assertFalse(wrapper.isInitialized());
        assertTrue(wrapper.cachedTools.isEmpty());
    }

    @Test
    void testClose_MultipleCallsSafe() {
        wrapper.initialize().block();

        // 多次关闭应安全
        wrapper.close();
        wrapper.close();
        wrapper.close();

        assertFalse(wrapper.isInitialized());
    }

    @Test
    void testInitialize_Idempotent() {
        // 多次初始化
        wrapper.initialize().block();
        assertTrue(wrapper.isInitialized());

        wrapper.initialize().block();
        assertTrue(wrapper.isInitialized());

        // 应仍处于已初始化状态
        assertEquals(1, wrapper.initializeCallCount);
    }

    // ==================== 测试实现 ====================

    /**
     * 用于测试的 McpClientWrapper 具体实现。
     */
    private static class TestMcpClientWrapper extends McpClientWrapper {

        private int initializeCallCount = 0;

        public TestMcpClientWrapper(String name) {
            super(name);
        }

        @Override
        public Mono<Void> initialize() {
            if (initialized) {
                return Mono.empty();
            }

            return Mono.fromRunnable(
                    () -> {
                        initializeCallCount++;
                        // 模拟初始化期间缓存工具
                        McpSchema.Tool tool =
                                McpSchema.Tool.builder("test-tool", Map.of("type", "object"))
                                        .description("A test tool")
                                        .build();
                        cachedTools.put("test-tool", tool);
                        initialized = true;
                    });
        }

        @Override
        public Mono<List<McpSchema.Tool>> listTools() {
            if (!initialized) {
                return Mono.error(new IllegalStateException("Client not initialized: " + name));
            }
            return Mono.just(List.copyOf(cachedTools.values()));
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(
                String toolName, Map<String, Object> arguments) {
            return callTool(toolName, arguments, null);
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(
                String toolName, Map<String, Object> arguments, Map<String, Object> meta) {
            if (!initialized) {
                return Mono.error(new IllegalStateException("Client not initialized: " + name));
            }

            if (!cachedTools.containsKey(toolName)) {
                return Mono.error(new IllegalArgumentException("Tool not found: " + toolName));
            }

            // 返回简单成功结果
            McpSchema.TextContent content = McpSchema.TextContent.builder("Success").build();
            return Mono.just(
                    McpSchema.CallToolResult.builder()
                            .content(List.of(content))
                            .isError(false)
                            .build());
        }

        @Override
        public void close() {
            initialized = false;
            cachedTools.clear();
        }
    }
}
