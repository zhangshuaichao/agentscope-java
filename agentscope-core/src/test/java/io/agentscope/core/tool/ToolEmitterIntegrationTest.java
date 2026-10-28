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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.hook.ActingChunkEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * ToolEmitter 与 Hook 配合的集成测试。
 */
class ToolEmitterIntegrationTest {

    private Toolkit toolkit;
    private List<ToolResultBlock> capturedChunks;
    private List<ToolUseBlock> capturedToolUseBlocks;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        capturedChunks = new ArrayList<>();
        capturedToolUseBlocks = new ArrayList<>();
    }

    @Test
    @DisplayName("Tool with ToolEmitter should send chunks to Hook")
    void testToolEmitterWithHook() {
        // 注册使用 ToolEmitter 的工具
        toolkit.registerTool(
                new Object() {
                    @Tool(name = "stream_task", description = "A task that emits streaming chunks")
                    public ToolResultBlock execute(
                            @ToolParam(name = "input", description = "Input data") String input,
                            ToolEmitter emitter) {
                        // 发出中间内容块
                        emitter.emit(ToolResultBlock.text("Step 1: Initializing"));
                        emitter.emit(ToolResultBlock.text("Step 2: Processing " + input));
                        emitter.emit(ToolResultBlock.text("Step 3: Finalizing"));

                        // 返回最终结果
                        return ToolResultBlock.text("Task completed for: " + input);
                    }
                });

        // 设置内容块回调
        toolkit.setChunkCallback(
                (toolUse, chunk) -> {
                    capturedChunks.add(chunk);
                    capturedToolUseBlocks.add(toolUse);
                });

        // 创建 ToolUseBlock
        Map<String, Object> input = Map.of("input", "test-data");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-1")
                        .name("stream_task")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        // 调用工具
        ToolResultBlock finalResponse =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolUseBlock).build())
                        .block();

        // 验证最终响应
        assertNotNull(finalResponse);
        // 输出现在是 List<ContentBlock>
        List<ContentBlock> outputs = finalResponse.getOutput();
        assertEquals(1, outputs.size());
        TextBlock textBlock = (TextBlock) outputs.get(0);
        assertEquals("Task completed for: test-data", textBlock.getText());

        // 验证捕获了内容块
        assertEquals(3, capturedChunks.size(), "Should have captured 3 chunks");
        assertEquals("Step 1: Initializing", extractText(capturedChunks.get(0)));
        assertEquals("Step 2: Processing test-data", extractText(capturedChunks.get(1)));
        assertEquals("Step 3: Finalizing", extractText(capturedChunks.get(2)));

        // 验证所有块来自同一工具调用
        for (ToolUseBlock toolUse : capturedToolUseBlocks) {
            assertEquals("test-call-1", toolUse.getId());
            assertEquals("stream_task", toolUse.getName());
        }
    }

    @Test
    @DisplayName("Tool without ToolEmitter should work normally")
    void testToolWithoutEmitter() {
        // 注册不带 ToolEmitter 的普通工具
        toolkit.registerTool(
                new Object() {
                    @Tool(name = "simple_task", description = "A simple task")
                    public ToolResultBlock execute(
                            @ToolParam(name = "input", description = "Input") String input) {
                        return ToolResultBlock.text("Result: " + input);
                    }
                });

        // 设置不应被调用的内容块回调
        toolkit.setChunkCallback(
                (toolUse, chunk) -> {
                    capturedChunks.add(chunk);
                });

        // 调用工具
        Map<String, Object> input = Map.of("input", "hello");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-2")
                        .name("simple_task")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        ToolResultBlock finalResponse =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolUseBlock).build())
                        .block();

        // 验证最终响应
        assertNotNull(finalResponse);
        assertEquals("Result: hello", extractText(finalResponse));

        // 验证未发出内容块
        assertEquals(
                0, capturedChunks.size(), "No chunks should be emitted for tools without emitter");
    }

    @Test
    @DisplayName("Multiple tools with emitters should emit chunks correctly")
    void testMultipleToolsWithEmitters() {
        // 注册两个带 emitter 的工具
        toolkit.registerTool(
                new Object() {
                    @Tool(name = "task_a", description = "Task A")
                    public ToolResultBlock taskA(
                            @ToolParam(name = "data") String data, ToolEmitter emitter) {
                        emitter.emit(ToolResultBlock.text("A: Started"));
                        emitter.emit(ToolResultBlock.text("A: Processing"));
                        return ToolResultBlock.text("A: Done");
                    }
                });

        toolkit.registerTool(
                new Object() {
                    @Tool(name = "task_b", description = "Task B")
                    public ToolResultBlock taskB(
                            @ToolParam(name = "data") String data, ToolEmitter emitter) {
                        emitter.emit(ToolResultBlock.text("B: Started"));
                        return ToolResultBlock.text("B: Done");
                    }
                });

        toolkit.setChunkCallback(
                (toolUse, chunk) -> {
                    capturedChunks.add(chunk);
                    capturedToolUseBlocks.add(toolUse);
                });

        // 调用 task_a
        Map<String, Object> inputA = Map.of("data", "x");
        ToolUseBlock toolA =
                ToolUseBlock.builder()
                        .id("call-a")
                        .name("task_a")
                        .input(inputA)
                        .content(JsonUtils.getJsonCodec().toJson(inputA))
                        .build();
        toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolA).build()).block();

        // 调用 task_b
        Map<String, Object> inputB = Map.of("data", "y");
        ToolUseBlock toolB =
                ToolUseBlock.builder()
                        .id("call-b")
                        .name("task_b")
                        .input(inputB)
                        .content(JsonUtils.getJsonCodec().toJson(inputB))
                        .build();
        toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolB).build()).block();

        // 验证内容块
        assertEquals(3, capturedChunks.size());
        assertEquals("A: Started", extractText(capturedChunks.get(0)));
        assertEquals("A: Processing", extractText(capturedChunks.get(1)));
        assertEquals("B: Started", extractText(capturedChunks.get(2)));

        // 验证工具使用块
        assertEquals("call-a", capturedToolUseBlocks.get(0).getId());
        assertEquals("call-a", capturedToolUseBlocks.get(1).getId());
        assertEquals("call-b", capturedToolUseBlocks.get(2).getId());
    }

    @Test
    @DisplayName("ReActAgent should preserve user chunk callback while emitting ActingChunkEvent")
    void testReActAgentPreservesUserChunkCallback() {
        toolkit.registerTool(new StreamingTool());

        List<String> userChunks = new CopyOnWriteArrayList<>();
        toolkit.setChunkCallback((toolUse, chunk) -> userChunks.add(extractText(chunk)));

        List<String> hookChunks = new CopyOnWriteArrayList<>();
        Hook captureHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof ActingChunkEvent actingChunkEvent) {
                            hookChunks.add(extractText(actingChunkEvent.getChunk()));
                        }
                        return Mono.just(event);
                    }
                };

        AtomicInteger modelCallCount = new AtomicInteger(0);
        MockModel model =
                new MockModel(
                        messages -> {
                            if (modelCallCount.getAndIncrement() == 0) {
                                return List.of(createToolCallResponse());
                            }
                            return List.of(createTextResponse("final-response"));
                        });

        ReActAgent agent =
                ReActAgent.builder()
                        .name("Issue870Agent")
                        .sysPrompt("You are a helpful assistant.")
                        .model(model)
                        .toolkit(toolkit)
                        .hook(captureHook)
                        .build();

        Msg response = agent.call(createUserMessage()).block();

        assertNotNull(response);
        assertEquals("final-response", extractText(response));
        assertEquals(List.of("chunk:1:demo", "chunk:2:demo"), userChunks);
        assertEquals(List.of("chunk:1:demo", "chunk:2:demo"), hookChunks);
    }

    @Test
    @DisplayName("Internal chunk callback failure should not block user callback")
    void testInternalChunkCallbackFailureDoesNotBlockUserCallback() {
        toolkit.registerTool(new StreamingTool());

        List<String> userChunks = new CopyOnWriteArrayList<>();
        toolkit.setChunkCallback((toolUse, chunk) -> userChunks.add(extractText(chunk)));
        BiConsumer<ToolUseBlock, ToolResultBlock> internal =
                (toolUse, chunk) -> {
                    throw new IllegalStateException("boom");
                };

        List<ToolResultBlock> results =
                toolkit.callTools(
                                List.of(createToolCall("stream_task", Map.of("input", "demo"))),
                                null,
                                null,
                                null,
                                internal)
                        .block();

        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("tool-result:demo", extractText(results.get(0)));
        assertEquals(List.of("chunk:1:demo", "chunk:2:demo"), userChunks);
    }

    @Test
    @DisplayName("User chunk callback failure should not interrupt tool execution")
    void testUserChunkCallbackFailureDoesNotInterruptToolExecution() {
        toolkit.registerTool(new StreamingTool());
        toolkit.setChunkCallback(
                (toolUse, chunk) -> {
                    throw new IllegalStateException("user callback failure");
                });

        ToolResultBlock finalResponse =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(
                                                createToolCall(
                                                        "stream_task", Map.of("input", "demo")))
                                        .build())
                        .block();

        assertNotNull(finalResponse);
        assertEquals("tool-result:demo", extractText(finalResponse));
    }

    @Test
    @DisplayName("Internal chunk callback failure should not interrupt tool execution")
    void testInternalChunkCallbackFailureDoesNotInterruptToolExecution() {
        toolkit.registerTool(new StreamingTool());
        BiConsumer<ToolUseBlock, ToolResultBlock> internal =
                (toolUse, chunk) -> {
                    throw new IllegalStateException("internal callback failure");
                };

        List<ToolResultBlock> results =
                toolkit.callTools(
                                List.of(createToolCall("stream_task", Map.of("input", "demo"))),
                                null,
                                null,
                                null,
                                internal)
                        .block();

        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("tool-result:demo", extractText(results.get(0)));
    }

    /**
     * 从 ToolResultBlock 提取文本的辅助方法。
     */
    private String extractText(ToolResultBlock response) {
        // ToolResultBlock 输出现在为 List<ContentBlock>
        List<ContentBlock> outputs = response.getOutput();
        if (outputs.isEmpty()) return "";
        TextBlock block = (TextBlock) outputs.get(0);
        return block.getText();
    }

    private String extractText(Msg msg) {
        return ((TextBlock) msg.getContent().get(0)).getText();
    }

    private Msg createUserMessage() {
        return Msg.builder()
                .name("User")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text("Use the streaming tool").build())
                .build();
    }

    private ChatResponse createToolCallResponse() {
        return ChatResponse.builder()
                .content(List.of(createToolCall("stream_task", Map.of("input", "demo"))))
                .usage(new ChatUsage(8, 15, 23))
                .build();
    }

    private ChatResponse createTextResponse(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(new ChatUsage(10, 20, 30))
                .build();
    }

    private ToolUseBlock createToolCall(String name, Map<String, Object> input) {
        return ToolUseBlock.builder()
                .id("call-1")
                .name(name)
                .input(input)
                .content(JsonUtils.getJsonCodec().toJson(input))
                .build();
    }

    static class StreamingTool {

        @Tool(name = "stream_task", description = "Emit streaming chunks and return a final result")
        public ToolResultBlock execute(
                @ToolParam(name = "input", description = "Input text") String input,
                ToolEmitter emitter) {
            emitter.emit(ToolResultBlock.text("chunk:1:" + input));
            emitter.emit(ToolResultBlock.text("chunk:2:" + input));
            return ToolResultBlock.text("tool-result:" + input);
        }
    }
}
