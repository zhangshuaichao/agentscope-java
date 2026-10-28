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

package io.agentscope.extensions.judge.openaidecisions;

/**
 * 已脱敏的传输、HTTP、超时或无效响应故障；省略提供方原始响应体。
 */
public final class OpenAIDecisionsException extends RuntimeException {
    private final Integer statusCode;
    private final boolean retryable;

    public OpenAIDecisionsException(String message, Integer statusCode, boolean retryable) {
        super(message);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    /**
     * HTTP 状态码；不可用时为 {@code null}。
     */
    public Integer getStatusCode() {
        return statusCode;
    }

    /**
     * 此故障是否符合客户端所配置重试策略的条件。
     */
    public boolean isRetryable() {
        return retryable;
    }
}
