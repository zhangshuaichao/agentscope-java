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
package io.agentscope.harness.agent.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证多节点部署下 {@link RemoteFilesystem#grep} 的正确性。
 *
 * <p>本地 {@link WorkspaceIndex} 是尽力维护的索引，仅看到经过所属实例的写入。若节点 B 仅靠索引 grep，会静默遗漏节点 A 刚写入的文件；索引没有候选项时，实现必须回退到完整存储扫描。
 */
class RemoteFilesystemGrepTest {

    private static final RuntimeContext CTX = RuntimeContext.empty();

    @Test
    void grep_findsContentWrittenBySiblingInstance(@TempDir Path tmp) throws IOException {
        // 两个 RemoteFilesystem 实例共享同一底层存储，但各有独立
        // 工作区索引，准确模拟多节点场景。
        InMemoryStore store = new InMemoryStore();
        Path indexDirA = Files.createDirectories(tmp.resolve("nodeA"));
        Path indexDirB = Files.createDirectories(tmp.resolve("nodeB"));
        try (WorkspaceIndex indexA = WorkspaceIndex.open(indexDirA);
                WorkspaceIndex indexB = WorkspaceIndex.open(indexDirB)) {
            RemoteFilesystem nodeA =
                    new RemoteFilesystem(store, List.of("shared")).withIndex(indexA);
            RemoteFilesystem nodeB =
                    new RemoteFilesystem(store, List.of("shared")).withIndex(indexB);

            // 通过节点 A 写入并为新文件填充 A 的索引。RemoteFilesystem.write 不直接
            // 更新索引，索引填充由 WorkspaceManager 负责；此处模拟
            // 该操作，使 A 的索引看到自己的写入，B 的索引看不到。路径位于
            // {@code memory/} 下，属于索引跟踪的前缀范围。
            assertTrue(nodeA.write(CTX, "/memory/notes.md", "hello SENTINEL world").isSuccess());
            indexA.upsert("memory/notes.md", 0L, null);
            assertTrue(indexA.exists("memory/notes.md"));
            assertEquals(0, indexB.listByPrefix("").size(), "node B's index must be empty");

            // 节点 B 的 grep 仍必须通过存储扫描后备逻辑找到匹配；否则
            // B 的空索引会直接返回零匹配。
            GrepResult result = nodeB.grep(CTX, "SENTINEL", "/", null);
            assertTrue(result.isSuccess());
            assertEquals(
                    1,
                    result.matches().size(),
                    () ->
                            "node B grep must find sibling-written content via store fallback;"
                                    + " got: "
                                    + result.matches());
            assertEquals("/memory/notes.md", result.matches().get(0).path());
            assertEquals("hello SENTINEL world", result.matches().get(0).text());
        }
    }
}
