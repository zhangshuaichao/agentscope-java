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

/**
 * 模型操作失败时抛出的异常。
 * 此异常提供统一方式处理不同模型服务商的错误。
 */
public class ModelException extends RuntimeException {

    private final String modelName;
    private final String provider;

    /**
     * 使用错误消息创建 ModelException。
     *
     * <p>当无法获取具体模型／服务商信息，或这些信息与问题无关时，
     * 使用此构造方法表示一般模型错误。
     *
     * @param message 错误消息
     */
    public ModelException(String message) {
        super(message);
        this.modelName = null;
        this.provider = null;
    }

    /**
     * 使用错误消息与原因创建 ModelException。
     *
     * <p>当需要包装引发模型错误的底层异常时，使用此构造方法。
     *
     * @param message 错误消息
     * @param cause 异常的底层原因
     */
    public ModelException(String message, Throwable cause) {
        super(message, cause);
        this.modelName = null;
        this.provider = null;
    }

    /**
     * 使用错误消息与模型信息创建 ModelException。
     *
     * <p>当错误与特定模型及服务商有关时，使用此构造方法，
     * 以便更有针对性地处理错误和调试。
     *
     * @param message 错误消息
     * @param modelName 发生错误的模型名称
     * @param provider 模型服务商（例如 "openai"、"dashscope"）
     */
    public ModelException(String message, String modelName, String provider) {
        super(message);
        this.modelName = modelName;
        this.provider = provider;
    }

    /**
     * 使用错误消息、原因及模型信息创建 ModelException。
     *
     * <p>当需要包装引发模型错误的底层异常，
     * 且错误与特定模型和服务商有关时，使用此构造方法。
     *
     * @param message 错误消息
     * @param cause 异常的底层原因
     * @param modelName 发生错误的模型名称
     * @param provider 模型服务商（例如 "openai"、"dashscope"）
     */
    public ModelException(String message, Throwable cause, String modelName, String provider) {
        super(message, cause);
        this.modelName = modelName;
        this.provider = provider;
    }

    /**
     * 获取发生错误的模型名称。
     *
     * @return 模型名称；未设置时为 null
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * 获取发生错误的模型所属服务商。
     *
     * @return 服务商名称；未设置时为 null
     */
    public String getProvider() {
        return provider;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(super.toString());
        if (modelName != null) {
            sb.append(" [model=").append(modelName);
            if (provider != null) {
                sb.append(", provider=").append(provider);
            }
            sb.append("]");
        }
        return sb.toString();
    }
}
