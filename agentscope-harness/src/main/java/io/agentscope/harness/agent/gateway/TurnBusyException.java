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
 * 另一个轮次持有锁、导致无法获取会话轮次门控时抛出。
 *
 * <p>{@link HarnessGateway} 将它映射为空 {@link reactor.core.publisher.Mono}，
 * 因此同会话的重复唤醒或并发入站请求被跳过，而不是排队。
 */
public final class TurnBusyException extends Exception {

    private final String gateKey;

    /**
     * 为指定门控键创建异常。
     *
     * @param gateKey 正处于繁忙状态的规范会话门控键
     */
    public TurnBusyException(String gateKey) {
        super("Session turn gate busy: " + gateKey);
        this.gateKey = gateKey;
    }

    /**
     * 返回无法获取的门控键。
     *
     * @return 繁忙门控键
     */
    public String getGateKey() {
        return gateKey;
    }
}
