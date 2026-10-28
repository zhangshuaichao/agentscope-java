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
package io.agentscope.claw2.runtime.session;

/**
 * 子智能体／主任务的调度通道。
 *
 * <p>AgentStateStore 串行化（每个 {@code sessionKey} 同时一个嵌入式运行）另由逐会话锁实现；调度通道限制跨会话的<strong>全局</strong>并发，例如单个会话管理器实例内并行子智能体运行上限。
 */
public enum CommandLane {

    /**
     * 面向主要用户的默认智能体轮次。
     */
    MAIN,

    /**
     * 后台子智能体运行（{@code sessions_spawn} 子任务），共享会话管理器实例内的信号量，使多个子智能体可推进而不会无限并行。
     */
    SUBAGENT,

    /**
     * 嵌套／内部跳转，不应占据父调度通道名额，以免分层调度死锁。仅少量用于不应计入子智能体池的后续工作。
     */
    NESTED
}
