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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.scheduler.config.AgentConfig;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Mono;

/**
 * {@link ScheduleAgentTask} 的默认实现。
 * <p>根据调度配置管理任务生命周期，通过 {@link AgentConfig} 为每次执行创建新智能体，隔离智能体状态并管理资源。支持运行／取消、执行计数与配置查询。
 * <p>使用示例：
 * <pre>{@code
 * // 创建智能体配置
 * AgentConfig agentConfig = AgentConfig.builder()
 *     .name("MyAgent")
 *     .model(model)
 *     .toolkit(toolkit)
 *     .build();
 *
 * // 调度智能体
 * ScheduleConfig scheduleConfig = ScheduleConfig.builder()
 *     .cron("0 0 8 * * ?")
 *     .build();
 * ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);
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
 *
 * // 查询执行信息（转换为 BaseScheduleAgentTask 后）
 * if (task instanceof BaseScheduleAgentTask baseTask) {
 *     boolean cancelled = baseTask.isCancelled();
 *     long count = baseTask.getExecutionCount();
 * }
 * }</pre>
 * <p><b>并发边界：</b>取消标记与计数采用原子变量；每次创建新智能体不代表共享的模型、工具包与 Hook 自动线程安全，调用方仍需考虑这些依赖的并发行为。
 * @see ScheduleAgentTask
 * @see AgentScheduler
 * @see AgentConfig
 * @see ScheduleConfig
 */
public class BaseScheduleAgentTask implements ScheduleAgentTask<Msg> {

    private final String id;
    private final RuntimeAgentConfig agentConfig;
    private final ScheduleConfig scheduleConfig;
    private final AgentScheduler scheduler;
    private final AtomicBoolean cancelled;
    private final AtomicLong executionCount;

    /**
     * 创建 BaseScheduleAgentTask。
     * @param agentConfig 包含模型、工具包及 Hook 的运行时智能体配置
     * @param scheduleConfig 时间与执行策略配置
     * @param scheduler 管理此任务的调度器
     */
    public BaseScheduleAgentTask(
            RuntimeAgentConfig agentConfig,
            ScheduleConfig scheduleConfig,
            AgentScheduler scheduler) {
        this.id = UUID.randomUUID().toString();
        this.agentConfig = agentConfig;
        this.scheduleConfig = scheduleConfig;
        this.scheduler = scheduler;
        this.cancelled = new AtomicBoolean(false);
        this.executionCount = new AtomicLong(0);
    }

    // ==================== ScheduleAgentTask 接口实现 ====================

    /**
     * 获取任务唯一标识，创建任务时自动生成 UUID。
     * @return 唯一任务标识
     */
    @Override
    public String getId() {
        return id;
    }

    /**
     * 获取任务名称，来自智能体配置。
     * @return 任务名称
     */
    @Override
    public String getName() {
        return agentConfig.getName();
    }

    /**
     * 立即发起任务调用。创建新智能体并使用输入消息调用；返回响应式结果前递增执行计数。
     * @param msgs 传给智能体的可选输入消息，可为空或 null
     * @return 执行完成时发出响应消息的 Mono
     * @throws IllegalStateException 任务已取消时
     */
    @Override
    public Mono<Msg> run(Msg... msgs) {
        if (cancelled.get()) {
            throw new IllegalStateException("Cannot run a cancelled task: " + getName());
        }
        // 为本次执行创建新的智能体实例
        Agent agent = createAgent();

        Mono<Msg> result =
                agent.call(msgs == null || msgs.length == 0 ? List.of() : Arrays.asList(msgs));
        // 递增执行计数以便跟踪
        // 【学习提示】此处尚未订阅返回的 Mono，计数反映调用发起而非成功完成；不能直接把它当作任务成功指标。
        incrementExecutionCount();
        return result;
    }

    /**
     * 永久取消此任务。先标记取消，再委托调度器移除调度；之后调用 {@link #run(Msg...)} 会抛出 IllegalStateException。
     */
    @Override
    public void cancel() {
        cancelled.set(true);
        scheduler.cancel(getName());
    }

    // ==================== 任务管理附加方法 ====================

    /**
     * 检查任务是否已取消。取消的任务不可手动运行，也不应再被调度。
     * @return 已取消为 true，否则为 false
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 获取调度时提供的配置，包括时间、执行策略及其他调度参数。
     * @return 此任务的 ScheduleConfig
     */
    public ScheduleConfig getScheduleConfig() {
        return scheduleConfig;
    }

    /**
     * 获取运行时智能体配置。{@link RuntimeAgentConfig} 包含用于创建实例的全部配置及模型、工具包、Hook 等运行时对象。
     * @return RuntimeAgentConfig 实例
     */
    public RuntimeAgentConfig getAgentConfig() {
        return agentConfig;
    }

    /**
     * 创建并返回新的智能体实例，供调度器内部使用。根据 RuntimeAgentConfig 构建具有独立状态的 ReActAgent，应用配置的模型及可选工具包、Hook。
     * @return 新 ReActAgent 实例
     * @throws IllegalArgumentException 必需配置缺失或无效时
     */
    private Agent createAgent() {
        String name = Optional.ofNullable(this.agentConfig.getName()).orElse("");
        String sysPrompt = Optional.ofNullable(this.agentConfig.getSysPrompt()).orElse("");

        if (name.isEmpty() || sysPrompt.isEmpty()) {
            throw new IllegalArgumentException(
                    "Parameter configuration error: name or sysPrompt cannot be empty.");
        }
        ReActAgent.Builder builder = ReActAgent.builder().name(name).sysPrompt(sysPrompt);
        Model model = this.agentConfig.getModel();
        if (model != null) {
            builder.model(this.agentConfig.getModel());
        } else {
            throw new IllegalArgumentException("Model cannot be null.");
        }
        Toolkit toolkit = this.agentConfig.getToolkit();
        if (toolkit != null) {
            builder.toolkit(toolkit);
        }
        List<Hook> hooks = this.agentConfig.getHooks();
        if (hooks != null) {
            builder.hooks(hooks);
        }

        return builder.build();
    }

    /**
     * 获取执行计数，包括调度调用及手动 {@link #run(Msg...)} 调用。
     * <p>当前 run 在返回 Mono 前递增计数，不等待订阅或成功完成，因此它不是成功完成次数；创建／调用在递增之前同步抛错时也不会计入。
     * @return 执行计数，初始为 0
     */
    public long getExecutionCount() {
        return executionCount.get();
    }

    /**
     * 递增执行计数。供调度器实现或子类内部调用；计数发生时点由调用位置决定。
     */
    public void incrementExecutionCount() {
        executionCount.incrementAndGet();
    }
}
