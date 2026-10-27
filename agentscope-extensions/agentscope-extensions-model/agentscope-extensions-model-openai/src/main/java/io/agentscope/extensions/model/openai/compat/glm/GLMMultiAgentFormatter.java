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
package io.agentscope.extensions.model.openai.compat.glm;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.formatter.OpenAIMultiAgentFormatter;
import java.util.List;

/**
 * 智谱 AI（Z.ai）GLM 模型的多智能体格式化器。
 *
 * <p>扩展 {@link OpenAIMultiAgentFormatter}，采用与 {@link GLMFormatter} 相同的专用处理：
 * <ul><li>至少一条用户消息，否则返回错误 1214。</li><li>消息不支持 {@code name} 参数。</li>
 * <li>{@code tool_choice} 仅支持 {@code "auto"}；强制选择降级，
 * {@code none} 通过移除工具确保不调用工具。</li><li>工具定义不支持 {@code strict} 参数。</li>
 * <li>移除不支持的采样参数，将 {@code max_completion_tokens} 映射到 {@code max_tokens}，
 * 并将 {@code temperature} / {@code top_p} 限制到 GLM 范围，详见 {@link GLMFormatter}。</li></ul>
 *
 * <p>用法：
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new GLMMultiAgentFormatter())
 *     .modelName("glm-5.2")
 *     .baseUrl("https://open.bigmodel.cn/api/paas/v4")
 *     .apiKey(apiKey)
 *     .build();
 * }</pre>
 *
 * @see GLMFormatter
 */
public class GLMMultiAgentFormatter extends OpenAIMultiAgentFormatter {

    public GLMMultiAgentFormatter() {
        super();
    }

    public GLMMultiAgentFormatter(String conversationHistoryPrompt) {
        super(conversationHistoryPrompt);
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        return doFormat(msgs, null);
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs, GenerateOptions options) {
        List<OpenAIMessage> messages = super.doFormat(msgs, options);
        return GLMFormatter.ensureUserMessage(messages);
    }

    @Override
    protected OpenAIMessage convertMessage(Msg msg, boolean hasMedia) {
        OpenAIMessage message = super.convertMessage(msg, hasMedia);
        if (message != null) {
            message.setName(null);
        }
        return message;
    }

    @Override
    protected boolean supportsStrict() {
        return false;
    }

    @Override
    public void applyOptions(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        super.applyOptions(request, options, defaultOptions);
        GLMFormatter.stripUnsupportedSamplingParams(request);
    }

    @Override
    public void applyToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        GLMFormatter.applyGLMToolChoice(request, toolChoice);
    }
}
