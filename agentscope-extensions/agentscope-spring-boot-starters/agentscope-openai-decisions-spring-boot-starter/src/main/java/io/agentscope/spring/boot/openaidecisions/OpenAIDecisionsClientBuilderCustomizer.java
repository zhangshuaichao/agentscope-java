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
package io.agentscope.spring.boot.openaidecisions;

import io.agentscope.extensions.judge.openaidecisions.OpenAIDecisionsClient;
import java.util.function.Consumer;

/** 应用配置属性后定制 {@link OpenAIDecisionsClient.Builder} 的回调。 */
@FunctionalInterface
public interface OpenAIDecisionsClientBuilderCustomizer
        extends Consumer<OpenAIDecisionsClient.Builder> {

    /** 定制 OpenAIDecisions 客户端构建器。 */
    void customize(OpenAIDecisionsClient.Builder builder);

    /** 接收并定制给定的 OpenAIDecisions 客户端构建器。 */
    @Override
    default void accept(OpenAIDecisionsClient.Builder builder) {
        customize(builder);
    }
}
