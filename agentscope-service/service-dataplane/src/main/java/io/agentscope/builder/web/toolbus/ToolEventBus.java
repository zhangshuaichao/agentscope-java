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
package io.agentscope.builder.web.toolbus;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 用于 HarnessAgent 钩子发出工具调用事件的单例内存事件总线。
 * <p>消费者通过 {@link #subscribe(String)} 按会话键订阅。工具即将执行时，发布者在钩子中调用 {@link #publish(ToolEvent)}。
 */
@Component
public class ToolEventBus {

    private final Sinks.Many<ToolEvent> sink =
            Sinks.many().multicast().onBackpressureBuffer(256, false);

    /**
     * 向所有当前订阅者发布工具调用事件。
     */
    public void publish(ToolEvent event) {
        sink.tryEmitNext(event);
    }

    /**
     * 返回按给定会话键过滤事件的 {@link Flux}。调用方应管理流生命周期，例如在信号到来时结束。
     */
    public Flux<ToolEvent> subscribe(String sessionKey) {
        return sink.asFlux().filter(e -> sessionKey.equals(e.sessionKey()));
    }

    /**
     * 单个工具调用或工具结果事件。
     * @param sessionKey 产生事件的会话键
     * @param eventType {@code TOOL_CALL} 或 {@code TOOL_RESULT}
     * @param toolName 工具名称
     * @param data 附加事件数据（输入参数或结果文本）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolEvent(
            String sessionKey, String eventType, String toolName, Map<String, Object> data) {

        public static ToolEvent toolCall(
                String sessionKey, String toolName, Map<String, Object> input) {
            return new ToolEvent(sessionKey, "TOOL_CALL", toolName, input);
        }
    }
}
