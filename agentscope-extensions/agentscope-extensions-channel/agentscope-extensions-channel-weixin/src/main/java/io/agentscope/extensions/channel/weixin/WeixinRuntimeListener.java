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
package io.agentscope.extensions.channel.weixin;

/**
 * 接收技术层运行时观察，不强加宿主产品生命周期。
 */
public interface WeixinRuntimeListener {

    default void onLeaseAcquired(String accountId, WeixinLease lease) {}

    default void onRunning(String accountId) {}

    default void onStopped(String accountId) {}

    default void onCredentialRejected(String accountId, String reason) {}

    default void onTransientFailure(String accountId, String reason) {}

    /**
     * 服务方拒绝的回复。智能体已经运行，因此此投递失败通知不会重放入站消息；宿主报告失败，由其投递队列决定后续处理。
     */
    default void onDeliveryFailed(String accountId, String reason) {}

    /**
     * 已接受但持续分发失败的入站消息，因此放弃而非无限重试。与 {@link #onDeliveryFailed} 不同，智能体可能从未完成。
     */
    default void onDispatchFailed(String accountId, String reason) {}

    default void onRecovered(String accountId) {}

    static WeixinRuntimeListener noOp() {
        return new WeixinRuntimeListener() {};
    }
}
