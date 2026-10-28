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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * SkillHook 单元测试。
 *
 * <p>验证 SkillHook 在 {@link PreCallEvent} 期间正确注入技能提示词。
 */
@Tag("unit")
class SkillHookTest {
    private SkillBox skillBox;
    private Toolkit toolkit;
    private Agent testAgent;
    private Hook skillHook;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        skillBox = new SkillBox(toolkit);
        skillHook = new SkillHook(skillBox);
        skillBox.registerSkillLoadTool(); // 注册技能加载工具
        testAgent = new TestAgent("test-agent");
    }

    @Test
    @DisplayName("Should inject skill prompt for a registered skill")
    void testInjectPromptForRegisteredSkill() {
        AgentSkill skill = new AgentSkill("test-skill", "Test Skill", "# Test Content", null);
        skillBox.registerSkill(skill);

        // 创建带一条用户消息的 PreCallEvent
        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("User query").build())
                        .build());

        PreCallEvent event = new PreCallEvent(testAgent, messages);

        // 执行：通过钩子处理事件
        PreCallEvent result = skillHook.onEvent(event).block();

        // 断言：技能提示词应注入 systemMsg，而非 inputMessages
        assertNotNull(result, "Event should be processed");
        assertEquals(1, result.getInputMessages().size(), "Should not add SYSTEM to inputMessages");
        assertNotNull(result.getSystemMessage(), "systemMsg should be set by SkillHook");
        assertEquals(MsgRole.SYSTEM, result.getSystemMessage().getRole());
        assertTrue(
                result.getSystemMessage().getTextContent().contains("test-skill"),
                "systemMsg should contain skill information");
    }

    @Test
    @DisplayName("Should inject prompt even when skills are registered but not active")
    void testInjectPromptForRegisteredSkills() {
        // 准备：注册技能但不激活
        // 注意：SkillPromptProvider 为全部注册技能返回提示词，而非仅
        // 已激活技能
        AgentSkill skill = new AgentSkill("inactive-skill", "Inactive Skill", "# Inactive", null);
        skillBox.registerSkill(skill);

        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("User query").build())
                        .build());

        PreCallEvent event = new PreCallEvent(testAgent, messages);

        // 执行：通过钩子处理事件
        PreCallEvent result = skillHook.onEvent(event).block();

        // 断言：技能提示词应位于 systemMsg，而非 inputMessages
        assertNotNull(result, "Event should be processed");
        assertEquals(1, result.getInputMessages().size(), "Should not add SYSTEM to inputMessages");
        assertNotNull(result.getSystemMessage(), "systemMsg should be set for registered skills");
        assertEquals(MsgRole.SYSTEM, result.getSystemMessage().getRole());
    }

    @Test
    @DisplayName("Should handle empty skill prompt gracefully")
    void testHandleEmptySkillPromptGracefully() {
        // 准备：完全不注册技能
        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("User query").build())
                        .build());

        PreCallEvent event = new PreCallEvent(testAgent, messages);

        // 执行：通过钩子处理事件
        PreCallEvent result = skillHook.onEvent(event).block();

        // 断言：应平稳处理且不添加提示词
        assertNotNull(result, "Event should be processed");
        assertEquals(1, result.getInputMessages().size(), "Should not add empty skill prompt");
    }

    @Test
    @DisplayName("Should return correct hook priority")
    void testHookPriority() {
        assertEquals(
                SkillHook.SKILL_HOOK_PRIORITY,
                skillHook.priority(),
                "Skill hook should use SKILL_HOOK_PRIORITY");
    }

    @Test
    @DisplayName("[ISSUE#765] should inject skill prompt at first not last")
    void testInjectSkillPromptAtFirst() {
        AgentSkill skill = new AgentSkill("test-skill", "Test Skill", "# Test Content", null);
        skillBox.registerSkill(skill);

        // 创建带多条消息、尚无 SYSTEM 消息的 PreCallEvent
        List<Msg> messages =
                new ArrayList<>(
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .content(TextBlock.builder().text("User query").build())
                                        .build(),
                                Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .content(
                                                TextBlock.builder()
                                                        .text("Assistant response")
                                                        .build())
                                        .build()));

        PreCallEvent event = new PreCallEvent(testAgent, messages);

        // 执行：通过钩子处理事件
        PreCallEvent result = skillHook.onEvent(event).block();

        // 断言：技能提示词位于 systemMsg，inputMessages 不变
        assertNotNull(result, "Event should be processed");
        assertEquals(
                2,
                result.getInputMessages().size(),
                "inputMessages should not gain a SYSTEM entry");
        assertNotNull(result.getSystemMessage(), "systemMsg should be set");
        assertTrue(
                result.getSystemMessage().getTextContent().contains("test-skill"),
                "systemMsg should contain skill information");

        // 验证原始消息按顺序保留
        assertEquals(
                "User query",
                result.getInputMessages().get(0).getTextContent(),
                "First message should be original user query");
        assertEquals(
                "Assistant response",
                result.getInputMessages().get(1).getTextContent(),
                "Second message should be original assistant response");
    }

    @Test
    @DisplayName(
            "[ISSUE#845] should merge skill prompt into existing system message instead of adding a"
                    + " second one")
    void testMergeSkillPromptIntoExistingSystemMessage() {
        AgentSkill skill = new AgentSkill("test-skill", "Test Skill", "# Test Content", null);
        skillBox.registerSkill(skill);

        // 创建已有 SYSTEM 消息的 PreCallEvent
        List<Msg> messages =
                new ArrayList<>(
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.SYSTEM)
                                        .content(
                                                TextBlock.builder()
                                                        .text("System instruction")
                                                        .build())
                                        .build(),
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .content(TextBlock.builder().text("User query").build())
                                        .build(),
                                Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .content(
                                                TextBlock.builder()
                                                        .text("Assistant response")
                                                        .build())
                                        .build()));

        PreCallEvent event = new PreCallEvent(testAgent, messages);

        // 执行：通过钩子处理事件
        PreCallEvent result = skillHook.onEvent(event).block();

        // 断言：SkillHook 现在使用 systemMsg API，不修改 inputMessages。
        // inputMessages 中既有 SYSTEM 消息仍保留，因为它属于调用方列表，
        // 技能提示词则追加到 event.getSystemMessage()。
        assertNotNull(result, "Event should be processed");

        // inputMessages 仍有 3 项，未被 SkillHook 修改
        assertEquals(
                3, result.getInputMessages().size(), "SkillHook should not modify inputMessages");

        // systemMsg 应包含技能提示词
        assertNotNull(result.getSystemMessage(), "systemMsg should be set by SkillHook");
        assertEquals(MsgRole.SYSTEM, result.getSystemMessage().getRole());
        assertTrue(
                result.getSystemMessage().getTextContent().contains("test-skill"),
                "systemMsg should contain skill information");

        // 验证原始消息按顺序保留
        assertEquals("User query", result.getInputMessages().get(1).getTextContent());
        assertEquals("Assistant response", result.getInputMessages().get(2).getTextContent());
    }

    private <T extends HookEvent> Mono<T> notifyHooks(T event, List<Hook> hooks) {
        Mono<T> result = Mono.just(event);
        List<Hook> sortedHooks =
                hooks.stream().sorted(Comparator.comparingInt(Hook::priority)).toList();
        for (Hook hook : sortedHooks) {
            result = result.flatMap(hook::onEvent);
        }
        return result;
    }

    /**
     * 用于测试 Hook 事件的简单测试智能体。
     */
    private static class TestAgent extends AgentBase {
        TestAgent(String name) {
            super(name);
        }

        @Override
        protected Mono<Msg> doCall(List<Msg> msgs) {
            return Mono.just(
                    Msg.builder()
                            .name(getName())
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Test response").build())
                            .build());
        }

        @Override
        protected Mono<Void> doObserve(Msg msg) {
            return Mono.empty();
        }

        @Override
        protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
            return Mono.just(
                    Msg.builder()
                            .name(getName())
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Interrupted").build())
                            .build());
        }
    }
}
