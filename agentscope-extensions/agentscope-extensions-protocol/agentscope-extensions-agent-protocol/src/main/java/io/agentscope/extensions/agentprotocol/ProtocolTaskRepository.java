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

import io.agentscope.harness.agent.subagent.task.TaskRecord;
import java.util.Optional;

/**
 * Agent Protocol {@link TaskRecord} 的控制平面持久化接口。
 *
 * <p>此存储<strong>不是</strong>执行智能体的工作区。任务 HTTP 端点需要按全局 {@code task_id} 查询，不依赖执行任务的 {@link io.agentscope.harness.agent.HarnessAgent}；因此记录保存在独立协议桶中（参见 {@link AgentProtocolConstants#PROTOCOL_AGENT_ID}）。各智能体自己的 {@link io.agentscope.harness.agent.workspace.WorkspaceManager} 仍只管理该智能体的 MEMORY／会话／技能。
 */
public interface ProtocolTaskRepository {

    /** 插入或更新给定任务记录。 */
    void save(TaskRecord record);

    /** 返回指定 ID 的任务；未知时返回空值。 */
    Optional<TaskRecord> find(String taskId);
}
