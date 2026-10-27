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
package io.agentscope.core.shutdown;

import io.agentscope.core.agent.RunControl;
import io.agentscope.core.interruption.InterruptSource;
import io.agentscope.core.state.AgentState;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单个活跃请求的运行上下文。
 *
 * <p>所属 {@code call()} 解析出对应 (userId, sessionId) 的 {@link AgentState}
 * 槽位后，通过 {@link #bindState(AgentState)} 将状态绑定到此处，使关闭中断与状态保存
 * 精确针对该会话；即使同一智能体实例服务多个会话，也能保证并发安全。
 * 排队中的执行尚未绑定状态，取消时不会读取或保存其他执行的状态。
 */
final class ActiveRequestContext {

    private static final Logger log = LoggerFactory.getLogger(ActiveRequestContext.class);

    private final String requestId;
    private final RunControl control;
    private final AtomicBoolean shutdownInterruptIssued = new AtomicBoolean(false);

    private final ShutdownStateSaver saver;

    /**
     * 此请求运行所使用的调用级会话状态；在 {@code call()} 解析出对应槽位后绑定。
     * 排队时为 null：不会为该请求保存对话状态。
     */
    private volatile AgentState boundState;

    ActiveRequestContext(String requestId, ShutdownStateSaver saver, RunControl control) {
        this.requestId = requestId;
        this.control = control;
        this.saver = saver;
    }

    String getRequestId() {
        return requestId;
    }

    /**
     * 绑定为此请求解析出的调用级会话状态，使中断与保存精确针对
     * {@code (userId, sessionId)} 会话。由 {@code AgentBase} 在激活调用槽位后调用。
     */
    void bindState(AgentState state) {
        if (state != null) {
            this.boundState = state;
        }
    }

    private AgentState resolveState() {
        return boundState;
    }

    void saveState() {
        AgentState state = resolveState();
        if (state == null) return;
        // 原生会话通过所属记录器的最终检查点持久化此标记。
        // 缺少独立状态保存器时，也不能抑制运行时中断标记。
        state.setShutdownInterrupted(true);
        if (saver == null) return;
        try {
            saver.save(state);
        } catch (Exception e) {
            log.warn("Failed to save agent state for request {}", requestId, e);
        }
    }

    boolean interruptForShutdown() {
        if (!shutdownInterruptIssued.compareAndSet(false, true)) {
            return false;
        }
        control.interrupt(InterruptSource.SYSTEM, null);
        return true;
    }
}
