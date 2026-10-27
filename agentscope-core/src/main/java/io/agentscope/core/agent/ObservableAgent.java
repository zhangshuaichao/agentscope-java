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
package io.agentscope.core.agent;

import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * 能够观察消息而不生成回复的智能体接口。
 *
 * <p>允许智能体接收并处理来自其他智能体或环境的消息而不响应，
 * 常用于智能体需要了解彼此行动的多智能体协作场景。
 *
 * <p>用途包括：
 * <ul>
 * <li>被动监测对话流程</li>
 * <li>在多智能体系统中建立共享上下文</li>
 * <li>在智能体管道中实现观察者模式</li>
 * </ul>
 */
public interface ObservableAgent {

    /**
     * 观察单条消息但不生成回复。
     *
     * @param msg 要观察的消息
     * @return 观察完成时结束的 Mono
     */
    Mono<Void> observe(Msg msg);

    /**
     * 观察多条消息但不生成回复。
     *
     * @param msgs 要观察的消息列表
     * @return 全部观察完成时结束的 Mono
     */
    Mono<Void> observe(List<Msg> msgs);
}
