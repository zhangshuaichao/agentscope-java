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
package io.agentscope.harness.coding.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 对所有线程执行全局模型调用次数限制的中间件。
 *
 * <p>总模型调用数超出配置限制时终止运行，默认上限 5000。
 */
public class ModelCallLimitMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ModelCallLimitMiddleware.class);

    private final int globalLimit;
    private final AtomicLong totalCalls = new AtomicLong(0);

    public ModelCallLimitMiddleware(int globalLimit) {
        this.globalLimit = globalLimit;
    }

    public ModelCallLimitMiddleware() {
        this(5000);
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        long count = totalCalls.incrementAndGet();
        if (count > globalLimit) {
            log.warn(
                    "[model-call-limit-middleware] Global model call limit reached: {}/{}",
                    count,
                    globalLimit);
            return Flux.error(
                    new RuntimeException(
                            "Global model call limit exceeded: " + count + "/" + globalLimit));
        }
        return next.apply(input);
    }

    public long getTotalCalls() {
        return totalCalls.get();
    }
}
