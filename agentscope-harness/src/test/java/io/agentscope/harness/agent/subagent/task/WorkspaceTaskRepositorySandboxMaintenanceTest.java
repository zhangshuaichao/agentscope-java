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
package io.agentscope.harness.agent.subagent.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 沙箱工作区下维护线程失败问题 #2743 的回归测试。
 *
 * <p>{@code SandboxBackedFilesystem} 只在 {@code acquireForCall} 与 {@code releaseForCall} 之间持有活动沙箱；{@link WorkspaceTaskRepository} 的心跳和孤儿清理运行在此窗口外的 {@code ws-task-maint-*} 调度器上，文件操作会遇到 {@code SandboxException.SandboxConfigurationException("No active sandbox …")}。
 *
 * <p>沙箱测试以从未注入沙箱的 {@code SandboxBackedFilesystem} 模拟调用外状态，并在主机运行数据路径写入任务记录，检查 {@code WorkspaceManager.listAllTaskRecords} 将其与文件系统 glob 结果合并。路由测试验证另一约定：为 {@code agents/} 显式配置的持久路由仍提供任务记录，主机磁盘回退不能静默接管。
 */
class WorkspaceTaskRepositorySandboxMaintenanceTest {

    @TempDir Path tempDir;

    private static final ObjectMapper JSON =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private WorkspaceTaskRepository repo;
    private WorkspaceManager workspaceManager;

    @AfterEach
    void tearDown() throws Exception {
        if (repo != null) {
            repo.shutdown();
        }
        if (workspaceManager != null) {
            // 释放 SQLite 索引句柄，使 Windows 上的 @TempDir 清理成功。
            workspaceManager.close();
        }
    }

    @Test
    @DisplayName("#2743: orphan sweeper marks orphaned tasks FAILED without a live sandbox")
    void orphanSweep_marksOrphanFailed_outsideCallContext() throws Exception {
        SandboxBackedFilesystem sandboxFs = new SandboxBackedFilesystem();
        workspaceManager = new WorkspaceManager(tempDir, sandboxFs);
        WorkspaceManager wm = workspaceManager;

        Instant stale = Instant.now().minus(Duration.ofHours(1));
        Path recordFile = seedRunningRecord(stale);

        repo = WorkspaceTaskRepository.forTests(wm, "test-agent");
        repo.sweepOrphanedTasks(Duration.ofMinutes(10), Duration.ofHours(2));

        TaskRecord after = readRecord(recordFile, "task-1");
        assertEquals(
                TaskStatus.FAILED,
                after.getStatus(),
                "sweeper aborted with 'No active sandbox' and never marked the orphan FAILED");
        assertTrue(
                after.getErrorMessage() != null && after.getErrorMessage().contains("heartbeat"));
    }

    @Test
    @DisplayName("#2743: heartbeat refreshes lastUpdatedAt without a live sandbox")
    void heartbeat_refreshesLastUpdatedAt_outsideCallContext() throws Exception {
        SandboxBackedFilesystem sandboxFs = new SandboxBackedFilesystem();
        workspaceManager = new WorkspaceManager(tempDir, sandboxFs);
        WorkspaceManager wm = workspaceManager;

        Instant stale = Instant.now().minus(Duration.ofHours(1));
        Path recordFile = seedRunningRecord(stale);

        repo = WorkspaceTaskRepository.forTests(wm, "test-agent");

        CountDownLatch taskRunning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repo.putTask(
                RuntimeContext.empty(),
                "task-1",
                "sub-1",
                "sess",
                new TaskRunSpec.LocalTaskRunSpec(
                        () -> {
                            taskRunning.countDown();
                            try {
                                release.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return "done";
                        }));
        assertTrue(
                taskRunning.await(5, TimeUnit.SECONDS),
                "task supplier never started — its initial task-record read threw outside a call"
                        + " context");

        repo.heartbeat();
        release.countDown();

        TaskRecord after = readRecord(recordFile, "task-1");
        assertTrue(
                after.getLastUpdatedAt().isAfter(stale),
                "heartbeat failed silently ('No active sandbox') and lastUpdatedAt was never"
                        + " refreshed");
    }

    @Test
    @DisplayName("explicit agents/ route on a sandbox filesystem still serves task records")
    void routedPersistentBackend_stillServesTaskRecords() {
        MapFs persistent = new MapFs();
        RoutedSandboxFilesystem routed =
                new RoutedSandboxFilesystem(
                        new SandboxBackedFilesystem(), Map.of("agents/", persistent));

        workspaceManager = new WorkspaceManager(tempDir, routed);
        WorkspaceManager wm = workspaceManager;
        repo = WorkspaceTaskRepository.forTests(wm, "test-agent");

        TaskRecord record = new TaskRecord("task-1", "sub-1", "test-agent", "sess", null);
        record.setStatus(TaskStatus.RUNNING);
        wm.writeTaskRecord(RuntimeContext.empty(), "test-agent", "sess", record);

        assertFalse(
                Files.exists(tempDir.resolve("agents/test-agent/tasks/sess.json")),
                "task records must not silently fall back to the host disk when an explicit"
                        + " persistent route is mounted");
        assertTrue(
                wm.readTaskRecord(RuntimeContext.empty(), "test-agent", "sess", "task-1")
                        .isPresent(),
                "the routed persistent backend must serve the task record read-back");
        Collection<TaskRecord> listed =
                wm.listAllTaskRecords(RuntimeContext.empty(), "test-agent", Duration.ofHours(2));
        assertTrue(
                listed.stream().anyMatch(r -> "task-1".equals(r.getTaskId())),
                "the orphan sweeper's listing must still union the routed backend");
    }

    /** 路由测试的最小内存持久后端。{@code CompositeFilesystem} 已剥离路径前缀，键按收到的值保存；断言均通过 {@link WorkspaceManager} API，不直接检查键。 */
    private static final class MapFs implements AbstractFilesystem {
        private final Map<String, String> files = new ConcurrentHashMap<>();

        @Override
        public ReadResult read(RuntimeContext rc, String filePath, int offset, int limit) {
            String content = files.get(normalize(filePath));
            return content == null
                    ? ReadResult.fail("not found: " + filePath)
                    : ReadResult.success(new FileData(content, "utf-8"));
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext rc, List<Map.Entry<String, byte[]>> filesToUpload) {
            List<FileUploadResponse> results = new ArrayList<>();
            for (Map.Entry<String, byte[]> f : filesToUpload) {
                files.put(normalize(f.getKey()), new String(f.getValue(), StandardCharsets.UTF_8));
                results.add(FileUploadResponse.success(f.getKey()));
            }
            return results;
        }

        @Override
        public GlobResult glob(RuntimeContext rc, String pattern, String path) {
            String base = normalize(path);
            List<FileInfo> matches = new ArrayList<>();
            for (Map.Entry<String, String> e : files.entrySet()) {
                if ((base.isEmpty() || e.getKey().startsWith(base + "/"))
                        && e.getKey().endsWith(".json")
                        && "*.json".equals(pattern)) {
                    // 路由后端报告以斜杠开头的路径；CompositeFilesystem 的
                    // prependRoute 会重新拼回路由前缀。
                    matches.add(
                            FileInfo.ofFile(
                                    "/" + e.getKey(),
                                    e.getValue().length(),
                                    System.currentTimeMillis()));
                }
            }
            return GlobResult.success(matches);
        }

        private static String normalize(String p) {
            String s = p == null ? "" : p;
            while (s.startsWith("/")) {
                s = s.substring(1);
            }
            return s;
        }

        // 本测试未使用。
        @Override
        public LsResult ls(RuntimeContext rc, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WriteResult write(RuntimeContext rc, String filePath, String content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EditResult edit(
                RuntimeContext rc, String filePath, String old, String newStr, boolean all) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GrepResult grep(RuntimeContext rc, String pattern, String path, String glob) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(RuntimeContext rc, List<String> paths) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WriteResult delete(RuntimeContext rc, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WriteResult move(RuntimeContext rc, String from, String to) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(RuntimeContext rc, String path) {
            return files.containsKey(normalize(path));
        }
    }

    /** 写入包含一个 RUNNING 任务的 {@code agents/test-agent/tasks/sess.json}。 */
    private Path seedRunningRecord(Instant lastUpdatedAt) throws Exception {
        TaskRecord record = new TaskRecord("task-1", "sub-1", "test-agent", "sess", null);
        record.setStatus(TaskStatus.RUNNING);
        record.setLastUpdatedAt(lastUpdatedAt);
        Path recordFile = tempDir.resolve("agents/test-agent/tasks/sess.json");
        Files.createDirectories(recordFile.getParent());
        JSON.writeValue(recordFile.toFile(), Map.of("task-1", record));
        return recordFile;
    }

    private static TaskRecord readRecord(Path recordFile, String taskId) throws Exception {
        Map<String, TaskRecord> map =
                JSON.readValue(
                        recordFile.toFile(),
                        JSON.getTypeFactory()
                                .constructMapType(Map.class, String.class, TaskRecord.class));
        return map.get(taskId);
    }
}
