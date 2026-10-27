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
package io.agentscope.core.rag.exception;

/**
 * 文档读取失败时抛出的异常。
 *
 * <p>包装文档读取错误，提供失败读取器与输入的上下文。
 */
public class ReaderException extends Exception {

    /**
     * 使用消息创建 ReaderException。
     *
     * @param message 错误消息
     */
    public ReaderException(String message) {
        super(message);
    }

    /**
     * 使用消息和原因创建 ReaderException。
     *
     * @param message 错误消息
     * @param cause 异常原因
     */
    public ReaderException(String message, Throwable cause) {
        super(message, cause);
    }
}
