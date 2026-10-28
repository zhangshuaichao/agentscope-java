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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ToolContextState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class MetaToolFactoryTest {

    private ToolRegistry registry;
    private ToolGroupManager groupManager;
    private MetaToolFactory metaToolFactory;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        groupManager = new ToolGroupManager();
        metaToolFactory = new MetaToolFactory(groupManager, registry);
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
                Map<String, Object> schema = new HashMap<>();
                schema.put("type", "object");
                return schema;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(ToolResultBlock.text("result"));
            }
        };
    }

    /**
     * 使用逐会话运行上下文与工具上下文状态调用元工具。
     */
    private ToolResultBlock callTool(AgentTool tool, Map<String, Object> input) {
        return callTool(tool, input, ToolContextState.builder().build());
    }

    /**
     * 针对给定逐会话工具上下文状态调用元工具。
     */
    private ToolResultBlock callTool(
            AgentTool tool, Map<String, Object> input, ToolContextState tcs) {
        return callTool(tool, input, tcs != null ? runtimeContext(tcs) : null);
    }

    private ToolResultBlock callTool(
            AgentTool tool, Map<String, Object> input, RuntimeContext runtimeContext) {
        ToolUseBlock toolUseBlock = new ToolUseBlock("test-id", tool.getName(), input);
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(toolUseBlock)
                        .input(input)
                        .runtimeContext(runtimeContext)
                        .build();
        return tool.callAsync(param).block();
    }

    private static RuntimeContext runtimeContext(ToolContextState tcs) {
        return RuntimeContext.builder()
                .agentState(AgentState.builder().toolContext(tcs).build())
                .build();
    }

    private static String text(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }

    @Test
    void testCreateResetEquippedToolsAgentTool() {
        // 执行
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        // 断言
        assertNotNull(metaTool);
        assertEquals("reset_equipped_tools", metaTool.getName());
        assertNotNull(metaTool.getDescription());
        assertNotNull(metaTool.getParameters());
    }

    @Test
    void testMetaToolNameAndDescription() {
        // 执行
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        // 断言
        assertEquals("reset_equipped_tools", metaTool.getName());
        assertTrue(
                metaTool.getDescription()
                        .contains("Reset your equipped tools based on your current task"));
        assertTrue(
                metaTool.getDescription()
                        .contains("The input list is the FINAL set of active tool groups"));
    }

    @Test
    void testMetaToolDescriptionWithNoGroups() {
        // 执行
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        String description = metaTool.getDescription();

        // 断言
        assertTrue(description.contains("No tool groups"));
    }

    @Test
    void testMetaToolDescriptionWithGroups() {
        // 准备
        groupManager.createToolGroup("analytics", "Analytics tools", true);
        groupManager.createToolGroup("search", "Search tools", true);

        // 执行
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        String description = metaTool.getDescription();

        // 断言
        assertTrue(description.contains("Activated tool groups"));
        assertTrue(description.contains("analytics"));
        assertTrue(description.contains("search"));
    }

    @Test
    void testMetaToolParametersStructure() {
        // 执行
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        Map<String, Object> parameters = metaTool.getParameters();

        // 断言
        assertEquals("object", parameters.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        assertTrue(properties.containsKey("to_activate"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) parameters.get("required");
        assertTrue(required.contains("to_activate"));
    }

    @Test
    void testMetaToolParametersWithNoGroups() {
        // 执行
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        Map<String, Object> parameters = metaTool.getParameters();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> toActivate = (Map<String, Object>) properties.get("to_activate");
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) toActivate.get("items");

        // 断言
        assertFalse(items.containsKey("enum"), "Should not have enum when no groups exist");
    }

    @Test
    void testMetaToolParametersWithGroups() {
        // 准备
        groupManager.createToolGroup("analytics", "Analytics tools", true);
        groupManager.createToolGroup("search", "Search tools", true);

        // 执行
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        Map<String, Object> parameters = metaTool.getParameters();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> toActivate = (Map<String, Object>) properties.get("to_activate");
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) toActivate.get("items");
        @SuppressWarnings("unchecked")
        List<String> enumValues = (List<String>) items.get("enum");

        // 断言
        assertNotNull(enumValues);
        assertEquals(2, enumValues.size());
        assertTrue(enumValues.contains("analytics"));
        assertTrue(enumValues.contains("search"));
    }

    @Test
    void testMetaToolCallAsyncActivateGroups() {
        // 准备
        groupManager.createToolGroup("analytics", "Analytics tools", false);
        groupManager.createToolGroup("search", "Search tools", false);

        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        ToolContextState tcs = ToolContextState.builder().build();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("analytics", "search"));

        // 执行
        ToolResultBlock result = callTool(metaTool, input, tcs);

        // 断言
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String resultText = text(result);
        assertTrue(resultText.contains("The currently activated tool group(s):"));
        assertTrue(resultText.contains("analytics"));
        assertTrue(resultText.contains("search"));
        assertEquals(List.of("analytics", "search"), tcs.getActivatedGroups());
    }

    @Test
    void testMetaToolCallAsyncWithToolsInGroup() {
        // 准备
        groupManager.createToolGroup("search", "Search tools", false);

        AgentTool searchTool = createMockTool("search_tool", "Search function");
        RegisteredToolFunction searchRegistered =
                new RegisteredToolFunction(searchTool, null, null);
        registry.registerTool("search_tool", searchTool, searchRegistered);
        groupManager.addToolToGroup("search", "search_tool");

        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("search"));

        // 执行
        ToolResultBlock result = callTool(metaTool, input);

        // 断言
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String resultText = text(result);
        assertTrue(resultText.contains("The currently activated tool group(s): search"));
        assertTrue(resultText.contains("Search tools"));
    }

    @Test
    void testMetaToolCallAsyncMissingParameter() {
        // 准备
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        Map<String, Object> input = new HashMap<>();

        // 执行
        ToolResultBlock result = callTool(metaTool, input);

        // 断言
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String resultText = text(result);
        assertTrue(resultText.contains("Error:"));
        assertTrue(resultText.contains("Missing required parameter: to_activate"));
    }

    @Test
    void testMetaToolCallAsyncInvalidGroupName() {
        // 准备
        groupManager.createToolGroup("analytics", "Analytics tools", false);
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("nonexistent"));

        // 执行
        ToolResultBlock result = callTool(metaTool, input);

        // 断言
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String resultText = text(result);
        assertTrue(resultText.contains("Error:"));
        assertTrue(resultText.contains("does not exist"));
    }

    @Test
    void testMetaToolCallAsyncPartialInvalidGroups() {
        // 准备
        groupManager.createToolGroup("analytics", "Analytics tools", false);
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("analytics", "nonexistent"));

        // 执行
        ToolResultBlock result = callTool(metaTool, input);

        // 断言
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String resultText = text(result);
        assertTrue(resultText.contains("Error:"));
        assertTrue(resultText.contains("does not exist"));
    }

    @Test
    void testMetaToolCallAsyncEmptyListDeactivatesAll() {
        // 准备
        groupManager.createToolGroup("analytics", "Analytics tools", true);
        groupManager.createToolGroup("search", "Search tools", true);
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        ToolContextState tcs =
                ToolContextState.builder()
                        .addActivatedGroup("analytics")
                        .addActivatedGroup("search")
                        .build();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of());

        // 执行
        ToolResultBlock result = callTool(metaTool, input, tcs);

        // 断言：空列表停用逐会话状态中的所有 META 组
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String resultText = text(result);
        assertTrue(resultText.contains("All META tool groups are currently deactivated"));
        assertEquals(List.of(), tcs.getActivatedGroups());
    }

    @Test
    void testMetaToolReplacesActiveGroups() {
        // 准备
        groupManager.createToolGroup("group1", "Group 1", true);
        groupManager.createToolGroup("group2", "Group 2", true);
        groupManager.createToolGroup("group3", "Group 3", false);

        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        ToolContextState tcs =
                ToolContextState.builder()
                        .addActivatedGroup("group1")
                        .addActivatedGroup("group2")
                        .build();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("group3"));

        // 执行
        callTool(metaTool, input, tcs);

        // 断言：替换语义，group1 和 group2 被 group3 替换
        assertEquals(List.of("group3"), tcs.getActivatedGroups());
    }

    @Test
    void testMetaToolCallAsyncReactivateAlreadyActive() {
        // 准备
        groupManager.createToolGroup("analytics", "Analytics tools", true);
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("analytics"));

        ToolContextState tcs = ToolContextState.builder().addActivatedGroup("analytics").build();

        // 执行
        ToolResultBlock result = callTool(metaTool, input, tcs);

        // 断言
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String resultText = text(result);
        assertTrue(resultText.contains("The currently activated tool group(s):"));
        assertEquals(List.of("analytics"), tcs.getActivatedGroups());
    }

    @Test
    void testMetaToolParametersDescriptionField() {
        // 执行
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        Map<String, Object> parameters = metaTool.getParameters();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> toActivate = (Map<String, Object>) properties.get("to_activate");

        // 断言
        String description = (String) toActivate.get("description");
        assertTrue(description.contains("FINAL list"));
        assertTrue(description.contains("deactivated"));
    }

    @Test
    void testMetaToolParametersArrayType() {
        // 执行
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        Map<String, Object> parameters = metaTool.getParameters();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> toActivate = (Map<String, Object>) properties.get("to_activate");

        // 断言
        assertEquals("array", toActivate.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) toActivate.get("items");
        assertEquals("string", items.get("type"));
    }

    @Test
    void testMetaToolIgnoresExternalGroups() {
        // 准备：创建 META 与 EXTERNAL 组
        groupManager.createToolGroup("meta_group", "META group", true, ToolGroupScope.META);
        groupManager.createToolGroup(
                "external_group", "EXTERNAL group", true, ToolGroupScope.EXTERNAL);

        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        ToolContextState tcs =
                ToolContextState.builder().addActivatedGroup("external_group").build();

        // 执行：仅激活 meta_group，采用替换语义
        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("meta_group"));
        callTool(metaTool, input, tcs);

        // 断言：保留 EXTERNAL 组，激活 META 组
        assertEquals(List.of("external_group", "meta_group"), tcs.getActivatedGroups());
    }

    @Test
    void testMetaToolRejectsExternalGroupInInput() {
        // 准备
        groupManager.createToolGroup(
                "external_group", "EXTERNAL group", false, ToolGroupScope.EXTERNAL);

        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("external_group"));

        // 执行
        ToolResultBlock result = callTool(metaTool, input);

        // 断言
        assertEquals(ToolResultState.ERROR, result.getState());
        String resultText = text(result);
        assertTrue(resultText.contains("Error:"));
        assertTrue(resultText.contains("not manageable"));
    }

    @Test
    void testMetaToolParametersOnlyListsMetaGroups() {
        // 准备
        groupManager.createToolGroup("meta_group", "META group", false, ToolGroupScope.META);
        groupManager.createToolGroup(
                "external_group", "EXTERNAL group", true, ToolGroupScope.EXTERNAL);

        // 执行
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        Map<String, Object> parameters = metaTool.getParameters();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> toActivate = (Map<String, Object>) properties.get("to_activate");
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) toActivate.get("items");
        @SuppressWarnings("unchecked")
        List<String> enumValues = (List<String>) items.get("enum");

        // 断言：枚举仅包含 META 组
        assertNotNull(enumValues);
        assertEquals(1, enumValues.size());
        assertTrue(enumValues.contains("meta_group"));
        assertFalse(enumValues.contains("external_group"));
    }

    @Test
    void testMetaToolReplacementPreservesExternalDuringEmptyActivation() {
        // 准备：逐会话状态同时包含 META 和 EXTERNAL 组
        groupManager.createToolGroup("meta1", "META 1", true, ToolGroupScope.META);
        groupManager.createToolGroup("meta2", "META 2", true, ToolGroupScope.META);
        groupManager.createToolGroup("ext1", "EXTERNAL 1", true, ToolGroupScope.EXTERNAL);

        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        ToolContextState tcs =
                ToolContextState.builder()
                        .addActivatedGroup("meta1")
                        .addActivatedGroup("meta2")
                        .addActivatedGroup("ext1")
                        .build();

        // 执行：空列表停用全部
        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of());
        callTool(metaTool, input, tcs);

        // 断言：停用 META 组，保留 EXTERNAL
        assertEquals(List.of("ext1"), tcs.getActivatedGroups());
    }

    @Test
    void testMetaToolWithMultipleToolsInMultipleGroups() {
        // 准备
        groupManager.createToolGroup("group1", "Group 1", false);
        groupManager.createToolGroup("group2", "Group 2", false);

        AgentTool tool1 = createMockTool("tool1", "Tool 1");
        AgentTool tool2 = createMockTool("tool2", "Tool 2");
        AgentTool tool3 = createMockTool("tool3", "Tool 3");

        registry.registerTool("tool1", tool1, new RegisteredToolFunction(tool1, null, null));
        registry.registerTool("tool2", tool2, new RegisteredToolFunction(tool2, null, null));
        registry.registerTool("tool3", tool3, new RegisteredToolFunction(tool3, null, null));

        groupManager.addToolToGroup("group1", "tool1");
        groupManager.addToolToGroup("group1", "tool2");
        groupManager.addToolToGroup("group2", "tool3");

        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("group1", "group2"));

        // 执行

        ToolResultBlock result = callTool(metaTool, input);

        // 断言
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String resultText = text(result);
        assertTrue(resultText.contains("The currently activated tool group(s):"));
        assertTrue(resultText.contains("Group 1"));
        assertTrue(resultText.contains("Group 2"));
    }

    @Test
    void testMetaToolSkillToolGroupDescriptionInNotes() {
        // 准备
        groupManager.createSkillToolGroup("code_tools", "Code execution tools", false, "coding");

        // 执行
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        String description = metaTool.getDescription();

        // 断言：notes 应包含技能激活提醒
        assertTrue(description.contains("code_tools"));
        assertTrue(description.contains("MUST be activated"));
        assertTrue(description.contains("coding"));
    }

    @Test
    void testMetaToolSkillToolGroupInToolInstructions() {
        // 准备
        groupManager.createSkillToolGroup("code_tools", "Code execution tools", false, "coding");

        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("code_tools"));

        // 执行
        ToolResultBlock result = callTool(metaTool, input);

        // 断言：tool-instructions 应包含增强描述
        String resultText = text(result);
        assertTrue(resultText.contains("tool-instructions"));
        assertTrue(resultText.contains("MUST be activated"));
        assertTrue(resultText.contains("coding"));
    }

    @Test
    void testMetaToolCallAsyncWithoutRuntimeContextFailsWithoutMutatingGroupManager() {
        // 准备
        groupManager.createToolGroup("analytics", "Analytics tools", false);
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        Map<String, Object> input = new HashMap<>();
        input.put("to_activate", List.of("analytics"));

        // 执行：无运行上下文，rc == null 表示无逐会话工具上下文
        ToolResultBlock result = callTool(metaTool, input, (RuntimeContext) null);

        // 断言：快速失败，绝不修改共享组管理器
        assertNotNull(result);
        assertEquals(ToolResultState.ERROR, result.getState());
        String resultText = text(result);
        assertTrue(resultText.contains("Error:"));
        assertTrue(resultText.contains("requires a per-session runtime context"));
        assertFalse(groupManager.getToolGroup("analytics").isActive());
    }

    @Test
    void copiedToolkitMetaToolUsesCopiedGroups() {
        Toolkit source = new Toolkit();
        source.registerMetaTool();
        Toolkit copy = source.copy();
        copy.createToolGroup("only_in_copy", "copy group", false);
        ToolContextState state = ToolContextState.builder().build();
        callTool(
                copy.getTool("reset_equipped_tools"),
                Map.of("to_activate", List.of("only_in_copy")),
                state);
        assertEquals(List.of("only_in_copy"), state.getActivatedGroups());
        assertFalse(
                source.getTool("reset_equipped_tools")
                        .getParameters()
                        .toString()
                        .contains("only_in_copy"));
    }
}
