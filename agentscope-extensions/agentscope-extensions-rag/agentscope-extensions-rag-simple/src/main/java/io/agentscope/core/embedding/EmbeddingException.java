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
package io.agentscope.core.embedding;

/**
 * 嵌入生成失败时抛出的异常。
 *
 * <p>包装模型 API 调用错误，提供失败模型与供应商的上下文。
 */
public class EmbeddingException extends RuntimeException {

    private final String modelName;
    private final String provider;

    /**
     * 使用消息创建 EmbeddingException。
     *
     * @param message 错误消息
     */
    public EmbeddingException(String message) {
        super(message);
        this.modelName = null;
        this.provider = null;
    }

    /**
     * 使用消息和原因创建 EmbeddingException。
     *
     * @param message 错误消息
     * @param cause 异常原因
     */
    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
        this.modelName = null;
        this.provider = null;
    }

    /**
     * 使用消息、模型名称和提供者创建 EmbeddingException。
     *
     * @param message 错误消息
     * @param modelName 失败模型名称
     * @param provider 提供者名称，如 dashscope、openai
     */
    public EmbeddingException(String message, String modelName, String provider) {
        super(message);
        this.modelName = modelName;
        this.provider = provider;
    }

    /**
     * 使用消息、原因、模型名称和提供者创建 EmbeddingException。
     *
     * @param message 错误消息
     * @param cause 异常原因
     * @param modelName 失败模型名称
     * @param provider 提供者名称，如 dashscope、openai
     */
    public EmbeddingException(String message, Throwable cause, String modelName, String provider) {
        super(message, cause);
        this.modelName = modelName;
        this.provider = provider;
    }

    /**
     * 获取失败模型名称。
     *
     * @return 模型名称；未设置时为 null
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * 获取提供者名称。
     *
     * @return 提供者名称；未设置时为 null
     */
    public String getProvider() {
        return provider;
    }
}
