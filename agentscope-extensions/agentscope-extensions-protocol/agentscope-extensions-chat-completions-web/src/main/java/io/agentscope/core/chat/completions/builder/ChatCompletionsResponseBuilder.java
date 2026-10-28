/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.chat.completions.builder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.chat.completions.model.ChatChoice;
import io.agentscope.core.chat.completions.model.ChatCompletionsRequest;
import io.agentscope.core.chat.completions.model.ChatCompletionsResponse;
import io.agentscope.core.chat.completions.model.ChatMessage;
import io.agentscope.core.chat.completions.model.ToolCall;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用于构建 Chat Completions API 响应的服务。
 *
 * <p>构造采用 OpenAI Chat Completions API 风格的响应对象；具体支持范围由本模块实现决定。
 *
 * <p>【学习提示】协议 DTO 的兼容格式不等于完整实现服务端 API；本模块还会生成自定义错误结束原因，
 * 具体字段、工具执行方式与流式工具结果应结合转换器读取。
 *
 * <p><b>响应格式：</b>
 *
 * <pre>{@code
 * {
 *   "id": "chatcmpl-xxx",
 *   "object": "chat.completion",
 *   "created": 1234567890,
 *   "model": "gpt-4",
 *   "choices": [{
 *     "index": 0,
 *     "message": {"role": "assistant", "content": "Hello!"},
 *     "finish_reason": "stop"
 *   }],
 *   "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
 * }
 * }</pre>
 *
 * <p><b>工具调用：</b>智能体使用工具时，响应包含 tool_calls：
 *
 * <pre>{@code
 * {
 *   "message": {
 *     "role": "assistant",
 *     "content": null,
 *     "tool_calls": [{
 *       "id": "call_abc",
 *       "type": "function",
 *       "function": {"name": "get_weather", "arguments": "{\"city\":\"Hangzhou\"}"}
 *     }]
 *   },
 *   "finish_reason": "tool_calls"
 * }
 * }</pre>
 */
public class ChatCompletionsResponseBuilder {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 构建成功的聊天补全响应。
     *
     * @param request 原始请求
     * @param reply 智能体的回复消息
     * @param requestId 用于跟踪的请求 ID
     * @return 包含智能体回复的 {@link ChatCompletionsResponse}
     */
    public ChatCompletionsResponse buildResponse(
            ChatCompletionsRequest request, Msg reply, String requestId) {
        ChatCompletionsResponse response = new ChatCompletionsResponse();
        response.setId(requestId);
        response.setObject("chat.completion");
        response.setCreated(Instant.now().getEpochSecond());
        response.setModel(request.getModel());

        ChatChoice choice = new ChatChoice();
        choice.setIndex(0);

        // 将 Msg 转换为 ChatMessage，同时处理工具调用
        ChatMessage message = convertMsgToChatMessage(reply);
        choice.setMessage(message);

        // 根据 GenerateReason 或工具调用设置 finish_reason
        GenerateReason generateReason = reply != null ? reply.getGenerateReason() : null;
        if (generateReason == GenerateReason.TOOL_SUSPENDED
                || (message.getToolCalls() != null && !message.getToolCalls().isEmpty())) {
            choice.setFinishReason("tool_calls");
        } else if (generateReason == GenerateReason.MAX_ITERATIONS) {
            choice.setFinishReason("length");
        } else {
            choice.setFinishReason("stop");
        }

        response.setChoices(List.of(choice));

        return response;
    }

    /**
     * 构建错误响应。
     *
     * @param request 原始请求
     * @param error 发生的错误
     * @param requestId 用于跟踪的请求 ID
     * @return 包含错误消息、结束原因设为 "error" 的 {@link ChatCompletionsResponse}
     */
    public ChatCompletionsResponse buildErrorResponse(
            ChatCompletionsRequest request, Throwable error, String requestId) {
        ChatCompletionsResponse response = new ChatCompletionsResponse();
        response.setId(requestId);
        response.setObject("chat.completion");
        response.setCreated(Instant.now().getEpochSecond());
        response.setModel(request.getModel());

        ChatChoice choice = new ChatChoice();
        choice.setIndex(0);
        String errorMessage = error != null ? error.getMessage() : "Unknown error occurred";
        ChatMessage message = new ChatMessage("assistant", "Error: " + errorMessage);
        choice.setMessage(message);
        choice.setFinishReason("error");

        response.setChoices(List.of(choice));

        return response;
    }

    /**
     * 将内部 Msg 转换为外部 ChatMessage。
     *
     * <p>处理文本内容和工具调用，以兼容 OpenAI 格式。
     *
     * @param msg 待转换的内部消息
     * @return 兼容 OpenAI 格式的 ChatMessage
     */
    public ChatMessage convertMsgToChatMessage(Msg msg) {
        if (msg == null) {
            return new ChatMessage("assistant", "");
        }

        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setRole(
                msg.getRole() != null ? msg.getRole().name().toLowerCase() : "assistant");

        List<ContentBlock> contentBlocks = msg.getContent();
        if (contentBlocks != null && !contentBlocks.isEmpty()) {
            // 提取文本内容：始终设置（可能为空字符串）
            String textContent =
                    contentBlocks.stream()
                            .filter(TextBlock.class::isInstance)
                            .map(TextBlock.class::cast)
                            .map(TextBlock::getText)
                            .collect(Collectors.joining());
            // 即使内容为空也设置该字段，以匹配 OpenAI 格式
            chatMessage.setContent(textContent);

            // 为 assistant 角色提取工具调用
            if (msg.getRole() == MsgRole.ASSISTANT) {
                List<ToolCall> toolCalls =
                        contentBlocks.stream()
                                .filter(ToolUseBlock.class::isInstance)
                                .map(ToolUseBlock.class::cast)
                                .map(this::convertToolUseBlockToToolCall)
                                .collect(Collectors.toList());
                if (!toolCalls.isEmpty()) {
                    chatMessage.setToolCalls(toolCalls);
                }
            }
        } else {
            // 没有内容块时设置空内容
            chatMessage.setContent("");
        }

        return chatMessage;
    }

    /**
     * 安全地从 Msg 提取文本内容。
     *
     * @param msg 待提取文本的消息
     * @return 文本内容 {@link String}；消息为 null 或不含文本时返回空字符串
     */
    public String extractTextContent(Msg msg) {
        if (msg == null) {
            return "";
        }
        String text = msg.getTextContent();
        return text != null ? text : "";
    }

    /**
     * 将 ToolUseBlock 转换为 ToolCall，并将输入 Map 序列化为 JSON 字符串。
     *
     * <p>优先使用 content 字段（原始 JSON 字符串），其次才使用 input Map，因为 DashScope 等提供方将参数放在 content 字段中。
     *
     * @param block 待转换的 ToolUseBlock
     * @return 兼容 OpenAI 格式的 ToolCall
     */
    private ToolCall convertToolUseBlockToToolCall(ToolUseBlock block) {
        // 优先使用 content 字段（原始 JSON 字符串），其次才使用 input 映射
        // DashScope 等提供方将参数存储在 content 字段中
        String argumentsJson;
        String content = block.getContent();
        Map<String, Object> input = block.getInput();

        if (content != null && !content.isEmpty()) {
            argumentsJson = content;
        } else if (input != null && !input.isEmpty()) {
            // 仅在 input 非空时进行序列化
            argumentsJson = serializeMapToJson(input);
        } else {
            // content 和 input 均为空：非流式响应使用空对象
            argumentsJson = "{}";
        }

        return new ToolCall(block.getId(), block.getName(), argumentsJson);
    }

    /**
     * 将 Map 序列化为 JSON 字符串。
     *
     * @param map 待序列化的映射
     * @return JSON 字符串；序列化失败时返回 "{}"
     */
    private String serializeMapToJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
