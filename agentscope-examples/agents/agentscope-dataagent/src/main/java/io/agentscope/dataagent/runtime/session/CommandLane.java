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
package io.agentscope.dataagent.runtime.session;

/** 子智能体/主任务的调度通道，对齐 OpenClaw 网关 {@code CommandLane} 语义。<p>每个 {@code sessionKey} 同时只执行一个嵌入式运行的状态串行化，由单独的逐会话锁实现；调度通道限制跨会话的<strong>全局</strong>并发数（例如整个进程内并行子智能体数量）。 */
public enum CommandLane {

    /** 主要面向用户的默认智能体轮次。 */
    MAIN,

    /** 后台子智能体运行（{@code sessions_spawn} 子任务）。共享进程级信号量，支持多个子智能体推进而不产生无界并发。 */
    SUBAGENT,

    /** 嵌套/内部跳转，不占用父任务调度槽位（避免分层调度死锁）。谨慎用于不应计入子智能体池的后续任务。 */
    NESTED
}
