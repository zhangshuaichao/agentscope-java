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

package io.agentscope.core.training.reward;

import io.agentscope.core.agent.Agent;

/**
 * 奖励计算接口，根据影子智能体的执行结果计算奖励。
 */
public interface RewardCalculator {

    /**
     * 根据执行结果计算奖励。
     * @param agent 影子智能体
     * @return 奖励值，通常介于 -1 与 1 之间
     */
    double calculate(Agent agent);
}
