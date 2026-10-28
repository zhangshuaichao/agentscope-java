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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class ReActAgentStructuredOutputTest {

    private Toolkit toolkit;

    static class WeatherResponse {
        public String location;
        public String temperature;
        public String condition;
    }

    static class WeatherTools {
        @Tool(description = "Look up the current weather for a city")
        public String lookupWeather(@ToolParam(name = "city", description = "city") String city) {
            return "Sunny, 72°F";
        }
    }

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
    }

    @Test
    void testStructuredOutputToolBased() {
        Memory memory = new InMemoryMemory();

        // 创建模拟模型，依次返回：
        // 1. generate_response 工具调用
        // 2. 工具执行后的简单文本，表示结束
        Map<String, Object> toolInput =
                Map.of(
                        "response",
                        Map.of(
                                "location",
                                "San Francisco",
                                "temperature",
                                "72°F",
                                "condition",
                                "Sunny"));

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            // 检查是否存在 TOOL 角色的工具执行结果消息
                            boolean hasToolResults =
                                    msgs.stream().anyMatch(m -> m.getRole() == MsgRole.TOOL);

                            if (!hasToolResults) {
                                // 首次返回 generate_response 工具调用
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_1")
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("call_123")
                                                                        .name("generate_response")
                                                                        .input(toolInput)
                                                                        .content(
                                                                                JsonUtils
                                                                                        .getJsonCodec()
                                                                                        .toJson(
                                                                                                toolInput))
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            } else {
                                // 工具执行后第二次返回简单文本
                                // 无更多工具调用，表示完成
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_2")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Response generated")
                                                                        .build()))
                                                .usage(new ChatUsage(5, 10, 15))
                                                .build());
                            }
                        });

        // 创建 TOOL_BASED 策略智能体
        ReActAgent agent =
                ReActAgent.builder()
                        .name("weather-agent")
                        .sysPrompt("You are a weather assistant")
                        .model(mockModel)
                        .toolkit(toolkit)
                        .build();

        // 执行结构化输出调用
        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("What's the weather in San Francisco?")
                                        .build())
                        .build();

        // 调用智能体并从响应提取结构化数据
        Msg responseMsg = agent.call(inputMsg, WeatherResponse.class).block();
        assertNotNull(responseMsg);
        assertNotNull(responseMsg.getMetadata());

        // 从元数据提取结构化数据
        WeatherResponse result = responseMsg.getStructuredData(WeatherResponse.class);

        // 验证
        assertNotNull(result);
        assertEquals("San Francisco", result.location);
        assertEquals("72°F", result.temperature);
        assertEquals("Sunny", result.condition);
    }

    @Test
    void testStructuredOutputPreservesChatUsage() {
        Memory memory = new InMemoryMemory();

        // 创建返回带 ChatUsage 工具调用的模拟模型
        Map<String, Object> toolInput =
                Map.of(
                        "response",
                        Map.of(
                                "location",
                                "San Francisco",
                                "temperature",
                                "72°F",
                                "condition",
                                "Sunny"));

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            boolean hasToolResults =
                                    msgs.stream().anyMatch(m -> m.getRole() == MsgRole.TOOL);

                            if (!hasToolResults) {
                                // 首次返回工具调用及用量
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_1")
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("call_123")
                                                                        .name("generate_response")
                                                                        .input(toolInput)
                                                                        .content(
                                                                                JsonUtils
                                                                                        .getJsonCodec()
                                                                                        .toJson(
                                                                                                toolInput))
                                                                        .build()))
                                                .usage(new ChatUsage(100, 50, 1.5))
                                                .build());
                            } else {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_2")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Done")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 5, 0.1))
                                                .build());
                            }
                        });

        ReActAgent agent =
                ReActAgent.builder()
                        .name("weather-agent")
                        .sysPrompt("You are a weather assistant")
                        .model(mockModel)
                        .toolkit(toolkit)
                        .build();

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("What's the weather in San Francisco?")
                                        .build())
                        .build();

        Msg responseMsg = agent.call(inputMsg, WeatherResponse.class).block();
        assertNotNull(responseMsg);

        // 验证结构化输出
        WeatherResponse result = responseMsg.getStructuredData(WeatherResponse.class);
        assertNotNull(result);
        assertEquals("San Francisco", result.location);

        // 验证记忆压缩后 ChatUsage 仍保留
        ChatUsage usage = responseMsg.getChatUsage();
        assertNotNull(usage, "ChatUsage should be preserved after structured output compression");
        assertEquals(100, usage.getInputTokens(), "Input tokens should be preserved");
        assertEquals(50, usage.getOutputTokens(), "Output tokens should be preserved");
        assertEquals(1.5, usage.getTime(), 0.01, "Time should be preserved");

        ChatUsage canonicalUsage = responseMsg.getUsage();
        assertNotNull(canonicalUsage, "Canonical usage field should be preserved");
        assertEquals(100, canonicalUsage.getInputTokens());
        assertEquals(50, canonicalUsage.getOutputTokens());
        assertEquals(1.5, canonicalUsage.getTime(), 0.01);
    }

    @Test
    void testStructuredOutputPreservesThinkingBlock() {
        Memory memory = new InMemoryMemory();

        // 创建返回带 ThinkingBlock 工具调用的模拟模型
        Map<String, Object> toolInput =
                Map.of(
                        "response",
                        Map.of(
                                "location",
                                "San Francisco",
                                "temperature",
                                "72°F",
                                "condition",
                                "Sunny"));

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            boolean hasToolResults =
                                    msgs.stream().anyMatch(m -> m.getRole() == MsgRole.TOOL);

                            if (!hasToolResults) {
                                // 首次返回 ThinkingBlock 和工具调用
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_1")
                                                .content(
                                                        List.of(
                                                                ThinkingBlock.builder()
                                                                        .thinking(
                                                                                "Let me analyze the"
                                                                                    + " weather"
                                                                                    + " data for"
                                                                                    + " San Francisco...")
                                                                        .build(),
                                                                ToolUseBlock.builder()
                                                                        .id("call_123")
                                                                        .name("generate_response")
                                                                        .input(toolInput)
                                                                        .content(
                                                                                JsonUtils
                                                                                        .getJsonCodec()
                                                                                        .toJson(
                                                                                                toolInput))
                                                                        .build()))
                                                .usage(new ChatUsage(100, 50, 1.5))
                                                .build());
                            } else {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_2")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Done")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 5, 0.1))
                                                .build());
                            }
                        });

        ReActAgent agent =
                ReActAgent.builder()
                        .name("weather-agent")
                        .sysPrompt("You are a weather assistant")
                        .model(mockModel)
                        .toolkit(toolkit)
                        .build();

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("What's the weather in San Francisco?")
                                        .build())
                        .build();

        Msg responseMsg = agent.call(inputMsg, WeatherResponse.class).block();
        assertNotNull(responseMsg);

        // 验证结构化输出
        WeatherResponse result = responseMsg.getStructuredData(WeatherResponse.class);
        assertNotNull(result);
        assertEquals("San Francisco", result.location);

        // 验证记忆压缩后 ThinkingBlock 仍保留
        ThinkingBlock thinking = responseMsg.getFirstContentBlock(ThinkingBlock.class);
        assertNotNull(
                thinking, "ThinkingBlock should be preserved after structured output compression");
        assertEquals(
                "Let me analyze the weather data for San Francisco...",
                thinking.getThinking(),
                "Thinking content should be preserved");
    }

    @Test
    void testConcurrencyConflictStructuredOutput() {
        runSubscribeOnThenSequentialSecondCallStructuredOutputScenario(toolkit);
    }

    /**
     * 复现 subscribeOn(elastic) 与延迟 {@code doFinally} 的竞态；多次运行直到失败，或为修复积累信心。
     */
    @EnabledIfSystemProperty(named = "agentscope.runStructuredOutputRaceTest", matches = "true")
    @RepeatedTest(25000)
    @DisplayName("Structured output race: 25000 repetitions (subscribeOn + immediate second call)")
    void testConcurrencyConflictStructuredOutput_repeated() {
        runSubscribeOnThenSequentialSecondCallStructuredOutputScenario(toolkit);
    }

    /**
     * 首次在 {@link Schedulers#boundedElastic()} 调用，随后立即在调用线程使用同一智能体和工具包再次调用。结构化输出清理与第二次注册竞争时会不稳定。
     */
    private void runSubscribeOnThenSequentialSecondCallStructuredOutputScenario(
            Toolkit agentToolkit) {
        Memory memory = new InMemoryMemory();
        Map<String, Object> toolInput =
                Map.of(
                        "response",
                        Map.of(
                                "location", "San Francisco",
                                "temperature", "72°F",
                                "condition", "Sunny"));

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            boolean hasToolResults =
                                    msgs.stream().anyMatch(m -> m.getRole() == MsgRole.TOOL);
                            if (!hasToolResults) {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_1")
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("call_123")
                                                                        .name("generate_response")
                                                                        .input(toolInput)
                                                                        .content(
                                                                                JsonUtils
                                                                                        .getJsonCodec()
                                                                                        .toJson(
                                                                                                toolInput))
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            } else {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_2")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Done")
                                                                        .build()))
                                                .usage(new ChatUsage(5, 10, 15))
                                                .build());
                            }
                        });

        ReActAgent agent =
                ReActAgent.builder()
                        .name("weather-agent")
                        .sysPrompt("You are a weather assistant")
                        .model(mockModel)
                        .toolkit(agentToolkit)
                        .build();

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("What's the weather in San Francisco?")
                                        .build())
                        .build();

        Msg responseMsg =
                agent.call(inputMsg, WeatherResponse.class)
                        .subscribeOn(Schedulers.boundedElastic())
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS * 10000));

        Msg response2 =
                agent.call(inputMsg, WeatherResponse.class)
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(responseMsg);
        WeatherResponse result = responseMsg.getStructuredData(WeatherResponse.class);
        assertNotNull(result);
        assertEquals("San Francisco", result.location);
        assertEquals("72°F", result.temperature);
        assertEquals("Sunny", result.condition);

        assertNotNull(response2);
        // 不抛出 IllegalStateException
        WeatherResponse result2 = response2.getStructuredData(WeatherResponse.class);
        assertNotNull(result2);
    }

    @Test
    @DisplayName("Should not throw NPE when PostReasoning hook nulls out the reasoning message")
    void testStructuredOutputNullReasoningMessage() {
        Map<String, Object> toolInput =
                Map.of(
                        "response",
                        Map.of(
                                "location",
                                "San Francisco",
                                "temperature",
                                "72°F",
                                "condition",
                                "Sunny"));

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            boolean hasToolResults =
                                    msgs.stream().anyMatch(m -> m.getRole() == MsgRole.TOOL);

                            if (!hasToolResults) {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_1")
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("call_123")
                                                                        .name("generate_response")
                                                                        .input(toolInput)
                                                                        .content(
                                                                                JsonUtils
                                                                                        .getJsonCodec()
                                                                                        .toJson(
                                                                                                toolInput))
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            } else {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_2")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Done")
                                                                        .build()))
                                                .usage(new ChatUsage(5, 10, 15))
                                                .build());
                            }
                        });

        @SuppressWarnings("deprecation")
        Hook nullMessageHook =
                new Hook() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PostReasoningEvent pre) {
                            pre.setReasoningMessage(null);
                        }
                        return Mono.just(event);
                    }
                };

        ReActAgent agent =
                ReActAgent.builder()
                        .name("weather-agent")
                        .sysPrompt("You are a weather assistant")
                        .model(mockModel)
                        .toolkit(toolkit)
                        .hook(nullMessageHook)
                        .build();

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("What's the weather in San Francisco?")
                                        .build())
                        .build();

        // 修复前在 MonoJust 构造处抛出 value 为空的 NullPointerException
        Msg responseMsg =
                agent.call(inputMsg, WeatherResponse.class)
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // 应优雅处理 null eventMsg，可以返回空或继续循环，
        // 但不应抛出 NPE。
    }

    /**
     * 模型两次输出自由文本，随后通过 {@code ToolChoice.Specific} 强制调用才遵从。
     */
    @Test
    @DisplayName("Forces generate_response via tool_choice when the model skips the tool")
    void testStructuredOutputForcesToolChoiceWhenModelSkipsTool() {
        Map<String, Object> toolInput = weatherToolInput();
        AtomicInteger calls = new AtomicInteger();

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            int n = calls.getAndIncrement();
                            if (n < 2) {
                                // 第 0、1 轮忽略 generate_response，输出
                                // 自由文本，触发强制重试。
                                return List.of(textResponse("msg_" + n, "Plain text answer."));
                            }
                            // 第 2 轮通过 tool_choice 强制，模型才遵从。
                            return List.of(structuredToolResponse("msg_" + n, toolInput));
                        });

        ReActAgent agent = buildWeatherAgent(mockModel);

        Msg responseMsg = agent.call(weatherInput(), WeatherResponse.class).block();
        assertNotNull(responseMsg);

        WeatherResponse result = responseMsg.getStructuredData(WeatherResponse.class);
        assertNotNull(result);
        assertEquals("San Francisco", result.location);

        // 一次初始自由文本加两次强制重试，共三次推理，
        // 最后一轮调用 generate_response。
        assertEquals(3, mockModel.getCallCount());

        // 最终轮通过 ToolChoice.Specific 强制。
        assertTrue(
                mockModel.getLastOptions().getToolChoice() instanceof ToolChoice.Specific,
                "Expected a ToolChoice.Specific on the forced round");
        assertEquals(
                "generate_response",
                ((ToolChoice.Specific) mockModel.getLastOptions().getToolChoice()).toolName());
    }

    /**
     * 模型始终不调用 {@code generate_response} 时，三次强制重试后放弃。
     */
    @Test
    @DisplayName("Gives up after 3 forced retries when the model never calls generate_response")
    void testStructuredOutputGivesUpAfterThreeForcedRetries() {
        AtomicInteger calls = new AtomicInteger();

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            // 模型始终不调用 generate_response。
                            return List.of(
                                    textResponse(
                                            "msg_" + calls.getAndIncrement(),
                                            "I'll just answer in plain text."));
                        });

        ReActAgent agent = buildWeatherAgent(mockModel);

        Msg responseMsg = agent.call(weatherInput(), WeatherResponse.class).block();
        assertNotNull(responseMsg);

        // 无结构化数据，循环放弃而非死锁。
        assertFalse(responseMsg.hasStructuredData());
        // 一次初始轮加三次重试，共四次推理后放弃。
        assertEquals(4, mockModel.getCallCount());

        // 最终放弃轮仍在结束前使用 tool_choice 强制。
        assertTrue(mockModel.getLastOptions().getToolChoice() instanceof ToolChoice.Specific);
    }

    /**
     * 不支持 {@code ToolChoice.Specific} 时，首次回退调用发送提示提醒。
     */
    @Test
    @DisplayName(
            "Sends prompt reminder on first fallback call when ToolChoice.Specific unsupported")
    void testStructuredOutputPromptReminderWhenToolChoiceUnsupported() {
        Map<String, Object> toolInput = weatherToolInput();
        AtomicInteger calls = new AtomicInteger();

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            int n = calls.getAndIncrement();
                            boolean reminderPresent =
                                    msgs.stream()
                                            .anyMatch(
                                                    m ->
                                                            m.getTextContent() != null
                                                                    && m.getTextContent()
                                                                            .contains(
                                                                                    "You MUST call"
                                                                                        + " the `generate_response`"
                                                                                        + " tool"));
                            if (reminderPresent) {
                                // 模型看到提示提醒后遵从。
                                return List.of(structuredToolResponse("msg_" + n, toolInput));
                            }
                            return List.of(textResponse("msg_" + n, "Plain text answer."));
                        });
        mockModel.setSupportsToolChoiceSpecific(false);

        ReActAgent agent = buildWeatherAgent(mockModel);

        Msg responseMsg = agent.call(weatherInput(), WeatherResponse.class).block();
        assertNotNull(responseMsg);

        WeatherResponse result = responseMsg.getStructuredData(WeatherResponse.class);
        assertNotNull(result);
        assertEquals("San Francisco", result.location);

        // 从首次回退轮起即可见持续提醒。
        assertEquals(1, mockModel.getCallCount());

        // 提示策略不得设置 tool_choice。
        assertNull(mockModel.getLastOptions().getToolChoice());
    }

    /**
     * 每轮回退中调用局部提醒恰好出现一次。
     */
    @Test
    @DisplayName("Sends one request-local reminder on every fallback round")
    void testStructuredOutputPromptReminderPresentOnEveryFallbackRound() {
        Map<String, Object> toolInput = weatherToolInput();
        AtomicInteger calls = new AtomicInteger();
        List<Integer> reminderCounts = new CopyOnWriteArrayList<>();

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            int n = calls.getAndIncrement();
                            reminderCounts.add(
                                    (int)
                                            countTextOccurrences(
                                                    msgs,
                                                    "You MUST call the `generate_response`"
                                                            + " tool"));
                            if (n < 2) {
                                return List.of(textResponse("msg_" + n, "Plain text answer."));
                            }
                            return List.of(structuredToolResponse("msg_" + n, toolInput));
                        });
        mockModel.setSupportsToolChoiceSpecific(false);

        ReActAgent agent = buildWeatherAgent(mockModel);

        Msg responseMsg = agent.call(weatherInput(), WeatherResponse.class).block();
        assertNotNull(responseMsg);
        assertNotNull(responseMsg.getStructuredData(WeatherResponse.class));

        // 每轮都只有一个提醒，包括自由文本后的重试。
        assertEquals(3, mockModel.getCallCount());
        assertEquals(List.of(1, 1, 1), reminderCounts);
    }

    /**
     * 放弃后，后续普通调用不应收到结构化输出提醒。
     */
    @Test
    @DisplayName("Does not send structured-output reminder on a later normal call")
    void testStructuredOutputGiveUpLeavesNoReminderForLaterNormalCall() {
        AtomicInteger calls = new AtomicInteger();

        MockModel mockModel =
                new MockModel(
                        msgs ->
                                List.of(
                                        textResponse(
                                                "msg_" + calls.getAndIncrement(),
                                                "I'll just answer in plain text.")));
        mockModel.setSupportsToolChoiceSpecific(false);

        ReActAgent agent = buildWeatherAgent(mockModel);

        Msg responseMsg = agent.call(weatherInput(), WeatherResponse.class).block();
        assertNotNull(responseMsg);
        // 无结构化数据，循环放弃而非死锁。
        assertFalse(responseMsg.hasStructuredData());
        // 一次初始轮加三次重试，共四次推理后放弃。
        assertEquals(4, mockModel.getCallCount());

        // 后续普通调用不含请求局部结构化输出提醒。
        Msg followUp = agent.call(weatherInput()).block();
        assertNotNull(followUp);
        assertEquals(
                0,
                countTextOccurrences(
                        mockModel.getLastMessages(), "You MUST call the `generate_response` tool"));
    }

    /**
     * 将临时提醒合入最后用户消息，不改变持久化状态。
     */
    @Test
    @DisplayName("Merges reminder into last user message without changing durable state")
    void testStructuredOutputReminderMergesIntoLastUserMessage() {
        Map<String, Object> toolInput = weatherToolInput();

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            boolean reminderPresent =
                                    msgs.stream()
                                            .anyMatch(
                                                    m ->
                                                            m.getTextContent() != null
                                                                    && m.getTextContent()
                                                                            .contains(
                                                                                    "You MUST call"
                                                                                        + " the `generate_response`"
                                                                                        + " tool"));
                            if (reminderPresent) {
                                return List.of(structuredToolResponse("msg_so", toolInput));
                            }
                            return List.of(textResponse("msg_text", "ok"));
                        });

        ReActAgent agent = buildWeatherAgent(mockModel);

        Msg responseMsg = agent.call(weatherInput(), WeatherResponse.class).block();
        assertNotNull(responseMsg);
        assertNotNull(responseMsg.getStructuredData(WeatherResponse.class));

        List<Msg> requestMessages = mockModel.getLastMessages();
        assertNotNull(requestMessages);
        Msg lastMessage = requestMessages.get(requestMessages.size() - 1);
        assertEquals(MsgRole.USER, lastMessage.getRole());
        assertTrue(lastMessage.getTextContent().contains("What's the weather in San Francisco?"));
        assertTrue(lastMessage.getTextContent().contains("You MUST call the `generate_response`"));

        List<Msg> durableContext = agent.getAgentState().getContext();
        assertEquals(
                0,
                countTextOccurrences(durableContext, "You MUST call the `generate_response` tool"));
        durableContext.stream()
                .filter(msg -> msg.getRole() == MsgRole.USER)
                .filter(msg -> msg.getTextContent().contains("What's the weather in San"))
                .findFirst()
                .ifPresent(
                        msg ->
                                assertFalse(
                                        msg.getTextContent()
                                                .contains(
                                                        "You MUST call the"
                                                                + " `generate_response`")));
    }

    /**
     * 最终模型输入以工具结果结束时，添加独立用户提醒。
     */
    @Test
    @DisplayName("Adds separate user reminder after a tool result")
    void testStructuredOutputReminderAfterToolResultUsesSeparateUserMessage() {
        Map<String, Object> toolInput = weatherToolInput();
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> businessToolInput = Map.of("city", "San Francisco");

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            if (calls.getAndIncrement() == 0) {
                                return List.of(
                                        businessToolResponse(
                                                "business_1", "lookupWeather", businessToolInput));
                            }
                            return List.of(structuredToolResponse("so_1", toolInput));
                        });

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WeatherTools());
        ReActAgent agent =
                ReActAgent.builder()
                        .name("weather-agent")
                        .sysPrompt("You are a weather assistant")
                        .model(mockModel)
                        .toolkit(toolkit)
                        .build();

        Msg responseMsg = agent.call(weatherInput(), WeatherResponse.class).block();
        assertNotNull(responseMsg);
        assertNotNull(responseMsg.getStructuredData(WeatherResponse.class));
        assertEquals(2, mockModel.getCallCount());

        List<Msg> requestMessages = mockModel.getLastMessages();
        assertNotNull(requestMessages);
        assertTrue(requestMessages.size() >= 2);
        assertEquals(MsgRole.TOOL, requestMessages.get(requestMessages.size() - 2).getRole());
        Msg reminderMessage = requestMessages.get(requestMessages.size() - 1);
        assertEquals(MsgRole.USER, reminderMessage.getRole());
        assertTrue(
                reminderMessage.getTextContent().contains("You MUST call the `generate_response`"));
        assertEquals(
                1,
                countTextOccurrences(
                        requestMessages, "You MUST call the `generate_response` tool"));
        assertEquals(
                0,
                countTextOccurrences(
                        agent.getAgentState().getContext(),
                        "You MUST call the `generate_response` tool"));
    }

    @Test
    @DisplayName("Valid native structured output is accepted without fallback")
    void testNativeStructuredOutputIsValidated() {
        AtomicInteger calls = new AtomicInteger();
        List<GenerateOptions> seenOptions = new CopyOnWriteArrayList<>();

        Model model =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        calls.incrementAndGet();
                        seenOptions.add(options);
                        return Flux.just(
                                textResponse(
                                        "native_valid",
                                        """
                                        {"location":"San Francisco","temperature":"72°F","condition":"Sunny"}
                                        """));
                    }

                    @Override
                    public String getModelName() {
                        return "native-mock";
                    }

                    @Override
                    public boolean supportsNativeStructuredOutput() {
                        return true;
                    }
                };

        ReActAgent agent = buildWeatherAgent(model);
        Msg responseMsg = agent.call(weatherInput(), WeatherResponse.class).block();

        assertNotNull(responseMsg);
        WeatherResponse result = responseMsg.getStructuredData(WeatherResponse.class);
        assertNotNull(result);
        assertEquals("San Francisco", result.location);
        assertEquals(
                Map.of("location", "San Francisco", "temperature", "72°F", "condition", "Sunny"),
                responseMsg.getMetadata().get(MessageMetadataKeys.STRUCTURED_OUTPUT));
        assertEquals(1, calls.get());
        assertNotNull(seenOptions.get(0).getResponseFormat());
        assertNotNull(seenOptions.get(0).getResponseFormat().getJsonSchema());
    }

    @Test
    @DisplayName("Invalid native structured output falls back to the generate_response tool")
    void testInvalidNativeStructuredOutputFallsBackToTool() {
        AtomicInteger calls = new AtomicInteger();
        List<GenerateOptions> seenOptions = new CopyOnWriteArrayList<>();
        List<List<ToolSchema>> seenTools = new CopyOnWriteArrayList<>();
        Map<String, Object> toolInput = weatherToolInput();

        Model model =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        int call = calls.getAndIncrement();
                        seenOptions.add(options);
                        seenTools.add(tools);
                        if (call == 0) {
                            return Flux.just(
                                    textResponse(
                                            "native_invalid",
                                            "{\"location\":\"San Francisco\","
                                                    + "\"temperature\":\"72°F\","
                                                    + "\"condition\":null}"));
                        }
                        if (call == 1) {
                            return Flux.just(structuredToolResponse("fallback_tool", toolInput));
                        }
                        throw new AssertionError("Unexpected model call: " + call);
                    }

                    @Override
                    public String getModelName() {
                        return "native-mock";
                    }

                    @Override
                    public boolean supportsNativeStructuredOutput() {
                        return true;
                    }
                };

        ReActAgent agent = buildWeatherAgent(model);
        Msg responseMsg = agent.call(weatherInput(), WeatherResponse.class).block();

        assertNotNull(responseMsg);
        WeatherResponse result = responseMsg.getStructuredData(WeatherResponse.class);
        assertNotNull(result);
        assertEquals("San Francisco", result.location);
        assertEquals(2, calls.get());
        assertNotNull(seenOptions.get(0).getResponseFormat());
        assertNull(seenOptions.get(1).getResponseFormat());
        assertTrue(
                seenTools.get(1).stream()
                        .anyMatch(tool -> "generate_response".equals(tool.getName())));
        assertEquals(
                0,
                countTextOccurrences(
                        agent.getAgentState().getContext(),
                        "{\"location\":\"San Francisco\",\"temperature\":\"72°F\","
                                + "\"condition\":null}"));
    }

    @Test
    @DisplayName("Native structured-output validation can be explicitly disabled")
    void testNativeStructuredOutputValidationCanBeDisabled() {
        AtomicInteger calls = new AtomicInteger();
        List<GenerateOptions> seenOptions = new CopyOnWriteArrayList<>();

        Model model =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        calls.incrementAndGet();
                        seenOptions.add(options);
                        return Flux.just(
                                textResponse(
                                        "native_schema_invalid",
                                        "{\"location\":\"San Francisco\","
                                                + "\"temperature\":\"72°F\","
                                                + "\"condition\":null}"));
                    }

                    @Override
                    public String getModelName() {
                        return "native-mock";
                    }

                    @Override
                    public boolean supportsNativeStructuredOutput() {
                        return true;
                    }
                };

        ReActAgent agent =
                ReActAgent.builder()
                        .name("weather-agent")
                        .sysPrompt("You are a weather assistant")
                        .model(model)
                        .toolkit(new Toolkit())
                        .validateStructuredOutput(false)
                        .build();
        Msg responseMsg = agent.call(weatherInput(), WeatherResponse.class).block();

        assertNotNull(responseMsg);
        assertTrue(responseMsg.hasStructuredData());
        WeatherResponse result = responseMsg.getStructuredData(WeatherResponse.class);
        assertNotNull(result);
        assertEquals("San Francisco", result.location);
        assertNull(result.condition);
        assertEquals(1, calls.get());
        assertNotNull(seenOptions.get(0).getResponseFormat());
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建绑定模拟模型的天气智能体。
     */
    private static ReActAgent buildWeatherAgent(Model model) {
        return ReActAgent.builder()
                .name("weather-agent")
                .sysPrompt("You are a weather assistant")
                .model(model)
                .toolkit(new Toolkit())
                .build();
    }

    /**
     * 构建标准用户天气查询消息。
     */
    private static Msg weatherInput() {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text("What's the weather in San Francisco?").build())
                .build();
    }

    /**
     * 构建 {@code generate_response} 工具输入载荷。
     */
    private static Map<String, Object> weatherToolInput() {
        return Map.of(
                "response",
                Map.of("location", "San Francisco", "temperature", "72°F", "condition", "Sunny"));
    }

    /**
     * 构建指定 ID 与文本的自由文本响应。
     */
    private static ChatResponse textResponse(String id, String text) {
        return ChatResponse.builder()
                .id(id)
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(new ChatUsage(1, 2, 3))
                .build();
    }

    /**
     * 构建带指定载荷的 {@code generate_response} 工具调用响应。
     */
    private static ChatResponse structuredToolResponse(String id, Map<String, Object> toolInput) {
        return ChatResponse.builder()
                .id(id)
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id(id)
                                        .name("generate_response")
                                        .input(toolInput)
                                        .content(JsonUtils.getJsonCodec().toJson(toolInput))
                                        .build()))
                .usage(new ChatUsage(10, 20, 30))
                .build();
    }

    /**
     * 构建带指定载荷的业务工具调用响应。
     */
    private static ChatResponse businessToolResponse(
            String id, String toolName, Map<String, Object> toolInput) {
        return ChatResponse.builder()
                .id(id)
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id(id)
                                        .name(toolName)
                                        .input(toolInput)
                                        .content(JsonUtils.getJsonCodec().toJson(toolInput))
                                        .build()))
                .usage(new ChatUsage(1, 2, 3))
                .build();
    }

    /**
     * 统计文本含给定子串的消息数量。
     */
    private static long countTextOccurrences(List<Msg> msgs, String needle) {
        if (msgs == null) {
            return 0;
        }
        return msgs.stream()
                .filter(m -> m.getTextContent() != null && m.getTextContent().contains(needle))
                .count();
    }
}
