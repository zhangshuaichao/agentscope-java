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
package io.agentscope.harness.agent.subagent;

/**
 * 当父级无法在本次调用中将决定转交给交互式用户时，
 * 用于处理远程子智能体工具确认（HITL）请求的策略。
 */
public enum RemoteAskPolicy {

    /**
     * 自动拒绝所有待处理的远程确认（安全默认值）。父级调用没有实时事件流消费者，
     * 或者显式配置此策略时应用。
     */
    DENY,

    /**
     * 通过父级 {@code AgentEventEmitter} 流向上游转发确认请求，而非自动拒绝，
     * 使交互式消费者能够作出决定。
     */
    PROPAGATE
}
