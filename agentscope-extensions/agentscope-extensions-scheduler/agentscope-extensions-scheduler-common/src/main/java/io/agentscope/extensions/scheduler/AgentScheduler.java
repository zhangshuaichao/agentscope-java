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
import java.util.List;

/**
 * 按指定时间或间隔调度智能体运行的核心接口。
 * <p>定义多种配置（Cron、固定频率、固定延迟）的任务注册、生命周期管理（暂停、恢复、取消）及查询操作。
 * <p>后端可采用 Spring TaskScheduler（简单场景的轻量方案）、Quartz（持久化及集群）或 XXL-Job 等外部分布式调度器；具体能力取决于实现。
 * <p><b>设计原则：</b>以任务为中心，返回 {@link ScheduleAgentTask} 管理生命周期；接口不绑定后端，可扩展调度策略；任务可跟踪执行历史、指标及状态。
 * <p>使用示例：
 * <pre>{@code
 * AgentScheduler scheduler = new XxlJobAgentScheduler(executor);
 *
 * // 创建智能体配置
 * AgentConfig agentConfig = AgentConfig.builder()
 *     .name("MyAgent")
 *     .model(model)
 *     .toolkit(toolkit)
 *     .sysPrompt("You are a helpful assistant")
 *     .build();
 *
 * // 创建调度配置
 * ScheduleConfig scheduleConfig = ScheduleConfig.builder()
 *     .cron("0 0 8 * * ?")
 *     .build();
 *
 * // 调度智能体并获取任务句柄
 * ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);
 *
 * // 控制任务
 * task.run();      // 手动执行
 * task.cancel();   // 取消调度
 *
 * // 或通过调度器取消
 * scheduler.cancel(task.getName());
 * }</pre>
 * @see ScheduleConfig
 * @see ScheduleAgentTask
 * @see AgentConfig
 */
public interface AgentScheduler {

    /**
     * 按指定配置调度智能体。
     * <p>{@link AgentConfig} 定义如何创建新的智能体实例。若要配置 Toolkit、Hook 等运行时对象，可传入 {@link io.agentscope.extensions.scheduler.config.RuntimeAgentConfig}。每次执行创建新实例，有利于隔离状态并管理资源。
     * <p>返回的 {@link ScheduleAgentTask} 提供运行、取消等控制；{@link AgentConfig#getName()} 用作任务标识，可经 {@link #getScheduledAgent(String)} 查询。
     * <p>每次执行有新的智能体状态，不需要长期持有智能体；AgentConfig 配置可序列化持久化；任务生命周期与智能体生命周期独立。
     * @param agentConfig 创建智能体的配置，可为 AgentConfig 或 RuntimeAgentConfig
     * @param scheduleConfig 包含时间与执行策略的调度配置
     * @return 用于控制任务的 ScheduleAgentTask 句柄
     * @throws IllegalArgumentException 配置为 null 或无效时
     */
    ScheduleAgentTask schedule(AgentConfig agentConfig, ScheduleConfig scheduleConfig);

    /**
     * 使用初始输入消息调度智能体。扩展 {@link #schedule(AgentConfig, ScheduleConfig)}，允许传入 {@link Msg}，触发时交给新建智能体执行，适用于需要上下文、参数或初始指令的任务。
     * @param agentConfig 创建智能体的配置，可为 AgentConfig 或 RuntimeAgentConfig
     * @param scheduleConfig 时间与执行策略配置
     * @param input 触发时传入的初始消息，可为 null
     * @return 任务控制句柄
     * @throws UnsupportedOperationException 实现不支持初始输入时
     */
    default ScheduleAgentTask schedule(
            AgentConfig agentConfig, ScheduleConfig scheduleConfig, Msg input) {
        throw new UnsupportedOperationException(
                "This scheduler implementation does not support initial input.");
    }

    /**
     * 永久取消并移除调度任务。正在执行时如何处理由实现决定，可能等待完成或尝试中断。此方法通过调度器集中控制，也可直接调用 {@link ScheduleAgentTask#cancel()}。
     * @param name 待取消任务名称
     * @return 成功取消为 true，不存在或已移除为 false
     */
    boolean cancel(String name);

    /**
     * 按名称获取调度任务。名称来自调度时的 {@link AgentConfig#getName()}，因此无需保留 {@link #schedule(AgentConfig, ScheduleConfig)} 返回的 {@link ScheduleAgentTask} 引用。重名时可能返回首个、最近一个或抛异常，取决于实现。
     * @param name 任务名称
     * @return 找到的任务，不存在时为 null
     */
    ScheduleAgentTask getScheduledAgent(String name);

    /**
     * 获取此调度器管理的全部任务，不区分已调度、运行中、已暂停等状态。任务量较大时，实现应考虑分页。
     * @return 全部 ScheduleAgentTask 实例列表
     */
    List<ScheduleAgentTask> getAllScheduleAgentTasks();

    /**
     * 优雅关闭调度器：停止接受新调度请求，并按实现策略等待当前任务完成，最大等待时间由实现决定。关闭后不应再调用其方法。支持持久化的实现（如数据库存储的 Quartz）可保留配置供重启恢复；可恢复范围取决于持久化内容及运行时依赖。
     */
    void shutdown();

    /**
     * 获取调度器类型标识，用于区分实现及日志／监控，例如 "spring"、"quartz"、"xxl-job"。
     * @return 调度器类型标识
     */
    String getSchedulerType();
}
