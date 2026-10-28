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
package io.agentscope.harness.agent.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.middleware.SubagentEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 阶段 A 的 {@code mode} 与 {@code hidden} 语义：模式解析，以及拒绝派生仅 PRIMARY 声明。面向 LLM 的可见性过滤由中间件包的尾消息测试 {@code SubagentsMiddlewareHeadStabilityTest} 覆盖。 */
class SubagentModeHiddenTest {

    @Test
    void loader_parsesModeAndHidden() {
        String md =
                """
                ---
                description: An internal compaction agent
                mode: primary
                hidden: true
                ---
                body
                """;
        SubagentDeclaration decl = AgentSpecLoader.parse(md, "compaction", null);
        assertNotNull(decl);
        assertEquals(SubagentDeclaration.Mode.PRIMARY, decl.getMode());
        assertTrue(decl.isHidden());
    }

    @Test
    void loader_modeDefaultsToAll_andHiddenDefaultsFalse() {
        String md =
                """
                ---
                description: Plain agent
                ---
                body
                """;
        SubagentDeclaration decl = AgentSpecLoader.parse(md, "plain", null);
        assertNotNull(decl);
        assertEquals(SubagentDeclaration.Mode.ALL, decl.getMode());
        assertFalse(decl.isHidden());
    }

    @Test
    void loader_acceptsSubagentMode() {
        String md =
                """
                ---
                description: Worker-only
                mode: subagent
                ---
                body
                """;
        SubagentDeclaration decl = AgentSpecLoader.parse(md, "worker", null);
        assertNotNull(decl);
        assertEquals(SubagentDeclaration.Mode.SUBAGENT, decl.getMode());
    }

    @Test
    void loader_unknownMode_fallsBackToAllWithLog() {
        String md =
                """
                ---
                description: Bad mode value
                mode: superduper
                ---
                body
                """;
        SubagentDeclaration decl = AgentSpecLoader.parse(md, "x", null);
        assertNotNull(decl);
        assertEquals(SubagentDeclaration.Mode.ALL, decl.getMode());
    }

    @Test
    void defaultAgentManager_primaryOnlyRejectsSpawn() {
        SubagentDeclaration primaryOnly =
                SubagentDeclaration.builder()
                        .name("compaction")
                        .description("Compacts conversations")
                        .inlineAgentsBody("b")
                        .mode(SubagentDeclaration.Mode.PRIMARY)
                        .build();
        SubagentFactory shouldNotRun =
                (rc) -> {
                    throw new AssertionError(
                            "factory.create() must not be invoked for PRIMARY-only");
                };
        DefaultAgentManager mgr =
                new DefaultAgentManager(
                        List.of(
                                new SubagentEntry(
                                        "compaction",
                                        primaryOnly.getDescription(),
                                        shouldNotRun,
                                        primaryOnly)),
                        null);
        assertTrue(
                mgr.createAgentIfPresent(
                                "compaction", io.agentscope.core.agent.RuntimeContext.empty())
                        .isEmpty());
        assertTrue(mgr.isPrimaryOnly("compaction"));
    }

    @Test
    void defaultAgentManager_subagentMode_isSpawnable() {
        // 通过 hasAgent/isPrimaryOnly 和声明往返检查门控，而不调用
        // createAgentIfPresent，避免只为断言门控决策就引入
        // 完整 ReActAgent 栈构建真实智能体。
        SubagentDeclaration worker =
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("Worker-only")
                        .inlineAgentsBody("b")
                        .mode(SubagentDeclaration.Mode.SUBAGENT)
                        .build();
        SubagentFactory shouldNotRunInThisTest =
                (rc) -> {
                    throw new AssertionError(
                            "factory.create() not exercised by this assertion path");
                };
        DefaultAgentManager mgr =
                new DefaultAgentManager(
                        List.of(
                                new SubagentEntry(
                                        "worker",
                                        worker.getDescription(),
                                        shouldNotRunInThisTest,
                                        worker)),
                        null);
        assertTrue(mgr.hasAgent("worker"));
        assertFalse(mgr.isPrimaryOnly("worker"));
        assertTrue(mgr.getDeclaration("worker").isPresent());
        assertEquals(
                SubagentDeclaration.Mode.SUBAGENT, mgr.getDeclaration("worker").get().getMode());
    }
}
