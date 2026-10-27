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
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kimi（Moonshot AI）模型的格式化器，包括 kimi-k3、kimi-k2.7-code、kimi-k2.6、kimi-k2.5 和 moonshot-v1 系列。
 *
 * <p>适配本版本所依据的 Kimi 开放平台聊天补全 API
 * （{@code https://api.moonshot.cn/v1/chat/completions}）：
 * <ul><li>{@code kimi-*} 模型的 {@code temperature} / {@code top_p} / {@code n} /
 * {@code frequency_penalty} / {@code presence_penalty} 由平台固定，其他值会被 API 拒绝，
 * 因此从请求中移除；{@code moonshot-v1} 系列仍接受这些参数，予以保留。</li>
 * <li>{@code reasoning_effort}（{@code "low"} / {@code "high"} / {@code "max"}，默认
 * {@code "max"}）仅由 {@code kimi-k3} 支持，其他模型移除此字段。</li>
 * <li>{@code thinking_budget} 不是 Kimi 参数，始终移除；K2.x 思考改由 {@code thinking} 请求体参数控制，见下文。</li>
 * <li>文档仅列出 {@code max_completion_tokens}；将 {@code max_tokens} 在前者未设置时映射到
 * {@code max_completion_tokens}。推理 token 也计入 {@code max_completion_tokens}，
 * 因此所依据指南建议思考模型使用 {@code max_completion_tokens >= 16000}。</li>
 * <li>{@code tool_choice} 的 {@code auto} / {@code none} 对所有模型支持；
 * K2.x 系列的 {@code required} 降级为 {@code auto}。强制指定函数与开启思考不兼容，会返回 HTTP 400，
 * 因此降级为 {@code auto}，适用于始终思考的模型（{@code kimi-k3}、{@code kimi-k2.7-code}）；
 * {@code kimi-k2.6} / {@code kimi-k2.5} 未显式禁用思考时也降级。</li>
 * <li>Kimi 未记录工具定义中的 {@code strict} 参数，因此不发送。</li>
 * <li>请求历史保留助手的 {@code reasoning_content}，继承基础消息转换器行为，
 * 满足 {@code kimi-k3} / {@code kimi-k2.7-code} 的 Preserved Thinking，以及
 * {@code thinking.keep = "all"} 对 {@code kimi-k2.6} 的要求。</li></ul>
 *
 * <p>思考模式：{@code kimi-k2.6} / {@code kimi-k2.5} 接受 {@code thinking} 请求体参数，
 * 例如 {@code {"type": "disabled"}}，可通过
 * {@code GenerateOptions.additionalBodyParam("thinking", Map.of("type", "disabled"))} 透传。
 * {@code kimi-k3} 则使用顶层 {@code reasoning_effort} 选项（{@code GenerateOptions.reasoningEffort}）。
 * 结构化输出通过 {@code response_format = {"type": "json_schema", "json_schema": {...}}} 启用。
 * 同时存在工具时，Kimi 优先处理 {@code response_format} 而非工具调用，因此提供者对带工具组合保持回退路径。
 *
 * <p>用法：
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new KimiFormatter())
 *     .modelName("kimi-k3")
 *     .baseUrl("https://api.moonshot.cn/v1")
 *     .apiKey(apiKey)
 *     .build();
 * }</pre>
 *
 * @see <a href="https://platform.kimi.com/docs/api/overview">Kimi API 概览</a>
 * @see <a href="https://platform.kimi.com/docs/api/models-overview">Kimi 模型参数参考</a>
 * @see <a href="https://platform.kimi.com/docs/guide/use-thinking-models">Kimi 思考模式指南</a>
 * @see <a href="https://platform.kimi.com/docs/guide/use-tool-choice">Kimi 工具选择指南</a>
 * @see <a href="https://platform.kimi.com/docs/guide/response_format">结构化输出指南</a>
 */
public class KimiFormatter extends OpenAIChatFormatter {

    private static final Logger log = LoggerFactory.getLogger(KimiFormatter.class);

    public KimiFormatter() {
        super();
    }

    @Override
    protected boolean supportsStrict() {
        return false;
    }

    @Override
    public void applyOptions(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        super.applyOptions(request, options, defaultOptions);
        sanitizeKimiRequest(request);
    }

    @Override
    public void applyToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        applyKimiToolChoice(request, toolChoice);
    }

    /**
     * 判断模型是否属于采样参数由平台固定的 Kimi K 系列，
     * 例如 {@code kimi-k3}、{@code kimi-k2.7-code}、{@code kimi-k2.6}。
     *
     * @param model 请求中的模型名称，可为 null
     * @return {@code kimi-*} 模型返回 true
     */
    static boolean hasFixedSamplingParams(String model) {
        return model != null && model.startsWith("kimi-");
    }

    /**
     * 判断模型是否始终启用思考且无法关闭，
     * 例如 {@code kimi-k3} 和 {@code kimi-k2.7-code} 系列。
     *
     * @param model 请求中的模型名称，可为 null
     * @return 始终思考的 Kimi 模型返回 true
     */
    static boolean isAlwaysThinkingModel(String model) {
        return model != null && (model.startsWith("kimi-k3") || model.startsWith("kimi-k2.7-code"));
    }

    /**
     * 判断模型是否默认启用思考，但允许通过顶层 {@code thinking} 请求体参数关闭。
     *
     * @param model 请求中的模型名称，可为 null
     * @return 可配置思考模式的 Kimi 模型返回 true
     */
    static boolean hasConfigurableThinking(String model) {
        return model != null && (model.startsWith("kimi-k2.6") || model.startsWith("kimi-k2.5"));
    }

    /**
     * 判断模型是否支持顶层 {@code reasoning_effort} 参数。
     * 按所依据的官方参数参考，仅 {@code kimi-k3} 支持。
     *
     * @param model 请求中的模型名称，可为 null
     * @return 支持 {@code reasoning_effort} 时返回 true
     */
    static boolean supportsReasoningEffort(String model) {
        return model != null && model.startsWith("kimi-k3");
    }

    /**
     * 判断模型是否支持 {@code tool_choice = "required"}。
     * 按所依据的官方参数参考，{@code kimi-k3} 支持，而列出的 K2.x 模型会拒绝。
     * 为保持兼容，非 K2 模型透传该设置。
     *
     * @param model 请求中的模型名称，可为 null
     * @return 支持 {@code required} 时返回 true
     */
    static boolean supportsRequiredToolChoice(String model) {
        return model == null || !model.startsWith("kimi-k2");
    }

    /**
     * 根据 Kimi Chat Completions API 参考规范化请求。
     *
     * <p>执行的适配：
     * <ul><li>将 {@code temperature} / {@code top_p} / {@code n} /
     * {@code frequency_penalty} / {@code presence_penalty} 从 {@code kimi-*} 模型请求中移除，
     * 这些参数由平台固定，其他值会被拒绝。</li>
     * <li>移除 {@code reasoning_effort}，但 {@code kimi-k3} 模型例外。</li>
     * <li>始终移除 {@code thinking_budget}，它不是 Kimi 参数。</li>
     * <li>将 {@code max_tokens} 映射到 {@code max_completion_tokens}，仅在后者未设置时执行。</li></ul>
     *
     * <p>此方法为静态方法，以便与 {@link KimiMultiAgentFormatter} 共享。
     *
     * @param request 待规范化的请求
     */
    protected static void sanitizeKimiRequest(OpenAIRequest request) {
        String model = request.getModel();

        if (hasFixedSamplingParams(model)) {
            if (request.getTemperature() != null) {
                log.debug(
                        "Kimi model {} does not allow overriding temperature, removing it", model);
                request.setTemperature(null);
            }
            if (request.getTopP() != null) {
                log.debug("Kimi model {} does not allow overriding top_p, removing it", model);
                request.setTopP(null);
            }
            if (request.getN() != null) {
                log.debug("Kimi model {} does not allow overriding n, removing it", model);
                request.setN(null);
            }
            if (request.getFrequencyPenalty() != null) {
                log.debug(
                        "Kimi model {} does not allow overriding frequency_penalty, removing it",
                        model);
                request.setFrequencyPenalty(null);
            }
            if (request.getPresencePenalty() != null) {
                log.debug(
                        "Kimi model {} does not allow overriding presence_penalty, removing it",
                        model);
                request.setPresencePenalty(null);
            }
        }

        if (request.getReasoningEffort() != null && !supportsReasoningEffort(model)) {
            log.debug(
                    "Kimi model {} does not support reasoning_effort (kimi-k3 only), removing it;"
                            + " use the 'thinking' body param on K2.x models instead",
                    model);
            request.setReasoningEffort(null);
        }

        if (request.getThinkingBudget() != null) {
            log.debug(
                    "Kimi does not support thinking_budget, removing it from the request; use the"
                            + " 'thinking' body param (K2.x) or reasoning_effort (kimi-k3)"
                            + " instead");
            request.setThinkingBudget(null);
        }

        // Kimi 文档仅列出 max_completion_tokens，将旧版 max_tokens 映射到它。
        if (request.getMaxTokens() != null) {
            if (request.getMaxCompletionTokens() == null) {
                log.debug("Kimi only supports max_completion_tokens, mapping max_tokens to it");
                request.setMaxCompletionTokens(request.getMaxTokens());
            }
            request.setMaxTokens(null);
        }
    }

    /**
     * 应用 Kimi 专用工具选择处理。
     *
     * <p>依据所采用的官方工具选择指南：
     * <ul><li>{@code auto} / {@code none} 在所有模型上支持。</li>
     * <li>{@code required} 仅由 {@code kimi-k3} 支持；K2.x 会拒绝，因此降级为 {@code auto}。</li>
     * <li>强制指定函数（{@code {"type": "function", ...}}）与启用思考不兼容，会返回 HTTP 400；
     * 始终思考的模型降级为 {@code auto}，而 {@code kimi-k2.6} / {@code kimi-k2.5}
     * 除非通过 {@code thinking} 请求体参数明确关闭思考，否则也降级。</li></ul>
     *
     * <p>{@link KimiModelProvider} 将上述 {@code Specific} 降级行为反映到构建模型的
     * {@code supportsToolChoiceSpecific} 标志中；思考模型列表变化时必须同步更新两处。
     *
     * <p>此方法为静态方法，以便与 {@link KimiMultiAgentFormatter} 共享。
     *
     * @param request 要应用工具选择的请求
     * @param toolChoice 请求的工具选择
     */
    protected static void applyKimiToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        if (request.getTools() == null || request.getTools().isEmpty()) {
            return;
        }

        String model = request.getModel();

        if (toolChoice == null || toolChoice instanceof ToolChoice.Auto) {
            request.setToolChoice("auto");
        } else if (toolChoice instanceof ToolChoice.None) {
            request.setToolChoice("none");
        } else if (toolChoice instanceof ToolChoice.Required) {
            if (supportsRequiredToolChoice(model)) {
                request.setToolChoice("required");
            } else {
                log.info(
                        "Kimi model {} does not support tool_choice='required' (kimi-k3 only),"
                                + " degrading to 'auto'",
                        model);
                request.setToolChoice("auto");
            }
        } else if (toolChoice instanceof ToolChoice.Specific specific) {
            if (isAlwaysThinkingModel(model)
                    || (hasConfigurableThinking(model) && !hasThinkingDisabled(request))) {
                log.warn(
                        "Kimi model {} has thinking enabled, which is incompatible with a"
                                + " specific tool_choice; degrading to 'auto'",
                        model);
                request.setToolChoice("auto");
            } else {
                Map<String, Object> namedToolChoice = new HashMap<>();
                namedToolChoice.put("type", "function");
                Map<String, Object> function = new HashMap<>();
                function.put("name", specific.toolName());
                namedToolChoice.put("function", function);
                request.setToolChoice(namedToolChoice);
                log.debug(
                        "Applied specific tool_choice '{}' for Kimi model {}; note it requires"
                                + " thinking to be disabled on thinking-capable models",
                        specific.toolName(),
                        model);
            }
        } else {
            request.setToolChoice("auto");
        }
    }

    private static boolean hasThinkingDisabled(OpenAIRequest request) {
        Map<String, Object> extraParams = request.getExtraParams();
        if (extraParams == null) {
            return false;
        }

        Object thinking = extraParams.get("thinking");
        if (!(thinking instanceof Map<?, ?> thinkingMap)) {
            return false;
        }

        return "disabled".equals(thinkingMap.get("type"));
    }
}
