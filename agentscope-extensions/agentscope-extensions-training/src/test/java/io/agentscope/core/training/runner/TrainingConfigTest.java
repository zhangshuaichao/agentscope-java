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

package io.agentscope.core.training.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.training.reward.RewardCalculator;
import io.agentscope.core.training.strategy.ExplicitMarkingStrategy;
import io.agentscope.core.training.strategy.SamplingRateStrategy;
import io.agentscope.core.training.util.TrainingTestConstants;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TrainingConfig Unit Tests")
class TrainingConfigTest {

    @Test
    @DisplayName("Should build config with all required fields")
    void shouldBuildConfigWithAllRequiredFields() {
        // 准备
        RewardCalculator calculator = mock(RewardCalculator.class);

        // 执行
        TrainingConfig config =
                TrainingConfig.builder()
                        .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                        .rewardCalculator(calculator)
                        .build();

        // 断言
        assertNotNull(config);
        assertEquals(TrainingTestConstants.TEST_TRINITY_ENDPOINT, config.getTrinityEndpoint());
        assertNotNull(config.getRewardCalculator());
    }

    @Test
    @DisplayName("Should throw exception when trinityEndpoint is null")
    void shouldThrowExceptionWhenTrinityEndpointIsNull() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingConfig.builder()
                                .rewardCalculator(mock(RewardCalculator.class))
                                .build());
    }

    @Test
    @DisplayName("Should throw exception when trinityEndpoint is empty")
    void shouldThrowExceptionWhenTrinityEndpointIsEmpty() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingConfig.builder()
                                .trinityEndpoint("")
                                .rewardCalculator(mock(RewardCalculator.class))
                                .build());
    }

    @Test
    @DisplayName("Should throw exception when rewardCalculator is null")
    void shouldThrowExceptionWhenRewardCalculatorIsNull() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingConfig.builder()
                                .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                                .build());
    }

    @Test
    @DisplayName("Should use default selectionStrategy when not specified")
    void shouldUseDefaultSelectionStrategyWhenNotSpecified() {
        // 执行
        TrainingConfig config =
                TrainingConfig.builder()
                        .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                        .rewardCalculator(mock(RewardCalculator.class))
                        .build();

        // 断言
        assertNotNull(config.getSelectionStrategy());
        assertTrue(config.getSelectionStrategy() instanceof SamplingRateStrategy);
        assertEquals(0.1, config.getSampleRate(), 0.001);
    }

    @Test
    @DisplayName("Should use custom selectionStrategy when specified")
    void shouldUseCustomSelectionStrategyWhenSpecified() {
        // 执行
        TrainingConfig config =
                TrainingConfig.builder()
                        .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                        .selectionStrategy(SamplingRateStrategy.of(0.5))
                        .rewardCalculator(mock(RewardCalculator.class))
                        .build();

        // 断言
        assertEquals(0.5, config.getSampleRate(), 0.001);
    }

    @Test
    @DisplayName("Should use ExplicitMarkingStrategy")
    void shouldUseExplicitMarkingStrategy() {
        // 执行
        TrainingConfig config =
                TrainingConfig.builder()
                        .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                        .selectionStrategy(ExplicitMarkingStrategy.create())
                        .rewardCalculator(mock(RewardCalculator.class))
                        .build();

        // 断言
        assertTrue(config.getSelectionStrategy() instanceof ExplicitMarkingStrategy);
        assertEquals(-1, config.getSampleRate(), 0.001);
    }

    @Test
    @DisplayName("Should use default commitIntervalSeconds")
    void shouldUseDefaultCommitIntervalSeconds() {
        // 执行
        TrainingConfig config =
                TrainingConfig.builder()
                        .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                        .rewardCalculator(mock(RewardCalculator.class))
                        .build();

        // 断言
        assertEquals(300, config.getCommitIntervalSeconds());
    }

    @Test
    @DisplayName("Should use custom commitIntervalSeconds")
    void shouldUseCustomCommitIntervalSeconds() {
        // 执行
        TrainingConfig config =
                TrainingConfig.builder()
                        .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                        .rewardCalculator(mock(RewardCalculator.class))
                        .commitIntervalSeconds(60)
                        .build();

        // 断言
        assertEquals(60, config.getCommitIntervalSeconds());
    }

    @Test
    @DisplayName("Should use default httpTimeout")
    void shouldUseDefaultHttpTimeout() {
        // 执行
        TrainingConfig config =
                TrainingConfig.builder()
                        .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                        .rewardCalculator(mock(RewardCalculator.class))
                        .build();

        // 断言
        assertEquals(Duration.ofSeconds(300), config.getHttpTimeout());
    }

    @Test
    @DisplayName("Should use custom httpTimeout")
    void shouldUseCustomHttpTimeout() {
        // 执行
        TrainingConfig config =
                TrainingConfig.builder()
                        .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                        .rewardCalculator(mock(RewardCalculator.class))
                        .httpTimeout(Duration.ofSeconds(60))
                        .build();

        // 断言
        assertEquals(Duration.ofSeconds(60), config.getHttpTimeout());
    }

    @Test
    @DisplayName("Should use default repeatTime")
    void shouldUseDefaultRepeatTime() {
        // 执行
        TrainingConfig config =
                TrainingConfig.builder()
                        .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                        .rewardCalculator(mock(RewardCalculator.class))
                        .build();

        // 断言
        assertEquals(1, config.getRepeatTime());
    }

    @Test
    @DisplayName("Should use custom repeatTime")
    void shouldUseCustomRepeatTime() {
        // 执行
        TrainingConfig config =
                TrainingConfig.builder()
                        .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                        .rewardCalculator(mock(RewardCalculator.class))
                        .repeatTime(3)
                        .build();

        // 断言
        assertEquals(3, config.getRepeatTime());
    }

    @Test
    @DisplayName("Should throw exception when repeatTime is less than 1")
    void shouldThrowExceptionWhenRepeatTimeLessThanOne() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingConfig.builder()
                                .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                                .rewardCalculator(mock(RewardCalculator.class))
                                .repeatTime(0)
                                .build());
    }

    @Test
    @DisplayName("Should use default shadowPoolSize")
    void shouldUseDefaultShadowPoolSize() {
        // 执行
        TrainingConfig config =
                TrainingConfig.builder()
                        .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                        .rewardCalculator(mock(RewardCalculator.class))
                        .build();

        // 断言
        assertEquals(10, config.getShadowPoolSize());
    }

    @Test
    @DisplayName("Should use custom shadowPoolSize")
    void shouldUseCustomShadowPoolSize() {
        // 执行
        TrainingConfig config =
                TrainingConfig.builder()
                        .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                        .rewardCalculator(mock(RewardCalculator.class))
                        .shadowPoolSize(20)
                        .build();

        // 断言
        assertEquals(20, config.getShadowPoolSize());
    }

    @Test
    @DisplayName("Should use default shadowPoolCapacity")
    void shouldUseDefaultShadowPoolCapacity() {
        // 执行
        TrainingConfig config =
                TrainingConfig.builder()
                        .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                        .rewardCalculator(mock(RewardCalculator.class))
                        .build();

        // 断言
        assertEquals(1000, config.getShadowPoolCapacity());
    }

    @Test
    @DisplayName("Should use default trinityApiKey")
    void shouldUseDefaultTrinityApiKey() {
        // 执行
        TrainingConfig config =
                TrainingConfig.builder()
                        .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                        .rewardCalculator(mock(RewardCalculator.class))
                        .build();

        // 断言
        assertEquals("dummy", config.getTrinityApiKey());
    }

    @Test
    @DisplayName("Should use custom trinityApiKey")
    void shouldUseCustomTrinityApiKey() {
        // 执行
        TrainingConfig config =
                TrainingConfig.builder()
                        .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                        .trinityApiKey("custom-api-key")
                        .rewardCalculator(mock(RewardCalculator.class))
                        .build();

        // 断言
        assertEquals("custom-api-key", config.getTrinityApiKey());
    }

    @Test
    @DisplayName("Should use default modelName")
    void shouldUseDefaultModelName() {
        // 执行
        TrainingConfig config =
                TrainingConfig.builder()
                        .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                        .rewardCalculator(mock(RewardCalculator.class))
                        .build();

        // 断言
        assertEquals("training-model", config.getModelName());
    }

    @Test
    @DisplayName("Should use custom modelName")
    void shouldUseCustomModelName() {
        // 执行
        TrainingConfig config =
                TrainingConfig.builder()
                        .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                        .modelName("custom-model")
                        .rewardCalculator(mock(RewardCalculator.class))
                        .build();

        // 断言
        assertEquals("custom-model", config.getModelName());
    }

    @Test
    @DisplayName("Should use default enableAutoCommit")
    void shouldUseDefaultEnableAutoCommit() {
        // 执行
        TrainingConfig config =
                TrainingConfig.builder()
                        .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                        .rewardCalculator(mock(RewardCalculator.class))
                        .build();

        // 断言
        assertTrue(config.isEnableAutoCommit());
    }

    @Test
    @DisplayName("Should use custom enableAutoCommit")
    void shouldUseCustomEnableAutoCommit() {
        // 执行
        TrainingConfig config =
                TrainingConfig.builder()
                        .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                        .rewardCalculator(mock(RewardCalculator.class))
                        .enableAutoCommit(false)
                        .build();

        // 断言
        assertFalse(config.isEnableAutoCommit());
    }

    @Test
    @DisplayName("Should throw exception when sampleRate is invalid using deprecated method")
    @SuppressWarnings("deprecation")
    void shouldThrowExceptionWhenSampleRateIsInvalid() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingConfig.builder()
                                .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                                .rewardCalculator(mock(RewardCalculator.class))
                                .sampleRate(-0.1)
                                .build());

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingConfig.builder()
                                .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                                .rewardCalculator(mock(RewardCalculator.class))
                                .sampleRate(1.1)
                                .build());
    }
}
