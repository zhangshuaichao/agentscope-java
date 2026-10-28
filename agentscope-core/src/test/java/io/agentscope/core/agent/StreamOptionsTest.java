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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link StreamOptions} 的测试。
 */
class StreamOptionsTest {

    @Test
    void testDefaults() {
        StreamOptions options = StreamOptions.defaults();

        // 根据当前实现，默认应包含 ALL 事件类型
        assertTrue(options.shouldStream(EventType.REASONING));
        assertTrue(options.shouldStream(EventType.TOOL_RESULT));
        assertTrue(options.shouldStream(EventType.HINT));
        assertTrue(options.shouldStream(EventType.SUMMARY));
        assertTrue(options.shouldStream(EventType.AGENT_RESULT));

        // 默认应为增量模式
        assertTrue(options.isIncremental());

        // 默认同时包含推理块和推理结果
        assertTrue(options.isIncludeReasoningChunk());
        assertTrue(options.isIncludeReasoningResult());

        // 便捷辅助方法应遵循默认值
        assertTrue(options.shouldIncludeReasoningEmission(true)); // 增量块
        assertTrue(options.shouldIncludeReasoningEmission(false)); // 结果
    }

    @Test
    void testBuilderEventTypes() {
        // 测试单事件类型
        StreamOptions options = StreamOptions.builder().eventTypes(EventType.REASONING).build();

        assertTrue(options.shouldStream(EventType.REASONING));
        assertFalse(options.shouldStream(EventType.TOOL_RESULT));
        assertFalse(options.shouldStream(EventType.HINT));
        assertFalse(options.shouldStream(EventType.SUMMARY));
        assertFalse(options.shouldStream(EventType.AGENT_RESULT));
    }

    @Test
    void testBuilderMultipleEventTypes() {
        // 测试多事件类型
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING, EventType.TOOL_RESULT)
                        .build();

        assertTrue(options.shouldStream(EventType.REASONING));
        assertTrue(options.shouldStream(EventType.TOOL_RESULT));
        assertFalse(options.shouldStream(EventType.HINT));
        assertFalse(options.shouldStream(EventType.SUMMARY));
        assertFalse(options.shouldStream(EventType.AGENT_RESULT));
    }

    @Test
    void testBuilderAllEventTypes() {
        // 测试 ALL 事件类型
        StreamOptions options = StreamOptions.builder().eventTypes(EventType.ALL).build();

        assertTrue(options.shouldStream(EventType.REASONING));
        assertTrue(options.shouldStream(EventType.TOOL_RESULT));
        assertTrue(options.shouldStream(EventType.HINT));
        assertTrue(options.shouldStream(EventType.SUMMARY));
        assertTrue(options.shouldStream(EventType.AGENT_RESULT));
    }

    @Test
    void testBuilderIncrementalMode() {
        // 测试增量模式 true
        StreamOptions incrementalOptions = StreamOptions.builder().incremental(true).build();
        assertTrue(incrementalOptions.isIncremental());

        // 测试累计模式 false
        StreamOptions cumulativeOptions = StreamOptions.builder().incremental(false).build();
        assertFalse(cumulativeOptions.isIncremental());
    }

    @Test
    void testGetEventTypes() {
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING, EventType.TOOL_RESULT)
                        .build();

        assertEquals(2, options.getEventTypes().size());
        assertTrue(options.getEventTypes().contains(EventType.REASONING));
        assertTrue(options.getEventTypes().contains(EventType.TOOL_RESULT));
    }

    @Test
    void testCompleteConfiguration() {
        // 组合测试全部配置选项
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING, EventType.TOOL_RESULT, EventType.HINT)
                        .incremental(false)
                        .build();

        assertTrue(options.shouldStream(EventType.REASONING));
        assertTrue(options.shouldStream(EventType.TOOL_RESULT));
        assertTrue(options.shouldStream(EventType.HINT));
        assertFalse(options.shouldStream(EventType.SUMMARY));
        assertFalse(options.shouldStream(EventType.AGENT_RESULT));
        assertFalse(options.isIncremental());
    }

    @Test
    void testFilteringByEventType() {
        // 测试 shouldStream 正确过滤
        StreamOptions reasoningOnly =
                StreamOptions.builder().eventTypes(EventType.REASONING).build();

        assertTrue(reasoningOnly.shouldStream(EventType.REASONING));
        assertFalse(reasoningOnly.shouldStream(EventType.TOOL_RESULT));
        assertFalse(reasoningOnly.shouldStream(EventType.HINT));
        assertFalse(reasoningOnly.shouldStream(EventType.SUMMARY));
        assertFalse(reasoningOnly.shouldStream(EventType.AGENT_RESULT));
    }

    @Test
    void testReasoningChunkAndResultFlags_DefaultsTrue() {
        StreamOptions options = StreamOptions.builder().eventTypes(EventType.REASONING).build();

        assertTrue(options.isIncludeReasoningChunk());
        assertTrue(options.isIncludeReasoningResult());

        assertTrue(options.shouldIncludeReasoningEmission(true)); // 增量块
        assertTrue(options.shouldIncludeReasoningEmission(false)); // 结果
    }

    @Test
    void testReasoningChunkDisabled_ResultEnabled() {
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING)
                        .includeReasoningChunk(false)
                        .includeReasoningResult(true)
                        .build();

        assertFalse(options.isIncludeReasoningChunk());
        assertTrue(options.isIncludeReasoningResult());

        assertFalse(options.shouldIncludeReasoningEmission(true)); // 块被过滤
        assertTrue(options.shouldIncludeReasoningEmission(false)); // 结果被允许
    }

    @Test
    void testReasoningChunkEnabled_ResultDisabled() {
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING)
                        .includeReasoningChunk(true)
                        .includeReasoningResult(false)
                        .build();

        assertTrue(options.isIncludeReasoningChunk());
        assertFalse(options.isIncludeReasoningResult());

        assertTrue(options.shouldIncludeReasoningEmission(true)); // 块被允许
        assertFalse(options.shouldIncludeReasoningEmission(false)); // 结果被过滤
    }

    @Test
    void testReasoningChunkAndResultBothDisabled() {
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING)
                        .includeReasoningChunk(false)
                        .includeReasoningResult(false)
                        .build();

        assertFalse(options.isIncludeReasoningChunk());
        assertFalse(options.isIncludeReasoningResult());

        assertFalse(options.shouldIncludeReasoningEmission(true)); // 块被过滤
        assertFalse(options.shouldIncludeReasoningEmission(false)); // 结果被过滤
    }

    @Test
    void testActingChunk_DefaultsTrue() {
        StreamOptions options = StreamOptions.builder().eventTypes(EventType.TOOL_RESULT).build();

        assertTrue(options.isIncludeActingChunk());
    }

    @Test
    void testActingChunk_SetToFalse() {
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.TOOL_RESULT)
                        .includeActingChunk(false)
                        .build();

        assertFalse(options.isIncludeActingChunk());
    }

    @Test
    void testActingChunk_SetToTrue() {
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.TOOL_RESULT)
                        .includeActingChunk(true)
                        .build();

        assertTrue(options.isIncludeActingChunk());
    }

    @Test
    void testActingChunk_CombinedWithReasoningFlags() {
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.ALL)
                        .includeReasoningChunk(false)
                        .includeReasoningResult(true)
                        .includeActingChunk(false)
                        .build();

        assertFalse(options.isIncludeReasoningChunk());
        assertTrue(options.isIncludeReasoningResult());
        assertFalse(options.isIncludeActingChunk());
    }

    @Test
    void testDefaults_IncludesActingChunk() {
        StreamOptions options = StreamOptions.defaults();

        assertTrue(options.isIncludeActingChunk());
    }
}
