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

package io.agentscope.core.a2a.agent.event;

import org.a2aproject.sdk.client.ClientEvent;

/**
 * 处理不同 {@link ClientEvent} 的处理器接口。
 */
public interface ClientEventHandler<T extends ClientEvent> {

    /**
     * 获取处理的事件类。
     *
     * @return 处理的事件类
     */
    Class<T> getHandleEventType();

    /**
     * 按事件类型处理 {@link ClientEvent}。
     *
     * @param event 来自 A2A 服务器的客户端事件
     * @param context 客户端事件上下文
     */
    void handle(T event, ClientEventContext context);
}
