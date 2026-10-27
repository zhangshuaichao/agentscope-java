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
package io.agentscope.extensions.model.dashscope;

/**
 * DashScope 模型端点类型枚举。
 *
 * <p>允许开发者显式指定 API 端点，覆盖基于模型名称的自动检测。
 *
 * <p>用法示例：
 * <pre>{@code
 * DashScopeChatModel model = DashScopeChatModel.builder()
 *     .apiKey("sk-xxx")
 *     .modelName("qwen3.5-plus")
 *     .endpointType(EndpointType.MULTIMODAL)  // Explicitly use multimodal API
 *     .build();
 * }</pre>
 */
public enum EndpointType {
    /**
     * 根据模型名称自动确定端点类型。
     *
     * <p>这是默认行为。
     *
     * @see DashScopeHttpClient#isMultimodalModel(String)
     */
    AUTO,

    /**
     * 强制使用文本生成 API。
     */
    TEXT,

    /**
     * 强制使用多模态生成 API。
     */
    MULTIMODAL
}
