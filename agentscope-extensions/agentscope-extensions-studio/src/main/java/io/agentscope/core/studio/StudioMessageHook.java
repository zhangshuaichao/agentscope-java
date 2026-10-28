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
package io.agentscope.core.studio;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 自动向 Studio 转发智能体消息的钩子。
 * <p>拦截智能体产生响应后的 PostCallEvent，将输出消息发送到 Studio 可视化。转发失败仅记录错误，不中断智能体执行。
 * <p>用法：
 * <pre>{@code
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(model)
 *     .hook(new StudioMessageHook(studioClient))
 *     .build();
 * }</pre>
 */
public class StudioMessageHook implements Hook {
    private static final Logger logger = LoggerFactory.getLogger(StudioMessageHook.class);

    private final StudioClient studioClient;

    /**
     * 创建 Studio 消息钩子。
     * @param studioClient 用于发送消息的 Studio HTTP 客户端
     */
    public StudioMessageHook(StudioClient studioClient) {
        this.studioClient = studioClient;
    }

    /**
     * 处理钩子事件。监听 PostCallEvent 并转发智能体输出消息到 Studio，其他事件类型原样传递。
     * @param event 待处理钩子事件
     * @param <T> 事件类型
     * @return 发出事件的 Mono，可能在异步处理后发出
     */
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostCallEvent) {
            PostCallEvent e = (PostCallEvent) event;
            // 获取智能体输出消息
            Msg msg = e.getFinalMessage();

            // 检查 Studio 客户端是否可用
            if (studioClient == null) {
                logger.warn(
                        "StudioMessageHook has null studioClient. Initialize StudioManager before"
                            + " creating hooks, or remove this hook from the agent configuration."
                            + " Skipping message forwarding.");
                return Mono.just(event);
            }

            // 异步发送到 Studio，发出后不等待
            // 即使 Studio 不可用，也不阻塞智能体执行
            return studioClient
                    .pushMessage(msg)
                    .thenReturn(event)
                    .onErrorResume(
                            ex -> {
                                // 记录错误，但不使智能体执行失败
                                logger.error("Failed to push message to Studio", ex);
                                return Mono.just(event);
                            });
        }
        return Mono.just(event);
    }
}
