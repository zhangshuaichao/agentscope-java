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
package io.agentscope.harness.agent.skill.curator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("deprecation")
class CanaryFilterTest {

    @TempDir Path workspace;
    private SkillUsageStore store;

    @BeforeEach
    void setUp() {
        store = new SkillUsageStore(new LocalFilesystem(workspace));
    }

    private static AgentSkill skill(String name) {
        return new AgentSkill(name, "desc " + name, "# " + name, null);
    }

    private static RuntimeContext ctxFor(String userId) {
        return RuntimeContext.builder().userId(userId).build();
    }

    @Test
    void agentCreatedSkill_canaryAtTenPercent_admitsRoughlyTenPercent() {
        store.markAgentCreated("rolled", "auto", List.of("prod"));
        CanaryFilter f = new CanaryFilter(10, store);

        AgentSkill s = skill("rolled");
        int admitted = 0;
        int total = 200;
        for (int i = 0; i < total; i++) {
            String user = "user-" + i;
            List<AgentSkill> out = f.filter(List.of(s), ctxFor(user));
            if (!out.isEmpty()) {
                admitted++;
            }
        }
        // 允许较宽范围：200 的 10% 为 20，容忍 5 到 40。
        assertTrue(
                admitted >= 5 && admitted <= 40,
                "expected ~10% admit; got " + admitted + "/" + total);
    }

    @Test
    void preExistingSkill_passesThroughAt100Percent() {
        // 植入无旁路元数据条目的技能，应视为用户编写／外部技能。
        // CanaryFilter 不应限制它。
        AgentSkill s = skill("hand-crafted");
        CanaryFilter f = new CanaryFilter(0, store); // 0% 会阻止智能体创建的技能。
        List<AgentSkill> admitted = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            admitted.addAll(f.filter(List.of(s), ctxFor("user-" + i)));
        }
        assertEquals(
                50, admitted.size(), "external skill must always pass through (createdBy=null)");
    }

    @Test
    void agentDraft_isAlsoTreatedAsExternal_byCanaryFilter() {
        // 草稿的 createdBy="agent-draft"，而非 "agent"；金丝雀过滤器只限制
        // "agent"，所以草稿通过该过滤器，但真正将草稿隐藏于提示之外的是
        // EnvironmentFilter；此处仅确认 CanaryFilter
        // 不会意外阻止它们。
        store.markAgentDraft("draft-x", null);
        CanaryFilter f = new CanaryFilter(0, store);
        AgentSkill s = skill("draft-x");
        assertEquals(1, f.filter(List.of(s), ctxFor("user-7")).size());
    }

    @Test
    void zeroPercent_admitsNothingAgentCreated() {
        store.markAgentCreated("none", "auto", List.of("prod"));
        CanaryFilter f = new CanaryFilter(0, store);
        AgentSkill s = skill("none");
        for (int i = 0; i < 20; i++) {
            assertTrue(
                    f.filter(List.of(s), ctxFor("user-" + i)).isEmpty(), "0% must admit nothing");
        }
    }

    @Test
    void hundredPercent_admitsEverythingAgentCreated() {
        store.markAgentCreated("yes", "auto", List.of("prod"));
        CanaryFilter f = new CanaryFilter(100, store);
        AgentSkill s = skill("yes");
        for (int i = 0; i < 20; i++) {
            assertEquals(1, f.filter(List.of(s), ctxFor("user-" + i)).size());
        }
    }

    @Test
    void rampUpDays_acceptedButNotImplemented() {
        // 验收：rampUpDays 参数可以编译且 getter 有效；当前行为
        // 与静态比例相同，完整实现留待未来里程碑。
        CanaryFilter f = new CanaryFilter(50, 7, store);
        assertEquals(7, f.getRampUpDays());
    }
}
