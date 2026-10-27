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
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek Chat 模型（deepseek-v4-flash、deepseek-v4-pro）的格式化器。
 *
 * <p>本适配器处理的 DeepSeek API 特定要求：
 * <ul><li>允许 system/user/assistant 的 {@code name} 字段。</li><li>工具定义省略 strict 参数。</li>
 * <li>所有助手消息均保留 reasoning_content，缺失时补齐。</li></ul>
 *
 * <p>用法：
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new DeepSeekFormatter())
 *     .modelName("deepseek-v4-flash")
 *     .baseUrl("https://api.deepseek.com")
 *     .apiKey(apiKey)
 *     .build();
 * }</pre>
 *
 * @see <a href="https://api-docs.deepseek.com/guides/thinking_mode#tool-calls">DeepSeek 思考模式</a>
 */
public class DeepSeekFormatter extends OpenAIChatFormatter {

    private final boolean appendEmptyUserIfEndsWithAssistant;

    public DeepSeekFormatter() {
        this(false);
    }

    /**
     * 创建 DeepSeek 格式化器，可选地追加空用户消息。
     *
     * @param appendEmptyUserIfEndsWithAssistant 为 true 时，若对话以助手消息结束则追加空用户消息，以避免 API 错误
     */
    public DeepSeekFormatter(boolean appendEmptyUserIfEndsWithAssistant) {
        super();
        this.appendEmptyUserIfEndsWithAssistant = appendEmptyUserIfEndsWithAssistant;
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        return doFormat(msgs, null);
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs, GenerateOptions options) {
        List<OpenAIMessage> messages = super.doFormat(msgs, options);
        messages = applyDeepSeekFixes(messages);
        if (appendEmptyUserIfEndsWithAssistant) {
            messages = appendEmptyUserIfNeeded(messages);
        }
        return messages;
    }

    @Override
    protected boolean supportsStrict() {
        return false;
    }

    /**
     * 应用 DeepSeek 专用消息格式修正。
     *
     * <p>在思考模式且请求携带工具时，DeepSeek API 要求完整回传<b>所有</b>助手轮次的
     * reasoning_content，即使该轮没有工具调用；否则 API 返回 HTTP 400，提示思考模式的推理内容必须回传。
     * @see <a href="https://api-docs.deepseek.com/guides/thinking_mode#tool-calls">DeepSeek 思考模式与工具调用</a>
     */
    static List<OpenAIMessage> applyDeepSeekFixes(List<OpenAIMessage> messages) {
        for (OpenAIMessage msg : messages) {
            // 补齐缺失的 reasoning_content。
            if ("assistant".equals(msg.getRole()) && msg.getReasoningContent() == null) {
                msg.setReasoningContent("");
            }
        }
        return messages;
    }

    /**
     * 对话以助手消息结束时追加空用户消息。
     *
     * <p>某些 DeepSeek API 场景要求对话不能以助手消息结束。
     *
     * @param messages 待检查的消息
     * @return 必要时已追加空用户消息的列表
     */
    static List<OpenAIMessage> appendEmptyUserIfNeeded(List<OpenAIMessage> messages) {
        if (messages.isEmpty()
                || !"assistant".equals(messages.get(messages.size() - 1).getRole())) {
            return messages;
        }
        List<OpenAIMessage> result = new ArrayList<>(messages);
        result.add(OpenAIMessage.builder().role("user").content("").build());
        return result;
    }
}
