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
package io.agentscope.harness.agent.memory.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * 测试聚合工具结果裁剪仅截短输出文本：重建内容块保留下游中间件与 HITL 运行时分支依赖的身份字段（元数据标记、执行状态、用量）。
 */
class ConversationCompactorPruneTest {

    private final ConversationCompactor compactor =
            new ConversationCompactor(mock(Model.class), mock(MemoryFlushManager.class));

    /**
     * 验证裁剪后工具结果保留元数据标记（驱逐、挂起）和执行状态，承载消息保留 ID、用量与时间戳。
     */
    @Test
    void pruneToolResults_preservesMetadataStateAndUsageWhenTrimming() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("agentscope.tool_result_evicted", Boolean.TRUE);
        metadata.put(ToolResultBlock.METADATA_SUSPENDED, Boolean.TRUE);
        metadata.put("custom_key", "custom_value");
        ToolResultBlock original =
                ToolResultBlock.builder()
                        .id("call-1")
                        .name("execute")
                        .output(TextBlock.builder().text("x".repeat(1_000)).build())
                        .metadata(metadata)
                        .state(ToolResultState.ERROR)
                        .build();
        Msg toolMsg =
                Msg.builder()
                        .id("msg-1")
                        .role(MsgRole.TOOL)
                        .content(original)
                        .metadata(Map.of("msg_meta", "kept"))
                        .timestamp("2026-09-28T10:00:00Z")
                        .usage(ChatUsage.builder().inputTokens(10).outputTokens(5).build())
                        .build();

        List<Msg> result =
                compactor.pruneToolResults(List.of(toolMsg), pruneConfig(100, 1, 0, Set.of()));

        ToolResultBlock pruned = (ToolResultBlock) result.get(0).getContent().get(0);
        String prunedText = ((TextBlock) pruned.getOutput().get(0)).getText();
        assertTrue(prunedText.contains("chars pruned"));
        assertTrue(prunedText.length() < 1_000);

        assertEquals(metadata, pruned.getMetadata());
        assertTrue(pruned.isSuspended());
        assertEquals(ToolResultState.ERROR, pruned.getState());
        assertEquals("call-1", pruned.getId());
        assertEquals("execute", pruned.getName());

        Msg rebuilt = result.get(0);
        assertEquals("msg-1", rebuilt.getId());
        assertEquals(MsgRole.TOOL, rebuilt.getRole());
        assertEquals(Map.of("msg_meta", "kept"), rebuilt.getMetadata());
        assertEquals("2026-09-28T10:00:00Z", rebuilt.getTimestamp());
        assertEquals(10, rebuilt.getUsage().getInputTokens());
        assertEquals(5, rebuilt.getUsage().getOutputTokens());
    }

    /**
     * 验证排除工具即使超长且不在保护范围内也绝不裁剪。
     */
    @Test
    void pruneToolResults_skipsExcludedTools() {
        Msg toolMsg =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.of(
                                        "call-2",
                                        "read_file",
                                        TextBlock.builder().text("x".repeat(1_000)).build()))
                        .build();
        List<Msg> messages = List.of(toolMsg);

        List<Msg> result =
                compactor.pruneToolResults(messages, pruneConfig(100, 1, 0, Set.of("read_file")));

        assertSame(messages, result);
    }

    /**
     * 验证可裁剪总量低于阈值的对话原样返回。
     */
    @Test
    void pruneToolResults_returnsOriginalWhenBelowMinimum() {
        Msg toolMsg =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.of(
                                        "call-3",
                                        "execute",
                                        TextBlock.builder().text("x".repeat(1_000)).build()))
                        .build();
        List<Msg> messages = List.of(toolMsg);

        List<Msg> result =
                compactor.pruneToolResults(
                        messages, pruneConfig(100, Integer.MAX_VALUE, 0, Set.of()));

        assertSame(messages, result);
    }

    /**
     * 验证无元数据或显式状态的块也能正常裁剪。
     */
    @Test
    void pruneToolResults_handlesBlockWithoutMetadataOrState() {
        Msg toolMsg =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.of(
                                        "call-4",
                                        "execute",
                                        TextBlock.builder().text("y".repeat(500)).build()))
                        .build();

        List<Msg> result =
                compactor.pruneToolResults(List.of(toolMsg), pruneConfig(100, 1, 0, Set.of()));

        ToolResultBlock pruned = (ToolResultBlock) result.get(0).getContent().get(0);
        assertTrue(pruned.getMetadata().isEmpty());
        assertEquals(ToolResultState.RUNNING, pruned.getState());
        assertTrue(((TextBlock) pruned.getOutput().get(0)).getText().contains("chars pruned"));
    }

    /**
     * 验证含多个工具结果的消息重建时仅替换超长块，未触及的块保留原引用。
     */
    @Test
    void pruneToolResults_multiBlockMessagePrunesOnlyOversizedBlock() {
        ToolResultBlock small =
                ToolResultBlock.builder()
                        .id("call-small")
                        .name("execute")
                        .output(TextBlock.builder().text("short output").build())
                        .metadata(Map.of("small_meta", "v"))
                        .state(ToolResultState.SUCCESS)
                        .build();
        ToolResultBlock large =
                ToolResultBlock.builder()
                        .id("call-large")
                        .name("execute")
                        .output(TextBlock.builder().text("x".repeat(1_000)).build())
                        .metadata(Map.of("large_meta", "v"))
                        .state(ToolResultState.ERROR)
                        .build();
        Msg toolMsg =
                Msg.builder().id("msg-5").role(MsgRole.TOOL).content(List.of(small, large)).build();

        List<Msg> result =
                compactor.pruneToolResults(List.of(toolMsg), pruneConfig(100, 1, 0, Set.of()));

        assertEquals(1, result.size());
        List<ContentBlock> blocks = result.get(0).getContent();
        assertEquals(2, blocks.size());
        assertSame(small, blocks.get(0));
        ToolResultBlock prunedLarge = (ToolResultBlock) blocks.get(1);
        assertTrue(((TextBlock) prunedLarge.getOutput().get(0)).getText().contains("chars pruned"));
        assertEquals(Map.of("large_meta", "v"), prunedLarge.getMetadata());
        assertEquals(ToolResultState.ERROR, prunedLarge.getState());
        assertEquals("call-large", prunedLarge.getId());
        assertEquals("msg-5", result.get(0).getId());
    }

    @Test
    void compactIfNeeded_protectsTheSameRecentOutputsWhenResultsAreBatched() {
        List<ContentBlock> blocks = largeResults();
        List<Msg> separate =
                blocks.stream()
                        .map(b -> Msg.builder().role(MsgRole.TOOL).content(b).build())
                        .toList();
        List<Msg> batched = List.of(Msg.builder().role(MsgRole.TOOL).content(blocks).build());
        CompactionConfig config = compactionConfig(pruneConfig(100, 1, 700, Set.of()));

        List<ContentBlock> separateOutput =
                compact(separate, config).stream()
                        .flatMap(msg -> msg.getContent().stream())
                        .toList();
        List<ContentBlock> batchedOutput = compact(batched, config).get(0).getContent();

        assertEquals(6, batchedOutput.size());
        for (int i = 0; i < blocks.size(); i++) {
            assertEquals(
                    ((TextBlock) ((ToolResultBlock) separateOutput.get(i)).getOutput().get(0))
                            .getText(),
                    ((TextBlock) ((ToolResultBlock) batchedOutput.get(i)).getOutput().get(0))
                            .getText(),
                    "result " + i);
        }
        assertSame(blocks.get(4), batchedOutput.get(4));
        assertSame(blocks.get(5), batchedOutput.get(5));
    }

    @Test
    void compactIfNeeded_doesNotMultiplyPrunableTokensByResultsPerMessage() {
        List<Msg> messages =
                List.of(Msg.builder().role(MsgRole.TOOL).content(largeResults()).build());
        CompactionConfig config = compactionConfig(pruneConfig(100, 3_000, 0, Set.of()));

        // 6 个各 1000 字符的输出约为 2400 个词元，低于阈值。
        assertTrue(
                compactor
                        .compactIfNeeded(null, messages, config, "agent", "session")
                        .block()
                        .isEmpty());
    }

    private static List<ContentBlock> largeResults() {
        return IntStream.range(0, 6)
                .<ContentBlock>mapToObj(
                        i ->
                                ToolResultBlock.of(
                                        "call-" + i,
                                        "execute",
                                        TextBlock.builder().text("x".repeat(1_000)).build()))
                .toList();
    }

    private static CompactionConfig compactionConfig(CompactionConfig.PruneConfig prune) {
        return CompactionConfig.builder()
                .triggerMessages(0)
                .triggerTokens(Integer.MAX_VALUE)
                .prune(prune)
                .build();
    }

    private List<Msg> compact(List<Msg> messages, CompactionConfig config) {
        return compactor
                .compactIfNeeded(null, messages, config, "agent", "session")
                .block()
                .orElse(messages);
    }

    /**
     * 创建裁剪配置：maxOutputChars、minimumTokens、protectTokens、excludedTools。
     */
    private static CompactionConfig.PruneConfig pruneConfig(
            int maxOutputChars, int minimumTokens, int protectTokens, Set<String> excludedTools) {
        return CompactionConfig.PruneConfig.builder()
                .maxOutputChars(maxOutputChars)
                .minimumTokens(minimumTokens)
                .protectTokens(protectTokens)
                .excludedTools(excludedTools)
                .build();
    }
}
