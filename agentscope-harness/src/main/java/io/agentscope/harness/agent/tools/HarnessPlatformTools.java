/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.tools;

import java.util.Set;

/**
 * Harness 平台与编排工具的名称，{@link ToolFilter} 不得仅因 {@code
 * allow} 列表而移除它们。
 *
 * <p>产品级智能体工具集（Managed Agents UI 或 {@code tools.json} 的 allow）可以缩小
 * <em>目录内</em>工具的可见范围，如文件系统、Shell、Web、记忆、会话辅助工具等。
 * 但不能因此禁用协调运行时本身：子智能体、团队、后台任务、计划模式及技能管理。
 * 显式 {@code deny} 仍会移除对应名称，允许操作者主动禁用。
 */
public final class HarnessPlatformTools {

    /**
     * 由 {@code HarnessAgent} 的中间件或构建器路径注册、
     * 但不属于产品“智能体工具集”目录的名称。
     */
    public static final Set<String> NAMES =
            Set.of(
                    // AgentTeams：统一工具及模型常自行构造的逐动作别名。
                    "team",
                    "listTasks",
                    "listClaimableTasks",
                    "createTask",
                    "assignTask",
                    "claimTask",
                    "unclaimTask",
                    "completeTask",
                    "failTask",
                    "sendMessage",
                    "broadcastMessage",
                    "listMessages",
                    "listMembers",
                    "spawnMember",
                    "shutdownMember",
                    "submitPlan",
                    "approvePlan",
                    "rejectPlan",
                    "completeTeam",
                    // 子智能体：包含会话模式别名。
                    "agent_spawn",
                    "agent_send",
                    "agent_list",
                    "agent_generate",
                    "sessions_spawn",
                    "sessions_send",
                    "sessions_list",
                    "sessions_history",
                    "sessions_pending_completions",
                    // 后台任务生命周期。
                    "task_output",
                    "task_cancel",
                    "task_list",
                    "wait_async_results",
                    // 计划模式。
                    "plan_enter",
                    "plan_write",
                    "plan_exit",
                    // 技能自主学习。
                    "skill_manage",
                    "propose_skill");

    private HarnessPlatformTools() {}

    /** 返回 {@code true} 表示 {@code toolName} 属于 Harness 平台工具。 */
    public static boolean isPlatformTool(String toolName) {
        return toolName != null && NAMES.contains(toolName);
    }
}
