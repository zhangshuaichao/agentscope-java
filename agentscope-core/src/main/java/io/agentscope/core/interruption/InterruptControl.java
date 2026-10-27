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
package io.agentscope.core.interruption;

import io.agentscope.core.message.Msg;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 仅在运行时存在的协作式中断信号，归属于单次执行，而非会话。 */
public final class InterruptControl {

    private final AtomicBoolean flag = new AtomicBoolean(false);
    private final AtomicReference<Msg> userMessage = new AtomicReference<>(null);
    private final AtomicReference<InterruptSource> source =
            new AtomicReference<>(InterruptSource.USER);

    /**
     * 发出中断信号。
     *
     * @param src 中断来源；{@code null} 按 {@link InterruptSource#USER} 处理
     * @param msg 与中断关联的可选用户消息（为 {@code null} 时忽略）
     */
    public void trigger(InterruptSource src, Msg msg) {
        this.source.set(src != null ? src : InterruptSource.USER);
        if (msg != null) {
            this.userMessage.set(msg);
        }
        this.flag.set(true);
    }

    /** @return 此次执行已中断且尚未重置时返回 {@code true} */
    public boolean isInterrupted() {
        return flag.get();
    }

    /** @return 当前中断来源 */
    public InterruptSource getSource() {
        return source.get();
    }

    /** @return 与中断关联的用户消息，或 {@code null} */
    public Msg getUserMessage() {
        return userMessage.get();
    }

    /** 清除中断信号，恢复为初始的未中断状态。 */
    public void reset() {
        flag.set(false);
        userMessage.set(null);
        source.set(InterruptSource.USER);
    }

    /** 根据当前信号构建 {@link InterruptContext} 快照。 */
    public InterruptContext toContext() {
        return InterruptContext.builder()
                .source(source.get())
                .userMessage(userMessage.get())
                .build();
    }
}
