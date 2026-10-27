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
package io.agentscope.core.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.agentscope.core.model.Model;

/**
 * 模型调用配置。{@link #maxRetries()} 限制单次模型调用的重试次数；
 * 主模型耗尽重试仍失败时调用可空的 {@link #fallbackModel()}，回退模型使用相同的 {@code maxRetries} 重试额度。
 * 发生此类切换时通知可空的 {@link #failoverListener()}。
 *
 * <p>{@code fallbackModel} 和 {@code failoverListener} 字段有意排除在 JSON 序列化之外（{@code @JsonIgnore}），
 * 因为它们持有实时实例（凭据、连接、回调），无法通过状态快照可靠地往返还原。
 */
public record ModelConfig(
        int maxRetries,
        @JsonIgnore Model fallbackModel,
        @JsonIgnore FailoverListener failoverListener) {

    public static final int DEFAULT_MAX_RETRIES = 3;

    public ModelConfig {
        if (maxRetries <= 0) {
            throw new IllegalArgumentException("maxRetries must be > 0: " + maxRetries);
        }
    }

    /**
     * 不观察回退切换的便捷构造方法，相当于传入 {@code null} 监听器。
     */
    public ModelConfig(int maxRetries, Model fallbackModel) {
        this(maxRetries, fallbackModel, null);
    }

    /**
     * 返回全部采用默认值的配置（无回退模型）。
     */
    public static ModelConfig defaults() {
        return new ModelConfig(DEFAULT_MAX_RETRIES, null, null);
    }
}
