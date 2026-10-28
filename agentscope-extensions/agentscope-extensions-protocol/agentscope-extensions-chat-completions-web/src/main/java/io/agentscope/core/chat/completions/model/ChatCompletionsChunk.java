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
package io.agentscope.core.chat.completions.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Chat Completions API 的流式分块。
 *
 * <p>采用 OpenAI 聊天补全的流式响应格式，每个分块携带部分内容的增量。
 *
 * <p><b>流式分块示例：</b>
 *
 * <pre>
 * {
 *   "id": "chatcmpl-xxx",
 *   "object": "chat.completion.chunk",
 *   "created": 1234567890,
 *   "model": "gpt-4",
 *   "choices": [{
 *     "index": 0,
 *     "delta": {"content": "Hello"},
 *     "finish_reason": null
 *   }]
 * }
 * </pre>
 *
 * <p><b>工具调用流式分块：</b>
 *
 * <pre>
 * {
 *   "id": "chatcmpl-xxx",
 *   "object": "chat.completion.chunk",
 *   "created": 1234567890,
 *   "model": "gpt-4",
 *   "choices": [{
 *     "index": 0,
 *     "delta": {
 *       "tool_calls": [{
 *         "index": 0,
 *         "id": "call_abc",
 *         "type": "function",
 *         "function": {"name": "get_weather", "arguments": "{...}"}
 *       }]
 *     },
 *     "finish_reason": null
 *   }]
 * }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionsChunk {

    private String id;

    private String object = "chat.completion.chunk";

    private long created;

    private String model;

    private List<ChatChoice> choices;

    /** 默认构造器。 */
    public ChatCompletionsChunk() {
        this.created = Instant.now().getEpochSecond();
    }

    /**
     * 使用基本信息创建分块。
     *
     * @param id 请求 ID
     * @param model 模型名称
     */
    public ChatCompletionsChunk(String id, String model) {
        this();
        this.id = id;
        this.model = model;
    }

    /**
     * 创建文本内容分块。
     *
     * @param id 请求 ID
     * @param model 模型名称
     * @param content 文本内容增量
     * @return 携带文本增量的 ChatCompletionsChunk
     */
    public static ChatCompletionsChunk textChunk(String id, String model, String content) {
        ChatCompletionsChunk chunk = new ChatCompletionsChunk(id, model);

        ChatMessage delta = new ChatMessage();
        delta.setRole("assistant");
        delta.setContent(content);

        ChatChoice choice = new ChatChoice();
        choice.setIndex(0);
        choice.setDelta(delta);

        chunk.setChoices(List.of(choice));
        return chunk;
    }

    /**
     * 创建工具调用分块。
     *
     * @param id 请求 ID
     * @param model 模型名称
     * @param toolCalls 要包含的工具调用
     * @return 携带工具调用的 ChatCompletionsChunk
     */
    public static ChatCompletionsChunk toolCallChunk(
            String id, String model, List<ToolCall> toolCalls) {
        ChatCompletionsChunk chunk = new ChatCompletionsChunk(id, model);

        ChatMessage delta = new ChatMessage();
        delta.setRole("assistant");
        delta.setToolCalls(toolCalls);

        ChatChoice choice = new ChatChoice();
        choice.setIndex(0);
        choice.setDelta(delta);

        chunk.setChoices(List.of(choice));
        return chunk;
    }

    /**
     * 创建工具结果分块。
     *
     * <p>以流式格式表示工具执行结果。工具执行结果不是标准 OpenAI 流式补全中的独立结果事件；AgentScope 的 ReActAgent 会在内部执行工具，因此此处将结果暴露在流中。
     *
     * <p><b>注意：</b>本实现将工具结果格式化为 assistant 文本内容，前缀标出工具名称，而不是使用 delta.role="tool"。
     *
     * <p><b>输出示例：</b>
     *
     * <pre>
     * {
     *   "id": "chatcmpl-xxx",
     *   "object": "chat.completion.chunk",
     *   "created": 1234567890,
     *   "model": "gpt-4",
     *   "choices": [{
     *     "index": 0,
     *     "delta": {
     *       "role": "assistant",
     *       "content": "[Tool: get_weather] The weather is sunny..."
     *     }
     *   }]
     * }
     * </pre>
     *
     * @param id 请求 ID
     * @param model 模型名称
     * @param toolCallId 该结果对应的工具调用 ID（当前流式输出未使用）
     * @param toolName 已执行工具的名称
     * @param content 工具执行结果内容
     * @return 将工具结果格式化为 assistant 内容的 ChatCompletionsChunk
     */
    public static ChatCompletionsChunk toolResultChunk(
            String id, String model, String toolCallId, String toolName, String content) {
        ChatCompletionsChunk chunk = new ChatCompletionsChunk(id, model);

        ChatMessage delta = new ChatMessage();
        delta.setRole("assistant");
        // 为兼容流式输出，将工具结果格式化为 assistant 内容
        // 此处不在流式 delta 中使用 role: "tool"
        delta.setContent("[Tool: " + toolName + "] " + content);

        ChatChoice choice = new ChatChoice();
        choice.setIndex(0);
        choice.setDelta(delta);

        chunk.setChoices(List.of(choice));
        return chunk;
    }

    /**
     * 创建结束分块。
     *
     * @param id 请求 ID
     * @param model 模型名称
     * @param finishReason 结束原因（stop、tool_calls 等）
     * @return 携带 finish_reason 的 ChatCompletionsChunk
     */
    public static ChatCompletionsChunk finishChunk(String id, String model, String finishReason) {
        ChatCompletionsChunk chunk = new ChatCompletionsChunk(id, model);

        ChatMessage delta = new ChatMessage();

        ChatChoice choice = new ChatChoice();
        choice.setIndex(0);
        choice.setDelta(delta);
        choice.setFinishReason(finishReason);

        chunk.setChoices(List.of(choice));
        return chunk;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public long getCreated() {
        return created;
    }

    public void setCreated(long created) {
        this.created = created;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<ChatChoice> getChoices() {
        return choices;
    }

    public void setChoices(List<ChatChoice> choices) {
        this.choices = choices;
    }
}
