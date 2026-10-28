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
package io.agentscope.core.agui.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiFunctionCall;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.AguiToolCall;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code returnDirect} 轮次的 AG-UI 投影回归测试：智能体跳过收尾模型调用，core 层为最终消息合成标准 {@code TextBlock} 事件，因此现有转换器无需本模块额外适配即可发出 {@code TEXT_MESSAGE_*}。
 */
class ReturnDirectAguiProjectionTest {

    /**
     * 脚本化模型，每次顺序模型调用返回一个 {@link ChatResponse} 流。
     */
    private static final class ScriptedModel extends ChatModelBase {

        private final List<Supplier<Flux<ChatResponse>>> scripts;
        private final AtomicInteger idx = new AtomicInteger(0);
        private final List<List<Msg>> received = new ArrayList<>();

        ScriptedModel(List<Supplier<Flux<ChatResponse>>> scripts) {
            this.scripts = scripts;
        }

        @Override
        public String getModelName() {
            return "scripted";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            received.add(List.copyOf(messages));
            int i = idx.getAndIncrement();
            if (i >= scripts.size()) {
                return Flux.just(
                        ChatResponse.builder()
                                .content(List.of(TextBlock.builder().text("").build()))
                                .build());
            }
            return scripts.get(i).get();
        }

        int callCount() {
            return idx.get();
        }

        /**
         * 展平模型收到的每条消息中的全部内容块。
         */
        List<ContentBlock> receivedBlocks() {
            return received.stream()
                    .flatMap(List::stream)
                    .flatMap(m -> m.getContent().stream())
                    .toList();
        }
    }

    /**
     * 返回固定结果的最小 {@code returnDirect} 工具。
     */
    private static final class ReturnDirectTool extends ToolBase {

        private final ToolResultBlock result;

        ReturnDirectTool(String name) {
            this(name, ToolResultBlock.text("sunny"));
        }

        ReturnDirectTool(String name, ToolResultBlock result) {
            super(
                    ToolBase.builder()
                            .name(name)
                            .description(name)
                            .inputSchema(schemaFor())
                            .returnDirect(true));
            this.result = result;
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.passthrough(getName()));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(result);
        }
    }

    private static Map<String, Object> schemaFor() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        Map<String, Object> q = new HashMap<>();
        q.put("type", "string");
        props.put("query", q);
        schema.put("properties", props);
        return schema;
    }

    private static ChatResponse toolUseResponse(String toolId, String toolName) {
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id(toolId)
                                        .name(toolName)
                                        .input(Map.of())
                                        .content("{}")
                                        .build()))
                .build();
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    @Test
    void returnDirectTurnProducesTextMessageEventsWithoutAguiAdaptation() {
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(toolUseResponse("tc1", "weather"))));
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new ReturnDirectTool("weather"));
        ReActAgent agent = ReActAgent.builder().name("asst").model(model).toolkit(toolkit).build();
        AguiAgentAdapter adapter = new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig());

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-rd")
                        .runId("run-rd")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "weather?")))
                        .tools(List.of())
                        .context(List.of())
                        .state(Map.of())
                        .forwardedProps(Map.of())
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();
        assertNotNull(events);

        int iStart = indexOf(events, AguiEvent.TextMessageStart.class);
        int iContent = indexOf(events, AguiEvent.TextMessageContent.class);
        int iEnd = indexOf(events, AguiEvent.TextMessageEnd.class);
        assertTrue(iStart >= 0, "TEXT_MESSAGE_START must be emitted for the closing message");
        assertTrue(iContent > iStart, "TEXT_MESSAGE_CONTENT must follow TEXT_MESSAGE_START");
        assertTrue(iEnd > iContent, "TEXT_MESSAGE_END must follow TEXT_MESSAGE_CONTENT");

        AguiEvent.TextMessageStart start = (AguiEvent.TextMessageStart) events.get(iStart);
        AguiEvent.TextMessageContent content = (AguiEvent.TextMessageContent) events.get(iContent);
        AguiEvent.TextMessageEnd end = (AguiEvent.TextMessageEnd) events.get(iEnd);
        assertEquals(start.messageId(), content.messageId());
        assertEquals(content.messageId(), end.messageId());
        assertEquals("sunny", content.delta(), "the final answer must be projected as text");
        assertEquals(1, model.callCount(), "returnDirect must skip the closing model call");
    }

    private static int indexOf(List<AguiEvent> events, Class<?> type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.isInstance(events.get(i))) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void clientReportedErrorReachesTheModelAsAFailedResult() {
        // 问题 #3307 实际报告的路径：客户端完成自己执行的工具调用，
        // 通过协议 `error` 字段报告失败。此前没有端到端覆盖，
        // 转换器测试停在 Msg 边界，因此转换器与运行时之间的
        // 回归可能漏检。
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(textResponse("the tool failed"))));
        Toolkit toolkit = new Toolkit();
        ReActAgent agent = ReActAgent.builder().name("asst").model(model).toolkit(toolkit).build();
        AguiAgentAdapter adapter = new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig());

        AguiMessage assistant =
                new AguiMessage(
                        "msg-a",
                        "assistant",
                        null,
                        List.of(new AguiToolCall("tc-1", new AguiFunctionCall("lookup", "{}"))),
                        null);
        AguiMessage tool =
                new AguiMessage("msg-t", "tool", null, null, "tc-1", "sandbox unavailable");

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-client-error")
                        .runId("run-client-error")
                        .messages(
                                List.of(
                                        AguiMessage.userMessage("msg-u", "weather?"),
                                        assistant,
                                        tool))
                        .tools(List.of())
                        .context(List.of())
                        .state(Map.of())
                        .forwardedProps(Map.of())
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();
        assertNotNull(events);

        ToolResultBlock seen =
                model.receivedBlocks().stream()
                        .filter(ToolResultBlock.class::isInstance)
                        .map(ToolResultBlock.class::cast)
                        .filter(b -> "tc-1".equals(b.getId()))
                        .findFirst()
                        .orElse(null);

        assertNotNull(seen, "the model must be called with the client's tool result");
        assertEquals(ToolResultState.ERROR, seen.getState());
        assertEquals(
                "sandbox unavailable",
                seen.getOutput().stream()
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .map(TextBlock::getText)
                        .findFirst()
                        .orElse(""));
    }

    @Test
    void failedToolResultIsNotProjectedAsTheFinalAnswer() {
        // 前端工具经协议 `error` 字段报告失败时，AguiMessageConverter
        // 生成的结构是携带原因的显式 ERROR 状态。
        // 该状态工具结果不得触发 returnDirect 短路，否则
        // 失败会直接作为智能体答案交回调用方，
        // 模型没有机会处理错误。
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("tc1", "weather")),
                                () -> Flux.just(textResponse("the weather tool failed"))));
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new ReturnDirectTool(
                        "weather", ToolResultBlock.error("tc1", "sandbox unavailable")));
        ReActAgent agent = ReActAgent.builder().name("asst").model(model).toolkit(toolkit).build();
        AguiAgentAdapter adapter = new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig());

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-rd-failed")
                        .runId("run-rd-failed")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "weather?")))
                        .tools(List.of())
                        .context(List.of())
                        .state(Map.of())
                        .forwardedProps(Map.of())
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();
        assertNotNull(events);

        assertEquals(2, model.callCount(), "a failed result must not skip the closing model call");
        AguiEvent.TextMessageContent content =
                (AguiEvent.TextMessageContent)
                        events.get(indexOf(events, AguiEvent.TextMessageContent.class));
        assertEquals("the weather tool failed", content.delta());
        assertTrue(
                events.stream()
                        .filter(AguiEvent.TextMessageContent.class::isInstance)
                        .map(AguiEvent.TextMessageContent.class::cast)
                        .noneMatch(e -> e.delta() != null && e.delta().contains("[ERROR]")),
                "the failure must never be projected as the final answer");
    }
}
