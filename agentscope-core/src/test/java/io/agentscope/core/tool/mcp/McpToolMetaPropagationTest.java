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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

/**
 * {@link McpTool#callAsync} 元数据传播开关测试。
 *
 * <p>实际传播是两个开关的逻辑与：
 * <ul>
 * <li>每次调用动态读取连接级 {@link McpClientWrapper#isPropagateMeta()}，禁用连接立即生效，即使工具更早已注册</li>
 * <li>注册时固定的逐工具 {@link McpTool#isPropagateMeta()} 限制</li>
 * </ul>
 *
 * <p>逻辑与为 false 时，{@code meta} 参数必须为 {@code null}，使 {@link McpSchema.CallToolRequest} 完全省略该字段；用户 {@link McpMeta} 条目与框架工具调用 ID 都不离开进程。
 */
class McpToolMetaPropagationTest {

    private static final String TOOL_CALL_ID_KEY = "io.agentscope/toolCallId";

    private McpClientWrapper mockClientWrapper;
    private Map<String, Object> parameters;
    private McpSchema.CallToolResult successResult;

    @BeforeEach
    void setUp() {
        // 连接级开关默认启用，各测试按需重新设桩。
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

    private McpTool newTool() {
        return new McpTool("test-tool", "Description", parameters, mockClientWrapper);
    }

    private ToolCallParam paramWithMeta() {
        McpMeta meta = new McpMeta(Map.of("traceId", "trace-123", "callbackUrl", "internal"));
        return ToolCallParam.builder()
                .toolUseBlock(
                        ToolUseBlock.builder()
                                .id("call-1")
                                .name("test-tool")
                                .input(Map.of())
                                .build())
                .runtimeContext(RuntimeContext.builder().put(McpMeta.class, meta).build())
                .build();
    }

    private ToolCallParam paramWithoutMeta() {
        return ToolCallParam.builder()
                .toolUseBlock(
                        ToolUseBlock.builder()
                                .id("call-1")
                                .name("test-tool")
                                .input(Map.of())
                                .build())
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureMeta(McpTool tool, ToolCallParam param) {
        when(mockClientWrapper.callTool(eq("test-tool"), any(Map.class), any(Map.class)))
                .thenReturn(Mono.just(successResult));
        assertNotNull(tool.callAsync(param).block());

        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mockClientWrapper).callTool(eq("test-tool"), any(Map.class), metaCaptor.capture());
        return metaCaptor.getValue();
    }

    private void assertMetaOmitted(McpTool tool, ToolCallParam param) {
        when(mockClientWrapper.callTool(eq("test-tool"), any(Map.class), isNull()))
                .thenReturn(Mono.just(successResult));
        assertNotNull(tool.callAsync(param).block());
        verify(mockClientWrapper).callTool(eq("test-tool"), any(Map.class), isNull());
    }

    @Test
    void propagatesMetaByDefault() {
        McpTool tool = newTool();

        Map<String, Object> meta = captureMeta(tool, paramWithMeta());

        assertNotNull(meta);
        assertEquals("trace-123", meta.get("traceId"));
        assertEquals("call-1", meta.get(TOOL_CALL_ID_KEY));
        assertEquals("internal", meta.get("callbackUrl"));
        assertTrue(tool.isPropagateMeta());
    }

    @Test
    void toolDisabledOmitsMetaEntirely() {
        McpTool tool = newTool();
        tool.setPropagateMeta(false);
        assertFalse(tool.isPropagateMeta());

        assertMetaOmitted(tool, paramWithMeta());
    }

    @Test
    void toolDisabledWithoutRuntimeContextStillOmitsMeta() {
        McpTool tool = newTool();
        tool.setPropagateMeta(false);

        assertMetaOmitted(tool, paramWithoutMeta());
    }

    @Test
    void wrapperDisabledAtCallTimeOmitsMetaEvenWhenToolAllows() {
        // 工具级限制允许传播，但动态连接级开关
        // 在逻辑与中使结果为 false，必须省略 meta。
        McpTool tool = newTool();
        when(mockClientWrapper.isPropagateMeta()).thenReturn(false);

        assertMetaOmitted(tool, paramWithMeta());
    }

    @Test
    void bothDisabledOmitsMeta() {
        McpTool tool = newTool();
        tool.setPropagateMeta(false);
        when(mockClientWrapper.isPropagateMeta()).thenReturn(false);

        assertMetaOmitted(tool, paramWithMeta());
    }

    @Test
    void wrapperToggledAtRuntimeTakesEffectImmediately() {
        // 包装器开关动态读取，变更无需重新注册即可生效。
        McpTool tool = newTool();

        Map<String, Object> meta = captureMeta(tool, paramWithMeta());
        assertEquals("trace-123", meta.get("traceId"));

        // 工具已使用后翻转连接级开关。
        when(mockClientWrapper.isPropagateMeta()).thenReturn(false);

        McpTool sameTool = tool;
        assertMetaOmitted(sameTool, paramWithMeta());
    }

    @Test
    void reEnablingToolRestoresPropagation() {
        McpTool tool = newTool();
        tool.setPropagateMeta(false);
        tool.setPropagateMeta(true);

        Map<String, Object> meta = captureMeta(tool, paramWithMeta());

        assertEquals("trace-123", meta.get("traceId"));
        assertEquals("call-1", meta.get(TOOL_CALL_ID_KEY));
    }

    @Test
    void propagatesUserMetaWithoutToolUseBlock() {
        // buildMetaMap 必须容忍缺少 ToolUseBlock，用户 McpMeta 条目仍
        // 传播，仅缺少框架工具调用 ID。
        McpTool tool = newTool();

        ToolCallParam param =
                ToolCallParam.builder()
                        .runtimeContext(
                                RuntimeContext.builder()
                                        .put(
                                                McpMeta.class,
                                                new McpMeta(Map.of("traceId", "trace-123")))
                                        .build())
                        .build();

        Map<String, Object> meta = captureMeta(tool, param);

        assertNotNull(meta);
        assertEquals("trace-123", meta.get("traceId"));
        assertFalse(meta.containsKey(TOOL_CALL_ID_KEY));
    }

    @Test
    void omitsToolCallIdWhenToolUseBlockHasNoId() {
        // 没有 ID 的 ToolUseBlock 仍传播用户 McpMeta 条目，但
        // 无法附加框架工具调用 ID。
        McpTool tool = newTool();

        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(
                                ToolUseBlock.builder().name("test-tool").input(Map.of()).build())
                        .runtimeContext(
                                RuntimeContext.builder()
                                        .put(
                                                McpMeta.class,
                                                new McpMeta(Map.of("traceId", "trace-123")))
                                        .build())
                        .build();

        Map<String, Object> meta = captureMeta(tool, param);

        assertNotNull(meta);
        assertEquals("trace-123", meta.get("traceId"));
        assertFalse(meta.containsKey(TOOL_CALL_ID_KEY));
    }

    @Test
    void disabledStillConvertsSuccessfulResults() {
        McpTool tool = newTool();
        tool.setPropagateMeta(false);

        when(mockClientWrapper.callTool(eq("test-tool"), any(Map.class), isNull()))
                .thenReturn(Mono.just(successResult));

        ToolResultBlock result = tool.callAsync(paramWithMeta()).block();
        assertNotNull(result);
        assertNotNull(result.getOutput());
        assertFalse(result.getOutput().isEmpty());
    }
}
