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
import reactor.util.context.ContextView;

/**
 * 训练请求筛选策略：统一决定哪些智能体调用用于训练。
 * <p>可实现显式标记、按概率随机采样或 AND／OR 组合等筛选方式；具体组合行为由实现决定。
 * @see SamplingRateStrategy
 * @see ExplicitMarkingStrategy
 */
@FunctionalInterface
public interface TrainingSelectionStrategy {

    /**
     * 决定当前智能体调用是否用于训练。
     * @param agent 被调用的智能体
     * @param inputMessages 输入消息列表
     * @param outputMessage 输出消息
     * @param reactorContext 异步场景使用的 Reactor 上下文
     * @return 是否训练及相关元数据的选择结果
     */
    SelectionDecision shouldSelect(
            Agent agent, List<Msg> inputMessages, Msg outputMessage, ContextView reactorContext);

    /**
     * 策略优先级，数值越小优先级越高。
     * <p>组合多个策略时可先执行高优先级策略。
     * @return 优先级，默认 100
     */
    default int priority() {
        return 100;
    }

    /**
     * 用于日志和调试的策略名称。
     * @return 策略名称
     */
    default String name() {
        return this.getClass().getSimpleName();
    }
}
