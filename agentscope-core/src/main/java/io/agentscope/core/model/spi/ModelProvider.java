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
package io.agentscope.core.model.spi;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;

/** 从扩展模块发现模型适配器的服务提供者接口（SPI）。 */
public interface ModelProvider {

    /** 服务商标识，例如 {@code openai}。 */
    String providerId();

    /**
     * 返回此服务商是否能够为完整模型 ID 创建模型，
     * 例如 {@code openai:gpt-4o}。
     */
    boolean supports(String modelId);

    /** 返回此服务商是否能够根据完整模型 ID 和上下文创建模型。 */
    default boolean supports(String modelId, ModelCreationContext context) {
        return supports(modelId);
    }

    /** 根据完整模型 ID 创建模型。 */
    Model create(String modelId);

    /** 根据完整模型 ID 和与服务商无关的上下文创建模型。 */
    default Model create(String modelId, ModelCreationContext context) {
        return create(modelId);
    }
}
