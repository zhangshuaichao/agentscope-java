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
package io.agentscope.spring.boot.admin.metrics;

/**
 * 单个分组（智能体或模型）的累计用量不可变快照。
 *
 * <p>{@code totalTokens} 对应 {@link io.agentscope.core.model.ChatUsage#getTotalTokens()}；两项均上报时为 {@code inputTokens + outputTokens}，部分服务方只上报合计。
 */
public record UsageStats(
        String key, long calls, long inputTokens, long outputTokens, long totalTokens) {

    public static UsageStats zero(String key) {
        return new UsageStats(key, 0L, 0L, 0L, 0L);
    }
}
