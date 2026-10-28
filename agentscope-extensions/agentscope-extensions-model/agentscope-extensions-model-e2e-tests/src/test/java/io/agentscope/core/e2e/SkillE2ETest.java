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
package io.agentscope.core.e2e;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.e2e.providers.ModelProvider;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

/** 技能召回与代码执行召回 E2E 基准测试。<p>使用 {@code src/test/resources/e2e-skills/} 中专门设计的技能，考察四个维度：<ul><li><b>语义区分</b>：{@code data-transform} 与 {@code data-report} 领域相近，模型需区分格式转换与统计分析。<li><b>抗干扰</b>：{@code image-resize} 是非图片任务的干扰项，不涉及图片时不得选择。<li><b>模糊触发</b>：用户不显式提及日志时，{@code log-parser} 仍应由“应用崩溃”等间接描述触发。<li><b>代码执行召回</b>：{@code git-changelog} 等技能已部署脚本，模型应引用绝对路径而非内联重写等价代码。</ul><p><b>通过标准：</b>所有提供方和场景的总体召回率 &ge; 75%。单个测试方法不判失败，由 {@link #assertRecallRates()} 的 {@code @AfterAll} 汇总断言。<p><b>要求：</b>{@code ENABLE_E2E_TESTS=true} 且至少配置一个 API 密钥环境变量。 */
@Tag("e2e")
@Tag("skill")
@ExtendWith(E2ETestCondition.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Skill E2E Benchmark Tests")
class SkillE2ETest {

    private static final String SKILLS_CLASSPATH = "e2e-skills";
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final double RECALL_THRESHOLD = 0.75;

    /** 同时注册所有基准技能，模型必须选对目标。 */
    private static final List<String> ALL_SKILL_NAMES =
            List.of("data-transform", "data-report", "image-resize", "log-parser", "git-changelog");

    // key = "SKILL_RECALL/<skillName>/<provider>"
    private static final Map<String, Boolean> RESULTS = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // 技能召回基准场景
    //
    // 维度 1：语义区分，data-transform 与 data-report
    // 两者都涉及数据，但一个做格式转换，另一个做分析。
    // 维度 2：抗干扰，非图片任务不得触发 image-resize
    // 维度 3：模糊触发，通过间接问题描述触发 log-parser
    // -------------------------------------------------------------------------

    /** 语义区分：必须选择 data-transform，而非 data-report。 */
    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("[semantic] data-transform: convert CSV to JSON")
    void testSkillRecall_dataTransform(ModelProvider provider) throws IOException {
        runSkillRecallTest(
                provider,
                "data-transform",
                "I have a CSV file exported from Excel and I need to convert it to JSON"
                        + " so my frontend application can consume it.");
    }

    /** 语义区分：必须选择 data-report，而非 data-transform。 */
    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("[semantic] data-report: summarize dataset statistics")
    void testSkillRecall_dataReport(ModelProvider provider) throws IOException {
        runSkillRecallTest(
                provider,
                "data-report",
                "I have a CSV file with sales numbers across multiple regions and I need"
                        + " a summary showing averages, totals, and standard deviations.");
    }

    /** 模糊触发：间接描述仍应触发 log-parser。 */
    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("[fuzzy] log-parser: implicit debugging prompt")
    void testSkillRecall_logParser_fuzzy(ModelProvider provider) throws IOException {
        runSkillRecallTest(
                provider,
                "log-parser",
                "My app keeps crashing in production and I have no idea why."
                        + " Can you help me figure out what's going wrong?");
    }

    // -------------------------------------------------------------------------
    // 汇总召回率断言，在全部测试完成后运行一次
    // -------------------------------------------------------------------------

    @AfterAll
    static void assertRecallRates() {
        if (RESULTS.isEmpty()) {
            return;
        }

        long total = RESULTS.size();
        long hits = RESULTS.values().stream().filter(Boolean::booleanValue).count();
        double rate = total > 0 ? (double) hits / total : 1.0;

        String sep = "=".repeat(62);
        System.out.println("\n" + sep);
        System.out.println("  SKILL BENCHMARK RECALL SUMMARY");
        System.out.println(sep);

        System.out.println("\n  Skill recall:");
        RESULTS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        e -> {
                            String label = e.getKey().substring("SKILL_RECALL/".length());
                            System.out.printf(
                                    "    %s  %s%n", e.getValue() ? "[PASS]" : "[FAIL]", label);
                        });

        System.out.println();
        System.out.printf(
                "  Overall: %d / %d  (%.0f%%)   threshold >= %.0f%%%n",
                hits, total, rate * 100, RECALL_THRESHOLD * 100);
        System.out.println(sep + "\n");

        assertTrue(
                rate >= RECALL_THRESHOLD,
                String.format(
                        "Recall rate %.0f%% (%d/%d) is below the %.0f%% threshold.",
                        rate * 100, hits, total, RECALL_THRESHOLD * 100));
    }

    // -------------------------------------------------------------------------
    // 共享辅助方法
    // -------------------------------------------------------------------------

    private void runSkillRecallTest(ModelProvider provider, String targetSkillName, String prompt)
            throws IOException {
        String resultKey = "SKILL_RECALL/" + targetSkillName + "/" + provider.getProviderName();
        System.out.println(
                "\n>>> Skill Recall [" + targetSkillName + "] | " + provider.getProviderName());

        try (ClasspathSkillRepository repo = new ClasspathSkillRepository(SKILLS_CLASSPATH)) {

            Toolkit toolkit = new Toolkit();
            SkillBox skillBox = new SkillBox(toolkit);

            for (String name : ALL_SKILL_NAMES) {
                AgentSkill skill = repo.getSkill(name);
                assertNotNull(skill, "Benchmark skill not found in classpath: " + name);
                skillBox.registration().skill(skill).apply();
            }
            skillBox.registerSkillLoadTool();

            AgentSkill targetSkill = repo.getSkill(targetSkillName);
            String expectedSkillId = targetSkill.getSkillId();
            System.out.println("    expected skillId : " + expectedSkillId);
            System.out.println("    prompt           : " + prompt);

            AtomicBoolean loadedCorrectSkill = new AtomicBoolean(false);

            ReActAgent agent =
                    provider.createAgentBuilder("SkillRecallAgent-" + targetSkillName, toolkit)
                            .maxIters(3)
                            .hook(
                                    new Hook() {
                                        @Override
                                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                                            if (event instanceof PostActingEvent postActing) {
                                                ToolUseBlock toolUse = postActing.getToolUse();
                                                if (toolUse != null
                                                        && "load_skill_through_path"
                                                                .equals(toolUse.getName())
                                                        && expectedSkillId.equals(
                                                                toolUse.getInput()
                                                                        .get("skillId"))) {
                                                    loadedCorrectSkill.set(true);
                                                    postActing.stopAgent();
                                                }
                                            }
                                            return Mono.just(event);
                                        }
                                    })
                            .build();

            try {
                agent.call(TestUtils.createUserMessage("User", prompt)).block(TIMEOUT);
            } catch (Exception e) {
                System.out.println("    agent error: " + e.getMessage());
            }

            boolean passed = loadedCorrectSkill.get();
            RESULTS.put(resultKey, passed);

            if (passed) {
                System.out.println(
                        "<<< [PASS] Skill recall ["
                                + targetSkillName
                                + "] | "
                                + provider.getProviderName());
            } else {
                System.out.println(
                        "<<< [FAIL] Skill recall ["
                                + targetSkillName
                                + "] | "
                                + provider.getProviderName()
                                + " — did not call load_skill_through_path(skillId='"
                                + expectedSkillId
                                + "')");
            }
        }
    }
}
