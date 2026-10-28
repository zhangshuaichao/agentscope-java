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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * ReActAgent 超时功能单元测试。
 */
@DisplayName("ReActAgent Timeout Tests")
class ReActAgentTimeoutTest {

    @Test
    @DisplayName("Should timeout tool execution when exceeds toolExecutionTimeout")
    void testToolExecutionTimeout() {
        // 模型先返回工具调用，再返回最终响应
        Model modelWithToolCall = createModelWithToolCallThenResponse();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SlowTools());

        ExecutionConfig toolExecutionConfig =
                ExecutionConfig.builder().timeout(Duration.ofMillis(100)).build();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(modelWithToolCall)
                        .toolkit(toolkit)
                        .toolExecutionConfig(toolExecutionConfig)
                        .build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Call slow_tool").build())
                        .build();

        // 工具执行应超时，并在工具结果中返回错误
        StepVerifier.create(agent.call(testMsg))
                .assertNext(
                        response -> {
                            // 智能体应返回响应，而不是抛错
                            assertNotNull(response);
                            // 检查记忆中存在带超时错误的工具结果
                            List<Msg> messages = agent.getAgentState().getContext();
                            boolean foundTimeoutError = false;
                            for (Msg msg : messages) {
                                if (msg.getRole() == MsgRole.TOOL) {
                                    // 在内容块中检查 ToolResultBlock
                                    for (ContentBlock block : msg.getContent()) {
                                        if (block instanceof ToolResultBlock) {
                                            ToolResultBlock toolResult = (ToolResultBlock) block;
                                            // 检查 ToolResultBlock 输出
                                            for (ContentBlock output : toolResult.getOutput()) {
                                                if (output instanceof TextBlock) {
                                                    String text = ((TextBlock) output).getText();
                                                    if (text != null
                                                            && (text.contains(
                                                                            "Tool execution"
                                                                                    + " timeout")
                                                                    || text.contains("timeout"))) {
                                                        foundTimeoutError = true;
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                        if (foundTimeoutError) {
                                            break;
                                        }
                                    }
                                    if (foundTimeoutError) {
                                        break;
                                    }
                                }
                            }
                            assertTrue(
                                    foundTimeoutError,
                                    "Expected to find tool timeout error in memory");
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should not apply timeout when toolExecutionConfig is null")
    void testNoTimeoutWhenToolExecutionConfigNull() {
        Model modelWithToolCall = createModelWithToolCall();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new FastTools());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(modelWithToolCall)
                        .toolkit(toolkit)
                        // 不设置 toolExecutionConfig
                        .build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Call fast_tool").build())
                        .build();

        // 无超时配置也应成功完成
        Msg response = agent.call(testMsg).block();

        assertNotNull(response);
    }

    // 辅助方法

    /**
     * 创建延迟指定时长响应的模拟模型，用于调用超时测试。
     * @param delay 响应前延迟
     * @return 延迟响应的 Model
     */
    private Model createSlowModel(Duration delay) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Mono.delay(delay)
                        .thenMany(
                                Flux.just(
                                        new ChatResponse(
                                                "test-id",
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("Response from slow model")
                                                                .build()),
                                                null,
                                                null,
                                                null)));
            }

            @Override
            public String getModelName() {
                return "slow-model";
            }
        };
    }

    /**
     * 创建立即响应的模拟模型，返回无工具调用的简单文本。
     * @return 立即响应的 Model
     */
    private Model createFastModel() {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(
                        new ChatResponse(
                                "test-id",
                                List.of(
                                        TextBlock.builder()
                                                .text("Response from fast model")
                                                .build()),
                                null,
                                null,
                                null));
            }

            @Override
            public String getModelName() {
                return "fast-model";
            }
        };
    }

    /**
     * 创建返回工具调用的模拟模型，ToolUseBlock 触发工具执行，供工具超时测试。
     * @return 调用 slow_tool 的 Model
     */
    private Model createModelWithToolCall() {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                // 返回 slow_tool 工具调用响应
                Map<String, Object> input = Map.of("input", "test");
                return Flux.just(
                        new ChatResponse(
                                "test-id",
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("tool-call-1")
                                                .name("slow_tool")
                                                .input(input)
                                                .content(JsonUtils.getJsonCodec().toJson(input))
                                                .build()),
                                null,
                                null,
                                null));
            }

            @Override
            public String getModelName() {
                return "model-with-tool-call";
            }
        };
    }

    /**
     * 首次返回工具调用，后续返回最终文本，防止工具失败测试无限循环。
     * @return 支持多轮推理的 Model
     */
    private Model createModelWithToolCallThenResponse() {
        return new Model() {
            private final AtomicBoolean firstCall = new AtomicBoolean(true);

            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                if (firstCall.compareAndSet(true, false)) {
                    // 首次返回工具调用
                    Map<String, Object> input = Map.of("input", "test");
                    return Flux.just(
                            new ChatResponse(
                                    "test-id-1",
                                    List.of(
                                            ToolUseBlock.builder()
                                                    .id("tool-call-1")
                                                    .name("slow_tool")
                                                    .input(input)
                                                    .content(JsonUtils.getJsonCodec().toJson(input))
                                                    .build()),
                                    null,
                                    null,
                                    null));
                } else {
                    // 后续返回最终文本
                    return Flux.just(
                            new ChatResponse(
                                    "test-id-2",
                                    List.of(
                                            TextBlock.builder()
                                                    .text("Tool execution failed due to timeout")
                                                    .build()),
                                    null,
                                    null,
                                    null));
                }
            }

            @Override
            public String getModelName() {
                return "model-with-tool-call-then-response";
            }
        };
    }

    // 慢执行测试工具

    public static class SlowTools {
        @Tool(description = "A slow tool for testing timeout")
        public String slow_tool(@ToolParam(name = "input") String input) {
            try {
                Thread.sleep(5000); // 睡眠 5 秒
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "Slow tool result: " + input;
        }
    }

    // 快速执行测试工具

    public static class FastTools {
        @Tool(description = "A fast tool for testing")
        public String fast_tool(@ToolParam(name = "input") String input) {
            return "Fast tool result: " + input;
        }
    }
}
