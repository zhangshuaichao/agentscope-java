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
package io.agentscope.extensions.model.gemini.formatter;

import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.ThinkingConfig;
import com.google.genai.types.Tool;
import com.google.genai.types.ToolConfig;
import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Gemini 内容生成 API 的格式化器。
 *
 * <p>在 AgentScope Msg 对象与 Gemini SDK 类型之间转换：
 * <ul>
 *   <li>Msg → Content（请求格式）</li>
 *   <li>GenerateContentResponse → ChatResponse（响应解析）</li>
 *   <li>ToolSchema → Tool（工具定义）</li>
 * </ul>
 *
 * <p><b>重要 Gemini API 行为：</b>
 * <ul>
 *   <li>系统消息转为 "user" 角色（Gemini 的 contents 不支持 system 角色）</li>
 *   <li>本地工具结果是独立的 "user" 角色 Content 对象</li>
 *   <li>服务端工具结果保留在原始 "model" Content 内部</li>
 *   <li>思考内容使用 Part 对象上的 "thought" 标志</li>
 * </ul>
 */
public class GeminiChatFormatter
        extends AbstractBaseFormatter<
                Content, GenerateContentResponse, GenerateContentConfig.Builder> {

    private final GeminiMessageConverter messageConverter;
    private final GeminiResponseParser responseParser;
    private final GeminiToolsHelper toolsHelper;

    /**
     * 使用默认转换器和解析器创建新的 GeminiChatFormatter。
     */
    public GeminiChatFormatter() {
        this.messageConverter = new GeminiMessageConverter();
        this.responseParser = new GeminiResponseParser();
        this.toolsHelper = new GeminiToolsHelper();
    }

    @Override
    protected List<Content> doFormat(List<Msg> msgs) {
        return messageConverter.convertMessages(msgs);
    }

    @Override
    public ChatResponse parseResponse(GenerateContentResponse response, Instant startTime) {
        return responseParser.parseResponse(response, startTime);
    }

    @Override
    public void applyOptions(
            GenerateContentConfig.Builder configBuilder,
            GenerateOptions options,
            GenerateOptions defaultOptions) {

        // 逐个应用选项，缺失时回退到 defaultOptions
        applyFloatOption(
                GenerateOptions::getTemperature,
                options,
                defaultOptions,
                configBuilder::temperature);

        applyFloatOption(GenerateOptions::getTopP, options, defaultOptions, configBuilder::topP);

        // 应用 topK（Gemini 的 topK 使用 Float）
        applyIntegerAsFloatOption(
                GenerateOptions::getTopK, options, defaultOptions, configBuilder::topK);

        // 应用 seed
        applyLongAsIntOption(
                GenerateOptions::getSeed, options, defaultOptions, configBuilder::seed);

        applyIntegerOption(
                GenerateOptions::getMaxTokens,
                options,
                defaultOptions,
                configBuilder::maxOutputTokens);

        applyFloatOption(
                GenerateOptions::getFrequencyPenalty,
                options,
                defaultOptions,
                configBuilder::frequencyPenalty);

        applyFloatOption(
                GenerateOptions::getPresencePenalty,
                options,
                defaultOptions,
                configBuilder::presencePenalty);

        // 设置 includeThoughts 或 thinkingBudget 任意一项时应用 ThinkingConfig
        Integer thinkingBudget =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getThinkingBudget);

        if (thinkingBudget != null) {
            ThinkingConfig.Builder thinkingConfigBuilder = ThinkingConfig.builder();
            thinkingConfigBuilder.includeThoughts(true);
            thinkingConfigBuilder.thinkingBudget(thinkingBudget);
            configBuilder.thinkingConfig(thinkingConfigBuilder.build());
        }
    }

    /**
     * 应用 Float 选项，包含回退逻辑。
     */
    private void applyFloatOption(
            Function<GenerateOptions, Double> accessor,
            GenerateOptions options,
            GenerateOptions defaultOptions,
            Consumer<Float> setter) {

        Double value = getOptionOrDefault(options, defaultOptions, accessor);
        if (value != null) {
            setter.accept(value.floatValue());
        }
    }

    /**
     * 应用 Integer 选项，包含回退逻辑。
     */
    private void applyIntegerOption(
            Function<GenerateOptions, Integer> accessor,
            GenerateOptions options,
            GenerateOptions defaultOptions,
            Consumer<Integer> setter) {

        Integer value = getOptionOrDefault(options, defaultOptions, accessor);
        if (value != null) {
            setter.accept(value);
        }
    }

    /**
     * 将 Integer 选项作为 Float 应用，包含回退逻辑（用于要求 Float 的 Gemini topK）。
     */
    private void applyIntegerAsFloatOption(
            Function<GenerateOptions, Integer> accessor,
            GenerateOptions options,
            GenerateOptions defaultOptions,
            Consumer<Float> setter) {

        Integer value = getOptionOrDefault(options, defaultOptions, accessor);
        if (value != null) {
            setter.accept(value.floatValue());
        }
    }

    /**
     * 将 Long 选项作为 Integer 应用，包含回退逻辑（用于要求 Integer 的 Gemini seed）。
     */
    private void applyLongAsIntOption(
            Function<GenerateOptions, Long> accessor,
            GenerateOptions options,
            GenerateOptions defaultOptions,
            Consumer<Integer> setter) {

        Long value = getOptionOrDefault(options, defaultOptions, accessor);
        if (value != null) {
            setter.accept(value.intValue());
        }
    }

    @Override
    public void applyTools(GenerateContentConfig.Builder configBuilder, List<ToolSchema> tools) {
        Tool tool = toolsHelper.convertToGeminiTool(tools);
        if (tool != null) {
            configBuilder.tools(List.of(tool));
        }
    }

    @Override
    public void applyToolChoice(
            GenerateContentConfig.Builder configBuilder, ToolChoice toolChoice) {
        ToolConfig toolConfig = toolsHelper.convertToolChoice(toolChoice);
        if (toolConfig != null) {
            configBuilder.toolConfig(toolConfig);
        }
    }
}
