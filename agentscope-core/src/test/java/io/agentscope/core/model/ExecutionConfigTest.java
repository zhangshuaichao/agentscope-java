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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.transport.HttpTransportException;
import java.net.SocketException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ExecutionConfig} 单元测试。
 */
@Tag("unit")
@DisplayName("ExecutionConfig Unit Tests")
class ExecutionConfigTest {

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    @DisplayName("Should retry retryable model HTTP status codes")
    void shouldRetryRetryableModelHttpStatusCodes(int statusCode) {
        assertTrue(ExecutionConfig.RETRYABLE_ERRORS.test(new TestModelHttpException(statusCode)));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 422})
    @DisplayName("Should not retry non-retryable model HTTP status codes")
    void shouldNotRetryNonRetryableModelHttpStatusCodes(int statusCode) {
        assertFalse(ExecutionConfig.RETRYABLE_ERRORS.test(new TestModelHttpException(statusCode)));
    }

    @Test
    @DisplayName("Should retry model HTTP exception from cause chain")
    void shouldRetryModelHttpExceptionFromCauseChain() {
        RuntimeException wrapped = new RuntimeException(new TestModelHttpException(429));

        assertTrue(ExecutionConfig.RETRYABLE_ERRORS.test(wrapped));
    }

    @Test
    @DisplayName("Should not retry model HTTP exception without status code")
    void shouldNotRetryModelHttpExceptionWithoutStatusCode() {
        assertFalse(ExecutionConfig.RETRYABLE_ERRORS.test(new TestModelHttpException(null)));
    }

    @Test
    @DisplayName("Should retry model HTTP exception without status code wrapping transport error")
    void shouldRetryModelHttpExceptionWithoutStatusCodeWrappingTransportError() {
        // 复现 #3057：流式传输故障被不带 HTTP 状态码的模型
        // 异常包装，例如 OpenAIException，因此必须检查原因
        // 链，不能将其视为永久客户端错误。
        HttpTransportException transportError =
                new HttpTransportException(
                        "SSE/NDJSON stream failed: Connection reset",
                        new SocketException("Connection reset"));
        TestModelHttpException wrapped = new TestModelHttpException(null, transportError);

        assertTrue(ExecutionConfig.RETRYABLE_ERRORS.test(wrapped));
    }

    @Test
    @DisplayName("Should retry model HTTP exception without status code wrapping IO error")
    void shouldRetryModelHttpExceptionWithoutStatusCodeWrappingIoError() {
        TestModelHttpException wrapped =
                new TestModelHttpException(null, new SocketException("Connection reset"));

        assertTrue(ExecutionConfig.RETRYABLE_ERRORS.test(wrapped));
    }

    private static final class TestModelHttpException extends RuntimeException
            implements ModelHttpException {

        private final Integer statusCode;

        private TestModelHttpException(Integer statusCode) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
        }

        private TestModelHttpException(Integer statusCode, Throwable cause) {
            super("HTTP " + statusCode, cause);
            this.statusCode = statusCode;
        }

        @Override
        public Integer getStatusCode() {
            return statusCode;
        }
    }
}
