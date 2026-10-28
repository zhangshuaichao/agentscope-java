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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link Event} 与 {@link EventType} 的测试。
 */
class EventTest {

    @Test
    void testEventTypeValues() {
        // 验证预期事件类型均存在
        assertEquals(6, EventType.values().length);
        assertNotNull(EventType.valueOf("REASONING"));
        assertNotNull(EventType.valueOf("TOOL_RESULT"));
        assertNotNull(EventType.valueOf("HINT"));
        assertNotNull(EventType.valueOf("AGENT_RESULT"));
        assertNotNull(EventType.valueOf("SUMMARY"));
        assertNotNull(EventType.valueOf("ALL"));
    }

    @Test
    void testEventCreation() {
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("Test reasoning").build()))
                        .build();

        Event event = new Event(EventType.REASONING, msg, true);

        assertEquals(EventType.REASONING, event.getType());
        assertEquals(msg, event.getMessage());
        assertTrue(event.isLast());
        assertEquals(msg.getId(), event.getMessageId());
    }

    @Test
    void testEventIsLast() {
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("Chunk").build()))
                        .build();

        // 测试中间块，isLast=false
        Event intermediateEvent = new Event(EventType.REASONING, msg, false);
        assertFalse(intermediateEvent.isLast());

        // 测试最终块，isLast=true
        Event finalEvent = new Event(EventType.REASONING, msg, true);
        assertTrue(finalEvent.isLast());
    }

    @Test
    void testEventGetMessageId() {
        Msg msg =
                Msg.builder()
                        .id("test-msg-id-123")
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("Content").build()))
                        .build();

        Event event = new Event(EventType.TOOL_RESULT, msg, true);

        assertEquals("test-msg-id-123", event.getMessageId());
        assertEquals(msg.getId(), event.getMessageId());
    }

    @Test
    void testEventToString() {
        Msg msg =
                Msg.builder()
                        .id("msg-123")
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        TextBlock.builder().text("Content 1").build(),
                                        TextBlock.builder().text("Content 2").build()))
                        .build();

        Event event = new Event(EventType.REASONING, msg, true);
        String toString = event.toString();

        // 验证 toString 包含关键信息
        assertTrue(toString.contains("Event"));
        assertTrue(toString.contains("type=REASONING"));
        assertTrue(toString.contains("isLast=true"));
        assertTrue(toString.contains("msgId=msg-123"));
        assertTrue(toString.contains("contentBlocks=2"));
    }

    @Test
    void testEventWithEmptyContent() {
        Msg msg =
                Msg.builder()
                        .id("empty-msg")
                        .name("system")
                        .role(MsgRole.SYSTEM)
                        .content(List.of())
                        .build();

        Event event = new Event(EventType.HINT, msg, true);

        assertEquals(EventType.HINT, event.getType());
        assertEquals(0, event.getMessage().getContent().size());
        assertTrue(event.toString().contains("contentBlocks=0"));
    }

    @Test
    void testEventWithMultipleContentBlocks() {
        List<ContentBlock> content =
                List.of(
                        TextBlock.builder().text("Block 1").build(),
                        TextBlock.builder().text("Block 2").build(),
                        TextBlock.builder().text("Block 3").build());

        Msg msg = Msg.builder().name("assistant").role(MsgRole.ASSISTANT).content(content).build();

        Event event = new Event(EventType.SUMMARY, msg, false);

        assertEquals(3, event.getMessage().getContent().size());
        assertEquals(content, event.getMessage().getContent());
    }

    @Test
    void testEventDifferentTypes() {
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("Test").build()))
                        .build();

        // 测试每种事件类型
        Event reasoningEvent = new Event(EventType.REASONING, msg, true);
        assertEquals(EventType.REASONING, reasoningEvent.getType());

        Event toolResultEvent = new Event(EventType.TOOL_RESULT, msg, true);
        assertEquals(EventType.TOOL_RESULT, toolResultEvent.getType());

        Event hintEvent = new Event(EventType.HINT, msg, true);
        assertEquals(EventType.HINT, hintEvent.getType());

        Event agentResultEvent = new Event(EventType.AGENT_RESULT, msg, true);
        assertEquals(EventType.AGENT_RESULT, agentResultEvent.getType());

        Event summaryEvent = new Event(EventType.SUMMARY, msg, true);
        assertEquals(EventType.SUMMARY, summaryEvent.getType());
    }

    @Test
    void testStreamingScenario() {
        // 模拟使用相同消息 ID 的流式场景
        String messageId = "streaming-msg-123";

        // 第一块
        Msg chunk1 =
                Msg.builder()
                        .id(messageId)
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("First ").build()))
                        .build();
        Event event1 = new Event(EventType.REASONING, chunk1, false);

        assertFalse(event1.isLast());
        assertEquals(messageId, event1.getMessageId());

        // 第二块
        Msg chunk2 =
                Msg.builder()
                        .id(messageId)
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        TextBlock.builder().text("First ").build(),
                                        TextBlock.builder().text("second ").build()))
                        .build();
        Event event2 = new Event(EventType.REASONING, chunk2, false);

        assertFalse(event2.isLast());
        assertEquals(messageId, event2.getMessageId());

        // 最终块
        Msg chunk3 =
                Msg.builder()
                        .id(messageId)
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        TextBlock.builder().text("First ").build(),
                                        TextBlock.builder().text("second ").build(),
                                        TextBlock.builder().text("complete").build()))
                        .build();
        Event event3 = new Event(EventType.REASONING, chunk3, true);

        assertTrue(event3.isLast());
        assertEquals(messageId, event3.getMessageId());

        // 全部事件应有相同消息 ID
        assertEquals(event1.getMessageId(), event2.getMessageId());
        assertEquals(event2.getMessageId(), event3.getMessageId());
    }

    @Test
    void testEventJsonSerializationIsLastFieldName() {
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("Test").build()))
                        .build();

        Event event = new Event(EventType.REASONING, msg, false);
        String json = JsonUtils.getJsonCodec().toJson(event);

        // 回归背景：旧版 Jackson 属性配置曾按 JavaBeans 规则将 isLast 序列化为 last，
        // 因为 getter isLast() 的 is 前缀被移除。
        // 构造函数上的 @JsonProperty("isLast") 只影响反序列化。
        assertTrue(
                json.contains("\"isLast\""), "JSON should contain 'isLast' key, but got: " + json);
        assertFalse(
                json.contains("\"last\""), "JSON should NOT contain 'last' key, but got: " + json);

        // 验证往返转换，反序列化应保留 isLast
        Event deserialized = JsonUtils.getJsonCodec().fromJson(json, Event.class);
        assertEquals(event.isLast(), deserialized.isLast());
    }
}
