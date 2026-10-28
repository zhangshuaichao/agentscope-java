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
package io.agentscope.claw2.web.toolbus;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 用于 HarnessAgent 钩子发出的工具调用事件的单例内存事件总线。
 *
 * <p>消费者经 {@link #subscribe(String)} 订阅并按会话键过滤；发布方在工具即将执行时从钩子调用 {@link #publish(ToolEvent)}。
 */
@Component
public class ToolEventBus {

    private final Sinks.Many<ToolEvent> sink =
            Sinks.many().multicast().onBackpressureBuffer(256, false);

    /** 向当前订阅者发布工具调用事件。 */
    public void publish(ToolEvent event) {
        // 【学习提示】这是进程内有限缓冲广播，不是持久化会话日志；此处忽略发送失败结果且不重试。
        // sessionKey 过滤是分流条件，不会验证订阅者身份，也不能保证重连后补齐所有工具事件。
        sink.tryEmitNext(event);
    }

    /** 返回仅包含指定会话键事件的 {@link Flux}；调用方应管理其生命周期，例如收到信号后结束订阅。 */
    public Flux<ToolEvent> subscribe(String sessionKey) {
        return sink.asFlux().filter(e -> sessionKey.equals(e.sessionKey()));
    }

    /**
     * 单个工具调用或工具结果事件。
     *
     * @param sessionKey 产生事件的会话键
     * @param eventType {@code TOOL_CALL} 或 {@code TOOL_RESULT}
     * @param toolName 工具名称
     * @param data 附加事件数据，包含输入参数或结果文本
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
