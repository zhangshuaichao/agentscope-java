/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.training.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.training.util.TrainingTestConstants;
import io.agentscope.core.training.util.TrainingTestUtils;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

@DisplayName("ExplicitMarkingStrategy Unit Tests")
class ExplicitMarkingStrategyTest {

    private Agent mockAgent;
    private List<Msg> testInputs;
    private Msg testOutput;

    @BeforeEach
    void setUp() {
        mockAgent = TrainingTestUtils.createMockAgent(TrainingTestConstants.TEST_AGENT_NAME);
        testInputs = TrainingTestUtils.createTestMessages();
        testOutput =
                TrainingTestUtils.createTestMessage("assistant", MsgRole.ASSISTANT, "Response");
    }

    @Test
    @DisplayName("Should create strategy with default TTL")
    void shouldCreateStrategyWithDefaultTTL() {
        // 执行
        ExplicitMarkingStrategy strategy = ExplicitMarkingStrategy.create();

        // 断言
        assertNotNull(strategy);
        assertEquals("ExplicitMarking", strategy.name());
    }

    @Test
    @DisplayName("Should create strategy with custom TTL")
    void shouldCreateStrategyWithCustomTTL() {
        // 执行
        ExplicitMarkingStrategy strategy = ExplicitMarkingStrategy.withTTL(Duration.ofMinutes(5));

        // 断言
        assertNotNull(strategy);
    }

    @Test
    @DisplayName("Should reject when no annotation in context")
    void shouldRejectWhenNoAnnotationInContext() {
        // 准备
        ExplicitMarkingStrategy strategy = ExplicitMarkingStrategy.create();

        // 执行
        SelectionDecision decision =
                strategy.shouldSelect(mockAgent, testInputs, testOutput, Context.empty());

        // 断言
        assertFalse(decision.shouldTrain());
        assertEquals("no-explicit-marking", decision.getReason());
    }

    @Test
    @DisplayName("Should accept when annotation is enabled")
    void shouldAcceptWhenAnnotationIsEnabled() {
        // 准备
        ExplicitMarkingStrategy strategy = ExplicitMarkingStrategy.create();
        TrainingAnnotation annotation = TrainingAnnotation.enabled();
        ContextView context = Context.of(TrainingContext.REACTOR_KEY, annotation);

        // 执行
        SelectionDecision decision =
                strategy.shouldSelect(mockAgent, testInputs, testOutput, context);

        // 断言
        assertTrue(decision.shouldTrain());
        assertEquals("explicit-marking", decision.getReason());
    }

    @Test
    @DisplayName("Should accept with labels from annotation")
    void shouldAcceptWithLabelsFromAnnotation() {
        // 准备
        ExplicitMarkingStrategy strategy = ExplicitMarkingStrategy.create();
        TrainingAnnotation annotation = TrainingAnnotation.withLabels("high-quality", "production");
        ContextView context = Context.of(TrainingContext.REACTOR_KEY, annotation);

        // 执行
        SelectionDecision decision =
                strategy.shouldSelect(mockAgent, testInputs, testOutput, context);

        // 断言
        assertTrue(decision.shouldTrain());
        assertTrue(decision.getLabels().contains("high-quality"));
        assertTrue(decision.getLabels().contains("production"));
    }

    @Test
    @DisplayName("Should pass through taskId from annotation metadata")
    void shouldPassThroughTaskIdFromAnnotationMetadata() {
        // 准备
        ExplicitMarkingStrategy strategy = ExplicitMarkingStrategy.create();
        TrainingAnnotation annotation =
                TrainingAnnotation.builder()
                        .enabled(true)
                        .taskId("custom-task-id")
                        .labels(Arrays.asList("test"))
                        .build();
        ContextView context = Context.of(TrainingContext.REACTOR_KEY, annotation);

        // 执行
        SelectionDecision decision =
                strategy.shouldSelect(mockAgent, testInputs, testOutput, context);

        // 断言
        assertTrue(decision.shouldTrain());
        assertEquals("custom-task-id", decision.getMetadata().get("taskId"));
    }

    @Test
    @DisplayName("Should pass through metadata from annotation")
    void shouldPassThroughMetadataFromAnnotation() {
        // 准备
        ExplicitMarkingStrategy strategy = ExplicitMarkingStrategy.create();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("userId", "user-123");
        metadata.put("sessionId", "session-456");

        TrainingAnnotation annotation =
                TrainingAnnotation.builder()
                        .enabled(true)
                        .labels(Arrays.asList("test"))
                        .metadata(metadata)
                        .build();
        ContextView context = Context.of(TrainingContext.REACTOR_KEY, annotation);

        // 执行
        SelectionDecision decision =
                strategy.shouldSelect(mockAgent, testInputs, testOutput, context);

        // 断言
        assertTrue(decision.shouldTrain());
        assertEquals("user-123", decision.getMetadata().get("userId"));
        assertEquals("session-456", decision.getMetadata().get("sessionId"));
    }

    @Test
    @DisplayName("Should reject when annotation is expired")
    void shouldRejectWhenAnnotationIsExpired() {
        // 准备
        ExplicitMarkingStrategy strategy =
                ExplicitMarkingStrategy.withTTL(Duration.ofMillis(1)); // 极短的 TTL。

        // 创建带旧时间戳的注解。
        TrainingAnnotation annotation =
                TrainingAnnotation.builder()
                        .enabled(true)
                        .timestamp(System.currentTimeMillis() - 1000) // 1 秒前
                        .build();
        ContextView context = Context.of(TrainingContext.REACTOR_KEY, annotation);

        // 执行
        SelectionDecision decision =
                strategy.shouldSelect(mockAgent, testInputs, testOutput, context);

        // 断言
        assertFalse(decision.shouldTrain());
        assertEquals("explicit-marking-expired", decision.getReason());
    }

    @Test
    @DisplayName("Should return high priority")
    void shouldReturnHighPriority() {
        // 准备
        ExplicitMarkingStrategy strategy = ExplicitMarkingStrategy.create();

        // 断言
        assertEquals(10, strategy.priority());
    }

    @Test
    @DisplayName("Should return correct name")
    void shouldReturnCorrectName() {
        // 准备
        ExplicitMarkingStrategy strategy = ExplicitMarkingStrategy.create();

        // 断言
        assertEquals("ExplicitMarking", strategy.name());
    }
}
