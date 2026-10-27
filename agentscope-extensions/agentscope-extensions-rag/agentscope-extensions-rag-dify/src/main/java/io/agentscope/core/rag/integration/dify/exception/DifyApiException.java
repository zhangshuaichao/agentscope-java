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
package io.agentscope.core.rag.integration.dify.exception;

/**
 * Dify API 返回错误响应时抛出的异常。
 *
 * <p>封装 Dify API 的 HTTP 状态码、错误码与错误消息。
 *
 * <p>常见错误：
 * <ul><li>400：参数无效或请求格式错误。</li><li>401：API 密钥无效或缺失。</li>
 * <li>403：权限不足。</li><li>404：数据集或文档不存在。</li>
 * <li>429：超过速率限制。</li><li>500：Dify 服务错误。</li></ul>
 */
public class DifyApiException extends RuntimeException {

    private final int statusCode;
    private final String errorCode;

    /**
     * 使用消息创建 DifyApiException。
     *
     * @param message 错误消息
     */
    public DifyApiException(String message) {
        super(message);
        this.statusCode = -1;
        this.errorCode = null;
    }

    /**
     * 使用消息与原因创建 DifyApiException。
     *
     * @param message 错误消息
     * @param cause 底层原因
     */
    public DifyApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
        this.errorCode = null;
    }

    /**
     * 使用 HTTP 状态码创建 DifyApiException。
     *
     * @param statusCode HTTP 状态码
     * @param message 错误消息
     */
    public DifyApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = null;
    }

    /**
     * 使用完整错误详情创建 DifyApiException。
     *
     * @param statusCode HTTP 状态码
     * @param errorCode Dify 错误码
     * @param message 错误消息
     */
    public DifyApiException(int statusCode, String errorCode, String message) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    /**
     * 使用完整错误详情及原因创建 DifyApiException。
     *
     * @param statusCode HTTP 状态码
     * @param errorCode Dify 错误码
     * @param message 错误消息
     * @param cause 底层原因
     */
    public DifyApiException(int statusCode, String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    /**
     * 获取 HTTP 状态码。
     *
     * @return 状态码，不可用时为 -1
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * 获取 Dify 错误码。
     *
     * @return 错误码，不可用时为 null
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * 检查是否为客户端错误，即 4xx。
     *
     * @return 状态码为 4xx 时返回 true
     */
    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }

    /**
     * 检查是否为服务端错误，即 5xx。
     *
     * @return 状态码为 5xx 时返回 true
     */
    public boolean isServerError() {
        return statusCode >= 500 && statusCode < 600;
    }

    /**
     * 检查是否为限流错误，即 429。
     *
     * @return 状态码为 429 时返回 true
     */
    public boolean isRateLimitError() {
        return statusCode == 429;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("DifyApiException");
        if (statusCode > 0) {
            sb.append(" [").append(statusCode).append("]");
        }
        if (errorCode != null) {
            sb.append(" (").append(errorCode).append(")");
        }
        sb.append(": ").append(getMessage());
        return sb.toString();
    }
}
