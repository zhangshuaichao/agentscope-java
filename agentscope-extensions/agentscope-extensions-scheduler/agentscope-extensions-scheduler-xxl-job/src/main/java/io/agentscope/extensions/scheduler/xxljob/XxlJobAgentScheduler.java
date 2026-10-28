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
package io.agentscope.extensions.scheduler.xxljob;

import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.handler.IJobHandler;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.extensions.scheduler.AgentScheduler;
import io.agentscope.extensions.scheduler.BaseScheduleAgentTask;
import io.agentscope.extensions.scheduler.ScheduleAgentTask;
import io.agentscope.extensions.scheduler.config.AgentConfig;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import io.agentscope.extensions.scheduler.config.ScheduleMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 基于 XXL-Job 分布式任务平台的 AgentScheduler 实现。
 * <p>将智能体任务注册为 JobHandler，由 XXL-Job 管理控制台调度。平台提供跨执行器调度、集中管理、Cron／固定频率／手动触发、监控日志与故障转移能力；实际策略由平台配置决定。
 * <p>前提：XXL-Job 管理服务可访问，XxlJobExecutor 已正确初始化并启动，控制台配置的 JobHandler 名称匹配。
 * <p>使用示例：
 * <pre>{@code
 * // 1. 初始化 XXL-Job 执行器（通常在 Spring Boot 配置中）
 * XxlJobExecutor executor = new XxlJobExecutor();
 * executor.setAdminAddresses("http://localhost:8080/xxl-job-admin");
 * executor.setAppname("agentscope-executor");
 * executor.setPort(9999);
 * executor.start();
 *
 * // 2. 创建调度器
 * AgentScheduler scheduler = new XxlJobAgentScheduler(executor);
 *
 * // 3. 创建智能体配置
 * AgentConfig agentConfig = AgentConfig.builder()
 *     .name("MyAgent")
 *     .description("My scheduled agent")
 *     .agentFactory(() -> ReActAgent.builder()
 *         .name("MyAgent")
 *         .model(model)
 *         .toolkit(toolkit)
 *         .build())
 *     .build();
 *
 * // 4. 调度智能体
 * ScheduleConfig scheduleConfig = ScheduleConfig.builder().build();
 * ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);
 *
 * // 5. 在 XXL-Job 管理控制台配置作业：
 * //    - JobHandler: MyAgent（与智能体名称相同）
 * //    - 调度类型：CRON
 * //    - Cron 表达式：0 0 8 * * ?
 *
 * // 6. 智能体按 XXL-Job 中的调度配置执行
 * //    每次执行创建新的智能体实例
 * }</pre>
 * <p>AgentConfig 的名称用作 JobHandler 名称；每次执行创建新智能体。Cron、固定频率等调度配置由 XXL-Job 控制台管理，此适配器仅注册处理器，不控制触发时间；暂停／恢复须在控制台操作。
 * @see AgentScheduler
 * @see ScheduleAgentTask
 */
public class XxlJobAgentScheduler implements AgentScheduler {

    private static final Logger logger = LoggerFactory.getLogger(XxlJobAgentScheduler.class);

    private final XxlJobExecutor executor;
    private final Map<String, ScheduleAgentTask> scheduleAgentTasks;

    /**
     * 创建 XxlJobAgentScheduler。
     * @param executor 已初始化并启动的 XXL-Job 执行器实例
     */
    public XxlJobAgentScheduler(XxlJobExecutor executor) {
        if (executor == null) {
            throw new IllegalArgumentException("XxlJobExecutor must not be null");
        }
        this.executor = executor;
        this.scheduleAgentTasks = new ConcurrentHashMap<>();
    }

    /**
     * 使用默认 NONE 配置注册智能体。此便捷方法只注册 XXL-Job JobHandler，不自行创建自动调度；必须在 XXL-Job 控制台配置。
     * @param agentConfig 智能体配置，可为 AgentConfig 或 RuntimeAgentConfig
     * @return 调度任务
     */
    public ScheduleAgentTask schedule(AgentConfig agentConfig) {
        return this.schedule(agentConfig, ScheduleConfig.builder().build());
    }

    @Override
    public ScheduleAgentTask schedule(AgentConfig agentConfig, ScheduleConfig scheduleConfig) {
        if (agentConfig == null) {
            throw new IllegalArgumentException("AgentConfig must not be null");
        }
        if (scheduleConfig == null) {
            throw new IllegalArgumentException("ScheduleConfig must not be null");
        }
        if (scheduleConfig.getScheduleMode() != null
                && scheduleConfig.getScheduleMode() != ScheduleMode.NONE) {
            throw new UnsupportedOperationException(
                    "XXL-Job scheduler implementation currently only supports scheduleType 'none'."
                            + " Other types are not supported yet.");
        }

        String jobName = agentConfig.getName();
        if (jobName == null || jobName.trim().isEmpty()) {
            throw new IllegalArgumentException("Agent name must not be null or empty");
        }

        // 检查是否已调度
        if (scheduleAgentTasks.containsKey(jobName)) {
            logger.warn("Task '{}' is already scheduled, returning existing task", jobName);
            return scheduleAgentTasks.get(jobName);
        }

        // 必要时转换为 RuntimeAgentConfig
        RuntimeAgentConfig runtimeAgentConfig;
        if (agentConfig instanceof RuntimeAgentConfig) {
            runtimeAgentConfig = (RuntimeAgentConfig) agentConfig;
        } else {
            // 从可序列化 AgentConfig 创建 RuntimeAgentConfig
            runtimeAgentConfig =
                    RuntimeAgentConfig.builder()
                            .name(agentConfig.getName())
                            .modelConfig(agentConfig.getModelConfig())
                            .sysPrompt(agentConfig.getSysPrompt())
                            .build();
        }

        // 添加 XXL-Job 日志钩子以捕获智能体输出
        runtimeAgentConfig
                .getHooks()
                .add(
                        new Hook() {
                            @Override
                            public <T extends HookEvent> Mono<T> onEvent(T event) {
                                if (event instanceof PostReasoningEvent postReasoningEvent) {
                                    Msg msg = postReasoningEvent.getReasoningMessage();
                                    XxlJobHelper.log(msg.getTextContent());
                                }
                                return Mono.just(event);
                            }
                        });

        // 创建实现 ScheduleAgentTask 的调度任务
        BaseScheduleAgentTask scheduleAgentTask =
                new BaseScheduleAgentTask(runtimeAgentConfig, scheduleConfig, this);

        try {
            // 将 JobHandler 注册到 XXL-Job，要求可用的新注册 API
            IJobHandler jobHandler =
                    new IJobHandler() {
                        @Override
                        public void execute() throws Exception {
                            executeAgent(scheduleAgentTask);
                        }
                    };
            registerJobHandler(jobName, jobHandler);

            // 存入映射
            scheduleAgentTasks.put(jobName, scheduleAgentTask);

            logger.info(
                    "Successfully registered task '{}' as XXL-Job handler. Please configure the"
                            + " job in XXL-Job admin console with JobHandler name: {}",
                    jobName,
                    jobName);

            return scheduleAgentTask;

        } catch (Exception e) {
            logger.error("Failed to register task '{}' as XXL-Job handler", jobName, e);
            throw new RuntimeException("Failed to schedule task: " + jobName, e);
        }
    }

    /**
     * 执行调度智能体任务。由 XXL-Job 触发时调用：获取执行上下文和参数、创建新智能体、将作业参数作为输入执行、向 XXL-Job 记录结果，并递增计数。
     * @param scheduleAgentTask 待执行任务
     * @throws RuntimeException 执行失败时
     */
    private void executeAgent(BaseScheduleAgentTask scheduleAgentTask) {
        String id = scheduleAgentTask.getId();
        String name = scheduleAgentTask.getName();
        XxlJobContext context = XxlJobContext.getXxlJobContext();

        try {
            logger.info(
                    "Executing scheduled task '{}' triggered by XXL-Job. JobId: {},"
                            + " name: {}, "
                            + " ExecutorParams: {}",
                    id,
                    name,
                    context != null ? context.getJobId() : "N/A",
                    context != null ? context.getJobParam() : "N/A");
            // 使用 XXL-Job 上下文准备输入消息
            String jobParam = context != null ? context.getJobParam() : "";
            Msg inputMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text(jobParam).build())
                            .build();

            // 为本次执行创建新智能体实例
            Msg result = scheduleAgentTask.run(inputMsg).block();

            // 递增执行计数
            // 【学习提示】run 已在公共基类中递增一次，成功后这里又递增一次；当前计数会重复累计成功执行，不是准确的成功任务数。
            scheduleAgentTask.incrementExecutionCount();

            logger.info(
                    "Successfully executed scheduled task '{}'. Result: {}",
                    name,
                    result != null ? result.getTextContent() : "null");
            XxlJobHelper.handleSuccess("Successfully executed scheduled task '" + name + "'");
        } catch (Exception e) {
            logger.error("Failed to execute scheduled task '{}'", name, e);
            throw new RuntimeException("Task execution failed: " + name, e);
        }
    }

    /**
     * 取消调度任务。
     * <p>XXL-Job 适配器不支持此操作；请在管理控制台暂停或删除作业。
     * @param name 待取消任务名称
     * @return 不会正常返回，始终抛出 UnsupportedOperationException
     * @throws UnsupportedOperationException 始终抛出，因为不支持取消
     */
    @Override
    public boolean cancel(String name) {
        throw new UnsupportedOperationException(
                "Cancel operation is not supported by XxlJobAgentScheduler. "
                        + "Please use XXL-Job admin console to pause or delete the job.");
    }

    /**
     * 按名称获取任务。
     * @param name 任务名称，与智能体名称相同
     * @return 找到的 ScheduleAgentTask，未找到或名称为空时为 null
     */
    @Override
    public ScheduleAgentTask getScheduledAgent(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        return scheduleAgentTasks.get(name);
    }

    /**
     * 获取此调度器管理的全部任务。
     * @return 已注册 ScheduleAgentTask 实例列表
     */
    @Override
    public List<ScheduleAgentTask> getAllScheduleAgentTasks() {
        return new ArrayList<>(scheduleAgentTasks.values());
    }

    /**
     * 优雅关闭调度器：销毁 XXL-Job 执行器，移除已注册 JobHandler，清空任务集合。关闭后不应再调用此调度器的方法。
     */
    @Override
    public void shutdown() {
        logger.info("Shutting down XxlJobAgentScheduler. Removing all registered JobHandlers...");
        this.executor.destroy();
        scheduleAgentTasks.clear();
        logger.info("XxlJobAgentScheduler shutdown completed");
    }

    /**
     * 获取调度器类型标识。
     * @return "xxl-job"
     */
    @Override
    public String getSchedulerType() {
        return "xxl-job";
    }

    /**
     * 注册 XXL-Job JobHandler，当前实现要求提供新注册方法的版本。
     * <p>直接调用 XXL-Job 3.3.1+ 的 {@code registryJobHandler}；失败时记录错误并重新抛出，并未实现旧版 {@code registJobHandler} 回退。
     * @param jobName 作业处理器名称
     * @param jobHandler 待注册处理器实例
     * @throws Exception 注册失败时
     */
    private void registerJobHandler(String jobName, IJobHandler jobHandler) throws Exception {
        try {
            // 调用新方法名（XXL-Job 3.3.1+），无旧版回退
            XxlJobExecutor.registryJobHandler(jobName, jobHandler);
            logger.debug(
                    "Registered JobHandler '{}' using registryJobHandler (XXL-Job 3.3.1+)",
                    jobName);
        } catch (Throwable e) {
            logger.error(
                    "Registered JobHandler '{}' using registryJobHandler failed. (Need to update"
                            + " xxl-job core to 3.3.1+)",
                    jobName);
            throw e;
        }
    }
}
