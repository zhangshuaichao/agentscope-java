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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * AgentBase 单元测试，覆盖初始化与属性、消息处理、观察及中断。记忆、状态和待处理工具调用在真正使用这些功能的具体实现（ReActAgent）中测试。
 */
@DisplayName("AgentBase Tests")
class AgentBaseTest {

    /**
     * 对应 AgentBase 私有 MAX_AGENT_ID_LENGTH，说明见 normalizeAgentId。
     */
    private static final int MAX_AGENT_ID_LENGTH = 255;

    private TestAgent agent;

    /**
     * 供测试使用的 AgentBase 具体实现。
     */
    static class TestAgent extends AgentBase {
        private String testResponse = TestConstants.TEST_ASSISTANT_RESPONSE;
        private Duration delay = Duration.ZERO;

        public TestAgent(String name) {
            super(name);
        }

        public TestAgent(String name, boolean checkRunning) {
            super(name, null, checkRunning, List.of());
        }

        public void setTestResponse(String response) {
            this.testResponse = response;
        }

        public void setDelay(Duration delay) {
            this.delay = delay;
        }

        @Override
        protected Mono<Msg> doCall(List<Msg> msgs) {
            Msg response =
                    Msg.builder()
                            .name(getName())
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text(testResponse).build())
                            .build();

            if (delay.isZero()) {
                return Mono.just(response);
            }
            return Mono.delay(delay).thenReturn(response);
        }

        @Override
        protected Mono<Void> doObserve(Msg msg) {
            // TestAgent 无需观察，只需完成
            return Mono.empty();
        }

        @Override
        protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
            Msg interruptMsg =
                    Msg.builder()
                            .name(getName())
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Interrupted").build())
                            .build();
            return Mono.just(interruptMsg);
        }
    }

    @BeforeEach
    void setUp() {
        agent = new TestAgent(TestConstants.TEST_AGENT_NAME);
    }

    @Test
    @DisplayName("Should initialize agent with correct properties")
    void testInitialization() {
        // 验证基本属性
        assertNotNull(agent.getAgentId(), "Agent ID should not be null");
        assertEquals(TestConstants.TEST_AGENT_NAME, agent.getName(), "Agent name should match");

        // 验证智能体 ID 唯一
        TestAgent agent2 = new TestAgent("Agent2");
        assertNotEquals(
                agent.getAgentId(),
                agent2.getAgentId(),
                "Different agents should have different IDs");
    }

    @Test
    @DisplayName("Should reject agent id consisting only of dots")
    void testNormalizeAgentIdAllDotsRejected() {
        for (String allDots : new String[] {".", "..", "..."}) {
            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> AgentBase.normalizeAgentId(allDots),
                            "All-dots agent id '" + allDots + "' should be rejected");
            assertTrue(
                    exception.getMessage().contains("all dots"),
                    "Error message should explain the all-dots rule");
        }
    }

    @Test
    @DisplayName("Should reject agent id longer than 255 characters, accepting exactly 255")
    void testNormalizeAgentIdLengthLimit() {
        // 恰好达到上限仍合法
        String atLimit = "a".repeat(MAX_AGENT_ID_LENGTH);
        assertEquals(
                atLimit,
                AgentBase.normalizeAgentId(atLimit),
                "Agent id at the max length should be accepted");

        // 超过上限一个单位即拒绝
        String overLimit = "a".repeat(MAX_AGENT_ID_LENGTH + 1);
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> AgentBase.normalizeAgentId(overLimit),
                        "Agent id exceeding the max length should be rejected");
        assertTrue(
                exception.getMessage().contains(String.valueOf(MAX_AGENT_ID_LENGTH)),
                "Error message should state the max length");
    }

    @Test
    @DisplayName("Should handle single message input")
    void testSingleMessageInput() {
        // 创建用户消息
        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);

        // 获取响应
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // 验证响应
        assertNotNull(response, "Response should not be null");
        assertEquals(
                TestConstants.TEST_AGENT_NAME,
                response.getName(),
                "Response should be from the agent");
        assertEquals(MsgRole.ASSISTANT, response.getRole(), "Response should have ASSISTANT role");

        String text = TestUtils.extractTextContent(response);
        assertEquals(
                TestConstants.TEST_ASSISTANT_RESPONSE, text, "Response text should match expected");
    }

    @Test
    @DisplayName("Should handle multiple message input")
    void testMultipleMessageInput() {
        // 创建多条用户消息
        List<Msg> messages =
                List.of(
                        TestUtils.createUserMessage("User", "First message"),
                        TestUtils.createUserMessage("User", "Second message"),
                        TestUtils.createUserMessage("User", "Third message"));

        // 获取响应
        Msg response =
                agent.call(messages)
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // 验证响应
        assertNotNull(response, "Response should not be null");
        assertEquals(
                TestConstants.TEST_AGENT_NAME,
                response.getName(),
                "Response should be from the agent");
    }

    @Test
    @DisplayName("Should not trigger concurrency conflict")
    void testConcurrencyConflict() {
        Msg message = TestUtils.createUserMessage("User", "First message");
        // 获取响应
        Msg responseMsg =
                agent.call(message)
                        .subscribeOn(Schedulers.boundedElastic()) // 模拟聊天模型
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // 不抛出 IllegalStateException
        Msg response2 =
                agent.call(message).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // 验证响应
        assertNotNull(responseMsg, "Response should not be null");
        assertEquals(
                TestConstants.TEST_AGENT_NAME,
                responseMsg.getName(),
                "Response should be from the agent");

        assertNotNull(response2, "Response should not be null");
    }

    @Test
    @DisplayName("Should handle observe without generating reply")
    void testObserve() {
        // 创建待观察消息
        Msg msg = TestUtils.createUserMessage("User", "Observed message");

        // 观察应无错误完成
        agent.observe(msg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // TestAgent 不记录观察，只验证完成
        // 实际观察行为由 ReActAgent 等具体实现的测试覆盖
    }

    @Test
    @DisplayName("Should handle observe with multiple messages")
    void testObserveMultiple() {
        // 创建多条待观察消息
        List<Msg> messages =
                List.of(
                        TestUtils.createUserMessage("User", "Message 1"),
                        TestUtils.createUserMessage("User", "Message 2"));

        // 观察应无错误完成
        agent.observe(messages).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));
    }

    @Test
    @DisplayName("Should handle interrupt")
    void testInterrupt() {
        // 测试 interrupt() 方法
        agent.interrupt();

        // 验证仍可调用智能体，中断处理在内部完成
        Msg msg = TestUtils.createUserMessage("User", "Test after interrupt");
        Msg response =
                agent.call(msg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Agent should still respond after interrupt");
    }

    @Test
    @DisplayName("Should handle interrupt with message")
    void testInterruptWithMessage() {
        Msg interruptMsg = TestUtils.createUserMessage("User", "Stop");

        // 测试 interrupt(Msg) 方法
        agent.interrupt(interruptMsg);

        // 验证仍可调用智能体
        Msg msg = TestUtils.createUserMessage("User", "Test after interrupt");
        Msg response =
                agent.call(msg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Agent should still respond after interrupt with message");
    }

    @Test
    @DisplayName("Should support continuation without new input")
    void testContinuation() {
        // 无参数调用，用于续执行
        Msg response = agent.call().block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // 验证响应
        assertNotNull(response, "Response should not be null for continuation");
        assertEquals(MsgRole.ASSISTANT, response.getRole(), "Response should have ASSISTANT role");
    }

    @Test
    @DisplayName("Should allow concurrent calls (checkRunning no longer enforced)")
    void testConcurrentCallsAllowed() throws InterruptedException {
        TestAgent slowAgent = new TestAgent("SlowAgent", false);
        slowAgent.setDelay(Duration.ofMillis(200));

        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicReference<Msg> result1 = new AtomicReference<>();
        AtomicReference<Msg> result2 = new AtomicReference<>();

        Thread t1 =
                new Thread(
                        () -> {
                            bothStarted.countDown();
                            result1.set(
                                    slowAgent
                                            .call(TestUtils.createUserMessage("User", "call1"))
                                            .block(Duration.ofSeconds(5)));
                        });
        Thread t2 =
                new Thread(
                        () -> {
                            bothStarted.countDown();
                            result2.set(
                                    slowAgent
                                            .call(TestUtils.createUserMessage("User", "call2"))
                                            .block(Duration.ofSeconds(5)));
                        });
        t1.start();
        t2.start();
        t1.join(3000);
        t2.join(3000);
        assertNotNull(result1.get());
        assertNotNull(result2.get());
    }

    @Test
    @DisplayName("Should allow concurrent calls when checkRunning is disabled")
    void testCheckRunningDisabled_AllowsConcurrentCall() throws InterruptedException {
        // 创建 checkRunning=false 且有延迟的智能体
        TestAgent slowAgent = new TestAgent("SlowAgent", false);
        slowAgent.setDelay(Duration.ofMillis(300));

        CountDownLatch firstCallStarted = new CountDownLatch(1);
        AtomicReference<Msg> firstResponse = new AtomicReference<>();
        AtomicReference<Msg> secondResponse = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        // 后台启动第一次调用
        Thread firstCallThread =
                new Thread(
                        () -> {
                            firstCallStarted.countDown();
                            Msg msg = TestUtils.createUserMessage("User", "First call");
                            Msg response = slowAgent.call(msg).block(Duration.ofSeconds(5));
                            firstResponse.set(response);
                        });
        firstCallThread.start();

        // 等待第一次调用开始
        assertTrue(firstCallStarted.await(1, TimeUnit.SECONDS), "First call should start");
        Thread.sleep(50); // 留出时间设置运行标记

        // 首个调用仍运行时发起第二个，不应抛错
        try {
            Msg msg = TestUtils.createUserMessage("User", "Second call");
            Msg response = slowAgent.call(msg).block(Duration.ofSeconds(5));
            secondResponse.set(response);
        } catch (Throwable t) {
            error.set(t);
        }

        // 等待首个调用完成
        firstCallThread.join(2000);

        // 验证两次调用均成功
        assertNotNull(firstResponse.get(), "First call should complete successfully");
        assertNotNull(secondResponse.get(), "Second call should complete successfully");
        assertTrue(error.get() == null, "No error should occur during concurrent calls");
    }
}
