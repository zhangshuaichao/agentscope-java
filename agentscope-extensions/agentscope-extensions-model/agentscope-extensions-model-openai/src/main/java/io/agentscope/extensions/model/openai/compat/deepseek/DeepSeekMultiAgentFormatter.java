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
package io.agentscope.extensions.model.openai.compat.deepseek;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.formatter.OpenAIMultiAgentFormatter;
import java.util.List;

/**
 * DeepSeek Chat 模型（deepseek-v4-flash、deepseek-v4-pro）的多智能体格式化器。
 *
 * <p>扩展 {@link OpenAIMultiAgentFormatter}，加入 DeepSeek 专用处理：
 * <ul><li>允许 system/user/assistant 的 {@code name} 字段。</li><li>工具定义省略 strict 参数。</li>
 * <li>处理思考模式的 reasoning_content。</li></ul>
 *
 * <p>用法：
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new DeepSeekMultiAgentFormatter())
 *     .modelName("deepseek-v4-pro")
 *     .baseUrl("https://api.deepseek.com")
 *     .apiKey(apiKey)
 *     .build();
 * }</pre>
 */
public class DeepSeekMultiAgentFormatter extends OpenAIMultiAgentFormatter {

    private final boolean appendEmptyUserIfEndsWithAssistant;

    public DeepSeekMultiAgentFormatter() {
        super();
        this.appendEmptyUserIfEndsWithAssistant = false;
    }

    public DeepSeekMultiAgentFormatter(String conversationHistoryPrompt) {
        super(conversationHistoryPrompt);
        this.appendEmptyUserIfEndsWithAssistant = false;
    }

    /**
     * 创建 DeepSeek 多智能体格式化器，可选地追加空用户消息。
     *
     * @param appendEmptyUserIfEndsWithAssistant 为 true 时，若对话以助手消息结束则追加空用户消息，以避免 API 错误
     */
    public DeepSeekMultiAgentFormatter(boolean appendEmptyUserIfEndsWithAssistant) {
        super();
        this.appendEmptyUserIfEndsWithAssistant = appendEmptyUserIfEndsWithAssistant;
    }

    /**
     * 使用自定义历史提示词创建 DeepSeek 多智能体格式化器，可选地追加空用户消息。
     *
     * @param conversationHistoryPrompt 自定义对话历史提示词
     * @param appendEmptyUserIfEndsWithAssistant 为 true 时，若对话以助手消息结束则追加空用户消息，以避免 API 错误
     */
    public DeepSeekMultiAgentFormatter(
            String conversationHistoryPrompt, boolean appendEmptyUserIfEndsWithAssistant) {
        super(conversationHistoryPrompt);
        this.appendEmptyUserIfEndsWithAssistant = appendEmptyUserIfEndsWithAssistant;
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        return doFormat(msgs, null);
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs, GenerateOptions options) {
        List<OpenAIMessage> messages = super.doFormat(msgs, options);
        messages = DeepSeekFormatter.applyDeepSeekFixes(messages);
        if (appendEmptyUserIfEndsWithAssistant) {
            messages = DeepSeekFormatter.appendEmptyUserIfNeeded(messages);
        }
        return messages;
    }

    @Override
    protected boolean supportsStrict() {
        return false;
    }
}
