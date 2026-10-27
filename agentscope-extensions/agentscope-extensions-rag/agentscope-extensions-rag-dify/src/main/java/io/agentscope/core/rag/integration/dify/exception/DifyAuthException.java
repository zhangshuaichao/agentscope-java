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
 * Dify API 身份认证失败时抛出的异常。
 *
 * <p>这是 {@link DifyApiException} 的认证错误专用子类，通常表示：
 * <ul><li>API 密钥无效。</li><li>API 密钥过期。</li><li>缺少 API 密钥。</li><li>请求操作权限不足。</li></ul>
 *
 * <p>用法示例：
 * <pre>{@code
 * try {
 *     difyClient.retrieve(...);
 * } catch (DifyAuthException e) {
 *     log.error("Authentication failed: {}", e.getMessage());
 *     // 检查 API 密钥配置
 * }
 * }</pre>
 */
public class DifyAuthException extends DifyApiException {

    /**
     * 使用消息创建 DifyAuthException。
     *
     * @param message 错误消息
     */
    public DifyAuthException(String message) {
        super(401, "UNAUTHORIZED", message);
    }

    /**
     * 使用消息和原因创建 DifyAuthException。
     *
     * @param message 错误消息
     * @param cause 底层原因
     */
    public DifyAuthException(String message, Throwable cause) {
        super(401, "UNAUTHORIZED", message, cause);
    }

    /**
     * 使用自定义状态码创建 DifyAuthException。
     *
     * <p>用于权限相关错误，例如 403 Forbidden。
     *
     * @param statusCode HTTP 状态码，401 或 403
     * @param message 错误消息
     */
    public DifyAuthException(int statusCode, String message) {
        super(statusCode, "UNAUTHORIZED", message);
    }

    @Override
    public String toString() {
        return "DifyAuthException [" + getStatusCode() + "]: " + getMessage();
    }
}
