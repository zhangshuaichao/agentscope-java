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
package io.agentscope.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import java.util.LinkedHashMap;
import java.util.function.Consumer;
import reactor.core.publisher.Mono;

/** 在所有模型调用中间件完成转换后，执行最终的异步请求准备。 */
@FunctionalInterface
public interface ModelRequestPreparer {
    String MANIFEST_ATTRIBUTE_PREFIX = "agentscope.model_request_manifest.";
    String BUILD_EVENT_NAME = "context_build";

    /** 发出一个独立的构建结果，包括在调用任何服务商之前就被拒绝的情况。 */
    default Mono<ModelCallInput> prepareObserved(
            Agent agent,
            RuntimeContext context,
            ModelCallInput input,
            String callId,
            Purpose purpose,
            Consumer<AgentEvent> events) {
        // 【学习提示】把清理旧清单与请求准备放在订阅阶段，使每次重试都重新构建。
        // 内层 defer 也让 prepare 同步抛出的异常进入响应式错误通道，供 doOnError 观察。
        return Mono.defer(
                () -> {
                    RuntimeContext rc = context == null ? RuntimeContext.empty() : context;
                    String key = MANIFEST_ATTRIBUTE_PREFIX + callId;
                    rc.put(key, null);
                    return Mono.defer(() -> prepare(agent, rc, input, callId, purpose))
                            .switchIfEmpty(
                                    Mono.error(
                                            new IllegalStateException(
                                                    "Model request preparer returned no request")))
                            .doOnSuccess(result -> emitBuild(events, rc, callId, purpose, null))
                            .doOnError(
                                    error -> {
                                        emitBuild(events, rc, callId, purpose, error);
                                        rc.put(key, null);
                                    });
                });
    }

    private static void emitBuild(
            Consumer<AgentEvent> events,
            RuntimeContext rc,
            String callId,
            Purpose purpose,
            Throwable error) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("model_call_id", callId);
        payload.put("purpose", purpose.name());
        payload.put("status", error == null ? "passed" : "failed");
        Object manifest = rc.get(MANIFEST_ATTRIBUTE_PREFIX + callId);
        if (manifest != null) payload.put("context_manifest", manifest);
        // 异常文本可能包含源内容，因此只记录异常类型。
        if (error != null) payload.put("error_type", error.getClass().getSimpleName());
        events.accept(new CustomEvent(BUILD_EVENT_NAME, payload));
    }

    default boolean handlesTaskProjection() {
        return false;
    }

    /** 每次订阅（包括重试）都会重新调用。不得在此直接调用主模型。 */
    Mono<ModelCallInput> prepare(
            Agent agent,
            RuntimeContext context,
            ModelCallInput input,
            String callId,
            Purpose purpose);

    enum Purpose {
        REASONING,
        SUMMARY
    }
}
