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
package io.agentscope.core.studio;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("StudioMessageHook Tests")
class StudioMessageHookTest {

    private StudioClient mockStudioClient;
    private StudioMessageHook hook;
    private Agent mockAgent;

    @BeforeEach
    void setUp() {
        mockStudioClient = mock(StudioClient.class);
        mockAgent = mock(Agent.class);
        hook = new StudioMessageHook(mockStudioClient);
    }

    @Test
    @DisplayName("Should forward message to Studio on PostCallEvent")
    void testPostCallEventSuccess() {
        // 创建测试消息
        Msg msg =
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Test response").build())
                        .build();

        PostCallEvent event = new PostCallEvent(mockAgent, msg);

        // 模拟推送成功
        when(mockStudioClient.pushMessage(any(Msg.class))).thenReturn(Mono.empty());

        // 处理事件
        Mono<HookEvent> result = hook.onEvent(event);

        // 验证
        StepVerifier.create(result).expectNext(event).verifyComplete();

        verify(mockStudioClient, times(1)).pushMessage(msg);
    }

    @Test
    @DisplayName("Should handle Studio client error gracefully")
    void testPostCallEventWithError() {
        Msg msg =
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Test response").build())
                        .build();

        PostCallEvent event = new PostCallEvent(mockAgent, msg);

        // 模拟推送失败
        when(mockStudioClient.pushMessage(any(Msg.class)))
                .thenReturn(Mono.error(new IOException("Studio unavailable")));

        // 处理事件，不应失败
        Mono<HookEvent> result = hook.onEvent(event);

        // 验证即使出错也返回事件
        StepVerifier.create(result).expectNext(event).verifyComplete();

        verify(mockStudioClient, times(1)).pushMessage(msg);
    }

    @Test
    @DisplayName("Should handle null StudioClient gracefully")
    void testPostCallEventWithNullClient() {
        StudioMessageHook nullClientHook = new StudioMessageHook(null);

        Msg msg =
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Test response").build())
                        .build();

        PostCallEvent event = new PostCallEvent(mockAgent, msg);

        // 处理事件
        Mono<HookEvent> result = nullClientHook.onEvent(event);

        // 验证无错误返回事件
        StepVerifier.create(result).expectNext(event).verifyComplete();
    }

    @Test
    @DisplayName("Should pass through non-PostCallEvent events")
    void testOtherEvents() {
        Msg msg =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Input").build())
                        .build();

        PreCallEvent event = new PreCallEvent(mockAgent, new ArrayList<>(List.of(msg)));

        // 处理事件
        Mono<HookEvent> result = hook.onEvent(event);

        // 验证返回事件而不调用 Studio
        StepVerifier.create(result).expectNext(event).verifyComplete();

        verify(mockStudioClient, never()).pushMessage(any());
    }

    @Test
    @DisplayName("Should extract message from PostCallEvent correctly")
    void testMessageExtraction() {
        Msg msg =
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Complex message").build())
                        .build();

        PostCallEvent event = new PostCallEvent(mockAgent, msg);

        when(mockStudioClient.pushMessage(any(Msg.class))).thenReturn(Mono.empty());

        // 处理
        Mono<HookEvent> result = hook.onEvent(event);

        StepVerifier.create(result).expectNext(event).verifyComplete();

        // 验证推送了正确消息
        verify(mockStudioClient, times(1)).pushMessage(msg);
    }

    @Test
    @DisplayName("Constructor should accept StudioClient")
    void testConstructor() {
        StudioClient client = mock(StudioClient.class);
        StudioMessageHook testHook = new StudioMessageHook(client);
        assertNotNull(testHook);
    }

    @Test
    @DisplayName("Should be able to create hook with null client")
    void testConstructorWithNull() {
        StudioMessageHook testHook = new StudioMessageHook(null);
        assertNotNull(testHook);

        // 测试客户端为 null 时仍可工作
        Msg msg =
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Test").build())
                        .build();

        PostCallEvent event = new PostCallEvent(mockAgent, msg);
        Mono<HookEvent> result = testHook.onEvent(event);

        StepVerifier.create(result).expectNext(event).verifyComplete();
    }

    @Test
    @DisplayName("Multiple events should be handled independently")
    void testMultipleEvents() {
        Msg msg1 =
                Msg.builder()
                        .name("Agent1")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Response 1").build())
                        .build();

        Msg msg2 =
                Msg.builder()
                        .name("Agent2")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Response 2").build())
                        .build();

        PostCallEvent event1 = new PostCallEvent(mockAgent, msg1);
        PostCallEvent event2 = new PostCallEvent(mockAgent, msg2);

        when(mockStudioClient.pushMessage(any(Msg.class))).thenReturn(Mono.empty());

        // 处理两个事件
        Mono<HookEvent> result1 = hook.onEvent(event1);
        Mono<HookEvent> result2 = hook.onEvent(event2);

        StepVerifier.create(result1).expectNext(event1).verifyComplete();
        StepVerifier.create(result2).expectNext(event2).verifyComplete();

        // 验证两条消息都已推送
        verify(mockStudioClient, times(1)).pushMessage(msg1);
        verify(mockStudioClient, times(1)).pushMessage(msg2);
    }
}
