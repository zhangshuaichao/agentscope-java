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

package io.agentscope.extensions.judge.jev;

/**
 * Jev System One 调用的客户端、传输、验证和 API 错误。
 */
public class JevException extends RuntimeException {

    private final Integer statusCode;
    private final String responseBody;
    private final boolean retryable;

    public JevException(String message) {
        this(message, null, null, false);
    }

    public JevException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
        this.responseBody = null;
        this.retryable = false;
    }

    public JevException(
            String message, Integer statusCode, String responseBody, boolean retryable) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.retryable = retryable;
    }

    public JevException(
            String message,
            Throwable cause,
            Integer statusCode,
            String responseBody,
            boolean retryable) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.retryable = retryable;
    }

    /**
     * HTTP 状态码；收到响应前请求就失败时为 {@code null}。
     */
    public Integer getStatusCode() {
        return statusCode;
    }

    /**
     * 原始 HTTP 错误响应体，或 {@code null}。
     */
    public String getResponseBody() {
        return responseBody;
    }

    /**
     * 配置的重试策略是否可以重试此错误。
     */
    public boolean isRetryable() {
        return retryable;
    }
}
