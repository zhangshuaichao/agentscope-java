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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import org.junit.jupiter.api.Test;

/** 阶段 B-0：组合子会话 ID 的算法。测试位于 {@code io.agentscope.harness.agent}，以访问包级私有的 {@link HarnessAgentBuilderSupport#deriveChildSessionId}。派生方式与使用 Workspace、Redis、InMemory 或自定义 {@link io.agentscope.core.state.AgentStateStore} 后端无关。 */
class SubagentSessionKeyTest {

    private static SubagentDeclaration decl(String name) {
        return SubagentDeclaration.builder()
                .name(name)
                .description("test")
                .inlineAgentsBody("body")
                .build();
    }

    private static SubagentDeclaration sharedDecl(String name) {
        return SubagentDeclaration.builder()
                .name(name)
                .description("test")
                .inlineAgentsBody("body")
                .workspaceMode(WorkspaceMode.SHARED)
                .build();
    }

    @Test
    void nullParentRc_legacyBucket() {
        String id = HarnessAgentBuilderSupport.deriveChildSessionId(decl("worker"), null);
        assertEquals("worker", id);
    }

    @Test
    void emptyParentRc_legacyBucket() {
        String id =
                HarnessAgentBuilderSupport.deriveChildSessionId(
                        decl("worker"), RuntimeContext.empty());
        assertEquals("worker", id);
    }

    @Test
    void onlySessionId_appendsAtSegment() {
        RuntimeContext rc = RuntimeContext.builder().sessionId("s1").build();
        String id = HarnessAgentBuilderSupport.deriveChildSessionId(decl("worker"), rc);
        assertEquals("worker@s1", id);
    }

    @Test
    void onlyUserId_appendsHashSegment() {
        RuntimeContext rc = RuntimeContext.builder().userId("alice").build();
        String id = HarnessAgentBuilderSupport.deriveChildSessionId(decl("worker"), rc);
        assertEquals("worker#alice", id);
    }

    @Test
    void bothIds_composedInOrder() {
        RuntimeContext rc = RuntimeContext.builder().sessionId("s1").userId("alice").build();
        String id = HarnessAgentBuilderSupport.deriveChildSessionId(decl("worker"), rc);
        assertEquals("worker@s1#alice", id);
    }

    @Test
    void sharedMode_alwaysLegacyBucket() {
        RuntimeContext rc = RuntimeContext.builder().sessionId("s1").userId("alice").build();
        String id = HarnessAgentBuilderSupport.deriveChildSessionId(sharedDecl("worker"), rc);
        assertEquals("worker", id);
    }

    @Test
    void slashesInIds_areSanitised() {
        RuntimeContext rc = RuntimeContext.builder().sessionId("a/b/c").userId("dom\\user").build();
        String id = HarnessAgentBuilderSupport.deriveChildSessionId(decl("worker"), rc);
        assertEquals("worker@a_b_c#dom_user", id);
    }

    @Test
    void blankSessionId_treatedAsAbsent() {
        RuntimeContext rc = RuntimeContext.builder().sessionId("   ").userId("alice").build();
        String id = HarnessAgentBuilderSupport.deriveChildSessionId(decl("worker"), rc);
        assertEquals("worker#alice", id);
    }

    @Test
    void differentParents_produceDifferentKeys() {
        RuntimeContext alice = RuntimeContext.builder().sessionId("s1").userId("alice").build();
        RuntimeContext bob = RuntimeContext.builder().sessionId("s1").userId("bob").build();
        assertNotEquals(
                HarnessAgentBuilderSupport.deriveChildSessionId(decl("worker"), alice),
                HarnessAgentBuilderSupport.deriveChildSessionId(decl("worker"), bob));

        RuntimeContext sessA = RuntimeContext.builder().sessionId("A").build();
        RuntimeContext sessB = RuntimeContext.builder().sessionId("B").build();
        assertNotEquals(
                HarnessAgentBuilderSupport.deriveChildSessionId(decl("worker"), sessA),
                HarnessAgentBuilderSupport.deriveChildSessionId(decl("worker"), sessB));
    }
}
