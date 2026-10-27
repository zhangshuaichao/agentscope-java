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
package io.agentscope.core.tool;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.observation.ActionObservationException;
import io.agentscope.core.observation.ActionObservations;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.tracing.TracerRegistry;
import io.agentscope.core.util.ExceptionUtils;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * 统一工具执行器，集中处理基础设施相关职责。
 *
 * <p>整合工具执行逻辑，包括：
 * <ul>
 * <li>单次和批量工具执行</li>
 * <li>并行与顺序执行控制</li>
 * <li>超时和重试处理</li>
 * <li>线程调度</li>
 * <li>执行前模式验证</li>
 * </ul>
 *
 * <p>执行模式：
 * <ul>
 * <li>默认：使用 Reactor 的 Schedulers.boundedElastic() 处理异步 I/O</li>
 * <li>自定义：使用用户提供的 ExecutorService</li>
 * </ul>
 */
class ToolExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ToolExecutor.class);

    /**
     * 拦截器链顺序：{@code order()} 越大越靠外；相同顺序保持稳定。
     */
    private static final Comparator<ToolCallInterceptor> INTERCEPTOR_ORDER =
            Comparator.comparingInt(ToolCallInterceptor::order).reversed();

    private final Toolkit toolkit;
    private final ToolRegistry toolRegistry;
    private final ToolGroupManager groupManager;
    private final ToolkitConfig config;
    private final ExecutorService executorService;
    private final List<ToolCallInterceptor> interceptors;
    private BiConsumer<ToolUseBlock, ToolResultBlock> userChunkCallback;

    /**
     * 使用 Reactor 调度器创建工具执行器（推荐）。
     */
    ToolExecutor(
            Toolkit toolkit,
            ToolRegistry toolRegistry,
            ToolGroupManager groupManager,
            ToolkitConfig config) {
        this(toolkit, toolRegistry, groupManager, config, null);
    }

    /**
     * 使用自定义执行器服务创建工具执行器。
     */
    ToolExecutor(
            Toolkit toolkit,
            ToolRegistry toolRegistry,
            ToolGroupManager groupManager,
            ToolkitConfig config,
            ExecutorService executorService) {
        this.toolkit = toolkit;
        this.toolRegistry = toolRegistry;
        this.groupManager = groupManager;
        this.config = config;
        this.executorService = executorService;
        this.interceptors = sortInterceptors(config);
    }

    /**
     * 仅在构造时排序拦截器（{@link #INTERCEPTOR_ORDER}）；空列表保持为空。
     *
     * @param config 携带已注册拦截器的工具集配置，可为 {@code null}
     * @return 已排序的不可变拦截器列表（最外层在前）
     */
    private static List<ToolCallInterceptor> sortInterceptors(ToolkitConfig config) {
        List<ToolCallInterceptor> registered =
                config != null ? config.getInterceptors() : List.of();
        return registered.isEmpty()
                ? List.of()
                : registered.stream().sorted(INTERCEPTOR_ORDER).toList();
    }

    /**
     * 设置用户定义的流式工具响应数据块回调。
     */
    void setChunkCallback(BiConsumer<ToolUseBlock, ToolResultBlock> callback) {
        this.userChunkCallback = callback;
    }

    /**
     * 获取用户定义的数据块回调。{@link Toolkit#copy()} 用它在构建期隔离复制时保留用户回调。
     */
    BiConsumer<ToolUseBlock, ToolResultBlock> getChunkCallback() {
        return this.userChunkCallback;
    }

    /**
     * 调用数据块回调，同时避免其阻碍其他回调。
     */
    private void invokeChunkCallback(
            String callbackType,
            BiConsumer<ToolUseBlock, ToolResultBlock> callback,
            ToolUseBlock toolUse,
            ToolResultBlock chunk) {
        try {
            callback.accept(toolUse, chunk);
        } catch (Exception e) {
            logger.warn(
                    "Chunk callback '{}' failed for tool '{}': {}",
                    callbackType,
                    toolUse.getName(),
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                    e);
        }
    }

    // ==================== 单次工具执行 ====================

    /**
     * 执行单次工具调用（仅核心执行：Tracer 与 {@link #executeCore}；不包含调度、超时、重试、关闭保护或 id/name 填充）。完整基础设施路径请使用 {@link #executeWithInfrastructure(ToolCallParam, ExecutionConfig)}。
     *
     * @param param 工具调用参数
     * @return 包含执行结果的 Mono
     */
    Mono<ToolResultBlock> execute(ToolCallParam param) {
        return execute(
                param,
                param.getRuntimeContext() == null
                        ? ToolRequestConfig.NONE
                        : param.getRuntimeContext().getToolRequestConfig(),
                null);
    }

    /**
     * 使用逐调用工具请求配置执行单次工具调用，并通过执行回调通知进度。这是唯一核心入口；单参数 {@link #execute(ToolCallParam)} 重载从显式运行时上下文解析请求配置，且不使用回调。
     */
    Mono<ToolResultBlock> execute(
            ToolCallParam param,
            ToolRequestConfig requestConfig,
            ToolExecutionCallback executionCallback) {
        return ActionObservations.observe(
                param, () -> executeUnobserved(param, requestConfig, executionCallback));
    }

    private Mono<ToolResultBlock> executeUnobserved(
            ToolCallParam param,
            ToolRequestConfig requestConfig,
            ToolExecutionCallback executionCallback) {
        return TracerRegistry.get()
                .callTool(
                        this.toolkit,
                        param,
                        () -> executeCore(param, requestConfig, executionCallback));
    }

    /**
     * 工具执行核心逻辑。
     *
     * <p>【学习提示】模型选择工具只是产生调用请求，不等于获得执行授权。这里的工具查找、
     * 激活检查和参数模式验证分别回答“工具是什么”“当前是否可用”“输入结构是否合法”；
     * 它们不能替代上层 PermissionEngine 的权限决策。直接调用 Toolkit 时，也不要误以为
     * 此方法会自动走完智能体循环中的所有权限流程。
     *
     * <p>处理以下步骤：
     * <ul>
     * <li>工具查找和验证</li>
     * <li>工具组激活检查</li>
     * <li>参数合并（预设与输入）</li>
     * <li>上下文合并</li>
     * <li>模式验证</li>
     * <li>实际工具调用</li>
     * </ul>
     */
    private Mono<ToolResultBlock> executeCore(
            ToolCallParam param,
            ToolRequestConfig requestConfig,
            ToolExecutionCallback executionCallback) {
        if (requestConfig == null) {
            requestConfig = ToolRequestConfig.NONE;
        }
        ToolUseBlock toolCall = param.getToolUseBlock();
        AgentTool tool = resolveTool(toolCall.getName(), requestConfig);

        if (tool == null) {
            return Mono.just(ToolResultBlock.error("Tool not found: " + toolCall.getName()));
        }

        // 检查工具激活状态。此门禁只适用于后端工具：通过请求配置注入的外部工具
        // 没有工具组限制且始终可调用，因此同名但未激活的
        // 后端工具不能拒绝它（模型看到的是外部覆盖工具，
        // 必须尊重该调用）。
        boolean fromRequestConfig = requestConfig.overrides(toolCall.getName());
        RegisteredToolFunction registered = toolRegistry.getRegisteredTool(toolCall.getName());
        if (!fromRequestConfig
                && registered != null
                && !groupManager.isActiveTool(toolCall.getName(), resolveActiveGroups(param))) {
            String errorMsg =
                    String.format(
                            "Unauthorized tool call: '%s' is not available", toolCall.getName());
            logger.warn(errorMsg);
            return Mono.just(ToolResultBlock.error(errorMsg));
        }

        // 按模式验证输入。使用与调用路径相同的方式解析有效参数
        // （原始内容有效时使用原始内容，否则使用已解析输入映射），避免
        // 仅提供 input 的工具调用因空指针派生的误导性错误而被拒绝。
        String validationError =
                ToolValidator.validateInput(
                        JsonUtils.resolveToolCallArgsJson(toolCall), tool.getParameters());
        if (validationError != null) {
            String errorMsg =
                    String.format(
                            "Parameter validation failed for tool '%s': %s\n"
                                    + "Please correct the parameters and try again.",
                            toolCall.getName(), validationError);
            logger.debug(errorMsg);
            return Mono.just(ToolResultBlock.error(errorMsg));
        }

        // 外部工具短路：可用性和模式验证通过后，直接暴露调用，
        // 不注入预设参数，也不本地执行。SchemaOnlyTool 和
        // @Tool(externalTool=true) 方法都进入此路径。
        if (tool instanceof ToolBase tb && tb.isExternalTool()) {
            if (config.getBeforeToolCall() == null) {
                return Mono.just(ToolResultBlock.suspended(toolCall));
            }
            return applyResultErrorMapping(
                            Mono.fromSupplier(
                                    () -> {
                                        config.getBeforeToolCall().accept(param);
                                        return ToolResultBlock.suspended(toolCall);
                                    }),
                            toolCall)
                    .map(result -> result.withIdAndName(toolCall.getId(), toolCall.getName()));
        }

        // 合并运行时上下文：参数级 > 工具集默认值
        RuntimeContext runtimeContext = param.getRuntimeContext();
        @SuppressWarnings("deprecation")
        ToolExecutionContext toolkitDefault = config.getDefaultContext();
        if (runtimeContext == null && toolkitDefault != null) {
            runtimeContext = RuntimeContext.builder().toolExecutionContext(toolkitDefault).build();
        } else if (runtimeContext != null && toolkitDefault != null) {
            ToolExecutionContext merged =
                    ToolExecutionContext.merge(
                            runtimeContext.asToolExecutionContext(), toolkitDefault);
            runtimeContext =
                    RuntimeContext.builder(runtimeContext).toolExecutionContext(merged).build();
        }
        final RuntimeContext effectiveRuntimeContext = runtimeContext;

        // 创建流式输出发射器
        ToolEmitter toolEmitter =
                new DefaultToolEmitter(
                        toolCall,
                        (use, chunk) -> {
                            SessionRecorder recorder =
                                    SessionRecorder.from(param.getRuntimeContext());
                            if (recorder != null)
                                recorder.append(
                                        "tool/chunk",
                                        Map.of(
                                                "toolCallId",
                                                use.getId(),
                                                "actionId",
                                                recorder.actionId(use.getId()),
                                                "chunk",
                                                chunk));
                            if (executionCallback != null)
                                invokeChunkCallback(
                                        "internal", executionCallback::onProgress, use, chunk);
                            if (userChunkCallback != null)
                                invokeChunkCallback("user", userChunkCallback, use, chunk);
                        });

        // 将输入与预设参数合并。预设值优先，使框架控制的
        // 参数对调用方和大语言模型保持不可修改。
        // 【学习提示】putAll 的先后顺序就是优先级：后写入的预设值覆盖模型输入。
        // 但后续拦截器仍可重写最终参数，因此拦截器属于可信应用逻辑，而非模型控制的数据。
        Map<String, Object> mergedInput = new HashMap<>();
        if (!param.getInput().isEmpty()) {
            mergedInput.putAll(param.getInput());
        } else if (!toolCall.getInput().isEmpty()) {
            mergedInput.putAll(toolCall.getInput());
        }
        if (registered != null) {
            mergedInput.putAll(registered.getPresetParameters());
        }

        // 构建最终执行参数
        ToolCallParam executionParam =
                ToolCallParam.builder()
                        .toolUseBlock(toolCall)
                        .input(mergedInput)
                        .agent(param.getAgent())
                        .runtimeContext(runtimeContext)
                        .emitter(toolEmitter)
                        .build();

        SessionRecorder recorder = SessionRecorder.from(runtimeContext);

        // 没有拦截器时，保留带可选最终检查的分派路径。
        if (interceptors.isEmpty()) {
            return applyResultErrorMapping(
                    dispatchRecord(recorder, executionParam, toolCall, mergedInput)
                            .then(Mono.defer(() -> invokeTool(tool, executionParam))),
                    toolCall);
        }

        // 拦截器链包装分派日志和真实执行：分派记录
        // 捕获拦截后的参数，拦截器拒绝时不生成
        // 允许或分派
        // 记录。智能体身份仅通过 agentId/agentName 传递；无智能体调用时为 null。
        Agent callingAgent = param.getAgent();
        String agentId = callingAgent != null ? callingAgent.getAgentId() : null;
        String agentName = callingAgent != null ? callingAgent.getName() : null;
        Function<ToolInvocation, Mono<ToolResultBlock>> terminal =
                iv ->
                        dispatchRecord(recorder, executionParam, toolCall, iv.input())
                                .then(
                                        Mono.defer(
                                                () ->
                                                        invokeTool(
                                                                tool,
                                                                ToolCallParam.builder(
                                                                                executionParam)
                                                                        .input(iv.input())
                                                                        .build())));
        return applyResultErrorMapping(
                        Mono.defer(
                                () ->
                                        applyInterceptorChain(
                                                interceptors,
                                                new ToolInvocation(
                                                        toolCall.getName(),
                                                        tool,
                                                        mergedInput,
                                                        effectiveRuntimeContext,
                                                        agentId,
                                                        agentName),
                                                terminal)),
                        toolCall)
                // 拦截器生成的结果（如拒绝）没有 id/name，需要补齐；
                // 工具结果已带这些字段。错误或空结果转换得到的结果也在此补齐
                // id/name（仅直接路径；批次路径会统一规范化）。
                .map(
                        result ->
                                result.getId() == null
                                        ? result.withIdAndName(toolCall.getId(), toolCall.getName())
                                        : result);
    }

    private Mono<ToolResultBlock> invokeTool(AgentTool tool, ToolCallParam param) {
        if (config.getBeforeToolCall() != null) {
            config.getBeforeToolCall().accept(param);
        }
        return tool.callAsync(param);
    }

    /**
     * 记录执行前状态快照，以及针对给定的拦截后参数的 {@code allowed} 决策和 {@code tool/dispatch} 日志；没有会话记录器时不执行任何操作。
     */
    private static Mono<Void> dispatchRecord(
            SessionRecorder recorder,
            ToolCallParam executionParam,
            ToolUseBlock toolCall,
            Map<String, Object> arguments) {
        if (recorder == null) {
            return Mono.empty();
        }
        return Mono.defer(
                () -> {
                    recorder.captureState(
                            RuntimeContext.resolveAgentState(
                                    executionParam.getRuntimeContext(), executionParam.getAgent()),
                            "before_tool");
                    recorder.append(
                            "tool/decision",
                            Map.of("toolCallId", toolCall.getId(), "decision", "allowed"));
                    return recorder.record(
                            "tool/dispatch",
                            Map.of(
                                    "toolCallId",
                                    toolCall.getId(),
                                    "actionId",
                                    recorder.actionId(toolCall.getId()),
                                    "name",
                                    toolCall.getName(),
                                    "arguments",
                                    arguments));
                });
    }

    /**
     * 围绕最终执行组装有序拦截器链（洋葱模式，与 {@code MiddlewareBase} 的委托结构相同）。
     *
     * <p>{@code interceptors} 必须已按 {@code order()} 降序稳定排序（见 {@link #sortInterceptors(ToolkitConfig)}）：较大值在外层，相同值保持注册顺序。每个 {@code intercept} 调用都包装在 {@link Mono#defer} 中，使同步抛出的异常转换为错误信号。
     *
     * <p>有意不按层归属错误：洋葱链中的错误信号无法可靠归因于某层，因此原样传播，再由调用方 {@link #applyResultErrorMapping(Mono, ToolUseBlock)} 映射。需要归因的拦截器应在异常消息中编码自身标识。
     *
     * @param interceptors 按 {@code order()} 降序排列的拦截器
     * @param invocation 进入最外层拦截器的调用快照
     * @param terminal 链端点：会话分派日志与真实工具执行
     * @return 链产生的结果（以最外层拦截器的结果为准）
     */
    private static Mono<ToolResultBlock> applyInterceptorChain(
            List<ToolCallInterceptor> interceptors,
            ToolInvocation invocation,
            Function<ToolInvocation, Mono<ToolResultBlock>> terminal) {
        Function<ToolInvocation, Mono<ToolResultBlock>> next = terminal;
        for (int i = interceptors.size() - 1; i >= 0; i--) {
            ToolCallInterceptor current = interceptors.get(i);
            Function<ToolInvocation, Mono<ToolResultBlock>> downstream = next;
            next = iv -> Mono.defer(() -> current.intercept(iv, downstream));
        }
        return next.apply(invocation);
    }

    /**
     * 统一映射工具执行错误：{@link ToolSuspendException} 转为挂起结果，观测或会话日志错误原样传播，其他错误及空结果转换为模型可见的 {@code ToolResultBlock.error}。
     *
     * @param execution 要映射错误和空信号的执行流水线
     * @param toolCall 工具调用，用于挂起结果标识和日志
     * @return 已将挂起、错误和空结果映射为结果块的流水线
     */
    private Mono<ToolResultBlock> applyResultErrorMapping(
            Mono<ToolResultBlock> execution, ToolUseBlock toolCall) {
        return execution
                .onErrorResume(
                        ToolSuspendException.class,
                        e -> {
                            // 将 ToolSuspendException 转换为挂起结果
                            logger.debug(
                                    "Tool '{}' suspended: {}",
                                    toolCall.getName(),
                                    e.getReason() != null ? e.getReason() : "no reason");
                            return Mono.just(ToolResultBlock.suspended(toolCall, e));
                        })
                .onErrorResume(
                        e -> {
                            if (ActionObservationException.causedBy(e)
                                    || SessionLogException.causedBy(e)) return Mono.error(e);
                            String errorMsg =
                                    e.getMessage() != null
                                            ? e.getMessage()
                                            : e.getClass().getSimpleName();
                            return Mono.just(
                                    ToolResultBlock.error("Tool execution failed: " + errorMsg));
                        })
                .switchIfEmpty(
                        Mono.just(
                                ToolResultBlock.error(
                                        "Tool execution failed: Tool completed without returning a"
                                                + " result")));
    }

    /**
     * 在执行器自己的 {@link #toolRegistry} 中按名称解析工具。合并策略（外部工具 → 隐藏后端 → 注册表）定义在 {@link ToolRequestConfig#resolveTool(String, ToolRegistry)}；此处将其应用于执行器<em>注入的</em> {@link #toolRegistry}/{@link #config}（生产环境中与工具集使用同一对象，注入方式使 {@code ToolExecutor} 保持可测试）。
     */
    private AgentTool resolveTool(String name, ToolRequestConfig requestConfig) {
        return (requestConfig != null ? requestConfig : ToolRequestConfig.NONE)
                .resolveTool(name, this.toolRegistry);
    }

    /**
     * 从工具调用运行时上下文解析本次调用的激活组；不可用时返回 {@code null}，回退到 {@code ToolGroupManager} 的共享激活标志。
     */
    private Collection<String> resolveActiveGroups(ToolCallParam param) {
        RuntimeContext rc = param.getRuntimeContext();
        if (rc != null && rc.getAgentState() != null) {
            return rc.getAgentState().getToolContext().getActivatedGroups();
        }
        return null;
    }

    // ==================== 批量工具执行 ====================

    /**
     * 执行多个工具调用，提供并发控制和完整逐调用基础设施（调度、超时、重试、关闭保护、id/name 填充）。
     *
     * @param toolCalls 待执行工具调用列表
     * @param parallel 是否并行执行
     * @param executionConfig 执行配置
     * @param agent 发起调用的智能体（可为 null）
     * @param agentRuntimeContext 智能体级运行时上下文（可为 null）
     * @return 包含结果列表的 Mono
     */
    Mono<List<ToolResultBlock>> executeAll(
            List<ToolUseBlock> toolCalls,
            boolean parallel,
            ExecutionConfig executionConfig,
            Agent agent,
            RuntimeContext agentRuntimeContext) {
        return executeAll(
                toolCalls,
                parallel,
                executionConfig,
                agent,
                agentRuntimeContext,
                ToolRequestConfig.NONE,
                null);
    }

    /**
     * 使用显式逐调用请求配置和执行回调执行多个工具调用。在按声明顺序汇总前，先通知每个最终结果。
     */
    Mono<List<ToolResultBlock>> executeAll(
            List<ToolUseBlock> toolCalls,
            boolean parallel,
            ExecutionConfig executionConfig,
            Agent agent,
            RuntimeContext agentRuntimeContext,
            ToolRequestConfig requestConfig,
            ToolExecutionCallback executionCallback) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Mono.just(List.of());
        }

        logger.debug("Executing {} tool calls (parallel={})", toolCalls.size(), parallel);

        Function<ToolUseBlock, Mono<ToolResultBlock>> execute =
                toolCall ->
                        executeWithInfrastructure(
                                        toolCall,
                                        executionConfig,
                                        agent,
                                        agentRuntimeContext,
                                        requestConfig,
                                        executionCallback)
                                // 两个钩子都位于 executeWithInfrastructure 内部
                                // 重试包装器之外，因此即使发生重试，
                                // 每个逻辑调用的 onStart/onResult 也只触发一次。
                                .doOnSubscribe(
                                        s -> {
                                            if (executionCallback != null)
                                                executionCallback.onStart(toolCall);
                                        })
                                .doOnNext(
                                        result -> {
                                            if (executionCallback != null)
                                                executionCallback.onResult(toolCall, result);
                                        });

        // 顺序模式无需分区，按声明顺序执行。
        if (!parallel) {
            List<Mono<ToolResultBlock>> monos = toolCalls.stream().map(execute).toList();
            return Flux.concat(monos).collectList();
        }

        // 并行模式按并发安全性分区：连续的安全工具并发执行
        // （通过 mergeSequential 保持输出顺序）；不安全工具（或无法检查的
        // 旧 AgentTool）各自形成串行槽位，使共享状态的
        // 两次调用不会重叠。
        List<Flux<ToolResultBlock>> chunks = new ArrayList<>();
        List<Mono<ToolResultBlock>> safeBatch = new ArrayList<>();
        for (ToolUseBlock toolCall : toolCalls) {
            Mono<ToolResultBlock> mono = execute.apply(toolCall);
            if (isConcurrencySafe(toolCall, requestConfig)) {
                safeBatch.add(mono);
            } else {
                if (!safeBatch.isEmpty()) {
                    chunks.add(Flux.mergeSequential(safeBatch));
                    safeBatch = new ArrayList<>();
                }
                chunks.add(mono.flux());
            }
        }
        if (!safeBatch.isEmpty()) {
            chunks.add(Flux.mergeSequential(safeBatch));
        }
        return Flux.concat(chunks).collectList();
    }

    /**
     * 判断 {@code toolCall} 对应工具能否与自身并行运行。通过 {@link #resolveTool} 解析，确保安全分类与实际执行工具一致（请求配置中的外部工具只有模式定义并将挂起，因此安全）。不继承 {@link ToolBase} 的旧 {@link AgentTool} 默认返回 {@code true}，保留 2.0 之前的并发行为。
     */
    private boolean isConcurrencySafe(ToolUseBlock toolCall, ToolRequestConfig requestConfig) {
        AgentTool tool = resolveTool(toolCall.getName(), requestConfig);
        if (tool instanceof ToolBase tb) {
            return tb.isConcurrencySafe();
        }
        return true;
    }

    /**
     * 带基础设施（调度、超时、重试、关闭保护）执行单次工具调用，并为结果填充调用的 id/name。
     */
    Mono<ToolResultBlock> executeWithInfrastructure(
            ToolUseBlock toolCall,
            ExecutionConfig executionConfig,
            Agent agent,
            RuntimeContext agentRuntimeContext,
            ToolRequestConfig requestConfig,
            ToolExecutionCallback executionCallback) {
        // 构建工具调用参数
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(toolCall)
                        .agent(agent)
                        .runtimeContext(agentRuntimeContext)
                        .build();

        return executeWithInfrastructure(param, executionConfig, requestConfig, executionCallback);
    }

    /**
     * 带完整基础设施执行单次工具调用，保留原始 {@link ToolCallParam} 的每个字段（包括调用方提供的输入）。
     */
    Mono<ToolResultBlock> executeWithInfrastructure(
            ToolCallParam param, ExecutionConfig executionConfig) {
        return executeWithInfrastructure(
                param,
                executionConfig,
                param.getRuntimeContext() == null
                        ? ToolRequestConfig.NONE
                        : param.getRuntimeContext().getToolRequestConfig(),
                null);
    }

    /**
     * 批量和直接工具调用共用的基础设施路径。
     *
     * <p>观测包装在超时与调度层外部，避免缓慢的观测提交被超时并重试。每次重试拥有独立的观测尝试。观测和会话日志失败保持为错误信号；其他失败转换为携带调用标识的模型可见工具错误。
     */
    private Mono<ToolResultBlock> executeWithInfrastructure(
            ToolCallParam param,
            ExecutionConfig executionConfig,
            ToolRequestConfig requestConfig,
            ToolExecutionCallback executionCallback) {
        ToolUseBlock toolCall = param.getToolUseBlock();

        Mono<ToolResultBlock> execution =
                ActionObservations.observe(
                        param,
                        () ->
                                applyTimeout(
                                        applyScheduling(
                                                executeUnobserved(
                                                        param, requestConfig, executionCallback)),
                                        executionConfig,
                                        toolCall));

        // 每次重试都拥有独立的观测尝试。
        execution = applyRetry(execution, executionConfig, toolCall);
        execution = applyShutdownGuard(execution);

        // 添加工具元数据和错误处理
        return execution
                .map(result -> result.withIdAndName(toolCall.getId(), toolCall.getName()))
                .onErrorResume(
                        e -> {
                            if (ActionObservationException.causedBy(e)
                                    || SessionLogException.causedBy(e)) return Mono.error(e);
                            logger.warn("Tool call failed: {}", toolCall.getName(), e);
                            String errorMsg = ExceptionUtils.getErrorMessage(e);
                            return Mono.just(
                                    ToolResultBlock.error("Tool execution failed: " + errorMsg)
                                            .withIdAndName(toolCall.getId(), toolCall.getName()));
                        });
    }

    // ==================== 基础设施方法 ====================

    private Mono<ToolResultBlock> applyScheduling(Mono<ToolResultBlock> execution) {
        if (executorService == null) {
            return execution.subscribeOn(Schedulers.boundedElastic());
        }
        return execution.subscribeOn(Schedulers.fromExecutor(executorService));
    }

    private Mono<ToolResultBlock> applyTimeout(
            Mono<ToolResultBlock> execution, ExecutionConfig config, ToolUseBlock toolCall) {
        if (config == null || config.getTimeout() == null) {
            return execution;
        }

        Duration timeout = config.getTimeout();
        logger.debug("Applied timeout: {} for tool: {}", timeout, toolCall.getName());

        return execution.timeout(
                timeout,
                Mono.error(new RuntimeException("Tool execution timeout after " + timeout)));
    }

    private Mono<ToolResultBlock> applyRetry(
            Mono<ToolResultBlock> execution, ExecutionConfig config, ToolUseBlock toolCall) {
        if (config == null || config.getMaxAttempts() == null || config.getMaxAttempts() <= 1) {
            return execution;
        }

        Integer maxAttempts = config.getMaxAttempts();
        Duration initialBackoff =
                config.getInitialBackoff() != null
                        ? config.getInitialBackoff()
                        : Duration.ofSeconds(1);
        Duration maxBackoff =
                config.getMaxBackoff() != null ? config.getMaxBackoff() : Duration.ofSeconds(10);
        Predicate<Throwable> retryOn =
                config.getRetryOn() != null ? config.getRetryOn() : error -> true;

        Retry retrySpec =
                Retry.backoff(maxAttempts - 1, initialBackoff)
                        .maxBackoff(maxBackoff)
                        .jitter(0.5)
                        .filter(
                                error ->
                                        !ActionObservationException.causedBy(error)
                                                && !SessionLogException.causedBy(error)
                                                && retryOn.test(error))
                        .doBeforeRetry(
                                signal ->
                                        logger.warn(
                                                "Retrying tool call '{}' (attempt {}/{}) due to:"
                                                    + " {}. The previous attempt is cancelled and"
                                                    + " may still be consuming resources.",
                                                toolCall.getName(),
                                                signal.totalRetriesInARow() + 1,
                                                maxAttempts - 1,
                                                signal.failure().getMessage(),
                                                signal.failure()));

        logger.debug(
                "Applied retry config: maxAttempts={} for tool: {}",
                maxAttempts,
                toolCall.getName());

        return execution.retryWhen(retrySpec);
    }

    /**
     * 让工具执行与全局关闭超时信号竞争。信号触发时取消工具 Mono 并发出错误，经 {@code onErrorResume} 转换为普通的 {@code ToolResultBlock.error}。
     */
    private Mono<ToolResultBlock> applyShutdownGuard(Mono<ToolResultBlock> execution) {
        Mono<ToolResultBlock> shutdownGuard =
                GracefulShutdownManager.getInstance()
                        .getShutdownTimeoutSignal()
                        .then(
                                Mono.error(
                                        new RuntimeException(
                                                "Tool execution timeout due to system graceful"
                                                        + " shutdown.")));
        return Mono.firstWithSignal(execution, shutdownGuard);
    }
}
