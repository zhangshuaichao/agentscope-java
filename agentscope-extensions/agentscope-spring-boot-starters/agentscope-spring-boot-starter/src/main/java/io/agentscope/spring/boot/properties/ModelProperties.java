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
package io.agentscope.spring.boot.properties;

/**
 * 用于选择提供方的通用模型配置。
 *
 * <p>选择提供方的示例：
 *
 * <pre>{@code
 * agentscope:
 *   model:
 *     provider: openai # 或 dashscope、gemini、anthropic
 * }</pre>
 */
public class ModelProperties {

    /**
     * 模型提供方标识。
     *
     * <p>常见取值（实际可用范围还取决于已引入的 starter）：
     *
     * <ul>
     *   <li>{@code dashscope}（默认；具体匹配行为见对应条件注解）</li>
     *   <li>{@code openai}</li>
     *   <li>{@code gemini}</li>
     *   <li>{@code anthropic}</li>
     * </ul>
     */
    private String provider;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }
}
