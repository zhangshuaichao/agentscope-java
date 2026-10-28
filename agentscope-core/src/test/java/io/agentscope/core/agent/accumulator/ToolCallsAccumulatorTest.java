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
package io.agentscope.core.agent.accumulator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolUseBlock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ToolCallsAccumulator 单元测试。
 */
@DisplayName("ToolCallsAccumulator Tests")
class ToolCallsAccumulatorTest {

    private ToolCallsAccumulator accumulator;

    @BeforeEach
    void setUp() {
        accumulator = new ToolCallsAccumulator();
    }

    @Test
    @DisplayName("Should accumulate metadata from tool call chunks")
    void testAccumulateMetadata() {
        // 第一块带 thoughtSignature
        byte[] signature = "test-thought-signature".getBytes();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, signature);

        ToolUseBlock chunk1 =
                ToolUseBlock.builder().id("call_1").name("get_weather").metadata(metadata).build();

        // 第二块带参数，无元数据
        Map<String, Object> args = new HashMap<>();
        args.put("city", "Tokyo");

        ToolUseBlock chunk2 =
                ToolUseBlock.builder().id("call_1").name("get_weather").input(args).build();

        // 累积两个块
        accumulator.add(chunk1);
        accumulator.add(chunk2);

        // 构建并验证
        List<ToolUseBlock> result = accumulator.buildAllToolCalls();

        assertEquals(1, result.size());
        ToolUseBlock toolCall = result.get(0);

        assertEquals("call_1", toolCall.getId());
        assertEquals("get_weather", toolCall.getName());
        assertEquals("Tokyo", toolCall.getInput().get("city"));

        // 验证保留元数据
        assertNotNull(toolCall.getMetadata());
        assertTrue(toolCall.getMetadata().containsKey(ToolUseBlock.METADATA_THOUGHT_SIGNATURE));
        assertArrayEquals(
                signature,
                (byte[]) toolCall.getMetadata().get(ToolUseBlock.METADATA_THOUGHT_SIGNATURE));
    }

    @Test
    @DisplayName("Should accumulate without metadata")
    void testAccumulateWithoutMetadata() {
        Map<String, Object> args = new HashMap<>();
        args.put("query", "test");

        ToolUseBlock chunk = ToolUseBlock.builder().id("call_2").name("search").input(args).build();

        accumulator.add(chunk);

        List<ToolUseBlock> result = accumulator.buildAllToolCalls();

        assertEquals(1, result.size());
        ToolUseBlock toolCall = result.get(0);

        assertEquals("call_2", toolCall.getId());
        assertEquals("search", toolCall.getName());
        // 传给 builder 的元数据为 null，结果应为空
        assertTrue(toolCall.getMetadata().isEmpty());
    }

    @Test
    @DisplayName("Should handle parallel tool calls with different metadata")
    void testParallelToolCallsWithMetadata() {
        // 第一个工具调用带元数据
        byte[] sig1 = "sig-1".getBytes();
        Map<String, Object> metadata1 = new HashMap<>();
        metadata1.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, sig1);

        ToolUseBlock call1 =
                ToolUseBlock.builder()
                        .id("call_a")
                        .name("tool_a")
                        .input(Map.of("param", "value_a"))
                        .metadata(metadata1)
                        .build();

        // 第二个工具调用无元数据
        ToolUseBlock call2 =
                ToolUseBlock.builder()
                        .id("call_b")
                        .name("tool_b")
                        .input(Map.of("param", "value_b"))
                        .build();

        accumulator.add(call1);
        accumulator.add(call2);

        List<ToolUseBlock> result = accumulator.buildAllToolCalls();

        assertEquals(2, result.size());

        // 第一个调用应有元数据
        ToolUseBlock resultA =
                result.stream().filter(t -> "call_a".equals(t.getId())).findFirst().orElse(null);
        assertNotNull(resultA);
        assertTrue(resultA.getMetadata().containsKey(ToolUseBlock.METADATA_THOUGHT_SIGNATURE));

        // 第二个调用不应有元数据
        ToolUseBlock resultB =
                result.stream().filter(t -> "call_b".equals(t.getId())).findFirst().orElse(null);
        assertNotNull(resultB);
        assertTrue(resultB.getMetadata().isEmpty());
    }

    @Test
    @DisplayName("Should save raw content to content field in build()")
    void testBuildSavesRawContentToContentField() {
        // 模拟带原始内容的流式块
        ToolUseBlock chunk1 =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("get_weather")
                        .content("{\"city\":")
                        .build();

        ToolUseBlock chunk2 =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("__fragment__")
                        .content("\"Beijing\"}")
                        .build();

        accumulator.add(chunk1);
        accumulator.add(chunk2);

        List<ToolUseBlock> result = accumulator.buildAllToolCalls();

        assertEquals(1, result.size());
        ToolUseBlock toolCall = result.get(0);

        // 验证 content 包含累计原始内容
        assertEquals("{\"city\":\"Beijing\"}", toolCall.getContent());
        // 验证 input 从原始内容解析
        assertEquals("Beijing", toolCall.getInput().get("city"));
    }

    @Test
    @DisplayName("Should get accumulated tool call by ID")
    void testGetAccumulatedToolCallById() {
        ToolUseBlock call1 =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("weather")
                        .input(Map.of("city", "Tokyo"))
                        .build();

        ToolUseBlock call2 =
                ToolUseBlock.builder()
                        .id("call_2")
                        .name("calculator")
                        .input(Map.of("expr", "1+1"))
                        .build();

        accumulator.add(call1);
        accumulator.add(call2);

        // 按指定 ID 获取
        ToolUseBlock result1 = accumulator.getAccumulatedToolCall("call_1");
        assertNotNull(result1);
        assertEquals("call_1", result1.getId());
        assertEquals("weather", result1.getName());
        assertEquals("Tokyo", result1.getInput().get("city"));

        ToolUseBlock result2 = accumulator.getAccumulatedToolCall("call_2");
        assertNotNull(result2);
        assertEquals("call_2", result2.getId());
        assertEquals("calculator", result2.getName());
    }

    @Test
    @DisplayName("Should fallback to lastToolCallKey when ID is null or empty")
    void testGetAccumulatedToolCallFallbackToLastKey() {
        ToolUseBlock call =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("weather")
                        .input(Map.of("city", "Tokyo"))
                        .build();

        accumulator.add(call);

        // null ID 回退到最后一个键
        ToolUseBlock resultNull = accumulator.getAccumulatedToolCall(null);
        assertNotNull(resultNull);
        assertEquals("call_1", resultNull.getId());

        // 空 ID 回退到最后一个键
        ToolUseBlock resultEmpty = accumulator.getAccumulatedToolCall("");
        assertNotNull(resultEmpty);
        assertEquals("call_1", resultEmpty.getId());
    }

    @Test
    @DisplayName("Should return null when no tool calls accumulated")
    void testGetAccumulatedToolCallReturnsNullWhenEmpty() {
        ToolUseBlock result = accumulator.getAccumulatedToolCall("nonexistent");
        assertNull(result);

        ToolUseBlock resultNull = accumulator.getAccumulatedToolCall(null);
        assertNull(resultNull);
    }

    @Test
    @DisplayName("Should get all accumulated tool calls")
    void testGetAllAccumulatedToolCalls() {
        ToolUseBlock call1 =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("weather")
                        .input(Map.of("city", "Tokyo"))
                        .build();

        ToolUseBlock call2 =
                ToolUseBlock.builder()
                        .id("call_2")
                        .name("calculator")
                        .input(Map.of("expr", "1+1"))
                        .build();

        accumulator.add(call1);
        accumulator.add(call2);

        List<ToolUseBlock> result = accumulator.getAllAccumulatedToolCalls();

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(t -> "call_1".equals(t.getId())));
        assertTrue(result.stream().anyMatch(t -> "call_2".equals(t.getId())));
    }

    @Test
    @DisplayName("Should accumulate multiple parallel tool calls with streaming chunks")
    void testMultipleParallelToolCallsWithStreamingChunks() {
        // 模拟两个并行调用的交错流式块
        ToolUseBlock chunk1a =
                ToolUseBlock.builder().id("call_1").name("weather").content("{\"city\":").build();

        ToolUseBlock chunk2a =
                ToolUseBlock.builder()
                        .id("call_2")
                        .name("calculator")
                        .content("{\"expr\":")
                        .build();

        ToolUseBlock chunk1b =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("__fragment__")
                        .content("\"Beijing\"}")
                        .build();

        ToolUseBlock chunk2b =
                ToolUseBlock.builder()
                        .id("call_2")
                        .name("__fragment__")
                        .content("\"1+1\"}")
                        .build();

        // 以交错顺序加入块
        accumulator.add(chunk1a);
        accumulator.add(chunk2a);
        accumulator.add(chunk1b);
        accumulator.add(chunk2b);

        // 验证两个工具调用均正确累积
        ToolUseBlock result1 = accumulator.getAccumulatedToolCall("call_1");
        assertNotNull(result1);
        assertEquals("weather", result1.getName());
        assertEquals("{\"city\":\"Beijing\"}", result1.getContent());
        assertEquals("Beijing", result1.getInput().get("city"));

        ToolUseBlock result2 = accumulator.getAccumulatedToolCall("call_2");
        assertNotNull(result2);
        assertEquals("calculator", result2.getName());
        assertEquals("{\"expr\":\"1+1\"}", result2.getContent());
        assertEquals("1+1", result2.getInput().get("expr"));

        // 验证 getAllAccumulatedToolCalls 返回两者
        List<ToolUseBlock> allCalls = accumulator.getAllAccumulatedToolCalls();
        assertEquals(2, allCalls.size());
    }

    @Test
    @DisplayName("Should produce valid JSON content when streaming is interrupted mid-arguments")
    void testInterruptedStreamingProducesValidJsonContent() {
        // 模拟参数流中途被打断：
        // 模型正在输出未完成的查询字符串时被截断
        ToolUseBlock chunk1 =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("search")
                        .content("{\"query\": \"hello wor")
                        .build();

        accumulator.add(chunk1);

        List<ToolUseBlock> result = accumulator.buildAllToolCalls();
        assertEquals(1, result.size());

        ToolUseBlock toolCall = result.get(0);
        // 原始内容不是有效 JSON，content 应回退为 "{}"
        assertEquals("{}", toolCall.getContent());
        // 解析失败，input 应为空
        assertTrue(toolCall.getInput().isEmpty());
    }

    @Test
    @DisplayName("Should produce valid JSON content when multiple chunks are interrupted")
    void testInterruptedMultiChunkStreamingProducesValidJsonContent() {
        // 第一块开始参数
        ToolUseBlock chunk1 =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("get_weather")
                        .content("{\"city\":")
                        .build();

        // 第二块值不完整，流在此中断
        ToolUseBlock chunk2 =
                ToolUseBlock.builder().id("call_1").name("__fragment__").content("\"Bei").build();

        accumulator.add(chunk1);
        accumulator.add(chunk2);

        List<ToolUseBlock> result = accumulator.buildAllToolCalls();
        assertEquals(1, result.size());

        ToolUseBlock toolCall = result.get(0);
        assertEquals("{}", toolCall.getContent());
        assertTrue(toolCall.getInput().isEmpty());
    }

    @Test
    @DisplayName("Should handle non-object JSON content like arrays or null")
    void testNonObjectJsonContentFallsBackToEmpty() {
        ToolUseBlock chunk =
                ToolUseBlock.builder().id("call_1").name("tool").content("[1, 2, 3]").build();

        accumulator.add(chunk);

        List<ToolUseBlock> result = accumulator.buildAllToolCalls();
        assertEquals(1, result.size());
        // 数组不属于工具参数所需的 JSON 对象
        assertEquals("{}", result.get(0).getContent());
    }

    @Test
    @DisplayName("Should preserve valid content even when input was populated via merge")
    void testValidContentPreservedWithMergedInput() {
        // 首块 input 已由解析参数填充
        Map<String, Object> args = new HashMap<>();
        args.put("city", "Tokyo");
        ToolUseBlock chunk1 =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("weather")
                        .input(args)
                        .content("{\"city\": \"Tokyo\"}")
                        .build();

        accumulator.add(chunk1);

        List<ToolUseBlock> result = accumulator.buildAllToolCalls();
        assertEquals(1, result.size());

        ToolUseBlock toolCall = result.get(0);
        // 应保留有效 JSON 内容
        assertEquals("{\"city\": \"Tokyo\"}", toolCall.getContent());
        assertEquals("Tokyo", toolCall.getInput().get("city"));
    }

    @Test
    @DisplayName(
            "Should repair null input values from complete raw JSON (#768 HTML streaming case)")
    void testRepairNullInputValuesFromCompleteRawContent() {
        // 早期块有键但值为 null，代表不完整 JSON 的部分解析
        Map<String, Object> partialArgs = new HashMap<>();
        partialArgs.put("file_path", null);
        partialArgs.put("content", null);

        String html =
                "<html>\n  <body class=\"main\">\n    <p>Hello \"world\"</p>\n  </body>\n</html>";
        String completeJson =
                "{\"file_path\":\"index.html\",\"content\":"
                        + io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(html)
                        + "}";

        ToolUseBlock chunk1 =
                ToolUseBlock.builder()
                        .id("call_html")
                        .name("write_text_file")
                        .input(partialArgs)
                        .content(completeJson)
                        .build();

        accumulator.add(chunk1);

        List<ToolUseBlock> result = accumulator.buildAllToolCalls();
        assertEquals(1, result.size());
        ToolUseBlock toolCall = result.get(0);

        assertEquals("index.html", toolCall.getInput().get("file_path"));
        assertEquals(html, toolCall.getInput().get("content"));
    }

    @Test
    @DisplayName("Should not let null input from later chunks overwrite earlier non-null values")
    void testNullInputDoesNotOverwriteNonNullArgs() {
        Map<String, Object> goodArgs = new HashMap<>();
        goodArgs.put("file_path", "a.md");
        goodArgs.put("content", "hello");

        Map<String, Object> nullArgs = new HashMap<>();
        nullArgs.put("file_path", null);
        nullArgs.put("content", null);

        accumulator.add(
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("write_text_file")
                        .input(goodArgs)
                        .content("{\"file_path\":\"a.md\",\"content\":\"hello\"}")
                        .build());
        accumulator.add(
                ToolUseBlock.builder().id("call_1").name("__fragment__").input(nullArgs).build());

        List<ToolUseBlock> result = accumulator.buildAllToolCalls();
        assertEquals(1, result.size());
        assertEquals("a.md", result.get(0).getInput().get("file_path"));
        assertEquals("hello", result.get(0).getInput().get("content"));
    }

    @Test
    @DisplayName("Empty continuation name must not clobber a captured real name")
    void testEmptyContinuationNameDoesNotOverwriteRealName() {
        accumulator.add(
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("run_query")
                        .content("{\"q\": \"se")
                        .build());
        // OpenAI 风格续传增量常携带空名称
        accumulator.add(
                ToolUseBlock.builder().id("call_1").name("").content("lect * from t\"}").build());

        List<ToolUseBlock> result = accumulator.buildAllToolCalls();
        assertEquals(1, result.size());
        assertEquals(
                "run_query",
                result.get(0).getName(),
                "an empty continuation name must not overwrite the captured name");
    }

    @Test
    @DisplayName("Id-less empty-name chunk attaches to the pending call, not a new builder")
    void testIdlessEmptyNameChunkReusesPendingCallKey() {
        accumulator.add(
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("run_query")
                        .content("{\"q\": \"se")
                        .build());
        // 无 ID 且名称为空时，应经 lastToolCallKey 视为
        // call_1 的片段，而不是新建 name: 构建器
        accumulator.add(ToolUseBlock.builder().name("").content("lect * from t\"}").build());

        List<ToolUseBlock> result = accumulator.buildAllToolCalls();
        assertEquals(
                1,
                result.size(),
                "an empty-name chunk without id is a continuation of the pending call");
        assertEquals("run_query", result.get(0).getName());
        assertEquals(
                "{\"q\": \"select * from t\"}",
                result.get(0).getContent(),
                "the continuation content must merge into the pending call");
    }

    @Test
    @DisplayName("Should mark truncated raw arguments as malformed")
    void testInterruptedStreamingMarksMalformedArguments() {
        accumulator.add(
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("write_text_file")
                        .content("{\"file_path\": \"src/Main.ja")
                        .build());

        List<ToolUseBlock> result = accumulator.buildAllToolCalls();
        assertEquals(1, result.size());
        ToolUseBlock toolCall = result.get(0);

        assertEquals("{}", toolCall.getContent());
        assertTrue(
                toolCall.hasMalformedArguments(),
                "truncated arguments must carry the malformed marker");
        assertTrue(
                Boolean.TRUE.equals(
                        toolCall.getMetadata().get(ToolUseBlock.METADATA_MALFORMED_ARGUMENTS)));
    }

    @Test
    @DisplayName("Should not mark complete raw arguments as malformed")
    void testCompleteArgumentsNotMarkedMalformed() {
        accumulator.add(
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("write_text_file")
                        .content("{\"file_path\": \"a.md\"}")
                        .build());

        List<ToolUseBlock> result = accumulator.buildAllToolCalls();
        assertEquals(1, result.size());
        assertFalse(result.get(0).hasMalformedArguments());
    }

    @Test
    @DisplayName("Should not mark calls whose arguments arrive only via input merge")
    void testInputOnlyChunksNotMarkedMalformed() {
        accumulator.add(
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("weather")
                        .input(Map.of("city", "Tokyo"))
                        .build());

        List<ToolUseBlock> result = accumulator.buildAllToolCalls();
        assertEquals(1, result.size());
        assertFalse(result.get(0).hasMalformedArguments());
    }

    @Test
    @DisplayName("Should keep prior metadata when marking malformed arguments")
    void testExistingMetadataPreservedWhenMarkingMalformed() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, "sig".getBytes());

        accumulator.add(
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("tool")
                        .metadata(metadata)
                        .content("{\"partial\":")
                        .build());

        List<ToolUseBlock> result = accumulator.buildAllToolCalls();
        assertEquals(1, result.size());
        ToolUseBlock toolCall = result.get(0);

        assertTrue(toolCall.hasMalformedArguments());
        assertArrayEquals(
                "sig".getBytes(),
                (byte[]) toolCall.getMetadata().get(ToolUseBlock.METADATA_THOUGHT_SIGNATURE));
    }
}
