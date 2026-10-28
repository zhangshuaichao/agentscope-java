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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpClientWrapperTestSupport;
import io.agentscope.core.tool.mcp.McpTool;
import io.agentscope.core.tool.test.SampleTools;
import io.agentscope.core.tool.test.ToolTestUtils;
import io.agentscope.core.util.JsonUtils;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Toolkit 单元测试。
 *
 * <p>验证工具注册、发现、执行、参数验证及多工具管理。
 *
 * <p>标记为 "unit"，无需外部依赖的快速测试。
 */
@Tag("unit")
@DisplayName("Toolkit Unit Tests")
class ToolkitTest {

    private Toolkit toolkit;
    private SampleTools sampleTools;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        sampleTools = new SampleTools();
    }

    /**
     * 检查 ToolResultBlock 是否表示错误的辅助方法。
     * 错误结果输出包含文本以 "Error:" 开头的 TextBlock。
     */
    private boolean isErrorResult(ToolResultBlock result) {
        if (result == null || result.getOutput() == null || result.getOutput().isEmpty()) {
            return false;
        }
        return result.getOutput().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .anyMatch(text -> text != null && text.startsWith("Error:"));
    }

    /**
     * 从 ToolResultBlock 提取文本内容的辅助方法。
     */
    private String getResultText(ToolResultBlock result) {
        if (result == null || result.getOutput() == null || result.getOutput().isEmpty()) {
            return "";
        }
        return result.getOutput().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .findFirst()
                .orElse("");
    }

    @Test
    @DisplayName("Should register tools from object")
    void testToolRegistration() {
        // 注册示例工具，传实例而非类
        assertDoesNotThrow(
                () -> {
                    toolkit.registerTool(sampleTools);
                });

        // 验证工具已注册
        Set<String> toolNames = toolkit.getToolNames();
        assertNotNull(toolNames, "Tool names should not be null");

        // 应包含 SampleTools 类的多个工具
        assertTrue(toolNames.size() > 0, "Should have at least one tool registered");
    }

    @Test
    @DisplayName("Should discover registered tools")
    void testToolDiscovery() {
        // 注册工具
        toolkit.registerTool(sampleTools);

        // 获取全部工具名
        Set<String> toolNames = toolkit.getToolNames();
        assertNotNull(toolNames);
        assertTrue(toolNames.size() > 0);

        // 验证可取得每个工具
        for (String toolName : toolNames) {
            AgentTool tool = toolkit.getTool(toolName);
            assertNotNull(tool, "Should be able to get tool: " + toolName);
            assertNotNull(tool.getName(), "Tool should have a name");
            assertNotNull(tool.getDescription(), "Tool should have a description");
            assertNotNull(tool.getParameters(), "Tool should have parameters definition");
        }
    }

    @Test
    @DisplayName("Should execute tools correctly")
    void testToolExecution() {
        // 注册工具
        toolkit.registerTool(sampleTools);

        // 获取 add 工具
        AgentTool addTool = toolkit.getTool("add");

        if (addTool != null) {
            // 执行工具
            Map<String, Object> params = Map.of("a", 5, "b", 3);
            ToolResultBlock response =
                    addTool.callAsync(ToolCallParam.builder().input(params).build()).block();

            assertNotNull(response, "Response should not be null");
            assertTrue(ToolTestUtils.isValidToolResultBlock(response), "Response should be valid");
        }
    }

    @Test
    @DisplayName("Should validate tool parameters")
    void testParameterValidation() {
        // 注册工具
        toolkit.registerTool(sampleTools);

        Set<String> toolNames = toolkit.getToolNames();
        assertNotNull(toolNames);

        // 检查每个工具有有效参数定义
        for (String toolName : toolNames) {
            AgentTool tool = toolkit.getTool(toolName);
            Map<String, Object> parameters = tool.getParameters();
            assertNotNull(parameters, "Parameters should not be null for tool: " + toolName);

            // 参数应为有效 JSON Schema 对象
            assertTrue(
                    parameters.containsKey("type") || parameters.isEmpty(),
                    "Parameters should have 'type' field or be empty");
        }
    }

    @Test
    @DisplayName("Should manage multiple tools")
    void testMultipleTools() {
        // 注册工具
        toolkit.registerTool(sampleTools);

        Set<String> toolNames = toolkit.getToolNames();
        assertNotNull(toolNames);

        // 应有多个不同工具
        assertTrue(toolNames.size() >= 3, "Should have at least 3 tools registered");

        // 验证所有工具名唯一，由 Set 保证
        assertEquals(toolNames.size(), toolNames.size(), "All tools should have unique names");
    }

    @Test
    @DisplayName("Should handle empty toolkit")
    void testEmptyToolkit() {
        // 空工具集
        Set<String> toolNames = toolkit.getToolNames();
        assertNotNull(toolNames, "Tool names should not be null even when empty");
        assertEquals(0, toolNames.size(), "Empty toolkit should have 0 tools");
    }

    @Test
    @DisplayName("Should allow tool deletion by default")
    void testDefaultAllowToolDeletion() {
        // 注册并移除工具
        toolkit.registerTool(sampleTools);
        Set<String> toolNames = toolkit.getToolNames();
        assertTrue(toolNames.size() > 0, "Should have tools registered");

        // 取得首个工具名
        String toolName = toolNames.iterator().next();

        // 默认配置下移除应生效
        assertDoesNotThrow(() -> toolkit.removeTool(toolName));

        // 工具应被移除
        AgentTool tool = toolkit.getTool(toolName);
        assertEquals(null, tool, "Tool should be removed");
    }

    @Test
    @DisplayName("Should ignore removeTool when deletion is disabled")
    void testRemoveToolWhenDeletionDisabled() {
        // 创建禁用删除的工具集
        ToolkitConfig config = ToolkitConfig.builder().allowToolDeletion(false).build();
        Toolkit toolkit = new Toolkit(config);

        // 注册工具
        toolkit.registerTool(sampleTools);
        Set<String> toolNames = toolkit.getToolNames();
        int initialCount = toolNames.size();
        assertTrue(initialCount > 0, "Should have tools registered");

        // 取得首个工具名
        String toolName = toolNames.iterator().next();

        // 移除应被忽略
        toolkit.removeTool(toolName);

        // 工具应仍存在
        AgentTool tool = toolkit.getTool(toolName);
        assertNotNull(tool, "Tool should still exist after ignored removal");
        assertEquals(initialCount, toolkit.getToolNames().size(), "Tool count should not change");
    }

    private static AgentTool namedAgentTool(String name) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "test";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object");
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.empty();
            }
        };
    }

    @Test
    @DisplayName("removeToolIfSame removes when registered instance matches")
    void removeToolIfSame_removesWhenInstanceMatches() {
        AgentTool tool = namedAgentTool("remove_if_same_a");
        toolkit.registerAgentTool(tool);
        assertTrue(toolkit.removeToolIfSame("remove_if_same_a", tool));
        assertEquals(null, toolkit.getTool("remove_if_same_a"));
    }

    @Test
    @DisplayName("removeToolIfSame does not remove when a newer tool replaced the name")
    void removeToolIfSame_noOpWhenReplaced() {
        AgentTool first = namedAgentTool("remove_if_same_b");
        AgentTool second = namedAgentTool("remove_if_same_b");
        toolkit.registerAgentTool(first);
        toolkit.registerAgentTool(second);
        assertFalse(
                toolkit.removeToolIfSame("remove_if_same_b", first),
                "stale instance after replace must return false");
        assertSame(second, toolkit.getTool("remove_if_same_b"));
        assertTrue(toolkit.removeToolIfSame("remove_if_same_b", second));
        assertEquals(null, toolkit.getTool("remove_if_same_b"));
    }

    @Test
    @DisplayName("removeToolIfSame returns false when tool name is absent")
    void removeToolIfSame_falseWhenAbsent() {
        AgentTool phantom = namedAgentTool("remove_if_same_missing");
        assertFalse(
                toolkit.removeToolIfSame("remove_if_same_missing", phantom),
                "no registration must return false");
        assertEquals(null, toolkit.getTool("remove_if_same_missing"));
    }

    @Test
    @DisplayName("removeToolIfSame returns false when expected is not the registered instance")
    void removeToolIfSame_falseWhenExpectedNotRegisteredInstance() {
        AgentTool registered = namedAgentTool("remove_if_same_wrong_ref");
        AgentTool otherSameName = namedAgentTool("remove_if_same_wrong_ref");
        toolkit.registerAgentTool(registered);
        assertFalse(
                toolkit.removeToolIfSame("remove_if_same_wrong_ref", otherSameName),
                "non-matching instance must return false and leave registry unchanged");
        assertSame(registered, toolkit.getTool("remove_if_same_wrong_ref"));
    }

    @Test
    @DisplayName("removeToolIfSame returns false when deletion is disabled")
    void removeToolIfSame_falseWhenDeletionDisabled() {
        ToolkitConfig config = ToolkitConfig.builder().allowToolDeletion(false).build();
        Toolkit tk = new Toolkit(config);
        AgentTool tool = namedAgentTool("remove_if_same_no_delete");
        tk.registerAgentTool(tool);
        assertFalse(
                tk.removeToolIfSame("remove_if_same_no_delete", tool),
                "allowToolDeletion=false must return false");
        assertSame(tool, tk.getTool("remove_if_same_no_delete"));
    }

    @Test
    @DisplayName("Should ignore removeToolGroups when deletion is disabled")
    void testRemoveToolGroupsWhenDeletionDisabled() {
        // 创建禁用删除的工具集
        ToolkitConfig config = ToolkitConfig.builder().allowToolDeletion(false).build();
        Toolkit toolkit = new Toolkit(config);

        // 创建工具组并注册工具
        toolkit.createToolGroup("testGroup", "Test group");
        toolkit.registration().tool(sampleTools).group("testGroup").apply();

        Set<String> toolNames = toolkit.getToolNames();
        int initialCount = toolNames.size();
        assertTrue(initialCount > 0, "Should have tools registered");

        // 移除组应被忽略
        toolkit.removeToolGroups(List.of("testGroup"));

        // 工具应仍存在
        assertEquals(initialCount, toolkit.getToolNames().size(), "Tool count should not change");
        assertNotNull(toolkit.getToolGroup("testGroup"), "Group should still exist");
    }

    @Test
    @DisplayName("Should ignore tool group deactivation when deletion is disabled")
    void testDeactivateToolGroupWhenDeletionDisabled() {
        // 创建禁用删除的工具集
        ToolkitConfig config = ToolkitConfig.builder().allowToolDeletion(false).build();
        Toolkit toolkit = new Toolkit(config);

        // 创建活动工具组
        toolkit.createToolGroup("testGroup", "Test group", true);
        assertTrue(
                toolkit.getActiveGroups().contains("testGroup"),
                "Group should be active initially");

        // 停用应被忽略
        toolkit.updateToolGroups(List.of("testGroup"), false);

        // 组应仍活动
        assertTrue(
                toolkit.getActiveGroups().contains("testGroup"),
                "Group should still be active after ignored deactivation");
    }

    @Test
    @DisplayName("Should allow tool group activation when deletion is disabled")
    void testActivateToolGroupWhenDeletionDisabled() {
        // 创建禁用删除的工具集
        ToolkitConfig config = ToolkitConfig.builder().allowToolDeletion(false).build();
        Toolkit toolkit = new Toolkit(config);

        // 创建未激活工具组
        toolkit.createToolGroup("testGroup", "Test group", false);
        assertFalse(
                toolkit.getActiveGroups().contains("testGroup"),
                "Group should be inactive initially");

        // 激活仍应生效
        toolkit.updateToolGroups(List.of("testGroup"), true);

        // 组应被激活
        assertTrue(
                toolkit.getActiveGroups().contains("testGroup"),
                "Group should be activated even with deletion disabled");
    }

    @Test
    @DisplayName("Should allow tool registration and override when deletion is disabled")
    void testToolRegistrationWhenDeletionDisabled() {
        // 创建禁用删除的工具集
        ToolkitConfig config = ToolkitConfig.builder().allowToolDeletion(false).build();
        Toolkit toolkit = new Toolkit(config);

        // 注册应生效
        assertDoesNotThrow(() -> toolkit.registerTool(sampleTools));
        Set<String> toolNames = toolkit.getToolNames();
        assertTrue(toolNames.size() > 0, "Should be able to register tools");

        // 重注册覆盖也应生效
        assertDoesNotThrow(
                () -> toolkit.registerTool(sampleTools),
                "Should be able to override existing tools");
    }

    @Test
    @DisplayName("Should reject tool call from inactive group")
    void testUnauthorizedToolCallShouldBeRejected() {
        // 创建活动和未激活两个组
        toolkit.createToolGroup("activeGroup", "Active tools", true);
        toolkit.createToolGroup("inactiveGroup", "Inactive tools", false);

        // 将工具注册到不同的工具组
        toolkit.registration().tool(sampleTools).group("activeGroup").apply();

        // 为未激活组创建独立工具
        SampleTools inactiveTools = new SampleTools();
        toolkit.registration().tool(inactiveTools).group("inactiveGroup").apply();

        // 取得未激活组的工具，注册表中应存在
        AgentTool tool = toolkit.getTool("add");
        assertNotNull(tool, "Tool should be registered");

        // 尝试通过 callToolAsync 调用工具
        Map<String, Object> addInput = Map.of("a", 1, "b", 2);
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("add")
                        .input(addInput)
                        .content(JsonUtils.getJsonCodec().toJson(addInput))
                        .build();

        // 先验证位于活动组时正常工作
        toolkit.updateToolGroups(List.of("activeGroup"), true);
        toolkit.updateToolGroups(List.of("inactiveGroup"), false);
        ToolResultBlock result1 =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        assertNotNull(result1, "Should execute when group is active");

        // 再停用组并重试
        toolkit.updateToolGroups(List.of("activeGroup"), false);
        ToolResultBlock result2 =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        assertNotNull(result2, "Should return error response");
        assertTrue(
                isErrorResult(result2),
                "Should be an error when tool's group is inactive: " + getResultText(result2));
        String errorText = getResultText(result2);
        assertTrue(
                errorText.contains("Unauthorized") || errorText.contains("not available"),
                "Error message should indicate unauthorized access: " + errorText);
    }

    @Test
    @DisplayName("Should allow ungrouped tools to be called regardless of groups")
    void testUngroupedToolsAlwaysCallable() {
        // 创建组并停用
        toolkit.createToolGroup("someGroup", "Some group", false);

        // 注册不带组的工具
        toolkit.registerTool(sampleTools);

        // 尝试调用未分组工具
        Map<String, Object> addInput = Map.of("a", 1, "b", 2);
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("add")
                        .input(addInput)
                        .content(JsonUtils.getJsonCodec().toJson(addInput))
                        .build();

        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        assertNotNull(result, "Ungrouped tool should be callable");
        assertFalse(isErrorResult(result), "Ungrouped tool should execute successfully");
    }

    @Test
    @DisplayName("Should allow tool call from active group")
    void testAuthorizedToolCallSucceeds() {
        // 创建活动组
        toolkit.createToolGroup("activeGroup", "Active tools", true);

        // 将工具注册到该工具组
        toolkit.registration().tool(sampleTools).group("activeGroup").apply();

        // 尝试调用工具
        Map<String, Object> addInput = Map.of("a", 5, "b", 3);
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("add")
                        .input(addInput)
                        .content(JsonUtils.getJsonCodec().toJson(addInput))
                        .build();

        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        assertNotNull(result, "Should execute tool from active group");
        assertFalse(isErrorResult(result), "Should succeed: " + getResultText(result));
    }

    @Test
    @DisplayName("Should allow tool when any assigned group is active")
    void testToolAvailableWhenAssignedToMultipleGroups() {
        // 准备：创建两个组
        toolkit.createToolGroup("groupA", "Group A", false);
        toolkit.createToolGroup("groupB", "Group B", false);

        toolkit.registration().tool(sampleTools).group("groupA").apply();
        AgentTool registeredTool = toolkit.getTool("add");
        toolkit.addToolToGroup("groupB", "add");
        toolkit.addToolToGroup("groupB", "add");

        assertNotNull(registeredTool);
        assertSame(registeredTool, toolkit.getTool("add"));
        assertEquals(Set.of("add"), toolkit.getToolGroup("groupB").getTools());

        Map<String, Object> addInput = Map.of("a", 5, "b", 7);
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("add")
                        .input(addInput)
                        .content(JsonUtils.getJsonCodec().toJson(addInput))
                        .build();

        // 执行并断言：分别激活每个组并测试
        toolkit.updateToolGroups(List.of("groupA"), true);
        toolkit.updateToolGroups(List.of("groupB"), false);
        ToolResultBlock resultWithGroupA =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        assertNotNull(resultWithGroupA, "Should execute when groupA is active");
        assertFalse(
                isErrorResult(resultWithGroupA),
                "Should succeed when groupA is active: " + getResultText(resultWithGroupA));

        toolkit.updateToolGroups(List.of("groupA"), false);
        toolkit.updateToolGroups(List.of("groupB"), true);
        ToolResultBlock resultWithGroupB =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        assertNotNull(resultWithGroupB, "Should execute when groupB is active");
        assertFalse(
                isErrorResult(resultWithGroupB),
                "Should succeed when groupB is active: " + getResultText(resultWithGroupB));

        toolkit.updateToolGroups(List.of("groupA", "groupB"), true);
        ToolResultBlock resultWithBoth =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        assertNotNull(resultWithBoth, "Should execute when both groups are active");
        assertFalse(
                isErrorResult(resultWithBoth),
                "Should succeed when both groups are active: " + getResultText(resultWithBoth));

        toolkit.updateToolGroups(List.of("groupA", "groupB"), false);
        ToolResultBlock resultWithNone =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        assertNotNull(resultWithNone, "Should execute when no groups are active");
        assertTrue(
                isErrorResult(resultWithNone),
                "Should fail when no groups are active: " + getResultText(resultWithNone));
    }

    @Test
    @DisplayName("Should reject adding an unknown group or tool")
    void testAddToolToGroupValidation() {
        toolkit.createToolGroup("existingGroup", "Existing group", false);
        toolkit.registerAgentTool(namedAgentTool("registeredTool"));

        IllegalArgumentException missingGroup =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> toolkit.addToolToGroup("missingGroup", "registeredTool"));
        assertEquals("Tool group 'missingGroup' does not exist", missingGroup.getMessage());

        IllegalArgumentException missingTool =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> toolkit.addToolToGroup("existingGroup", "missingTool"));
        assertEquals("Tool not found: missingTool", missingTool.getMessage());
        assertTrue(toolkit.getToolGroup("existingGroup").getTools().isEmpty());
    }

    @Test
    @DisplayName("Should prevent execution after group deactivation")
    void testToolGroupDeactivationPreventsExecution() {
        // 创建活动组
        toolkit.createToolGroup("dynamicGroup", "Dynamic group", true);
        toolkit.registration().tool(sampleTools).group("dynamicGroup").apply();

        Map<String, Object> addInput = Map.of("a", 10, "b", 20);
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("add")
                        .input(addInput)
                        .content(JsonUtils.getJsonCodec().toJson(addInput))
                        .build();

        // 首次调用应成功
        ToolResultBlock result1 =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        assertNotNull(result1, "First call should work");
        assertFalse(isErrorResult(result1), "First call should succeed");

        // 停用组
        toolkit.updateToolGroups(List.of("dynamicGroup"), false);

        // 第二次调用应被拒绝
        ToolResultBlock result2 =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        assertNotNull(result2, "Should return error response");
        assertTrue(isErrorResult(result2), "Second call should fail after group deactivation");
        String errorText = getResultText(result2);
        assertTrue(
                errorText.contains("Unauthorized") || errorText.contains("not available"),
                "Error should indicate unauthorized access");
    }

    @Test
    @DisplayName("Should support preset parameters in tool registration")
    void testPresetParameters() {
        // 创建使用预设参数的测试工具
        class ContextTool {
            @Tool(description = "Test tool with context parameters")
            public ToolResultBlock testWithContext(
                    @ToolParam(name = "query", description = "User query") String query,
                    @ToolParam(name = "apiKey", description = "API key") String apiKey,
                    @ToolParam(name = "userId", description = "User ID") String userId) {
                String result =
                        String.format("Query: %s, API Key: %s, User ID: %s", query, apiKey, userId);
                return ToolResultBlock.text(result);
            }
        }

        // 注册带预设参数的工具
        Map<String, Map<String, Object>> presetParams =
                Map.of("testWithContext", Map.of("apiKey", "secret_key_123", "userId", "user_456"));
        toolkit.registration().tool(new ContextTool()).presetParameters(presetParams).apply();

        // 验证工具已注册
        Set<String> toolNames = toolkit.getToolNames();
        assertTrue(toolNames.contains("testWithContext"), "Tool should be registered");

        // 验证 JSON schema 排除预设参数
        List<ToolSchema> schemas = toolkit.getToolSchemas();
        ToolSchema toolSchema =
                schemas.stream()
                        .filter(s -> "testWithContext".equals(getToolName(s)))
                        .findFirst()
                        .orElse(null);
        assertNotNull(toolSchema, "Tool schema should exist");

        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = toolSchema.getParameters();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");

        // Schema 仅应含 "query"，不含 "apiKey" 或 "userId"
        assertTrue(properties.containsKey("query"), "Schema should contain query parameter");
        assertFalse(
                properties.containsKey("apiKey"),
                "Schema should NOT contain preset parameter apiKey");
        assertFalse(
                properties.containsKey("userId"),
                "Schema should NOT contain preset parameter userId");

        // 仅用 query 参数调用工具
        Map<String, Object> queryInput = Map.of("query", "test query");
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .name("testWithContext")
                        .input(queryInput)
                        .content(JsonUtils.getJsonCodec().toJson(queryInput))
                        .build();

        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        assertNotNull(result, "Result should not be null");
        assertFalse(isErrorResult(result), "Execution should succeed");

        // 验证预设参数已注入
        String resultText = getResultText(result);
        assertTrue(resultText.contains("Query: test query"), "Result should contain user query");
        assertTrue(
                resultText.contains("API Key: secret_key_123"),
                "Result should contain preset API key");
        assertTrue(
                resultText.contains("User ID: user_456"), "Result should contain preset user ID");
    }

    @Test
    @DisplayName("Should keep preset parameters authoritative over agent parameters")
    void testPresetParametersOverride() {
        class OverrideTool {
            @Tool(description = "Test tool for parameter override")
            public ToolResultBlock testOverride(
                    @ToolParam(name = "param1") String param1,
                    @ToolParam(name = "param2") String param2) {
                return ToolResultBlock.text(
                        String.format("param1: %s, param2: %s", param1, param2));
            }
        }

        // 带预设参数注册
        Map<String, Map<String, Object>> presetParams =
                Map.of(
                        "testOverride",
                        Map.of("param1", "preset_value1", "param2", "preset_value2"));
        toolkit.registration().tool(new OverrideTool()).presetParameters(presetParams).apply();

        // 智能体提供 param1 调用，预设仍应优先
        Map<String, Object> overrideInput = Map.of("param1", "agent_value1");
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .name("testOverride")
                        .input(overrideInput)
                        .content(JsonUtils.getJsonCodec().toJson(overrideInput))
                        .build();

        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        String resultText = getResultText(result);

        // 智能体输入不得覆盖预设值
        assertTrue(
                resultText.contains("param1: preset_value1"),
                "Preset value should override agent input");
        assertTrue(resultText.contains("param2: preset_value2"), "Preset value should be used");
    }

    @Test
    @DisplayName("Should support updating preset parameters at runtime")
    void testUpdatePresetParameters() {
        class DynamicContextTool {
            @Tool(description = "Tool with dynamic context")
            public ToolResultBlock dynamicContext(@ToolParam(name = "sessionId") String sessionId) {
                return ToolResultBlock.text("AgentStateStore ID: " + sessionId);
            }
        }

        // 使用初始预设参数注册
        Map<String, Map<String, Object>> initialParams =
                Map.of("dynamicContext", Map.of("sessionId", "session_001"));
        toolkit.registration()
                .tool(new DynamicContextTool())
                .presetParameters(initialParams)
                .apply();

        // 首次调用
        Map<String, Object> emptyInput = Map.of();
        ToolUseBlock toolCall1 =
                ToolUseBlock.builder()
                        .name("dynamicContext")
                        .input(emptyInput)
                        .content(JsonUtils.getJsonCodec().toJson(emptyInput))
                        .build();
        ToolResultBlock result1 =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall1).build()).block();
        assertTrue(getResultText(result1).contains("session_001"), "Should use initial session");

        // 更新预设参数
        Map<String, Object> updatedParams = Map.of("sessionId", "session_002");
        toolkit.updateToolPresetParameters("dynamicContext", updatedParams);

        // 第二次调用应使用更新参数
        ToolUseBlock toolCall2 =
                ToolUseBlock.builder()
                        .name("dynamicContext")
                        .input(emptyInput)
                        .content(JsonUtils.getJsonCodec().toJson(emptyInput))
                        .build();
        ToolResultBlock result2 =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall2).build()).block();
        assertTrue(getResultText(result2).contains("session_002"), "Should use updated session");
    }

    @Test
    @DisplayName("Should throw exception at apply() when multiple types tools set")
    void testSetMultipleTypesTool() {
        // 模拟类与动作
        McpClientWrapper mcpClientWrapper = mock(McpClientWrapper.class);
        AgentTool agentTool = mock(AgentTool.class);
        TestToolObject testToolObject = new TestToolObject();

        // 不抛异常
        Toolkit.ToolRegistration toolRegistration = toolkit.registration();
        toolRegistration
                .mcpClient(mcpClientWrapper)
                .agentTool(agentTool)
                .tool(testToolObject)
                .subAgent(() -> mock(Agent.class));
        toolkit.registration()
                .agentTool(agentTool)
                .mcpClient(mcpClientWrapper)
                .tool(testToolObject)
                .subAgent(() -> mock(Agent.class));
        toolkit.registration()
                .tool(testToolObject)
                .agentTool(agentTool)
                .mcpClient(mcpClientWrapper)
                .subAgent(() -> mock(Agent.class));
        toolkit.registration()
                .subAgent(() -> mock(Agent.class))
                .mcpClient(mcpClientWrapper)
                .agentTool(agentTool)
                .tool(testToolObject);

        // 执行动作
        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> toolRegistration.apply());
        assertTrue(exception.getMessage().contains("Cannot set multiple registration types"));
    }

    @Test
    @DisplayName("Should throw exception at apply() when none tool set")
    void testSetNoneTool() {
        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> toolkit.registration().apply());
        assertTrue(exception.getMessage().contains("Must call one of"));
    }

    @Test
    @DisplayName("Should not treat the incoming null value as a valid setting")
    void testSetNullTool() {
        AgentTool agentTool = mock(AgentTool.class);
        when(agentTool.getName()).thenReturn("mock_tool");
        // 执行动作
        toolkit.registration().tool(null).agentTool(agentTool).apply();
    }

    @Test
    @DisplayName("Should handle setting value then resetting to null correctly")
    void testSetValueThenResetToNull() {
        // 创建模拟对象
        AgentTool agentTool = mock(AgentTool.class);
        when(agentTool.getName()).thenReturn("mock_tool");
        McpClientWrapper mcpClientWrapper = mock(McpClientWrapper.class);
        TestToolObject testToolObject = new TestToolObject();

        // 测试 1：设置工具对象后重置为 null，应抛异常
        Toolkit.ToolRegistration registration1 = toolkit.registration();
        registration1.tool(testToolObject).tool(null);
        IllegalStateException exception1 =
                assertThrows(IllegalStateException.class, () -> registration1.apply());
        assertTrue(
                exception1.getMessage().contains("Must call one of"),
                "Should throw exception when all values are null");

        // 测试 2：设置 agentTool 后重置为 null，应抛异常
        Toolkit.ToolRegistration registration2 = toolkit.registration();
        registration2.agentTool(agentTool).agentTool(null);
        IllegalStateException exception2 =
                assertThrows(IllegalStateException.class, () -> registration2.apply());
        assertTrue(
                exception2.getMessage().contains("Must call one of"),
                "Should throw exception when all values are null");

        // 测试 3：设置 mcpClient 后重置为 null，应抛异常
        Toolkit.ToolRegistration registration3 = toolkit.registration();
        registration3.mcpClient(mcpClientWrapper).mcpClient(null);
        IllegalStateException exception3 =
                assertThrows(IllegalStateException.class, () -> registration3.apply());
        assertTrue(
                exception3.getMessage().contains("Must call one of"),
                "Should throw exception when all values are null");

        // 测试 4：设置 subAgent 后重置为 null，应抛异常
        Toolkit.ToolRegistration registration4 = toolkit.registration();
        registration4.subAgent(() -> mock(Agent.class)).subAgent(null);
        IllegalStateException exception4 =
                assertThrows(IllegalStateException.class, () -> registration4.apply());
        assertTrue(
                exception4.getMessage().contains("Must call one of"),
                "Should throw exception when all values are null");

        // 测试 5：设置多个值后将其中一个重置为 null，最后非 null 值应生效
        Toolkit.ToolRegistration registration5 = toolkit.registration();
        registration5.tool(testToolObject).tool(null).agentTool(agentTool);
        assertDoesNotThrow(
                () -> registration5.apply(),
                "Should succeed when one valid tool type remains after reset");

        // 测试 6：设置多个值后仅保留一个非 null，应成功
        AgentTool agentTool2 = mock(AgentTool.class);
        when(agentTool2.getName()).thenReturn("mock_tool_2");
        Toolkit.ToolRegistration registration6 = toolkit.registration();
        registration6
                .tool(testToolObject)
                .agentTool(agentTool)
                .mcpClient(mcpClientWrapper)
                .tool(null)
                .mcpClient(null)
                .agentTool(agentTool2);
        assertDoesNotThrow(
                () -> registration6.apply(),
                "Should succeed when only one tool type is non-null after multiple resets");
    }

    /**
     * 从 schema 提取工具名的辅助方法。
     */
    private String getToolName(ToolSchema schema) {
        return schema.getName();
    }

    /**
     * 含 @Tool 注解方法的测试工具类，用于测试工具对象注册。
     */
    private static class TestToolObject {

        @Tool(name = "test_tool_method", description = "A test tool method")
        public Mono<ToolResultBlock> testToolMethod(
                @ToolParam(name = "input", description = "Test input") String input) {
            return Mono.just(
                    ToolResultBlock.of(TextBlock.builder().text("Result: " + input).build()));
        }
    }

    // ==================== 嵌套对象 $ref 测试，Issue #893 ====================

    /**
     * 在 List 中使用时，victools 会为其生成 $defs/$ref 的 POJO。
     */
    public static class Material {
        private String key;
        private String value;

        public Material() {}

        public Material(String key, String value) {
            this.key = key;
            this.value = value;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    /**
     * 包含 Material 列表的嵌套请求对象，触发 victools 在 schema 中生成 $defs 和 $ref。
     */
    public static class CreateRequest {
        private java.util.List<Material> baseMaterialList;
        private java.util.List<Material> searchMaterialList;

        public java.util.List<Material> getBaseMaterialList() {
            return baseMaterialList;
        }

        public void setBaseMaterialList(java.util.List<Material> baseMaterialList) {
            this.baseMaterialList = baseMaterialList;
        }

        public java.util.List<Material> getSearchMaterialList() {
            return searchMaterialList;
        }

        public void setSearchMaterialList(java.util.List<Material> searchMaterialList) {
            this.searchMaterialList = searchMaterialList;
        }
    }

    /**
     * 使用嵌套对象参数的工具类，复现 Issue #893 场景。
     */
    public static class NestedObjectTool {

        @Tool(name = "createTheme", description = "Create a theme with material lists")
        public String create(
                @ToolParam(name = "request", description = "The creation request")
                        CreateRequest request) {
            return "Created with "
                    + (request.getSearchMaterialList() != null
                            ? request.getSearchMaterialList().size()
                            : 0)
                    + " search materials";
        }
    }

    @Test
    @DisplayName("Should handle nested object with $ref in schema (Issue #893)")
    void testNestedObjectWithRefIssue893() {
        // 注册带嵌套 POJO 参数的工具
        NestedObjectTool nestedTool = new NestedObjectTool();
        toolkit.registerTool(nestedTool);

        // 验证工具已注册
        AgentTool tool = toolkit.getTool("createTheme");
        assertNotNull(tool, "Tool should be registered");

        // 打印生成的 schema 供调试
        Map<String, Object> parameters = tool.getParameters();
        String schemaJson = JsonUtils.getJsonCodec().toJson(parameters);

        // 验证 schema 包含 $defs，victools 应为 Material 生成它
        assertTrue(
                schemaJson.contains("$defs") || schemaJson.contains("$ref"),
                "Schema should contain $defs/$ref for nested Material type");

        // 模拟智能体用嵌套对象输入调用工具，对应 Issue #893
        Map<String, Object> input =
                Map.of(
                        "request",
                        Map.of(
                                "searchMaterialList",
                                List.of(
                                        Map.of("key", "aaa", "value", "123"),
                                        Map.of("key", "bbb", "value", "456"))));

        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("toolu_test_893")
                        .name("createTheme")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        // 原缺陷：callTool 触发 ToolValidator.validateInput 后失败，
        // 报 "Schema validation error: Reference /$defs/Material cannot be resolved"
        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();

        assertNotNull(result, "Result should not be null");
        assertFalse(
                isErrorResult(result),
                "Tool call with nested objects should succeed, but got error: "
                        + getResultText(result));

        String resultText = getResultText(result);
        assertTrue(
                resultText.contains("2 search materials"),
                "Should have processed 2 search materials. Got: " + resultText);
    }

    // ==================== 转换器测试 ====================

    /**
     * 用于测试的带无参构造器的自定义转换器。
     */
    public static class CustomNoArgConverter implements ToolResultConverter {
        @Override
        public ToolResultBlock convert(Object result, Type returnType) {
            return ToolResultBlock.text("[CustomNoArg] " + result);
        }
    }

    /**
     * 用于测试的无无参构造器的无效转换器。
     */
    public static class InvalidConverterNoConstructor implements ToolResultConverter {
        private final String config;

        public InvalidConverterNoConstructor(String config) {
            this.config = config;
        }

        @Override
        public ToolResultBlock convert(Object result, Type returnType) {
            return ToolResultBlock.text(result + " with config: " + config);
        }
    }

    /**
     * 使用自定义转换器的测试工具类。
     */
    private static class ToolWithCustomConverter {
        @Tool(
                name = "tool_with_custom_converter",
                description = "Tool with custom converter",
                converter = CustomNoArgConverter.class)
        public String execute(@ToolParam(name = "input") String input) {
            return "Processed: " + input;
        }
    }

    /**
     * 使用无效转换器的测试工具类，转换器没有无参构造器。
     */
    private static class ToolWithInvalidConverter {
        @Tool(
                name = "tool_with_invalid_converter",
                description = "Tool with invalid converter",
                converter = InvalidConverterNoConstructor.class)
        public String execute(@ToolParam(name = "input") String input) {
            return "Should not reach here";
        }
    }

    /**
     * 使用默认转换器的测试工具类。
     */
    private static class ToolWithDefaultConverter {
        @Tool(name = "tool_with_default_converter", description = "Tool with default converter")
        public String execute(@ToolParam(name = "input") String input) {
            return "Default: " + input;
        }
    }

    @Test
    @DisplayName("Should use custom converter when specified in @Tool annotation")
    void testParseConverterFromAnnotation_CustomConverter() {
        // 注册使用自定义转换器的工具
        ToolWithCustomConverter toolObj = new ToolWithCustomConverter();
        toolkit.registerTool(toolObj);

        // 执行工具
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .name("tool_with_custom_converter")
                        .input(Map.of("input", "test"))
                        .content(JsonUtils.getJsonCodec().toJson(Map.of("input", "test")))
                        .build();

        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();

        // 验证使用了自定义转换器
        assertNotNull(result, "Result should not be null");
        String resultText = getResultText(result);
        assertTrue(
                resultText.contains("[CustomNoArg]"),
                "Result should contain custom converter prefix. Got: " + resultText);
        assertTrue(
                resultText.contains("Processed: test"),
                "Result should contain processed input. Got: " + resultText);
    }

    @Test
    @DisplayName("Should use default converter when no converter specified")
    void testParseConverterFromAnnotation_DefaultConverter() {
        // 注册不带自定义转换器的工具
        ToolWithDefaultConverter toolObj = new ToolWithDefaultConverter();
        toolkit.registerTool(toolObj);

        // 执行工具
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .name("tool_with_default_converter")
                        .input(Map.of("input", "test"))
                        .content(JsonUtils.getJsonCodec().toJson(Map.of("input", "test")))
                        .build();

        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();

        // 验证使用默认转换器，没有自定义前缀
        assertNotNull(result, "Result should not be null");
        String resultText = getResultText(result);
        assertFalse(
                resultText.contains("[CustomNoArg]"),
                "Result should NOT contain custom converter prefix");
        assertTrue(resultText.contains("Default: test"), "Result should contain default output");
    }

    @Test
    @DisplayName("Should throw exception when converter has no no-arg constructor")
    void testInstantiateConverter_NoNoArgConstructor() {
        // 尝试注册带无效转换器的工具
        ToolWithInvalidConverter toolObj = new ToolWithInvalidConverter();

        // 尝试注册时应抛 IllegalStateException
        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> toolkit.registerTool(toolObj),
                        "Should throw exception for converter without no-arg constructor");

        // 验证异常消息
        assertTrue(
                exception.getMessage().contains("Failed to create converter from @Tool annotation"),
                "Exception should indicate converter creation failure. Got: "
                        + exception.getMessage());

        Throwable cause = exception.getCause();
        assertNotNull(cause, "Exception should have a cause");
        assertTrue(
                cause instanceof IllegalStateException,
                "Cause should be IllegalStateException. Got: " + cause.getClass().getName());
        assertTrue(
                cause.getMessage().contains("must have either a no-arg constructor"),
                "Cause message should mention no-arg constructor requirement. Got: "
                        + cause.getMessage());
    }

    @Test
    @DisplayName("Should successfully instantiate converter with no-arg constructor")
    void testInstantiateConverter_WithNoArgConstructor() {
        // 注册带有效自定义转换器的工具
        ToolWithCustomConverter toolObj = new ToolWithCustomConverter();

        // 不应抛任何异常
        assertDoesNotThrow(
                () -> toolkit.registerTool(toolObj),
                "Should successfully register tool with valid converter");

        // 验证工具已注册
        AgentTool tool = toolkit.getTool("tool_with_custom_converter");
        assertNotNull(tool, "Tool should be registered successfully");

        // 验证工具可执行
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .name("tool_with_custom_converter")
                        .input(Map.of("input", "validation"))
                        .content(JsonUtils.getJsonCodec().toJson(Map.of("input", "validation")))
                        .build();

        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();
        assertNotNull(result, "Tool should execute successfully");
        assertFalse(isErrorResult(result), "Tool execution should not have errors");
    }

    @Test
    @DisplayName("Should handle null annotation in parseConverterFromAnnotation")
    void testParseConverterFromAnnotation_NullAnnotation() {
        // 通过使用默认转换器间接测试此行为
        // 未指定自定义转换器时，注解的 converter() 返回
        // DefaultToolResultConverter.class，应正常工作

        ToolWithDefaultConverter toolObj = new ToolWithDefaultConverter();
        assertDoesNotThrow(
                () -> toolkit.registerTool(toolObj),
                "Should handle tools without custom converter");

        AgentTool tool = toolkit.getTool("tool_with_default_converter");
        assertNotNull(tool, "Tool should be registered");
    }

    @Test
    @DisplayName("Should expose MCP output schema through getToolSchemas")
    void testGetToolSchemasIncludesMcpOutputSchema() {
        McpClientWrapper mcpClientWrapper = mock(McpClientWrapper.class);
        when(mcpClientWrapper.getName()).thenReturn("mcp-client");
        when(mcpClientWrapper.initialize()).thenReturn(Mono.empty());

        McpSchema.Tool mcpTool = mock(McpSchema.Tool.class);
        when(mcpTool.name()).thenReturn("structured_mcp_tool");
        when(mcpTool.description()).thenReturn("Returns structured MCP output");
        when(mcpTool.inputSchema()).thenReturn(Map.of("type", "object"));
        when(mcpTool.outputSchema())
                .thenReturn(
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("answer", Map.of("type", "string"))));
        when(mcpClientWrapper.listTools()).thenReturn(Mono.just(List.of(mcpTool)));

        toolkit.registerMcpClient(mcpClientWrapper).block();

        ToolSchema schema =
                toolkit.getToolSchemas().stream()
                        .filter(s -> "structured_mcp_tool".equals(getToolName(s)))
                        .findFirst()
                        .orElse(null);

        assertNotNull(schema);
        assertNotNull(schema.getOutputSchema());
        assertEquals("object", schema.getOutputSchema().get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) schema.getOutputSchema().get("properties");
        assertTrue(properties.containsKey("answer"));
    }

    @Test
    @DisplayName("ToolRegistration.propagateMeta should override the wrapper default")
    void testRegistrationPropagateMetaOverridesWrapper() {
        McpClientWrapper mcpClientWrapper =
                McpClientWrapperTestSupport.mockWrapper("external-mcp-client", true);
        when(mcpClientWrapper.initialize()).thenReturn(Mono.empty());
        // 包装器显式允许传播，注册级设置必须优先

        McpSchema.Tool mcpTool = mock(McpSchema.Tool.class);
        when(mcpTool.name()).thenReturn("external_tool");
        when(mcpTool.description()).thenReturn("Tool from an untrusted MCP server");
        when(mcpTool.inputSchema()).thenReturn(Map.of("type", "object"));
        when(mcpClientWrapper.listTools()).thenReturn(Mono.just(List.of(mcpTool)));

        toolkit.registration().mcpClient(mcpClientWrapper).propagateMeta(false).apply();

        AgentTool tool = toolkit.getTool("external_tool");
        assertNotNull(tool, "Tool should be registered");
        assertTrue(tool instanceof McpTool);
        assertFalse(((McpTool) tool).isPropagateMeta());
    }

    @Test
    @DisplayName("Per-tool propagateMeta should win over the registration default")
    void testRegistrationPerToolPropagateMeta() {
        McpClientWrapper mcpClientWrapper = mock(McpClientWrapper.class);
        when(mcpClientWrapper.getName()).thenReturn("mixed-mcp-client");
        when(mcpClientWrapper.initialize()).thenReturn(Mono.empty());

        McpSchema.Tool trustedTool = mock(McpSchema.Tool.class);
        when(trustedTool.name()).thenReturn("trusted_tool");
        when(trustedTool.description()).thenReturn("Tool that still needs its callback URL");
        when(trustedTool.inputSchema()).thenReturn(Map.of("type", "object"));

        McpSchema.Tool noisyTool = mock(McpSchema.Tool.class);
        when(noisyTool.name()).thenReturn("noisy_tool");
        when(noisyTool.description()).thenReturn("Tool that must not leak metadata");
        when(noisyTool.inputSchema()).thenReturn(Map.of("type", "object"));

        when(mcpClientWrapper.listTools()).thenReturn(Mono.just(List.of(trustedTool, noisyTool)));

        toolkit.registration()
                .mcpClient(mcpClientWrapper)
                .propagateMeta(false)
                .propagateMeta("trusted_tool", true)
                .apply();

        AgentTool trusted = toolkit.getTool("trusted_tool");
        AgentTool noisy = toolkit.getTool("noisy_tool");
        assertNotNull(trusted);
        assertNotNull(noisy);
        assertTrue(trusted instanceof McpTool);
        assertTrue(noisy instanceof McpTool);
        assertTrue(((McpTool) trusted).isPropagateMeta());
        assertFalse(((McpTool) noisy).isPropagateMeta());
    }

    @Test
    @DisplayName("Per-tool propagateMeta should reject a blank tool name")
    void testRegistrationPerToolPropagateMetaRejectsBlankName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> toolkit.registration().propagateMeta("  ", false));
    }

    @Test
    @DisplayName("Per-tool propagateMeta should reject a null tool name")
    void testRegistrationPerToolPropagateMetaRejectsNullName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> toolkit.registration().propagateMeta(null, false));
    }

    @Test
    @DisplayName("callTool should stamp id and name on success results")
    void testCallToolStampsIdAndNameOnSuccess() {
        toolkit.registerTool(sampleTools);

        Map<String, Object> input = Map.of("a", 2, "b", 3);
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-single-success")
                        .name("add")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();

        assertNotNull(result);
        assertEquals("call-single-success", result.getId());
        assertEquals("add", result.getName());
    }

    @Test
    @DisplayName("callTool should stamp id and name on error results")
    void testCallToolStampsIdAndNameOnError() {
        toolkit.registerTool(sampleTools);

        Map<String, Object> input = Map.of("message", "boom");
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-single-error")
                        .name("error_tool")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();

        assertNotNull(result);
        assertEquals("call-single-error", result.getId());
        assertEquals("error_tool", result.getName());
    }

    @Test
    @DisplayName("callTool should prefer ToolCallParam.input over ToolUseBlock.input")
    void testCallToolPrefersParamInputOverToolUseBlockInput() {
        toolkit.registerTool(sampleTools);

        Map<String, Object> toolUseInput = Map.of("a", 2, "b", 3);
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-single-param-input")
                        .name("add")
                        .input(toolUseInput)
                        .content(JsonUtils.getJsonCodec().toJson(toolUseInput))
                        .build();

        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(toolCall)
                        .input(Map.of("a", 100, "b", 200))
                        .build();

        ToolResultBlock result = toolkit.callTool(param).block();

        assertNotNull(result);
        assertEquals("call-single-param-input", result.getId());
        assertEquals("add", result.getName());
        assertEquals("300", ToolTestUtils.extractContent(result));
    }

    @Test
    @DisplayName("callTool with a null per-call config should match the single-arg overload")
    void testCallToolWithNullConfigMatchesSingleArgOverload() {
        toolkit.registerTool(sampleTools);

        Map<String, Object> input = Map.of("a", 3, "b", 4);
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-single-null-config")
                        .name("add")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();
        ToolCallParam param = ToolCallParam.builder().toolUseBlock(toolCall).input(input).build();

        ToolResultBlock result = toolkit.callTool(param, null).block();

        assertNotNull(result);
        assertEquals("call-single-null-config", result.getId());
        assertEquals("add", result.getName());
        assertEquals("7", ToolTestUtils.extractContent(result));
    }

    @Test
    @DisplayName("callTool with a per-call config should return a stamped result")
    void testCallToolWithPerCallConfigReturnsStampedResult() {
        toolkit.registerTool(sampleTools);

        Map<String, Object> input = Map.of("a", 10, "b", 20);
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-percall-config")
                        .name("add")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();
        ToolCallParam param = ToolCallParam.builder().toolUseBlock(toolCall).input(input).build();

        ExecutionConfig perCallConfig =
                ExecutionConfig.builder().timeout(Duration.ofMinutes(10)).build();

        ToolResultBlock result = toolkit.callTool(param, perCallConfig).block();

        assertNotNull(result);
        assertEquals("call-percall-config", result.getId());
        assertEquals("add", result.getName());
        assertEquals("30", ToolTestUtils.extractContent(result));
    }

    @Test
    @DisplayName("callTool per-call config should override a short toolkit-level timeout")
    void testCallToolPerCallConfigOverridesToolkitTimeout() {
        ExecutionConfig toolkitTimeout =
                ExecutionConfig.builder().timeout(Duration.ofMillis(100)).build();
        Toolkit shortTimeoutToolkit =
                new Toolkit(ToolkitConfig.builder().executionConfig(toolkitTimeout).build());
        shortTimeoutToolkit.registerTool(new SlowTool());

        Map<String, Object> input = Map.of("delayMs", 300);
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-percall-timeout")
                        .name("slow")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();
        ToolCallParam param = ToolCallParam.builder().toolUseBlock(toolCall).input(input).build();

        ExecutionConfig perCallConfig =
                ExecutionConfig.builder().timeout(Duration.ofMinutes(10)).build();

        ToolResultBlock result = shortTimeoutToolkit.callTool(param, perCallConfig).block();

        assertNotNull(result);
        assertEquals("call-percall-timeout", result.getId());
        assertEquals("slow", result.getName());
        assertEquals("\"done\"", ToolTestUtils.extractContent(result));
    }

    @Test
    @DisplayName("callTool should stamp id and name when the tool is not found")
    void testCallToolStampsIdAndNameWhenToolNotFound() {
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-single-missing")
                        .name("missing_tool")
                        .input(Map.of())
                        .content("{}")
                        .build();

        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();

        assertNotNull(result);
        assertEquals("call-single-missing", result.getId());
        assertEquals("missing_tool", result.getName());
    }

    @Test
    @DisplayName("callTool should stamp id and name when parameter validation fails")
    void testCallToolStampsIdAndNameWhenValidationFails() {
        toolkit.registerTool(sampleTools);

        Map<String, Object> input = Map.of("a", "not-a-number", "b", 2);
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-single-invalid")
                        .name("add")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block();

        assertNotNull(result);
        assertEquals("call-single-invalid", result.getId());
        assertEquals("add", result.getName());
    }

    /**
     * 用于验证逐调用超时可覆盖工具集超时的工具。
     */
    public static class SlowTool {
        @Tool(name = "slow", description = "Delay then return done")
        public Mono<String> slow(
                @ToolParam(name = "delayMs", description = "Delay in milliseconds") long delayMs) {
            return Mono.delay(Duration.ofMillis(delayMs)).thenReturn("done");
        }
    }
}
