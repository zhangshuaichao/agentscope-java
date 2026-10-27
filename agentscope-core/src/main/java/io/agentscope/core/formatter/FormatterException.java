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
package io.agentscope.core.formatter;

/**
 * 格式化器在消息转换或响应解析中遇到错误时抛出的异常。
 *
 * <p>用于包装底层错误（JSON 解析、SDK 错误等），让各格式化器实现采用一致的错误处理方式。
 */
public class FormatterException extends RuntimeException {

    /**
     * 使用指定详细消息构造 FormatterException。
     *
     * @param message 详细消息
     */
    public FormatterException(String message) {
        super(message);
    }

    /**
     * 使用指定详细消息和原因构造 FormatterException。
     *
     * @param message 详细消息
     * @param cause 异常原因
     */
    public FormatterException(String message, Throwable cause) {
        super(message, cause);
    }
}
