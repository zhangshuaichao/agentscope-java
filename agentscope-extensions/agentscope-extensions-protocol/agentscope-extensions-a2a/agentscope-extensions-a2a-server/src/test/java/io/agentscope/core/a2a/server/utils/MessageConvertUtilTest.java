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

package io.agentscope.core.a2a.server.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ChatUsage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MessageConvertUtil 单元测试。
 *
 * <p>覆盖 Agentscope {@link Msg} 到 A2A {@link Message} 的转换、{@link Msg} 内容块到 {@link Part} 列表的转换，以及转换中的元数据处理。
 */
@DisplayName("MessageConvertUtil Tests")
class MessageConvertUtilTest {

    private Msg msg;

    private String taskId;

    private String contextId;

    @BeforeEach
    void setUp() {
        msg = mock(Msg.class);
        taskId = "test-task-id";
        contextId = "test-context-id";
    }

    @Test
    @DisplayName("Should convert Msg to Message with empty metadata")
    void testConvertFromMsgToMessageWithEmptyMetadata() {
        when(msg.getMetadata()).thenReturn(null);
        when(msg.getContent()).thenReturn(mockContentBlocks());
        when(msg.getId()).thenReturn("msg-id");
        when(msg.getName()).thenReturn("test-agent");

        Message result = MessageConvertUtil.convertFromMsgToMessage(msg, taskId, contextId);

        assertNotNull(result);
        assertEquals(Message.Role.ROLE_AGENT, result.role());
        assertEquals(taskId, result.taskId());
        assertEquals(contextId, result.contextId());
        assertNotNull(result.metadata());
        assertTrue(result.metadata().isEmpty());
    }

    @Test
    @DisplayName("Should send Msg metadata objects as JSON values")
    void testConvertFromMsgToMessageWithObjectMetadata() {
        ChatUsage usage = ChatUsage.builder().inputTokens(3).outputTokens(5).build();
        Msg source =
                Msg.builder()
                        .id("msg-id")
                        .name("test-agent")
                        .content(List.of(TextBlock.builder().text("done").build()))
                        .metadata(Map.of(MessageMetadataKeys.CHAT_USAGE, usage))
                        .build();

        Message result = MessageConvertUtil.convertFromMsgToMessage(source, taskId, contextId);

        Map<?, ?> metadata = assertInstanceOf(Map.class, result.metadata().get("msg-id"));
        Map<?, ?> json = assertInstanceOf(Map.class, metadata.get(MessageMetadataKeys.CHAT_USAGE));
        assertEquals(3, json.get("inputTokens"));
        assertEquals(5, json.get("outputTokens"));
    }

    @Test
    @DisplayName("Should convert Msg to Message with metadata")
    void testConvertFromMsgToMessageWithMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key1", "value1");
        metadata.put("key2", 123);

        when(msg.getMetadata()).thenReturn(metadata);
        when(msg.getId()).thenReturn("msg-id");
        when(msg.getName()).thenReturn("test-agent");
        when(msg.getContent()).thenReturn(mockContentBlocks());

        Message result = MessageConvertUtil.convertFromMsgToMessage(msg, taskId, contextId);

        assertNotNull(result);
        assertEquals(Message.Role.ROLE_AGENT, result.role());
        assertEquals(taskId, result.taskId());
        assertEquals(contextId, result.contextId());
        assertNotNull(result.metadata());
        assertEquals(metadata, result.metadata().get("msg-id"));
    }

    @Test
    @DisplayName("Should convert content blocks to Parts")
    void testConvertFromContentBlocks() {
        when(msg.getContent()).thenReturn(mockContentBlocks());
        when(msg.getId()).thenReturn("msg-id");
        when(msg.getName()).thenReturn("test-agent");

        List<Part<?>> parts = MessageConvertUtil.convertFromContentBlocks(msg);

        assertNotNull(parts);
        // 注意：实际大小取决于 ContentBlockParserRouter 实现。
        // 若要控制输出，可在测试中模拟解析路由器。
    }

    @Test
    @DisplayName("Should have correct constant values")
    void testConstantValues() {
        assertEquals("_agentscope_msg_source", MessageConstants.SOURCE_NAME_METADATA_KEY);
        assertEquals("_agentscope_msg_id", MessageConstants.MSG_ID_METADATA_KEY);
        assertEquals("_agentscope_stream_chunk", MessageConstants.STREAM_CHUNK_METADATA_KEY);
    }

    @Test
    @DisplayName("Should mark converted parts as streaming chunks")
    void testConvertFromContentBlocksWithStreamingChunkMetadata() {
        Msg message =
                Msg.builder()
                        .id("msg-id")
                        .name("test-agent")
                        .content(List.of(TextBlock.builder().text("chunk").build()))
                        .build();

        List<Part<?>> parts = MessageConvertUtil.convertFromContentBlocks(message, true);

        assertEquals(1, parts.size());
        assertEquals(
                Boolean.TRUE,
                ((TextPart) parts.get(0))
                        .metadata()
                        .get(MessageConstants.STREAM_CHUNK_METADATA_KEY));
    }

    @Test
    @DisplayName("Should compact consecutive streaming chunks")
    void testCompactStreamingChunks() {
        Msg first =
                Msg.builder()
                        .id("msg-1")
                        .name("agent")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("Hel").build()))
                        .build();
        Msg second =
                Msg.builder()
                        .id("msg-1")
                        .name("agent")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("lo").build()))
                        .build();
        Msg third =
                Msg.builder()
                        .id("msg-2")
                        .name("agent")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text(" world").build()))
                        .build();

        List<Msg> result = MessageConvertUtil.compactStreamingChunks(List.of(first, second, third));

        assertEquals(2, result.size());
        assertEquals("Hello", result.get(0).getTextContent());
        assertEquals(" world", result.get(1).getTextContent());
    }

    @Test
    @DisplayName("Should compact empty and null streaming chunks")
    void testCompactStreamingChunksWithEmptyAndNullValues() {
        assertTrue(MessageConvertUtil.compactStreamingChunks(null).isEmpty());
        assertTrue(MessageConvertUtil.compactStreamingChunks(List.of()).isEmpty());

        Msg message =
                Msg.builder()
                        .id("msg-1")
                        .name("agent")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("content").build()))
                        .build();
        List<Msg> input = new java.util.ArrayList<>();
        input.add(null);
        input.add(message);

        List<Msg> result = MessageConvertUtil.compactStreamingChunks(input);

        assertEquals(1, result.size());
        assertEquals("content", result.get(0).getTextContent());
    }

    @Test
    @DisplayName("Should compact thinking chunks and preserve mixed block boundaries")
    void testCompactStreamingChunksWithThinkingAndMixedBlocks() {
        Msg first =
                Msg.builder()
                        .id("msg-1")
                        .name("agent")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(ThinkingBlock.builder().thinking("thin").build()))
                        .build();
        Msg second =
                Msg.builder()
                        .id("msg-1")
                        .name("agent")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ThinkingBlock.builder().thinking("king").build(),
                                        TextBlock.builder().text(" answer").build()))
                        .build();

        List<Msg> result = MessageConvertUtil.compactStreamingChunks(List.of(first, second));

        assertEquals(1, result.size());
        assertEquals(2, result.get(0).getContent().size());
        ThinkingBlock thinkingBlock =
                assertInstanceOf(ThinkingBlock.class, result.get(0).getContent().get(0));
        assertEquals("thinking", thinkingBlock.getThinking());
        assertEquals(" answer", ((TextBlock) result.get(0).getContent().get(1)).getText());
    }

    @Test
    @DisplayName("Should compact chunks with null content and null blocks")
    void testCompactStreamingChunksWithNullContentAndBlocks() {
        Msg first =
                Msg.builder()
                        .id("msg-1")
                        .name("agent")
                        .role(MsgRole.ASSISTANT)
                        .content((List<ContentBlock>) null)
                        .build();
        List<ContentBlock> content = new java.util.ArrayList<>();
        content.add(null);
        content.add(TextBlock.builder().text("tail").build());
        Msg second =
                Msg.builder()
                        .id("msg-1")
                        .name("agent")
                        .role(MsgRole.ASSISTANT)
                        .content(content)
                        .build();

        List<Msg> result = MessageConvertUtil.compactStreamingChunks(List.of(first, second));

        assertEquals(1, result.size());
        assertEquals(1, result.get(0).getContent().size());
        assertEquals("tail", ((TextBlock) result.get(0).getContent().get(0)).getText());
    }

    @Test
    @DisplayName("Should convert Message to Msgs with multiple parts from same message")
    void testConvertFromMessageToMsgsWithMultiplePartsFromSameMsg() {
        // 给定条件
        Message message = mock(Message.class);

        Map<String, Object> part1Metadata = new HashMap<>();
        Map<String, Object> part2Metadata = new HashMap<>();
        Map<String, Object> messageMetadata = new HashMap<>();
        Map<String, Object> msgMetadata = new HashMap<>();

        String msgId = "msg-1";
        String msgName = "test-agent";

        part1Metadata.put(MessageConstants.MSG_ID_METADATA_KEY, msgId);
        part1Metadata.put(MessageConstants.SOURCE_NAME_METADATA_KEY, msgName);
        part2Metadata.put(MessageConstants.MSG_ID_METADATA_KEY, msgId);
        part2Metadata.put(MessageConstants.SOURCE_NAME_METADATA_KEY, msgName);

        msgMetadata.put("key1", "value1");
        messageMetadata.put(msgId, msgMetadata);

        TextPart part1 = new TextPart("text1", part1Metadata);
        TextPart part2 = new TextPart("text2", part2Metadata);

        when(message.metadata()).thenReturn(messageMetadata);
        when(message.parts()).thenReturn(List.of(part1, part2));

        // 执行操作
        List<Msg> result = MessageConvertUtil.convertFromMessageToMsgs(message);

        // 验证结果
        assertNotNull(result);
        assertEquals(1, result.size());
        Msg msg = result.get(0);
        assertEquals(msgId, msg.getId());
        assertEquals(msgName, msg.getName());
        assertEquals(MsgRole.USER, msg.getRole());
        assertEquals(2, msg.getContent().size());
        assertInstanceOf(TextBlock.class, msg.getContent().get(0));
        assertInstanceOf(TextBlock.class, msg.getContent().get(1));
        assertEquals("text1", ((TextBlock) msg.getContent().get(0)).getText());
        assertEquals("text2", ((TextBlock) msg.getContent().get(1)).getText());
        assertEquals(msgMetadata, msg.getMetadata());
    }

    @Test
    @DisplayName("Should convert Message to Msgs with parts from different messages")
    void testConvertFromMessageToMsgsWithPartsFromDifferentMsgs() {
        // 给定条件
        Message message = mock(Message.class);

        Map<String, Object> part1Metadata = new HashMap<>();
        Map<String, Object> part2Metadata = new HashMap<>();

        String msgId1 = "msg-1";
        String msgName1 = "test-agent-1";
        String msgId2 = "msg-2";
        String msgName2 = "test-agent-2";

        part1Metadata.put(MessageConstants.MSG_ID_METADATA_KEY, msgId1);
        part1Metadata.put(MessageConstants.SOURCE_NAME_METADATA_KEY, msgName1);
        part2Metadata.put(MessageConstants.MSG_ID_METADATA_KEY, msgId2);
        part2Metadata.put(MessageConstants.SOURCE_NAME_METADATA_KEY, msgName2);

        TextPart part1 = new TextPart("text1", part1Metadata);
        TextPart part2 = new TextPart("text2", part2Metadata);

        when(message.metadata()).thenReturn(null);
        when(message.parts()).thenReturn(List.of(part1, part2));

        // 执行操作
        List<Msg> result = MessageConvertUtil.convertFromMessageToMsgs(message);

        // 验证结果
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(msgId1, result.get(0).getId());
        assertEquals(msgName1, result.get(0).getName());
        assertEquals(MsgRole.USER, result.get(0).getRole());
        assertEquals(1, result.get(0).getContent().size());
        assertInstanceOf(TextBlock.class, result.get(0).getContent().get(0));
        assertEquals("text1", ((TextBlock) result.get(0).getContent().get(0)).getText());

        assertEquals(msgId2, result.get(1).getId());
        assertEquals(msgName2, result.get(1).getName());
        assertEquals(MsgRole.USER, result.get(1).getRole());
        assertEquals(1, result.get(1).getContent().size());
        assertInstanceOf(TextBlock.class, result.get(1).getContent().get(0));
        assertEquals("text2", ((TextBlock) result.get(1).getContent().get(0)).getText());
    }

    @Test
    @DisplayName("Should restore Msg role from part metadata")
    void testConvertFromMessageToMsgsWithRoleMetadata() {
        // 给定条件
        Message message = mock(Message.class);

        Map<String, Object> partMetadata = new HashMap<>();
        String msgId = "assistant-msg";
        partMetadata.put(MessageConstants.MSG_ID_METADATA_KEY, msgId);
        partMetadata.put(MessageConstants.MSG_ROLE_METADATA_KEY, MsgRole.ASSISTANT.name());
        partMetadata.put(
                MessageConstants.BLOCK_TYPE_METADATA_KEY,
                MessageConstants.BlockContent.TYPE_THINKING);

        TextPart part = new TextPart("thinking content", partMetadata);

        when(message.metadata()).thenReturn(null);
        when(message.role()).thenReturn(Message.Role.ROLE_USER);
        when(message.parts()).thenReturn(List.of(part));

        // 执行操作
        List<Msg> result = MessageConvertUtil.convertFromMessageToMsgs(message);

        // 验证结果
        assertNotNull(result);
        assertEquals(1, result.size());
        Msg msg = result.get(0);
        assertEquals(msgId, msg.getId());
        assertEquals(MsgRole.ASSISTANT, msg.getRole());
        assertEquals(1, msg.getContent().size());
        assertInstanceOf(ThinkingBlock.class, msg.getContent().get(0));
        assertEquals("thinking content", ((ThinkingBlock) msg.getContent().get(0)).getThinking());
    }

    @Test
    @DisplayName("Should preserve roles when converting mixed history through A2A message")
    void testRoundTripMixedHistoryPreservesRoles() {
        Msg userMsg =
                Msg.builder()
                        .id("user-msg")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("please review").build()))
                        .build();
        Msg assistantMsg =
                Msg.builder()
                        .id("assistant-msg")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(ThinkingBlock.builder().thinking("checking diff").build()))
                        .build();

        Message message =
                MessageConvertUtil.convertFromMsgToMessage(
                        List.of(userMsg, assistantMsg), taskId, contextId);
        List<Msg> result = MessageConvertUtil.convertFromMessageToMsgs(message);

        assertEquals(Message.Role.ROLE_USER, message.role());
        assertEquals(2, result.size());
        assertEquals(MsgRole.USER, result.get(0).getRole());
        assertEquals(MsgRole.ASSISTANT, result.get(1).getRole());
        assertInstanceOf(ThinkingBlock.class, result.get(1).getContent().get(0));
        assertEquals(
                "checking diff", ((ThinkingBlock) result.get(1).getContent().get(0)).getThinking());
    }

    @Test
    @DisplayName("Should handle parts without msgId metadata")
    void testConvertFromMessageToMsgsWithoutMsgId() {
        // 给定条件
        Message message = mock(Message.class);

        Map<String, Object> partMetadata = new HashMap<>();

        // 元数据中没有 MSG_ID_METADATA_KEY。
        partMetadata.put(MessageConstants.SOURCE_NAME_METADATA_KEY, "test-agent");

        TextPart part = new TextPart("text content", partMetadata);

        when(message.metadata()).thenReturn(null);
        when(message.parts()).thenReturn(List.of(part));

        // 执行操作
        List<Msg> result = MessageConvertUtil.convertFromMessageToMsgs(message);

        // 验证结果
        assertNotNull(result);
        assertEquals(1, result.size());
        assertNotNull(result.get(0).getId()); // 应生成随机 UUID。
        assertEquals("test-agent", result.get(0).getName());
        assertEquals(MsgRole.USER, result.get(0).getRole());
        assertEquals(1, result.get(0).getContent().size());
        assertInstanceOf(TextBlock.class, result.get(0).getContent().get(0));
        assertEquals("text content", ((TextBlock) result.get(0).getContent().get(0)).getText());
    }

    @Test
    @DisplayName("Should handle parts without metadata")
    void testConvertFromMessageToMsgsWithoutPartMetadata() {
        // 给定条件
        Message message = mock(Message.class);

        TextPart part = new TextPart("text content", null);

        when(message.metadata()).thenReturn(null);
        when(message.parts()).thenReturn(List.of(part));

        // 执行操作
        List<Msg> result = MessageConvertUtil.convertFromMessageToMsgs(message);

        // 验证结果
        assertNotNull(result);
        assertEquals(1, result.size());
        assertNotNull(result.get(0).getId()); // 应生成随机 UUID。
        assertNull(result.get(0).getName()); // 没有元数据，应为 null。
        assertEquals(MsgRole.USER, result.get(0).getRole());
        assertEquals(1, result.get(0).getContent().size());
        assertInstanceOf(TextBlock.class, result.get(0).getContent().get(0));
        assertEquals("text content", ((TextBlock) result.get(0).getContent().get(0)).getText());
    }

    @Test
    @DisplayName("Should handle message without metadata")
    void testConvertFromMessageToMsgsWithoutMessageMetadata() {
        // 给定条件
        Message message = mock(Message.class);

        Map<String, Object> partMetadata = new HashMap<>();

        String msgId = "msg-1";
        String msgName = "test-agent";

        partMetadata.put(MessageConstants.MSG_ID_METADATA_KEY, msgId);
        partMetadata.put(MessageConstants.SOURCE_NAME_METADATA_KEY, msgName);

        TextPart part = new TextPart("text content", partMetadata);

        when(message.metadata()).thenReturn(null); // 没有消息元数据。
        when(message.parts()).thenReturn(List.of(part));

        // 执行操作
        List<Msg> result = MessageConvertUtil.convertFromMessageToMsgs(message);

        // 验证结果
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(msgId, result.get(0).getId());
        assertEquals(msgName, result.get(0).getName());
        assertEquals(MsgRole.USER, result.get(0).getRole());
        assertEquals(1, result.get(0).getContent().size());
        assertInstanceOf(TextBlock.class, result.get(0).getContent().get(0));
        assertEquals("text content", ((TextBlock) result.get(0).getContent().get(0)).getText());
        assertNotNull(result.get(0).getMetadata());
        assertTrue(result.get(0).getMetadata().isEmpty());
    }

    @Test
    @DisplayName("Should handle message with non-map metadata")
    void testConvertFromMessageToMsgsWithNonMapMetadata() {
        // 给定条件
        Message message = mock(Message.class);

        Map<String, Object> partMetadata = new HashMap<>();
        Map<String, Object> messageMetadata = new HashMap<>();

        String msgId = "msg-1";
        String msgName = "test-agent";

        partMetadata.put(MessageConstants.MSG_ID_METADATA_KEY, msgId);
        partMetadata.put(MessageConstants.SOURCE_NAME_METADATA_KEY, msgName);

        // 放入非映射类型元数据。
        messageMetadata.put(msgId, "not-a-map");

        TextPart part = new TextPart("text content", partMetadata);

        when(message.metadata()).thenReturn(messageMetadata);
        when(message.parts()).thenReturn(List.of(part));

        // 执行操作
        List<Msg> result = MessageConvertUtil.convertFromMessageToMsgs(message);

        // 验证结果
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(msgId, result.get(0).getId());
        assertEquals(msgName, result.get(0).getName());
        assertEquals(MsgRole.USER, result.get(0).getRole());
        assertEquals(1, result.get(0).getContent().size());
        assertInstanceOf(TextBlock.class, result.get(0).getContent().get(0));
        assertEquals("text content", ((TextBlock) result.get(0).getContent().get(0)).getText());
        assertNotNull(result.get(0).getMetadata());
        assertTrue(result.get(0).getMetadata().isEmpty());
    }

    @Test
    @DisplayName("Should handle parts with metadata but without source name key")
    void testConvertFromMessageToMsgsWithoutSourceNameInMetadata() {
        // 给定条件
        Message message = mock(Message.class);

        Map<String, Object> partMetadata = new HashMap<>();

        String msgId = "msg-1";

        // 元数据存在，但没有 SOURCE_NAME_METADATA_KEY。
        partMetadata.put(MessageConstants.MSG_ID_METADATA_KEY, msgId);

        TextPart part = new TextPart("text content", partMetadata);

        when(message.metadata()).thenReturn(null);
        when(message.parts()).thenReturn(List.of(part));

        // 执行操作
        List<Msg> result = MessageConvertUtil.convertFromMessageToMsgs(message);

        // 验证结果
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(msgId, result.get(0).getId());
        assertNull(result.get(0).getName()); // 元数据没有来源名称，应为 null。
        assertEquals(MsgRole.USER, result.get(0).getRole());
        assertEquals(1, result.get(0).getContent().size());
        assertInstanceOf(TextBlock.class, result.get(0).getContent().get(0));
        assertEquals("text content", ((TextBlock) result.get(0).getContent().get(0)).getText());
    }

    @Test
    @DisplayName("Should handle message with metadata but without corresponding msgId key")
    void testConvertFromMessageToMsgsWithoutCorrespondingMsgIdKey() {
        // 给定条件
        Message message = mock(Message.class);

        Map<String, Object> partMetadata = new HashMap<>();
        Map<String, Object> messageMetadata = new HashMap<>();

        String msgId = "msg-1";
        String msgName = "test-agent";

        partMetadata.put(MessageConstants.MSG_ID_METADATA_KEY, msgId);
        partMetadata.put(MessageConstants.SOURCE_NAME_METADATA_KEY, msgName);

        // 消息元数据存在，但无 msgId 对应条目。
        messageMetadata.put("different-msg-id", Map.of("key", "value"));

        TextPart part = new TextPart("text content", partMetadata);

        when(message.metadata()).thenReturn(messageMetadata);
        when(message.parts()).thenReturn(List.of(part));

        // 执行操作
        List<Msg> result = MessageConvertUtil.convertFromMessageToMsgs(message);

        // 验证结果
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(msgId, result.get(0).getId());
        assertEquals(msgName, result.get(0).getName());
        assertEquals(MsgRole.USER, result.get(0).getRole());
        assertEquals(1, result.get(0).getContent().size());
        assertInstanceOf(TextBlock.class, result.get(0).getContent().get(0));
        assertEquals("text content", ((TextBlock) result.get(0).getContent().get(0)).getText());
        assertNotNull(result.get(0).getMetadata());
        assertTrue(
                result.get(0)
                        .getMetadata()
                        .isEmpty()); // 没有对应元数据，应为空。
    }

    private List<ContentBlock> mockContentBlocks() {
        return List.of(
                TextBlock.builder().text("text1").build(),
                TextBlock.builder().text("text2").build());
    }
}
