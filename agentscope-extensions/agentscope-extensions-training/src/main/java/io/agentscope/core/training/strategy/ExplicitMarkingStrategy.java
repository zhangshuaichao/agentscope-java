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
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.util.context.ContextView;

/**
 * 基于用户显式标记的训练筛选策略，仅在 {@link TrainingContext} 有有效标记时选择训练。
 * <h2>原始用法示例</h2>
 * <p>注意：以下原样保留的上游示例使用历史 markForTraining API；当前 TrainingContext 应通过 mark 与 contextWrite 使用，见该类示例。
 * <pre>{@code
 * // 创建策略
 * TrainingSelectionStrategy strategy = ExplicitMarkingStrategy.create();
 *
 * // 在用户代码中设置标记（此示例为历史 API，见上方说明）
 * TrainingContext.markForTraining("high-quality");
 * agent.call(msg).block();  // 历史示例意图为训练此调用；当前用法需 contextWrite
 * }</pre>
 */
public class ExplicitMarkingStrategy implements TrainingSelectionStrategy {
    private static final Logger logger = LoggerFactory.getLogger(ExplicitMarkingStrategy.class);

    private final Duration ttl;

    private ExplicitMarkingStrategy(Duration ttl) {
        this.ttl = ttl;
    }

    /**
     * 创建显式标记策略。
     * @return 策略实例
     */
    public static ExplicitMarkingStrategy create() {
        return new ExplicitMarkingStrategy(Duration.ofMinutes(1));
    }

    /**
     * 创建指定 TTL 的显式标记策略。
     * @param ttl 标记有效期
     * @return 策略实例
     */
    public static ExplicitMarkingStrategy withTTL(Duration ttl) {
        return new ExplicitMarkingStrategy(ttl);
    }

    @Override
    public SelectionDecision shouldSelect(
            Agent agent, List<Msg> inputMessages, Msg outputMessage, ContextView reactorContext) {

        // 从 TrainingContext 获取标记。
        TrainingAnnotation annotation = TrainingContext.getCurrent(reactorContext);

        // 添加详细调试日志。
        if (annotation == null) {
            logger.info(
                    "ExplicitMarkingStrategy: No annotation found for agent={}, threadId={}",
                    agent.getName(),
                    Thread.currentThread().getId());
            return SelectionDecision.reject("no-explicit-marking");
        }

        logger.info(
                "ExplicitMarkingStrategy: Found annotation for agent={}, enabled={}, labels={},"
                        + " threadId={}",
                agent.getName(),
                annotation.isEnabled(),
                annotation.getLabels(),
                Thread.currentThread().getId());

        if (annotation.isEnabled()) {
            // 检查是否过期。
            if (annotation.isExpired(ttl.toMillis())) {
                logger.warn(
                        "ExplicitMarkingStrategy: Annotation expired for agent={}, age={}ms,"
                                + " ttl={}ms",
                        agent.getName(),
                        System.currentTimeMillis() - annotation.getTimestamp(),
                        ttl.toMillis());
                return SelectionDecision.reject("explicit-marking-expired");
            }

            // 合并元数据，将用户指定的 taskId 向下传递。
            Map<String, Object> metadata = new HashMap<>();
            if (annotation.getMetadata() != null) {
                metadata.putAll(annotation.getMetadata());
            }

            // 用户指定 taskId 时，将其加入元数据。
            if (annotation.getTaskId() != null) {
                metadata.put("taskId", annotation.getTaskId());
            }

            // 提取标签与元数据。
            logger.info(
                    "ExplicitMarkingStrategy: Accepting training for agent={}, labels={}",
                    agent.getName(),
                    annotation.getLabels());
            return SelectionDecision.accept("explicit-marking", annotation.getLabels(), metadata);
        }

        // annotation == null 已在前面处理，不会到达此处。
        return SelectionDecision.reject("no-explicit-marking");
    }

    @Override
    public int priority() {
        return 10; // 优先级较高，显式标记优先。
    }

    @Override
    public String name() {
        return "ExplicitMarking";
    }
}
