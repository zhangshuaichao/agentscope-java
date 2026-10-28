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
package io.agentscope.harness.agent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证 {@link MemoryConsolidator} 完全通过 {@link io.agentscope.harness.agent.filesystem.AbstractFilesystem} 读取每日日志并写入水位／MEMORY.md，从而与具体后端解耦。
 */
class MemoryConsolidatorFilesystemTest {

    private static void seedStoreFile(
            InMemoryStore store, List<String> ns, String path, String content, Instant modifiedAt) {
        Map<String, Object> value =
                Map.of(
                        "content",
                        content,
                        "encoding",
                        "utf-8",
                        "modified_at",
                        modifiedAt.toString());
        store.put(ns, path, value);
    }

    // ======================================================================
    // readWatermark：状态文件不存在时返回 EPOCH
    // ======================================================================

    @Test
    void readWatermark_returnsEpochWhenStateAbsent(@TempDir Path tmp) throws Exception {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("test-ns");
        RemoteFilesystem fs = new RemoteFilesystem(store, ns);
        try (WorkspaceManager wsm = new WorkspaceManager(tmp, fs)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, null);

            assertEquals(Instant.EPOCH, consolidator.readWatermark(RuntimeContext.empty()));
        }
    }

    // ======================================================================
    // readWatermark / writeWatermark 经文件系统往返读写
    // ======================================================================

    @Test
    void watermark_roundTripThroughFilesystem(@TempDir Path tmp) throws Exception {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("test-ns");
        RemoteFilesystem fs = new RemoteFilesystem(store, ns);
        try (WorkspaceManager wsm = new WorkspaceManager(tmp, fs)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, null);

            Instant ts = Instant.parse("2025-06-15T12:00:00Z");
            wsm.writeUtf8WorkspaceRelative(
                    RuntimeContext.empty(), MemoryConsolidator.STATE_REL_PATH, ts.toString());

            assertEquals(ts, consolidator.readWatermark(RuntimeContext.empty()));
        }
    }

    // ======================================================================
    // readWatermark：不访问本地文件，仅通过文件系统抽象操作
    // ======================================================================

    @Test
    void watermark_doesNotCreateLocalFile(@TempDir Path tmp) throws Exception {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("test-ns");
        RemoteFilesystem fs = new RemoteFilesystem(store, ns);
        try (WorkspaceManager wsm = new WorkspaceManager(tmp, fs)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, null);

            Instant ts = Instant.now();
            wsm.writeUtf8WorkspaceRelative(
                    RuntimeContext.empty(), MemoryConsolidator.STATE_REL_PATH, ts.toString());

            // 本地磁盘不得存在状态文件，文件只在存储中。
            Path localState = tmp.resolve("memory").resolve(MemoryConsolidator.STATE_FILE);
            assertFalse(
                    Files.exists(localState),
                    "state file should not be written to local disk when using RemoteFilesystem");

            // 但整合器能从存储正确读取。
            assertEquals(ts, consolidator.readWatermark(RuntimeContext.empty()));
        }
    }

    // ======================================================================
    // STATE_FILE 常量保持不变
    // ======================================================================

    @Test
    void stateFileRelPath_matchesConstant() {
        assertEquals("memory/" + MemoryConsolidator.STATE_FILE, MemoryConsolidator.STATE_REL_PATH);
    }

    // ======================================================================
    // 本地文件系统（无存储）：水位经 WorkspaceManager 使用本地磁盘
    // ======================================================================

    @Test
    void watermark_localFallback_whenNoFilesystem(@TempDir Path tmp) throws Exception {
        WorkspaceManager wsm = new WorkspaceManager(tmp);

        MemoryConsolidator consolidator = new MemoryConsolidator(wsm, null);

        // 无文件时返回 EPOCH。
        assertEquals(Instant.EPOCH, consolidator.readWatermark(RuntimeContext.empty()));

        // 通过 WorkspaceManager 写入，落到本地磁盘。
        Instant ts = Instant.parse("2025-03-10T09:00:00Z");
        wsm.writeUtf8WorkspaceRelative(
                RuntimeContext.empty(), MemoryConsolidator.STATE_REL_PATH, ts.toString());

        assertEquals(ts, consolidator.readWatermark(RuntimeContext.empty()));

        // 验证本地文件确实存在。
        Path localState = tmp.resolve("memory").resolve(MemoryConsolidator.STATE_FILE);
        assertTrue(
                Files.exists(localState),
                "state file should be written to local disk when no filesystem is configured");
    }
}
