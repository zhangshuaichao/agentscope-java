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
package io.agentscope.extensions.model.dashscope.formatter;

import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.dashscope.dto.DashScopeFunction;
import io.agentscope.extensions.model.dashscope.dto.DashScopeParameters;
import io.agentscope.extensions.model.dashscope.dto.DashScopeTool;
import io.agentscope.extensions.model.dashscope.dto.DashScopeToolCall;
import io.agentscope.extensions.model.dashscope.dto.DashScopeToolFunction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 处理 DashScope API 的工具注册与选项应用。
 *
 * <p>将 AgentScope 工具模式及选项转换为 DashScope DTO 格式。
 */
public class DashScopeToolsHelper {

    private static final Logger log = LoggerFactory.getLogger(DashScopeToolsHelper.class);

    public DashScopeToolsHelper() {}

    /**
     * 将 GenerateOptions 应用到 DashScopeParameters。
     *
     * @param params 要修改的 DashScope 参数
     * @param options 要应用的生成选项
     * @param defaultOptions options 为 null 时使用的默认选项
     */
    public void applyOptions(
            DashScopeParameters params, GenerateOptions options, GenerateOptions defaultOptions) {
        Double temperature = getOption(options, defaultOptions, GenerateOptions::getTemperature);
        if (temperature != null) {
            params.setTemperature(temperature);
        }

        Double topP = getOption(options, defaultOptions, GenerateOptions::getTopP);
        if (topP != null) {
            params.setTopP(topP);
        }

        Integer maxTokens = getOption(options, defaultOptions, GenerateOptions::getMaxTokens);
        if (maxTokens != null) {
            params.setMaxTokens(maxTokens);
        }

        Integer thinkingBudget =
                getOption(options, defaultOptions, GenerateOptions::getThinkingBudget);
        if (thinkingBudget != null) {
            params.setThinkingBudget(thinkingBudget);
            params.setEnableThinking(true);
        }

        Integer topK = getOption(options, defaultOptions, GenerateOptions::getTopK);
        if (topK != null) {
            params.setTopK(topK);
        }

        Long seed = getOption(options, defaultOptions, GenerateOptions::getSeed);
        if (seed != null) {
            params.setSeed(seed.intValue());
        }

        Double frequencyPenalty =
                getOption(options, defaultOptions, GenerateOptions::getFrequencyPenalty);
        if (frequencyPenalty != null) {
            params.setFrequencyPenalty(frequencyPenalty);
        }

        Double presencePenalty =
                getOption(options, defaultOptions, GenerateOptions::getPresencePenalty);
        if (presencePenalty != null) {
            params.setPresencePenalty(presencePenalty);
        }

        // 应用并行工具调用设置。
        Boolean parallelToolCalls =
                getOption(options, defaultOptions, GenerateOptions::getParallelToolCalls);
        if (parallelToolCalls != null) {
            params.setParallelToolCalls(parallelToolCalls);
        }

        ResponseFormat responseFormat =
                getOption(options, defaultOptions, GenerateOptions::getResponseFormat);
        if (responseFormat != null) {
            params.setResponseFormat(responseFormat);
        }

        String reasoningEffort =
                getOption(options, defaultOptions, GenerateOptions::getReasoningEffort);
        if (reasoningEffort != null) {
            params.setReasoningEffort(reasoningEffort);
        }
    }

    /**
     * 获取选项值并回退到默认值的辅助方法。
     */
    private <T> T getOption(
            GenerateOptions options,
            GenerateOptions defaultOptions,
            Function<GenerateOptions, T> getter) {
        if (options != null) {
            T value = getter.apply(options);
            if (value != null) {
                return value;
            }
        }
        if (defaultOptions != null) {
            return getter.apply(defaultOptions);
        }
        return null;
    }

    /**
     * 将工具模式转换为 DashScope 工具列表。
     *
     * @param tools 待转换的工具模式列表（可为 null 或空）
     * @return DashScopeTool 对象列表
     */
    public List<DashScopeTool> convertTools(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }

        List<DashScopeTool> result = new ArrayList<>();
        for (ToolSchema t : tools) {
            Map<String, Object> parameters = new HashMap<>();
            if (t.getParameters() != null) {
                parameters.putAll(t.getParameters());
            }

            DashScopeToolFunction function =
                    DashScopeToolFunction.builder()
                            .name(t.getName())
                            .description(t.getDescription())
                            .parameters(parameters)
                            .build();

            result.add(DashScopeTool.function(function));
        }

        log.debug("Converted {} tools to DashScope format", result.size());
        return result;
    }

    /**
     * 将工具应用到 DashScopeParameters。
     *
     * @param params 要修改的 DashScope 参数
     * @param tools 要应用的工具模式列表（可为 null 或空）
     */
    public void applyTools(DashScopeParameters params, List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }
        params.setTools(convertTools(tools));
    }

    /**
     * 将工具选择转换为 DashScope 格式。
     *
     * <p>DashScope API 支持：
     * <ul>
     * <li>字符串 "auto"：由模型决定是否调用工具（默认）。</li>
     * <li>字符串 "none"：禁用工具调用。</li>
     * <li>对象 {"type": "function", "function": {"name": "tool_name"}}：强制指定工具。</li>
     * </ul>
     *
     * @param toolChoice 工具选择配置（null 表示自动/默认）
     * @return 转换后的工具选择对象
     */
    public Object convertToolChoice(ToolChoice toolChoice) {
        if (toolChoice == null) {
            return null;
        }

        if (toolChoice instanceof ToolChoice.Auto) {
            log.debug("ToolChoice.Auto: returning 'auto'");
            return "auto";
        } else if (toolChoice instanceof ToolChoice.None) {
            log.debug("ToolChoice.None: returning 'none'");
            return "none";
        } else if (toolChoice instanceof ToolChoice.Required) {
            log.warn(
                    "ToolChoice.Required is not directly supported by DashScope API. Using 'auto'"
                            + " instead.");
            return "auto";
        } else if (toolChoice instanceof ToolChoice.Specific specific) {
            log.debug("ToolChoice.Specific: forcing tool '{}'", specific.toolName());
            Map<String, Object> choice = new HashMap<>();
            choice.put("type", "function");
            Map<String, String> function = new HashMap<>();
            function.put("name", specific.toolName());
            choice.put("function", function);
            return choice;
        }

        return null;
    }

    /**
     * 将工具选择配置应用到 DashScopeParameters。
     *
     * @param params 要修改的 DashScope 参数
     * @param toolChoice 工具选择配置（null 表示自动/默认）
     */
    public void applyToolChoice(DashScopeParameters params, ToolChoice toolChoice) {
        Object choice = convertToolChoice(toolChoice);
        if (choice != null) {
            params.setToolChoice(choice);
        }
    }

    /**
     * 将 ToolUseBlock 列表转换为 DashScope ToolCall 格式。
     *
     * @param toolBlocks 待转换的工具使用块
     * @return DashScopeToolCall 列表（输入为 null 或空时返回空列表）
     */
    public List<DashScopeToolCall> convertToolCalls(List<ToolUseBlock> toolBlocks) {
        if (toolBlocks == null || toolBlocks.isEmpty()) {
            return List.of();
        }

        List<DashScopeToolCall> result = new ArrayList<>();

        for (ToolUseBlock toolUse : toolBlocks) {
            if (toolUse == null) {
                log.warn("Skipping null ToolUseBlock in convertToolCalls");
                continue;
            }

            String argsJson = JsonUtils.resolveToolCallArgsJson(toolUse);

            DashScopeFunction function = DashScopeFunction.of(toolUse.getName(), argsJson);
            DashScopeToolCall toolCall =
                    DashScopeToolCall.builder()
                            .id(toolUse.getId())
                            .type("function")
                            .function(function)
                            .build();

            result.add(toolCall);
        }

        return result;
    }

    /**
     * 合并调用选项与默认选项中的附加请求头。
     *
     * <p>先应用默认选项，再由调用选项覆盖。
     *
     * @param options 主选项（高优先级）
     * @param defaultOptions 回退选项（低优先级）
     * @return 合并后的请求头映射；两者都为空时为 null
     */
    public Map<String, String> mergeAdditionalHeaders(
            GenerateOptions options, GenerateOptions defaultOptions) {
        Map<String, String> result = new HashMap<>();

        if (defaultOptions != null && !defaultOptions.getAdditionalHeaders().isEmpty()) {
            result.putAll(defaultOptions.getAdditionalHeaders());
        }
        if (options != null && !options.getAdditionalHeaders().isEmpty()) {
            result.putAll(options.getAdditionalHeaders());
        }

        return result.isEmpty() ? null : result;
    }

    /**
     * 合并调用选项与默认选项中的附加请求体参数。
     *
     * <p>先应用默认选项，再由调用选项覆盖。
     *
     * @param options 主选项（高优先级）
     * @param defaultOptions 回退选项（低优先级）
     * @return 合并后的请求体参数映射；两者都为空时为 null
     */
    public Map<String, Object> mergeAdditionalBodyParams(
            GenerateOptions options, GenerateOptions defaultOptions) {
        Map<String, Object> result = new HashMap<>();

        if (defaultOptions != null && !defaultOptions.getAdditionalBodyParams().isEmpty()) {
            result.putAll(defaultOptions.getAdditionalBodyParams());
        }
        if (options != null && !options.getAdditionalBodyParams().isEmpty()) {
            result.putAll(options.getAdditionalBodyParams());
        }

        return result.isEmpty() ? null : result;
    }

    /**
     * 合并调用选项与默认选项中的附加查询参数。
     *
     * <p>先应用默认选项，再由调用选项覆盖。
     *
     * @param options 主选项（高优先级）
     * @param defaultOptions 回退选项（低优先级）
     * @return 合并后的查询参数映射；两者都为空时为 null
     */
    public Map<String, String> mergeAdditionalQueryParams(
            GenerateOptions options, GenerateOptions defaultOptions) {
        Map<String, String> result = new HashMap<>();

        if (defaultOptions != null && !defaultOptions.getAdditionalQueryParams().isEmpty()) {
            result.putAll(defaultOptions.getAdditionalQueryParams());
        }
        if (options != null && !options.getAdditionalQueryParams().isEmpty()) {
            result.putAll(options.getAdditionalQueryParams());
        }

        return result.isEmpty() ? null : result;
    }
}
