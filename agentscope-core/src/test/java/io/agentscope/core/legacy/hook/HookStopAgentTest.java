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
package io.agentscope.core.legacy.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Hook 停止智能体功能的综合测试。覆盖 PostReasoningEvent/PostActingEvent 的 stopAgent()/isStopRequested()、ReActAgent 停止时返回 ToolUse/ToolResult、恢复、Hook 集成、边界与错误处理。
 */
class HookStopAgentTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    private Model mockModel;
    private Toolkit toolkit;
    private Memory memory;

    @BeforeEach
    void setUp() {
        mockModel = mock(Model.class);
        when(mockModel.getModelName()).thenReturn("test-model");
        toolkit = new Toolkit();
        memory = new InMemoryMemory();
    }

    // ==================== A. PostReasoningEvent 测试 ====================

    @Nested
    @DisplayName("A. PostReasoningEvent Tests")
    class PostReasoningEventTests {

        @Test
        @DisplayName("stopAgent() sets the stop flag to true")
        void testStopAgentSetsFlag() {
            Agent mockAgent = mock(Agent.class);
            Msg msg = createTextMsg("test");
            PostReasoningEvent event = new PostReasoningEvent(mockAgent, "test-model", null, msg);

            assertFalse(event.isStopRequested(), "Initially should be false");

            event.stopAgent();

            assertTrue(event.isStopRequested(), "After stopAgent() should be true");
        }

        @Test
        @DisplayName("Default isStopRequested() returns false")
        void testDefaultNotStopped() {
            Agent mockAgent = mock(Agent.class);
            Msg msg = createTextMsg("test");
            PostReasoningEvent event = new PostReasoningEvent(mockAgent, "test-model", null, msg);

            assertFalse(event.isStopRequested());
        }

        @Test
        @DisplayName("Multiple stopAgent() calls do not cause errors")
        void testMultipleStopAgentCalls() {
            Agent mockAgent = mock(Agent.class);
            Msg msg = createTextMsg("test");
            PostReasoningEvent event = new PostReasoningEvent(mockAgent, "test-model", null, msg);

            event.stopAgent();
            event.stopAgent();
            event.stopAgent();

            assertTrue(event.isStopRequested());
        }
    }

    // ==================== B. PostActingEvent 测试 ====================

    @Nested
    @DisplayName("B. PostActingEvent Tests")
    class PostActingEventTests {

        @Test
        @DisplayName("stopAgent() sets the stop flag to true")
        void testStopAgentSetsFlag() {
            Agent mockAgent = mock(Agent.class);
            Toolkit mockToolkit = mock(Toolkit.class);
            ToolUseBlock toolUse = createToolUseBlock("tool1", "test_tool");
            ToolResultBlock toolResult = ToolResultBlock.text("result");

            PostActingEvent event =
                    new PostActingEvent(mockAgent, mockToolkit, toolUse, toolResult);

            assertFalse(event.isStopRequested(), "Initially should be false");

            event.stopAgent();

            assertTrue(event.isStopRequested(), "After stopAgent() should be true");
        }

        @Test
        @DisplayName("Default isStopRequested() returns false")
        void testDefaultNotStopped() {
            Agent mockAgent = mock(Agent.class);
            Toolkit mockToolkit = mock(Toolkit.class);
            ToolUseBlock toolUse = createToolUseBlock("tool1", "test_tool");
            ToolResultBlock toolResult = ToolResultBlock.text("result");

            PostActingEvent event =
                    new PostActingEvent(mockAgent, mockToolkit, toolUse, toolResult);

            assertFalse(event.isStopRequested());
        }
    }

    // ==================== C. ReActAgent 停止场景 ====================

    @Nested
    @DisplayName("C. ReActAgent Stop Scenarios")
    class StopScenarioTests {

        @Test
        @DisplayName("PostReasoning stop returns message with ToolUseBlock")
        void testPostReasoningStopReturnsToolUseMsg() {
            // 设置模型返回包含工具调用的响应
            Msg toolUseMsg = createToolUseMsg("tool1", "search", Map.of("query", "test"));
            setupModelToReturnToolUse(toolUseMsg);

            // 创建推理后停止的钩子
            Hook stopHook = createPostReasoningStopHook();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .checkRunning(false)
                            .hook(stopHook)
                            .build();

            Msg userMsg = createUserMsg("Hello");
            Msg result = agent.call(userMsg).block(TEST_TIMEOUT);

            assertNotNull(result);
            assertTrue(
                    result.hasContentBlocks(ToolUseBlock.class),
                    "Stop at PostReasoning should return ToolUse message");
        }

        @Test
        @DisplayName("Stop does not execute subsequent tools")
        void testStopDoesNotExecuteSubsequentTools() {
            AtomicBoolean toolExecuted = new AtomicBoolean(false);

            // 注册工具
            toolkit.registerTool(new TestToolClass(toolExecuted));

            // 设置模型返回工具调用
            Msg toolUseMsg = createToolUseMsg("tool1", "test_tool", Map.of());
            setupModelToReturnToolUse(toolUseMsg);

            // 创建推理后停止的钩子
            Hook stopHook = createPostReasoningStopHook();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .checkRunning(false)
                            .hook(stopHook)
                            .build();

            agent.call(createUserMsg("test")).block(TEST_TIMEOUT);

            assertFalse(toolExecuted.get(), "Tool should not be executed when stopped");
        }
    }

    // ==================== D. ReActAgent 恢复场景 ====================

    @Nested
    @DisplayName("D. ReActAgent Resume Scenarios")
    class ResumeScenarioTests {

        @Test
        @DisplayName("Resume with no args after PostReasoning stop continues Acting")
        void testResumeWithNoArgsAfterPostReasoningStop() {
            AtomicBoolean toolExecuted = new AtomicBoolean(false);
            AtomicInteger stopCount = new AtomicInteger(0);

            // 注册工具
            toolkit.registerTool(new TestToolClass(toolExecuted));

            // 设置模型先返回工具调用，再返回文本
            Msg toolUseMsg = createToolUseMsg("tool1", "test_tool", Map.of());
            Msg textResponse = createAssistantTextMsg("Done!");

            when(mockModel.stream(anyList(), anyList(), any()))
                    .thenReturn(createFluxFromMsg(toolUseMsg))
                    .thenReturn(createFluxFromMsg(textResponse));

            // 创建仅在首次 PostReasoning 停止的钩子
            Hook stopOnceHook =
                    new Hook() {
                        @Override
                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                            if (event instanceof PostReasoningEvent e
                                    && stopCount.getAndIncrement() == 0) {
                                e.stopAgent();
                            }
                            return Mono.just(event);
                        }
                    };

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .checkRunning(false)
                            .hook(stopOnceHook)
                            .build();

            // 首次调用停止，返回 ToolUse 消息
            Msg result1 = agent.call(createUserMsg("test")).block(TEST_TIMEOUT);
            assertTrue(
                    result1.hasContentBlocks(ToolUseBlock.class),
                    "First call should return ToolUse message");
            assertFalse(toolExecuted.get(), "Tool should not be executed yet");

            // 无参数恢复
            Msg result2 = agent.call().block(TEST_TIMEOUT);

            assertTrue(toolExecuted.get(), "Tool should be executed after resume");
            assertFalse(
                    result2.hasContentBlocks(ToolUseBlock.class),
                    "Final result should not have pending ToolUse");
        }

        @Test
        @DisplayName("Resume with ToolResult message continues execution")
        void testResumeWithToolResultMsg() {
            // 设置模型先返回工具调用再返回文本
            Msg toolUseMsg = createToolUseMsg("tool1", "test_tool", Map.of());
            Msg textResponse = createAssistantTextMsg("Processed result");

            when(mockModel.stream(anyList(), anyList(), any()))
                    .thenReturn(createFluxFromMsg(toolUseMsg))
                    .thenReturn(createFluxFromMsg(textResponse));

            Hook stopHook = createPostReasoningStopHook();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .checkRunning(false)
                            .hook(stopHook)
                            .build();

            // 首次调用停止
            Msg result1 = agent.call(createUserMsg("test")).block(TEST_TIMEOUT);
            assertTrue(
                    result1.hasContentBlocks(ToolUseBlock.class),
                    "First call should return ToolUse message");

            // 直接提供工具结果
            Msg toolResultMsg =
                    Msg.builder()
                            .name("test-agent")
                            .role(MsgRole.TOOL)
                            .content(
                                    ToolResultBlock.of(
                                            "tool1",
                                            "test_tool",
                                            TextBlock.builder().text("manual result").build()))
                            .build();

            Msg result = agent.call(toolResultMsg).block(TEST_TIMEOUT);

            assertNotNull(result);
            assertTrue(
                    result.hasContentBlocks(TextBlock.class),
                    "Final result should have text content");
        }

        @Test
        @DisplayName("New message with pending tool calls auto-recovers")
        void testNewMsgWithPendingToolUseContinuesActing() {
            Msg toolUseMsg = createToolUseMsg("tool1", "test_tool", Map.of());
            Msg textResponse =
                    createAssistantTextMsg("Recovered after auto-generated error results");

            when(mockModel.stream(anyList(), anyList(), any()))
                    .thenReturn(createFluxFromMsg(toolUseMsg))
                    .thenReturn(createFluxFromMsg(textResponse));

            Hook stopHook = createPostReasoningStopHook();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .checkRunning(false)
                            .hook(stopHook)
                            .enablePendingToolRecovery(true)
                            .build();

            // 首次调用停止
            Msg result1 = agent.call(createUserMsg("test")).block(TEST_TIMEOUT);
            assertTrue(
                    result1.hasContentBlocks(ToolUseBlock.class),
                    "First call should return ToolUse message");

            // 发送新普通消息，应为待处理调用生成错误结果以自动恢复
            Msg newMsg = createUserMsg("new message");
            Msg result2 = agent.call(newMsg).block(TEST_TIMEOUT);

            assertNotNull(result2, "Agent should auto-recover and return a result");

            // 验证模型被第二次调用，进行后续推理
            verify(mockModel, times(2)).stream(anyList(), anyList(), any());

            // 验证跟进响应文本符合预期
            assertTrue(
                    result2.hasContentBlocks(TextBlock.class),
                    "Recovery result should contain text content");
            String resultText =
                    result2.getContentBlocks(TextBlock.class).stream()
                            .map(TextBlock::getText)
                            .findFirst()
                            .orElse("");
            assertEquals(
                    "Recovered after auto-generated error results",
                    resultText,
                    "Recovery result should match the model's follow-up response");

            // 验证待处理工具调用 ID 对应的错误 ToolResultBlock
            // 已写入上下文，证明挂起状态确实被清除
            List<Msg> memoryMsgs = agent.getAgentState().getContext();
            boolean hasErrorToolResult =
                    memoryMsgs.stream()
                            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                            .anyMatch(
                                    tr ->
                                            "tool1".equals(tr.getId())
                                                    && tr.getOutput().stream()
                                                            .anyMatch(
                                                                    cb ->
                                                                            cb instanceof TextBlock
                                                                                    && ((TextBlock)
                                                                                                    cb)
                                                                                            .getText()
                                                                                            .contains(
                                                                                                    "[ERROR]")));
            assertTrue(
                    hasErrorToolResult,
                    "Memory should contain an error ToolResultBlock for the pending tool call"
                            + " id='tool1'");
        }
    }

    // ==================== E. Hook 集成测试 ====================

    @Nested
    @DisplayName("E. Hook Integration Tests")
    class HookIntegrationTests {

        @Test
        @DisplayName("Hook can inspect reasoning message to decide stop")
        void testHookCanInspectReasoningMessage() {
            // 设置模型返回指定名称的工具调用
            Msg toolUseMsg = createToolUseMsg("tool1", "dangerous_tool", Map.of());
            setupModelToReturnToolUse(toolUseMsg);

            // 仅对危险工具停止的钩子
            Hook conditionalStopHook =
                    new Hook() {
                        @Override
                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                            if (event instanceof PostReasoningEvent e) {
                                Msg msg = e.getReasoningMessage();
                                boolean hasDangerousTool =
                                        msg.getContentBlocks(ToolUseBlock.class).stream()
                                                .anyMatch(t -> t.getName().contains("dangerous"));
                                if (hasDangerousTool) {
                                    e.stopAgent();
                                }
                            }
                            return Mono.just(event);
                        }
                    };

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .checkRunning(false)
                            .hook(conditionalStopHook)
                            .build();

            Msg result = agent.call(createUserMsg("test")).block(TEST_TIMEOUT);

            assertTrue(
                    result.hasContentBlocks(ToolUseBlock.class),
                    "Should return ToolUse message when dangerous tool detected");
        }

        @Test
        @DisplayName("Multiple hooks with one calling stopAgent")
        void testMultipleHooksWithStop() {
            Msg toolUseMsg = createToolUseMsg("tool1", "test_tool", Map.of());
            setupModelToReturnToolUse(toolUseMsg);

            AtomicInteger hook1Count = new AtomicInteger(0);
            AtomicInteger hook2Count = new AtomicInteger(0);

            Hook hook1 =
                    new Hook() {
                        @Override
                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                            if (event instanceof PostReasoningEvent) {
                                hook1Count.incrementAndGet();
                            }
                            return Mono.just(event);
                        }

                        @Override
                        public int priority() {
                            return 10;
                        }
                    };

            Hook hook2 =
                    new Hook() {
                        @Override
                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                            if (event instanceof PostReasoningEvent e) {
                                hook2Count.incrementAndGet();
                                e.stopAgent();
                            }
                            return Mono.just(event);
                        }

                        @Override
                        public int priority() {
                            return 20;
                        }
                    };

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .checkRunning(false)
                            .hook(hook1)
                            .hook(hook2)
                            .build();

            Msg result = agent.call(createUserMsg("test")).block(TEST_TIMEOUT);

            assertEquals(1, hook1Count.get());
            assertEquals(1, hook2Count.get());
            assertTrue(
                    result.hasContentBlocks(ToolUseBlock.class),
                    "Should return ToolUse message when hook2 calls stopAgent");
        }
    }

    // ==================== F. 部分工具结果测试 ====================

    @Nested
    @DisplayName("F. Partial Tool Results Tests")
    class PartialToolResultsTests {

        @Test
        @DisplayName("Partial tool results should NOT cause duplicate tool execution")
        void testPartialToolResultsExecution() {
            // 记录工具执行次数
            AtomicInteger tool1Count = new AtomicInteger(0);
            AtomicInteger tool2Count = new AtomicInteger(0);

            // 注册三个工具
            toolkit.registerTool(new CountingToolClass(tool1Count, tool2Count));

            // 模型先返回两个工具调用，再返回文本
            Msg threeToolsMsg =
                    createMultipleToolUseMsg(
                            List.of(Map.entry("call1", "tool1"), Map.entry("call2", "tool2")));
            Msg textResponse = createAssistantTextMsg("All tools completed!");

            when(mockModel.stream(anyList(), anyList(), any()))
                    .thenReturn(createFluxFromMsg(threeToolsMsg))
                    .thenReturn(createFluxFromMsg(textResponse));

            // 创建推理后停止的钩子
            Hook stopHook = createPostReasoningStopHook();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .checkRunning(false)
                            .hook(stopHook)
                            .build();

            // 首次调用停止，返回两个待处理工具调用
            Msg result1 = agent.call(createUserMsg("test")).block(TEST_TIMEOUT);
            assertTrue(
                    result1.hasContentBlocks(ToolUseBlock.class),
                    "First call should return ToolUse message");
            List<ToolUseBlock> pendingTools = result1.getContentBlocks(ToolUseBlock.class);
            assertEquals(2, pendingTools.size(), "Should have 2 pending tool calls");

            // 验证尚无工具执行
            assertEquals(0, tool1Count.get(), "Tool1 should not be executed yet");
            assertEquals(0, tool2Count.get(), "Tool2 should not be executed yet");

            // 用户仅提供 tool1 的部分结果
            Msg partialResultMsg =
                    Msg.builder()
                            .name("user")
                            .role(MsgRole.TOOL)
                            .content(
                                    List.of(
                                            ToolResultBlock.of(
                                                    "call1",
                                                    "tool1",
                                                    TextBlock.builder().text("canceled").build())))
                            .build();

            // 使用部分结果恢复
            Msg result2 = agent.call(partialResultMsg).block(TEST_TIMEOUT);

            // 回归背景：旧缺陷会重复执行两个工具
            // 预期行为：只应执行 tool2
            // 当前断言要求 tool1 不再执行，tool2 恰好执行一次
            assertTrue(
                    tool1Count.get() == 0 && tool2Count.get() == 1,
                    "BUG REPRODUCED: Tools with existing results were re-executed. "
                            + "tool1Count="
                            + tool1Count.get()
                            + ", tool2Count="
                            + tool2Count.get());
            assertTrue(
                    result1.hasContentBlocks(ToolUseBlock.class),
                    "First call should return ToolUse message");
        }
    }

    // ==================== G. 边界测试 ====================

    @Nested
    @DisplayName("G. Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("Stop on first iteration")
        void testStopOnFirstIteration() {
            Msg toolUseMsg = createToolUseMsg("tool1", "test_tool", Map.of());
            setupModelToReturnToolUse(toolUseMsg);

            Hook stopHook = createPostReasoningStopHook();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .checkRunning(false)
                            .hook(stopHook)
                            .build();

            Msg result = agent.call(createUserMsg("test")).block(TEST_TIMEOUT);

            assertNotNull(result);
            assertTrue(
                    result.hasContentBlocks(ToolUseBlock.class),
                    "Should return ToolUse message on first iteration stop");
            // 应只调用模型一次
            verify(mockModel, times(1)).stream(anyList(), anyList(), any());
        }

        @Test
        @DisplayName("Normal call after completion works")
        void testNormalCallAfterCompletion() {
            // 首次响应仅含文本
            Msg textResponse1 = createAssistantTextMsg("First response");
            Msg textResponse2 = createAssistantTextMsg("Second response");

            when(mockModel.stream(anyList(), anyList(), any()))
                    .thenReturn(createFluxFromMsg(textResponse1))
                    .thenReturn(createFluxFromMsg(textResponse2));

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .checkRunning(false)
                            .build();

            Msg result1 = agent.call(createUserMsg("First")).block(TEST_TIMEOUT);
            assertNotNull(result1);
            assertFalse(
                    result1.hasContentBlocks(ToolUseBlock.class),
                    "First result should not have ToolUse");

            Msg result2 = agent.call(createUserMsg("Second")).block(TEST_TIMEOUT);
            assertNotNull(result2);
            assertFalse(
                    result2.hasContentBlocks(ToolUseBlock.class),
                    "Second result should not have ToolUse");
        }

        @Test
        @DisplayName("Agent auto-recovers when adding regular message with pending tool calls")
        void testAgentHandlesPendingToolCallsGracefully() {
            Msg toolUseMsg = createToolUseMsg("tool1", "test_tool", Map.of());
            Msg textResponse = createAssistantTextMsg("Recovered");

            when(mockModel.stream(anyList(), anyList(), any()))
                    .thenReturn(createFluxFromMsg(toolUseMsg))
                    .thenReturn(createFluxFromMsg(textResponse));

            Hook stopHook = createPostReasoningStopHook();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .model(mockModel)
                            .toolkit(toolkit)
                            .checkRunning(false)
                            .hook(stopHook)
                            .enablePendingToolRecovery(true)
                            .build();

            agent.call(createUserMsg("test")).block(TEST_TIMEOUT);

            // 新设计为待处理工具生成错误结果，
            // 自动恢复并继续处理
            Msg result = agent.call(createUserMsg("new")).block(TEST_TIMEOUT);
            assertNotNull(result, "Agent should auto-recover and return a result");
        }
    }

    // ==================== 辅助方法 ====================

    private Msg createTextMsg(String text) {
        return Msg.builder()
                .name("test")
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private Msg createUserMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private Msg createAssistantTextMsg(String text) {
        return Msg.builder()
                .name("assistant")
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private Msg createToolUseMsg(String toolId, String toolName, Map<String, Object> input) {
        return Msg.builder()
                .name("assistant")
                .role(MsgRole.ASSISTANT)
                .content(
                        ToolUseBlock.builder()
                                .id(toolId)
                                .name(toolName)
                                .input(input)
                                .content(JsonUtils.getJsonCodec().toJson(input))
                                .build())
                .build();
    }

    private ToolUseBlock createToolUseBlock(String id, String name) {
        Map<String, Object> emptyInput = Map.of();
        return ToolUseBlock.builder()
                .id(id)
                .name(name)
                .input(emptyInput)
                .content(JsonUtils.getJsonCodec().toJson(emptyInput))
                .build();
    }

    private void setupModelToReturnToolUse(Msg toolUseMsg) {
        when(mockModel.stream(anyList(), anyList(), any()))
                .thenReturn(createFluxFromMsg(toolUseMsg));
    }

    private Flux<ChatResponse> createFluxFromMsg(Msg msg) {
        ChatResponse response =
                ChatResponse.builder().id("test-id").content(msg.getContent()).build();
        return Flux.just(response);
    }

    private Hook createPostReasoningStopHook() {
        return new Hook() {
            @Override
            public <T extends HookEvent> Mono<T> onEvent(T event) {
                if (event instanceof PostReasoningEvent e) {
                    e.stopAgent();
                }
                return Mono.just(event);
            }
        };
    }

    private Hook createPostActingStopHook() {
        return new Hook() {
            @Override
            public <T extends HookEvent> Mono<T> onEvent(T event) {
                if (event instanceof PostActingEvent e) {
                    e.stopAgent();
                }
                return Mono.just(event);
            }
        };
    }

    // ==================== 多工具辅助方法 ====================

    private Msg createMultipleToolUseMsg(List<Map.Entry<String, String>> toolIdNamePairs) {
        List<ContentBlock> toolUseBlocks = new ArrayList<>();
        for (Map.Entry<String, String> pair : toolIdNamePairs) {
            Map<String, Object> emptyInput = Map.of();
            toolUseBlocks.add(
                    ToolUseBlock.builder()
                            .id(pair.getKey())
                            .name(pair.getValue())
                            .input(emptyInput)
                            .content(JsonUtils.getJsonCodec().toJson(emptyInput))
                            .build());
        }
        return Msg.builder()
                .name("assistant")
                .role(MsgRole.ASSISTANT)
                .content(toolUseBlocks)
                .build();
    }

    // ==================== 测试工具类 ====================

    /**
     * 验证工具执行的简单测试工具类。
     */
    static class TestToolClass {
        private final AtomicBoolean executed;

        TestToolClass(AtomicBoolean executed) {
            this.executed = executed;
        }

        @Tool(name = "test_tool", description = "A test tool")
        public ToolResultBlock testTool() {
            executed.set(true);
            return ToolResultBlock.text("Tool executed");
        }
    }

    /**
     * 统计执行次数，用于测试重复调用的工具。
     */
    static class CountingToolClass {
        private final AtomicInteger executionCount1;
        private final AtomicInteger executionCount2;

        CountingToolClass(AtomicInteger executionCount1, AtomicInteger executionCount2) {
            this.executionCount1 = executionCount1;
            this.executionCount2 = executionCount2;
        }

        @Tool(name = "tool1", description = "Counting tool 1")
        public ToolResultBlock tool1() {
            executionCount1.incrementAndGet();
            return ToolResultBlock.text("Tool1 executed: " + executionCount1.get());
        }

        @Tool(name = "tool2", description = "Counting tool 2")
        public ToolResultBlock tool2() {
            executionCount2.incrementAndGet();
            return ToolResultBlock.text("Tool2 executed: " + executionCount2.get());
        }
    }
}
