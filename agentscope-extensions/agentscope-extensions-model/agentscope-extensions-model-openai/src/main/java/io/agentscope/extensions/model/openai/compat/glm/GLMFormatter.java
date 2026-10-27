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
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 智谱 AI（Z.ai）GLM 模型（glm-5.2、glm-5.1、glm-5、glm-4.7、glm-4.6 等）的格式化器。
 *
 * <p>适配本版本所依据的智谱开放平台聊天补全 API
 * （{@code https://open.bigmodel.cn/api/paas/v4/chat/completions}）：
 * <ul><li>至少一条用户消息，否则返回错误 1214。</li><li>消息不支持 {@code name} 参数。</li>
 * <li>{@code tool_choice} 仅支持 {@code "auto"}，强制选择会降级；
 * {@code none} 则通过移除工具确保不调用工具。</li><li>工具定义不支持 {@code strict} 参数。</li>
 * <li>不支持 {@code frequency_penalty} / {@code presence_penalty} / {@code thinking_budget}，从请求中移除。</li>
 * <li>仅支持 {@code max_tokens}；{@code max_completion_tokens} 在前者未设置时映射到 {@code max_tokens}。</li>
 * <li>{@code temperature} 范围为 [0.0, 1.0]，OpenAI 可到 2.0；{@code top_p} 为 [0.01, 1.0]，超范围值截到边界。</li></ul>
 *
 * <p>思考模式：GLM-4.5 及之后模型接受 {@code thinking} 对象，例如 {@code {"type": "enabled"}}。
 * GLM-4.7 和 GLM-5 系列默认启用思考，可通过
 * {@code GenerateOptions.additionalBodyParam("thinking", Map.of("type",
 * "disabled"))} 控制，该值原样透传到请求体。GLM-5.2 还支持标准 {@code reasoning_effort}
 * 选项（{@code GenerateOptions.reasoningEffort}，默认 {@code max}），以及通过
 * {@code GenerateOptions.additionalBodyParam("tool_stream", true)} 启用工具调用参数流式输出。
 *
 * <p>用法：
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new GLMFormatter())
 *     .modelName("glm-5.2")
 *     .baseUrl("https://open.bigmodel.cn/api/paas/v4")
 *     .apiKey(apiKey)
 *     .build();
 * }</pre>
 *
 * @see <a href="https://docs.bigmodel.cn/cn/api/introduction">智谱 AI API 参考</a>
 * @see <a href="https://docs.bigmodel.cn/cn/guide/capabilities/function-calling">智谱 AI 工具调用指南</a>
 * @see <a href="https://docs.bigmodel.cn/cn/guide/develop/openai/introduction">智谱 AI OpenAI 兼容指南</a>
 */
public class GLMFormatter extends OpenAIChatFormatter {

    private static final Logger log = LoggerFactory.getLogger(GLMFormatter.class);

    public GLMFormatter() {
        super();
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        return doFormat(msgs, null);
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs, GenerateOptions options) {
        List<OpenAIMessage> messages = super.doFormat(msgs, options);
        return ensureUserMessage(messages);
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
        stripUnsupportedSamplingParams(request);
    }

    @Override
    public void applyToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        applyGLMToolChoice(request, toolChoice);
    }

    /**
     * 确保对话至少包含一条用户消息。
     *
     * <p>GLM API 要求至少一条用户消息，否则返回错误 1214；若不存在，则在末尾添加占位消息。
     *
     * <p>此方法为静态方法，以便与 {@link GLMMultiAgentFormatter} 共享。
     *
     * @param messages 待检查的消息
     * @return 已确保存在用户消息的列表
     */
    protected static List<OpenAIMessage> ensureUserMessage(List<OpenAIMessage> messages) {
        boolean hasUserMessage = messages.stream().anyMatch(msg -> "user".equals(msg.getRole()));

        if (hasUserMessage) {
            return messages;
        }

        // 没有用户消息时，GLM API 返回错误 1214。
        log.debug("GLM: adding placeholder user message to satisfy API requirement");
        List<OpenAIMessage> result = new ArrayList<>(messages);
        result.add(OpenAIMessage.builder().role("user").content("").build());
        return result;
    }

    /**
     * 应用 GLM 专用工具选择处理。
     *
     * <p>依据本版本采用的智谱 API 参考，{@code tool_choice} 默认且仅支持 {@code "auto"}。
     * 不支持的强制选择降级为 {@code "auto"}；{@link ToolChoice.None} 则改为移除工具。
     *
     * <p>此方法为静态方法，以便与 {@link GLMMultiAgentFormatter} 共享。
     *
     * @param request 要应用工具选择的请求
     * @param toolChoice 请求的工具选择
     */
    protected static void applyGLMToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        if (request.getTools() == null || request.getTools().isEmpty()) {
            return;
        }

        if (toolChoice instanceof ToolChoice.None) {
            log.info("GLM does not support tool_choice='none', removing tools from the request");
            request.setTools(null);
            request.setToolChoice(null);
            return;
        }

        if (toolChoice != null && !(toolChoice instanceof ToolChoice.Auto)) {
            log.info(
                    "GLM only supports tool_choice='auto', degrading from '{}'",
                    toolChoice.getClass().getSimpleName());
        }

        request.setToolChoice("auto");
    }

    /**
     * 根据 GLM Chat Completions API 参考规范化请求。
     *
     * <p>执行的适配：
     * <ul><li>移除不支持的 {@code frequency_penalty} / {@code presence_penalty} / {@code thinking_budget}。</li>
     * <li>不支持 {@code max_completion_tokens}，在 {@code max_tokens} 未设置时映射到 {@code max_tokens}。</li>
     * <li>{@code temperature} 限制到 [0.0, 1.0]，{@code top_p} 限制到 [0.01, 1.0]。</li></ul>
     *
     * <p>此方法为静态方法，以便与 {@link GLMMultiAgentFormatter} 共享。
     *
     * @param request 待规范化的请求
     */
    protected static void stripUnsupportedSamplingParams(OpenAIRequest request) {
        if (request.getFrequencyPenalty() != null) {
            log.debug("GLM does not support frequency_penalty, removing it from the request");
            request.setFrequencyPenalty(null);
        }
        if (request.getPresencePenalty() != null) {
            log.debug("GLM does not support presence_penalty, removing it from the request");
            request.setPresencePenalty(null);
        }
        if (request.getThinkingBudget() != null) {
            log.debug(
                    "GLM does not support thinking_budget, removing it from the request; use the"
                        + " 'thinking' body param or reasoning_effort (GLM-5.2 and later) instead");
            request.setThinkingBudget(null);
        }
        if (request.getStreamOptions() != null) {
            log.debug("GLM does not support stream_options, removing it from the request");
            request.setStreamOptions(null);
        }

        // GLM 仅支持 max_tokens，将 OpenAI 风格的 max_completion_tokens 映射到它。
        if (request.getMaxCompletionTokens() != null) {
            if (request.getMaxTokens() == null) {
                log.debug("GLM only supports max_tokens, mapping max_completion_tokens to it");
                request.setMaxTokens(request.getMaxCompletionTokens());
            }
            request.setMaxCompletionTokens(null);
        }

        normalizeSamplingRanges(request);
    }

    /**
     * 将采样参数限制到 GLM API 接受的范围，并将
     * {@code temperature = 0} 转换为 {@code do_sample = false}。
     *
     * @param request 待规范化的请求
     */
    private static void normalizeSamplingRanges(OpenAIRequest request) {
        Double temperature = request.getTemperature();
        if (temperature != null) {
            if (temperature < 0.0) {
                log.warn("GLM temperature range is [0.0, 1.0], clamping {} to 0.0", temperature);
                request.setTemperature(0.0);
            } else if (temperature > 1.0) {
                log.warn("GLM temperature range is [0.0, 1.0], clamping {} to 1.0", temperature);
                request.setTemperature(1.0);
            }
        }

        Double topP = request.getTopP();
        if (topP != null) {
            if (topP < 0.01) {
                log.warn("GLM top_p range is [0.01, 1.0], clamping {} to 0.01", topP);
                request.setTopP(0.01);
            } else if (topP > 1.0) {
                log.warn("GLM top_p range is [0.01, 1.0], clamping {} to 1.0", topP);
                request.setTopP(1.0);
            }
        }
    }
}
