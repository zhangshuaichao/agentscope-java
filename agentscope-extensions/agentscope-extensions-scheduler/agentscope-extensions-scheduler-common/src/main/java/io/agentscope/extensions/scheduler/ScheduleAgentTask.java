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
package io.agentscope.extensions.scheduler;

import io.agentscope.core.message.Msg;
import io.agentscope.extensions.scheduler.config.AgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import reactor.core.publisher.Mono;

/**
 * 调度智能体任务的接口，分离调度关注点与智能体执行逻辑。
 * <p>遵循单一职责、最小接口及后端无关原则，专注任务生命周期管理。
 * <p>使用示例：
 * <pre>{@code
 * // 调度智能体任务
 * AgentConfig config = AgentConfig.builder()
 *     .name("MyAgent")
 *     .model(myModel)
 *     .build();
 *
 * ScheduleAgentTask task = scheduler.schedule(config, scheduleConfig);
 *
 * // 控制任务
 * String taskId = task.getId();
 * String taskName = task.getName();
 *
 * // 手动触发执行
 * task.run();
 *
 * // 取消任务
 * task.cancel();
 * }</pre>
 * @see AgentScheduler
 * @see AgentConfig
 * @see ScheduleConfig
 */
public interface ScheduleAgentTask<T> {

    /**
     * 获取任务唯一标识。通常由调度器实现生成，并在生命周期内保持不变，可用于唯一识别及查询任务。
     * @return 唯一任务标识
     */
    String getId();

    /**
     * 获取任务名称。来自智能体配置，用于可读标识；多个任务来自同一配置时可能不唯一。
     * @return 任务名称
     */
    String getName();

    /**
     * 立即手动执行任务，不依赖常规调度。通过 AgentConfig 创建新实例，传入消息执行、跟踪指标，并以 Mono 返回响应。
     * <p>实现应保证可与调度执行并发调用。
     * @param msgs 可选输入消息，可为空
     * @return 执行完成时发出响应消息的 Mono
     * @throws IllegalStateException 任务已取消时
     * @throws RuntimeException 执行失败时
     */
    Mono<T> run(Msg... msgs);

    /**
     * 永久取消任务。取消后不再调度并从调度器移除；{@link #run(Msg...)} 可能抛出 IllegalStateException，通常不可逆。对在途执行的等待／中断行为由实现决定。已取消任务上的重复取消应幂等（空操作）。
     */
    void cancel();
}
