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
package io.agentscope.core.agent;

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
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * 空最终响应保护测试：结束响应没有工具调用也无可见内容（如全部回答位于推理通道）时，返回推理循环，但受 {@code maxIters} 限制，不能静默返回空答复。
 */
class ReActAgentEmptyResponseRetryTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    private Model mockModel;
    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        mockModel = mock(Model.class);
        when(mockModel.getModelName()).thenReturn("test-model");
        toolkit = new Toolkit();
    }

    @Test
    @DisplayName("Thinking-only final response loops back and the retry carries a reminder")
    void retriesThinkingOnlyFinalResponse() {
        when(mockModel.stream(anyList(), anyList(), any()))
                .thenReturn(fluxOf(TestUtils.createThinkingMessage("assistant", "thinking only")))
                .thenReturn(fluxOf(TestUtils.createAssistantMessage("assistant", "final answer")));

        ReActAgent agent = buildAgent(10);
        Msg result = agent.call(TestUtils.createUserMessage("user", "hello")).block(TEST_TIMEOUT);

        assertNotNull(result);
        assertTrue(result.hasContentBlocks(TextBlock.class), "Retry should produce visible text");
        verify(mockModel, times(2)).stream(anyList(), anyList(), any());

        // 重新进入推理时，合成提醒必须为最后一条消息
        ArgumentCaptor<List<Msg>> msgsCaptor = ArgumentCaptor.forClass(List.class);
        verify(mockModel, times(2)).stream(msgsCaptor.capture(), anyList(), any());
        List<Msg> secondCallMsgs = msgsCaptor.getAllValues().get(1);
        Msg lastMsg = secondCallMsgs.get(secondCallMsgs.size() - 1);
        assertEquals(Boolean.TRUE, lastMsg.getMetadata().get(Msg.METADATA_SYNTHETIC));
        assertEquals("empty_response", lastMsg.getMetadata().get(Msg.METADATA_REMINDER_KIND));
        TextBlock reminderText = lastMsg.getFirstContentBlock(TextBlock.class);
        assertNotNull(reminderText);
        assertTrue(
                reminderText.getText().startsWith("<system-reminder>"),
                "Reminder should be a system reminder");
    }

    @Test
    @DisplayName("A model that keeps returning thinking-only stops at maxIters")
    void loopIsBoundedByMaxIters() {
        // 工具匹配使用 any()，因为摘要路径传入 null 工具模式
        when(mockModel.stream(anyList(), any(), any()))
                .thenAnswer(
                        invocation ->
                                fluxOf(TestUtils.createThinkingMessage("assistant", "thinking")));

        ReActAgent agent = buildAgent(1);
        Msg result = agent.call(TestUtils.createUserMessage("user", "hello")).block(TEST_TIMEOUT);

        assertNotNull(result);
        assertFalse(
                result.hasContentBlocks(TextBlock.class),
                "Exhausted iterations return the empty response as-is");
        // 迭代 0 推理一轮，迭代 1 通过 summarizing() 的摘要路径
        // 再次调用模型，不能形成无限循环
        verify(mockModel, times(2)).stream(anyList(), any(), any());
    }

    @Test
    @DisplayName("Blank text counts as no visible content")
    void blankTextTriggersRetry() {
        when(mockModel.stream(anyList(), anyList(), any()))
                .thenReturn(fluxOf(blankTextMsg()))
                .thenReturn(fluxOf(TestUtils.createAssistantMessage("assistant", "final answer")));

        ReActAgent agent = buildAgent(10);
        Msg result = agent.call(TestUtils.createUserMessage("user", "hello")).block(TEST_TIMEOUT);

        assertNotNull(result);
        assertTrue(result.hasContentBlocks(TextBlock.class));
        verify(mockModel, times(2)).stream(anyList(), anyList(), any());
    }

    @Test
    @DisplayName("Responses with tool calls do not trigger the guard")
    void toolCallResponsesAreNotRetried() {
        when(mockModel.stream(anyList(), anyList(), any()))
                .thenReturn(fluxOf(toolUseMsg()))
                .thenReturn(fluxOf(TestUtils.createAssistantMessage("assistant", "final answer")));

        ReActAgent agent = buildAgent(10);
        Msg result = agent.call(TestUtils.createUserMessage("user", "hello")).block(TEST_TIMEOUT);

        assertNotNull(result);
        assertTrue(result.hasContentBlocks(TextBlock.class));
        // 恰好一次行动，总计两次模型调用，无保护机制引起的额外调用
        verify(mockModel, times(2)).stream(anyList(), anyList(), any());
    }

    @Test
    @DisplayName("Normal text responses are not retried")
    void textResponsesAreNotRetried() {
        when(mockModel.stream(anyList(), anyList(), any()))
                .thenReturn(fluxOf(TestUtils.createAssistantMessage("assistant", "hello back")));

        ReActAgent agent = buildAgent(10);
        Msg result = agent.call(TestUtils.createUserMessage("user", "hello")).block(TEST_TIMEOUT);

        assertNotNull(result);
        assertTrue(result.hasContentBlocks(TextBlock.class));
        verify(mockModel, times(1)).stream(anyList(), anyList(), any());
    }

    private ReActAgent buildAgent(int maxIters) {
        return ReActAgent.builder()
                .name("test-agent")
                .model(mockModel)
                .toolkit(toolkit)
                .checkRunning(false)
                .maxIters(maxIters)
                .build();
    }

    private Flux<ChatResponse> fluxOf(Msg msg) {
        ChatResponse response =
                ChatResponse.builder().id("test-id").content(msg.getContent()).build();
        return Flux.just(response);
    }

    private Msg blankTextMsg() {
        return Msg.builder()
                .name("assistant")
                .role(MsgRole.ASSISTANT)
                .content(
                        ThinkingBlock.builder().thinking("thinking").build(),
                        TextBlock.builder().text("   ").build())
                .build();
    }

    private Msg toolUseMsg() {
        Map<String, Object> input = Map.of("query", "test");
        return Msg.builder()
                .name("assistant")
                .role(MsgRole.ASSISTANT)
                .content(
                        ThinkingBlock.builder().thinking("call the tool").build(),
                        ToolUseBlock.builder()
                                .id("t1")
                                .name("search")
                                .input(input)
                                .content(JsonUtils.getJsonCodec().toJson(input))
                                .build())
                .build();
    }
}
