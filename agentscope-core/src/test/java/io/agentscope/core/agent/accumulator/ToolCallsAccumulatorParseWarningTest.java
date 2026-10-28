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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * ToolCallsAccumulator 非法 JSON 警告回归测试。
 * <p>流式中间 build() 常看到不完整 JSON 前缀，必须静默；只有最终 {@code buildAllToolCalls()} 边界可发出一次脱敏警告，不含原始参数和堆栈。
 */
@DisplayName("ToolCallsAccumulator malformed-JSON warning behavior")
class ToolCallsAccumulatorParseWarningTest {

    private final ToolCallsAccumulator accumulator = new ToolCallsAccumulator();
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(ToolCallsAccumulator.class);
        // 此处固定日志等级而非依赖 logback-test.xml，
        // 断言要求 WARN 到达 appender，避免将来 XML 等级收紧
        // 导致测试静默失效。
        logger.setLevel(Level.WARN);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        // null 恢复继承的有效等级
        logger.setLevel(null);
    }

    private List<ILoggingEvent> warnings() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("Valid multi-fragment tool call emits no warning, including per-fragment builds")
    void validMultiFragmentEmitsNoWarning() {
        accumulator.add(
                ToolUseBlock.builder().id("call-1").name("get_weather").content("{\"ci").build());

        // 流式钩子分片构建看到不完整 JSON 前缀，
        // 解析暂时失败；后续补全后，最终阶段不得
        // 再发出已过期的失败警告
        assertNotNull(accumulator.getAccumulatedToolCall("call-1"));

        accumulator.add(
                ToolUseBlock.builder().name("__fragment__").content("ty\": \"Shanghai\"}").build());

        ToolUseBlock result = accumulator.buildAllToolCalls().get(0);
        assertEquals("{\"city\": \"Shanghai\"}", result.getContent());
        assertTrue(warnings().isEmpty(), "expected no warnings, got: " + warnings());
    }

    @Test
    @DisplayName("Truncated arguments emit exactly one sanitized warning at finalization")
    void truncatedArgumentsEmitOneSanitizedWarning() {
        String secretValue = "s3cr3t-credential";
        accumulator.add(
                ToolUseBlock.builder()
                        .id("call-2")
                        .name("run_query")
                        .content("{\"token\": \"" + secretValue)
                        .build());
        accumulator.add(
                ToolUseBlock.builder().name("__fragment__").content("\", \"q\": 1").build());

        // 反复构建不完整前缀不应刷屏警告
        accumulator.getAccumulatedToolCall("call-2");
        accumulator.getAccumulatedToolCall("call-2");

        ToolUseBlock result = accumulator.buildAllToolCalls().get(0);
        // 格式错误的原始内容不得作为内容暴露
        assertEquals("{}", result.getContent());

        List<ILoggingEvent> warns = warnings();
        assertEquals(1, warns.size(), "expected exactly one warning, got: " + warns);
        String message = warns.get(0).getFormattedMessage();
        assertTrue(message.contains("run_query"), "warning should name the tool: " + message);
        // 原始参数可能含凭据或个人信息，不得记录到日志
        assertTrue(!message.contains(secretValue), "warning leaked raw arguments: " + message);
        assertTrue(!message.contains("token"), "warning leaked argument keys: " + message);
        // 不包含堆栈
        assertNull(warns.get(0).getThrowableProxy());
    }

    @Test
    @DisplayName("Truncated call with no mid-stream builds warns on the first finalization")
    void truncatedWithoutIntermediateBuildsWarnsOnFirstFinalization() {
        // 问题 #2841 场景：只添加片段，流式期间不构建
        accumulator.add(
                ToolUseBlock.builder()
                        .id("call-3")
                        .name("run_query")
                        .content("{\"q\": \"sel")
                        .build());
        accumulator.add(
                ToolUseBlock.builder().name("__fragment__").content("ect * from t\"").build());

        ToolUseBlock result = accumulator.buildAllToolCalls().get(0);
        assertEquals("{}", result.getContent());

        List<ILoggingEvent> warns = warnings();
        assertEquals(1, warns.size(), "expected exactly one warning, got: " + warns);
        String message = warns.get(0).getFormattedMessage();
        assertTrue(message.contains("run_query"), "warning should name the tool: " + message);
        assertTrue(!message.contains("select"), "warning leaked raw arguments: " + message);
        assertNull(warns.get(0).getThrowableProxy());

        // 重复最终构建保持静默，每个工具调用只警告一次
        accumulator.buildAllToolCalls();
        assertEquals(1, warnings().size(), "warning repeated on second finalization");
    }

    @Test
    @DisplayName("Empty-string name falls back to the tool id in the parse warning")
    void emptyStringNameFallsBackToToolId() {
        // OpenAI 风格续传增量在带 ID 首块中常使用空名称，
        // 警告仍须能关联调用，因此回退到 ID，
        // 而不是显示空字符串。
        accumulator.add(
                ToolUseBlock.builder()
                        .id("call-empty-name")
                        .name("")
                        .content("{\"q\": \"sel")
                        .build());
        accumulator.add(
                ToolUseBlock.builder().name("__fragment__").content("ect * from t\"").build());

        ToolUseBlock result = accumulator.buildAllToolCalls().get(0);
        assertEquals("{}", result.getContent());

        List<ILoggingEvent> warns = warnings();
        assertEquals(1, warns.size(), "expected exactly one warning, got: " + warns);
        String message = warns.get(0).getFormattedMessage();
        assertTrue(
                message.contains("call-empty-name"),
                "warning must fall back to the tool id for an empty name: " + message);
        assertTrue(
                !message.contains("Tool call ''"),
                "warning must not render the empty name: " + message);
        assertNull(warns.get(0).getThrowableProxy());
    }

    @Test
    @DisplayName("JSON null arguments are marked malformed and warned")
    void jsonNullArgumentsAreMarkedAndWarned() {
        accumulator.add(
                ToolUseBlock.builder()
                        .id("call-json-null")
                        .name("run_query")
                        .content("null")
                        .build());

        ToolUseBlock result = accumulator.buildAllToolCalls().get(0);
        assertEquals("{}", result.getContent());
        assertTrue(result.hasMalformedArguments());

        List<ILoggingEvent> warns = warnings();
        assertEquals(1, warns.size(), "expected exactly one warning, got: " + warns);
        String message = warns.get(0).getFormattedMessage();
        assertTrue(message.contains("run_query"), "warning should name the tool: " + message);
        assertTrue(message.contains("call-json-null"), "warning should name the call: " + message);
        assertTrue(message.contains("NotAJsonObject"), "warning should name the cause: " + message);
    }

    @Test
    @DisplayName("Parallel calls with the same tool name remain distinguishable")
    void parallelCallsWithSameNameRemainDistinguishable() {
        accumulator.add(
                ToolUseBlock.builder()
                        .id("call-a")
                        .name("run_query")
                        .content("{\"q\": \"a")
                        .build());
        accumulator.add(
                ToolUseBlock.builder()
                        .id("call-b")
                        .name("run_query")
                        .content("{\"q\": \"b")
                        .build());

        List<ToolUseBlock> result = accumulator.buildAllToolCalls();
        assertEquals(2, result.size());

        List<ILoggingEvent> warns = warnings();
        assertEquals(2, warns.size(), "expected one warning per call, got: " + warns);
        assertTrue(
                warns.get(0).getFormattedMessage().contains("call-a")
                        && warns.get(1).getFormattedMessage().contains("call-b"),
                "warnings should identify both calls: " + warns);
    }
}
