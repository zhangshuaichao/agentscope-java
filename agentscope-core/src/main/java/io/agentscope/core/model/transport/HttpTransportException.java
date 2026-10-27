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
package io.agentscope.core.model.transport;

/**
 * HTTP 传输操作失败时抛出的异常。
 *
 * <p>包装连接失败、超时和协议错误等底层传输错误。
 */
public class HttpTransportException extends RuntimeException {

    private final Integer statusCode;
    private final String responseBody;

    /**
     * 使用消息创建新的 HttpTransportException。
     *
     * @param message 错误消息
     */
    public HttpTransportException(String message) {
        super(message);
        this.statusCode = null;
        this.responseBody = null;
    }

    /**
     * 使用消息和原因创建新的 HttpTransportException。
     *
     * @param message 错误消息
     * @param cause 底层原因
     */
    public HttpTransportException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
        this.responseBody = null;
    }

    /**
     * 为 HTTP 错误响应创建新的 HttpTransportException。
     *
     * @param message 错误消息
     * @param statusCode HTTP 状态码
     * @param responseBody 响应体（可能包含错误详情）
     */
    public HttpTransportException(String message, int statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    /**
     * 为 HTTP 错误响应创建带底层原因的 HttpTransportException。
     *
     * @param message 错误消息
     * @param statusCode HTTP 状态码
     * @param responseBody 响应体
     * @param cause 底层原因
     */
    public HttpTransportException(
            String message, int statusCode, String responseBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    /**
     * 获取 HTTP 状态码（如果存在）。
     *
     * @return 状态码；不是 HTTP 错误时为 null
     */
    public Integer getStatusCode() {
        return statusCode;
    }

    /**
     * 获取响应体（如果存在）。
     *
     * @return 响应体；不可用时为 null
     */
    public String getResponseBody() {
        return responseBody;
    }

    /**
     * 检查是否为 HTTP 错误（包含状态码）。
     *
     * @return 异常表示 HTTP 错误响应时返回 true
     */
    public boolean isHttpError() {
        return statusCode != null;
    }

    /**
     * 检查是否为客户端错误（4xx 状态码）。
     *
     * @return 状态码为 4xx 时返回 true
     */
    public boolean isClientError() {
        return statusCode != null && statusCode >= 400 && statusCode < 500;
    }

    /**
     * 检查是否为服务端错误（5xx 状态码）。
     *
     * @return 状态码为 5xx 时返回 true
     */
    public boolean isServerError() {
        return statusCode != null && statusCode >= 500 && statusCode < 600;
    }

    /**
     * 检查错误是否可重试。
     *
     * <p>服务端错误（5xx）及某些客户端错误（429 Too Many Requests）通常可以重试。
     *
     * @return 错误可重试时返回 true
     */
    public boolean isRetryable() {
        if (statusCode == null) {
            // 连接错误通常可以重试
            return true;
        }
        // 服务端错误和限流可以重试
        return isServerError() || statusCode == 429;
    }

    /**
     * 获取包含响应体的详细错误消息，便于调试。
     *
     * <p>响应体可用时将其追加到消息中，便于排查问题。
     *
     * @return 包含响应体详情的完整错误消息
     */
    @Override
    public String getMessage() {
        String baseMessage = super.getMessage();
        if (responseBody != null && !responseBody.isEmpty()) {
            // 截断过长的响应体（最多 1000 个字符），避免日志过量
            String truncatedBody =
                    responseBody.length() > 1000
                            ? responseBody.substring(0, 1000) + "...(truncated)"
                            : responseBody;
            return baseMessage + " | Response body: " + truncatedBody;
        }
        return baseMessage;
    }

    /**
     * 获取包含所有可用详情的格式化错误消息。
     *
     * @return 包含状态码和响应体的格式化错误字符串
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getName()).append(": ");
        if (statusCode != null) {
            sb.append("[HTTP ").append(statusCode).append("] ");
        }
        sb.append(super.getMessage());
        if (responseBody != null && !responseBody.isEmpty()) {
            // 截断过长的响应体
            String truncatedBody =
                    responseBody.length() > 500
                            ? responseBody.substring(0, 500) + "...(truncated)"
                            : responseBody;
            sb.append("\nResponse body: ").append(truncatedBody);
        }
        return sb.toString();
    }
}
