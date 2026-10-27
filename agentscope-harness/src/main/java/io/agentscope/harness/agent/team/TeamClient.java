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
package io.agentscope.harness.agent.team;

import java.util.List;
import reactor.core.publisher.Mono;

/**
 * AgentTeams 协调客户端，提供与 Claude 对应的任务看板和邮箱机制。
 *
 * <p>实现包括 {@link LocalTeamClient}（BaseStore CAS）以及控制平面 HTTP 客户端。
 *
 * <p>【学习提示】负责人分配任务仍保持 pending；成员成功领取才进入 in_progress。
 * “有拥有者”不等于“已经开始执行”，依赖未解除的任务也不能直接领取。
 */
public interface TeamClient {

    /** 列出团队看板上的全部任务。 */
    Mono<List<TeamTask>> listTasks(String namespace, String teamName);

    /** 创建 pending 任务；{@code owner} 可为空白，表示尚未分配。 */
    Mono<TeamTask> createTask(
            String namespace,
            String teamName,
            String subject,
            String description,
            List<String> blockedBy,
            String owner);

    /** 负责人将 pending 任务分配给 {@code owner}，状态仍为 pending。 */
    Mono<TeamTask> assignTask(
            String namespace, String teamName, String taskId, String owner, long expectedVersion);

    /**
     * 当拥有者为空（自行领取）或已等于 {@code claimedBy}（被指派成员开始）时，
     * 将未阻塞的 pending 任务领取为 in_progress。
     *
     * <p>{@code expectedVersion <= 0} 表示按存储当前版本领取，因为模型经常省略乐观锁令牌。
     */
    Mono<TeamTask> claimTask(
            String namespace,
            String teamName,
            String taskId,
            String claimedBy,
            long expectedVersion);

    /** 完成正在执行的任务，并将结果通知负责人。 */
    Mono<TeamTask> completeTask(String namespace, String teamName, String taskId, String result);

    /** 将非终态任务标为失败，并将原因通知负责人。 */
    Mono<TeamTask> failTask(String namespace, String teamName, String taskId, String reason);

    /** 放弃正在执行的任务，将其退回 pending。 */
    Mono<TeamTask> unclaimTask(
            String namespace, String teamName, String taskId, long expectedVersion);

    /**
     * 返回调用方可以开始、且未被阻塞的 pending 任务：未分配的开放看板任务，
     * 以及在 {@code forMember} 非空白时已分配给该成员的 pending 任务。
     */
    Mono<List<TeamTask>> listClaimableTasks(String namespace, String teamName, String forMember);

    /** 仅返回开放看板中可领取的任务，即 {@code forMember} 为空白。 */
    default Mono<List<TeamTask>> listClaimableTasks(String namespace, String teamName) {
        return listClaimableTasks(namespace, teamName, null);
    }

    /** 点对点团队邮箱消息。 */
    Mono<TeamMessage> sendMessage(
            String namespace, String teamName, String from, String to, String content);

    /** 向其他全部成员广播邮箱消息；控制平面使用空的 {@code to}。 */
    Mono<List<TeamMessage>> broadcastMessage(
            String namespace, String teamName, String from, String content);

    /** 列出近期团队消息。 */
    Mono<List<TeamMessage>> listMessages(String namespace, String teamName, int limit);

    /** 列出团队成员。 */
    Mono<List<TeamMemberInfo>> listMembers(String namespace, String teamName);

    /** 动态创建队友，由负责人调用。 */
    Mono<Void> spawnMember(
            String namespace, String teamName, String name, String agentRef, String prompt);

    /** 关闭或移除队友，由负责人调用。 */
    Mono<Void> shutdownMember(String namespace, String teamName, String memberName);

    /** 工作成员提交计划供负责人审批。 */
    Mono<Void> submitPlan(String namespace, String teamName, String memberName, String planText);

    /** 负责人批准成员计划。 */
    Mono<Void> approvePlan(String namespace, String teamName, String memberName);

    /** 负责人拒绝成员计划。 */
    Mono<Void> rejectPlan(String namespace, String teamName, String memberName);

    /** 创建由存储支持的团队及初始成员。 */
    Mono<TeamInfo> createTeam(TeamCreateSpec spec);

    /** 将团队标记为完成，按保留 TTL 清理，而非立即删除。 */
    Mono<Void> completeTeam(String namespace, String teamName);
}
