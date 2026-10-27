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
package io.agentscope.extensions.model.openai.compat.minimax;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MiniMax OpenAI 兼容聊天补全的格式化器。
 *
 * <p>参见 MiniMax <a href="https://platform.minimaxi.com/docs/api-reference/text-chat-openai">聊天补全 API</a>。
 */
public class MiniMaxFormatter extends OpenAIChatFormatter {

    private static final Logger log = LoggerFactory.getLogger(MiniMaxFormatter.class);

    @Override
    public void applyOptions(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        // 先于 super 应用，使 additionalBodyParam 能覆盖 MiniMax 默认值。
        applyReasoningSplit(request);
        super.applyOptions(request, options, defaultOptions);
        removeUnsupported(request);
    }

    @Override
    protected void applyMaxTokens(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        applyMiniMaxMaxTokens(
                request,
                getOptionOrDefault(
                        options, defaultOptions, GenerateOptions::getMaxCompletionTokens),
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getMaxTokens));
    }

    @Override
    public void applyToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        applyMiniMaxToolChoice(request, toolChoice);
    }

    /**
     * 应用 MiniMax 专用工具选择处理。
     *
     * <p>MiniMax 不接受 {@code tool_choice} 字段，因此不发送该字段，使用 API 默认值（{@code auto}）。
     * 对于 {@link ToolChoice.None}，默认行为仍允许模型调用工具，所以改为移除工具定义，确保不调用工具的契约成立。
     *
     * <p>此方法为静态方法，以便与 {@link MiniMaxMultiAgentFormatter} 共享。
     *
     * @param request 要应用工具选择的请求
     * @param toolChoice 请求的工具选择
     */
    static void applyMiniMaxToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        if (toolChoice instanceof ToolChoice.None
                && request.getTools() != null
                && !request.getTools().isEmpty()) {
            log.info(
                    "MiniMax does not support tool_choice='none', removing tools from the request");
            request.setTools(null);
        }
        request.setToolChoice(null);
    }

    @Override
    protected boolean supportsStrict() {
        return false;
    }

    static void applyReasoningSplit(OpenAIRequest request) {
        // 将 MiniMax 思考内容拆到 OpenAI 兼容推理字段，使共享的
        // OpenAIResponseParser 能将其转换为 ThinkingBlock。
        request.addExtraParam("reasoning_split", true);
    }

    static void applyMiniMaxMaxTokens(
            OpenAIRequest request, Integer maxCompletionTokens, Integer maxTokens) {
        if (maxCompletionTokens != null) {
            request.setMaxCompletionTokens(maxCompletionTokens);
            return;
        }

        if (maxTokens != null) {
            // MiniMax 弃用 max_tokens，改用 max_completion_tokens。
            request.setMaxCompletionTokens(maxTokens);
        }
    }

    static void removeUnsupported(OpenAIRequest request) {
        request.setThinkingBudget(null);
        request.setReasoningEffort(null);
        request.setFrequencyPenalty(null);
        request.setPresencePenalty(null);
        request.setParallelToolCalls(null);
        request.setResponseFormat(null);
        request.setSeed(null);
    }
}
