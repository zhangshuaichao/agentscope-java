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
package io.agentscope.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

/**
 * SkillBox 单元测试。
 *
 * <p>验证技能注册。
 */
@Tag("unit")
class SkillBoxTest {

    private SkillBox skillBox;
    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        skillBox = new SkillBox(toolkit);
        toolkit.registerTool(skillBox);
    }

    @Nested
    @DisplayName("SkillBox Basic Skill Management Test")
    class SkillBoxBasic {
        @Test
        @DisplayName("Should get skill by id")
        void testGetSkillById() {
            AgentSkill skill = new AgentSkill("test-skill", "Test Skill", "# Content", null);
            skillBox.registerSkill(skill);

            AgentSkill retrieved = skillBox.getSkill(skill.getSkillId());

            assertNotNull(retrieved);
            assertEquals("test-skill", retrieved.getName());
            assertEquals("Test Skill", retrieved.getDescription());
        }

        @Test
        @DisplayName("Should throw exception for null skill id")
        void testThrowExceptionForNullSkillId() {
            assertThrows(IllegalArgumentException.class, () -> skillBox.getSkill(null));
        }

        @Test
        @DisplayName("Should remove skill")
        void testRemoveSkill() {
            AgentSkill skill = new AgentSkill("test-skill", "Test Skill", "# Content", null);
            skillBox.registerSkill(skill);

            assertTrue(skillBox.exists("test-skill_custom"));

            skillBox.removeSkill("test-skill_custom");

            assertFalse(skillBox.exists(skill.getSkillId()));
        }

        @Test
        @DisplayName("Should check skill exists")
        void testCheckSkillExists() {
            assertFalse(skillBox.exists("non_existent_skill"));

            AgentSkill skill = new AgentSkill("test-skill", "Test Skill", "# Content", null);
            skillBox.registerSkill(skill);

            assertTrue(skillBox.exists(skill.getSkillId()));
        }

        @Test
        @DisplayName("Should register skill load tools")
        void testRegisterSkillLoadTools() {
            // 调用 registerSkillAccessTools 后，工具应可用
            skillBox.registerSkillLoadTool();
            assertNotNull(toolkit.getTool("load_skill_through_path"));
        }

        @Test
        @DisplayName("Should create tool group when registering skill")
        void testCreateToolGroupWhenRegisteringSkill() {
            AgentSkill skill = new AgentSkill("my-skill", "My Skill", "# Content", null);

            // 注册前工具组不应存在
            String toolsGroupName = skill.getSkillId() + "_skill_tools";
            assertNull(
                    toolkit.getToolGroup(toolsGroupName),
                    "Tool group should not exist before skill registration");

            // 创建简单测试工具
            AgentTool testTool = createTestTool("test_tool");

            // 注册带工具的技能
            skillBox.registration().agentTool(testTool).skill(skill).apply();

            // 注册后应创建工具组
            assertNotNull(
                    toolkit.getToolGroup(toolsGroupName),
                    "Tool group should be created after skill registration");

            // 验证工具组属性
            var toolGroup = toolkit.getToolGroup(toolsGroupName);
            assertEquals(toolsGroupName, toolGroup.getName());
        }

        @Test
        @DisplayName("Should throw exception for null skill id in operations")
        void testThrowExceptionForNullSkillIdInOperations() {
            assertThrows(IllegalArgumentException.class, () -> skillBox.removeSkill(null));
            assertThrows(IllegalArgumentException.class, () -> skillBox.exists(null));
        }

        @Test
        @DisplayName("Should successfully register when only tool object is provided")
        void testSuccessfullyRegisterWhenOnlyToolObjectProvided() {
            TestToolObject toolObject = new TestToolObject();
            AgentSkill skill =
                    new AgentSkill(
                            "tool-object-only-skill",
                            "Skill with only tool object",
                            "# Tool Object",
                            null);

            // 不应抛异常：仅有一种注册类型
            skillBox.registration().skill(skill).tool(toolObject).apply();

            assertTrue(skillBox.exists(skill.getSkillId()));
            assertNotNull(toolkit.getTool("test_tool_method"), "Tool should be registered");
        }

        @Test
        @DisplayName("Should successfully register when only agent tool is provided")
        void testSuccessfullyRegisterWhenOnlyAgentToolProvided() {
            AgentTool agentTool = createTestTool("agent_tool_only");
            AgentSkill skill =
                    new AgentSkill(
                            "agent-tool-only-skill",
                            "Skill with only agent tool",
                            "# Agent Tool",
                            null);

            // 不应抛异常：仅有一种注册类型
            skillBox.registration().skill(skill).agentTool(agentTool).apply();

            assertTrue(skillBox.exists(skill.getSkillId()));
            assertNotNull(toolkit.getTool("agent_tool_only"), "Agent tool should be registered");
        }

        @Test
        @DisplayName("Should successfully register when only mcp client is provided")
        void testSuccessfullyRegisterWhenOnlyMcpClientProvided() {
            McpClientWrapper mcpClient = mock(McpClientWrapper.class);
            McpSchema.Tool mockToolInfo =
                    McpSchema.Tool.builder("mcp_only_tool", Map.of("type", "object"))
                            .description("MCP only tool")
                            .build();
            when(mcpClient.listTools()).thenReturn(Mono.just(List.of(mockToolInfo)));
            when(mcpClient.isInitialized()).thenReturn(true);
            when(mcpClient.initialize()).thenReturn(Mono.empty());
            when(mcpClient.getName()).thenReturn("mcp-only-client");

            AgentSkill skill =
                    new AgentSkill(
                            "mcp-only-skill", "Skill with only MCP client", "# MCP Only", null);

            // 不应抛异常：仅有一种注册类型
            skillBox.registration().skill(skill).mcpClient(mcpClient).apply();

            assertTrue(skillBox.exists(skill.getSkillId()));
            assertNotNull(toolkit.getTool("mcp_only_tool"), "MCP tool should be registered");
        }

        @Test
        @DisplayName("Should return empty set when no skills are registered")
        void testGetAllSkillIdsWhenEmpty() {
            var skillIds = skillBox.getAllSkillIds();

            assertNotNull(skillIds, "Skill IDs set should not be null");
            assertTrue(
                    skillIds.isEmpty(), "Skill IDs set should be empty when no skills registered");
        }

        @Test
        @DisplayName("Should return all skill IDs when multiple skills are registered")
        void testGetAllSkillIdsWithMultipleSkills() {
            AgentSkill skill1 = new AgentSkill("skill-one", "Skill One", "# Content 1", null);
            AgentSkill skill2 = new AgentSkill("skill-two", "Skill Two", "# Content 2", null);
            AgentSkill skill3 = new AgentSkill("skill-three", "Skill Three", "# Content 3", null);

            skillBox.registerSkill(skill1);
            skillBox.registerSkill(skill2);
            skillBox.registerSkill(skill3);

            var skillIds = skillBox.getAllSkillIds();

            assertNotNull(skillIds, "Skill IDs set should not be null");
            assertEquals(3, skillIds.size(), "Should have exactly three skill IDs");
            assertTrue(skillIds.contains(skill1.getSkillId()), "Should contain first skill ID");
            assertTrue(skillIds.contains(skill2.getSkillId()), "Should contain second skill ID");
            assertTrue(skillIds.contains(skill3.getSkillId()), "Should contain third skill ID");
        }
    }

    @Nested
    @DisplayName("SkillBox Upload Security Gate Test")
    class SkillBoxUploadSecurityGate {

        @TempDir Path tempDir;

        private List<Path> filesUnder(Path root) throws IOException {
            try (Stream<Path> walk = Files.walk(root)) {
                return walk.filter(Files::isRegularFile).toList();
            }
        }

        @Test
        @DisplayName("Should normalize skillDir before containment check")
        void testNormalizesSkillDirBeforeContainmentCheck() throws IOException {
            // 名称合法但 source 被污染：技能 ID "safe_../../.." 解析到
            // 目标目录外，".." 段抵消 "safe_.." 和 "skills"，必须由
            // 规范化后的 startsWith(targetDir) 检查捕获。
            skillBox.setWorkDir(tempDir);
            skillBox.registerSkill(
                    new AgentSkill(
                            "safe",
                            "Poisoned source",
                            "# Content",
                            Map.of("scripts/run.sh", "echo hi"),
                            "../../.."));

            skillBox.uploadSkillFiles();

            List<Path> files = filesUnder(tempDir);
            assertTrue(
                    files.isEmpty(),
                    "a skill id that normalizes outside the target dir must not be"
                            + " materialized, but found: "
                            + files);
        }

        @Test
        @DisplayName(
                "Should materialize valid resources under the skill id and block escaping ones")
        void testMaterializesValidResourcesUnderSkillIdAndBlocksEscapingOnes() throws IOException {
            skillBox.setWorkDir(tempDir);
            skillBox.registerSkill(
                    new AgentSkill(
                            "safe",
                            "Mixed resources",
                            "# Content",
                            Map.of(
                                    "ok.txt", "fine",
                                    "scripts/run.sh", "echo hi",
                                    "sub/../../escaped.txt", "evil")));

            skillBox.uploadSkillFiles();

            // 合法资源进入 skills/<skillId>/ 且内容完整；
            // 只有越界资源被排除在目录树外。
            Path skillDir = tempDir.resolve("skills").resolve("safe_custom");
            assertEquals("fine", Files.readString(skillDir.resolve("ok.txt")));
            assertEquals(
                    "echo hi", Files.readString(skillDir.resolve("scripts").resolve("run.sh")));
            assertEquals(
                    List.of(
                            skillDir.resolve("ok.txt"),
                            skillDir.resolve("scripts").resolve("run.sh")),
                    filesUnder(tempDir));
        }
    }

    private AgentTool createTestTool(String name) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "Test tool: " + name;
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(
                        ToolResultBlock.of(TextBlock.builder().text("Test result").build()));
            }
        };
    }

    /**
     * 包含 @Tool 注解方法的测试工具类，用于测试工具对象注册。
     */
    private static class TestToolObject {

        @Tool(name = "test_tool_method", description = "A test tool method")
        public Mono<ToolResultBlock> testToolMethod(
                @ToolParam(name = "input", description = "Test input") String input) {
            return Mono.just(
                    ToolResultBlock.of(TextBlock.builder().text("Result: " + input).build()));
        }
    }
}
