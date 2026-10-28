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
package io.agentscope.examples.documentation2.copilotkit.a2ui;

import org.springframework.core.Ordered;

/**
 * 将意图转换为 A2UI JSON 的单个模型后端。{@link A2uiComposer} 按 {@link Ordered} 顺序尝试客户端，先 Gemini，再 DashScope 回退。
 */
public interface A2uiModelClient extends Ordered {

    /**
     * 记录到共享状态 {@code a2ui.generatedBy} 的标识。
     */
    String name();

    /**
     * 所需凭据是否已配置。
     */
    boolean isAvailable();

    /**
     * 生成原始模型输出，组合器从中提取 {@code <a2ui-json>} 块。
     * @throws Exception 模型调用失败时，允许组合器尝试下一客户端
     */
    String generate(String systemPrompt, String userPrompt) throws Exception;
}
