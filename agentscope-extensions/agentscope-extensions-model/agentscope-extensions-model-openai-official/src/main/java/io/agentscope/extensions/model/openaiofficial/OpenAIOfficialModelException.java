/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.openaiofficial;

import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ModelHttpException;

/**
 * {@code openai-official} 提供者的异常类型。
 *
 * <p>扩展 {@link ModelException} 并实现 {@link ModelHttpException}，
 * 使感知 HTTP 状态的重试分类同时适用于 core 的
 * {@code ExecutionConfig.RETRYABLE_ERRORS} 兜底机制和模块自身的 retryOn 谓词。
 *
 * <p>覆盖 {@link #isRetryableHttpStatus()}，在 core 默认的 429 与 5xx 之外
 * 增加 408 和 409，与 SDK {@code RetryingHttpClient.shouldRetry}
 * 的状态码集合 {408, 409, 429, >=500} 保持一致。
 */
public class OpenAIOfficialModelException extends ModelException implements ModelHttpException {

    private final Integer statusCode;

    /**
     * 创建无 HTTP 状态的异常（用于 {@code OpenAIIoException}、
     * {@code OpenAIInvalidDataException} 或验证错误等非 HTTP SDK 失败）。
     *
     * @param message 错误消息
     * @param cause 底层 SDK 异常，或 null
     * @param modelName 模型名称，未知时为 null
     */
    public OpenAIOfficialModelException(String message, Throwable cause, String modelName) {
        super(message, cause, modelName, OpenAIOfficialConstants.PROVIDER_ID);
        this.statusCode = null;
    }

    /**
     * 创建带 HTTP 状态码的异常（用于 {@code OpenAIServiceException} 子类，
     * 例如 {@code BadRequestException}、{@code RateLimitException} 等）。
     *
     * @param message 错误消息
     * @param cause 底层 SDK 异常
     * @param modelName 模型名称，未知时为 null
     * @param statusCode SDK 异常中的 HTTP 状态码
     */
    public OpenAIOfficialModelException(
            String message, Throwable cause, String modelName, Integer statusCode) {
        super(message, cause, modelName, OpenAIOfficialConstants.PROVIDER_ID);
        this.statusCode = statusCode;
    }

    /**
     * 创建无 cause、无 HTTP 状态的验证／不可重试异常。
     *
     * @param message 验证消息
     */
    public OpenAIOfficialModelException(String message) {
        super(message, null, null, OpenAIOfficialConstants.PROVIDER_ID);
        this.statusCode = null;
    }

    /**
     * 创建无 cause、无 HTTP 状态但已知模型名称的验证／不可重试异常。
     *
     * @param message 验证消息
     * @param modelName 模型名称，未知时为 null
     */
    public OpenAIOfficialModelException(String message, String modelName) {
        super(message, null, modelName, OpenAIOfficialConstants.PROVIDER_ID);
        this.statusCode = null;
    }

    @Override
    public Integer getStatusCode() {
        return statusCode;
    }

    @Override
    public boolean isRetryableHttpStatus() {
        if (statusCode == null) {
            return false;
        }
        return statusCode == 408
                || statusCode == 409
                || statusCode == 429
                || (statusCode >= 500 && statusCode < 600);
    }
}
