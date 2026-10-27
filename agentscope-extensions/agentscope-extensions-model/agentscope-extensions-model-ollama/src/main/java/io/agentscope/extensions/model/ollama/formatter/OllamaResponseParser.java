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

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.tool.ToolValidator;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.ollama.dto.OllamaFunction;
import io.agentscope.extensions.model.ollama.dto.OllamaMessage;
import io.agentscope.extensions.model.ollama.dto.OllamaResponse;
import io.agentscope.extensions.model.ollama.dto.OllamaToolCall;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 将 OllamaResponse 对象转换为 AgentScope ChatResponse 对象的解析器。
 */
public class OllamaResponseParser {

    /**
     * 将 OllamaResponse 对象解析为 ChatResponse 对象。
     *
     * @param response 待解析的 OllamaResponse
     * @return 解析后的 ChatResponse
     */
    public ChatResponse parseResponse(OllamaResponse response) {
        OllamaMessage msg = response.getMessage();

        // 【学习提示】适配层把提供商的消息字段拆成框架通用内容块：思考、文本和工具调用
        // 保留不同类型，后续展示或执行无需直接依赖 Ollama DTO 的字段布局。
        List<ContentBlock> contentBlocks = new ArrayList<>();

        // 1. 处理思考内容（启用了 `think` 选项的思考模型）
        if (msg != null && msg.getThinking() != null && !msg.getThinking().isEmpty()) {
            contentBlocks.add(ThinkingBlock.builder().thinking(msg.getThinking()).build());
        }

        // 2. 处理文本内容
        if (msg != null && msg.getContent() != null && !msg.getContent().isEmpty()) {
            contentBlocks.add(TextBlock.builder().text(msg.getContent()).build());
        }

        // 3. 处理工具调用
        if (msg != null) {
            List<OllamaToolCall> toolCalls = msg.getToolCalls();
            if (toolCalls != null && !toolCalls.isEmpty()) {
                for (OllamaToolCall toolCall : toolCalls) {
                    OllamaFunction fn = toolCall.getFunction();
                    if (fn != null) {
                        Map<String, Object> input = fn.getArguments();
                        // ToolUseBlock 需要 ID。Ollama 在非流式模式下可能不返回 ID？
                        // 也可以自行生成；OpenAI 会生成 ID。
                        // 如果 Ollama 不提供 ID，就生成随机 UUID，以满足
                        // AgentScope 要求。
                        String callId = UUID.randomUUID().toString();
                        if (!ToolValidator.requireNonBlank("Ollama", fn.getName(), callId)) {
                            continue;
                        }

                        // 将输入转为 JSON 字符串，以供 ToolExecutor 验证
                        // 无参数工具的输入将为 null 或空映射 {}
                        String argumentsJson;
                        if (input == null || input.isEmpty()) {
                            argumentsJson = "{}";
                        } else {
                            argumentsJson = JsonUtils.getJsonCodec().toJson(input);
                        }
                        contentBlocks.add(
                                new ToolUseBlock(callId, fn.getName(), input, argumentsJson, null));
                    }
                }
            }
        }

        // 4. 映射用量
        int inputTokens = response.getPromptEvalCount() != null ? response.getPromptEvalCount() : 0;
        int outputTokens = response.getEvalCount() != null ? response.getEvalCount() : 0;
        int cachedTokens =
                response.getPromptEvalCachedCount() != null
                        ? response.getPromptEvalCachedCount()
                        : 0;
        // Ollama 时长单位为纳秒，转换为秒
        double time = response.getTotalDuration() != null ? response.getTotalDuration() / 1e9 : 0.0;

        ChatUsage usage =
                ChatUsage.builder()
                        .inputTokens(inputTokens)
                        .outputTokens(outputTokens)
                        .cachedTokens(cachedTokens)
                        .time(time)
                        .build();

        // 5. 映射元数据
        Map<String, Object> metadata = new HashMap<>();
        if (response.getModel() != null) metadata.put("model", response.getModel());
        if (response.getCreatedAt() != null) metadata.put("created_at", response.getCreatedAt());
        if (response.getTotalDuration() != null)
            metadata.put("total_duration", response.getTotalDuration());
        if (response.getLoadDuration() != null)
            metadata.put("load_duration", response.getLoadDuration());
        if (response.getPromptEvalCount() != null)
            metadata.put("prompt_eval_count", response.getPromptEvalCount());
        if (response.getPromptEvalCachedCount() != null)
            metadata.put("prompt_eval_cached_count", response.getPromptEvalCachedCount());
        if (response.getPromptEvalDuration() != null)
            metadata.put("prompt_eval_duration", response.getPromptEvalDuration());
        if (response.getEvalCount() != null) metadata.put("eval_count", response.getEvalCount());
        if (response.getEvalDuration() != null)
            metadata.put("eval_duration", response.getEvalDuration());
        if (response.getDone() != null) metadata.put("done", response.getDone());

        // 使用 Builder 创建 ChatResponse
        ChatResponse.Builder builder =
                ChatResponse.builder().content(contentBlocks).usage(usage).metadata(metadata);

        if (response.getDoneReason() != null) {
            builder.finishReason(response.getDoneReason());
        } else if (response.getDone() != null && response.getDone()) {
            builder.finishReason("stop"); // doneReason 缺失但 done 为 true 时的回退处理
        }

        return builder.build();
    }
}
