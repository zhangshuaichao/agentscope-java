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
package io.agentscope.harness.coding.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 遇到可重试错误时回退到次选模型的 {@link Model} 装饰器。
 *
 * <p>透明地使用次选模型重试调用。
 *
 * <p>可重试错误按消息包含 {@code "overloaded"}、{@code "rate_limit"}、{@code "529"}、"too_many_requests" 或 "capacity" 判断；并不直接识别 {@code RateLimitException} 或 {@code OverloadedException} 类型。
 *
 * <p>【学习提示】onErrorResume 不能撤回已经向下游发出的主模型流片段，失败后次选模型从原请求重新开始，可能形成部分主输出加次选输出。当前仅按异常消息子串判断可重试，并不按异常类型匹配；这只是示例故障切换，不保证跨模型输出等价或工具调用去重。
 */
public class FallbackModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(FallbackModel.class);

    private final Model primary;
    private final Model secondary;

    public FallbackModel(Model primary, Model secondary) {
        this.primary = primary;
        this.secondary = secondary;
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return primary.stream(messages, tools, options)
                .onErrorResume(
                        e -> isRetryable(e),
                        e -> {
                            log.warn(
                                    "[fallback-model] Primary model '{}' failed with retryable"
                                            + " error: {}. Falling back to '{}'.",
                                    primary.getModelName(),
                                    e.getMessage(),
                                    secondary.getModelName());
                            return secondary.stream(messages, tools, options);
                        });
    }

    @Override
    public String getModelName() {
        return primary.getModelName() + "→" + secondary.getModelName();
    }

    private static boolean isRetryable(Throwable t) {
        if (t == null) return false;
        String msg = t.getMessage() != null ? t.getMessage().toLowerCase() : "";
        return msg.contains("rate_limit")
                || msg.contains("overloaded")
                || msg.contains("529")
                || msg.contains("too_many_requests")
                || msg.contains("capacity");
    }
}
