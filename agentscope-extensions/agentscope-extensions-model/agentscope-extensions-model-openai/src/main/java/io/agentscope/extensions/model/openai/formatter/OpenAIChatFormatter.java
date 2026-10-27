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

import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.dto.OpenAITool;
import io.agentscope.extensions.model.openai.dto.OpenAIToolFunction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI Chat Completion HTTP API 的格式化器，在 AgentScope Msg 与 OpenAI DTO 之间转换。
 *
 * <p>配合基于 HTTP 的 OpenAI 客户端，用于标准 OpenAI GPT 模型，支持：
 * <ul><li>全部采样参数，包括 temperature、top_p 与惩罚参数。</li>
 * <li>支持严格模式的工具调用。</li><li>完整的 tool_choice 选项：auto、none、required 和指定工具。</li></ul>
 */
public class OpenAIChatFormatter extends OpenAIBaseFormatter {

    private static final Logger log = LoggerFactory.getLogger(OpenAIChatFormatter.class);

    public OpenAIChatFormatter() {
        super();
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        List<OpenAIMessage> result = new ArrayList<>();
        for (Msg msg : msgs) {
            boolean hasMedia = hasMediaContent(msg);
            OpenAIMessage openAIMsg = convertMessage(msg, hasMedia);
            if (openAIMsg != null) {
                result.add(openAIMsg);
            }
        }
        return result;
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs, GenerateOptions options) {
        List<OpenAIMessage> result = new ArrayList<>();
        List<Boolean> cacheDirectives = new ArrayList<>();
        for (Msg msg : msgs) {
            OpenAIMessage openAIMsg = convertMessage(msg, hasMediaContent(msg));
            if (openAIMsg != null) {
                result.add(openAIMsg);
                cacheDirectives.add(cacheControlDirective(msg));
            }
        }
        applyAutomaticCacheControl(result, cacheDirectives, options);
        return result;
    }

    /**
     * 将一条 AgentScope 消息转换为 OpenAI 消息格式。
     *
     * <p>目标 API 的消息形状略有差异时，提供商专用子类可重写此方法。
     *
     * @param msg 待转换的消息
     * @param hasMedia 消息是否包含媒体内容
     * @return 转换后的 OpenAI 消息
     */
    protected OpenAIMessage convertMessage(Msg msg, boolean hasMedia) {
        return messageConverter.convertToMessage(msg, hasMedia);
    }

    @Override
    public void applyOptions(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions) {

        // 应用温度。
        Double temperature =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getTemperature);
        if (temperature != null) {
            request.setTemperature(temperature);
        }
        // 应用推理投入程度。
        String reasoningEffort =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getReasoningEffort);
        if (reasoningEffort != null) {
            request.setReasoningEffort(reasoningEffort);
        }
        // 应用思考预算。
        Integer thinkingBudget =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getThinkingBudget);
        if (thinkingBudget != null) {
            request.setThinkingBudget(thinkingBudget);
        }

        // 应用 top_p。
        Double topP = getOptionOrDefault(options, defaultOptions, GenerateOptions::getTopP);
        if (topP != null) {
            request.setTopP(topP);
        }

        // 应用频率惩罚。
        Double frequencyPenalty =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getFrequencyPenalty);
        if (frequencyPenalty != null) {
            request.setFrequencyPenalty(frequencyPenalty);
        }

        // 应用出现惩罚。
        Double presencePenalty =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getPresencePenalty);
        if (presencePenalty != null) {
            request.setPresencePenalty(presencePenalty);
        }

        applyMaxTokens(request, options, defaultOptions);

        // 应用随机种子。
        Long seed = getOptionOrDefault(options, defaultOptions, GenerateOptions::getSeed);
        if (seed != null) {
            if (seed < Integer.MIN_VALUE || seed > Integer.MAX_VALUE) {
                log.warn("Seed value {} is out of Integer range, will be truncated", seed);
            }
            request.setSeed(seed.intValue());
        }

        // 应用并行工具调用设置。
        Boolean parallelToolCalls =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getParallelToolCalls);
        if (parallelToolCalls != null) {
            request.setParallelToolCalls(parallelToolCalls);
        }

        // 应用响应格式。
        ResponseFormat responseFormat =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getResponseFormat);
        if (responseFormat != null) {
            request.setResponseFormat(responseFormat);
        }

        // 最后应用附加请求体参数，以允许覆盖前面的值。
        applyAdditionalBodyParams(request, defaultOptions);
        applyAdditionalBodyParams(request, options);
    }

    protected void applyMaxTokens(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        Integer maxTokens =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getMaxTokens);
        if (maxTokens != null) {
            request.setMaxTokens(maxTokens);
        }

        Integer maxCompletionTokens =
                getOptionOrDefault(
                        options, defaultOptions, GenerateOptions::getMaxCompletionTokens);
        if (maxCompletionTokens != null) {
            request.setMaxCompletionTokens(maxCompletionTokens);
        }
    }

    @Override
    public void applyTools(OpenAIRequest request, List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }

        List<OpenAITool> openAITools = new ArrayList<>();

        try {
            for (ToolSchema toolSchema : tools) {
                OpenAIToolFunction.Builder functionBuilder =
                        OpenAIToolFunction.builder()
                                .name(toolSchema.getName())
                                .description(toolSchema.getDescription())
                                .parameters(toolSchema.getParameters());

                // 仅在提供商支持时应用 strict 参数。
                if (supportsStrict() && toolSchema.getStrict() != null) {
                    functionBuilder.strict(toolSchema.getStrict());
                }

                OpenAIToolFunction function = functionBuilder.build();
                openAITools.add(OpenAITool.function(function));
                log.debug(
                        "Converted tool to OpenAI format: {} (strict: {})",
                        toolSchema.getName(),
                        supportsStrict() ? toolSchema.getStrict() : "not supported");
            }
        } catch (Exception e) {
            log.error("Failed to convert tools to OpenAI format: {}", e.getMessage(), e);
        }

        if (!openAITools.isEmpty()) {
            request.setTools(openAITools);
        }
    }

    /**
     * 返回目标 API 是否支持工具定义中的 strict 参数。
     *
     * <p>子类可重写此方法，为不支持该参数的提供商（如 DeepSeek）禁用 strict。
     *
     * @return 支持 strict 参数时返回 true，否则返回 false
     */
    protected boolean supportsStrict() {
        return true;
    }

    @Override
    public void applyToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        // 仅在存在工具时应用 tool_choice。
        if (request.getTools() == null || request.getTools().isEmpty()) {
            return;
        }

        if (toolChoice == null || toolChoice instanceof ToolChoice.Auto) {
            request.setToolChoice("auto");
        } else if (toolChoice instanceof ToolChoice.None) {
            request.setToolChoice("none");
        } else if (toolChoice instanceof ToolChoice.Required) {
            request.setToolChoice("required");
        } else if (toolChoice instanceof ToolChoice.Specific specific) {
            // OpenAI 格式：{"type": "function", "function": {"name": "..."}}
            Map<String, Object> namedToolChoice = new HashMap<>();
            namedToolChoice.put("type", "function");
            Map<String, Object> function = new HashMap<>();
            function.put("name", specific.toolName());
            namedToolChoice.put("function", function);
            request.setToolChoice(namedToolChoice);
        } else {
            // 未知类型回退为 auto。
            request.setToolChoice("auto");
        }

        log.debug(
                "Applied tool choice: {}",
                toolChoice != null ? toolChoice.getClass().getSimpleName() : "Auto");
    }

    /**
     * 将 GenerateOptions 中的附加请求体参数应用到 OpenAI 请求。
     * 未知参数透传到 extraParams。
     */
    protected void applyAdditionalBodyParams(OpenAIRequest request, GenerateOptions opts) {
        if (opts == null) return;
        Map<String, Object> params = opts.getAdditionalBodyParams();
        if (params != null && !params.isEmpty()) {
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                switch (key) {
                    case "stop":
                        if (value instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<String> stopList = (List<String>) value;
                            request.setStop(stopList);
                        }
                        break;
                    case "response_format":
                        if (value instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> formatMap = (Map<String, Object>) value;
                            request.setResponseFormat(formatMap);
                        }

                        if (value instanceof ResponseFormat responseFormat) {
                            request.setResponseFormat(responseFormat);
                        }

                        break;
                    default:
                        // 将未知参数加入 extraParams。
                        request.addExtraParam(key, value);
                        log.debug("Additional body parameter '{}' added to extraParams", key);
                        break;
                }
            }
            log.debug("Applied {} additional body params to OpenAI request", params.size());
        }
    }
}
