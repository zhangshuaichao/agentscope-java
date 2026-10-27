/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.openaiofficial;

import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseReasoningItem;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.tool.ToolValidator;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openaiofficial.tool.ResponsesServerToolHelper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将非流式 Responses API {@link Response} 解析为单个 {@link ChatResponse}。
 *
 * <p>仅遍历一次 {@code response.output()}，按类型
 * （reasoning、message、function_call、未知）分桶。按固定顺序组装内容块：
 * ThinkingBlock → TextBlock → ToolUseBlock。优先检查拒绝内容，
 * 遇到拒绝时立即抛出不可重试异常。
 */
final class ResponsesResponseParser {

    private ResponsesResponseParser() {}

    private static final Logger log = LoggerFactory.getLogger(ResponsesResponseParser.class);

    static ChatResponse parse(Response response, String modelName, Instant startTime) {
        // ── 提取：遍历 output 一次，按类型分桶 ──
        StringBuilder summaryBuilder = new StringBuilder();
        String encryptedContent = null;
        StringBuilder reasoningTextBuilder = new StringBuilder();
        StringBuilder textBuilder = new StringBuilder();
        List<ContentBlock> toolBlocks = new ArrayList<>();
        ResponsesServerToolHelper.HostedToolSearchPairing toolSearchPairing =
                new ResponsesServerToolHelper.HostedToolSearchPairing();

        List<ResponseOutputItem> output = response.output();
        for (ResponseOutputItem item : output) {
            if (item.isReasoning()) {
                ResponseReasoningItem reasoning = item.asReasoning();
                extractReasoning(reasoning, summaryBuilder, reasoningTextBuilder);
                if (encryptedContent == null) {
                    Optional<String> ec = reasoning.encryptedContent();
                    if (ec.isPresent() && !ec.get().isEmpty()) {
                        encryptedContent = ec.get();
                    }
                }
            } else if (item.isMessage()) {
                extractMessage(item.asMessage(), textBuilder, modelName);
            } else if (item.isFunctionCall()) {
                ToolUseBlock toolUse = extractFunctionCall(item.asFunctionCall());
                if (toolUse != null) {
                    toolBlocks.add(toolUse);
                }
            } else if (ResponsesServerToolHelper.isSupportedServerToolItem(item)) {
                toolBlocks.addAll(
                        ResponsesServerToolHelper.decodeBlocks(
                                item, toolSearchPairing.companionCallId(item)));
            }
            // 静默忽略未知输出项类型（向前兼容）
        }

        // ── 组装：ThinkingBlock → TextBlock → ToolUseBlock ──
        List<ContentBlock> contentBlocks = new ArrayList<>();

        // 存在任意思考数据时构建 ThinkingBlock。
        // 所有推理级元数据（encrypted_content、summary、text）
        // 存储在 ThinkingBlock 上，用于推理重放。
        String summaryText = summaryBuilder.toString();
        String reasoningText = reasoningTextBuilder.toString();
        if (!summaryText.isEmpty() || encryptedContent != null || !reasoningText.isEmpty()) {
            ThinkingBlock.Builder thinkingBuilder = ThinkingBlock.builder();
            if (!summaryText.isEmpty()) {
                thinkingBuilder.thinking(summaryText);
            }
            Map<String, Object> thinkingMetadata = new HashMap<>();
            if (encryptedContent != null) {
                thinkingMetadata.put(
                        OpenAIOfficialConstants.MD_REASONING_ENCRYPTED_CONTENT, encryptedContent);
            }
            if (!reasoningText.isEmpty()) {
                thinkingMetadata.put(OpenAIOfficialConstants.MD_REASONING_TEXT, reasoningText);
            }
            thinkingBuilder.metadata(thinkingMetadata);
            contentBlocks.add(thinkingBuilder.build());
        }

        // 文本块（TextBlock）
        String text = textBuilder.toString();
        if (!text.isEmpty()) {
            contentBlocks.add(TextBlock.builder().text(text).build());
        }

        // 工具块保留输出项顺序。
        contentBlocks.addAll(toolBlocks);

        // ── 元数据 + 用量 + finishReason ──
        String responseId = response.id();
        Map<String, Object> metadata = ResponsesHelper.extractResponseMetadata(response);
        String finishReason = (String) metadata.get(OpenAIOfficialConstants.MD_RESPONSE_STATUS);
        ChatUsage usage = ResponsesHelper.extractUsage(response, startTime);

        return ChatResponse.builder()
                .id(responseId)
                .content(contentBlocks)
                .usage(usage)
                .metadata(metadata)
                .finishReason(finishReason)
                .build();
    }

    private static void extractReasoning(
            ResponseReasoningItem reasoning,
            StringBuilder summaryBuilder,
            StringBuilder reasoningTextBuilder) {
        // 摘要部分：连接每个摘要项的所有 .text()
        for (ResponseReasoningItem.Summary summary : reasoning.summary()) {
            summaryBuilder.append(summary.text());
        }
        // 推理文本内容：连接每个内容项的所有 .text()
        Optional<List<ResponseReasoningItem.Content>> contentOpt = reasoning.content();
        if (contentOpt.isPresent()) {
            for (ResponseReasoningItem.Content content : contentOpt.get()) {
                reasoningTextBuilder.append(content.text());
            }
        }
    }

    private static void extractMessage(
            ResponseOutputMessage message, StringBuilder textBuilder, String modelName) {
        for (ResponseOutputMessage.Content content : message.content()) {
            if (content.isOutputText()) {
                textBuilder.append(content.asOutputText().text());
            } else if (content.isRefusal()) {
                throw new OpenAIOfficialModelException(
                        "Model response was refused: " + content.asRefusal().refusal(),
                        null,
                        modelName);
            }
            // 静默忽略未知内容部分类型
        }
    }

    private static ToolUseBlock extractFunctionCall(ResponseFunctionToolCall call) {
        String callId = call.callId();
        String name = call.name();
        String arguments = call.arguments();
        if (!ToolValidator.requireNonBlank("OpenAI official", name, callId)) {
            return null;
        }

        Map<String, Object> input;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JsonUtils.getJsonCodec().fromJson(arguments, Map.class);
            input = parsed != null ? parsed : new HashMap<>();
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to parse tool call arguments as JSON; preserving raw arguments:"
                            + " callId={}, name={}, error={}",
                    callId,
                    name,
                    e.getMessage());
            input = new HashMap<>();
        }

        Map<String, Object> metadata = new HashMap<>();
        call.namespace()
                .ifPresent(
                        namespace ->
                                metadata.put(
                                        OpenAIOfficialConstants.MD_FUNCTION_CALL_NAMESPACE,
                                        namespace));

        return ToolUseBlock.builder()
                .id(callId)
                .name(name)
                .input(input)
                .content(arguments)
                .metadata(metadata.isEmpty() ? null : metadata)
                .build();
    }
}
