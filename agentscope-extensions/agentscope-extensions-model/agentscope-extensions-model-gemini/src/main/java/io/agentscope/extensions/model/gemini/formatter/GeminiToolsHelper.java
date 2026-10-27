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

import com.google.genai.types.FunctionCallingConfig;
import com.google.genai.types.FunctionCallingConfigMode;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Tool;
import com.google.genai.types.ToolConfig;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.gemini.tool.GeminiServerTool;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 转换并组合 Gemini API 的工具配置。
 *
 * <p><b>职责：</b>
 * <ul>
 *   <li>将 AgentScope {@link ToolSchema} 定义转换为包含函数声明与 JSON Schema
 *       参数的 Gemini {@link Tool}</li>
 *   <li>向 {@link GenerateContentConfig} 追加提供商专属的
 *       {@link GeminiServerTool} 条目，同时保留现有函数工具</li>
 *   <li>将 AgentScope {@link ToolChoice} 转换为 Gemini {@link ToolConfig}
 *       函数调用策略</li>
 * </ul>
 *
 * <p>函数工具与 Gemini 服务端工具在 {@code GenerateContentConfig.tools}
 * 中保持独立条目；工具选择策略仅作用于函数调用。
 *
 * <p><b>工具选择映射：</b>
 * <ul>
 *   <li>Auto：mode=AUTO（模型决定）</li>
 *   <li>None：mode=NONE（禁用工具调用）</li>
 *   <li>Required：mode=ANY（强制从全部提供工具中选择调用）</li>
 *   <li>Specific：mode=ANY + allowedFunctionNames（强制调用指定工具）</li>
 * </ul>
 */
public class GeminiToolsHelper {

    private static final Logger log = LoggerFactory.getLogger(GeminiToolsHelper.class);

    /**
     * 创建新的 GeminiToolsHelper。
     */
    public GeminiToolsHelper() {}

    /**
     * 向内容配置追加 Gemini 服务端工具，不修改现有工具列表。
     *
     * @param config 现有内容配置
     * @param serverTools 待追加的 Gemini 服务端工具（可以为 null 或空）
     * @return 同时包含现有工具与服务端工具的配置；无服务端工具时返回 {@code config}
     */
    public static GenerateContentConfig mergeServerTools(
            GenerateContentConfig config, List<GeminiServerTool> serverTools) {
        if (serverTools == null || serverTools.isEmpty()) {
            return config;
        }
        GenerateContentConfig.Builder updateConfigBuilder = config.toBuilder();
        ToolConfig.Builder toolConfigBuilder =
                config.toolConfig().map(ToolConfig::toBuilder).orElseGet(ToolConfig::builder);

        List<Tool> mergedTools = new ArrayList<>(config.tools().orElseGet(List::of));
        serverTools.stream().map(GeminiServerTool::toTool).forEach(mergedTools::add);
        updateConfigBuilder
                .tools(mergedTools)
                // 必须将 `include_server_side_tool_invocations` 标志设为 `true`，才能启用
                // 工具上下文循环。
                .toolConfig(toolConfigBuilder.includeServerSideToolInvocations(true).build());
        return updateConfigBuilder.build();
    }

    /**
     * 将 AgentScope ToolSchema 列表转换为 Gemini Tool 对象。
     *
     * @param tools 工具 schema 列表（可以为 null 或空）
     * @return 包含函数声明的 Gemini Tool 对象；无工具时返回 null
     */
    public Tool convertToGeminiTool(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }

        List<FunctionDeclaration> functionDeclarations = new ArrayList<>();

        for (ToolSchema toolSchema : tools) {
            FunctionDeclaration.Builder builder = FunctionDeclaration.builder();

            if (toolSchema.getName() != null) {
                builder.name(toolSchema.getName());
            }

            if (toolSchema.getDescription() != null) {
                builder.description(toolSchema.getDescription());
            }

            if (toolSchema.getParameters() != null && !toolSchema.getParameters().isEmpty()) {
                builder.parametersJsonSchema(toolSchema.getParameters());
            }

            functionDeclarations.add(builder.build());
            log.debug("Converted tool schema: {}", toolSchema.getName());
        }

        return Tool.builder().functionDeclarations(functionDeclarations).build();
    }

    /**
     * 根据 AgentScope ToolChoice 创建 Gemini ToolConfig。
     *
     * <p>工具选择映射：
     * <ul>
     *   <li>null 或 Auto：mode=AUTO（模型决定）</li>
     *   <li>None：mode=NONE（禁用工具调用）</li>
     *   <li>Required：mode=ANY（强制从全部提供工具中选择调用）</li>
     *   <li>Specific：mode=ANY + allowedFunctionNames（强制调用指定工具）</li>
     * </ul>
     *
     * @param toolChoice 工具选择配置（null 表示 auto）
     * @return Gemini ToolConfig 对象；auto 时返回 null（默认行为）
     */
    public ToolConfig convertToolChoice(ToolChoice toolChoice) {
        if (toolChoice == null || toolChoice instanceof ToolChoice.Auto) {
            // Auto 是默认行为，无需显式配置
            log.debug("ToolChoice.Auto: using default AUTO mode");
            return null;
        }

        FunctionCallingConfig.Builder configBuilder = FunctionCallingConfig.builder();

        if (toolChoice instanceof ToolChoice.None) {
            // NONE：禁用工具调用
            configBuilder.mode(FunctionCallingConfigMode.Known.NONE);
            log.debug("ToolChoice.None: set mode to NONE");

        } else if (toolChoice instanceof ToolChoice.Required) {
            // ANY：强制从全部提供工具中选择调用
            configBuilder.mode(FunctionCallingConfigMode.Known.ANY);
            log.debug("ToolChoice.Required: set mode to ANY");

        } else if (toolChoice instanceof ToolChoice.Specific specific) {
            // ANY 加 allowedFunctionNames：强制调用指定工具
            configBuilder.mode(FunctionCallingConfigMode.Known.ANY);
            configBuilder.allowedFunctionNames(List.of(specific.toolName()));
            log.debug("ToolChoice.Specific: set mode to ANY with tool '{}'", specific.toolName());

        } else {
            log.warn(
                    "Unknown ToolChoice type: {}, using AUTO mode",
                    toolChoice.getClass().getSimpleName());
            return null;
        }

        FunctionCallingConfig functionCallingConfig = configBuilder.build();
        return ToolConfig.builder().functionCallingConfig(functionCallingConfig).build();
    }
}
