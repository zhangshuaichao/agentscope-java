/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.ollama.formatter;

import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.ollama.dto.OllamaFunction;
import io.agentscope.extensions.model.ollama.dto.OllamaRequest;
import io.agentscope.extensions.model.ollama.dto.OllamaTool;
import io.agentscope.extensions.model.ollama.dto.OllamaToolCall;
import io.agentscope.extensions.model.ollama.dto.OllamaToolFunction;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 管理 Ollama 工具转换与选项应用的辅助类。
 */
public class OllamaToolsHelper {

    private static final Logger log = LoggerFactory.getLogger(OllamaToolsHelper.class);

    public OllamaToolsHelper() {
        // 使用 JsonUtils 进行 JSON 操作
    }

    /**
     * 将 GenerateOptions 应用到 OllamaRequest。
     *
     * @param request 待修改的 Ollama 请求
     * @param options 待应用的生成选项
     * @param defaultOptions options 参数为 null 时使用的默认选项
     */
    public void applyOptions(
            OllamaRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        OllamaOptions runtimeOpts = OllamaOptions.fromGenerateOptions(options);
        OllamaOptions defaultOpts = OllamaOptions.fromGenerateOptions(defaultOptions);
        applyOptions(request, runtimeOpts, defaultOpts);
    }

    /**
     * 将 OllamaOptions 应用到 OllamaRequest。
     *
     * @param request 待修改的 Ollama 请求
     * @param options 待应用的 Ollama 选项
     * @param defaultOptions options 参数为 null 时使用的默认 Ollama 选项
     */
    public void applyOptions(
            OllamaRequest request, OllamaOptions options, OllamaOptions defaultOptions) {
        OllamaOptions merged;
        if (defaultOptions != null) {
            merged = defaultOptions.merge(options);
        } else if (options != null) {
            merged = options;
        } else {
            merged = OllamaOptions.builder().build();
        }

        // 转换为映射
        @SuppressWarnings("unchecked")
        Map<String, Object> optionsMap = JsonUtils.getJsonCodec().convertValue(merged, Map.class);

        // 【学习提示】框架选项对象与服务端 JSON 结构不是一一平铺的关系：
        // format、keep_alive、think 属于请求顶层，采样等参数才留在 options 中。
        // 将顶层选项从映射移到请求对象，并从 options 映射移除
        if (merged.getFormat() != null) {
            request.setFormat(merged.getFormat());
        }
        optionsMap.remove("format");

        if (merged.getKeepAlive() != null) {
            request.setKeepAlive(merged.getKeepAlive());
        }
        optionsMap.remove("keep_alive");

        if (merged.getThinkOption() != null) {
            request.setThink(merged.getThinkOption().toJsonValue());
        }
        optionsMap.remove("think");

        // 移除不应出现在 options 块中的其他非选项字段
        optionsMap.remove("model");
        optionsMap.remove("executionConfig");

        request.setOptions(optionsMap);
    }

    /**
     * 将 AgentScope ToolSchema 对象列表转换为 OllamaTool 对象列表。
     *
     * @param tools 待转换的 ToolSchema 对象列表
     * @return OllamaTool 对象列表；输入列表为 null 或空时返回 null
     */
    public List<OllamaTool> convertTools(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        return tools.stream().map(this::convertTool).collect(Collectors.toList());
    }

    private OllamaTool convertTool(ToolSchema tool) {
        OllamaToolFunction function = new OllamaToolFunction();
        function.setName(tool.getName());
        function.setDescription(tool.getDescription());

        // 直接映射 ToolSchema 的参数（它已经是 Map／JSON Schema）
        if (tool.getParameters() != null) {
            function.setParameters(tool.getParameters());
        } else {
            function.setParameters(Collections.emptyMap());
        }

        return new OllamaTool(function);
    }

    /**
     * 将工具应用到 OllamaRequest。
     *
     * @param request 待修改的 Ollama 请求
     * @param tools 待应用的工具 schema 列表（可以为 null 或空）
     */
    public void applyTools(OllamaRequest request, List<ToolSchema> tools) {
        List<OllamaTool> ollamaTools = convertTools(tools);
        if (ollamaTools != null && !ollamaTools.isEmpty()) {
            request.setTools(ollamaTools);
        }
    }

    /**
     * 将工具选择配置应用到 OllamaRequest。
     *
     * @param request 待修改的 Ollama 请求
     * @param toolChoice 工具选择配置（null 表示 auto／默认）
     */
    public void applyToolChoice(OllamaRequest request, ToolChoice toolChoice) {
        if (toolChoice == null) {
            return;
        }

        if (toolChoice instanceof ToolChoice.Auto) {
            request.setToolChoice("auto");
        } else if (toolChoice instanceof ToolChoice.None) {
            // "none" 阻止模型调用工具
            request.setToolChoice("none");
        } else if (toolChoice instanceof ToolChoice.Required) {
            // Ollama 原生不支持通用的 "required"（任意工具）。
            // 回退到允许使用工具的 "auto"，并记录调试消息。
            log.debug("ToolChoice.Required is not strictly supported by Ollama. Using 'auto'.");
            request.setToolChoice("auto");
        } else if (toolChoice instanceof ToolChoice.Specific) {
            ToolChoice.Specific specific = (ToolChoice.Specific) toolChoice;
            // 格式：{"type": "function", "function": {"name": "my_tool"}}
            Map<String, Object> functionObj = Map.of("name", specific.toolName());
            Map<String, Object> typeObj = Map.of("type", "function", "function", functionObj);
            request.setToolChoice(typeObj);
        }
    }

    /**
     * 将 ToolUseBlock 列表转换为 OllamaToolCall 列表。
     *
     * @param toolBlocks 待转换的工具使用块
     * @return OllamaToolCall 对象列表（输入为 null／空时返回空列表）
     */
    public List<OllamaToolCall> convertToolCalls(List<ToolUseBlock> toolBlocks) {
        if (toolBlocks == null || toolBlocks.isEmpty()) {
            return Collections.emptyList();
        }

        List<OllamaToolCall> result = new ArrayList<>();
        for (ToolUseBlock block : toolBlocks) {
            if (block == null) {
                continue;
            }
            OllamaFunction function = new OllamaFunction(block.getName(), block.getInput());
            result.add(new OllamaToolCall(function));
        }
        return result;
    }
}
