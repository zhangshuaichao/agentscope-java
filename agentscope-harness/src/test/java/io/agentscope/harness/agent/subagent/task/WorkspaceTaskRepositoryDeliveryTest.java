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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 阶段 B-3：{@link WorkspaceTaskRepository} 的推送投递语义，包括待投递检测、幂等性和心跳读写往返中的字段保留。 */
class WorkspaceTaskRepositoryDeliveryTest {

    @TempDir Path tempDir;

    private WorkspaceTaskRepository repo;
    private WorkspaceManager wsm;
    private static final String AGENT_ID = "parent-agent";
    private static final String SESSION_ID = "session-A";

    @BeforeEach
    void setup() {
        wsm = new WorkspaceManager(tempDir);
        repo = WorkspaceTaskRepository.forTests(wsm, AGENT_ID);
    }

    @AfterEach
    void tearDown() {
        if (repo != null) repo.shutdown();
    }

    private TaskRecord writeRecord(String taskId, TaskStatus status, String result, String error) {
        TaskRecord r = new TaskRecord(taskId, "sub-agent", AGENT_ID, SESSION_ID, null);
        r.setStatus(status);
        r.setResult(result);
        r.setErrorMessage(error);
        wsm.writeTaskRecord(RuntimeContext.empty(), AGENT_ID, SESSION_ID, r);
        return r;
    }

    @Test
    void findPendingDeliveries_includesOnlyTerminalUndelivered() {
        writeRecord("t1", TaskStatus.RUNNING, null, null);
        writeRecord("t2", TaskStatus.COMPLETED, "result-2", null);
        writeRecord("t3", TaskStatus.FAILED, null, "boom");
        writeRecord("t4", TaskStatus.CANCELLED, null, null);

        List<TaskDelivery> pending = repo.findPendingDeliveries(RuntimeContext.empty(), SESSION_ID);

        // 排除 RUNNING 的 t1，包含已终态且未投递的 t2/t3/t4。
        assertEquals(3, pending.size());
        assertTrue(pending.stream().anyMatch(d -> d.taskId().equals("t2")));
        assertTrue(pending.stream().anyMatch(d -> d.taskId().equals("t3")));
        assertTrue(pending.stream().anyMatch(d -> d.taskId().equals("t4")));
    }

    @Test
    void findPendingDeliveries_orderedByLastUpdatedAtAscending() throws InterruptedException {
        writeRecord("first", TaskStatus.COMPLETED, "A", null);
        Thread.sleep(15);
        writeRecord("second", TaskStatus.COMPLETED, "B", null);
        Thread.sleep(15);
        writeRecord("third", TaskStatus.COMPLETED, "C", null);

        List<TaskDelivery> pending = repo.findPendingDeliveries(RuntimeContext.empty(), SESSION_ID);
        assertEquals(3, pending.size());
        assertEquals("first", pending.get(0).taskId());
        assertEquals("second", pending.get(1).taskId());
        assertEquals("third", pending.get(2).taskId());
    }

    @Test
    void markDelivered_isIdempotent() {
        writeRecord("t1", TaskStatus.COMPLETED, "ok", null);

        repo.markDelivered(RuntimeContext.empty(), SESSION_ID, "t1");
        TaskRecord afterFirst =
                wsm.readTaskRecord(RuntimeContext.empty(), AGENT_ID, SESSION_ID, "t1")
                        .orElseThrow();
        Instant firstStamp = afterFirst.getDeliveredAt();
        assertNotNull(firstStamp);

        // 第二次调用不得覆盖原时间戳。
        repo.markDelivered(RuntimeContext.empty(), SESSION_ID, "t1");
        TaskRecord afterSecond =
                wsm.readTaskRecord(RuntimeContext.empty(), AGENT_ID, SESSION_ID, "t1")
                        .orElseThrow();
        assertEquals(firstStamp, afterSecond.getDeliveredAt());
    }

    @Test
    void findPendingDeliveries_excludesAlreadyDelivered() {
        writeRecord("t1", TaskStatus.COMPLETED, "a", null);
        writeRecord("t2", TaskStatus.COMPLETED, "b", null);

        repo.markDelivered(RuntimeContext.empty(), SESSION_ID, "t1");

        List<TaskDelivery> pending = repo.findPendingDeliveries(RuntimeContext.empty(), SESSION_ID);
        assertEquals(1, pending.size());
        assertEquals("t2", pending.get(0).taskId());
    }

    @Test
    void isDelivered_reflectsStamp() {
        writeRecord("t1", TaskStatus.COMPLETED, "x", null);
        assertFalse(repo.isDelivered(RuntimeContext.empty(), SESSION_ID, "t1"));
        repo.markDelivered(RuntimeContext.empty(), SESSION_ID, "t1");
        assertTrue(repo.isDelivered(RuntimeContext.empty(), SESSION_ID, "t1"));
    }

    @Test
    void heartbeat_doesNotClobberDeliveredAt() {
        // 模拟任务转为 RUNNING、推送投递后又触发心跳。
        // 心跳经 updateStatus(...RUNNING...) 更新 lastUpdatedAt 时不得
        // 覆盖 deliveredAt。心跳仅操作本地任务，因此记录需同时放入
        // 工作区和内存 localTasks 映射。
        // 简化模拟：写入已投递终态记录，强制进行工作区往返
        // （读、修改 lastUpdatedAt、持久化），检查 deliveredAt 保留。
        writeRecord("t1", TaskStatus.COMPLETED, "x", null);
        repo.markDelivered(RuntimeContext.empty(), SESSION_ID, "t1");

        TaskRecord r =
                wsm.readTaskRecord(RuntimeContext.empty(), AGENT_ID, SESSION_ID, "t1")
                        .orElseThrow();
        Instant deliveredBefore = r.getDeliveredAt();
        assertNotNull(deliveredBefore);

        // 读取、更新时间、写回，模拟心跳／孤儿任务清理器经
        // updateStatus 执行的操作；Jackson NON_NULL 必须在往返中保留该字段。
        r.touch();
        wsm.writeTaskRecord(RuntimeContext.empty(), AGENT_ID, SESSION_ID, r);

        TaskRecord after =
                wsm.readTaskRecord(RuntimeContext.empty(), AGENT_ID, SESSION_ID, "t1")
                        .orElseThrow();
        assertEquals(deliveredBefore, after.getDeliveredAt());
    }

    @Test
    void markDelivered_missingRecord_isNoop() {
        // 记录尚不存在，不应抛出异常。
        repo.markDelivered(RuntimeContext.empty(), SESSION_ID, "does-not-exist");
        assertNull(
                wsm.readTaskRecord(RuntimeContext.empty(), AGENT_ID, SESSION_ID, "does-not-exist")
                        .orElse(null));
    }

    @Test
    void findPendingDeliveries_emptySession_returnsEmpty() {
        List<TaskDelivery> pending = repo.findPendingDeliveries(RuntimeContext.empty(), SESSION_ID);
        assertTrue(pending.isEmpty());
    }
}
