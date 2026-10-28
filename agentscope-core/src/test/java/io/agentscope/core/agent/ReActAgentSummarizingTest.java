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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.MockToolkit;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostSummaryEvent;
import io.agentscope.core.hook.PreSummaryEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * ReActAgent 摘要功能单元测试：达到迭代上限时摘要、包含历史上下文、摘要加入记忆、正确处理提示消息。
 */
@DisplayName("ReActAgent Summarizing Tests")
class ReActAgentSummarizingTest {

    @Test
    @DisplayName("Should generate summary when max iterations reached")
    void testSummarizingOnMaxIterations() {

        // 模型返回普通文本，不调用 generate_response 工具
        // 这使结构化任务继续迭代而非结束
        final int[] callCount = {0};
        MockModel mockModel =
                new MockModel(
                        messages -> {
                            int callNum = callCount[0]++;
                            if (callNum < 2) {
                                // 前两次返回普通文本，不调用完成工具
                                // 不满足 isFinished()，强制继续迭代
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_" + callNum)
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "I'm thinking about"
                                                                                    + " your"
                                                                                    + " request...")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            } else {
                                // 第三次摘要调用返回最终文本
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_summary")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "I attempted to"
                                                                                    + " process"
                                                                                    + " your"
                                                                                    + " request but"
                                                                                    + " reached the"
                                                                                    + " maximum"
                                                                                    + " iteration"
                                                                                    + " limit.")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            }
                        });

        MockToolkit mockToolkit = new MockToolkit();

        // 创建 maxIters=2 的智能体以快速测试
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt("You are a test assistant.")
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .maxIters(2) // 设置较小上限，快速达到限制
                        .build();

        // 创建用户消息
        Msg userMsg = TestUtils.createUserMessage("User", "Please help me with a task");

        // 调用智能体，应达到 maxIters 并触发摘要
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // 验证响应不为 null
        assertNotNull(response, "Response should not be null");

        // 验证响应来自助手
        assertEquals(MsgRole.ASSISTANT, response.getRole(), "Response should be from assistant");

        // 验证响应包含摘要内容
        assertNotNull(response.getContent(), "Response should have content");
        assertNotNull(response.getFirstContentBlock(), "Response should have a content block");

        // 验证为文本块
        assertTrue(
                response.getFirstContentBlock() instanceof TextBlock,
                "Response should contain TextBlock");

        TextBlock textBlock = (TextBlock) response.getFirstContentBlock();
        String summaryText = textBlock.getText();

        // 验证摘要非空，且不只是错误信息
        assertNotNull(summaryText, "Summary text should not be null");
        assertTrue(summaryText.length() > 10, "Summary should be substantial");

        // 验证记忆包含摘要
        List<Msg> memoryMessages = agent.getAgentState().getContext();
        assertTrue(memoryMessages.contains(response), "Memory should contain summary message");
    }

    @Test
    @DisplayName("Should include context from memory in summary")
    void testSummarizingIncludesMemoryContext() {

        // 预先向记忆填入上下文
        Msg contextMsg1 =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("What is the weather today?").build())
                        .build();

        Msg contextMsg2 =
                Msg.builder()
                        .name("Assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("I don't have that information.").build())
                        .build();

        // 创建记录所收消息的模型
        final List<Msg>[] capturedMessages = new List[] {null};
        final int[] callCount = {0};
        MockModel mockModel =
                new MockModel(
                        messages -> {
                            int callNum = callCount[0]++;
                            if (callNum == 0) {
                                // 首次返回工具调用，强制达到 maxIters
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_0")
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .name("test_tool")
                                                                        .id("tool_0")
                                                                        .input(Map.of())
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            } else {
                                // 第二次摘要时捕获消息
                                capturedMessages[0] = messages;
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_summary")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "Based on our"
                                                                                    + " conversation,"
                                                                                    + " you asked"
                                                                                    + " about the"
                                                                                    + " weather but"
                                                                                    + " I don't"
                                                                                    + " have access"
                                                                                    + " to that"
                                                                                    + " information.")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            }
                        });

        MockToolkit mockToolkit = new MockToolkit();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt("You are a helpful assistant.")
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .maxIters(1)
                        .build();

        // 创建立即触发 maxIters 的用户消息
        Msg userMsg = TestUtils.createUserMessage("User", "Can you help?");

        // 调用智能体
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // 验证响应
        assertNotNull(response, "Response should not be null");

        // 验证模型收到上下文消息
        assertNotNull(capturedMessages[0], "Model should have received messages");

        // 消息应包括系统提示、已有上下文、用户消息和提示消息
        // 至少应有系统消息、两条上下文、用户消息、提示，共五条
        assertTrue(
                capturedMessages[0].size() >= 5,
                "Model should receive system prompt, context, user msg, and hint");

        // 验证提示消息位于最后
        Msg lastMsg = capturedMessages[0].get(capturedMessages[0].size() - 1);
        assertEquals(MsgRole.USER, lastMsg.getRole(), "Hint message should be USER role");
        String hintText = ((TextBlock) lastMsg.getFirstContentBlock()).getText();
        assertTrue(
                hintText.contains("failed to generate response"),
                "Hint message should contain expected text");
        assertTrue(hintText.contains("summarizing"), "Hint message should mention summarizing");
    }

    @Test
    @DisplayName("Should handle summarizing with empty memory")
    void testSummarizingWithEmptyMemory() {

        final int[] callCount = {0};
        MockModel mockModel =
                new MockModel(
                        messages -> {
                            int callNum = callCount[0]++;
                            if (callNum == 0) {
                                // 强制达到 maxIters
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_0")
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .name("test_tool")
                                                                        .id("tool_0")
                                                                        .input(Map.of())
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            } else {
                                // 返回摘要
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_summary")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "I was unable to"
                                                                                    + " complete"
                                                                                    + " the task"
                                                                                    + " within the"
                                                                                    + " iteration"
                                                                                    + " limit.")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            }
                        });

        MockToolkit mockToolkit = new MockToolkit();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt("You are a test assistant.")
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .maxIters(1)
                        .build();

        Msg userMsg = TestUtils.createUserMessage("User", "Help please");

        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Response should not be null");
        assertEquals(MsgRole.ASSISTANT, response.getRole(), "Response should be from assistant");
        assertTrue(
                response.getFirstContentBlock() instanceof TextBlock,
                "Response should be TextBlock");
    }

    @Test
    @DisplayName("Should add summary message to memory")
    void testSummaryAddedToMemory() {

        final int[] callCount = {0};
        MockModel mockModel =
                new MockModel(
                        messages -> {
                            int callNum = callCount[0]++;
                            if (callNum == 0) {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_0")
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .name("test_tool")
                                                                        .id("tool_0")
                                                                        .input(Map.of())
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            } else {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_summary")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "This is the"
                                                                                    + " summary of"
                                                                                    + " what"
                                                                                    + " happened.")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            }
                        });

        MockToolkit mockToolkit = new MockToolkit();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt("You are a helpful assistant.")
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .maxIters(1)
                        .build();

        // 检查初始记忆大小
        int initialMemorySize = agent.getAgentState().getContext().size();

        Msg userMsg = TestUtils.createUserMessage("User", "Please help");
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // 验证记忆增长
        int finalMemorySize = agent.getAgentState().getContext().size();
        assertTrue(
                finalMemorySize > initialMemorySize,
                "Memory should contain additional messages after summarizing");

        // 验证最后消息是摘要
        Msg lastMessage = agent.getAgentState().getContext().get(finalMemorySize - 1);
        assertEquals(response, lastMessage, "Last message in memory should be the summary");
        assertEquals(
                MsgRole.ASSISTANT, lastMessage.getRole(), "Summary message should be ASSISTANT");
    }

    @Test
    @DisplayName("Should handle second call after maxIters with pending tool calls - Issue #1005")
    void testSecondCallAfterMaxItersWithPendingToolCalls() {
        // 复现问题 #1005：
        // 1. 多轮对话中调用工具
        // 2. 工具未响应或超时，留下待处理调用
        // 3. 达到 maxIters，会话自动结束
        // 4. 用户发送新消息，不应抛 IllegalStateException
        final String toolId = "call_638e428da2cf48ceb8b05762";

        // 模拟模型首次返回工具调用，随后返回摘要
        final int[] callCount = {0};
        MockModel mockModel =
                new MockModel(
                        messages -> {
                            int callNum = callCount[0]++;
                            if (callNum == 0) {
                                // 首次返回 ToolUseBlock，模拟工具调用
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_0")
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .name("search_tool")
                                                                        .id(toolId)
                                                                        .input(
                                                                                Map.of(
                                                                                        "query",
                                                                                        "test"))
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            } else {
                                // 第二次因 maxIters=1 达到上限而摘要
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_summary")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "I reached the"
                                                                                    + " maximum"
                                                                                    + " iteration"
                                                                                    + " limit."
                                                                                    + " Please try"
                                                                                    + " again.")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            }
                        });

        MockToolkit mockToolkit = new MockToolkit();

        // 创建 maxIters=1 智能体以快速触发摘要
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt("You are a helpful assistant.")
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .maxIters(1)
                        .build();

        // 第一条用户消息触发工具及 maxIters 摘要
        Msg firstUserMsg = TestUtils.createUserMessage("User", "Please search for something");
        Msg firstResponse =
                agent.call(firstUserMsg)
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // 验证首个响应
        assertNotNull(firstResponse, "First response should not be null");
        assertEquals(MsgRole.ASSISTANT, firstResponse.getRole());

        // 关键：验证记忆中的待处理工具调用已被协调
        // 修复前会留下没有结果的待处理调用
        // 修复后 summarizing() 应为待处理工具添加错误结果
        List<Msg> memoryMessages = agent.getAgentState().getContext();

        // 查找待处理工具对应的结果消息
        boolean hasToolResultForPendingTool =
                memoryMessages.stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .anyMatch(tr -> tr.getId() != null && tr.getId().equals(toolId));

        assertTrue(
                hasToolResultForPendingTool,
                "Memory should contain error result for pending tool call after summarizing");

        Msg pendingToolResultMsg =
                memoryMessages.stream()
                        .filter(
                                m ->
                                        m.getContentBlocks(ToolResultBlock.class).stream()
                                                .anyMatch(
                                                        tr ->
                                                                tr.getId() != null
                                                                        && tr.getId()
                                                                                .equals(toolId)))
                        .findFirst()
                        .orElse(null);
        assertNotNull(pendingToolResultMsg, "Pending tool call should have a result message");
        assertEquals(
                MsgRole.TOOL,
                pendingToolResultMsg.getRole(),
                "Pending tool error result should be stored as TOOL message");

        // 验证结果指明因迭代上限而取消
        ToolResultBlock toolResult =
                memoryMessages.stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .filter(tr -> tr.getId() != null && tr.getId().equals(toolId))
                        .findFirst()
                        .orElse(null);

        // 结果必须存在，来源可以是工具包或摘要修复逻辑
        assertNotNull(toolResult);

        // 第二次调用是问题 #1005 的关键验证
        // 修复前此处会抛异常：
        // IllegalStateException：存在待处理工具调用却没有结果，
        // 因此不能追加消息

        // 为第二次用户交互重置模型
        final int[] secondCallCount = {0};
        MockModel secondMockModel =
                new MockModel(
                        messages -> {
                            int callNum = secondCallCount[0]++;
                            if (callNum == 0) {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_second_0")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "Hello! How can I"
                                                                                    + " help you"
                                                                                    + " today?")
                                                                        .build()))
                                                .usage(new ChatUsage(5, 10, 15))
                                                .build());
                            }
                            return List.of();
                        });

        ReActAgent secondAgent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt("You are a helpful assistant.")
                        .model(secondMockModel)
                        .toolkit(mockToolkit)
                        .maxIters(2)
                        .build();

        // 第二条用户消息，修复前会抛 IllegalStateException
        Msg secondUserMsg = TestUtils.createUserMessage("User", "Hello again");

        // 不应再因存在待处理调用但没有结果
        // 而拒绝添加消息
        Msg secondResponse =
                secondAgent
                        .call(secondUserMsg)
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // 验证第二次响应成功
        assertNotNull(secondResponse, "Second response should not be null");
        assertEquals(MsgRole.ASSISTANT, secondResponse.getRole());
        assertTrue(
                secondResponse.getFirstContentBlock() instanceof TextBlock,
                "Second response should contain TextBlock");

        TextBlock secondText = (TextBlock) secondResponse.getFirstContentBlock();
        assertEquals("Hello! How can I help you today?", secondText.getText());
    }

    @Test
    @DisplayName("Should add exactly one TOOL result for each pending tool during summarizing")
    void testSummarizingAddsOneToolResultPerPendingTool() {
        final String toolId1 = "call_pending_1";
        final String toolId2 = "call_pending_2";

        Msg pendingAssistantMsg =
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .name("search_tool")
                                                .id(toolId1)
                                                .input(Map.of("query", "weather"))
                                                .build(),
                                        ToolUseBlock.builder()
                                                .name("search_tool")
                                                .id(toolId2)
                                                .input(Map.of("query", "news"))
                                                .build()))
                        .build();

        MockModel mockModel =
                new MockModel(
                        messages ->
                                List.of(
                                        ChatResponse.builder()
                                                .id("msg_summary")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "Iteration limit"
                                                                                    + " reached.")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build()));

        MockToolkit mockToolkit = new MockToolkit();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt("You are a helpful assistant.")
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .maxIters(1)
                        .build();
        agent.getAgentState().contextMutable().add(pendingAssistantMsg);

        Msg summaryResponse = invokeSummarizing(agent);
        assertNotNull(summaryResponse, "Summary response should not be null");

        List<Msg> memoryMessages = agent.getAgentState().getContext();

        long toolId1ToolRoleCount =
                memoryMessages.stream()
                        .filter(m -> m.getRole() == MsgRole.TOOL)
                        .filter(
                                m ->
                                        m.getContentBlocks(ToolResultBlock.class).stream()
                                                .anyMatch(
                                                        tr ->
                                                                tr.getId() != null
                                                                        && tr.getId()
                                                                                .equals(toolId1)))
                        .count();
        long toolId2ToolRoleCount =
                memoryMessages.stream()
                        .filter(m -> m.getRole() == MsgRole.TOOL)
                        .filter(
                                m ->
                                        m.getContentBlocks(ToolResultBlock.class).stream()
                                                .anyMatch(
                                                        tr ->
                                                                tr.getId() != null
                                                                        && tr.getId()
                                                                                .equals(toolId2)))
                        .count();

        assertEquals(
                1L, toolId1ToolRoleCount, "toolId1 should have exactly one TOOL result message");
        assertEquals(
                1L, toolId2ToolRoleCount, "toolId2 should have exactly one TOOL result message");

        long toolId1NonToolRoleCount =
                memoryMessages.stream()
                        .filter(m -> m.getRole() != MsgRole.TOOL)
                        .filter(
                                m ->
                                        m.getContentBlocks(ToolResultBlock.class).stream()
                                                .anyMatch(
                                                        tr ->
                                                                tr.getId() != null
                                                                        && tr.getId()
                                                                                .equals(toolId1)))
                        .count();
        long toolId2NonToolRoleCount =
                memoryMessages.stream()
                        .filter(m -> m.getRole() != MsgRole.TOOL)
                        .filter(
                                m ->
                                        m.getContentBlocks(ToolResultBlock.class).stream()
                                                .anyMatch(
                                                        tr ->
                                                                tr.getId() != null
                                                                        && tr.getId()
                                                                                .equals(toolId2)))
                        .count();

        assertEquals(
                0L, toolId1NonToolRoleCount, "toolId1 should not have non-TOOL result messages");
        assertEquals(
                0L, toolId2NonToolRoleCount, "toolId2 should not have non-TOOL result messages");
    }

    @Test
    @DisplayName("Should report fallback model name in summary hooks")
    void testSummaryHooksUseFallbackModelName() {
        List<String> seenModelNames = new CopyOnWriteArrayList<>();
        Hook captureHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreSummaryEvent pre) {
                            seenModelNames.add("pre:" + pre.getModelName());
                        } else if (event instanceof PostSummaryEvent post) {
                            seenModelNames.add("post:" + post.getModelName());
                        }
                        return Mono.just(event);
                    }
                };

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt("You are a helpful assistant.")
                        .model(new ThrowingModel("Primary summary model failed"))
                        .fallbackModel(
                                new StaticModel("Fallback summary output", "FallbackSummaryModel"))
                        .toolkit(new MockToolkit())
                        .hook(captureHook)
                        .maxIters(1)
                        .build();

        Msg pendingAssistantMsg =
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .name("search_tool")
                                                .id("call_summary_fallback")
                                                .input(Map.of("query", "weather"))
                                                .build()))
                        .build();
        agent.getAgentState().contextMutable().add(pendingAssistantMsg);

        Msg response = invokeSummarizing(agent);
        assertNotNull(response, "Summary response should not be null");
        assertEquals("Fallback summary output", TestUtils.extractTextContent(response));
        assertEquals(
                List.of("pre:primary-summary-model", "post:FallbackSummaryModel"), seenModelNames);
    }

    private static Msg invokeSummarizing(ReActAgent agent) {
        try {
            // 通过 activateSlotForContext(null) 为默认会话创建 CallExecution
            Method activateMethod =
                    ReActAgent.class.getDeclaredMethod(
                            "activateSlotForContext", RuntimeContext.class);
            activateMethod.setAccessible(true);
            Object exec = activateMethod.invoke(agent, (Object) null);
            Method method = exec.getClass().getDeclaredMethod("summarizing");
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            reactor.core.publisher.Mono<Msg> mono =
                    (reactor.core.publisher.Mono<Msg>) method.invoke(exec);
            return mono.block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke summarizing()", e);
        }
    }

    private static final class ThrowingModel implements Model {
        private final String errorMessage;

        private ThrowingModel(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.error(new RuntimeException(errorMessage));
        }

        @Override
        public String getModelName() {
            return "primary-summary-model";
        }
    }

    private static final class StaticModel implements Model {
        private final String responseText;
        private final String modelName;

        private StaticModel(String responseText, String modelName) {
            this.responseText = responseText;
            this.modelName = modelName;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .id("msg_summary")
                            .content(List.of(TextBlock.builder().text(responseText).build()))
                            .usage(new ChatUsage(10, 20, 30))
                            .build());
        }

        @Override
        public String getModelName() {
            return modelName;
        }
    }
}
