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
package io.agentscope.core.agent.test;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 测试用模拟 Model，提供可配置响应，无需真实 API 调用即可验证交互。
 */
public class MockModel implements Model {

    private final List<ChatResponse> responsesToReturn;
    private Function<List<Msg>, List<ChatResponse>> responseGenerator;
    private int callCount = 0;
    private List<Msg> lastMessages;
    private List<ToolSchema> lastTools;
    private GenerateOptions lastOptions;
    private boolean shouldThrowError = false;
    private String errorMessage = "Mock error";

    /**
     * 是否报告支持 {@code ToolChoice.Specific}。默认 {@code true}，不同于 {@link Model} 接口的 {@code false}；测试提示提醒回退时调用 {@link #setSupportsToolChoiceSpecific(boolean)} 设置 {@code false}。
     */
    private boolean supportsToolChoiceSpecific = true;

    /**
     * 创建返回简单文本的模拟模型。
     */
    public MockModel(String response) {
        this.responsesToReturn = new ArrayList<>();
        this.responsesToReturn.add(createTextResponse(response));
    }

    /**
     * 创建返回多个响应的模拟模型。
     */
    public MockModel(List<String> responses) {
        this.responsesToReturn = new ArrayList<>();
        for (String response : responses) {
            this.responsesToReturn.add(createTextResponse(response));
        }
    }

    /**
     * 通过自定义响应生成器创建模拟模型。
     */
    public MockModel(Function<List<Msg>, List<ChatResponse>> responseGenerator) {
        this.responsesToReturn = new ArrayList<>();
        this.responseGenerator = responseGenerator;
    }

    /**
     * 创建先思考再回答的模拟模型。
     */
    public static MockModel withThinking(String thinking, String finalResponse) {
        MockModel model = new MockModel("");
        model.responsesToReturn.clear();
        model.responsesToReturn.add(createThinkingResponse(thinking));
        model.responsesToReturn.add(createTextResponse(finalResponse));
        return model;
    }

    /**
     * 创建调用工具的模拟模型。
     */
    public static MockModel withToolCall(
            String toolName, String toolCallId, Map<String, Object> arguments) {
        MockModel model = new MockModel("");
        model.responsesToReturn.clear();
        model.responsesToReturn.add(createToolCallResponse(toolName, toolCallId, arguments));
        return model;
    }

    /**
     * 创建依次思考、调用工具、最终回答的模拟模型。
     */
    public static MockModel withFullFlow(String thinking, String toolName, String finalResponse) {
        MockModel model = new MockModel("");
        model.responsesToReturn.clear();
        model.responsesToReturn.add(createThinkingResponse(thinking));
        model.responsesToReturn.add(
                createToolCallResponse(toolName, "tool_call_1", new HashMap<>()));
        model.responsesToReturn.add(createTextResponse(finalResponse));
        return model;
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {

        // 记录调用
        this.callCount++;
        this.lastMessages = messages;
        this.lastTools = tools;
        this.lastOptions = options;

        // 配置了错误时抛出
        if (shouldThrowError) {
            return Flux.error(new RuntimeException(errorMessage));
        }

        // 提供了自定义生成器时使用它
        if (responseGenerator != null) {
            List<ChatResponse> responses = responseGenerator.apply(messages);
            return Flux.fromIterable(responses);
        }

        // 返回配置的响应
        if (responsesToReturn.isEmpty()) {
            return Flux.just(createTextResponse(TestConstants.MOCK_MODEL_SIMPLE_RESPONSE));
        }

        return Flux.fromIterable(responsesToReturn);
    }

    /**
     * 配置模型抛错。
     */
    public MockModel withError(String errorMessage) {
        this.shouldThrowError = true;
        this.errorMessage = errorMessage;
        return this;
    }

    /**
     * 获取模型调用次数。
     */
    public int getCallCount() {
        return callCount;
    }

    /**
     * 获取最后发送到模型的消息。
     */
    public List<Msg> getLastMessages() {
        return lastMessages;
    }

    /**
     * 获取最后发送到模型的工具。
     */
    public List<ToolSchema> getLastTools() {
        return lastTools;
    }

    /**
     * 获取最后发送到模型的选项。
     */
    public GenerateOptions getLastOptions() {
        return lastOptions;
    }

    /**
     * 重置模拟状态。
     */
    public void reset() {
        this.callCount = 0;
        this.lastMessages = null;
        this.lastTools = null;
        this.lastOptions = null;
        this.shouldThrowError = false;
        this.supportsToolChoiceSpecific = true;
    }

    @Override
    public String getModelName() {
        return "mock-model";
    }

    /**
     * 接口覆盖的 getter，不能加 {@code get} 前缀，参见 {@link Model}。
     */
    @Override
    public boolean supportsToolChoiceSpecific() {
        return supportsToolChoiceSpecific;
    }

    /**
     * 设置是否报告支持 {@code ToolChoice.Specific}。
     */
    public void setSupportsToolChoiceSpecific(boolean supportsToolChoiceSpecific) {
        this.supportsToolChoiceSpecific = supportsToolChoiceSpecific;
    }

    // 创建响应的辅助方法

    private static ChatResponse createTextResponse(String text) {
        return ChatResponse.builder()
                .id("msg_" + UUID.randomUUID().toString())
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(new ChatUsage(10, 20, 30))
                .build();
    }

    private static ChatResponse createThinkingResponse(String thinking) {
        return ChatResponse.builder()
                .id("msg_" + UUID.randomUUID().toString())
                .content(List.of(ThinkingBlock.builder().thinking(thinking).build()))
                .usage(new ChatUsage(5, 10, 15))
                .build();
    }

    private static ChatResponse createToolCallResponse(
            String toolName, String toolCallId, Map<String, Object> arguments) {
        Map<String, Object> args = arguments != null ? arguments : new HashMap<>();
        return ChatResponse.builder()
                .id("msg_" + UUID.randomUUID().toString())
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .name(toolName)
                                        .id(
                                                toolCallId != null
                                                        ? toolCallId
                                                        : UUID.randomUUID().toString())
                                        .input(args)
                                        .content(JsonUtils.getJsonCodec().toJson(args))
                                        .build()))
                .usage(new ChatUsage(8, 15, 23))
                .build();
    }
}
