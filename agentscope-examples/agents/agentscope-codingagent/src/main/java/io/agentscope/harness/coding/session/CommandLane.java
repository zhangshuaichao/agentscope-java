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
package io.agentscope.harness.coding.session;

/**
 * 子智能体/主任务的调度通道，对齐 OpenClaw 网关 {@code CommandLane} 语义。
 *
 * <p>AgentStateStore 串行化（每个 {@code sessionKey} 同一时刻仅一个内嵌运行）由逐会话锁单独实现；调度通道限制跨会话的<strong>全局</strong>并发，例如限制整个进程并行子智能体数。
 */
public enum CommandLane {

    /**
     * 面向用户的主智能体/默认轮次。
     */
    MAIN,

    /**
     * 后台子智能体运行，即 {@code sessions_spawn} 子任务。共享进程级信号量，使多个子智能体可推进但并行度有界。
     */
    SUBAGENT,

    /**
     * 不得占用父调度槽位的嵌套/内部跳转，避免分层调度死锁。谨慎用于不应计入子智能体池的后续工作。
     */
    NESTED
}
