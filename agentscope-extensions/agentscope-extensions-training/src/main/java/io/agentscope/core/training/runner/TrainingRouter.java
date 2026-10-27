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

package io.agentscope.core.training.runner;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.training.backend.TrinityClient;
import io.agentscope.core.training.backend.TrinityModelAdapter;
import io.agentscope.core.training.backend.dto.FeedbackRequest;
import io.agentscope.core.training.reward.RewardCalculator;
import io.agentscope.core.training.strategy.SelectionDecision;
import io.agentscope.core.training.strategy.TrainingSelectionStrategy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * 基于 Hook 的影子流量训练路由器，自动关联 Task／Run ID。
 * <p>PreCallEvent 保存输入、不改生产请求；PostCallEvent 筛选完成的调用，异步启动影子智能体。
 * 随后分配任务和运行 ID，用 TrinityModelAdapter 收集模型消息 ID，计算奖励并提交反馈。
 * <p>生产请求继续使用原模型；被选中的请求另外执行训练模型路径，而不是把原请求切换到训练模型。
 * <p>【学习提示】异步不等于没有用户影响：它消耗线程、模型与工具资源，并可能重放副作用。
 * 多次运行分别 subscribe，标为 isLastRun 的是最后发起者，不保证最后完成；其 doFinally 清计数器
 * 不能作为“全部训练运行已结束”的屏障。
 * @see TaskIdGenerator
 * @see RunRegistry
 * @see RunExecutionContext
 */
public class TrainingRouter implements Hook {
    private static final Logger logger = LoggerFactory.getLogger(TrainingRouter.class);

    private final TrainingConfig config;
    private final TrinityClient trinityClient;
    private final RewardCalculator rewardCalculator;
    private final TrainingSelectionStrategy selectionStrategy;
    private final Scheduler asyncScheduler;

    // 保存 PreCallEvent 输入，供 PostCallEvent 取用。
    private final Map<String, List<Msg>> callInputs = new ConcurrentHashMap<>();

    public TrainingRouter(
            TrainingConfig config,
            TrinityClient trinityClient,
            RewardCalculator rewardCalculator,
            TrainingSelectionStrategy selectionStrategy) {
        this.config = config;
        this.trinityClient = trinityClient;
        this.rewardCalculator = rewardCalculator;
        this.selectionStrategy = selectionStrategy;
        this.asyncScheduler =
                Schedulers.newBoundedElastic(
                        config.getShadowPoolSize(),
                        config.getShadowPoolCapacity(),
                        "training-shadow");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreCallEvent) {
            PreCallEvent e = (PreCallEvent) event;
            return handlePreCall(e).thenReturn((T) e);
        } else if (event instanceof PostCallEvent) {
            PostCallEvent e = (PostCallEvent) event;
            return handlePostCall(e).thenReturn((T) e);
        } else {
            return Mono.just(event);
        }
    }

    @Override
    public int priority() {
        return 500; // 优先级较低，在业务逻辑之后运行。
    }

    /**
     * 处理 PreCallEvent，保存输入消息供 PostCallEvent 使用。
     */
    private Mono<Void> handlePreCall(PreCallEvent event) {
        return Mono.fromRunnable(
                () -> {
                    String agentId = event.getAgent().getAgentId();
                    callInputs.put(agentId, event.getInputMessages());
                    logger.debug("Saved input messages for agent: {}", agentId);
                });
    }

    /**
     * 处理 PostCallEvent：确定任务 ID、分配运行 ID，并异步启动影子智能体。
     */
    private Mono<Void> handlePostCall(PostCallEvent event) {
        return Mono.deferContextual(
                ctx -> {
                    String agentId = event.getAgent().getAgentId();
                    String agentName = event.getAgent().getName();

                    // 防止影子智能体再次触发训练，避免递归循环。
                    if (agentName != null && agentName.contains("-shadow")) {
                        logger.trace(
                                "Skipping training for shadow agent: {} (prevents recursive"
                                        + " training)",
                                agentName);
                        callInputs.remove(agentId); // 清理保存的输入。
                        return Mono.empty();
                    }

                    // 获取输入消息。
                    List<Msg> inputs = callInputs.remove(agentId);
                    if (inputs == null) {
                        logger.warn("No input messages found for agent: {}", agentId);
                        return Mono.empty();
                    }

                    // 通过统一筛选策略决定是否训练。
                    logger.info(
                            "Checking training selection: agent={}, strategy={}, threadId={}",
                            event.getAgent().getName(),
                            selectionStrategy.getClass().getSimpleName(),
                            Thread.currentThread().getId());

                    SelectionDecision decision =
                            selectionStrategy.shouldSelect(
                                    event.getAgent(), inputs, event.getFinalMessage(), ctx);

                    if (!decision.shouldTrain()) {
                        logger.info(
                                "Skip training: agent={}, reason={}",
                                event.getAgent().getName(),
                                decision.getReason());
                        return Mono.empty();
                    }

                    // 获取或生成任务 ID。
                    // 优先使用用户指定的 taskId，允许同任务多次运行。
                    // 否则自动生成新 taskId。
                    String taskId =
                            decision.getMetadata() != null
                                            && decision.getMetadata().containsKey("taskId")
                                    ? (String) decision.getMetadata().get("taskId")
                                    : TaskIdGenerator.generate();

                    if (decision.getMetadata() != null
                            && decision.getMetadata().containsKey("taskId")) {
                        logger.debug(
                                "Using user-specified Task ID: {} for agent: {}",
                                taskId,
                                event.getAgent().getName());
                    } else {
                        logger.debug(
                                "Auto-generated Task ID: {} for agent: {}",
                                taskId,
                                event.getAgent().getName());
                    }

                    // 读取每个任务的重复次数。
                    int repeatTime = config.getRepeatTime();

                    logger.info(
                            "Training triggered for task {}: repeatTime={}, reason={}, labels={}",
                            taskId,
                            repeatTime,
                            decision.getReason(),
                            decision.getLabels());

                    // 循环发起多次运行，共享 taskId，各自分配递增 runId。
                    for (int i = 0; i < repeatTime; i++) {
                        // 为本次迭代分配 runId。
                        String currentRunId = RunRegistry.allocateRunId(taskId);
                        RunExecutionContext currentContext =
                                RunExecutionContext.create(taskId, currentRunId);

                        logger.info(
                                "Starting run {}/{} for task {}: {}",
                                i + 1,
                                repeatTime,
                                taskId,
                                currentContext);

                        // 判断是否最后发起的运行，不代表最后完成。
                        boolean isLastRun = (i == repeatTime - 1);

                        // 将影子执行订阅到异步调度器。
                        runShadowAgent(event.getAgent(), inputs, currentContext, decision)
                                .subscribeOn(asyncScheduler)
                                .doFinally(
                                        signal -> {
                                            // 最后发起的运行终止时清理计数器，其他运行可能仍未完成。
                                            if (isLastRun) {
                                                RunRegistry.cleanup(taskId);
                                                logger.debug(
                                                        "Cleaned up registry for Task: {} after {}"
                                                                + " runs",
                                                        taskId,
                                                        repeatTime);
                                            }
                                        })
                                .subscribe(
                                        null,
                                        error ->
                                                logger.error(
                                                        "Shadow agent failed for {}",
                                                        currentContext,
                                                        error));
                    }

                    return Mono.empty();
                });
    }

    /**
     * 运行影子智能体：创建关联执行上下文的 TrinityModelAdapter，自动收集 msg_ids，
     * 计算奖励并提交包含任务 ID、运行 ID 和消息 ID 的反馈。
     * <p>内部使用 block 等同步等待，由调用方 subscribeOn 的有界弹性调度器承载。
     */
    private Mono<Void> runShadowAgent(
            Agent productionAgent,
            List<Msg> inputs,
            RunExecutionContext executionContext,
            SelectionDecision decision) {
        return Mono.defer(
                () -> {
                    logger.info(
                            "Starting shadow agent for {}: agent={}, labels={}",
                            executionContext,
                            productionAgent.getName(),
                            decision.getLabels());

                    try {
                        // 1. 创建 Trinity 模型并关联执行上下文。
                        TrinityModelAdapter trinityModel =
                                TrinityModelAdapter.builder()
                                        .baseUrl(trinityClient.getEndpoint() + "/v1")
                                        .modelName(config.getModelName())
                                        .apiKey(config.getTrinityApiKey())
                                        .executionContext(
                                                executionContext) // 传入执行上下文。
                                        .build();

                        // 2. 克隆智能体并替换模型。
                        Agent shadowAgent =
                                AgentCloner.cloneWithModel(productionAgent, trinityModel);

                        logger.debug(
                                "Shadow agent created for {}: {}",
                                executionContext,
                                shadowAgent.getName());

                        // 3. 执行影子智能体。
                        shadowAgent.call(inputs).block();

                        logger.info(
                                "Shadow agent completed: {}, duration={}ms",
                                executionContext,
                                executionContext.getDuration());

                        // 4. 从执行上下文读取 msg_ids。
                        List<String> msgIds = executionContext.getMsgIds();

                        logger.info("Collected {} msg_ids for {}", msgIds.size(), executionContext);

                        if (msgIds.isEmpty()) {
                            logger.warn("No msg_ids collected for {}", executionContext);
                            return Mono.empty();
                        }

                        // 5. 使用 RewardCalculator 计算奖励。
                        double reward = rewardCalculator.calculate(shadowAgent);

                        logger.info("Calculated reward: {} for {}", reward, executionContext);

                        // 6. 自动填入任务 ID、运行 ID 与消息 ID，提交反馈。
                        return trinityClient
                                .feedback(
                                        FeedbackRequest.builder()
                                                .taskId(executionContext.getTaskId())
                                                .runId(executionContext.getRunId())
                                                .msgIds(msgIds)
                                                .reward(reward)
                                                .build())
                                .doOnSuccess(
                                        v -> {
                                            logger.info(
                                                    "Feedback submitted successfully for {}",
                                                    executionContext);

                                            // 8. 将执行上下文保存到注册表，
                                            // 供后续查询。
                                            TaskExecutionRegistry.register(executionContext);
                                            logger.debug(
                                                    "Registered context: {}, total runs: {}",
                                                    executionContext,
                                                    TaskExecutionRegistry.getRunCount(
                                                            executionContext.getTaskId()));
                                        })
                                .then();

                    } catch (Exception e) {
                        logger.error("Failed to create shadow agent for {}", executionContext, e);
                        return Mono.empty();
                    }
                });
    }
}
