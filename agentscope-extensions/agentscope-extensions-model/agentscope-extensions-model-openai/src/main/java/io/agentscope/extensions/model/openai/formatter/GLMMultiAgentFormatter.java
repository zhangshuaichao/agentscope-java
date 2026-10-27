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
package io.agentscope.extensions.model.openai.formatter;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import java.util.List;

/**
 * 智谱 GLM 模型的多智能体格式化器。
 *
 * <p>扩展 {@link OpenAIMultiAgentFormatter}，加入 GLM 专用处理：
 * <ul><li>至少一条用户消息，否则返回错误 1214。</li><li>tool_choice 仅支持 "auto"。</li>
 * <li>工具定义不支持 strict 参数。</li></ul>
 *
 * <p>用法：
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new GLMMultiAgentFormatter())
 *     .modelName("glm-4")
 *     .baseUrl("https://open.bigmodel.cn/api/paas/v4")
 *     .apiKey(apiKey)
 *     .build();
 * }</pre>
 *
 * @deprecated 请改用 {@link io.agentscope.extensions.model.openai.compat.glm.GLMMultiAgentFormatter}。
 */
@Deprecated
public class GLMMultiAgentFormatter extends OpenAIMultiAgentFormatter {

    public GLMMultiAgentFormatter() {
        super();
    }

    public GLMMultiAgentFormatter(String conversationHistoryPrompt) {
        super(conversationHistoryPrompt);
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        List<OpenAIMessage> messages = super.doFormat(msgs);
        return GLMFormatter.ensureUserMessage(messages);
    }

    @Override
    protected boolean supportsStrict() {
        return false;
    }

    @Override
    public void applyToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        GLMFormatter.applyGLMToolChoice(request, toolChoice);
    }
}
