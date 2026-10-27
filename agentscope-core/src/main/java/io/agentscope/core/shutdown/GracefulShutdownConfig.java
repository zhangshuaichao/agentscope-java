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

import java.time.Duration;
import java.util.Objects;

/**
 * 智能体系统优雅关闭行为的配置。
 *
 * <p>此记录定义系统在关闭时的行为，包括：
 * <ul>
 *   <li>等待正在进行的操作完成的最长时间</li>
 *   <li>处理部分推理结果的策略</li>
 * </ul>
 *
 * @param shutdownTimeout       等待关闭完成的最长时长；为 null 时无限等待所有操作完成；
 *                              指定时必须为正时长
 * @param partialReasoningPolicy 关闭期间处理未完成推理结果的策略；不可为 null
 */
public record GracefulShutdownConfig(
        Duration shutdownTimeout, PartialReasoningPolicy partialReasoningPolicy) {

    /**
     * 默认配置实例。
     *
     * <p>使用以下设置：
     * <ul>
     *   <li>关闭超时：null（无限等待）</li>
     *   <li>部分推理策略：{@link PartialReasoningPolicy#SAVE}</li>
     * </ul>
     */
    public static final GracefulShutdownConfig DEFAULT =
            new GracefulShutdownConfig(null, PartialReasoningPolicy.SAVE);

    /**
     * 用于验证的紧凑构造器。
     *
     * <p>验证以下条件：
     * <ul>
     *   <li>{@code partialReasoningPolicy} 不为 null</li>
     *   <li>{@code shutdownTimeout} 若已指定，必须为正时长</li>
     * </ul>
     *
     * @throws NullPointerException     当 partialReasoningPolicy 为 null 时抛出
     * @throws IllegalArgumentException 当 shutdownTimeout 不是正时长时抛出
     */
    public GracefulShutdownConfig {
        Objects.requireNonNull(partialReasoningPolicy, "partialReasoningPolicy cannot be null");
        if (shutdownTimeout != null && (shutdownTimeout.isNegative() || shutdownTimeout.isZero())) {
            throw new IllegalArgumentException("shutdownTimeout must be > 0 or null (infinite)");
        }
    }
}
