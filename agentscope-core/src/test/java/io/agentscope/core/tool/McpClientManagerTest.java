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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpTool;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class McpClientManagerTest {

    private McpClientManager manager;
    private Method shouldRegisterToolMethod;

    @BeforeEach
    void setUp() throws Exception {
        ToolRegistry toolRegistry = new ToolRegistry();
        ToolGroupManager groupManager = new ToolGroupManager();

        // 使用空操作回调创建管理器
        manager =
                new McpClientManager(
                        toolRegistry,
                        groupManager,
                        (tool, groupName, mcpClientName, presetParams) -> {
                            // 测试用空操作回调
                        });

        // 通过反射取得私有方法
        shouldRegisterToolMethod =
                McpClientManager.class.getDeclaredMethod(
                        "shouldRegisterTool", String.class, List.class, List.class);
        shouldRegisterToolMethod.setAccessible(true);
    }

    private boolean invokeShouldRegisterTool(
            String toolName, List<String> enableTools, List<String> disableTools) throws Exception {
        return (boolean)
                shouldRegisterToolMethod.invoke(manager, toolName, enableTools, disableTools);
    }

    // ==================== null/空列表测试 ====================

    @Test
    void testShouldRegisterTool_BothListsNull_ReturnsTrue() throws Exception {
        // 两个列表均为 null 时应注册所有工具
        assertTrue(invokeShouldRegisterTool("anyTool", null, null));
    }

    @Test
    void testShouldRegisterTool_BothListsEmpty_ReturnsTrue() throws Exception {
        // 两个列表均为空时应注册所有工具
        assertTrue(
                invokeShouldRegisterTool(
                        "anyTool", Collections.emptyList(), Collections.emptyList()));
    }

    // ==================== 仅 disableTools 测试 ====================

    @Test
    void testShouldRegisterTool_DisableToolsContainsTool_ReturnsFalse() throws Exception {
        // 工具位于 disableTools 时不应注册
        List<String> disableTools = Arrays.asList("tool1", "tool2", "tool3");
        assertFalse(invokeShouldRegisterTool("tool2", null, disableTools));
    }

    @Test
    void testShouldRegisterTool_DisableToolsDoesNotContainTool_ReturnsTrue() throws Exception {
        // 工具不在 disableTools 时应注册
        List<String> disableTools = Arrays.asList("tool1", "tool2", "tool3");
        assertTrue(invokeShouldRegisterTool("tool4", null, disableTools));
    }

    // ==================== 仅 enableTools 测试 ====================

    @Test
    void testShouldRegisterTool_EnableToolsContainsTool_ReturnsTrue() throws Exception {
        // 工具位于 enableTools 时应注册
        List<String> enableTools = Arrays.asList("tool1", "tool2", "tool3");
        assertTrue(invokeShouldRegisterTool("tool2", enableTools, null));
    }

    @Test
    void testShouldRegisterTool_EnableToolsDoesNotContainTool_ReturnsFalse() throws Exception {
        // 工具不在 enableTools 时不应注册
        List<String> enableTools = Arrays.asList("tool1", "tool2", "tool3");
        assertFalse(invokeShouldRegisterTool("tool4", enableTools, null));
    }

    // ==================== 同时指定两个列表的测试 ====================

    @Test
    void testShouldRegisterTool_BothListsSpecified_EnableToolsTakesPrecedence() throws Exception {
        // enableTools 最后检查，因此优先
        List<String> enableTools = Arrays.asList("tool1", "tool2");
        List<String> disableTools = Arrays.asList("tool2", "tool3");

        // tool1：不在 disableTools，位于 enableTools，结果 true
        assertTrue(invokeShouldRegisterTool("tool1", enableTools, disableTools));

        // tool2：位于 disableTools 本应 false，但也在 enableTools，因此 true，启用列表优先
        assertTrue(invokeShouldRegisterTool("tool2", enableTools, disableTools));

        // tool3：位于 disableTools，不在 enableTools，结果 false
        assertFalse(invokeShouldRegisterTool("tool3", enableTools, disableTools));

        // tool4：两个列表均无，但指定了 enableTools，因此 false
        assertFalse(invokeShouldRegisterTool("tool4", enableTools, disableTools));
    }

    // 测试 McpClientManager 的预设参数功能
    @Test
    void testRegisterMcpClient_WithPresetParametersMapping() {
        // 创建模拟对象
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolGroupManager groupManager = mock(ToolGroupManager.class);
        McpClientWrapper clientWrapper = mock(McpClientWrapper.class);

        // 创建带跟踪回调的管理器
        boolean[] callbackCalled = {false};
        McpClientManager manager =
                new McpClientManager(
                        toolRegistry,
                        groupManager,
                        (tool, groupName, mcpClientName, presetParams) -> {
                            callbackCalled[0] = true;
                            // 验证 presetParams 正确传递
                            assertNotNull(presetParams);
                            assertTrue(presetParams.containsKey("param3"));
                            assertEquals("preset_value", presetParams.get("param3"));
                        });

        // 设置客户端包装器
        when(clientWrapper.getName()).thenReturn("test-client");
        when(clientWrapper.initialize()).thenReturn(Mono.empty());

        // 创建模拟 MCP 工具
        McpSchema.Tool mockMcpTool = mock(McpSchema.Tool.class);
        when(mockMcpTool.name()).thenReturn("test-tool");
        when(mockMcpTool.description()).thenReturn("Test tool description");

        // 创建带属性的 schema
        Map<String, Object> properties = new HashMap<>();
        properties.put("param1", Map.of("type", "string"));
        properties.put("param2", Map.of("type", "number"));
        List<String> required = List.of("param1");

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        when(mockMcpTool.inputSchema()).thenReturn(schema);

        when(clientWrapper.listTools()).thenReturn(Mono.just(List.of(mockMcpTool)));

        // 定义预设参数映射
        Map<String, Map<String, Object>> presetParamsMapping = new HashMap<>();
        Map<String, Object> toolPresetParams = new HashMap<>();
        toolPresetParams.put("param3", "preset_value");
        toolPresetParams.put("param4", 42);
        presetParamsMapping.put("test-tool", toolPresetParams);

        // 执行注册
        manager.registerMcpClient(clientWrapper, null, null, null, presetParamsMapping).block();

        // 验证交互
        verify(clientWrapper).initialize();
        verify(clientWrapper).listTools();
        assertTrue(callbackCalled[0]);
    }

    @Test
    void testRegisterMcpClient_WithPresetParametersKeySetExclusion() {
        // 创建模拟对象
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolGroupManager groupManager = mock(ToolGroupManager.class);
        McpClientWrapper clientWrapper = mock(McpClientWrapper.class);

        // 创建带跟踪回调的管理器
        boolean[] callbackCalled = {false};
        McpClientManager manager =
                new McpClientManager(
                        toolRegistry,
                        groupManager,
                        (tool, groupName, mcpClientName, presetParams) -> {
                            callbackCalled[0] = true;
                            // 验证 presetParams 正确传递
                            assertNotNull(presetParams);
                            assertTrue(presetParams.containsKey("units"));
                            assertEquals("celsius", presetParams.get("units"));
                        });

        // 设置客户端包装器
        when(clientWrapper.getName()).thenReturn("test-client");
        when(clientWrapper.initialize()).thenReturn(Mono.empty());

        // 创建带参数 schema 的模拟 MCP 工具
        McpSchema.Tool mockMcpTool = mock(McpSchema.Tool.class);
        when(mockMcpTool.name()).thenReturn("weather-tool");
        when(mockMcpTool.description()).thenReturn("Weather tool");

        // 创建多参数 schema
        Map<String, Object> properties = new HashMap<>();
        properties.put("city", Map.of("type", "string", "description", "City name"));
        properties.put("units", Map.of("type", "string", "description", "Temperature units"));
        properties.put("forecast_days", Map.of("type", "number", "description", "Number of days"));

        List<String> required = List.of("city");

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        when(mockMcpTool.inputSchema()).thenReturn(schema);

        when(clientWrapper.listTools()).thenReturn(Mono.just(List.of(mockMcpTool)));

        // 定义应从 schema 排除的预设参数
        Map<String, Map<String, Object>> presetParamsMapping = new HashMap<>();
        Map<String, Object> toolPresetParams = new HashMap<>();
        toolPresetParams.put("units", "celsius"); // 此项应从 schema 排除
        toolPresetParams.put("forecast_days", 5); // 此项应从 schema 排除
        presetParamsMapping.put("weather-tool", toolPresetParams);

        // 执行注册，应覆盖 keySet 排除逻辑
        manager.registerMcpClient(clientWrapper, null, null, null, presetParamsMapping).block();

        // 验证发生交互
        verify(clientWrapper).initialize();
        verify(clientWrapper).listTools();
        assertTrue(callbackCalled[0]);
    }

    @Test
    void testRegisterMcpClient_WithEmptyPresetParameters() {
        // 创建模拟对象
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolGroupManager groupManager = mock(ToolGroupManager.class);
        McpClientWrapper clientWrapper = mock(McpClientWrapper.class);

        // 创建带跟踪回调的管理器
        boolean[] callbackCalled = {false};
        McpClientManager manager =
                new McpClientManager(
                        toolRegistry,
                        groupManager,
                        (tool, groupName, mcpClientName, presetParams) -> {
                            callbackCalled[0] = true;
                        });

        // 设置客户端包装器
        when(clientWrapper.getName()).thenReturn("test-client");
        when(clientWrapper.initialize()).thenReturn(Mono.empty());

        // 创建模拟 MCP 工具
        McpSchema.Tool mockMcpTool = mock(McpSchema.Tool.class);
        when(mockMcpTool.name()).thenReturn("simple-tool");
        when(mockMcpTool.description()).thenReturn("Simple tool");

        when(mockMcpTool.inputSchema()).thenReturn(Map.of("type", "object"));

        when(clientWrapper.listTools()).thenReturn(Mono.just(List.of(mockMcpTool)));

        // 使用空预设参数映射
        Map<String, Map<String, Object>> presetParamsMapping = new HashMap<>();
        presetParamsMapping.put("simple-tool", new HashMap<>()); // 空预设参数

        // 执行注册
        manager.registerMcpClient(clientWrapper, null, null, null, presetParamsMapping).block();

        verify(clientWrapper).initialize();
        verify(clientWrapper).listTools();
        assert callbackCalled[0];
    }

    @Test
    void testRegisterMcpClient_WithNullPresetParametersForTool() {
        // 创建模拟对象
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolGroupManager groupManager = mock(ToolGroupManager.class);
        McpClientWrapper clientWrapper = mock(McpClientWrapper.class);

        // 创建带跟踪回调的管理器
        boolean[] callbackCalled = {false};
        McpClientManager manager =
                new McpClientManager(
                        toolRegistry,
                        groupManager,
                        (tool, groupName, mcpClientName, presetParams) -> {
                            callbackCalled[0] = true;
                        });

        // 设置客户端包装器
        when(clientWrapper.getName()).thenReturn("test-client");
        when(clientWrapper.initialize()).thenReturn(Mono.empty());

        // 创建模拟 MCP 工具
        McpSchema.Tool mockMcpTool = mock(McpSchema.Tool.class);
        when(mockMcpTool.name()).thenReturn("null-param-tool");
        when(mockMcpTool.description()).thenReturn("Tool with null preset params");

        when(mockMcpTool.inputSchema()).thenReturn(Map.of("type", "object"));

        when(clientWrapper.listTools()).thenReturn(Mono.just(List.of(mockMcpTool)));

        // 整个预设参数映射使用 null
        // 覆盖 presetParametersMapping != null 条件中的 null 检查
        manager.registerMcpClient(clientWrapper, null, null, null, null).block();

        verify(clientWrapper).initialize();
        verify(clientWrapper).listTools();
        assertTrue(callbackCalled[0]);
    }

    @Test
    void testRegisterMcpClient_PreservesOutputSchemaInRegisteredTool() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolGroupManager groupManager = mock(ToolGroupManager.class);
        McpClientWrapper clientWrapper = mock(McpClientWrapper.class);

        AgentTool[] registeredTool = new AgentTool[1];
        String[] registeredGroupName = new String[1];
        String[] registeredClientName = new String[1];
        Map<String, Object>[] registeredPresetParams = new Map[1];

        McpClientManager manager =
                new McpClientManager(
                        toolRegistry,
                        groupManager,
                        (tool, groupName, mcpClientName, presetParams) -> {
                            registeredTool[0] = tool;
                            registeredGroupName[0] = groupName;
                            registeredClientName[0] = mcpClientName;
                            registeredPresetParams[0] = presetParams;
                        });

        when(clientWrapper.getName()).thenReturn("test-client");
        when(clientWrapper.initialize()).thenReturn(Mono.empty());

        McpSchema.Tool mockMcpTool = mock(McpSchema.Tool.class);
        when(mockMcpTool.name()).thenReturn("structured-tool");
        when(mockMcpTool.description()).thenReturn("Tool with output schema");
        when(mockMcpTool.inputSchema())
                .thenReturn(
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("query", Map.of("type", "string")),
                                "required",
                                List.of("query")));

        Map<String, Object> outputSchema =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "result",
                                Map.of("type", "string"),
                                "confidence",
                                Map.of("type", "number")));
        when(mockMcpTool.outputSchema()).thenReturn(outputSchema);
        when(clientWrapper.listTools()).thenReturn(Mono.just(List.of(mockMcpTool)));

        Map<String, Object> toolPresetParams = Map.of("temperature", 0.2);
        Map<String, Map<String, Object>> presetParamsMapping =
                Map.of("structured-tool", toolPresetParams);

        manager.registerMcpClient(clientWrapper, null, null, "mcp-group", presetParamsMapping)
                .block();

        verify(clientWrapper).initialize();
        verify(clientWrapper).listTools();
        assertNotNull(registeredTool[0]);
        assertEquals(outputSchema, registeredTool[0].getOutputSchema());
        assertTrue(registeredTool[0] instanceof McpTool);
        assertNull(((McpTool) registeredTool[0]).getPresetArguments());
        assertEquals("mcp-group", registeredGroupName[0]);
        assertEquals("test-client", registeredClientName[0]);
        assertEquals(toolPresetParams, registeredPresetParams[0]);
    }

    @Test
    void testRegisterMcpClient_PropagatesInitializationFailure() {
        McpClientWrapper clientWrapper = mock(McpClientWrapper.class);
        IllegalStateException failure = new IllegalStateException("initialize failure");
        when(clientWrapper.getName()).thenReturn("broken-client");
        when(clientWrapper.initialize()).thenReturn(Mono.error(failure));

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () -> manager.registerMcpClient(clientWrapper).block());

        assertSame(failure, thrown);
        verify(clientWrapper).initialize();
        verify(clientWrapper, never()).listTools();
    }

    @Test
    void testRegisterMcpClient_PropagatesListToolsFailure() {
        McpClientWrapper clientWrapper = mock(McpClientWrapper.class);
        IllegalStateException failure = new IllegalStateException("list tools failure");
        when(clientWrapper.getName()).thenReturn("broken-client");
        when(clientWrapper.initialize()).thenReturn(Mono.empty());
        when(clientWrapper.listTools()).thenReturn(Mono.error(failure));

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () -> manager.registerMcpClient(clientWrapper).block());

        assertSame(failure, thrown);
        verify(clientWrapper).initialize();
        verify(clientWrapper).listTools();
    }

    // ==================== 元数据传播测试 ====================

    private McpClientManager newCapturingManager(AgentTool[] registeredTool) {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolGroupManager groupManager = mock(ToolGroupManager.class);
        return new McpClientManager(
                toolRegistry,
                groupManager,
                (tool, groupName, mcpClientName, presetParams) -> registeredTool[0] = tool);
    }

    private McpClientWrapper newMetaMockWrapper() {
        McpClientWrapper clientWrapper = mock(McpClientWrapper.class);
        when(clientWrapper.getName()).thenReturn("meta-client");
        when(clientWrapper.initialize()).thenReturn(Mono.empty());

        McpSchema.Tool mockMcpTool = mock(McpSchema.Tool.class);
        when(mockMcpTool.name()).thenReturn("meta-tool");
        when(mockMcpTool.description()).thenReturn("Tool for meta propagation tests");
        when(mockMcpTool.inputSchema()).thenReturn(Map.of("type", "object"));
        when(clientWrapper.listTools()).thenReturn(Mono.just(List.of(mockMcpTool)));
        return clientWrapper;
    }

    @Test
    void testRegisterMcpClient_ToolFlagDefaultsTrueRegardlessOfWrapper() {
        // 包装器的 isPropagateMeta() 未设置桩，Mockito 默认 false。管理器
        // 不得读取它：逐工具标志仅携带注册配置，
        // 默认 true；连接级开关则在调用时动态应用。
        AgentTool[] registeredTool = new AgentTool[1];
        McpClientManager metaManager = newCapturingManager(registeredTool);
        McpClientWrapper clientWrapper = newMetaMockWrapper();

        metaManager.registerMcpClient(clientWrapper).block();

        assertNotNull(registeredTool[0]);
        assertTrue(registeredTool[0] instanceof McpTool);
        assertTrue(((McpTool) registeredTool[0]).isPropagateMeta());
        verify(clientWrapper, never()).isPropagateMeta();
    }

    @Test
    void testRegisterMcpClient_PropagateMetaOverrideAppliedToTool() {
        McpClientWrapper clientWrapper = newMetaMockWrapper();

        AgentTool[] offTool = new AgentTool[1];
        McpClientManager offManager = newCapturingManager(offTool);
        offManager
                .registerMcpClient(clientWrapper, null, null, null, null, null, Boolean.FALSE)
                .block();
        assertNotNull(offTool[0]);
        assertFalse(((McpTool) offTool[0]).isPropagateMeta());

        AgentTool[] onTool = new AgentTool[1];
        McpClientManager onManager = newCapturingManager(onTool);
        onManager
                .registerMcpClient(clientWrapper, null, null, null, null, null, Boolean.TRUE)
                .block();
        assertNotNull(onTool[0]);
        assertTrue(((McpTool) onTool[0]).isPropagateMeta());
    }

    @Test
    void testRegisterMcpClient_PerToolOverrideWinsOverRegistrationDefault() {
        McpClientWrapper clientWrapper = newMetaMockWrapper();

        // 逐工具条目 false 优先于注册级默认 true
        AgentTool[] restricted = new AgentTool[1];
        McpClientManager restrictedManager = newCapturingManager(restricted);
        restrictedManager
                .registerMcpClient(
                        clientWrapper,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Boolean.TRUE,
                        Map.of("meta-tool", false))
                .block();
        assertNotNull(restricted[0]);
        assertFalse(((McpTool) restricted[0]).isPropagateMeta());

        // 其他工具名称的条目不影响此工具
        AgentTool[] unaffected = new AgentTool[1];
        McpClientManager unaffectedManager = newCapturingManager(unaffected);
        unaffectedManager
                .registerMcpClient(
                        clientWrapper,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Boolean.TRUE,
                        Map.of("meta-tool", true))
                .block();
        assertNotNull(unaffected[0]);
        assertTrue(((McpTool) unaffected[0]).isPropagateMeta());
        // 成功注册保持包装器打开，由 mcpClients 管理。
        verify(clientWrapper, never()).close();
    }

    @Test
    void testRegisterMcpClient_UnknownPerToolOverrideNameFailsRegistration() {
        // 对服务端未暴露工具设置静默传播覆盖时，必须在
        // 注册时明确失败，而非静默丢弃；否则工具仍会
        // 传播元数据，恰好造成此开关本要防止的泄漏。
        McpClientWrapper clientWrapper = newMetaMockWrapper();

        McpClientManager metaManager = newCapturingManager(new AgentTool[1]);
        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                metaManager
                                        .registerMcpClient(
                                                clientWrapper,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                Boolean.TRUE,
                                                Map.of("some-other-tool", false))
                                        .block());
        assertTrue(thrown.getMessage().contains("some-other-tool"));
        // 检查失败前客户端已初始化，没有其他对象能关闭它。
        verify(clientWrapper).close();
    }

    @Test
    void testRegisterMcpClient_OverrideForFilteredOutToolFailsRegistration() {
        // 对被启用/禁用过滤器排除的工具设置覆盖，同样属于无效
        // 配置，必须在注册时明确失败。
        McpClientWrapper clientWrapper = newMetaMockWrapper();

        McpClientManager metaManager = newCapturingManager(new AgentTool[1]);
        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                metaManager
                                        .registerMcpClient(
                                                clientWrapper,
                                                null,
                                                List.of("meta-tool"),
                                                null,
                                                null,
                                                null,
                                                Boolean.TRUE,
                                                Map.of("meta-tool", false))
                                        .block());
        assertTrue(thrown.getMessage().contains("meta-tool"));
        // 检查失败前客户端已初始化，没有其他对象能关闭它。
        verify(clientWrapper).close();
    }

    @Test
    void testRegisterMcpClient_ReadOnlyHintAppliedToTool() {
        // MCP 服务端的 annotations.readOnlyHint 决定 ToolBase.readOnly，后者
        // 控制工具能否无需显式权限就运行（McpTool）。
        McpClientWrapper clientWrapper = mock(McpClientWrapper.class);
        when(clientWrapper.getName()).thenReturn("annotated-client");
        when(clientWrapper.initialize()).thenReturn(Mono.empty());

        McpSchema.Tool readOnlyTool = mock(McpSchema.Tool.class);
        when(readOnlyTool.name()).thenReturn("readonly_tool");
        when(readOnlyTool.description()).thenReturn("Read-only tool");
        when(readOnlyTool.inputSchema()).thenReturn(Map.of("type", "object"));
        when(readOnlyTool.annotations())
                .thenReturn(new McpSchema.ToolAnnotations(null, true, null, null, null, null));

        McpSchema.Tool writableTool = mock(McpSchema.Tool.class);
        when(writableTool.name()).thenReturn("writable_tool");
        when(writableTool.description()).thenReturn("Writable tool");
        when(writableTool.inputSchema()).thenReturn(Map.of("type", "object"));
        when(writableTool.annotations())
                .thenReturn(new McpSchema.ToolAnnotations(null, false, null, null, null, null));

        when(clientWrapper.listTools()).thenReturn(Mono.just(List.of(readOnlyTool, writableTool)));

        AgentTool[] registered = new AgentTool[2];
        McpClientManager annotatedManager =
                new McpClientManager(
                        mock(ToolRegistry.class),
                        mock(ToolGroupManager.class),
                        (tool, groupName, mcpClientName, presetParams) -> {
                            if ("readonly_tool".equals(tool.getName())) {
                                registered[0] = tool;
                            } else {
                                registered[1] = tool;
                            }
                        });
        annotatedManager.registerMcpClient(clientWrapper).block();

        assertTrue(registered[0] instanceof McpTool);
        assertTrue(((McpTool) registered[0]).isReadOnly());
        assertTrue(registered[1] instanceof McpTool);
        assertFalse(((McpTool) registered[1]).isReadOnly());
    }

    // ==================== 注册时传递显示标题的测试
    // ====================

    /**
     * 这些案例有意构建真实 {@link McpSchema.Tool}。其他注册测试都模拟它，仅为 {@code name()}/{@code description()}/{@code inputSchema()} 设置桩，因此 {@code title()} 保持 Mockito 默认 {@code null}，解析并交付标题的那行从未在有标题值时运行。
     */
    private McpClientWrapper newTitleMockWrapper(McpSchema.Tool tool) {
        McpClientWrapper clientWrapper = mock(McpClientWrapper.class);
        when(clientWrapper.getName()).thenReturn("title-client");
        when(clientWrapper.initialize()).thenReturn(Mono.empty());
        when(clientWrapper.listTools()).thenReturn(Mono.just(List.of(tool)));
        return clientWrapper;
    }

    @Test
    void testRegisterMcpClient_toolTitleReachesRegisteredTool() {
        McpSchema.Tool tool =
                McpSchema.Tool.builder("title-tool", Map.of("type", "object"))
                        .description("Creates a repair ticket")
                        .title("Repair Ticket")
                        .build();
        AgentTool[] registeredTool = new AgentTool[1];

        newCapturingManager(registeredTool).registerMcpClient(newTitleMockWrapper(tool)).block();

        assertNotNull(registeredTool[0]);
        assertEquals("Repair Ticket", registeredTool[0].getTitle());
        assertEquals(
                "title-tool", registeredTool[0].getName(), "the model-facing name stays untouched");
    }

    @Test
    void testRegisterMcpClient_annotationTitleIsTheFallback() {
        McpSchema.Tool tool =
                McpSchema.Tool.builder("title-tool", Map.of("type", "object"))
                        .description("Creates a repair ticket")
                        .annotations(
                                McpSchema.ToolAnnotations.builder().title("Repair Ticket").build())
                        .build();
        AgentTool[] registeredTool = new AgentTool[1];

        newCapturingManager(registeredTool).registerMcpClient(newTitleMockWrapper(tool)).block();

        assertEquals("Repair Ticket", registeredTool[0].getTitle());
    }

    @Test
    void testRegisterMcpClient_hostileTitleIsSanitisedBeforeRegistration() {
        McpSchema.Tool tool =
                McpSchema.Tool.builder("title-tool", Map.of("type", "object"))
                        .description("Creates a repair ticket")
                        .title("Delete\u202EAll Data\u0007 now")
                        .build();
        AgentTool[] registeredTool = new AgentTool[1];

        newCapturingManager(registeredTool).registerMcpClient(newTitleMockWrapper(tool)).block();

        assertEquals(
                "DeleteAll Data now",
                registeredTool[0].getTitle(),
                "the label shown in a permission prompt carries no reordering or control"
                        + " characters");
    }

    @Test
    void testRegisterMcpClient_serverSendingNoTitleKeepsNullTitle() {
        McpSchema.Tool tool =
                McpSchema.Tool.builder("title-tool", Map.of("type", "object"))
                        .description("Creates a repair ticket")
                        .build();
        AgentTool[] registeredTool = new AgentTool[1];

        newCapturingManager(registeredTool).registerMcpClient(newTitleMockWrapper(tool)).block();

        assertNull(
                registeredTool[0].getTitle(),
                "a server that sends no title leaves the pre-existing fallback to the name");
    }
}
