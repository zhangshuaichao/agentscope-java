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

import java.time.Instant;

/**
 * 已达终态、等待或正在向父级智能体推理循环推送结果的子智能体任务快照。
 * 由 {@link TaskRepository#findPendingDeliveries} 返回，使中间件无需重新查询底层记录
 * 即可将其渲染为合成的 {@code <system-reminder>} 消息。
 *
 * <p>有意将字段从 {@link TaskRecord} 反规范化提取出来：消费者
 * （{@code SubagentsMiddleware}）只需要投影视图，预先创建快照可避免
 * 与之后的状态变更发生竞争。
 *
 * @param taskId 任务标识符
 * @param agentId 产生此结果的子智能体类型（旧记录可以为 {@code null}）
 * @param status 终态：COMPLETED／FAILED／CANCELLED 之一
 * @param result 完成载荷；FAILED／CANCELLED 时为 {@code null}
 * @param errorMessage 失败消息；COMPLETED／CANCELLED 时为 {@code null}
 * @param completedAt 任务到达终态的墙上时钟时间点（尽力而为，
 *     通常为 {@link TaskRecord#getLastUpdatedAt()}）
 */
public record TaskDelivery(
        String taskId,
        String agentId,
        TaskStatus status,
        String result,
        String errorMessage,
        Instant completedAt) {}
