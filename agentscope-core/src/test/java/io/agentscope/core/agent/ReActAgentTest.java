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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.config.FailoverListener;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.MockToolkit;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * ReActAgent 单元测试，覆盖初始化、简单文本回复、推理／行动循环、工具调用、记忆、流式输出、错误处理及最大迭代限制。
 */
@DisplayName("ReActAgent Tests")
class ReActAgentTest {

    private ReActAgent agent;
    private MockModel mockModel;
    private MockToolkit mockToolkit;
    private InMemoryMemory memory;

    @BeforeEach
    void setUp() {
        memory = new InMemoryMemory();
        mockModel = new MockModel(TestConstants.MOCK_MODEL_SIMPLE_RESPONSE);
        mockToolkit = new MockToolkit();

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .build();
    }

    @Test
    @DisplayName("Should initialize ReActAgent with correct properties")
    void testInitialization() {
        // 验证智能体属性
        assertNotNull(agent.getAgentId(), "Agent ID should not be null");
        assertEquals(
                TestConstants.TEST_REACT_AGENT_NAME, agent.getName(), "Agent name should match");
        assertNotNull(agent.getAgentState(), "AgentState should not be null");
        assertEquals(
                TestConstants.DEFAULT_MAX_ITERS,
                agent.getMaxIters(),
                "Default max iterations should be 10");

        // 验证初始记忆为空
        assertTrue(
                agent.getAgentState().getContext().isEmpty(), "Memory should be empty initially");
    }

    @Test
    @DisplayName(
            "Explicit agent id is trimmed and used, blank falls back to UUID, invalid rejected")
    void testAgentIdOverride() {
        ReActAgent explicit =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .agentId("custom-agent-id")
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .build();
        assertEquals("custom-agent-id", explicit.getAgentId(), "Explicit agent id should be used");

        ReActAgent padded =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .agentId("  padded-agent-id  ")
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .build();
        assertEquals(
                "padded-agent-id",
                padded.getAgentId(),
                "Whitespace-padded agent id should be trimmed");

        ReActAgent blank =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .agentId("   ")
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .build();
        assertNotEquals("   ", blank.getAgentId(), "Blank agent id should not be used as-is");
        assertDoesNotThrow(
                () -> UUID.fromString(blank.getAgentId()),
                "Blank agent id should fall back to a generated UUID");

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ReActAgent.builder()
                                .name(TestConstants.TEST_REACT_AGENT_NAME)
                                .agentId("../other-agent")
                                .model(mockModel)
                                .toolkit(mockToolkit)
                                .build(),
                "Agent id with path traversal characters should be rejected");
    }

    @Test
    @DisplayName("Should generate simple text response")
    void testSimpleReply() {
        // 创建用户消息
        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);

        // 获取响应
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // 验证响应
        assertNotNull(response, "Response should not be null");

        assertEquals(
                TestConstants.TEST_REACT_AGENT_NAME,
                response.getName(),
                "Response should be from the agent");

        String text = TestUtils.extractTextContent(response);
        assertNotNull(text, "Response text should not be null");
        assertFalse(text.isEmpty(), "Response text should not be empty");

        // 验证记忆已更新
        List<Msg> messages = agent.getAgentState().getContext();
        assertTrue(messages.size() >= 1, "Memory should contain at least the user message");

        // 验证模型被调用
        assertEquals(1, mockModel.getCallCount(), "Model should be called once");
    }

    @Test
    @DisplayName("Should handle thinking and final response")
    void testThinkingResponse() {
        // 设置带思考内容的模型
        mockModel =
                MockModel.withThinking(
                        TestConstants.MOCK_MODEL_THINKING_RESPONSE,
                        TestConstants.MOCK_MODEL_FINAL_RESPONSE);

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .build();

        // 创建用户消息
        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);

        // 获取响应
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // 验证收到响应
        assertNotNull(response, "Response should not be null");

        // 验证响应包含思考或文本
        boolean hasContent =
                TestUtils.isThinkingMessage(response) || TestUtils.isTextMessage(response);
        assertTrue(hasContent, "Should have thinking or text content");
    }

    @Test
    @DisplayName("Should call tools when requested by model")
    void testToolCalling() {
        // 使用可变引用跟踪调用次数
        final int[] callCount = {0};

        // 模型先调用工具，再结束
        MockModel toolModel =
                new MockModel(
                        messages -> {
                            int currentCall = callCount[0]++;
                            if (currentCall == 0) {
                                // 首次返回工具调用
                                return List.of(
                                        createToolCallResponseHelper(
                                                TestConstants.TEST_TOOL_NAME,
                                                "tool_call_123",
                                                TestUtils.createToolArguments("param1", "value1")));
                            }
                            // 第二次返回文本并结束
                            return List.of(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text(
                                                                            "Tool executed"
                                                                                + " successfully")
                                                                    .build()))
                                            .usage(new ChatUsage(10, 20, 30))
                                            .build());
                        });

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(toolModel)
                        .toolkit(mockToolkit)
                        .build();

        // 创建用户消息
        Msg userMsg = TestUtils.createUserMessage("User", "Please use the test tool");

        // 获取响应
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // 验证响应
        assertNotNull(response, "Response should not be null");

        // 验证通过工具包调用工具
        assertTrue(
                mockToolkit.wasToolCalled(TestConstants.TEST_TOOL_NAME), "Tool should be called");
        assertEquals(1, mockToolkit.getCallCount(), "Tool should be called once");

        // 验证记忆包含工具结果
        List<Msg> messages = agent.getAgentState().getContext();
        boolean hasToolResult =
                messages.stream().anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class));
        assertTrue(hasToolResult, "Memory should contain tool result");
    }

    @Test
    @DisplayName("Should execute multiple tools in acting phase")
    void testMultipleToolExecution() {
        // 使用可变引用跟踪调用次数
        final int[] callCount = {0};

        // 模型按顺序调用两个工具
        MockModel toolModel =
                new MockModel(
                        messages -> {
                            int callNum = callCount[0]++;
                            if (callNum == 0) {
                                // 首次推理调用计算器
                                Map<String, Object> calcArgs = new HashMap<>();
                                calcArgs.put("operation", "add");
                                calcArgs.put("a", 5);
                                calcArgs.put("b", 3);
                                return List.of(
                                        createToolCallResponseHelper(
                                                TestConstants.CALCULATOR_TOOL_NAME,
                                                "tool_call_1",
                                                calcArgs));
                            } else if (callNum == 1) {
                                // 第二次推理调用测试工具
                                return List.of(
                                        createToolCallResponseHelper(
                                                TestConstants.TEST_TOOL_NAME,
                                                "tool_call_2",
                                                TestUtils.createToolArguments()));
                            } else {
                                // 最终响应
                                return List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("All tools executed")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            }
                        });

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(toolModel)
                        .toolkit(mockToolkit)
                        .build();

        Msg userMsg = TestUtils.createUserMessage("User", "Execute tools");
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Response should not be null");

        // 验证两个工具均被调用
        assertTrue(
                mockToolkit.wasToolCalled(TestConstants.CALCULATOR_TOOL_NAME),
                "Calculator tool should be called");
        assertTrue(
                mockToolkit.wasToolCalled(TestConstants.TEST_TOOL_NAME),
                "Test tool should be called");
        assertEquals(2, mockToolkit.getCallCount(), "Two tools should be called");

        // 验证工具调用顺序
        List<String> history = mockToolkit.getToolCallHistory();
        assertEquals(
                TestConstants.CALCULATOR_TOOL_NAME,
                history.get(0),
                "Calculator should be called first");
        assertEquals(
                TestConstants.TEST_TOOL_NAME, history.get(1), "Test tool should be called second");
    }

    @Test
    @DisplayName("Should handle tool execution errors in acting phase")
    void testToolExecutionError() {
        // 注册会抛错的工具
        mockToolkit.withErrorTool("failing_tool", "Tool execution failed");

        final int[] callCount = {0};
        mockModel =
                new MockModel(
                        messages -> {
                            int currentCall = callCount[0]++;
                            if (currentCall == 0) {
                                // 调用失败工具
                                return List.of(
                                        createToolCallResponseHelper(
                                                "failing_tool",
                                                "tool_call_error",
                                                TestUtils.createToolArguments()));
                            } else {
                                // 模型应处理错误并提供响应
                                return List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Handled tool error")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            }
                        });

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .build();

        Msg userMsg = TestUtils.createUserMessage("User", "Call failing tool");
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Response should not be null");

        // 验证尝试过执行工具
        assertTrue(mockToolkit.wasToolCalled("failing_tool"), "Failing tool should be called");

        // 验证记忆包含错误工具结果
        List<Msg> messages = agent.getAgentState().getContext();
        boolean hasErrorToolResult =
                messages.stream()
                        .anyMatch(
                                m -> {
                                    ToolResultBlock trb =
                                            m.getFirstContentBlock(ToolResultBlock.class);
                                    if (trb != null && !trb.getOutput().isEmpty()) {
                                        // 检查输出是否含错误文本
                                        if (trb.getOutput().get(0) instanceof TextBlock tb) {
                                            return tb.getText().contains("Error:");
                                        }
                                    }
                                    return false;
                                });
        assertTrue(hasErrorToolResult, "Memory should contain error tool result");
    }

    @Test
    @DisplayName("Should save tool results to memory during acting phase")
    void testToolResultsInMemory() {
        final int[] callCount = {0};
        mockModel =
                new MockModel(
                        messages -> {
                            int currentCall = callCount[0]++;
                            if (currentCall == 0) {
                                Map<String, Object> calcArgs = new HashMap<>();
                                calcArgs.put("operation", "multiply");
                                calcArgs.put("a", 4);
                                calcArgs.put("b", 7);
                                return List.of(
                                        createToolCallResponseHelper(
                                                TestConstants.CALCULATOR_TOOL_NAME,
                                                "calc_123",
                                                calcArgs));
                            } else {
                                return List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Result is 28")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            }
                        });

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .build();

        int initialMemorySize = agent.getAgentState().getContext().size();

        Msg userMsg = TestUtils.createUserMessage("User", "Calculate 4 * 7");
        agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        List<Msg> messages = agent.getAgentState().getContext();

        // 记忆应包含用户消息、助手工具调用、工具结果、最终响应
        assertTrue(
                messages.size() > initialMemorySize + 1, "Memory should contain multiple messages");

        // 查找工具结果消息
        Msg toolResultMsg =
                messages.stream()
                        .filter(m -> m.hasContentBlocks(ToolResultBlock.class))
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolResultMsg, "Tool result message should be in memory");

        ToolResultBlock toolResult = toolResultMsg.getFirstContentBlock(ToolResultBlock.class);
        assertEquals("calc_123", toolResult.getId(), "Tool call ID should match");
        // 通过输出不含错误验证工具成功
        for (ContentBlock output : toolResult.getOutput()) {
            if (output instanceof TextBlock tb) {
                assertFalse(tb.getText().startsWith("Error:"), "Tool should execute successfully");
            }
        }
    }

    @Test
    @DisplayName("Should handle max iterations limit")
    void testMaxIterations() {
        // 模型始终返回工具调用，模拟无限循环
        MockModel loopModel =
                new MockModel(
                        messages -> {
                            return List.of(
                                    createToolCallResponseHelper(
                                            TestConstants.TEST_TOOL_NAME,
                                            "tool_call_" + System.nanoTime(),
                                            TestUtils.createToolArguments()));
                        });

        // 通过 builder 设置较低迭代上限
        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(loopModel)
                        .toolkit(mockToolkit)
                        .maxIters(TestConstants.TEST_MAX_ITERS)
                        .build();
        mockModel = loopModel;

        // 创建用户消息
        Msg userMsg = TestUtils.createUserMessage("User", "Start the loop");

        // 带超时获取响应
        // 验证在合理时间内完成，而非无限循环
        Msg response =
                agent.call(userMsg)
                        .timeout(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS))
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // 验证生成原因反映最大迭代限制
        assertEquals(GenerateReason.MAX_ITERATIONS, response.getGenerateReason());

        // 验证遵守最大迭代限制
        assertTrue(
                mockModel.getCallCount() <= TestConstants.TEST_MAX_ITERS + 1,
                "Model calls should not exceed max iterations");
    }

    @Test
    @DisplayName("Should maintain conversation history in memory")
    void testMemoryManagement() {
        // 发送第一条消息
        Msg msg1 = TestUtils.createUserMessage("User", "First message");
        agent.call(msg1).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        int sizeAfterFirst = agent.getAgentState().getContext().size();
        assertTrue(sizeAfterFirst >= 1, "Memory should contain at least the first message");

        // 发送第二条消息
        Msg msg2 = TestUtils.createUserMessage("User", "Second message");
        agent.call(msg2).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        int sizeAfterSecond = agent.getAgentState().getContext().size();
        assertTrue(sizeAfterSecond > sizeAfterFirst, "Memory should grow with more messages");

        // 验证两条消息都在历史中
        List<Msg> allMessages = agent.getAgentState().getContext();
        assertTrue(
                allMessages.stream()
                        .anyMatch(m -> TestUtils.extractTextContent(m).contains("First message")),
                "First message should be in memory");
        assertTrue(
                allMessages.stream()
                        .anyMatch(m -> TestUtils.extractTextContent(m).contains("Second message")),
                "Second message should be in memory");
    }

    @Test
    @DisplayName("Should handle model errors gracefully")
    void testErrorHandling() {
        // 设置模型抛错
        String errorMessage = "Mock model error";
        mockModel = new MockModel("").withError(errorMessage);

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .build();

        // 创建用户消息
        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);

        // 获取响应
        // 验证错误传播
        try {
            agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));
            fail("Should have thrown an exception");
        } catch (Exception e) {
            assertTrue(
                    e.getMessage().contains(errorMessage)
                            || (e.getCause() != null
                                    && e.getCause().getMessage().contains(errorMessage)),
                    "Exception should contain error message");
        }
    }

    @Test
    @DisplayName("Should switch to fallback model when primary fails")
    void testFallbackModel() {
        String errorMessage = "Primary model unavailable";
        MockModel primaryModel = new MockModel("").withError(errorMessage);
        MockModel fallbackModel = new MockModel("Fallback response");

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(primaryModel)
                        .fallbackModel(fallbackModel)
                        .toolkit(mockToolkit)
                        .build();

        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Response should not be null");
        assertEquals("Fallback response", TestUtils.extractTextContent(response));
        assertEquals(1, primaryModel.getCallCount(), "Primary model should be tried once");
        assertEquals(1, fallbackModel.getCallCount(), "Fallback model should be called once");
    }

    @Test
    @DisplayName("Should notify failover listener with primary model and original error on switch")
    void testFailoverListenerNotifiedOnSwitch() {
        String errorMessage = "Primary model unavailable";
        MockModel primaryModel = new MockModel("").withError(errorMessage);
        MockModel fallbackModel = new MockModel("Fallback response");

        List<Model> capturedPrimaries = new ArrayList<>();
        List<Throwable> capturedErrors = new ArrayList<>();
        FailoverListener listener =
                (primary, error) -> {
                    capturedPrimaries.add(primary);
                    capturedErrors.add(error);
                };

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(primaryModel)
                        .fallbackModel(fallbackModel)
                        .failoverListener(listener)
                        .toolkit(mockToolkit)
                        .build();

        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);

        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Response should not be null");
        assertEquals("Fallback response", TestUtils.extractTextContent(response));
        assertEquals(1, capturedErrors.size(), "Listener should be notified exactly once");
        assertSame(
                primaryModel,
                capturedPrimaries.get(0),
                "Listener should receive the primary model instance");
        assertEquals(
                errorMessage,
                capturedErrors.get(0).getMessage(),
                "Listener should receive the original error");
    }

    @Test
    @DisplayName("Should keep fallback switching working when failover listener throws")
    void testFailoverListenerThrowingDoesNotAffectSwitch() {
        MockModel primaryModel = new MockModel("").withError("Primary model unavailable");
        MockModel fallbackModel = new MockModel("Fallback response");

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(primaryModel)
                        .fallbackModel(fallbackModel)
                        .failoverListener(
                                (primary, error) -> {
                                    throw new IllegalStateException("listener failure");
                                })
                        .toolkit(mockToolkit)
                        .build();

        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);

        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Response should not be null");
        assertEquals(
                "Fallback response",
                TestUtils.extractTextContent(response),
                "Fallback should still serve the call despite the listener throwing");
    }

    @Test
    @DisplayName("Should not notify failover listener when primary model succeeds")
    void testFailoverListenerNotCalledWhenPrimarySucceeds() {
        MockModel primaryModel = new MockModel("Primary response");

        List<Throwable> capturedErrors = new ArrayList<>();
        FailoverListener listener = (primary, error) -> capturedErrors.add(error);

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(primaryModel)
                        .fallbackModel(new MockModel("Fallback response"))
                        .failoverListener(listener)
                        .toolkit(mockToolkit)
                        .build();

        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);

        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Response should not be null");
        assertEquals("Primary response", TestUtils.extractTextContent(response));
        assertTrue(
                capturedErrors.isEmpty(), "Listener should not be notified when primary succeeds");
    }

    @Test
    @DisplayName("Should not notify failover listener when no fallback is configured")
    void testFailoverListenerNotCalledWithoutFallback() {
        MockModel primaryModel = new MockModel("").withError("Primary model unavailable");

        List<Throwable> capturedErrors = new ArrayList<>();
        FailoverListener listener = (primary, error) -> capturedErrors.add(error);

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(primaryModel)
                        .failoverListener(listener)
                        .toolkit(mockToolkit)
                        .build();

        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);

        try {
            agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));
            fail("Should have thrown an exception");
        } catch (Exception e) {
            assertTrue(
                    e.getMessage().contains("Primary model unavailable")
                            || (e.getCause() != null
                                    && e.getCause()
                                            .getMessage()
                                            .contains("Primary model unavailable")),
                    "Error should propagate when no fallback is configured");
        }
        assertTrue(capturedErrors.isEmpty(), "No switch happens, so no notification");
    }

    @Test
    @DisplayName("Should support streaming responses")
    void testStreaming() {
        // 设置模型返回多个响应块
        mockModel = new MockModel(List.of("First chunk", "Second chunk", "Third chunk"));

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .build();

        // 创建用户消息
        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);

        // 获取响应
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Response should not be null");
    }

    @Test
    @DisplayName("Should continue generation based on current memory without new input")
    void testContinueGeneration() {
        // 先在记忆中加入消息
        Msg userMsg = TestUtils.createUserMessage("User", "Tell me a story");
        agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        int initialCallCount = mockModel.getCallCount();
        int initialMemorySize = agent.getAgentState().getContext().size();

        // 无参数调用以继续生成
        Msg continueResponse =
                agent.call().block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // 验证响应
        assertNotNull(continueResponse, "Continue response should not be null");
        assertEquals(
                TestConstants.TEST_REACT_AGENT_NAME,
                continueResponse.getName(),
                "Response should be from the agent");

        // 验证模型再次被调用
        assertTrue(
                mockModel.getCallCount() > initialCallCount,
                "Model should be called again for continuation");

        // 验证记忆增加新响应，但未增加用户消息
        assertTrue(
                agent.getAgentState().getContext().size() > initialMemorySize,
                "Memory should contain the continuation response");

        // 验证仅新增智能体响应，没有新增用户消息
        List<Msg> messages = agent.getAgentState().getContext();
        long userMessageCount = messages.stream().filter(m -> m.getRole() == MsgRole.USER).count();
        assertEquals(
                1,
                userMessageCount,
                "Should still have only 1 user message (continuation doesn't add user input)");
    }

    @Test
    @DisplayName("Should return correct values from getter methods")
    void testGetters() {
        // 验证所有 getter 返回预期值
        assertNotNull(agent.getSysPrompt(), "System prompt should not be null");
        assertEquals(TestConstants.DEFAULT_SYS_PROMPT, agent.getSysPrompt());

        assertNotNull(agent.getModel(), "Model should not be null");
        assertEquals(mockModel, agent.getModel());

        assertNotNull(agent.getToolkit(), "Toolkit should not be null");
        // 智能体复制工具包以隔离状态，因此只验证 Toolkit 类型
        assertInstanceOf(Toolkit.class, agent.getToolkit());

        assertEquals(TestConstants.DEFAULT_MAX_ITERS, agent.getMaxIters());
    }

    @Test
    @DisplayName("Should have interrupt API methods")
    void testInterruptAfterToolCompletion() {
        // 空闲会话中断不得污染下一次执行。
        agent.interrupt();
        Msg reply = agent.call(TestUtils.createUserMessage("User", "hello")).block();
        assertNotNull(reply);
        assertNotEquals(
                io.agentscope.core.message.GenerateReason.INTERRUPTED, reply.getGenerateReason());
    }

    @Test
    @DisplayName("Should support interrupt with message")
    void testInterruptRecoveryMessage() {
        Msg interruptMsg = TestUtils.createUserMessage("User", "Stop processing");

        agent.interrupt(interruptMsg);
        Msg reply = agent.call(TestUtils.createUserMessage("User", "hello")).block();
        assertNotNull(reply);
        assertNotEquals(
                io.agentscope.core.message.GenerateReason.INTERRUPTED, reply.getGenerateReason());
    }

    @Test
    @DisplayName("Should not generate fake tool results after simplification")
    void testNoFakeToolResults() {
        // 测试验证 PR#15 后 ToolResultBlock.interrupted() 已删除，
        // ReActAgent 不再生成伪造中断结果。
        // 通过检查该方法不存在来验证。

        // 验证 ToolResultBlock 没有 interrupted() 方法
        boolean hasInterruptedMethod =
                Arrays.stream(ToolResultBlock.class.getMethods())
                        .anyMatch(
                                m ->
                                        m.getName().equals("interrupted")
                                                && m.getParameterCount() == 0);

        assertFalse(
                hasInterruptedMethod,
                "ToolResultBlock should not have interrupted() method after simplification");
    }

    @Test
    @DisplayName("Should use normal tool results not fake ones")
    void testAllToolResultsSaved() {
        // 验证使用真实工具包而非注入伪造结果
        // 此处通过确认智能体配置了工具包来检查

        assertNotNull(agent.getToolkit(), "Agent should have a toolkit configured");

        // 验证工具包可调用，作为基本健全性检查
        assertNotNull(
                agent.getToolkit().getTool(TestConstants.CALCULATOR_TOOL_NAME),
                "Toolkit should have calculator tool");
        assertNotNull(
                agent.getToolkit().getTool(TestConstants.TEST_TOOL_NAME),
                "Toolkit should have test tool");
    }

    @Test
    @DisplayName("Should emit ReasoningChunkEvent for ToolUseBlock during streaming")
    void testStreamingToolUseChunkEvent() {
        // 跟踪收到的 ToolUseBlock 事件
        final List<ToolUseBlock> receivedToolUseBlocks = new CopyOnWriteArrayList<>();
        final List<ToolUseBlock> accumulatedToolUseBlocks = new CopyOnWriteArrayList<>();

        // 创建钩子，捕获带 ToolUseBlock 的 ReasoningChunkEvent
        Hook captureHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof ReasoningChunkEvent chunkEvent) {
                            Msg chunk = chunkEvent.getIncrementalChunk();
                            if (chunk.hasContentBlocks(ToolUseBlock.class)) {
                                ToolUseBlock tub = chunk.getFirstContentBlock(ToolUseBlock.class);
                                receivedToolUseBlocks.add(tub);

                                // 同时捕获累计内容
                                Msg accumulated = chunkEvent.getAccumulated();
                                if (accumulated.hasContentBlocks(ToolUseBlock.class)) {
                                    accumulatedToolUseBlocks.add(
                                            accumulated.getFirstContentBlock(ToolUseBlock.class));
                                }
                            }
                        }
                        return Mono.just(event);
                    }
                };

        // 使用可变引用跟踪调用次数
        final int[] callCount = {0};

        // 模型先返回流式工具调用块，再结束
        MockModel toolModel =
                new MockModel(
                        messages -> {
                            int currentCall = callCount[0]++;
                            if (currentCall == 0) {
                                // 返回带内容的工具调用，模拟流式输出
                                return List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("call_stream_1")
                                                                        .name("weather")
                                                                        .content(
                                                                                "{\"city\":\"Beijing\"}")
                                                                        .input(
                                                                                Map.of(
                                                                                        "city",
                                                                                        "Beijing"))
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            }
                            // 第二次返回文本并结束
                            return List.of(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("Weather retrieved")
                                                                    .build()))
                                            .usage(new ChatUsage(10, 20, 30))
                                            .build());
                        });

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(toolModel)
                        .toolkit(mockToolkit)
                        .hook(captureHook)
                        .build();

        // 创建用户消息
        Msg userMsg = TestUtils.createUserMessage("User", "What's the weather in Beijing?");

        // 获取响应
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // 验证响应
        assertNotNull(response, "Response should not be null");

        // 验证收到 ToolUseBlock 事件
        assertFalse(receivedToolUseBlocks.isEmpty(), "Should receive ToolUseBlock chunk events");

        // 验证工具调用块数据正确
        ToolUseBlock receivedTub = receivedToolUseBlocks.get(0);
        assertEquals("call_stream_1", receivedTub.getId(), "Tool call ID should match");
        assertEquals("weather", receivedTub.getName(), "Tool name should match");

        // 验证累计数据
        assertFalse(accumulatedToolUseBlocks.isEmpty(), "Should have accumulated ToolUseBlock");
        ToolUseBlock accumulatedTub = accumulatedToolUseBlocks.get(0);
        assertEquals(
                "call_stream_1", accumulatedTub.getId(), "Accumulated tool call ID should match");
    }

    @Test
    @DisplayName("Should emit ReasoningChunkEvent for multiple parallel tool calls")
    void testStreamingMultipleToolCallsChunkEvents() {
        // 按 ID 跟踪收到的 ToolUseBlock 事件
        final Map<String, List<ToolUseBlock>> receivedByToolId = new ConcurrentHashMap<>();

        // 创建钩子，捕获带 ToolUseBlock 的 ReasoningChunkEvent
        Hook captureHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof ReasoningChunkEvent chunkEvent) {
                            Msg accumulated = chunkEvent.getAccumulated();
                            if (accumulated.hasContentBlocks(ToolUseBlock.class)) {
                                ToolUseBlock tub =
                                        accumulated.getFirstContentBlock(ToolUseBlock.class);
                                receivedByToolId
                                        .computeIfAbsent(
                                                tub.getId(),
                                                k ->
                                                        new java.util.concurrent
                                                                .CopyOnWriteArrayList<>())
                                        .add(tub);
                            }
                        }
                        return Mono.just(event);
                    }
                };

        // 使用可变引用跟踪调用次数
        final int[] callCount = {0};

        // 模型返回多个工具调用，再结束
        MockModel toolModel =
                new MockModel(
                        messages -> {
                            int currentCall = callCount[0]++;
                            if (currentCall == 0) {
                                // 返回两个并行工具调用
                                return List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("call_1")
                                                                        .name("weather")
                                                                        .content(
                                                                                "{\"city\":\"Beijing\"}")
                                                                        .input(
                                                                                Map.of(
                                                                                        "city",
                                                                                        "Beijing"))
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build(),
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("call_2")
                                                                        .name(
                                                                                TestConstants
                                                                                        .CALCULATOR_TOOL_NAME)
                                                                        .content(
                                                                                "{\"a\":1,\"b\":2}")
                                                                        .input(
                                                                                Map.of(
                                                                                        "a",
                                                                                        1,
                                                                                        "b",
                                                                                        2,
                                                                                        "operation",
                                                                                        "add"))
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            }
                            // 第二次返回文本并结束
                            return List.of(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("All tools executed")
                                                                    .build()))
                                            .usage(new ChatUsage(10, 20, 30))
                                            .build());
                        });

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(toolModel)
                        .toolkit(mockToolkit)
                        .hook(captureHook)
                        .build();

        // 创建用户消息
        Msg userMsg = TestUtils.createUserMessage("User", "Get weather and calculate");

        // 获取响应
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // 验证响应
        assertNotNull(response, "Response should not be null");

        // 验证两个工具调用均收到事件
        assertTrue(receivedByToolId.containsKey("call_1"), "Should receive events for call_1");
        assertTrue(receivedByToolId.containsKey("call_2"), "Should receive events for call_2");

        // 验证工具名称
        ToolUseBlock call1 = receivedByToolId.get("call_1").get(0);
        assertEquals("weather", call1.getName(), "First tool should be weather");

        ToolUseBlock call2 = receivedByToolId.get("call_2").get(0);
        assertEquals(
                TestConstants.CALCULATOR_TOOL_NAME,
                call2.getName(),
                "Second tool should be calculator");
    }

    @Test
    @DisplayName("Should include ChatUsage in accumulated message metadata when available")
    void testChatUsageInAccumulatedMessageMetadata() {
        // 跟踪 ReasoningChunkEvent 收到的 ChatUsage
        final java.util.List<ChatUsage> capturedChatUsages =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.List<Msg> capturedAccumulatedMsgs =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        // 创建钩子捕获事件并检查元数据
        Hook captureHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> reactor.core.publisher.Mono<T> onEvent(T event) {
                        if (event instanceof ReasoningChunkEvent chunkEvent) {
                            // 捕获累计消息并检查元数据
                            Msg accumulated = chunkEvent.getAccumulated();
                            if (accumulated != null) {
                                capturedAccumulatedMsgs.add(accumulated);

                                // 从元数据捕获 ChatUsage
                                Object usage =
                                        accumulated
                                                .getMetadata()
                                                .get(
                                                        io.agentscope.core.message
                                                                .MessageMetadataKeys.CHAT_USAGE);
                                if (usage instanceof ChatUsage) {
                                    capturedChatUsages.add((ChatUsage) usage);
                                }
                            }
                        }
                        return reactor.core.publisher.Mono.just(event);
                    }
                };

        // 设置模型返回带 ChatUsage 的响应
        MockModel modelWithUsage =
                new MockModel(
                        messages -> {
                            return List.of(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("Test response")
                                                                    .build()))
                                            .usage(new ChatUsage(100, 50, 1.5))
                                            .build());
                        });

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(modelWithUsage)
                        .toolkit(mockToolkit)
                        .hook(captureHook)
                        .build();

        // 创建用户消息
        Msg userMsg = TestUtils.createUserMessage("User", "Test message");

        // 获取响应
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // 验证响应
        assertNotNull(response, "Response should not be null");

        // 验证事件捕获到 ChatUsage
        assertFalse(capturedChatUsages.isEmpty(), "Should capture ChatUsage from events");

        // 验证 ChatUsage 数值
        ChatUsage capturedUsage = capturedChatUsages.get(0);
        assertEquals(100, capturedUsage.getInputTokens(), "Input tokens should match");
        assertEquals(50, capturedUsage.getOutputTokens(), "Output tokens should match");
        assertEquals(1.5, capturedUsage.getTime(), "Time should match");

        // 验证捕获到累计消息
        assertFalse(
                capturedAccumulatedMsgs.isEmpty(),
                "Should capture accumulated messages from events");

        // 验证元数据包含 CHAT_USAGE
        Msg accumulatedMsg = capturedAccumulatedMsgs.get(0);
        Object metadataUsage =
                accumulatedMsg
                        .getMetadata()
                        .get(io.agentscope.core.message.MessageMetadataKeys.CHAT_USAGE);
        assertNotNull(metadataUsage, "Accumulated message metadata should contain CHAT_USAGE");
        assertTrue(
                metadataUsage instanceof ChatUsage,
                "Metadata CHAT_USAGE should be ChatUsage instance");

        ChatUsage metadataChatUsage = (ChatUsage) metadataUsage;
        assertEquals(100, metadataChatUsage.getInputTokens(), "Metadata input tokens should match");
        assertEquals(
                50, metadataChatUsage.getOutputTokens(), "Metadata output tokens should match");
        assertEquals(1.5, metadataChatUsage.getTime(), "Metadata time should match");
    }

    // 创建工具调用响应的辅助方法
    private static ChatResponse createToolCallResponseHelper(
            String toolName, String toolCallId, Map<String, Object> arguments) {
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .name(toolName)
                                        .id(toolCallId)
                                        .input(arguments)
                                        .content(JsonUtils.getJsonCodec().toJson(arguments))
                                        .build()))
                .usage(new ChatUsage(8, 15, 23))
                .build();
    }
}
