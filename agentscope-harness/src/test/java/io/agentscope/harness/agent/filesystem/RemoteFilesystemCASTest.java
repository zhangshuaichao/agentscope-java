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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 验证 {@link RemoteFilesystem} 写入受 CAS 保护：
 *
 * <ul>
 *   <li>{@code write} 为不存在时原子创建，重复写入失败但不丢数据；
 *   <li>{@code edit} 遇版本冲突重试，可应对并发编辑而不丢失更新。
 * </ul>
 *
 * <p>{@code uploadFiles} 有意采用最后写入者获胜语义，供会话镜像、审计日志轮换和 {@code WorkspaceManager.writeUtf8WorkspaceRelative} 推送快照使用。此契约由 {@link #uploadFiles_overwritesExistingFile_lastWriteWins} 和 {@link #uploadFiles_repeatedOnSamePath_returnsSuccessEachTime} 覆盖。
 */
class RemoteFilesystemCASTest {

    private static final RuntimeContext CTX = RuntimeContext.empty();

    private RemoteFilesystem newFs(InMemoryStore store) {
        return new RemoteFilesystem(store, List.of("test"));
    }

    @Test
    void edit_rejectsNullNewString_andEmptyOldString() {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem fs = newFs(store);
        assertTrue(fs.write(CTX, "/f.txt", "Hello World").isSuccess());

        // null 不表示删除，应传入 ""；所有文件系统保持一致。
        EditResult nullNew = fs.edit(CTX, "/f.txt", "World", null, false);
        assertFalse(nullNew.isSuccess());
        assertTrue(nullNew.error().contains("must not be null"));
        assertEquals("Hello World", fs.read(CTX, "/f.txt", 0, 0).fileData().content());

        // "" 才表示真正删除，仍保留正常匹配次数。
        EditResult emptyNew = fs.edit(CTX, "/f.txt", "World", "", false);
        assertTrue(emptyNew.isSuccess(), () -> "expected success, got: " + emptyNew.error());
        assertEquals(1, emptyNew.occurrences());
        assertEquals("Hello ", fs.read(CTX, "/f.txt", 0, 0).fileData().content());

        // 空 oldString 会让出现次数统计无限循环。
        EditResult emptyOld = fs.edit(CTX, "/f.txt", "", "x", false);
        assertFalse(emptyOld.isSuccess());
        assertEquals("Hello ", fs.read(CTX, "/f.txt", 0, 0).fileData().content());
    }

    @Test
    void write_isAtomicCreateOnly() {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem fs = newFs(store);

        WriteResult first = fs.write(CTX, "/notes.md", "hello");
        assertTrue(first.isSuccess());

        WriteResult second = fs.write(CTX, "/notes.md", "should not overwrite");
        assertFalse(second.isSuccess());
        assertTrue(second.error().contains("already exists"));

        // 原内容保留。
        var read = fs.read(CTX, "/notes.md", 0, 0);
        assertTrue(read.isSuccess());
        assertEquals("hello", read.fileData().content());
    }

    @Test
    void write_concurrentCreates_onlyOneWins() throws Exception {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem fs = newFs(store);
        int writers = 32;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();

        for (int i = 0; i < writers; i++) {
            final int id = i;
            pool.submit(
                    () -> {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        WriteResult r = fs.write(CTX, "/race.md", "writer-" + id);
                        if (r.isSuccess()) {
                            succeeded.incrementAndGet();
                        }
                    });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, succeeded.get(), "exactly one concurrent create must win");
    }

    @Test
    void edit_concurrentAppends_noLostUpdates() throws Exception {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem fs = newFs(store);
        // 以空标记初始化，编辑器将在标记之后追加。
        assertTrue(fs.write(CTX, "/log.md", "BEGIN\n").isSuccess());

        int writers = 8;
        int linesPerWriter = 25;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);

        for (int i = 0; i < writers; i++) {
            final int id = i;
            pool.submit(
                    () -> {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        for (int j = 0; j < linesPerWriter; j++) {
                            String line = "w" + id + "-l" + j + "\n";
                            // 通过将末尾 BEGIN 哨兵替换为行内容加 BEGIN 来追加。
                            // edit() 内的有界重试处理版本冲突。
                            EditResult r =
                                    fs.edit(CTX, "/log.md", "BEGIN\n", line + "BEGIN\n", false);
                            // 高竞争下部分写入者可能耗尽重试，这是
                            // 文档说明的失败方式；这里只要求不静默丢失数据。
                            if (!r.isSuccess()) {
                                // 重试直到成功，测试端到端语义。
                                while (!r.isSuccess()) {
                                    r = fs.edit(CTX, "/log.md", "BEGIN\n", line + "BEGIN\n", false);
                                }
                            }
                        }
                    });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        var read = fs.read(CTX, "/log.md", 0, 0);
        assertTrue(read.isSuccess());
        String content = read.fileData().content();
        // 每个写入者尝试写入的行都应出现在最终内容中。
        for (int i = 0; i < writers; i++) {
            for (int j = 0; j < linesPerWriter; j++) {
                String marker = "w" + i + "-l" + j;
                assertTrue(
                        content.contains(marker),
                        () ->
                                "missing append "
                                        + marker
                                        + " — content lost under concurrency:\n"
                                        + content);
            }
        }
    }

    @Test
    void uploadFiles_overwritesExistingFile_lastWriteWins() {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem fs = newFs(store);

        List<FileUploadResponse> first =
                fs.uploadFiles(
                        CTX, List.of(Map.entry("/foo.md", "v1".getBytes(StandardCharsets.UTF_8))));
        assertEquals(1, first.size());
        assertTrue(first.get(0).isSuccess(), () -> "first upload: " + first.get(0).error());

        List<FileUploadResponse> second =
                fs.uploadFiles(
                        CTX, List.of(Map.entry("/foo.md", "v2".getBytes(StandardCharsets.UTF_8))));
        assertEquals(1, second.size());
        assertTrue(
                second.get(0).isSuccess(),
                () ->
                        "second upload must succeed (last-write-wins); got error: "
                                + second.get(0).error());

        var read = fs.read(CTX, "/foo.md", 0, 0);
        assertTrue(read.isSuccess());
        assertEquals("v2", read.fileData().content(), "second upload must replace first");
    }

    @Test
    void uploadFiles_repeatedOnSamePath_returnsSuccessEachTime() {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem fs = newFs(store);
        for (int i = 0; i < 5; i++) {
            String content = "iter-" + i;
            List<FileUploadResponse> resp =
                    fs.uploadFiles(
                            CTX,
                            List.of(
                                    Map.entry(
                                            "/log.md", content.getBytes(StandardCharsets.UTF_8))));
            assertEquals(1, resp.size());
            assertTrue(
                    resp.get(0).isSuccess(),
                    () -> "iteration " + content + " must succeed; got: " + resp.get(0).error());
        }
        var read = fs.read(CTX, "/log.md", 0, 0);
        assertTrue(read.isSuccess());
        assertEquals("iter-4", read.fileData().content());
    }

    @Test
    void uploadFiles_nonUtf8Binary_roundTripsWithoutCorruption() {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem fs = newFs(store);
        byte[] expected = new byte[] {0, (byte) 0xff, 1, (byte) 0x80};

        List<FileUploadResponse> upload =
                fs.uploadFiles(CTX, List.of(Map.entry("/image.png", expected)));
        assertEquals(1, upload.size());
        assertTrue(upload.get(0).isSuccess(), () -> "upload failed: " + upload.get(0).error());

        var download = fs.downloadFiles(CTX, List.of("/image.png"));
        assertEquals(1, download.size());
        assertTrue(
                download.get(0).isSuccess(), () -> "download failed: " + download.get(0).error());
        assertArrayEquals(expected, download.get(0).content());
    }

    @Test
    void downloadFiles_decodesWrappedBase64Content() {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem fs = newFs(store);
        byte[] expected = new byte[] {1, 2, 3, 4, 5, 6};
        String wrapped = Base64.getMimeEncoder(4, new byte[] {'\n'}).encodeToString(expected);

        store.put(
                List.of("test"),
                "/bin/data.bin",
                Map.of(
                        "content", wrapped,
                        "encoding", "base64",
                        "created_at", "2026-01-01T00:00:00Z",
                        "modified_at", "2026-01-01T00:00:00Z"));

        var responses = fs.downloadFiles(CTX, List.of("/bin/data.bin"));
        assertEquals(1, responses.size());
        assertTrue(responses.get(0).isSuccess(), () -> "got: " + responses.get(0).error());
        assertArrayEquals(expected, responses.get(0).content());
    }

    @Test
    void edit_returnsConflictAfterExhaustingRetries() {
        // 模拟每次 CAS 都失败的对抗性存储。
        InMemoryStore alwaysConflict =
                new InMemoryStore() {
                    @Override
                    public boolean putIfVersion(
                            List<String> namespace,
                            String key,
                            Map<String, Object> value,
                            long expectedVersion) {
                        // 允许不存在时创建（下方初始写入使用），但拒绝所有
                        // 基于版本的 CAS，使 edit() 耗尽重试。
                        if (expectedVersion == 0L) {
                            return super.putIfVersion(namespace, key, value, expectedVersion);
                        }
                        return false;
                    }
                };
        RemoteFilesystem fs = newFs(alwaysConflict);
        assertTrue(fs.write(CTX, "/x.md", "abc").isSuccess());

        EditResult r = fs.edit(CTX, "/x.md", "a", "z", false);
        assertFalse(r.isSuccess());
        assertNotNull(r.error());
        assertTrue(r.error().contains("Edit conflict"), () -> "got: " + r.error());
    }
}
