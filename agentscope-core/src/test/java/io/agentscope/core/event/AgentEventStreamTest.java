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
package io.agentscope.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentEventType} 与 {@code ReActAgent.replyStream(...)} 发出事件流的契约测试。
 *
 * <p>{@link JsonAliasRoundTrip} 套件测试 {@link AgentEventType} 声明的旧名称别名。
 *
 * <p>{@link StreamOrdering} 套件在智能体重设计完成前标记为 {@link Disabled}。它记录规范事件序列，使实现可直接加入断言而无需重新推导。
 */
class AgentEventStreamTest {

    @Nested
    @DisplayName("Legacy event-name aliases round-trip via Jackson")
    class JsonAliasRoundTrip {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("RUN_STARTED deserializes to AGENT_START and re-serializes as AGENT_START")
        void runStartedAlias() throws Exception {
            AgentEventType parsed = mapper.readValue("\"RUN_STARTED\"", AgentEventType.class);
            assertEquals(AgentEventType.AGENT_START, parsed);
            assertEquals("\"AGENT_START\"", mapper.writeValueAsString(parsed));
        }

        @Test
        @DisplayName("RUN_FINISHED → AGENT_END")
        void runFinishedAlias() throws Exception {
            AgentEventType parsed = mapper.readValue("\"RUN_FINISHED\"", AgentEventType.class);
            assertEquals(AgentEventType.AGENT_END, parsed);
            assertEquals("\"AGENT_END\"", mapper.writeValueAsString(parsed));
        }

        @Test
        @DisplayName("MODEL_CALL_STARTED / MODEL_CALL_ENDED aliases")
        void modelCallAliases() throws Exception {
            assertEquals(
                    AgentEventType.MODEL_CALL_START,
                    mapper.readValue("\"MODEL_CALL_STARTED\"", AgentEventType.class));
            assertEquals(
                    AgentEventType.MODEL_CALL_END,
                    mapper.readValue("\"MODEL_CALL_ENDED\"", AgentEventType.class));
        }

        @Test
        @DisplayName("BINARY_BLOCK_* aliases map to DATA_BLOCK_*")
        void binaryBlockAliases() throws Exception {
            assertEquals(
                    AgentEventType.DATA_BLOCK_START,
                    mapper.readValue("\"BINARY_BLOCK_START\"", AgentEventType.class));
            assertEquals(
                    AgentEventType.DATA_BLOCK_DELTA,
                    mapper.readValue("\"BINARY_BLOCK_DELTA\"", AgentEventType.class));
            assertEquals(
                    AgentEventType.DATA_BLOCK_END,
                    mapper.readValue("\"BINARY_BLOCK_END\"", AgentEventType.class));
        }

        @Test
        @DisplayName("TOOL_RESULT_BINARY_DELTA → TOOL_RESULT_DATA_DELTA")
        void toolResultBinaryDeltaAlias() throws Exception {
            assertEquals(
                    AgentEventType.TOOL_RESULT_DATA_DELTA,
                    mapper.readValue("\"TOOL_RESULT_BINARY_DELTA\"", AgentEventType.class));
        }

        @Test
        @DisplayName("Canonical names round-trip unchanged")
        void javaNativeNamesRoundTrip() throws Exception {
            for (AgentEventType type : AgentEventType.values()) {
                String json = mapper.writeValueAsString(type);
                assertEquals("\"" + type.getValue() + "\"", json);
                assertEquals(type, mapper.readValue(json, AgentEventType.class));
            }
        }

        @Test
        @DisplayName("fromValue rejects unknown strings")
        void fromValueRejectsUnknown() {
            IllegalArgumentException ex =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> AgentEventType.fromValue("NOT_A_TYPE"));
            assertTrue(ex.getMessage().contains("Unknown AgentEventType value"));
        }

        @Test
        @DisplayName("fromValue rejects null")
        void fromValueRejectsNull() {
            assertThrows(IllegalArgumentException.class, () -> AgentEventType.fromValue(null));
        }
    }

    @Nested
    @DisplayName("Tool result event metadata round-trips via Jackson")
    class ToolResultMetadataRoundTrip {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("ToolResultEndEvent serializes and deserializes metadata")
        void toolResultEndEventMetadataRoundTrip() throws Exception {
            Map<String, Object> metadata = Map.of("source", "meta_tool", "cost", 1);
            ToolResultEndEvent original =
                    new ToolResultEndEvent("reply-1", "tc-1", "search", ToolResultState.SUCCESS);
            original.withMetadata(metadata);

            String json = mapper.writeValueAsString(original);
            assertTrue(json.contains("\"metadata\""));
            assertTrue(json.contains("\"source\""));

            AgentEvent deserialized = mapper.readValue(json, AgentEvent.class);
            assertTrue(deserialized instanceof ToolResultEndEvent);
            assertEquals(metadata, deserialized.getMetadata());
        }

        @Test
        @DisplayName("ToolResultTextDeltaEvent serializes and deserializes metadata")
        void toolResultTextDeltaEventMetadataRoundTrip() throws Exception {
            Map<String, Object> metadata = Map.of("chunk", true);
            ToolResultTextDeltaEvent original =
                    new ToolResultTextDeltaEvent("reply-1", "tc-1", "search", "partial");
            original.withMetadata(metadata);

            String json = mapper.writeValueAsString(original);
            AgentEvent deserialized = mapper.readValue(json, AgentEvent.class);
            assertTrue(deserialized instanceof ToolResultTextDeltaEvent);
            assertEquals(metadata, deserialized.getMetadata());
        }

        @Test
        @DisplayName("ToolProgressEvent round-trips its type, content and metadata")
        void toolProgressEventRoundTrip() throws Exception {
            Map<String, Object> metadata = Map.of("chunk", true);
            ToolProgressEvent original =
                    new ToolProgressEvent(
                            "reply-1",
                            "tc-1",
                            "search",
                            TextBlock.builder().text("scanning 3 dirs").build());
            original.withMetadata(metadata);

            String json = mapper.writeValueAsString(original);
            assertTrue(json.contains("TOOL_PROGRESS"), json);

            AgentEvent deserialized = mapper.readValue(json, AgentEvent.class);
            assertTrue(
                    deserialized instanceof ToolProgressEvent,
                    "the subtype registry has to resolve progress events");
            ToolProgressEvent back = (ToolProgressEvent) deserialized;
            assertEquals("tc-1", back.getToolCallId());
            assertEquals("scanning 3 dirs", back.getText());
            assertEquals(metadata, back.getMetadata());
        }

        @Test
        @DisplayName("ToolResultDataDeltaEvent serializes and deserializes metadata")
        void toolResultDataDeltaEventMetadataRoundTrip() throws Exception {
            Map<String, Object> metadata = Map.of("binary", true);
            ToolResultDataDeltaEvent original =
                    new ToolResultDataDeltaEvent(
                            "reply-1", "tc-1", "search", TextBlock.builder().text("data").build());
            original.withMetadata(metadata);

            String json = mapper.writeValueAsString(original);
            AgentEvent deserialized = mapper.readValue(json, AgentEvent.class);
            assertTrue(deserialized instanceof ToolResultDataDeltaEvent);
            assertEquals(metadata, deserialized.getMetadata());
        }

        @Test
        @DisplayName("ToolResultStartEvent keeps metadata capability but emits empty by default")
        void toolResultStartEventEmptyMetadata() throws Exception {
            ToolResultStartEvent original = new ToolResultStartEvent("reply-1", "tc-1", "search");

            String json = mapper.writeValueAsString(original);
            assertTrue(json.contains("\"type\":\"TOOL_RESULT_START\""));

            AgentEvent deserialized = mapper.readValue(json, AgentEvent.class);
            assertTrue(deserialized instanceof ToolResultStartEvent);
            assertTrue(
                    deserialized.getMetadata() == null || deserialized.getMetadata().isEmpty(),
                    "ToolResultStartEvent should not carry result metadata before execution");
        }
    }

    @Nested
    @DisplayName("AgentEventType enumeration covers the full event surface")
    class EnumCoverage {

        @Test
        @DisplayName("All 25 supported event names resolve to a Java enum constant")
        void allPythonValuesResolve() {
            // 规范与旧版事件名称接口：
            String[] pythonValues = {
                "RUN_STARTED",
                "RUN_FINISHED",
                "MODEL_CALL_STARTED",
                "MODEL_CALL_ENDED",
                "TEXT_BLOCK_START",
                "TEXT_BLOCK_DELTA",
                "TEXT_BLOCK_END",
                "THINKING_BLOCK_START",
                "THINKING_BLOCK_DELTA",
                "THINKING_BLOCK_END",
                "BINARY_BLOCK_START",
                "BINARY_BLOCK_DELTA",
                "BINARY_BLOCK_END",
                "TOOL_CALL_START",
                "TOOL_CALL_DELTA",
                "TOOL_CALL_END",
                "TOOL_RESULT_START",
                "TOOL_RESULT_TEXT_DELTA",
                "TOOL_RESULT_BINARY_DELTA",
                "TOOL_RESULT_END",
                "EXCEED_MAX_ITERS",
                "REQUIRE_USER_CONFIRM",
                "REQUIRE_EXTERNAL_EXECUTION",
                "USER_CONFIRM_RESULT",
                "EXTERNAL_EXECUTION_RESULT",
            };
            for (String name : pythonValues) {
                AgentEventType resolved = AgentEventType.fromValue(name);
                assertNotNull(resolved, "EventType " + name + " must resolve");
            }
        }
    }

    @Nested
    @DisplayName("replyStream emits the canonical event order")
    @Disabled("Stage 7 lands the new Agent main class; this suite locks the stream contract.")
    class StreamOrdering {

        @Test
        @DisplayName(
                "Single-iteration reply: AGENT_START → MODEL_CALL_* → TEXT_BLOCK_* → AGENT_END")
        void singleIterationOrder() {
            // 给定 Agent.builder().model(...).build()
            // 并给定用户消息 "hello"
            // 执行 agent.streamEvents(userMsg).collectList().block()
            // 则按以下顺序发出事件类型：
            //         AGENT_START,
            //         MODEL_CALL_START, MODEL_CALL_END,
            //         TEXT_BLOCK_START, TEXT_BLOCK_DELTA(*), TEXT_BLOCK_END,
            //         AGENT_END
        }

        @Test
        @DisplayName("Thinking model emits THINKING_BLOCK_* before TEXT_BLOCK_*")
        void thinkingBeforeText() {
            // 给定支持思考的模型
            // 执行 agent.replyStream(userMsg)
            // 则 THINKING_BLOCK_START/DELTA/END 先于 TEXT_BLOCK_START
        }

        @Test
        @DisplayName("Tool call cycle: TOOL_CALL_* → TOOL_RESULT_* → second MODEL_CALL_*")
        void toolCallCycle() {
            // 给定启用工具的智能体和触发工具的用户提示词
            // 执行 replyStream(...)
            // 则顺序包含：
            //         MODEL_CALL_START, MODEL_CALL_END,
            //         TOOL_CALL_START, TOOL_CALL_DELTA(*), TOOL_CALL_END,
            //         TOOL_RESULT_START, TOOL_RESULT_TEXT_DELTA(*), TOOL_RESULT_END,
            //         MODEL_CALL_START, MODEL_CALL_END,
            //         TEXT_BLOCK_*, AGENT_END
        }

        @Test
        @DisplayName(
                "HITL: TOOL_CALL_END → REQUIRE_USER_CONFIRM → (await sink) → USER_CONFIRM_RESULT →"
                        + " TOOL_RESULT_*")
        void hitlReentry() {
            // 给定 checkPermissions 返回 ASK 的工具
            // 并将 HitlContextKey.KEY 绑定到 Sinks.Many<HitlResponse>
            // 执行 replyStream(...)
            // 则发出 REQUIRE_USER_CONFIRM 并暂停流
            // 随后执行 sink.tryEmitNext(allow)
            // 则发出 USER_CONFIRM_RESULT，随后为 TOOL_RESULT_*
            // 参见 docs/v2-design/RFC-002-event-stream-hitl.md
        }

        @Test
        @DisplayName("DataBlock turn: DATA_BLOCK_* fired for image/audio/video output")
        void dataBlockEmission() {
            // 给定返回图像块的模型
            // 执行 replyStream(...)
            // 则按序发出 DATA_BLOCK_START、DATA_BLOCK_DELTA(*)、DATA_BLOCK_END
        }

        @Test
        @DisplayName("Max-iters guard emits EXCEED_MAX_ITERS before AGENT_END")
        void exceedMaxIters() {
            // 给定 maxIters=1 的智能体和持续请求工具的模型
            // 执行 streamEvents(...)
            // 则先发出 EXCEED_MAX_ITERS，再发出 AGENT_END
        }

        @Test
        @DisplayName("Every stream begins with AGENT_START and ends with AGENT_END")
        void streamBoundaries() {
            // 对任意调用：首事件为 AGENT_START，末事件为 AGENT_END
        }
    }
}
