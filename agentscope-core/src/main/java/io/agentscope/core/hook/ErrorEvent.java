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
package io.agentscope.core.hook;

import io.agentscope.core.agent.Agent;
import java.util.Objects;

/**
 * 智能体执行过程中出错时触发的事件。
 *
 * <p><b>可修改：</b>否（仅通知）
 *
 * <p><b>上下文：</b>
 * <ul>
 *   <li>{@link #getAgent()}：智能体实例</li>
 *   <li>{@link #getMemory()}：智能体记忆</li>
 *   <li>{@link #getError()}：发生的错误</li>
 * </ul>
 *
 * <p><b>用途：</b>
 * <ul>
 *   <li>结合上下文记录错误</li>
 *   <li>发送错误通知</li>
 *   <li>收集错误指标</li>
 *   <li>实现自定义错误处理</li>
 * </ul>
 *
 * @deprecated 自 2.0.0 起弃用。请改用 {@link io.agentscope.core.middleware.MiddlewareBase}。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public final class ErrorEvent extends HookEvent {

    private final Throwable error;

    /**
     * ErrorEvent 的构造器。
     *
     * @param agent 智能体实例（不可为 null）
     * @param error 发生的错误（不可为 null）
     * @throws NullPointerException 当 agent 或 error 为 null 时抛出
     */
    public ErrorEvent(Agent agent, Throwable error) {
        super(HookEventType.ERROR, agent);
        this.error = Objects.requireNonNull(error, "error cannot be null");
    }

    /**
     * 获取发生的错误。
     *
     * @return 错误
     */
    public Throwable getError() {
        return error;
    }
}
