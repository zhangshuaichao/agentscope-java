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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 推理循环配置。{@link #maxIters()} 限制单次回复中的推理→行动迭代次数；
 * {@link #stopOnReject()} 控制任一工具调用遭权限拒绝时是否终止循环，而不是将拒绝反馈到下一轮推理。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReactConfig(
        @JsonProperty("max_iters") int maxIters,
        @JsonProperty("stop_on_reject") boolean stopOnReject) {

    public static final int DEFAULT_MAX_ITERS = 20;
    public static final boolean DEFAULT_STOP_ON_REJECT = false;

    public ReactConfig {
        if (maxIters <= 0) {
            throw new IllegalArgumentException("maxIters must be > 0: " + maxIters);
        }
    }

    /**
     * 返回全部采用默认值的配置。
     */
    public static ReactConfig defaults() {
        return new ReactConfig(DEFAULT_MAX_ITERS, DEFAULT_STOP_ON_REJECT);
    }

    @JsonCreator
    static ReactConfig fromJson(
            @JsonProperty("max_iters") Integer maxIters,
            @JsonProperty("stop_on_reject") Boolean stopOnReject) {
        return new ReactConfig(
                maxIters == null ? DEFAULT_MAX_ITERS : maxIters,
                stopOnReject == null ? DEFAULT_STOP_ON_REJECT : stopOnReject);
    }
}
