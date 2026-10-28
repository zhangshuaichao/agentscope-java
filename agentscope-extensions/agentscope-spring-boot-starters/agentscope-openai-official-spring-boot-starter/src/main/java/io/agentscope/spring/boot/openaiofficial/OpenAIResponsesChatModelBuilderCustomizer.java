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
package io.agentscope.spring.boot.openaiofficial;

import io.agentscope.extensions.model.openaiofficial.OpenAIResponsesChatModel;
import java.util.function.Consumer;

/**
 * {@link OpenAIResponsesChatModel.Builder} 的定制器。
 *
 * <p>用法示例：
 *
 * <pre>{@code
 *     @Bean
 *     public OpenAIResponsesChatModelBuilderCustomizer openAIResponsesChatModelBuilderCustomizer() {
 *         return builder -> builder.strictJsonSchema(true);
 *     }
 * }</pre>
 */
@FunctionalInterface
public interface OpenAIResponsesChatModelBuilderCustomizer
        extends Consumer<OpenAIResponsesChatModel.Builder> {

    /**
     * 定制 {@link OpenAIResponsesChatModel.Builder}。
     *
     * @param builder 待定制的构建器
     */
    void customize(OpenAIResponsesChatModel.Builder builder);

    /**
     * 接收给定构建器并执行定制。
     *
     * @param builder 待定制的构建器
     */
    @Override
    default void accept(OpenAIResponsesChatModel.Builder builder) {
        this.customize(builder);
    }
}
