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

import io.agentscope.core.agent.RuntimeContext;
import java.util.Collection;
import java.util.List;

/**
 * 按会话划分作用域的后台子智能体任务管理仓库。
 *
 * <p>所有操作均限定在 {@code sessionId} 作用域内，使不同父会话的任务彼此隔离。
 * 实现可忽略 {@code sessionId}（内存存储），或用它划分持久化存储
 * （工作区支持的存储）。
 *
 * <p>每个方法接收 {@link RuntimeContext}：通过按用户命名空间划分的文件系统
 * 持久化任务状态的实现必须传播 {@code rc}，使并发用户的写入落到各自命名空间。
 */
public interface TaskRepository {

    /**
     * 供保留执行所有权并自行收集结果的调用方使用的 RuntimeContext 标志。
     */
    String SUPPRESS_COMPLETION_CALLBACK = "harness.suppressTaskCompletionCallback";

    /**
     * 按会话与任务 ID 获取后台任务；未找到时返回 {@code null}。
     *
     * @param rc 当前调用的运行时上下文；可以为 {@link RuntimeContext#empty()}
     * @param sessionId 父会话作用域
     * @param taskId 唯一任务标识符
     */
    BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId);

    /**
     * 根据 {@link TaskRunSpec} 提交新的后台任务。
     *
     * <p>实现应捕获 {@code rc}，使后台执行线程能在发起用户的命名空间下
     * 持久化任务状态。
     *
     * @param rc 当前调用的运行时上下文；可以为 {@link RuntimeContext#empty()}
     * @param taskId 任务的唯一标识符
     * @param subAgentId 执行此任务的子智能体类型
     * @param sessionId 父会话作用域
     * @param spec 本地 supplier 执行或远程 HTTP 任务协议
     * @return 已创建的后台任务
     */
    BackgroundTask putTask(
            RuntimeContext rc,
            String taskId,
            String subAgentId,
            String sessionId,
            TaskRunSpec spec);

    /**
     * 列出指定会话的全部已跟踪任务，可按状态过滤。
     *
     * @param rc 当前调用的运行时上下文；可以为 {@link RuntimeContext#empty()}
     * @param sessionId 父会话作用域
     * @param filter 非 null 时仅返回此状态的任务；null 返回全部任务
     */
    Collection<BackgroundTask> listTasks(RuntimeContext rc, String sessionId, TaskStatus filter);

    /**
     * 按会话与任务 ID 取消正在运行的任务。
     *
     * @param rc 当前调用的运行时上下文；可以为 {@link RuntimeContext#empty()}
     * @return 找到任务并尝试取消时返回 true
     */
    boolean cancelTask(RuntimeContext rc, String sessionId, String taskId);

    // ------------------------------------------------------------------------
    // 阶段 B-3：推送投递 API。
    //
    // 持久化 {@link TaskRecord} 的实现应覆盖下面三个方法，
    // 将“已达终态但尚未投递”的任务呈现给父级智能体的推理循环。
    // 默认空实现为内存仓库保留仅拉取语义；
    // 在这种场景下，父进程和任务调用方共享同一 JVM，
    // 可以直接依赖 future 完成。
    // ------------------------------------------------------------------------

    /**
     * 返回指定会话中已达终态、但尚未将完成结果推回父级智能体的任务。
     *
     * <p>实现应以确定性顺序返回结果（通常按完成时间升序），
     * 使父级按实际发生顺序看到投递。
     *
     * <p>默认返回空列表：内存仓库当前不支持推送。
     */
    default List<TaskDelivery> findPendingDeliveries(RuntimeContext rc, String sessionId) {
        return List.of();
    }

    /**
     * 将指定任务标记为已投递，避免再次推送。此操作幂等：
     * 首次调用之后再次调用不会产生影响。
     *
     * <p>默认不执行操作。
     */
    default void markDelivered(RuntimeContext rc, String sessionId, String taskId) {
        // 不执行操作
    }

    /**
     * 指定任务是否已经投递给父级智能体。
     * {@link io.agentscope.harness.agent.middleware.SubagentsMiddleware#buildTaskSummary}
     * 据此从 SYSTEM 提示摘要中省略已投递的终态任务。
     *
     * <p>默认返回 {@code false}。
     */
    default boolean isDelivered(RuntimeContext rc, String sessionId, String taskId) {
        return false;
    }

    /**
     * 注册任务达到终态（COMPLETED 或 FAILED）时调用的回调。
     * {@link io.agentscope.harness.agent.middleware.SubagentsMiddleware}
     * 使用它将结果推送到会话收件箱，并将唤醒信号入队。任务失败时
     * {@code result} 参数为 {@code null}。
     *
     * <p>保留执行所有权的调用方可在 RuntimeContext 中设置
     * {@link #SUPPRESS_COMPLETION_CALLBACK}，抑制收件箱／唤醒回调，
     * 同时保留持久化结果。支持此回调的实现应遵循该标志。
     * <p>默认不执行操作；支持推送投递的实现应覆盖它。
     */
    default void setCompletionCallback(TaskCompletionCallback callback) {
        // 不执行操作
    }

    /**
     * 关闭此仓库拥有的后台执行器。默认不执行操作。
     */
    default void shutdown() {
        // 不执行操作
    }

    /**
     * 后台任务达到终态（COMPLETED 或 FAILED）时调用的回调。
     * 任务失败时 {@code result} 为 {@code null}。
     */
    @FunctionalInterface
    interface TaskCompletionCallback {
        void onCompleted(
                RuntimeContext rc,
                String taskId,
                String subAgentId,
                String sessionId,
                String result);
    }
}
