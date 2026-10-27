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

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ServerToolUseBlock;
import com.anthropic.models.messages.ServerToolUseBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolValidator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在提供商 SDK 与 AgentScope 内容块之间转换 Anthropic 服务端工具块。
 *
 * <p>原始提供商结果块保留在元数据中，后续轮次可以直接回传给 Anthropic，
 * 无需从通用 AgentScope 模型中重建提供商专用字段。
 *
 * <p>【学习提示】服务端工具已在提供商基础设施中执行，不能因其被表示为工具内容块，
 * 就在客户端工具执行器里再执行一次。重放元数据用于协议连续性，而不是新的调用授权。
 */
final class AnthropicServerToolHelper {

    static final String RESULT_METADATA = "anthropicServerToolResult";

    private static final Logger log = LoggerFactory.getLogger(AnthropicServerToolHelper.class);

    private static final AnthropicServerToolHelper INSTANCE = new AnthropicServerToolHelper();

    private AnthropicServerToolHelper() {}

    static AnthropicServerToolHelper instance() {
        return INSTANCE;
    }

    /**
     * 将带服务端标记的 AgentScope 工具调用转换回 Anthropic 请求块。
     *
     * @param toolUse AgentScope 服务端工具调用
     * @return Anthropic server_tool_use 请求块
     */
    ServerToolUseBlockParam encodeUse(ToolUseBlock toolUse) {
        ServerToolUseBlockParam.Input.Builder inputBuilder =
                ServerToolUseBlockParam.Input.builder();
        if (toolUse.getInput() != null) {
            toolUse.getInput()
                    .forEach(
                            (key, value) ->
                                    inputBuilder.putAdditionalProperty(key, JsonValue.from(value)));
        }
        return ServerToolUseBlockParam.builder()
                .id(toolUse.getId())
                .name(JsonValue.from(toolUse.getName()))
                .input(inputBuilder.build())
                .build();
    }

    /**
     * 恢复保存在 AgentScope 元数据中的原始 Anthropic 结果块。
     *
     * @param toolResult AgentScope 服务端工具结果
     * @return 原始 Anthropic 结果块，无法恢复时返回 null
     */
    ContentBlockParam encodeResult(ToolResultBlock toolResult) {
        Object raw = toolResult.getMetadata().get(RESULT_METADATA);
        if (raw instanceof String json && !json.isBlank()) {
            try {
                return ObjectMappers.jsonMapper().readValue(json, ContentBlockParam.class);
            } catch (Exception e) {
                log.warn(
                        "Failed to restore server tool result {}: {}",
                        toolResult.getId(),
                        e.getMessage());
            }
        } else {
            log.warn(
                    "Server tool result {} has no raw block JSON in metadata; skipping echo",
                    toolResult.getId());
        }
        return null;
    }

    /**
     * 将 Anthropic server_tool_use 响应块转换为 AgentScope 内容块。
     *
     * @param block Anthropic 服务端工具调用响应块
     * @return 带服务端标记的 AgentScope 工具调用
     */
    ToolUseBlock decodeUse(ServerToolUseBlock block) {
        String name = block.name().asString();
        if (!ToolValidator.requireNonBlank("Anthropic", name, block.id())) {
            return null;
        }
        Map<String, Object> input = AnthropicResponseParser.parseJsonInput(block._input(), name);
        return ToolUseBlock.builder()
                .id(block.id())
                .name(name)
                .input(input)
                .content(block._input() != null ? block._input().toString() : "")
                .metadata(Map.of(ToolUseBlock.METADATA_SERVER_TOOL, true))
                .state(ToolCallState.FINISHED)
                .build();
    }

    /**
     * 规范化非流式 Anthropic 服务端工具结果块。
     *
     * @param block Anthropic 响应内容块
     * @return 服务端工具结果参数；其他内容块类型返回空值
     */
    Optional<ContentBlockParam> toResultParam(com.anthropic.models.messages.ContentBlock block) {
        return block.webSearchToolResult()
                .map(r -> ContentBlockParam.ofWebSearchToolResult(r.toParam()))
                .or(
                        () ->
                                block.webFetchToolResult()
                                        .map(
                                                r ->
                                                        ContentBlockParam.ofWebFetchToolResult(
                                                                r.toParam())))
                .or(
                        () ->
                                block.codeExecutionToolResult()
                                        .map(
                                                r ->
                                                        ContentBlockParam.ofCodeExecutionToolResult(
                                                                r.toParam())))
                .or(
                        () ->
                                block.bashCodeExecutionToolResult()
                                        .map(
                                                r ->
                                                        ContentBlockParam
                                                                .ofBashCodeExecutionToolResult(
                                                                        r.toParam())))
                .or(
                        () ->
                                block.textEditorCodeExecutionToolResult()
                                        .map(
                                                r ->
                                                        ContentBlockParam
                                                                .ofTextEditorCodeExecutionToolResult(
                                                                        r.toParam())))
                .or(
                        () ->
                                block.toolSearchToolResult()
                                        .map(
                                                r ->
                                                        ContentBlockParam.ofToolSearchToolResult(
                                                                r.toParam())));
    }

    /**
     * 规范化流式 content_block_start 服务端工具结果块。
     *
     * @param block Anthropic 流起始内容块
     * @return 服务端工具结果参数；其他内容块类型返回空值
     */
    Optional<ContentBlockParam> toResultParam(
            com.anthropic.models.messages.RawContentBlockStartEvent.ContentBlock block) {
        return block.webSearchToolResult()
                .map(r -> ContentBlockParam.ofWebSearchToolResult(r.toParam()))
                .or(
                        () ->
                                block.webFetchToolResult()
                                        .map(
                                                r ->
                                                        ContentBlockParam.ofWebFetchToolResult(
                                                                r.toParam())))
                .or(
                        () ->
                                block.codeExecutionToolResult()
                                        .map(
                                                r ->
                                                        ContentBlockParam.ofCodeExecutionToolResult(
                                                                r.toParam())))
                .or(
                        () ->
                                block.bashCodeExecutionToolResult()
                                        .map(
                                                r ->
                                                        ContentBlockParam
                                                                .ofBashCodeExecutionToolResult(
                                                                        r.toParam())))
                .or(
                        () ->
                                block.textEditorCodeExecutionToolResult()
                                        .map(
                                                r ->
                                                        ContentBlockParam
                                                                .ofTextEditorCodeExecutionToolResult(
                                                                        r.toParam())))
                .or(
                        () ->
                                block.toolSearchToolResult()
                                        .map(
                                                r ->
                                                        ContentBlockParam.ofToolSearchToolResult(
                                                                r.toParam())));
    }

    /**
     * 将 Anthropic 服务端工具结果响应块转换为 AgentScope 内容块。
     *
     * @param param Anthropic 服务端工具结果响应块
     * @return 带服务端标记的 AgentScope 工具结果
     */
    ToolResultBlock decodeResult(ContentBlockParam param) {
        String toolName = serverToolResultName(param);
        String toolUseId = serverToolResultId(param);
        List<ContentBlock> output = new ArrayList<>();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ToolResultBlock.METADATA_SERVER_TOOL, true);
        ToolResultState state = ToolResultState.SUCCESS;

        try {
            String json = ObjectMappers.jsonMapper().writeValueAsString(param);
            metadata.put(RESULT_METADATA, json);

            JsonNode content = ObjectMappers.jsonMapper().readTree(json).path("content");
            if (content.isObject() && content.hasNonNull("error_code")) {
                state = ToolResultState.ERROR;
                output.add(
                        TextBlock.builder()
                                .text(
                                        "[ERROR] "
                                                + toolName
                                                + " failed: "
                                                + content.get("error_code").asText())
                                .build());
            } else if (param.isWebSearchToolResult() && content.isArray()) {
                for (JsonNode result : content) {
                    output.add(
                            TextBlock.builder()
                                    .text(
                                            result.path("title").asText()
                                                    + " ("
                                                    + result.path("url").asText()
                                                    + ")")
                                    .build());
                }
            } else {
                output.add(TextBlock.builder().text(content.toString()).build());
            }
        } catch (Exception e) {
            log.warn(
                    "Failed to capture server tool result {} ({}): {}",
                    toolUseId,
                    toolName,
                    e.getMessage());
            state = ToolResultState.ERROR;
            output.add(
                    TextBlock.builder()
                            .text("[ERROR] failed to capture " + toolName + " result")
                            .build());
        }

        return ToolResultBlock.builder()
                .id(toolUseId)
                .name(toolName)
                .output(output)
                .metadata(metadata)
                .state(state)
                .build();
    }

    private static String serverToolResultName(ContentBlockParam param) {
        if (param.isWebSearchToolResult()) {
            return "web_search";
        }
        if (param.isWebFetchToolResult()) {
            return "web_fetch";
        }
        if (param.isCodeExecutionToolResult()) {
            return "code_execution";
        }
        if (param.isBashCodeExecutionToolResult()) {
            return "bash_code_execution";
        }
        if (param.isTextEditorCodeExecutionToolResult()) {
            return "text_editor_code_execution";
        }
        if (param.isToolSearchToolResult()) {
            return "tool_search";
        }
        throw new IllegalArgumentException("ContentBlockParam is not a server tool result");
    }

    private static String serverToolResultId(ContentBlockParam param) {
        if (param.isWebSearchToolResult()) {
            return param.asWebSearchToolResult().toolUseId();
        }
        if (param.isWebFetchToolResult()) {
            return param.asWebFetchToolResult().toolUseId();
        }
        if (param.isCodeExecutionToolResult()) {
            return param.asCodeExecutionToolResult().toolUseId();
        }
        if (param.isBashCodeExecutionToolResult()) {
            return param.asBashCodeExecutionToolResult().toolUseId();
        }
        if (param.isTextEditorCodeExecutionToolResult()) {
            return param.asTextEditorCodeExecutionToolResult().toolUseId();
        }
        if (param.isToolSearchToolResult()) {
            return param.asToolSearchToolResult().toolUseId();
        }
        throw new IllegalArgumentException("ContentBlockParam is not a server tool result");
    }
}
