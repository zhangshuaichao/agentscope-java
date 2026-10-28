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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolExecutionContext;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

/**
 * {@link McpTool#callAsync(ToolCallParam)} 的 MCP meta 提取逻辑测试。
 *
 * <p>验证仅注册在 {@link McpMeta} 类型命名空间下的对象进入 {@code CallToolRequest} 的 {@code meta} 参数，且支持全部 ContextStore 实现。
 */
class McpToolMetaExtractionTest {

    private McpClientWrapper mockClientWrapper;
    private Map<String, Object> parameters;
    private McpSchema.CallToolResult successResult;

    @BeforeEach
    void setUp() {
        // McpTool.callAsync 动态读取连接级开关，此处默认启用。
        mockClientWrapper = McpClientWrapperTestSupport.mockWrapper("test-client", true);

        parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", new HashMap<>());

        McpSchema.TextContent resultContent = McpSchema.TextContent.builder("Success").build();
        successResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(resultContent))
                        .isError(false)
                        .build();
    }

    /**
     * 辅助方法：捕获传给 clientWrapper.callTool(name, args, meta) 的 meta Map。
     *
     * <p>模拟带 meta 的三参数重载并捕获第三个参数。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> captureMeta(McpTool tool, ToolCallParam param) {
        // 模拟三参数 callTool 返回成功
        when(mockClientWrapper.callTool(eq("test-tool"), any(Map.class), any(Map.class)))
                .thenReturn(Mono.just(successResult));

        // 执行
        ToolResultBlock result = tool.callAsync(param).block();
        assertNotNull(result);

        // 捕获 meta 参数
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mockClientWrapper).callTool(eq("test-tool"), any(Map.class), metaCaptor.capture());

        return metaCaptor.getValue();
    }

    @Test
    void carriesCallIdentityOutsideArgumentsWithoutMutatingUserMetadata() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);
        McpMeta original =
                new McpMeta(Map.of("traceId", "trace", "io.agentscope/toolCallId", "untrusted"));
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(
                                ToolUseBlock.builder()
                                        .id("call-local-validation")
                                        .name("test-tool")
                                        .input(Map.of())
                                        .build())
                        .runtimeContext(
                                RuntimeContext.builder().put(McpMeta.class, original).build())
                        .build();
        Map<String, Object> captured = captureMeta(tool, param);
        assertEquals("call-local-validation", captured.get("io.agentscope/toolCallId"));
        assertEquals("trace", captured.get("traceId"));
        assertEquals("untrusted", original.entries().get("io.agentscope/toolCallId"));
        verify(mockClientWrapper).callTool(eq("test-tool"), eq(Map.of()), eq(captured));
    }

    // ==================== RuntimeContext 路径 ====================

    @Test
    void testMetaExtracted_FromRuntimeContext() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        McpMeta mcpMeta = new McpMeta(Map.of("traceId", "abc-123", "userId", "u456"));
        RuntimeContext ctx = RuntimeContext.builder().put(McpMeta.class, mcpMeta).build();

        ToolCallParam param =
                ToolCallParam.builder().input(new HashMap<>()).runtimeContext(ctx).build();

        Map<String, Object> capturedMeta = captureMeta(tool, param);

        assertEquals(2, capturedMeta.size());
        assertEquals("abc-123", capturedMeta.get("traceId"));
        assertEquals("u456", capturedMeta.get("userId"));
    }

    @Test
    void testMetaExtracted_SingleEntry() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        McpMeta mcpMeta = new McpMeta(Map.of("callbackUrl", "https://hook.example.com"));
        RuntimeContext ctx = RuntimeContext.builder().put(McpMeta.class, mcpMeta).build();

        ToolCallParam param =
                ToolCallParam.builder().input(new HashMap<>()).runtimeContext(ctx).build();

        Map<String, Object> capturedMeta = captureMeta(tool, param);

        assertEquals(1, capturedMeta.size());
        assertEquals("https://hook.example.com", capturedMeta.get("callbackUrl"));
    }

    // ==================== null/空值安全 ====================

    @Test
    void testMetaExtracted_ContextIsNull_EmptyMetaSent() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        // 未设置上下文，不应 NPE，应发送空 meta
        ToolCallParam param = ToolCallParam.builder().input(new HashMap<>()).build();

        Map<String, Object> capturedMeta = captureMeta(tool, param);

        assertNotNull(capturedMeta);
        assertTrue(capturedMeta.isEmpty());
    }

    @Test
    void testMetaExtracted_ContextWithNoMcpMeta_EmptyMetaSent() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        // 上下文存在但未注册 McpMeta，仅有非 meta 对象
        ToolExecutionContext ctx =
                ToolExecutionContext.builder()
                        .register("just-a-string-value") // 不是 McpMeta
                        .build();

        ToolCallParam param = ToolCallParam.builder().input(new HashMap<>()).context(ctx).build();

        Map<String, Object> capturedMeta = captureMeta(tool, param);

        assertNotNull(capturedMeta);
        assertTrue(capturedMeta.isEmpty());
    }

    @Test
    void testMetaExtracted_McpMetaIsEmpty_EmptyMetaSent() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        // 注册了 McpMeta，但 entries 为空
        McpMeta emptyMeta = new McpMeta(null);
        RuntimeContext ctx = RuntimeContext.builder().put(McpMeta.class, emptyMeta).build();

        ToolCallParam param =
                ToolCallParam.builder().input(new HashMap<>()).runtimeContext(ctx).build();

        Map<String, Object> capturedMeta = captureMeta(tool, param);

        assertNotNull(capturedMeta);
        assertTrue(capturedMeta.isEmpty());
    }

    // ==================== meta 中的值类型 ====================

    @Test
    void testMetaExtracted_ComplexValueTypes() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        McpMeta mcpMeta =
                new McpMeta(
                        Map.of(
                                "stringVal",
                                "hello",
                                "intVal",
                                42,
                                "boolVal",
                                true,
                                "nested",
                                Map.of("inner", "value")));

        RuntimeContext ctx = RuntimeContext.builder().put(McpMeta.class, mcpMeta).build();

        ToolCallParam param =
                ToolCallParam.builder().input(new HashMap<>()).runtimeContext(ctx).build();

        Map<String, Object> capturedMeta = captureMeta(tool, param);

        assertEquals(4, capturedMeta.size());
        assertEquals("hello", capturedMeta.get("stringVal"));
        assertEquals(42, capturedMeta.get("intVal"));
        assertEquals(true, capturedMeta.get("boolVal"));
        assertEquals(Map.of("inner", "value"), capturedMeta.get("nested"));
    }

    // ==================== 预设参数与 meta 共存 ====================

    @Test
    void testMetaExtracted_PresetArgsAndMetaCoexist() {
        Map<String, Object> presetArgs = new HashMap<>();
        presetArgs.put("units", "celsius");

        McpTool tool =
                new McpTool("test-tool", "Description", parameters, mockClientWrapper, presetArgs);

        McpMeta mcpMeta = new McpMeta(Map.of("traceId", "abc-123"));
        RuntimeContext ctx = RuntimeContext.builder().put(McpMeta.class, mcpMeta).build();

        Map<String, Object> input = new HashMap<>();
        input.put("location", "Beijing");

        ToolCallParam param = ToolCallParam.builder().input(input).runtimeContext(ctx).build();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> argsCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);

        when(mockClientWrapper.callTool(eq("test-tool"), any(Map.class), any(Map.class)))
                .thenReturn(Mono.just(successResult));

        ToolResultBlock result = tool.callAsync(param).block();
        assertNotNull(result);

        verify(mockClientWrapper)
                .callTool(eq("test-tool"), argsCaptor.capture(), metaCaptor.capture());

        // 参数应同时包含预设与输入
        Map<String, Object> capturedArgs = argsCaptor.getValue();
        assertEquals("Beijing", capturedArgs.get("location"));
        assertEquals("celsius", capturedArgs.get("units"));

        // Meta 应包含 McpMeta 条目
        Map<String, Object> capturedMeta = metaCaptor.getValue();
        assertEquals(1, capturedMeta.size());
        assertEquals("abc-123", capturedMeta.get("traceId"));
    }
}
