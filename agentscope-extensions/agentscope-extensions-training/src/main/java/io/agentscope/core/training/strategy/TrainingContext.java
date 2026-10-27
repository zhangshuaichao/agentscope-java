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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * 提供智能体调用的训练标记 API，通过 Reactor Context 跨线程传播标记。
 *
 * <p>【学习提示】mark 返回上下文变换函数，必须挂到订阅链的 contextWrite 才生效；
 * 单独调用 mark 不会修改全局状态或 ThreadLocal。标记是否被策略接受还受 TTL 等条件约束。
 *
 * <h2>示例</h2>
 * <h3>基本标记</h3>
 * <pre>{@code
 * Msg response = agent.call(msg)
 *     .contextWrite(TrainingContext.mark())
 *     .block();
 * }</pre>
 * <h3>带标签</h3>
 * <pre>{@code
 * Msg response = agent.call(msg)
 *     .contextWrite(TrainingContext.mark("high-quality", "production"))
 *     .block();
 * }</pre>
 * <h3>带元数据</h3>
 * <pre>{@code
 * Msg response = agent.call(msg)
 *     .contextWrite(TrainingContext.mark(
 *         List.of("important"),
 *         Map.of("user_id", "12345", "session_id", "abc")
 *     ))
 *     .block();
 * }</pre>
 */
public class TrainingContext {

    private static final Logger logger = LoggerFactory.getLogger(TrainingContext.class);

    /**
     * Reactor Context 中保存训练标记的键。
     */
    public static final String REACTOR_KEY = "agentscope.training.marker";

    /**
     * 标记当前智能体调用用于训练。
     * <p>返回 Reactor 上下文写入函数，必须通过 {@code .contextWrite()} 使用。
     * <h4>示例</h4>
     * <pre>{@code
     * Msg response = agent.call(msg)
     *     .contextWrite(TrainingContext.mark())
     *     .block();
     * }</pre>
     * @return Reactor 上下文写入函数
     */
    public static Function<Context, Context> mark() {
        TrainingAnnotation annotation = TrainingAnnotation.enabled();
        logger.info("Creating training marker: annotation={}", annotation);
        return ctx -> ctx.put(REACTOR_KEY, annotation);
    }

    /**
     * 用标签标记当前调用用于训练。
     * <p>返回 Reactor 上下文写入函数，必须通过 {@code .contextWrite()} 使用。
     * <h4>示例</h4>
     * <pre>{@code
     * Msg response = agent.call(msg)
     *     .contextWrite(TrainingContext.mark("important", "production"))
     *     .block();
     * }</pre>
     * @param labels 训练标签
     * @return Reactor 上下文写入函数
     */
    public static Function<Context, Context> mark(String... labels) {
        TrainingAnnotation annotation = TrainingAnnotation.withLabels(labels);
        logger.info(
                "Creating training marker with labels: labels={}, annotation={}",
                Arrays.asList(labels),
                annotation);
        return ctx -> ctx.put(REACTOR_KEY, annotation);
    }

    /**
     * 用标签和元数据标记当前调用用于训练。
     * <p>返回 Reactor 上下文写入函数，必须通过 {@code .contextWrite()} 使用。
     * <h4>示例</h4>
     * <pre>{@code
     * Msg response = agent.call(msg)
     *     .contextWrite(TrainingContext.mark(
     *         List.of("important"),
     *         Map.of("user_id", "123")
     *     ))
     *     .block();
     * }</pre>
     * @param labels 训练标签
     * @param metadata 元数据
     * @return Reactor 上下文写入函数
     */
    public static Function<Context, Context> mark(
            List<String> labels, Map<String, Object> metadata) {
        TrainingAnnotation annotation = TrainingAnnotation.withLabelsAndMetadata(labels, metadata);
        logger.info(
                "Creating training marker with labels and metadata: labels={}, metadata={}",
                labels,
                metadata);
        return ctx -> ctx.put(REACTOR_KEY, annotation);
    }

    /**
     * 仅用元数据标记当前调用用于训练。
     * @param metadata 元数据
     * @return Reactor 上下文写入函数
     */
    public static Function<Context, Context> mark(Map<String, Object> metadata) {
        return mark(Collections.emptyList(), metadata);
    }

    /**
     * 从 Reactor Context 读取训练标记。
     * @param reactorContext Reactor 上下文
     * @return 存在时返回 TrainingAnnotation，否则为 null
     */
    public static TrainingAnnotation getCurrent(ContextView reactorContext) {
        if (reactorContext != null && reactorContext.hasKey(REACTOR_KEY)) {
            TrainingAnnotation annotation = reactorContext.get(REACTOR_KEY);
            logger.trace("Retrieved annotation from Reactor Context: {}", annotation);
            return annotation;
        }
        return null;
    }

    /**
     * 根据 TTL 检查标记是否过期。
     * @param annotation 待检查标记
     * @param ttlMillis 存活时间，单位毫秒
     * @return 过期或标记为 null 时为 true，否则为 false
     */
    static boolean isExpired(TrainingAnnotation annotation, long ttlMillis) {
        return annotation == null || annotation.isExpired(ttlMillis);
    }
}
