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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Random;
import reactor.util.context.ContextView;

/**
 * 按固定概率随机抽样训练请求的策略。
 * <h2>示例</h2>
 * <pre>{@code
 * // 10% 采样率
 * TrainingSelectionStrategy strategy = SamplingRateStrategy.of(0.1);
 *
 * // 50% 采样率
 * TrainingSelectionStrategy strategy = SamplingRateStrategy.of(0.5);
 * }</pre>
 */
public class SamplingRateStrategy implements TrainingSelectionStrategy {

    private final double sampleRate;
    private final Random random;

    private SamplingRateStrategy(double sampleRate) {
        if (sampleRate < 0 || sampleRate > 1) {
            throw new IllegalArgumentException("Sample rate must be between 0 and 1");
        }
        this.sampleRate = sampleRate;
        this.random = new Random();
    }

    /**
     * 创建采样率策略。
     * @param sampleRate 采样率，范围 [0, 1]
     * @return 策略实例
     */
    public static SamplingRateStrategy of(double sampleRate) {
        return new SamplingRateStrategy(sampleRate);
    }

    @Override
    public SelectionDecision shouldSelect(
            Agent agent, List<Msg> inputMessages, Msg outputMessage, ContextView reactorContext) {

        if (random.nextDouble() < sampleRate) {
            return SelectionDecision.accept("sampling-rate", "sampled");
        }

        return SelectionDecision.reject("not-sampled");
    }

    @Override
    public int priority() {
        return 200; // 优先级较低，通常作为回退策略。
    }

    @Override
    public String name() {
        return "SamplingRate(" + sampleRate + ")";
    }

    public double getSampleRate() {
        return this.sampleRate;
    }
}
