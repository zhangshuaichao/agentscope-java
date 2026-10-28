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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.SseException;
import com.openai.errors.UnauthorizedException;
import com.openai.errors.UnexpectedStatusCodeException;
import com.openai.errors.UnprocessableEntityException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * {@link OpenAIErrorTranslator} 测试，覆盖各类 SDK 异常。
 *
 * <p>逐项验证提供商 ID、模型名称、原因保留、适用时的状态码提取、safe_message 内容，以及通过 {@link OpenAIOfficialModelException#isRetryableHttpStatus()} 判定可重试性。
 */
class OpenAIErrorTranslatorTest {

    private static final String MODEL = TestSdkFixtures.MODEL_NAME;

    /**
     * 验证每个转换后异常的共同不变量。
     */
    private static void assertCommon(OpenAIOfficialModelException ex, Throwable originalCause) {
        assertEquals("openai-official", ex.getProvider(), "provider id");
        assertEquals(MODEL, ex.getModelName(), "model name");
        assertNotNull(ex.getMessage(), "safe_message must be non-null");
        assertFalse(ex.getMessage().isBlank(), "safe_message must be non-blank");
        String originalMessage = originalCause.getMessage();
        if (originalMessage != null && !originalMessage.isBlank()) {
            assertEquals(
                    originalMessage,
                    ex.getMessage(),
                    "safe_message must match the original throwable's message");
        }
        assertSame(
                originalCause, ex.getCause(), "original SDK exception must be preserved as cause");
    }

    // ── HTTP 400 错误请求 ──
    @Test
    void errBadRequest() {
        BadRequestException sdk = TestSdkFixtures.badRequest("bad request");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertEquals(400, ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── HTTP 401 未授权 ──
    @Test
    void errUnauthorized() {
        UnauthorizedException sdk = TestSdkFixtures.unauthorized("unauthorized");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertEquals(401, ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── HTTP 403 权限拒绝 ──
    @Test
    void errPermissionDenied() {
        PermissionDeniedException sdk = TestSdkFixtures.permissionDenied("forbidden");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertEquals(403, ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── HTTP 404 未找到 ──
    @Test
    void errNotFound() {
        NotFoundException sdk = TestSdkFixtures.notFound("not found");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertEquals(404, ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── HTTP 422 无法处理的实体 ──
    @Test
    void errUnprocessableEntity() {
        UnprocessableEntityException sdk = TestSdkFixtures.unprocessableEntity("unprocessable");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertEquals(422, ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── HTTP 429 限流 ──
    @Test
    void errRateLimit() {
        RateLimitException sdk = TestSdkFixtures.rateLimit("rate limited");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertEquals(429, ex.getStatusCode());
        assertTrue(ex.isRetryableHttpStatus());
    }

    // ── 来自 UnexpectedStatusCodeException 的 HTTP 408 ──
    @Test
    void errTimeoutConflict408() {
        UnexpectedStatusCodeException sdk = TestSdkFixtures.unexpectedStatusCode(408, "timeout");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertEquals(408, ex.getStatusCode());
        assertTrue(ex.isRetryableHttpStatus());
    }

    // ── 来自 SseException 的 HTTP 409 ──
    @Test
    void errTimeoutConflict409Sse() {
        SseException sdk = TestSdkFixtures.sseException(409, "conflict");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertEquals(409, ex.getStatusCode());
        assertTrue(ex.isRetryableHttpStatus());
    }

    // ── HTTP 5xx 服务端内部错误 ──
    @Test
    void errInternalServer500() {
        InternalServerException sdk = TestSdkFixtures.internalServer(500, "server error");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertEquals(500, ex.getStatusCode());
        assertTrue(ex.isRetryableHttpStatus());
    }

    @Test
    void errInternalServer503() {
        InternalServerException sdk = TestSdkFixtures.internalServer(503, "service unavailable");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertEquals(503, ex.getStatusCode());
        assertTrue(ex.isRetryableHttpStatus());
    }

    // ── 非标准 HTTP 状态，不可重试 ──
    @Test
    void errOtherService418() {
        UnexpectedStatusCodeException sdk = TestSdkFixtures.unexpectedStatusCode(418, "teapot");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertEquals(418, ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── OpenAIIoException ──
    @Test
    void errIoRetryableOpenAIIo() {
        OpenAIIoException sdk = TestSdkFixtures.ioException("connection error");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertNull(ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── OpenAIRetryableException ──
    @Test
    void errIoRetryableOpenAIRetryable() {
        OpenAIRetryableException sdk = TestSdkFixtures.retryableException("transient error");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertNull(ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── 直接 TimeoutException ──
    @Test
    void errSdkTimeoutDirect() {
        TimeoutException sdk = TestSdkFixtures.timeoutException("timed out after 30s");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertNull(ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── 原因链中的 TimeoutException ──
    @Test
    void errSdkTimeoutInCauseChain() {
        TimeoutException timeout = TestSdkFixtures.timeoutException("timed out");
        RuntimeException wrapper = new RuntimeException("wrapper", timeout);
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(wrapper, MODEL);
        assertCommon(ex, wrapper);
        assertNull(ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── OpenAIInvalidDataException ──
    @Test
    void errInvalidData() {
        OpenAIInvalidDataException sdk = TestSdkFixtures.invalidDataException("parse error");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertNull(ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── 已转换，直接透传 ──
    @Test
    void alreadyTranslatedIsPassedThrough() {
        RuntimeException cause = new RuntimeException("sdk error");
        OpenAIOfficialModelException original =
                new OpenAIOfficialModelException("already wrapped", cause, MODEL, 429);
        OpenAIOfficialModelException result = OpenAIErrorTranslator.translate(original, MODEL);
        assertSame(original, result);
    }

    // ── 通用回退 ──
    @Test
    void genericRuntimeExceptionIsWrapped() {
        RuntimeException sdk = new RuntimeException("unknown error");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertNull(ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── 消息为 null 时的 safe_message 回退 ──
    @Test
    void safeMessageFallbackForServiceExceptionWithNullMessage() {
        // OpenAIIoException 允许消息为 null；此时 safeMessage
        // 回退为 "OpenAI API error: <class name>"。
        OpenAIIoException sdk = new OpenAIIoException(null);
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertTrue(ex.getMessage().contains("OpenAI API error"));
    }
}
