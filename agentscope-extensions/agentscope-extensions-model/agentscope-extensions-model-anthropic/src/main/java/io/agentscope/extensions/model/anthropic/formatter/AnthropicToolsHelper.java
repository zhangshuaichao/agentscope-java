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
package io.agentscope.extensions.model.anthropic.formatter;

import static com.anthropic.models.messages.ToolChoice.ofAny;
import static com.anthropic.models.messages.ToolChoice.ofAuto;
import static com.anthropic.models.messages.ToolChoice.ofNone;
import static com.anthropic.models.messages.ToolChoice.ofTool;

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoiceAny;
import com.anthropic.models.messages.ToolChoiceAuto;
import com.anthropic.models.messages.ToolChoiceNone;
import com.anthropic.models.messages.ToolChoiceTool;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.anthropic.tool.AnthropicServerTool;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Anthropic API 工具注册和选项应用的辅助类。 */
public class AnthropicToolsHelper {

    private static final Logger log = LoggerFactory.getLogger(AnthropicToolsHelper.class);

    /**
     * 向消息创建参数构建器应用工具。
     *
     * @param builder 消息创建参数构建器
     * @param tools 工具模式列表
     * @param options 包含工具选择的生成选项
     */
    public static void applyTools(
            MessageCreateParams.Builder builder, List<ToolSchema> tools, GenerateOptions options) {
        applyTools(builder, tools, options, null);
    }

    /**
     * 向消息创建参数构建器应用工具，可选配置缓存 TTL。
     *
     * @param builder 消息创建参数构建器
     * @param tools 工具模式列表
     * @param options 包含工具选择的生成选项
     * @param cacheTtl 临时缓存控制的 TTL，null 或空时默认 5m
     */
    public static void applyTools(
            MessageCreateParams.Builder builder,
            List<ToolSchema> tools,
            GenerateOptions options,
            String cacheTtl) {
        applyTools(builder, tools, List.of(), options, cacheTtl);
    }

    /**
     * 将客户端工具和 Anthropic 服务端工具作为统一工具集应用。
     *
     * <p>Anthropic 按名称选择工具，因此先验证组合集合，再添加到请求。
     * 只有完整集合装配完成后，才解析工具选择与并行工具使用选项。
     *
     * @param builder Anthropic 请求构建器
     * @param tools 客户端工具模式
     * @param serverTools Anthropic 内置服务端工具
     * @param options 有效生成选项
     * @param cacheTtl 提示词缓存标记的 TTL
     */
    public static void applyTools(
            MessageCreateParams.Builder builder,
            List<ToolSchema> tools,
            List<AnthropicServerTool> serverTools,
            GenerateOptions options,
            String cacheTtl) {
        List<ToolSchema> clientTools = tools != null ? tools : List.of();
        List<AnthropicServerTool> anthropicServerTools =
                serverTools != null ? serverTools : List.of();
        if (clientTools.isEmpty() && anthropicServerTools.isEmpty()) {
            return;
        }

        validateToolNames(clientTools, anthropicServerTools);

        boolean cacheControlEnabled =
                options != null && Boolean.TRUE.equals(options.getCacheControl());

        for (AnthropicServerTool serverTool : anthropicServerTools) {
            builder.addTool(serverTool.toToolUnion());
        }

        // 转换并添加客户端工具。启用提示词缓存时，将最后一个客户端
        // 工具定义标记为 cache_control，使完整工具前缀可缓存。
        if (!clientTools.isEmpty()) {
            for (int i = 0; i < clientTools.size(); i++) {
                ToolSchema schema = clientTools.get(i);
                Tool.Builder toolBuilder =
                        Tool.builder()
                                .name(schema.getName())
                                .description(schema.getDescription())
                                .inputSchema(convertToJsonValue(schema.getParameters()));

                if (cacheControlEnabled && i == clientTools.size() - 1) {
                    toolBuilder.cacheControl(AnthropicBaseFormatter.buildCacheControl(cacheTtl));
                }

                builder.addTool(toolBuilder.build());
            }
        }

        // 解析有效 parallelToolCalls 与 toolChoice。
        Boolean parallelToolCalls = options != null ? options.getParallelToolCalls() : null;
        ToolChoice toolChoice = options != null ? options.getToolChoice() : null;

        if (toolChoice != null) {
            // 显式工具选择：一并传递 parallelToolCalls。
            applyToolChoice(builder, toolChoice, parallelToolCalls);
        } else if (parallelToolCalls != null) {
            // 没有显式工具选择，但设置了 parallelToolCalls：
            // Anthropic 要求 disable_parallel_tool_use 位于 tool_choice 对象中，
            // 因此创建隐式 Auto，并设置 disableParallelToolUse。
            applyToolChoice(builder, new ToolChoice.Auto(), parallelToolCalls);
        }
    }

    private static void validateToolNames(
            List<ToolSchema> clientTools, List<AnthropicServerTool> serverTools) {
        Set<String> names = new HashSet<>();
        for (ToolSchema tool : clientTools) {
            if (!names.add(tool.getName())) {
                throw duplicateToolName(tool.getName());
            }
        }
        for (AnthropicServerTool tool : serverTools) {
            String name = tool.getName();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Anthropic server tool name must not be blank");
            }
            if (!names.add(name)) {
                throw duplicateToolName(name);
            }
        }
    }

    private static IllegalArgumentException duplicateToolName(String name) {
        return new IllegalArgumentException(
                "Anthropic tool names must be unique; duplicate tool name: " + name);
    }

    /** 将工具参数映射转换为 Anthropic JsonValue。 */
    private static JsonValue convertToJsonValue(Object parameters) {
        try {
            return JsonValue.from(ObjectMappers.jsonMapper().valueToTree(parameters));
        } catch (Exception e) {
            log.error("Failed to convert tool parameters to JsonValue", e);
            return JsonValue.from(null);
        }
    }

    /**
     * 向构建器应用工具选择。
     *
     * <p>Anthropic 没有顶层 {@code parallel_tool_calls} 字段，而是通过
     * {@code disable_parallel_tool_use} 控制并行调用，该字段位于 {@code
     * tool_choice} 对象内。映射关系为 {@code disable_parallel_tool_use = !parallelToolCalls}。
     * {@link ToolChoiceNone} 不支持此子字段，因此该情况下忽略它。
     *
     * @param parallelToolCalls 有效的 {@code parallelToolCalls} 选项；{@code null} 保持 Anthropic 默认行为
     */
    private static void applyToolChoice(
            MessageCreateParams.Builder builder, ToolChoice toolChoice, Boolean parallelToolCalls) {
        boolean disableParallel = parallelToolCalls != null && !parallelToolCalls;

        if (toolChoice instanceof ToolChoice.Auto) {
            ToolChoiceAuto.Builder tcBuilder = ToolChoiceAuto.builder();
            if (parallelToolCalls != null) {
                tcBuilder.disableParallelToolUse(disableParallel);
            }
            builder.toolChoice(ofAuto(tcBuilder.build()));
        } else if (toolChoice instanceof ToolChoice.None) {
            // ToolChoiceNone 不支持 disable_parallel_tool_use。
            if (parallelToolCalls != null && disableParallel) {
                log.debug(
                        "disable_parallel_tool_use is not supported with ToolChoice.None,"
                                + " ignoring");
            }
            builder.toolChoice(ofNone(ToolChoiceNone.builder().build()));
        } else if (toolChoice instanceof ToolChoice.Required) {
            // Anthropic 没有直接的 "required" 选项，使用 "any" 强制
            // 调用工具。
            log.warn(
                    "Anthropic API doesn't support ToolChoice.Required directly, using 'any'"
                            + " instead");
            ToolChoiceAny.Builder tcBuilder = ToolChoiceAny.builder();
            if (parallelToolCalls != null) {
                tcBuilder.disableParallelToolUse(disableParallel);
            }
            builder.toolChoice(ofAny(tcBuilder.build()));
        } else if (toolChoice instanceof ToolChoice.Specific specific) {
            ToolChoiceTool.Builder tcBuilder = ToolChoiceTool.builder().name(specific.toolName());
            if (parallelToolCalls != null) {
                tcBuilder.disableParallelToolUse(disableParallel);
            }
            builder.toolChoice(ofTool(tcBuilder.build()));
        } else {
            log.warn("Unknown tool choice type: {}", toolChoice);
        }
    }

    /**
     * 向构建器应用生成选项。
     *
     * @param builder 消息创建参数构建器
     * @param options 生成选项
     * @param defaultOptions 默认生成选项
     */
    public static void applyOptions(
            MessageCreateParams.Builder builder,
            GenerateOptions options,
            GenerateOptions defaultOptions) {
        // 采样温度。
        Double temperature = getOption(options, defaultOptions, GenerateOptions::getTemperature);
        if (temperature != null) {
            builder.temperature(temperature);
        }

        // Top P 核采样。
        Double topP = getOption(options, defaultOptions, GenerateOptions::getTopP);
        if (topP != null) {
            builder.topP(topP);
        }

        // Top K 采样。
        Integer topK = getOption(options, defaultOptions, GenerateOptions::getTopK);
        if (topK != null) {
            builder.topK(topK.longValue());
        }

        // 最大 token 数。
        Integer maxTokens = getOption(options, defaultOptions, GenerateOptions::getMaxTokens);
        if (maxTokens != null) {
            builder.maxTokens(maxTokens);
        }

        // 应用附加参数：先合并 defaultOptions，再由 options 覆盖。
        // 应用附加请求头。
        applyAdditionalHeaders(builder, defaultOptions);
        applyAdditionalHeaders(builder, options);

        // 应用附加请求体参数。
        applyAdditionalBodyParams(builder, defaultOptions);
        applyAdditionalBodyParams(builder, options);

        // 应用附加查询参数。
        applyAdditionalQueryParams(builder, defaultOptions);
        applyAdditionalQueryParams(builder, options);
    }

    private static void applyAdditionalHeaders(
            MessageCreateParams.Builder builder, GenerateOptions opts) {
        if (opts == null) return;
        Map<String, String> headers = opts.getAdditionalHeaders();
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.putAdditionalHeader(entry.getKey(), entry.getValue());
            }
            log.debug("Applied {} additional headers to Anthropic request", headers.size());
        }
    }

    private static void applyAdditionalBodyParams(
            MessageCreateParams.Builder builder, GenerateOptions opts) {
        if (opts == null) return;
        Map<String, Object> params = opts.getAdditionalBodyParams();
        if (params != null && !params.isEmpty()) {
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                builder.putAdditionalBodyProperty(entry.getKey(), JsonValue.from(entry.getValue()));
            }
            log.debug("Applied {} additional body params to Anthropic request", params.size());
        }
    }

    private static void applyAdditionalQueryParams(
            MessageCreateParams.Builder builder, GenerateOptions opts) {
        if (opts == null) return;
        Map<String, String> params = opts.getAdditionalQueryParams();
        if (params != null && !params.isEmpty()) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                builder.putAdditionalQueryParam(entry.getKey(), entry.getValue());
            }
            log.debug("Applied {} additional query params to Anthropic request", params.size());
        }
    }

    /** 获取选项值，具体配置优先于默认值。 */
    private static <T> T getOption(
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
}
