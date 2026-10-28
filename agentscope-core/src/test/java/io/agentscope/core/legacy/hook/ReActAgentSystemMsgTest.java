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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 测试 HookEvent.systemMsg 重构后的统一系统消息传播。
 * <p>验证 sysPrompt 在 PreCallEvent 前成为 systemMsg；钩子修改传播到 PreReasoningEvent；systemMsg 成为 model.stream() 首条输入；PreCallEvent.inputMessages 包含历史快照和调用参数；将 SYSTEM 注入输入尾部的钩子抛出 IllegalStateException。
 */
@DisplayName("ReActAgent system-message propagation tests")
class ReActAgentSystemMsgTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private Model mockModel;
    private InMemoryMemory memory;

    @BeforeEach
    void setUp() {
        mockModel = mock(Model.class);
        memory = new InMemoryMemory();
    }

    /**
     * 桩模型发出单个文本响应。
     */
    private void stubModelText(String text) {
        ChatResponse response =
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text(text).build()))
                        .build();
        when(mockModel.stream(anyList(), any(), any())).thenReturn(Flux.just(response));
        when(mockModel.getModelName()).thenReturn("stub");
    }

    private Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    // =========================================================================

    @Nested
    @DisplayName("sysPrompt seeded as systemMsg")
    class SysPromptSeeding {

        @Test
        @DisplayName("sysPrompt becomes systemMsg before PreCallEvent hooks run")
        void sysPrompt_isSeededIntoSystemMsg() {
            AtomicReference<Msg> capturedSysMsg = new AtomicReference<>();

            stubModelText("done");
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("agent")
                            .model(mockModel)
                            .sysPrompt("You are a helpful assistant.")
                            .maxIters(1)
                            .hook(
                                    new Hook() {
                                        @Override
                                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                                            if (event instanceof PreCallEvent) {
                                                capturedSysMsg.set(event.getSystemMessage());
                                            }
                                            return Mono.just(event);
                                        }
                                    })
                            .build();

            agent.call(List.of(userMsg("hello"))).block(TIMEOUT);

            Msg sys = capturedSysMsg.get();
            assertNotNull(sys);
            assertEquals(MsgRole.SYSTEM, sys.getRole());
            assertTrue(
                    sys.getTextContent().contains("You are a helpful assistant."),
                    "systemMsg should contain sysPrompt text");
        }

        @Test
        @DisplayName("agent with empty sysPrompt seeds null systemMsg")
        void emptySysPrompt_seedsNullSystemMsg() {
            AtomicReference<Msg> capturedSysMsg =
                    new AtomicReference<>(
                            Msg.builder()
                                    .role(MsgRole.SYSTEM)
                                    .content(TextBlock.builder().text("marker").build())
                                    .build());

            stubModelText("done");
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("agent")
                            .model(mockModel)
                            .maxIters(1)
                            .hook(
                                    new Hook() {
                                        @Override
                                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                                            if (event instanceof PreCallEvent) {
                                                capturedSysMsg.set(event.getSystemMessage());
                                            }
                                            return Mono.just(event);
                                        }
                                    })
                            .build();

            agent.call(List.of(userMsg("hello"))).block(TIMEOUT);

            // 没有 sysPrompt 时初始值为 null
            assertTrue(
                    capturedSysMsg.get() == null || capturedSysMsg.get().getTextContent().isBlank(),
                    "Expected null or blank systemMsg when sysPrompt is empty");
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("systemMsg propagated to reasoning")
    class SystemMsgPropagation {

        @Test
        @DisplayName("systemMsg set by PreCallEvent hook appears as first message in model input")
        void preCallHook_systemMsg_prependedToModelInput() {
            List<List<Msg>> modelInputCapture = new ArrayList<>();

            when(mockModel.getModelName()).thenReturn("stub");
            when(mockModel.stream(anyList(), any(), any()))
                    .thenAnswer(
                            invocation -> {
                                @SuppressWarnings("unchecked")
                                List<Msg> msgs = invocation.getArgument(0);
                                modelInputCapture.add(new ArrayList<>(msgs));
                                ChatResponse resp =
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("ok")
                                                                        .build()))
                                                .build();
                                return Flux.just(resp);
                            });

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("agent")
                            .model(mockModel)
                            .sysPrompt("base prompt")
                            .maxIters(1)
                            .hook(
                                    new Hook() {
                                        @Override
                                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                                            if (event instanceof PreCallEvent) {
                                                event.appendSystemContent(" extra context");
                                            }
                                            return Mono.just(event);
                                        }
                                    })
                            .build();

            agent.call(List.of(userMsg("hi"))).block(TIMEOUT);

            assertEquals(1, modelInputCapture.size(), "model.stream should be called once");
            List<Msg> input = modelInputCapture.get(0);
            assertTrue(input.size() >= 1, "model should receive at least one message");

            Msg firstMsg = input.get(0);
            assertEquals(MsgRole.SYSTEM, firstMsg.getRole(), "first message must be SYSTEM");
            String sysText = firstMsg.getTextContent();
            assertTrue(sysText.contains("base prompt"), "systemMsg should contain sysPrompt");
            assertTrue(
                    sysText.contains("extra context"),
                    "systemMsg should include text appended by hook");
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("PreCallEvent full message view")
    class PreCallFullMessageView {

        @Test
        @DisplayName("PreCallEvent inputMessages contains memory snapshot + callArgs")
        void preCallEvent_containsMemoryPlusCallArgs() {
            AtomicReference<List<Msg>> capturedInput = new AtomicReference<>();

            stubModelText("done");
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("agent")
                            .model(mockModel)
                            .maxIters(1)
                            .hook(
                                    new Hook() {
                                        @Override
                                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                                            if (event instanceof PreCallEvent pre) {
                                                capturedInput.set(
                                                        new ArrayList<>(pre.getInputMessages()));
                                            }
                                            return Mono.just(event);
                                        }
                                    })
                            .build();

            // 构建后添加一条历史消息，确保写入智能体自己的状态
            Msg priorMsg = userMsg("prior turn");
            agent.getAgentState().contextMutable().add(priorMsg);

            Msg callArg = userMsg("new message");
            agent.call(List.of(callArg)).block(TIMEOUT);

            List<Msg> input = capturedInput.get();
            assertNotNull(input);
            assertEquals(2, input.size(), "inputMessages should be [prior, callArg]");
            assertEquals(
                    "prior turn",
                    input.get(0).getTextContent(),
                    "first message should be the memory snapshot");
            assertEquals(
                    "new message",
                    input.get(1).getTextContent(),
                    "second message should be the call argument");
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("SYSTEM-in-tail guard")
    class SystemInTailGuard {

        @Test
        @DisplayName("Hook injecting SYSTEM into inputMessages tail throws IllegalStateException")
        void hook_injectingSystemIntoTail_throws() {
            stubModelText("done");
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("agent")
                            .model(mockModel)
                            .maxIters(1)
                            .hook(
                                    new Hook() {
                                        @Override
                                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                                            if (event instanceof PreCallEvent pre) {
                                                // 禁止向 inputMessages 追加 SYSTEM
                                                List<Msg> msgs =
                                                        new ArrayList<>(pre.getInputMessages());
                                                msgs.add(
                                                        Msg.builder()
                                                                .role(MsgRole.SYSTEM)
                                                                .content(
                                                                        TextBlock.builder()
                                                                                .text("bad")
                                                                                .build())
                                                                .build());
                                                pre.setInputMessages(msgs);
                                            }
                                            return Mono.just(event);
                                        }
                                    })
                            .build();

            assertThrows(
                    Exception.class,
                    () -> agent.call(List.of(userMsg("hello"))).block(TIMEOUT),
                    "Should throw when SYSTEM message appears in inputMessages tail");
        }
    }
}
