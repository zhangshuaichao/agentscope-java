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
package io.agentscope.harness.agent.gateway;

/**
 * 代表已获取会话轮次槽位的句柄。
 *
 * <p>由 {@link SessionTurnGate#acquire(String)} 返回。轮次结束后，网关在
 * {@code doFinally} 中关闭租约。实现必须幂等：多次调用 {@link #close()} 必须安全。
 */
public interface TurnLease extends AutoCloseable {

    /**
     * 释放此租约持有的轮次槽位。
     *
     * <p>不得抛出异常。释放期间的错误应由实现内部记录日志。
     */
    @Override
    void close();
}
