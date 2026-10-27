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

import com.google.genai.JsonSerializable;
import com.google.genai.types.Candidate;
import com.google.genai.types.CodeExecutionResult;
import com.google.genai.types.Content;
import com.google.genai.types.ExecutableCode;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Outcome;
import com.google.genai.types.Part;
import com.google.genai.types.ToolCall;
import com.google.genai.types.ToolResponse;
import com.google.genai.types.ToolType;
import io.agentscope.core.formatter.FormatterException;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.tool.ToolValidator;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 Gemini API 响应解析为 AgentScope ChatResponse。
 *
 * <p>此解析器将 Gemini 的 GenerateContentResponse 转换为 AgentScope ChatResponse，
 * 包括：
 * <ul>
 *   <li>文本 Part 转为文本块</li>
 *   <li>带 thought=true 标志的 Part 转为思考块</li>
 *   <li>function_call Part 转为工具使用块（本地函数调用）</li>
 *   <li>tool_call/tool_response Part 转为工具使用块与工具结果块（服务端内置工具）</li>
 *   <li>包含 token 数量的用量元数据</li>
 * </ul>
 *
 * <p><b>重要：</b>Gemini API 通过 Part 对象上的 "thought" 标志标识思考内容。
 */
public class GeminiResponseParser {

    private static final Logger log = LoggerFactory.getLogger(GeminiResponseParser.class);

    /**
     * 在最终消息上携带 Gemini grounding 元数据的键。
     */
    public static final String METADATA_GROUNDING = "gemini.groundingMetadata";

    /**
     * 在最终消息上携带 Gemini URL 上下文元数据的键。
     */
    public static final String METADATA_URL_CONTEXT = "gemini.urlContextMetadata";

    /**
     * 将工具块标记为 Gemini 代码执行的元数据键。
     */
    static final String METADATA_CODE_EXECUTION = "gemini.codeExecution";

    /**
     * 携带 Gemini 代码执行结果状态的元数据键。
     */
    static final String METADATA_CODE_EXECUTION_OUTCOME = "gemini.codeExecution.outcome";

    /**
     * 保留原始 Gemini 服务端工具响应的元数据键。
     */
    public static final String METADATA_SERVER_TOOL_RESPONSE = "gemini.serverToolResponse";

    /**
     * 保留原始 Gemini 代码执行结果的元数据键。
     */
    public static final String METADATA_CODE_EXECUTION_RESULT = "gemini.codeExecutionResult";

    /**
     * 创建新的 GeminiResponseParser。
     */
    public GeminiResponseParser() {}

    /**
     * 将 Gemini GenerateContentResponse 解析为 AgentScope ChatResponse。
     *
     * @param response Gemini 生成响应
     * @param startTime 用于计算耗时的请求开始时间
     * @return AgentScope ChatResponse
     */
    public ChatResponse parseResponse(GenerateContentResponse response, Instant startTime) {
        try {
            List<ContentBlock> blocks = new ArrayList<>();
            String finishReason = null;
            Map<String, Object> responseMetadata = new LinkedHashMap<>();

            // 解析首个候选的内容
            if (response.candidates().isPresent() && !response.candidates().get().isEmpty()) {
                Candidate candidate = response.candidates().get().get(0);

                if (candidate.content().isPresent()) {
                    Content content = candidate.content().get();

                    if (content.parts().isPresent()) {
                        List<Part> parts = content.parts().get();
                        parsePartsToBlocks(parts, blocks);
                    }
                }
                finishReason = candidate.finishReason().map(Object::toString).orElse(null);

                candidate
                        .groundingMetadata()
                        .ifPresent(
                                metadata ->
                                        responseMetadata.put(METADATA_GROUNDING, toMap(metadata)));
                candidate
                        .urlContextMetadata()
                        .ifPresent(
                                metadata ->
                                        responseMetadata.put(
                                                METADATA_URL_CONTEXT, toMap(metadata)));
            }

            // 解析用量元数据
            ChatUsage usage = null;
            if (response.usageMetadata().isPresent()) {
                GenerateContentResponseUsageMetadata metadata = response.usageMetadata().get();

                int inputTokens =
                        metadata.promptTokenCount().orElse(0)
                                + metadata.toolUsePromptTokenCount().orElse(0);
                int cachedTokens = metadata.cachedContentTokenCount().orElse(0);
                int thinkingTokens = metadata.thoughtsTokenCount().orElse(0);
                int toolUsePromptTokens = metadata.toolUsePromptTokenCount().orElse(0);
                int outputTokens = metadata.candidatesTokenCount().orElse(0) + thinkingTokens;

                usage =
                        ChatUsage.builder()
                                .inputTokens(inputTokens)
                                .outputTokens(outputTokens)
                                .cachedTokens(cachedTokens)
                                .toolUsePromptTokens(toolUsePromptTokens)
                                .reasoningTokens(thinkingTokens)
                                .time(
                                        Duration.between(startTime, Instant.now()).toMillis()
                                                / 1000.0)
                                .build();
            }

            return ChatResponse.builder()
                    .id(response.responseId().orElse(null))
                    .content(blocks)
                    .usage(usage)
                    .metadata(responseMetadata.isEmpty() ? null : responseMetadata)
                    .finishReason(finishReason)
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse Gemini response: {}", e.getMessage(), e);
            throw new FormatterException("Failed to parse Gemini response: " + e.getMessage(), e);
        }
    }

    /**
     * 将 Gemini Part 对象解析为 AgentScope ContentBlock。
     * 块类型顺序：ThinkingBlock、TextBlock、ToolUseBlock、ToolResultBlock。
     *
     * @param parts Gemini Part 对象列表
     * @param blocks 接收已解析 ContentBlock 的列表
     */
    protected void parsePartsToBlocks(List<Part> parts, List<ContentBlock> blocks) {
        String pendingCodeExecutionId = null;

        for (Part part : parts) {
            // 先检查思考内容（带 thought=true 标志的 Part）
            if (part.thought().isPresent() && part.thought().get() && part.text().isPresent()) {
                String thinkingText = part.text().get();
                if (thinkingText != null && !thinkingText.isEmpty()) {
                    blocks.add(ThinkingBlock.builder().thinking(thinkingText).build());
                }
                continue;
            }

            // 检查文本内容
            if (part.text().isPresent()) {
                String text = part.text().get();
                if (text != null && !text.isEmpty()) {
                    blocks.add(TextBlock.builder().text(text).build());
                }
            }

            // Gemini 代码执行使用专用 Part，而非 ToolCall/ToolResponse。
            if (part.executableCode().isPresent()) {
                ExecutableCode executableCode = part.executableCode().get();
                byte[] thoughtSignature = part.thoughtSignature().orElse(null);
                ToolUseBlock codeBlock =
                        parseExecutableCode(
                                executableCode, thoughtSignature, pendingCodeExecutionId);
                blocks.add(codeBlock);
                pendingCodeExecutionId = codeBlock.getId();
            }

            if (part.codeExecutionResult().isPresent()) {
                CodeExecutionResult codeExecutionResult = part.codeExecutionResult().get();
                byte[] thoughtSignature = part.thoughtSignature().orElse(null);
                blocks.add(
                        parseCodeExecutionResult(
                                codeExecutionResult, thoughtSignature, pendingCodeExecutionId));
                pendingCodeExecutionId = null;
            }

            // 检查函数调用（工具使用）
            if (part.functionCall().isPresent()) {
                FunctionCall functionCall = part.functionCall().get();
                byte[] thoughtSignature = part.thoughtSignature().orElse(null);
                parseToolCall(functionCall, thoughtSignature, blocks);
            }

            // 检查工具调用（服务端工具使用）
            if (part.toolCall().isPresent()) {
                ToolCall toolCall = part.toolCall().get();
                byte[] thoughtSignature = part.thoughtSignature().orElse(null);
                parseServerToolCall(toolCall, thoughtSignature, blocks);
            }

            // 检查工具响应（服务端工具结果）
            if (part.toolResponse().isPresent()) {
                ToolResponse toolResponse = part.toolResponse().get();
                byte[] thoughtSignature = part.thoughtSignature().orElse(null);
                parseServerToolResponse(toolResponse, thoughtSignature, blocks);
            }
        }
    }

    /**
     * 将 Gemini 可执行代码 Part 解析为服务端工具使用块。
     *
     * @param executableCode Gemini 可执行代码 Part
     * @param thoughtSignature Part 的思考签名，或 null
     * @param fallbackId Part 没有 ID 时，匹配的可执行代码调用 ID
     * @return 表示生成代码的工具使用块
     */
    private ToolUseBlock parseExecutableCode(
            ExecutableCode executableCode, byte[] thoughtSignature, String fallbackId) {
        String id =
                executableCode
                        .id()
                        .orElseGet(
                                () ->
                                        fallbackId != null
                                                ? fallbackId
                                                : "code_execution_" + System.currentTimeMillis());

        Map<String, Object> input = new HashMap<>();
        executableCode
                .language()
                .map(language -> language.toString())
                .ifPresent(language -> input.put("language", language));
        executableCode.code().ifPresent(code -> input.put("code", code));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ToolUseBlock.METADATA_SERVER_TOOL, true);
        metadata.put(METADATA_CODE_EXECUTION, true);
        if (thoughtSignature != null) {
            metadata.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, thoughtSignature);
        }

        return ToolUseBlock.builder()
                .id(id)
                .name("CODE_EXECUTION")
                .input(input)
                .content(input.isEmpty() ? null : JsonUtils.getJsonCodec().toJson(input))
                .metadata(metadata)
                .state(ToolCallState.FINISHED)
                .build();
    }

    /**
     * 将 Gemini 代码执行结果解析为服务端工具结果块。
     *
     * @param codeExecutionResult Gemini 代码执行结果 Part
     * @param thoughtSignature Part 的思考签名，或 null
     * @param fallbackId 结果没有 ID 时，匹配的可执行代码调用 ID
     * @return 表示执行结果的工具结果块
     */
    private ToolResultBlock parseCodeExecutionResult(
            CodeExecutionResult codeExecutionResult, byte[] thoughtSignature, String fallbackId) {
        String id =
                codeExecutionResult
                        .id()
                        .orElseGet(
                                () ->
                                        fallbackId != null
                                                ? fallbackId
                                                : "code_execution_" + System.currentTimeMillis());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ToolResultBlock.METADATA_SERVER_TOOL, true);
        metadata.put(METADATA_CODE_EXECUTION, true);
        metadata.put(METADATA_CODE_EXECUTION_RESULT, codeExecutionResult.toJson());
        codeExecutionResult
                .outcome()
                .map(outcome -> outcome.toString())
                .ifPresent(outcome -> metadata.put(METADATA_CODE_EXECUTION_OUTCOME, outcome));
        if (thoughtSignature != null) {
            metadata.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, thoughtSignature);
        }

        ToolResultState resultState =
                codeExecutionResult
                        .outcome()
                        .map(GeminiResponseParser::codeExecutionResultState)
                        .orElse(ToolResultState.SUCCESS);

        ToolResultBlock.Builder builder =
                ToolResultBlock.builder()
                        .id(id)
                        .name("CODE_EXECUTION")
                        .state(resultState)
                        .metadata(metadata);
        codeExecutionResult
                .output()
                .ifPresent(output -> builder.output(TextBlock.builder().text(output).build()));

        return builder.build();
    }

    private static ToolResultState codeExecutionResultState(Outcome outcome) {
        return switch (outcome.knownEnum()) {
            case OUTCOME_FAILED, OUTCOME_DEADLINE_EXCEEDED -> ToolResultState.ERROR;
            default -> ToolResultState.SUCCESS;
        };
    }

    /**
     * 将 Gemini FunctionCall 解析为 ToolUseBlock。
     *
     * @param functionCall Gemini FunctionCall 对象
     * @param thoughtSignature Part 的思考签名（可以为 null）
     * @param blocks 接收已解析 ToolUseBlock 的列表
     */
    protected void parseToolCall(
            FunctionCall functionCall, byte[] thoughtSignature, List<ContentBlock> blocks) {
        try {
            String id = functionCall.id().orElse("tool_call_" + System.currentTimeMillis());
            String name = functionCall.name().orElse("");

            if (!ToolValidator.requireNonBlank("Gemini", name, id)) {
                return;
            }

            blocks.add(
                    convertToolUseBlock(
                            id,
                            name,
                            functionCall.args().orElse(null),
                            thoughtSignature,
                            null,
                            false));
        } catch (Exception e) {
            log.warn("Failed to parse function call: {}", e.getMessage(), e);
        }
    }

    /**
     * 将 Gemini ToolCall 解析为服务端（内置）工具的 ToolUseBlock。
     *
     * @param toolCall Gemini ToolCall 对象
     * @param thoughtSignature Part 的思考签名（可以为 null）
     * @param blocks 接收已解析 ToolUseBlock 的列表
     */
    protected void parseServerToolCall(
            ToolCall toolCall, byte[] thoughtSignature, List<ContentBlock> blocks) {
        try {
            String id = toolCall.id().orElse("tool_call_" + System.currentTimeMillis());
            String name = toolCall.toolType().map(ToolType::toString).orElse("");

            if (!ToolValidator.requireNonBlank("Gemini", name, id)) {
                return;
            }
            blocks.add(
                    convertToolUseBlock(
                            id,
                            name,
                            toolCall.args().orElse(null),
                            thoughtSignature,
                            ToolCallState.FINISHED,
                            true));
        } catch (Exception e) {
            log.warn("Failed to parse tool call: {}", e.getMessage(), e);
        }
    }

    /**
     * 将 Gemini 工具调用转换为 ToolUseBlock。
     *
     * @param id 工具调用 ID
     * @param name 工具名称（函数名或工具类型）
     * @param args 工具参数映射（可以为 null）
     * @param thoughtSignature Part 的思考签名（可以为 null）
     * @param server 工具是否由模型提供商在服务端执行
     * @return 新的 ToolUseBlock
     */
    protected ToolUseBlock convertToolUseBlock(
            String id,
            String name,
            Map<String, Object> args,
            byte[] thoughtSignature,
            ToolCallState state,
            boolean server) {
        // 解析参数
        Map<String, Object> argsMap = new HashMap<>();
        String rawContent = null;

        if (args != null && !args.isEmpty()) {
            argsMap.putAll(args);
            // 转换为 JSON 字符串，作为原始内容
            try {
                rawContent = JsonUtils.getJsonCodec().toJson(args);
            } catch (Exception e) {
                log.warn("Failed to serialize function call arguments: {}", e.getMessage());
            }
        }

        // 构建包含提供商标志与可选思考签名的元数据
        Map<String, Object> metadata = new HashMap<>();
        if (server) {
            metadata.put(ToolUseBlock.METADATA_SERVER_TOOL, true);
        }
        if (thoughtSignature != null) {
            metadata.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, thoughtSignature);
        }

        return ToolUseBlock.builder()
                .id(id)
                .name(name)
                .input(argsMap)
                .content(rawContent)
                .metadata(metadata.isEmpty() ? null : metadata)
                .state(state)
                .build();
    }

    /**
     * 将 Gemini ToolResponse 解析为服务端（内置）工具的 ToolResultBlock。
     *
     * @param toolResponse Gemini ToolResponse 对象
     * @param thoughtSignature Part 的思考签名（可以为 null）
     * @param blocks 接收已解析 ToolResultBlock 的列表
     */
    protected void parseServerToolResponse(
            ToolResponse toolResponse, byte[] thoughtSignature, List<ContentBlock> blocks) {
        try {
            String id = toolResponse.id().orElse("tool_call_" + System.currentTimeMillis());
            String name = toolResponse.toolType().map(ToolType::toString).orElse("");

            if (!ToolValidator.requireNonBlank("Gemini", name, id)) {
                return;
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put(ToolResultBlock.METADATA_SERVER_TOOL, true);
            metadata.put(METADATA_SERVER_TOOL_RESPONSE, toolResponse.toJson());
            if (thoughtSignature != null) {
                metadata.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, thoughtSignature);
            }

            ToolResultBlock.Builder toolResultBuilder =
                    ToolResultBlock.builder()
                            .id(id)
                            .name(name)
                            .state(ToolResultState.SUCCESS)
                            .metadata(metadata);

            if (toolResponse.response().isPresent()) {
                toolResultBuilder.output(
                        TextBlock.builder()
                                .text(
                                        JsonUtils.getJsonCodec()
                                                .toJson(toolResponse.response().get()))
                                .build());
            }

            blocks.add(toolResultBuilder.build());
        } catch (Exception e) {
            log.warn("Failed to parse tool response: {}", e.getMessage(), e);
        }
    }

    private static Map<String, Object> toMap(JsonSerializable serializable) {
        try {
            Map<String, Object> result =
                    JsonUtils.getJsonCodec().fromJson(serializable.toJson(), Map.class);
            return result != null ? result : Map.of();
        } catch (Exception e) {
            log.warn("Failed to normalize Gemini metadata: {}", e.getMessage(), e);
            return Map.of();
        }
    }
}
