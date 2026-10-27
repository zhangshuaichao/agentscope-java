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
package io.agentscope.extensions.model.openai.compat.kimi;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.formatter.OpenAIMultiAgentFormatter;

/**
 * Kimi（Moonshot AI）模型的多智能体格式化器。
 *
 * <p>扩展 {@link OpenAIMultiAgentFormatter}，使用与 {@link KimiFormatter} 相同的专用处理：
 * <ul><li>固定采样参数（{@code temperature} / {@code top_p} / 惩罚参数）在 {@code kimi-*} 模型上移除。</li>
 * <li>{@code reasoning_effort} 仅为 {@code kimi-k3} 保留；始终移除 {@code thinking_budget}。</li>
 * <li>将 {@code max_tokens} 映射到 {@code max_completion_tokens}。</li>
 * <li>目标模型不支持所请求模式时，降级 {@code tool_choice}，详见 {@link KimiFormatter}。</li>
 * <li>不发送工具定义中的 {@code strict} 参数。</li></ul>
 *
 * <p>用法：
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new KimiMultiAgentFormatter())
 *     .modelName("kimi-k3")
 *     .baseUrl("https://api.moonshot.cn/v1")
 *     .apiKey(apiKey)
 *     .build();
 * }</pre>
 *
 * @see KimiFormatter
 */
public class KimiMultiAgentFormatter extends OpenAIMultiAgentFormatter {

    public KimiMultiAgentFormatter() {
        super();
    }

    public KimiMultiAgentFormatter(String conversationHistoryPrompt) {
        super(conversationHistoryPrompt);
    }

    @Override
    protected boolean supportsStrict() {
        return false;
    }

    @Override
    public void applyOptions(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        super.applyOptions(request, options, defaultOptions);
        KimiFormatter.sanitizeKimiRequest(request);
    }

    @Override
    public void applyToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        KimiFormatter.applyKimiToolChoice(request, toolChoice);
    }
}
