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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * 接入 {@code ToolExecutor} 的工具调用拦截器链集成测试。
 *
 * <p>覆盖工具调用拦截器设计 §8 矩阵：拒绝形式、参数重写、预设参数交互、顺序、错误归属、挂起、经 {@code Toolkit#copy()} 传播、外部工具边界、调用方身份、并发、批量路径超时/重试语义及会话记录。
 *
 * @author shanhongyu
 */
@Tag("unit")
@DisplayName("ToolCallInterceptor Integration Tests")
class ToolCallInterceptorTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /**
     * 回显最终输入的自定义工具，记录每次收到的输入与调用次数。
     */
    static final class EchoTool implements AgentTool {
        final AtomicInteger calls = new AtomicInteger();
        final List<Map<String, Object>> receivedInputs = new CopyOnWriteArrayList<>();

        @Override
        public String getName() {
            return "echo";
        }

        @Override
        public String getDescription() {
            return "Echoes the final input it receives";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            calls.incrementAndGet();
            Map<String, Object> snapshot = new HashMap<>(param.getInput());
            receivedInputs.add(snapshot);
            return Mono.just(ToolResultBlock.text(JsonUtils.getJsonCodec().toJson(snapshot)));
        }
    }

    /**
     * 记录调用顺序并原样委托的拦截器。
     */
    static final class TracingInterceptor implements ToolCallInterceptor {
        final String marker;
        final List<String> trace;
        final int order;

        TracingInterceptor(String marker, List<String> trace) {
            this(marker, trace, 1);
        }

        TracingInterceptor(String marker, List<String> trace, int order) {
            this.marker = marker;
            this.trace = trace;
            this.order = order;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public Mono<ToolResultBlock> intercept(
                ToolInvocation invocation, Function<ToolInvocation, Mono<ToolResultBlock>> next) {
            trace.add(marker);
            return next.apply(invocation);
        }
    }

    private static ToolUseBlock call(String id, Map<String, Object> input) {
        return ToolUseBlock.builder()
                .id(id)
                .name("echo")
                .input(input)
                .content(JsonUtils.getJsonCodec().toJson(input))
                .build();
    }

    private static ToolUseBlock externalCall() {
        Map<String, Object> input = Map.of("endpoint", "/users");
        return ToolUseBlock.builder()
                .id("call-external")
                .name("external_api")
                .input(input)
                .content(JsonUtils.getJsonCodec().toJson(input))
                .build();
    }

    private static String textOf(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Agent agent(String agentId, String agentName) {
        Agent agent = mock(Agent.class);
        when(agent.getAgentId()).thenReturn(agentId);
        when(agent.getName()).thenReturn(agentName);
        return agent;
    }

    @Test
    @DisplayName("Without interceptors the execution path is unchanged")
    void zeroInterceptorsKeepsBehavior() {
        EchoTool echo = new EchoTool();
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder().parallel(false).build());
        toolkit.registerTool(echo);

        ToolResultBlock result =
                toolkit.callTools(List.of(call("call-1", Map.of("a", 1))), null, null, null)
                        .block(TIMEOUT)
                        .get(0);

        assertEquals(1, echo.calls.get());
        assertTrue(textOf(result).contains("\"a\":1"));
    }

    @Test
    @DisplayName("Both denial forms skip the tool; DENIED keeps denial semantics")
    void denialFormsSkipTool() {
        EchoTool echo = new EchoTool();
        Toolkit toolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .parallel(false)
                                .interceptor(
                                        (iv, next) ->
                                                Mono.just(
                                                        ToolResultBlock.text("Guard: denied")
                                                                .withState(ToolResultState.DENIED)))
                                .build());
        toolkit.registerTool(echo);

        ToolResultBlock denied =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(call("call-denied", Map.of()))
                                        .build())
                        .block(TIMEOUT);
        assertEquals(0, echo.calls.get(), "Tool must not execute on denial");
        assertEquals(ToolResultState.DENIED, denied.getState());
        assertEquals("call-denied", denied.getId(), "Id must be backfilled");
        assertEquals("echo", denied.getName(), "Name must be backfilled");
        assertEquals("Guard: denied", textOf(denied));

        Toolkit errorToolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .parallel(false)
                                .interceptor(
                                        (iv, next) ->
                                                Mono.just(ToolResultBlock.error("invalid call")))
                                .build());
        errorToolkit.registerTool(new EchoTool());
        ToolResultBlock invalid =
                errorToolkit
                        .callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(call("call-invalid", Map.of()))
                                        .build())
                        .block(TIMEOUT);
        assertEquals(ToolResultState.ERROR, invalid.getState(), "Error form reports call failure");
        assertTrue(textOf(invalid).contains("invalid call"));
    }

    @Test
    @DisplayName("Interceptor sees the preset-merged input, may rewrite it, and null args survive")
    void seesAndRewritesFinalInput() {
        EchoTool echo = new EchoTool();
        AtomicReference<Object> seenOptions = new AtomicReference<>("unset");
        AtomicReference<Object> seenPreset = new AtomicReference<>("unset");
        Toolkit toolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .parallel(false)
                                .interceptor(
                                        (iv, next) -> {
                                            seenOptions.set(iv.input().get("options"));
                                            seenPreset.set(iv.input().get("preset"));
                                            Map<String, Object> rewritten =
                                                    new HashMap<>(iv.input());
                                            rewritten.put("file_path", "resolved.txt");
                                            rewritten.put("preset", "overridden");
                                            return next.apply(iv.withInput(rewritten));
                                        })
                                .build());
        toolkit.registration()
                .agentTool(echo)
                .presetParameters(Map.of("echo", Map.of("preset", "preset_value")))
                .apply();

        // 模型参数、预设参数与 JSON null 值合并后均到达拦截器
        Map<String, Object> input = new HashMap<>();
        input.put("file_path", "../secrets.txt");
        input.put("options", null);
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-rewrite")
                        .name("echo")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build()).block(TIMEOUT);

        assertNull(seenOptions.get(), "Null argument must be preserved, not dropped");
        assertEquals("preset_value", seenPreset.get(), "Preset must be visible to interceptors");
        assertEquals(1, echo.calls.get());
        Map<String, Object> received = echo.receivedInputs.get(0);
        assertEquals(
                "resolved.txt", received.get("file_path"), "Tool must receive rewritten value");
        assertEquals("overridden", received.get("preset"), "Interceptor may override presets");
    }

    @Test
    @DisplayName("Higher order runs outermost; same order keeps registration order")
    void ordersNestDescendingAndStable() {
        EchoTool echo = new EchoTool();
        List<String> trace = new CopyOnWriteArrayList<>();
        Toolkit toolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .parallel(false)
                                .interceptor(
                                        new TracingInterceptor(
                                                "low", trace)) // order 为 1，最先注册
                                .interceptor(
                                        new TracingInterceptor(
                                                "high", trace,
                                                10)) // order 为 10，虽后注册但位于外层
                                .interceptor(
                                        new TracingInterceptor(
                                                "low2",
                                                trace)) // 与 low 同 order，保持位于其后的内层
                                .build());
        toolkit.registerTool(echo);

        toolkit.callTools(List.of(call("call-order", Map.of())), null, null, null).block(TIMEOUT);

        assertEquals(List.of("high", "low", "low2"), trace);
        assertEquals(1, echo.calls.get());
    }

    @Test
    @DisplayName("Terminal and interceptor errors map to the existing error format")
    void errorsKeepExistingMapping() {
        // 终端工具抛异常，消息仍为 "Tool execution failed: ..."
        Toolkit toolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .parallel(false)
                                .interceptor((iv, next) -> next.apply(iv))
                                .build());
        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "echo";
                    }

                    @Override
                    public String getDescription() {
                        return "Failing tool";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object", "properties", Map.of());
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.error(new IllegalStateException("tool blew up"));
                    }
                });
        ToolResultBlock terminalError =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(call("call-t1", Map.of()))
                                        .build())
                        .block(TIMEOUT);
        assertEquals(ToolResultState.ERROR, terminalError.getState());
        assertTrue(
                textOf(terminalError).contains("Tool execution failed: tool blew up"),
                "Terminal failure must not be misattributed: " + textOf(terminalError));

        // 拦截器同步抛异常，使用相同映射
        Toolkit syncThrowToolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .parallel(false)
                                .interceptor(
                                        (ToolCallInterceptor)
                                                (iv, next) -> {
                                                    throw new IllegalStateException("sync boom");
                                                })
                                .build());
        syncThrowToolkit.registerTool(new EchoTool());
        ToolResultBlock syncError =
                syncThrowToolkit
                        .callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(call("call-t2", Map.of()))
                                        .build())
                        .block(TIMEOUT);
        assertTrue(
                textOf(syncError).contains("Tool execution failed: sync boom"),
                "Sync throw must map through existing handler: " + textOf(syncError));

        // 拦截器返回 Mono.error，使用相同映射
        Toolkit asyncErrorToolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .parallel(false)
                                .interceptor(
                                        (iv, next) ->
                                                Mono.error(new IllegalStateException("async boom")))
                                .build());
        asyncErrorToolkit.registerTool(new EchoTool());
        ToolResultBlock asyncError =
                asyncErrorToolkit
                        .callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(call("call-t3", Map.of()))
                                        .build())
                        .block(TIMEOUT);
        assertTrue(textOf(asyncError).contains("Tool execution failed: async boom"));
    }

    @Test
    @DisplayName("Empty Mono and ToolSuspendException map to the existing result formats")
    void emptyAndSuspendResultsMapToExistingFormats() {
        Toolkit emptyToolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .parallel(false)
                                .interceptor((iv, next) -> Mono.empty())
                                .build());
        emptyToolkit.registerTool(new EchoTool());
        ToolResultBlock empty =
                emptyToolkit
                        .callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(call("call-empty", Map.of()))
                                        .build())
                        .block(TIMEOUT);
        assertEquals(ToolResultState.ERROR, empty.getState());
        assertEquals(
                "Error: Tool execution failed: Tool completed without returning a result",
                textOf(empty));

        Toolkit suspendToolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .parallel(false)
                                .interceptor(
                                        (iv, next) -> {
                                            throw new ToolSuspendException("needs human approval");
                                        })
                                .build());
        suspendToolkit.registerTool(new EchoTool());
        ToolResultBlock suspended =
                suspendToolkit
                        .callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(call("call-suspend", Map.of()))
                                        .build())
                        .block(TIMEOUT);
        assertTrue(suspended.isSuspended());
        assertEquals(ToolResultState.SUSPENDED, suspended.getState());
        assertEquals("needs human approval", suspended.getSuspendReason());
    }

    @Test
    @DisplayName("Toolkit#copy propagates interceptors; external tools short-circuit before them")
    void copyPropagatesAndExternalToolsBypass() {
        EchoTool echo = new EchoTool();
        List<String> trace = new CopyOnWriteArrayList<>();
        Toolkit toolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .parallel(false)
                                .interceptor(new TracingInterceptor("trace", trace))
                                .build());
        toolkit.registerTool(echo);
        toolkit.registerSchema(
                ToolSchema.builder()
                        .name("external_api")
                        .description("External tool")
                        .parameters(Map.of("type", "object", "properties", Map.of()))
                        .build());

        // 复制后拦截器仍生效
        Toolkit copy = toolkit.copy();
        copy.callTool(ToolCallParam.builder().toolUseBlock(call("call-copy", Map.of())).build())
                .block(TIMEOUT);
        assertEquals(List.of("trace"), trace);
        assertEquals(1, echo.calls.get());

        // 外部工具在进入链前挂起，不触及拦截器
        trace.clear();
        ToolResultBlock external =
                toolkit.callTools(List.of(externalCall()), null, null, null).block(TIMEOUT).get(0);
        assertTrue(external.isSuspended(), "External tool must surface as suspended");
        assertEquals(0, trace.size(), "Interceptors must not run for external tools");
    }

    @Test
    @DisplayName("Concurrent calls share one interceptor instance")
    void concurrentCallsShareInterceptor() {
        EchoTool echo = new EchoTool();
        AtomicInteger interceptions = new AtomicInteger();
        Toolkit toolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .parallel(true)
                                .interceptor(
                                        (iv, next) -> {
                                            interceptions.incrementAndGet();
                                            return next.apply(iv);
                                        })
                                .build());
        toolkit.registerTool(echo);

        List<ToolUseBlock> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(call("call-c" + i, Map.of("i", i)));
        }

        List<ToolResultBlock> results = toolkit.callTools(calls, null, null, null).block(TIMEOUT);

        assertEquals(8, results.size());
        assertEquals(8, interceptions.get(), "Every call must pass the shared interceptor");
        assertEquals(8, echo.calls.get());
    }

    @Test
    @DisplayName("Slow interceptor counts into the batch-path timeout; direct path has none")
    void timeoutAppliesOnBatchPathOnly() {
        EchoTool echo = new EchoTool();
        Toolkit toolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .parallel(false)
                                .interceptor(
                                        (iv, next) ->
                                                Mono.delay(Duration.ofMillis(400))
                                                        .then(next.apply(iv)))
                                .build());
        toolkit.registerTool(echo);

        // 批量路径：使用执行配置中的逐调用超时
        ExecutionConfig tight = ExecutionConfig.builder().timeout(Duration.ofMillis(100)).build();
        ToolResultBlock timedOut =
                toolkit.callTools(List.of(call("call-timeout", Map.of())), tight, null, null)
                        .block(TIMEOUT)
                        .get(0);
        assertEquals(ToolResultState.ERROR, timedOut.getState());
        assertTrue(
                textOf(timedOut).contains("timeout"),
                "Expected timeout error: " + textOf(timedOut));

        // 直接 callTool 路径：当前行为不包装超时
        toolkit.callTool(
                        ToolCallParam.builder().toolUseBlock(call("call-direct", Map.of())).build())
                .block(TIMEOUT);
        assertEquals(1, echo.calls.get(), "Direct path must complete without timeout");
    }

    @Test
    @DisplayName("Retries re-run interceptors once per attempt")
    void retriesReRunInterceptors() {
        EchoTool echo = new EchoTool();
        AtomicInteger interceptions = new AtomicInteger();
        Toolkit toolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .parallel(false)
                                .interceptor(
                                        (iv, next) -> {
                                            interceptions.incrementAndGet();
                                            // 时长超过超时，因此每次尝试均失败并
                                            // 重试
                                            return Mono.delay(Duration.ofMillis(300))
                                                    .then(next.apply(iv));
                                        })
                                .build());
        toolkit.registerTool(echo);

        ExecutionConfig retrying =
                ExecutionConfig.builder()
                        .timeout(Duration.ofMillis(100))
                        .maxAttempts(3)
                        .initialBackoff(Duration.ofMillis(10))
                        .build();

        ToolResultBlock result =
                toolkit.callTools(List.of(call("call-retry", Map.of())), retrying, null, null)
                        .block(TIMEOUT)
                        .get(0);

        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals(3, interceptions.get(), "Interceptor must run once per attempt");
        assertEquals(0, echo.calls.get(), "Tool never completes within the timeout");
    }

    @Test
    @DisplayName("Session records capture post-interception arguments; denial writes no dispatch")
    void sessionRecordsReflectInterception() {
        EchoTool echo = new EchoTool();
        Toolkit toolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .parallel(false)
                                .interceptor(
                                        (iv, next) ->
                                                next.apply(
                                                        iv.withInput(
                                                                Map.of(
                                                                        "file_path",
                                                                        "sandboxed.txt"))))
                                .build());
        toolkit.registerTool(echo);

        SessionLog log =
                new InMemorySessionLogStore()
                        .open(new SessionKey("u", "agent", "s"), RuntimeContext.empty());
        SessionRecorder recorder = new SessionRecorder(log, "turn-1");
        RuntimeContext context =
                RuntimeContext.builder()
                        .userId("u")
                        .sessionId("s")
                        .agentState(AgentState.builder().build())
                        .put(SessionRecorder.CONTEXT_KEY, recorder)
                        .build();

        try {
            toolkit.callTool(
                            ToolCallParam.builder()
                                    .toolUseBlock(
                                            call("call-allowed", Map.of("file_path", "../x.txt")))
                                    .runtimeContext(context)
                                    .build())
                    .block(TIMEOUT);
            assertEquals(1, echo.calls.get());

            Map<String, Object> dispatchArgs =
                    asMap(
                            log.readAfter(0, 1000).stream()
                                    .filter(e -> e.type().equals("tool/dispatch"))
                                    .map(SessionEvent::data)
                                    .findFirst()
                                    .orElseThrow()
                                    .get("arguments"));
            assertEquals(
                    Map.of("file_path", "sandboxed.txt"),
                    dispatchArgs,
                    "Dispatch must record post-interception arguments");
        } finally {
            recorder.close();
        }

        // 同一日志下一轮的拒绝路径，每个日志一个活动写入者
        SessionRecorder deniedRecorder = new SessionRecorder(log, "turn-2");
        try {
            RuntimeContext deniedContext =
                    RuntimeContext.builder()
                            .userId("u")
                            .sessionId("s")
                            .agentState(AgentState.builder().build())
                            .put(SessionRecorder.CONTEXT_KEY, deniedRecorder)
                            .build();
            Toolkit denyingToolkit =
                    new Toolkit(
                            ToolkitConfig.builder()
                                    .parallel(false)
                                    .interceptor(
                                            (iv, next) ->
                                                    Mono.just(
                                                            ToolResultBlock.text("Guard: no")
                                                                    .withState(
                                                                            ToolResultState
                                                                                    .DENIED)))
                                    .build());
            denyingToolkit.registerTool(new EchoTool());

            ToolResultBlock denied =
                    denyingToolkit
                            .callTool(
                                    ToolCallParam.builder()
                                            .toolUseBlock(call("call-denied", Map.of()))
                                            .runtimeContext(deniedContext)
                                            .build())
                            .block(TIMEOUT);
            assertEquals(ToolResultState.DENIED, denied.getState());
        } finally {
            deniedRecorder.close();
        }

        List<SessionEvent> turn2 =
                log.readAfter(0, 1000).stream().filter(e -> "turn-2".equals(e.turnId())).toList();
        assertTrue(
                turn2.stream().noneMatch(e -> e.type().equals("tool/dispatch")),
                "Denied call must not be dispatched");
        assertTrue(
                turn2.stream()
                        .noneMatch(
                                e ->
                                        e.type().equals("tool/decision")
                                                && "allowed".equals(e.data().get("decision"))),
                "Denied call must not be recorded as allowed");
        assertTrue(
                turn2.stream()
                        .anyMatch(
                                e ->
                                        e.type().equals("tool/decision")
                                                && "denied".equals(e.data().get("decision"))),
                "Existing observation logic must record the denied decision");
    }

    @Test
    @DisplayName("interceptor(varargs) appends while interceptors(list) replaces")
    void builderAppendAndReplaceSemantics() {
        List<String> trace = new CopyOnWriteArrayList<>();
        TracingInterceptor first = new TracingInterceptor("first", trace);
        TracingInterceptor second = new TracingInterceptor("second", trace);
        TracingInterceptor replacement = new TracingInterceptor("replacement", trace);

        ToolkitConfig appended =
                ToolkitConfig.builder().interceptor(first).interceptor(second).build();
        assertEquals(List.of(first, second), appended.getInterceptors(), "varargs calls append");

        ToolkitConfig replaced =
                ToolkitConfig.builder()
                        .interceptor(first)
                        .interceptors(List.of(replacement))
                        .build();
        assertEquals(List.of(replacement), replaced.getInterceptors(), "List overload replaces");

        assertEquals(
                List.of(), ToolkitConfig.defaultConfig().getInterceptors(), "Default is empty");

        List<ToolCallInterceptor> mutable = new ArrayList<>(List.of(first));
        ToolkitConfig copied = ToolkitConfig.builder().interceptors(mutable).build();
        mutable.add(second);
        assertEquals(List.of(first), copied.getInterceptors(), "Config must defensively copy");
    }

    @Test
    @DisplayName(
            "Interceptor observes caller identity and can dispatch business rules by agentName")
    void interceptorObservesCallerIdentity() {
        // 由智能体驱动的调用可见 runtimeContext 和智能体身份
        AtomicReference<RuntimeContext> seenContext = new AtomicReference<>();
        AtomicReference<String> seenAgentId = new AtomicReference<>("unset");
        AtomicReference<String> seenAgentName = new AtomicReference<>("unset");
        Toolkit observeToolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .parallel(false)
                                .interceptor(
                                        (iv, next) -> {
                                            seenContext.set(iv.runtimeContext());
                                            seenAgentId.set(iv.agentId());
                                            seenAgentName.set(iv.agentName());
                                            return next.apply(iv);
                                        })
                                .build());
        observeToolkit.registerTool(new EchoTool());

        RuntimeContext context =
                RuntimeContext.builder().userId("user-42").sessionId("sess-7").build();
        observeToolkit
                .callTool(
                        ToolCallParam.builder()
                                .toolUseBlock(call("call-ctx", Map.of()))
                                .runtimeContext(context)
                                .agent(agent("agent-42", "planner"))
                                .build())
                .block(TIMEOUT);
        assertNotNull(seenContext.get(), "RuntimeContext must be visible to interceptors");
        assertEquals("user-42", seenContext.get().getUserId());
        assertEquals("sess-7", seenContext.get().getSessionId());
        assertEquals("agent-42", seenAgentId.get());
        assertEquals("planner", seenAgentName.get());

        // 无智能体的直接调用身份为 null，这本身是有意义的信号
        AtomicReference<String> directAgentId = new AtomicReference<>("unset");
        AtomicReference<String> directAgentName = new AtomicReference<>("unset");
        AtomicReference<RuntimeContext> directContext = new AtomicReference<>();
        Toolkit directToolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .parallel(false)
                                .interceptor(
                                        (iv, next) -> {
                                            directContext.set(iv.runtimeContext());
                                            directAgentId.set(iv.agentId());
                                            directAgentName.set(iv.agentName());
                                            return next.apply(iv);
                                        })
                                .build());
        directToolkit.registerTool(new EchoTool());
        directToolkit
                .callTool(
                        ToolCallParam.builder().toolUseBlock(call("call-direct", Map.of())).build())
                .block(TIMEOUT);
        assertNull(directAgentId.get());
        assertNull(directAgentName.get());
        assertNull(directContext.get());

        // 业务规则可按 agentName 分发
        EchoTool echo = new EchoTool();
        Toolkit dispatchToolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .parallel(false)
                                .interceptor(
                                        (iv, next) ->
                                                "planner".equals(iv.agentName())
                                                        ? Mono.just(
                                                                ToolResultBlock.text(
                                                                                "Guard: planner is"
                                                                                    + " read-only")
                                                                        .withState(
                                                                                ToolResultState
                                                                                        .DENIED))
                                                        : next.apply(iv))
                                .build());
        dispatchToolkit.registerTool(echo);
        ToolResultBlock denied =
                dispatchToolkit
                        .callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(call("call-planner", Map.of()))
                                        .agent(agent("agent-1", "planner"))
                                        .build())
                        .block(TIMEOUT);
        assertEquals(ToolResultState.DENIED, denied.getState());
        assertEquals(0, echo.calls.get());

        ToolResultBlock allowed =
                dispatchToolkit
                        .callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(call("call-coder", Map.of()))
                                        .agent(agent("agent-2", "coder"))
                                        .build())
                        .block(TIMEOUT);
        assertTrue(allowed.getState() != ToolResultState.DENIED);
        assertEquals(1, echo.calls.get());
    }
}
