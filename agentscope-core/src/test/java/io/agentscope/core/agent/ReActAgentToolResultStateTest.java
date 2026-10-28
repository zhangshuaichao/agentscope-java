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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 问题 <a href="https://github.com/agentscope-ai/agentscope-java/issues/2745">#2745</a> 的端到端测试：{@code @Tool} 方法通过 {@link ToolResultBlock} 中的 {@link ToolResultState} 表达成功／失败，{@code determineToolResultState} 原样传播到 {@link ToolResultEndEvent}。
 * <p>覆盖两个方向，特别是成功工具返回看似错误的文本，不能仅靠文本启发式判断。
 */
@DisplayName("ReActAgent: tool result state comes from the tool, not from its text")
class ReActAgentToolResultStateTest {

    /**
     * 执行失败并通过结构化状态明确报告，与内置工具一致。
     */
    private static class FailingWriteTool {

        @Tool(name = "write_file", description = "Write a file (always fails in this stub)")
        public ToolResultBlock writeFile(
                @ToolParam(name = "path", description = "path") String path,
                @ToolParam(name = "content", description = "content") String content) {
            return ToolResultBlock.error("permission denied for " + path);
        }
    }

    /**
     * 成功但内容以 {@code "Error: "} 开头，例如读取记录旧错误的日志；对应 {@code FilesystemTool.readFile} 成功时返回原始文件内容。
     */
    private static class ContentEchoTool {

        @Tool(name = "read_file", description = "Read a file (stub returns fixed content)")
        public ToolResultBlock readFile(
                @ToolParam(name = "path", description = "path") String path) {
            return ToolResultBlock.success("Error: connection refused\n  at Foo.bar(Foo.java:1)");
        }
    }

    @Test
    @DisplayName("A structurally failing tool surfaces as ToolResultState.ERROR")
    void failingToolReportsError() {
        ToolResultEndEvent end = runSingleToolCall("write_file", new FailingWriteTool());
        assertEquals(
                ToolResultState.ERROR,
                end.getState(),
                "ToolResultBlock.error(...) must propagate to the emitted event");
    }

    @Test
    @DisplayName("A successful tool stays SUCCESS even when its output looks like an error")
    void successfulToolWithErrorLookingContentStaysSuccess() {
        ToolResultEndEvent end = runSingleToolCall("read_file", new ContentEchoTool());
        assertEquals(
                ToolResultState.SUCCESS,
                end.getState(),
                "content beginning with 'Error: ' must not be mistaken for a failed call");
    }

    // ── 辅助方法 ──

    /**
     * 驱动一轮推理调用 {@code toolName}，返回其结果事件。
     */
    private static ToolResultEndEvent runSingleToolCall(String toolName, Object tool) {
        List<Supplier<Flux<ChatResponse>>> steps =
                List.of(
                        () -> Flux.just(toolUseResponse("c1", toolName)),
                        () -> Flux.just(textResponse("done")));
        ScriptedModel model = new ScriptedModel(steps, new AtomicInteger(0));

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);

        ReActAgent agent =
                ReActAgent.builder()
                        .name("tester")
                        .sysPrompt("test")
                        .model(model)
                        .toolkit(toolkit)
                        .build();

        List<AgentEvent> events =
                agent.streamEvents(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("do it")
                                                .build()))
                        .collectList()
                        .block();
        assertNotNull(events);

        return events.stream()
                .filter(ToolResultEndEvent.class::isInstance)
                .map(ToolResultEndEvent.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ToolResultEndEvent found in: " + events));
    }

    private static ChatResponse toolUseResponse(String callId, String toolName) {
        return ChatResponse.builder()
                .id("msg-1")
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .name(toolName)
                                        .id(callId)
                                        .input(
                                                Map.of(
                                                        "path", "/tmp/test.txt",
                                                        "content", "hello"))
                                        .content(
                                                "{\"path\":\"/tmp/test.txt\",\"content\":\"hello\"}")
                                        .build()))
                .usage(new ChatUsage(10, 15, 25))
                .build();
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .id("msg-2")
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(new ChatUsage(5, 10, 15))
                .build();
    }

    /**
     * 重放预设响应序列的模型。
     */
    private static final class ScriptedModel extends ChatModelBase {

        private final List<Supplier<Flux<ChatResponse>>> steps;
        private final AtomicInteger idx;

        ScriptedModel(List<Supplier<Flux<ChatResponse>>> steps, AtomicInteger idx) {
            this.steps = steps;
            this.idx = idx;
        }

        @Override
        public String getModelName() {
            return "scripted";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int i = idx.getAndIncrement();
            if (i < steps.size()) {
                return steps.get(i).get();
            }
            return Flux.just(textResponse("fallback"));
        }
    }
}
