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

import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import java.util.concurrent.TimeoutException;

/**
 * 将官方 OpenAI Java SDK 异常转换为 {@link OpenAIOfficialModelException}。
 *
 * <p>SDK 异常分类如下：
 * <ul>
 *   <li>具体的 {@code OpenAIServiceException} 子类（例如 {@code BadRequestException}、
 *       {@code RateLimitException}、{@code InternalServerException}）转为带 HTTP 状态的异常；
 *       是否可重试由 {@link OpenAIOfficialModelException#isRetryableHttpStatus()} 决定。
 *   <li>{@code UnexpectedStatusCodeException} 或 {@code SseException} 中的 408/409
 *       转为带 HTTP 状态的异常。
 *   <li>其他 {@code OpenAIServiceException} 转为带 HTTP 状态的异常，默认不可重试。
 *   <li>{@code OpenAIIoException} / {@code OpenAIRetryableException} 转为无 HTTP 状态的异常。
 *   <li>异常原因链中的 {@code TimeoutException} 转为无 HTTP 状态的异常。
 *   <li>{@code OpenAIInvalidDataException} 转为无 HTTP 状态、不可重试的异常。
 * </ul>
 *
 * <p>非 SDK 异常（验证、拒绝、不支持的输入）在各自调用点创建，不由此转换器处理。
 *
 * <p>原始 SDK 异常始终保留为 cause，使模块的 retryOn 谓词可以检查原因链中的
 * 请求头与异常类型。
 */
final class OpenAIErrorTranslator {

    private OpenAIErrorTranslator() {}

    /**
     * 将 throwable 转换为 {@link OpenAIOfficialModelException}。
     *
     * @param throwable 待转换的异常（通常是 SDK 异常）
     * @param modelName 用于上下文的模型名称，未知时为 null
     * @return 提供者 ID 为 {@code openai-official} 的标准化异常
     */
    static OpenAIOfficialModelException translate(Throwable throwable, String modelName) {
        if (throwable instanceof OpenAIOfficialModelException alreadyTranslated) {
            return alreadyTranslated;
        }

        // OpenAIServiceException 覆盖所有携带 HTTP 状态的 SDK 错误
        if (throwable instanceof OpenAIServiceException serviceException) {
            int statusCode = serviceException.statusCode();
            String safeMessage = safeMessage(throwable, statusCode);
            return new OpenAIOfficialModelException(safeMessage, throwable, modelName, statusCode);
        }

        // 可重试的传输层异常（无 HTTP 状态）
        if (throwable instanceof OpenAIIoException
                || throwable instanceof OpenAIRetryableException) {
            String safeMessage = safeMessage(throwable, null);
            return new OpenAIOfficialModelException(safeMessage, throwable, modelName);
        }

        // SDK 响应解析／验证错误（不可重试，无 HTTP 状态）
        if (throwable instanceof OpenAIInvalidDataException) {
            String safeMessage = safeMessage(throwable, null);
            return new OpenAIOfficialModelException(safeMessage, throwable, modelName);
        }

        // TimeoutException（直接异常或位于原因链中）：可重试，无 HTTP 状态
        if (hasTimeoutInCauseChain(throwable)) {
            String safeMessage = safeMessage(throwable, null);
            return new OpenAIOfficialModelException(safeMessage, throwable, modelName);
        }

        // 回退：将其他 throwable 包装为不可重试异常
        String safeMessage = safeMessage(throwable, null);
        return new OpenAIOfficialModelException(safeMessage, throwable, modelName);
    }

    /**
     * 空值安全的异常消息提取。
     *
     * <p>{@code throwable.getMessage()} 非 null 时直接使用；否则，已知 HTTP 状态时
     * 回退到 {@code "OpenAI API error (HTTP {statusCode})"}，无状态时回退到异常类名。
     */
    private static String safeMessage(Throwable throwable, Integer statusCode) {
        String message = throwable.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        if (statusCode != null) {
            return "OpenAI API error (HTTP " + statusCode + ")";
        }
        return "OpenAI API error: " + throwable.getClass().getSimpleName();
    }

    /**
     * 检查原因链任意位置是否出现 {@link TimeoutException}。
     */
    private static boolean hasTimeoutInCauseChain(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
