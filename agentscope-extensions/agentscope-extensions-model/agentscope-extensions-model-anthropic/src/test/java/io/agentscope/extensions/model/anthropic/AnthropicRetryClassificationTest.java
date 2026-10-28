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
package io.agentscope.extensions.model.anthropic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.transport.HttpTransportException;
import java.net.SocketException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 确认此模块实际产生的异常形态对应的重试分类。Anthropic 模型将传输失败报告为
 * {@link HttpTransportException}（没有 {@code ModelHttpException} 实现），
 * 因此使用 {@code ExecutionConfig.isRetryableError} 的 {@code HttpTransportException} 分支，
 * 不受 {@code ModelHttpException} 空状态码修复影响；本测试固定此契约。
 * @see io.agentscope.core.model.ExecutionConfig#RETRYABLE_ERRORS
 */
@Tag("unit")
@DisplayName("Anthropic Model Retry Classification Tests")
class AnthropicRetryClassificationTest {

    @Test
    @DisplayName("Should retry connection errors without status code")
    void shouldRetryConnectionErrorsWithoutStatusCode() {
        HttpTransportException connectionError =
                new HttpTransportException(
                        "SSE/NDJSON stream failed: java.net.SocketException: Connection reset",
                        new SocketException("Connection reset"));

        assertTrue(ExecutionConfig.RETRYABLE_ERRORS.test(connectionError));
    }

    @Test
    @DisplayName("Should retry rate limiting and server errors")
    void shouldRetryRateLimitingAndServerErrors() {
        assertTrue(
                ExecutionConfig.RETRYABLE_ERRORS.test(
                        new HttpTransportException("Too Many Requests", 429, null)));
        assertTrue(
                ExecutionConfig.RETRYABLE_ERRORS.test(
                        new HttpTransportException("Service Unavailable", 503, null)));
    }

    @Test
    @DisplayName("Should not retry client errors")
    void shouldNotRetryClientErrors() {
        assertFalse(
                ExecutionConfig.RETRYABLE_ERRORS.test(
                        new HttpTransportException("Bad Request", 400, null)));
        assertFalse(
                ExecutionConfig.RETRYABLE_ERRORS.test(
                        new HttpTransportException("Unauthorized", 401, null)));
    }
}
