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

package io.agentscope.core.util;

/**
 * JSON 处理错误的运行时异常。
 *
 * <p>包装底层 JSON 处理异常（例如 Jackson 的 JsonProcessingException），
 * 为框架中的 JSON 操作提供统一异常类型。
 *
 * @see JsonCodec
 * @see JsonUtils
 */
public class JsonException extends RuntimeException {

    /**
     * 使用指定详细消息构造新的 JSON 异常。
     *
     * @param message 详细消息
     */
    public JsonException(String message) {
        super(message);
    }

    /**
     * 使用指定详细消息和原因构造新的 JSON 异常。
     *
     * @param message 详细消息
     * @param cause 异常原因
     */
    public JsonException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 使用指定原因构造新的 JSON 异常。
     *
     * @param cause 异常原因
     */
    public JsonException(Throwable cause) {
        super(cause);
    }
}
