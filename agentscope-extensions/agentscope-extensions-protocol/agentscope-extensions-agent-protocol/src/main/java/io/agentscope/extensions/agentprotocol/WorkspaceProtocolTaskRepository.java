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
package io.agentscope.extensions.agentprotocol;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * 由独立 {@link WorkspaceManager} 支撑的 {@link ProtocolTaskRepository}，向合成的 {@link AgentProtocolConstants#PROTOCOL_AGENT_ID} / {@link AgentProtocolConstants#PROTOCOL_SESSION_ID} 桶写入数据。
 *
 * <p>应传入根目录位于协议控制平面路径（而非执行智能体工作区）的管理器，使多智能体部署中的任务元数据与各智能体文件隔离存放。
 */
public final class WorkspaceProtocolTaskRepository implements ProtocolTaskRepository {

    private final WorkspaceManager workspaceManager;

    public WorkspaceProtocolTaskRepository(WorkspaceManager workspaceManager) {
        this.workspaceManager = Objects.requireNonNull(workspaceManager, "workspaceManager");
    }

    /** 以 {@code taskStorePath} 为根目录，使用新的本地工作区管理器创建仓库。 */
    public WorkspaceProtocolTaskRepository(Path taskStorePath) {
        this(new WorkspaceManager(Objects.requireNonNull(taskStorePath, "taskStorePath")));
    }

    /** 返回底层工作区管理器（仅对应控制平面根目录）。 */
    public WorkspaceManager workspaceManager() {
        return workspaceManager;
    }

    @Override
    public void save(TaskRecord record) {
        Objects.requireNonNull(record, "record");
        workspaceManager.writeTaskRecord(
                RuntimeContext.empty(),
                AgentProtocolConstants.PROTOCOL_AGENT_ID,
                AgentProtocolConstants.PROTOCOL_SESSION_ID,
                record);
    }

    @Override
    public Optional<TaskRecord> find(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return workspaceManager.readTaskRecord(
                RuntimeContext.empty(),
                AgentProtocolConstants.PROTOCOL_AGENT_ID,
                AgentProtocolConstants.PROTOCOL_SESSION_ID,
                taskId);
    }
}
