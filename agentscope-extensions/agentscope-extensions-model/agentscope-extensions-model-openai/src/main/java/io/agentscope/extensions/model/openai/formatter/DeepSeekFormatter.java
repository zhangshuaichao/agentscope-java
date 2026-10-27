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
import io.agentscope.extensions.model.openai.dto.OpenAIContentPart;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek Chat 模型（deepseek-chat、deepseek-coder）的格式化器。
 *
 * <p>DeepSeek API 的特定要求：
 * <ul><li>消息不包含 name 字段，否则返回 HTTP 400。</li><li>工具定义不支持 strict 参数。</li>
 * <li>思考模式中，带工具调用的片段保留 reasoning_content。</li></ul>
 *
 * <p>用法：
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new DeepSeekFormatter())
 *     .modelName("deepseek-chat")
 *     .baseUrl("https://api.deepseek.com/v1")
 *     .apiKey(apiKey)
 *     .build();
 * }</pre>
 *
 * @deprecated 请改用 {@link io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter}。
 * @see <a href="https://api-docs.deepseek.com/guides/thinking_mode#tool-calls">DeepSeek 思考模式</a>
 */
@Deprecated
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
        List<OpenAIMessage> messages = super.doFormat(msgs);
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
     * <p>DeepSeek API 要求：
     * <ul><li>消息不含 name 字段。</li><li>思考模式下，带工具调用的片段保留 reasoning_content。</li>
     * <li>思考模式下，不含工具调用的片段移除 reasoning_content。</li></ul>
     *
     * <p>此方法为静态方法，以便与 {@link DeepSeekMultiAgentFormatter} 共享。
     *
     * @param messages 原始 OpenAI 消息
     * @return 修正后用于 DeepSeek API 的消息
     */
    static List<OpenAIMessage> applyDeepSeekFixes(List<OpenAIMessage> messages) {
        int lastUserIndex = findLastUserIndex(messages);
        boolean thinkingMode = messages.stream().anyMatch(m -> m.getReasoningContent() != null);
        boolean[] segHasTool = thinkingMode ? computeSegmentToolFlags(messages) : null;

        List<OpenAIMessage> result = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            boolean isCurrentTurn = i >= lastUserIndex;
            boolean needReasoning =
                    thinkingMode
                            ? (isCurrentTurn || (segHasTool != null && segHasTool[i]))
                            : isCurrentTurn;
            result.add(fixMessage(messages.get(i), needReasoning));
        }
        return result;
    }

    /**
     * 单次扫描消息，识别连续用户消息之间包含工具调用的片段。
     * 将这些片段中的消息标记为需要保留 reasoning_content。
     */
    private static boolean[] computeSegmentToolFlags(List<OpenAIMessage> messages) {
        boolean[] flags = new boolean[messages.size()];
        int prevUser = -1;
        for (int i = 0; i <= messages.size(); i++) {
            if (i == messages.size() || "user".equals(messages.get(i).getRole())) {
                if (prevUser >= 0) {
                    // 检查片段 (prevUser, i) 是否含有工具调用。
                    boolean hasTool = false;
                    for (int j = prevUser + 1; j < i && !hasTool; j++) {
                        OpenAIMessage m = messages.get(j);
                        hasTool = m.getToolCalls() != null && !m.getToolCalls().isEmpty();
                    }
                    if (hasTool) {
                        for (int j = prevUser + 1; j < i; j++) {
                            flags[j] = true;
                        }
                    }
                }
                prevUser = i;
            }
        }
        return flags;
    }

    /**
     * 若对话以助手消息结束，则追加空用户消息。
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

    private static int findLastUserIndex(List<OpenAIMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                return i;
            }
        }
        return 0; // 未找到用户消息，将全部消息视为当前轮次。
    }

    @SuppressWarnings("unchecked")
    private static OpenAIMessage fixMessage(OpenAIMessage msg, boolean needReasoning) {
        boolean hasName = msg.getName() != null;
        boolean hasReasoning = msg.getReasoningContent() != null;
        // needReasoning 由 applyDeepSeekFixes 决定：
        // true 表示当前轮次，或思考模式下该片段包含工具调用。
        boolean shouldRemoveReasoning = hasReasoning && !needReasoning;

        if (!hasName && !shouldRemoveReasoning) {
            return msg;
        }

        // 构建新消息，移除 name 字段及过时推理内容。
        OpenAIMessage.Builder builder = OpenAIMessage.builder().role(msg.getRole());

        Object content = msg.getContent();
        if (content instanceof String s) {
            builder.content(s);
        } else if (content instanceof List<?> list) {
            builder.content((List<OpenAIContentPart>) list);
        }

        if (msg.getToolCalls() != null) {
            builder.toolCalls(msg.getToolCalls());
        }
        if (msg.getToolCallId() != null) {
            builder.toolCallId(msg.getToolCallId());
        }

        if (needReasoning && hasReasoning) {
            builder.reasoningContent(msg.getReasoningContent());
        }

        return builder.build();
    }
}
