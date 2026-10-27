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
package io.agentscope.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.interruption.InterruptSource;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tracing.TracerRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * AgentScope 框架中所有智能体的抽象基类。
 *
 * <p>提供基本钩子集成、MsgHub 订阅者管理、中断处理、追踪，以及通过 StateModule 进行状态管理等公共能力。
 * 它不管理记忆，记忆由 ReActAgent 等具体实现负责。
 *
 * <p>设计思想：
 * <ul>
 * <li>AgentBase 提供基础设施（钩子、订阅、中断、状态），不承担领域逻辑</li>
 * <li>记忆管理委托给需要它的具体智能体（例如 ReActAgent）</li>
 * <li>状态管理实现 StateModule 接口</li>
 * <li>中断采用响应式模式：子类在适当检查点调用 checkInterruptedAsync()，通过 Mono 链传播 InterruptedException</li>
 * <li>观察模式允许智能体接收消息而不生成回复</li>
 * </ul>
 *
 * <p><b>线程安全：</b>基础生命周期按订阅维护执行状态。子类通过 callSerializationKey 定义串行化方式；
 * ReActAgent 将同一会话内的调用串行化。应在调用智能体之前配置钩子，而不是在调用执行期间修改配置。
 *
 * <p><b>中断机制：</b>
 * <pre>{@code
 * // External call to interrupt
 * agent.interrupt(userMsg);
 *
 * // Inside agent's Mono chain, at checkpoints:
 * return checkInterruptedAsync()
 *     .then(doWork())
 *     .flatMap(result -> checkInterruptedAsync().thenReturn(result));
 *
 * // AgentBase.call() catches the exception:
 * .onErrorResume(error -> {
 *     if (error instanceof InterruptedException) {
 *         return handleInterrupt(context, msg);
 *     }
 *     ...
 * });
 * }</pre>
 */
@SuppressWarnings("deprecation")
public abstract class AgentBase implements Agent {

    private final String agentId;
    private final String name;
    private final String description;
    private final List<Hook> hooks;
    private static final List<Hook> systemHooks = new CopyOnWriteArrayList<>();
    private final Map<String, List<AgentBase>> hubSubscribers = new ConcurrentHashMap<>();

    private static final Comparator<Hook> HOOK_COMPARATOR = Comparator.comparingInt(Hook::priority);

    /**
     * 显式智能体 ID 允许使用的字符。下游代码会将 ID 插入路径／命名空间片段
     * （例如远程文件系统规格、工作区会话索引），因此它必须是单个安全的片段。
     */
    private static final Pattern AGENT_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

    /**
     * 显式智能体 ID 的最大长度：下游会将 ID 用作单个路径片段。
     */
    private static final int MAX_AGENT_ID_LENGTH = 255;

    /**
     * 按键维护调用串行化队尾。每项保存该键最近入队调用的完成信号；
     * 同键的下一次调用串接在它之后，因此同键调用按 FIFO 顺序逐个执行，不同键则并行运行。
     * 参见 {@link #callSerializationKey(RuntimeContext)} 和 {@link #serializeOnKey(Object, Mono)}。
     */
    private final ConcurrentHashMap<Object, Mono<Void>> callGates = new ConcurrentHashMap<>();

    /**
     * AgentBase 的构造方法。
     *
     * @param name 智能体名称
     */
    public AgentBase(String name) {
        this(name, null, List.of());
    }

    /**
     * AgentBase 的构造方法。
     *
     * @param name 智能体名称
     * @param description 智能体描述
     */
    public AgentBase(String name, String description) {
        this(name, description, List.of());
    }

    /**
     * @deprecated 请改用 {@link #AgentBase(String, String, List)}。
     */
    @Deprecated
    public AgentBase(String name, String description, boolean checkRunning, List<Hook> hooks) {
        this(name, description, hooks);
    }

    /**
     * 带钩子的 AgentBase 构造方法。
     *
     * @param name 智能体名称
     * @param description 智能体描述
     * @param hooks 用于监测／拦截执行的钩子列表
     */
    public AgentBase(String name, String description, List<Hook> hooks) {
        this(name, description, hooks, null);
    }

    /**
     * 带钩子和显式智能体 ID 的 AgentBase 构造方法。
     *
     * @param name 智能体名称
     * @param description 智能体描述
     * @param hooks 用于监测／拦截执行的钩子列表
     * @param agentId 显式智能体 ID，参见 {@link #normalizeAgentId(String)}。空白时回退到随机 UUID。
     * 除非由外部管理 ID，否则请留为 null；活动智能体间的唯一性由调用方负责，框架不强制检查。
     * @throws IllegalArgumentException 非空白的 agentId 无效时抛出
     */
    public AgentBase(String name, String description, List<Hook> hooks, String agentId) {
        String normalized = normalizeAgentId(agentId);
        this.agentId = normalized != null ? normalized : UUID.randomUUID().toString();
        this.name = name;
        this.description = description;
        this.hooks = new CopyOnWriteArrayList<>(hooks != null ? hooks : List.of());
        this.hooks.addAll(systemHooks);
        sortHooks();
    }

    /**
     * 去掉显式智能体 ID 的首尾空白并验证。下游会将 ID 插入路径／命名空间片段，
     * 所以它必须是单个安全片段：符合 {@code [A-Za-z0-9._-]+}，不能全是点，
     * 长度不超过 {@value #MAX_AGENT_ID_LENGTH} 个字符。
     * 空白输入返回 {@code null}，以便调用方回退到生成 UUID。
     *
     * @param agentId 原始 ID，可以为 null
     * @return 去掉首尾空白后的 ID；空白时为 {@code null}
     * @throws IllegalArgumentException 非空白 ID 不符合格式时抛出
     */
    public static String normalizeAgentId(String agentId) {
        String normalized = agentId != null ? agentId.trim() : "";
        if (normalized.isEmpty()) {
            return null;
        }
        boolean invalid =
                normalized.length() > MAX_AGENT_ID_LENGTH
                        || !AGENT_ID_PATTERN.matcher(normalized).matches()
                        || normalized.chars().allMatch(c -> c == '.');
        if (invalid) {
            throw new IllegalArgumentException(
                    "Invalid agentId '"
                            + normalized
                            + "': must match [A-Za-z0-9._-]+, not be all dots, and be at most "
                            + MAX_AGENT_ID_LENGTH
                            + " characters");
        }
        return normalized;
    }

    @Override
    public final String getAgentId() {
        return agentId;
    }

    @Override
    public final String getName() {
        return name;
    }

    @Override
    public final String getDescription() {
        return description != null ? description : Agent.super.getDescription();
    }

    /**
     * @deprecated 不再强制执行；会话级串行化负责处理并发。
     */
    @Deprecated
    public final boolean isCheckRunning() {
        return false;
    }

    /**
     * 处理输入消息列表，执行钩子并生成回复。
     *
     * <p>启用遥测后会采集追踪数据。
     *
     * @param msgs 输入消息列表
     * @return 回复消息
     */
    @Override
    public final Mono<Msg> call(List<Msg> msgs) {
        return callInternal(msgs, null, this::doCall);
    }

    /**
     * 每个 {@code call()} 重载都会调用的扩展点，允许子类用额外中间件包装整次调用
     * （例如 {@code ReActAgent} 的 {@code onAgent} 链）。
     *
     * <p>默认实现将非空 {@code context} 附加到 Reactor Context，然后直接委托给 {@link #runLifecycle}。
     * 覆盖此方法的子类最终必须调用 {@code runLifecycle(msgs, doCallFn)}，
     * 以执行标准生命周期（关闭保护、串行化门控、前后钩子及追踪）。
     *
     * @param msgs 输入消息列表
     * @param context 调用方为本次调用提供的 {@link RuntimeContext}，或 {@code null}
     * @param doCallFn 具体调用实现（{@link #doCall} 或结构化输出变体）
     * @return 回复消息
     */
    protected Mono<Msg> callInternal(
            List<Msg> msgs, RuntimeContext context, Function<List<Msg>, Mono<Msg>> doCallFn) {
        Mono<Msg> lifecycle = runLifecycle(msgs, doCallFn);
        return context == null
                ? lifecycle
                : lifecycle.contextWrite(c -> c.put(RUNTIME_CONTEXT_KEY, context));
    }

    /**
     * 已获准进入的会话所使用的轻量控制对象；绝不持有 RuntimeContext。
     */
    private final Map<Object, RunControl> runningCalls = new ConcurrentHashMap<>();

    /**
     * 会话级中断仅针对当前已获准使用此键执行的调用。
     */
    protected final void interruptRunning(Object key, InterruptSource source, Msg message) {
        RunControl control = runningCalls.get(key);
        if (control != null) {
            control.interrupt(source, message);
        }
    }

    /**
     * 在 Reactor Context 中携带 {@link #beforeAgentExecution(List, RuntimeContext, RunControl)}
     * 返回的单次调用作用域对象的键。维护单次调用状态的智能体（例如 {@code ReActAgent} 的 {@code CallExecution}）
     * 在 {@link #doCall}／{@link #handleInterrupt} 中从 Context 取回它，
     * 确保同一实例上的并发调用绝不共享可变的单次调用状态。
     */
    public static final String CALL_SCOPE_KEY = "io.agentscope.core.agent.AgentBase.callScope";

    /**
     * 在 Reactor Context 中携带调用方所提供的单次调用 {@link RuntimeContext} 的键。
     * 由 {@code call(msgs, context)}／{@code stream(..., context)}／{@code streamEvents(msgs, context)}
     * 重载通过 {@code contextWrite} 设置，并在此处的调用入口读取，
     * 使 RC 按订阅传递（并发安全），而不是通过共享实例字段传递。
     */
    public static final String RUNTIME_CONTEXT_KEY =
            "io.agentscope.core.agent.AgentBase.runtimeContext";

    /**
     * 在 Reactor Context 中携带本次调用优雅关闭 {@code requestId} 的键
     * （由 {@link GracefulShutdownManager#registerRequest} 生成）。
     * 按订阅传递，使单个智能体实例处理多个并发调用时，关闭过程中的中断／保存仍能准确指向进行中的调用。
     * 若以智能体 ID 跟踪关闭，则会将并发调用错误地合并为单项。
     */
    public static final String SHUTDOWN_REQUEST_ID_KEY =
            "io.agentscope.core.agent.AgentBase.shutdownRequestId";

    /**
     * 共享的 {@code call()} 生命周期：获取执行资源，然后在 {@code deferContextual} 中
     * 按订阅读取调用方提供的 {@link RuntimeContext}，执行
     * {@link #beforeAgentExecution(List, RuntimeContext, RunControl)} 以返回本次调用作用域，
     * 通过 Reactor Context 携带该作用域，执行带错误处理的 preCall → doCall → postCall 链，
     * 并在终止时释放执行资源。
     */
    protected Mono<Msg> runLifecycle(List<Msg> msgs, Function<List<Msg>, Mono<Msg>> doCallFn) {
        // 【学习提示】这里返回的是执行描述，而不是立即执行。deferContextual 在订阅时读取
        // 调用方上下文，因此同一个智能体对象可以为不同会话建立各自的执行作用域。
        return Mono.using(
                this::acquireExecution,
                resource -> Mono.deferContextual(cv -> runInContext(msgs, doCallFn, cv)),
                this::releaseExecution,
                true);
    }

    private Mono<Msg> runInContext(
            List<Msg> inputMsgs,
            Function<List<Msg>, Mono<Msg>> doCallFn,
            reactor.util.context.ContextView cv) {
        RuntimeContext rc = cv.getOrDefault(RUNTIME_CONTEXT_KEY, null);
        RunControl supplied = cv.getOrDefault(RunControl.CONTEXT_KEY, null);
        boolean managed = supplied != null && supplied.belongsTo(getAgentId());
        RunControl control =
                managed
                        ? supplied
                        : new RunControl(getAgentId(), rc == null ? null : rc.getRunId());
        if (!managed) {
            control.queue();
        }
        Object gateKey = callSerializationKey(rc);
        GracefulShutdownManager shutdown = GracefulShutdownManager.getInstance();
        String requestId = shutdown.registerRequest(this, control);
        Runnable retire =
                () -> {
                    if (gateKey != null) {
                        runningCalls.remove(gateKey, control);
                    }
                    shutdown.unregisterRequest(requestId);
                };
        // 每个订阅独有的可变副本：单次调用扩展点对输入所做的调整
        // （例如 onAgentStateReady 中间件）绝不会改动调用方的列表。
        List<Msg> msgs = new ArrayList<>(inputMsgs != null ? inputMsgs : List.of());
        // 仅在获准进入后解析会话状态。在释放队列门控前退役，
        // 以免前次尝试的清理删除重试新建的注册信息。
        Mono<Msg> lifecycle =
                Mono.defer(() -> runLifecycleBody(msgs, rc, doCallFn, requestId, control, gateKey))
                        .doOnTerminate(retire)
                        .doOnCancel(retire);
        Mono<Msg> gated = gateKey == null ? lifecycle : serializeOnKey(gateKey, lifecycle);
        return control.guard(gated)
                .singleOrEmpty()
                .doOnSuccess(
                        value -> {
                            if (!managed) control.finish(AgentRun.Status.COMPLETED);
                        })
                .doOnError(
                        error -> {
                            if (!managed) control.finish(AgentRun.Status.FAILED);
                        })
                .doOnCancel(
                        () -> {
                            if (!managed) control.cancel();
                        })
                .contextWrite(
                        c ->
                                requestId == null || requestId.isEmpty()
                                        ? c
                                        : c.put(SHUTDOWN_REQUEST_ID_KEY, requestId))
                // 也要注销尚未创建生命周期主体、仍在排队时被取消的调用。
                .doFinally(signal -> shutdown.unregisterRequest(requestId));
    }

    private Mono<Msg> runLifecycleBody(
            List<Msg> msgs,
            RuntimeContext rc,
            Function<List<Msg>, Mono<Msg>> doCallFn,
            String requestId,
            RunControl control,
            Object gateKey) {
        GracefulShutdownManager.getInstance().ensureAcceptingRequests();
        if (!control.start()) {
            return Mono.error(
                    new java.util.concurrent.CancellationException("Agent run cancelled"));
        }
        // 状态就绪中间件可能在获取资源期间中断调用。
        // 先注册其控制对象；生命周期退役会在每种终止信号下移除它。
        if (gateKey != null) {
            runningCalls.put(gateKey, control);
        }
        // 【学习提示】资源生命周期必须同时覆盖成功、失败和取消。下面三个清理分支都释放
        // 本次执行作用域，避免客户端断开订阅后仍占有会话写入资源。
        return Mono.usingWhen(
                acquireCallExecution(msgs, rc, control),
                scope -> executeCallBody(msgs, doCallFn, requestId, control, gateKey, scope),
                scope -> releaseCallExecution(scope, "completed"),
                (scope, error) -> releaseCallExecution(scope, "failed"),
                scope -> releaseCallExecution(scope, "cancelled"));
    }

    /**
     * 持久化执行所有权的异步资源边界。
     */
    protected Mono<Object> acquireCallExecution(
            List<Msg> msgs, RuntimeContext rc, RunControl control) {
        return Mono.fromSupplier(
                () -> {
                    Object scope = beforeAgentExecution(msgs, rc, control);
                    return scope == null ? this : scope;
                });
    }

    /**
     * 在传递完成／错误之前运行，也处理订阅者取消。
     */
    protected Mono<Void> releaseCallExecution(Object scope, String status) {
        return Mono.empty();
    }

    private Mono<Msg> executeCallBody(
            List<Msg> msgs,
            Function<List<Msg>, Mono<Msg>> doCallFn,
            String requestId,
            RunControl control,
            Object gateKey,
            Object scope) {
        // 将本次调用解析出的会话级状态绑定到跟踪中的关闭请求，确保优雅关闭时
        // 中断／保存的是准确的 (userId, sessionId) 会话，而不是通过智能体的
        // 无参“最近活跃”访问方法获取状态。
        GracefulShutdownManager.getInstance().bindRequestState(requestId, stateForCall(scope));
        List<Msg> acceptedInputs = inputsForCall(scope, msgs);
        Mono<Msg> body =
                TracerRegistry.get()
                        .callAgent(
                                this,
                                msgs,
                                () ->
                                        notifyPreCall(acceptedInputs, scope)
                                                .flatMap(doCallFn)
                                                .flatMap(this::notifyPostCall)
                                                .onErrorResume(
                                                        createErrorHandler(
                                                                control,
                                                                msgs.toArray(new Msg[0]))));
        body = body.doOnNext(result -> onCallResult(scope, result));
        Mono<Msg> scoped =
                scope == null ? body : body.contextWrite(c -> c.put(CALL_SCOPE_KEY, scope));
        // 嵌套调用拥有独立的控制对象；只有最外层执行接收此句柄。
        RuntimeContext runtime = runtimeForCall(scope);
        return scoped.contextWrite(
                c ->
                        runtime == null
                                ? c.delete(RunControl.CONTEXT_KEY)
                                : c.delete(RunControl.CONTEXT_KEY)
                                        .put(RUNTIME_CONTEXT_KEY, runtime));
    }

    protected List<Msg> inputsForCall(Object scope, List<Msg> inputs) {
        return inputs;
    }

    protected void onCallResult(Object scope, Msg result) {}

    protected RuntimeContext runtimeForCall(Object scope) {
        return null;
    }

    /**
     * 返回串行化共享此键的并发调用所用的键：相同非空键的调用按 FIFO 顺序逐个运行，
     * 不同键（或 {@code null} 键）的调用并发运行。默认返回 {@code null}（不串行化）。
     * {@code ReActAgent} 返回其 {@code (userId, sessionId)} 槽位键，
     * 使同一会话的调用不会破坏共享会话状态，而不同会话仍可并行运行。
     *
     * @param rc 调用方提供的单次调用 {@link RuntimeContext}（可以为 {@code null}）
     * @return 串行化键；返回 {@code null} 则不串行化
     */
    protected Object callSerializationKey(RuntimeContext rc) {
        return null;
    }

    /**
     * 将 {@code action} 与共享 {@code key} 的其他操作串行化：本次调用等待同键的前一个入队调用
     * 终止后才运行，并成为下一个同键调用等待的队尾。
     * 任何终止信号（完成、错误或取消）都会释放槽位，避免失败／取消的调用阻塞队列。
     */
    private <T> Mono<T> serializeOnKey(Object key, Mono<T> action) {
        // 【学习提示】这不是全局互斥锁：每个会话键维护自己的完成信号链。
        // 同键等待前驱，不同键互不等待；doFinally 释放信号，保证异常和取消也能推进队列。
        return Mono.defer(
                () -> {
                    Sinks.Empty<Void> release = Sinks.empty();
                    @SuppressWarnings("unchecked")
                    Mono<Void>[] previous = new Mono[1];
                    Mono<Void> tail =
                            callGates.compute(
                                    key,
                                    (k, existing) -> {
                                        previous[0] = existing == null ? Mono.empty() : existing;
                                        // 队列中间被取消的条目必须继续等待
                                        // 其前驱。否则，
                                        // A -> B(cancelled) -> C 可能在 A 仍持有
                                        // 会话状态时就允许 C 进入。
                                        return previous[0].then(release.asMono()).cache();
                                    });
                    tail.subscribe(
                            ignored -> {},
                            ignored -> callGates.remove(key, tail),
                            () -> callGates.remove(key, tail));
                    return previous[0].then(action).doFinally(signal -> release.tryEmitEmpty());
                });
    }

    /**
     * 处理多条输入消息，执行钩子并生成结构化输出。
     *
     * <p>启用遥测后会采集追踪数据。
     *
     * @param msgs 输入消息列表
     * @param structuredOutputClass 定义输出结构的类
     * @return 在元数据中携带结构化数据的回复消息
     */
    @Override
    public final Mono<Msg> call(List<Msg> msgs, Class<?> structuredOutputClass) {
        return callInternal(msgs, null, m -> doCall(m, structuredOutputClass));
    }

    /**
     * 处理多条输入消息，执行钩子并生成结构化输出。
     *
     * <p>启用遥测后会采集追踪数据。
     *
     * @param msgs 输入消息列表
     * @param schema 定义输出结构的 com.fasterxml.jackson.databind.JsonNode 实例
     * @return 在元数据中携带结构化数据的回复消息
     */
    @Override
    public final Mono<Msg> call(List<Msg> msgs, JsonNode schema) {
        return callInternal(msgs, null, m -> doCall(m, schema));
    }

    /**
     * 处理多条输入消息的内部实现。子类必须在此实现各自的具体逻辑。
     *
     * @param msgs 输入消息列表
     * @return 回复消息
     */
    protected abstract Mono<Msg> doCall(List<Msg> msgs);

    /**
     * 处理多条消息并生成结构化输出的内部实现。
     * 支持结构化输出的子类必须覆盖此方法。默认实现抛出 UnsupportedOperationException。
     *
     * @param msgs 输入消息列表
     * @param structuredOutputClass 定义结构的类
     * @return 在元数据中携带结构化数据的回复消息
     */
    protected Mono<Msg> doCall(List<Msg> msgs, Class<?> structuredOutputClass) {
        return Mono.error(
                new UnsupportedOperationException(
                        "Structured output not supported by " + getClass().getSimpleName()));
    }

    /**
     * 处理多条消息并生成结构化输出的内部实现。
     * 支持结构化输出的子类必须覆盖此方法。默认实现抛出 UnsupportedOperationException。
     *
     * @param msgs 输入消息列表
     * @param outputSchema 定义结构的 com.fasterxml.jackson.databind.JsonNode 实例
     * @return 在元数据中携带结构化数据的回复消息
     */
    protected Mono<Msg> doCall(List<Msg> msgs, JsonNode outputSchema) {
        return Mono.error(
                new UnsupportedOperationException(
                        "Structured output not supported by " + outputSchema.asText()));
    }

    public static void addSystemHook(Hook hook) {
        systemHooks.add(hook);
    }

    public static void removeSystemHook(Hook hook) {
        systemHooks.remove(hook);
    }

    /**
     * @deprecated 子类应通过 RuntimeContext 实现会话级中断。
     */
    @Deprecated
    @Override
    public void interrupt() {}

    /**
     * @deprecated 子类应通过 RuntimeContext 实现会话级中断。
     */
    @Deprecated
    @Override
    public void interrupt(Msg msg) {}

    /**
     * @deprecated 子类应通过 RuntimeContext 实现会话级中断。
     */
    @Deprecated
    public void interrupt(InterruptSource source) {}

    /**
     * @deprecated 不再需要；ReActAgent 使用每次执行独立的 InterruptControl。
     */
    @Deprecated
    protected Mono<Void> checkInterruptedAsync() {
        return Mono.empty();
    }

    /**
     * @deprecated 空操作；中断状态归执行所有。
     */
    @Deprecated
    protected void resetInterruptFlag() {}

    /**
     * 为 {@code call()} 调用获取执行资源。
     * 用作 {@link Mono#using} 的 {@code resourceSupplier}，确保在完成、错误或取消时
     * 始终调用 {@link #releaseExecution}。
     *
     * @return 当前智能体实例
     */
    private AgentBase acquireExecution() {
        GracefulShutdownManager.getInstance().ensureAcceptingRequests();
        return this;
    }

    private void releaseExecution(AgentBase resource) {
        afterAgentExecution();
    }

    /**
     * 为 call() 方法创建错误处理器。
     * 对 InterruptedException 进行特殊处理并委托给 handleInterrupt；其他错误则通知钩子。
     *
     * @param originalArgs 传给 handleInterrupt 的原始参数
     * @return 能适当处理错误的函数
     */
    private Function<Throwable, Mono<Msg>> createErrorHandler(
            RunControl control, Msg... originalArgs) {
        return error -> {
            if (error instanceof InterruptedException
                    || (error.getCause() instanceof InterruptedException)) {
                return handleInterrupt(control.interruption().toContext(), originalArgs);
            }
            return notifyError(error).then(Mono.error(error));
        };
    }

    /**
     * @deprecated 无操作占位实现。
     */
    @Deprecated
    protected AtomicBoolean getInterruptFlag() {
        return new AtomicBoolean(false);
    }

    /**
     * @deprecated 返回 USER。中断来源归执行所有。
     */
    @Deprecated
    protected InterruptSource getInterruptSource() {
        return InterruptSource.USER;
    }

    /**
     * 观察消息但不生成回复。
     * 允许智能体接收其他智能体或环境的消息而无需响应，常用于多智能体协作场景。
     *
     * <p>常见实现方式：
     * <ul>
     * <li>无状态智能体：不需要观察时使用空实现</li>
     * <li>有状态智能体：将消息存入记忆／上下文供后续调用使用</li>
     * <li>协作型智能体：更新共享知识或触发副作用</li>
     * </ul>
     *
     * @param msg 要观察的消息
     * @return 观察完成时结束的 Mono
     */
    protected Mono<Void> doObserve(Msg msg) {
        return Mono.empty();
    }

    /**
     * 处理执行期间发生的中断。
     * 子类必须实现此方法，根据中断上下文提供恢复逻辑。
     *
     * <p>实现建议：
     * <ul>
     * <li>简单智能体：返回基本的中断确认消息</li>
     * <li>复杂智能体：生成包含待处理操作或部分结果的摘要</li>
     * <li>有状态智能体：确保返回前妥善保存状态</li>
     * </ul>
     *
     * @param context 包含中断元数据的中断上下文
     * @param originalArgs 传给 call() 方法的原始参数（空、单个 Msg 或 List）
     * @return 返回给用户的恢复消息
     */
    protected abstract Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs);

    /**
     * 返回智能体的可变运行时状态；如果此类智能体不维护 {@link AgentState}，则返回 {@code null}。
     */
    public AgentState getAgentState() {
        return null;
    }

    /**
     * 在 {@code call}／流式支持的调用开始时执行，位于 {@link #acquireExecution} 之后、任何钩子之前。
     * {@link io.agentscope.core.ReActAgent} 通过它从传入的 {@link RuntimeContext} 激活单次调用的会话槽位，
     * 并返回新构建的单次调用作用域对象，以 {@link #CALL_SCOPE_KEY} 为键在 Reactor Context 中传递。
     * 在此直接返回作用域，而不是通过独立访问器获取，可避免构造到捕获之间被并发调用覆盖共享字段的时间窗口。
     *
     * @param msgs 调用方传给 {@code call()} 的消息
     * @param rc 调用方提供的单次调用 {@link RuntimeContext}；未提供时为 {@code null}
     * （从 Reactor Context 读取，因此并发安全）
     * @param control 本次执行独立的中断与取消控制
     * @return 本次调用作用域对象；此类智能体不维护作用域时为 {@code null}
     */
    protected Object beforeAgentExecution(List<Msg> msgs, RuntimeContext rc, RunControl control) {
        return null;
    }

    /**
     * 在 {@code Mono.using} 清理过程中、清除运行状态之前调用。
     * 与 {@link #beforeAgentExecution(List, RuntimeContext, RunControl)} 配对。默认不执行任何操作。
     */
    protected void afterAgentExecution() {}

    /**
     * 获取此智能体的钩子列表。
     * 设为 protected，以便子类访问钩子并实现自定义通知逻辑。
     *
     * @return 钩子列表
     */
    public List<Hook> getHooks() {
        return hooks;
    }

    /**
     * 动态向智能体添加钩子。
     *
     * <p>执行期间可以添加钩子来提供临时能力，常用于结构化输出处理或其他短期行为。
     *
     * @param hook 要添加的钩子
     */
    protected void addHook(Hook hook) {
        if (hook != null) {
            hooks.add(hook);
            sortHooks();
        }
    }

    private void sortHooks() {
        this.hooks.sort(HOOK_COMPARATOR);
    }

    /**
     * 动态移除智能体的钩子。
     *
     * <p>不再需要钩子时应移除，避免内存泄漏和意外副作用。
     *
     * @param hook 要移除的钩子
     */
    protected void removeHook(Hook hook) {
        if (hook != null) {
            hooks.remove(hook);
        }
    }

    /**
     * 获取按优先级排序的钩子（数值越小，优先级越高）。
     * 同优先级钩子保持注册顺序。
     *
     * @return 排序后的钩子列表
     */
    public List<Hook> getSortedHooks() {
        return hooks;
    }

    /**
     * 返回在钩子运行前填入 {@link PreCallEvent} 的初始系统消息。
     *
     * <p>默认实现返回 {@code null}。子类（例如 {@code ReActAgent}）覆盖此方法，
     * 根据配置的 {@code sysPrompt} 构建系统消息。
     *
     * @param callScope 调用入口捕获的单次调用作用域（可以为 {@code null}）；
     * 允许子类解析本次调用的 {@link RuntimeContext} 供系统提示中间件使用，而不读取共享实例字段
     * @return 初始系统消息；不存在时返回空 Mono
     */
    protected Mono<Msg> seedSystemMsg(Object callScope) {
        return Mono.empty();
    }

    /**
     * 返回用于初始化本次调用前置记忆快照的 {@link AgentState}，快照取自它的会话缓冲区。
     * 默认读取智能体级 {@link #getAgentState()}；具有单次调用作用域的子类（例如 {@code ReActAgent}）
     * 返回该作用域的会话状态，以保证快照在并发下正确。
     *
     * @param callScope 调用入口捕获的单次调用作用域（可以为 {@code null}）
     * @return 要拍摄快照的状态；不存在时为 {@code null}
     */
    protected AgentState stateForCall(Object callScope) {
        return getAgentState();
    }

    /**
     * 在 {@link PreCallEvent} 钩子运行后，以最终系统消息为参数调用。
     *
     * <p>默认不执行任何操作。子类（例如 {@code ReActAgent}）覆盖此方法，
     * 将系统消息保存到本次调用独有的 {@code AtomicReference}，
     * 供后续事件（{@code PreReasoningEvent}、{@code PreSummaryEvent}）使用。
     *
     * @param systemMsg 所有 PreCall 钩子处理后的系统消息（可以为 null）
     * @param callScope 调用入口捕获的单次调用作用域
     * （见 {@link #beforeAgentExecution(List, RuntimeContext, RunControl)}）；可以为 {@code null}
     */
    protected void consumeSystemMsgAfterPreCall(Msg systemMsg, Object callScope) {}

    /**
     * 通知所有钩子智能体即将开始（preCall 钩子）。
     *
     * <p>事件的 {@code inputMessages} 包含完整消息视图：先是当前记忆快照，
     * 随后是传给 {@code call()} 的 {@code callArgs}。钩子可以在尾部追加非 SYSTEM 消息。
     * 禁止通过 {@code setInputMessages} 注入 {@link MsgRole#SYSTEM} 消息，本方法结束时会检测；
     * 应改用 {@link PreCallEvent#setSystemMessage} 或 {@link PreCallEvent#appendSystemContent}。
     *
     * <p>钩子运行后，通过 {@link #consumeSystemMsgAfterPreCall(Msg, Object)} 交接系统消息，
     * 仅返回尾部（超出快照边界的消息），供 {@code doCall} 加入记忆。
     *
     * @param callArgs 调用方传给 {@code call()} 的消息
     * @return 包含 {@code doCall} 应加入记忆的新尾部消息的 Mono
     */
    private Mono<List<Msg>> notifyPreCall(List<Msg> callArgs, Object callScope) {
        // 在钩子执行前，从本次调用作用域状态获取快照（钩子执行前视图）。
        List<Msg> snapshot = List.of();
        AgentState agentState = stateForCall(callScope);
        if (agentState != null) {
            snapshot = agentState.getContext();
        }
        final int snapshotSize = snapshot.size();

        // 为钩子构建完整输入：快照 + callArgs
        List<Msg> fullInput = new ArrayList<>(snapshot);
        if (callArgs != null) {
            fullInput.addAll(callArgs);
        }

        PreCallEvent event = new PreCallEvent(this, fullInput);

        Mono<PreCallEvent> result =
                seedSystemMsg(callScope).doOnNext(event::setSystemMessage).thenReturn(event);
        for (Hook hook : getSortedHooks()) {
            result = result.flatMap(hook::onEvent);
        }

        return result.map(
                e -> {
                    // 将系统消息交给单次调用状态
                    consumeSystemMsgAfterPreCall(e.getSystemMessage(), callScope);

                    // 提取尾部：快照边界之后追加的消息
                    List<Msg> currentInput = e.getInputMessages();
                    List<Msg> tail;
                    if (currentInput == null || currentInput.size() <= snapshotSize) {
                        tail = List.of();
                    } else {
                        tail =
                                new ArrayList<>(
                                        currentInput.subList(snapshotSize, currentInput.size()));
                    }

                    // 保护措施（仅 ReActAgent）：钩子不能向尾部注入 SYSTEM 消息，
                    // 因为尾部会持久化到记忆中，SYSTEM 消息会
                    // 不断累积。无记忆的智能体（例如 UserAgent）则可以合法地
                    // 将 SYSTEM 消息作为调用参数传入。
                    if (AgentBase.this instanceof io.agentscope.core.ReActAgent) {
                        for (Msg msg : tail) {
                            if (msg != null && msg.getRole() == MsgRole.SYSTEM) {
                                throw new IllegalStateException(
                                        "Hooks must not inject SYSTEM messages into"
                                                + " PreCallEvent.inputMessages. Use"
                                                + " event.setSystemMessage() or"
                                                + " event.appendSystemContent() instead.");
                            }
                        }
                    }

                    return tail;
                });
    }

    /**
     * 通知所有钩子执行完成（postCall 钩子）。
     * 通知钩子后，将消息广播给所有订阅者。
     *
     * @param finalMsg 最终消息
     * @return 包含可能已被修改的最终消息的 Mono
     */
    private Mono<Msg> notifyPostCall(Msg finalMsg) {
        if (finalMsg == null) {
            return Mono.error(new IllegalStateException("Agent returned null message"));
        }
        PostCallEvent event = new PostCallEvent(this, finalMsg);
        Mono<PostCallEvent> result = Mono.just(event);
        for (Hook hook : getSortedHooks()) {
            result = result.flatMap(hook::onEvent);
        }
        // 钩子执行后，广播给订阅者
        return result.map(PostCallEvent::getFinalMessage)
                .flatMap(msg -> broadcastToSubscribers(msg).thenReturn(msg));
    }

    /**
     * 向所有钩子通知错误。
     *
     * @param error 错误
     * @return 所有钩子获知错误后完成的 Mono
     */
    private Mono<Void> notifyError(Throwable error) {
        ErrorEvent event = new ErrorEvent(this, error);
        return Flux.fromIterable(getSortedHooks()).flatMap(hook -> hook.onEvent(event)).then();
    }

    /**
     * 移除指定 MsgHub 的所有订阅者。
     * 通常在 MsgHub 被销毁或重置时调用；调用后智能体不再接收该 hub 的消息。
     *
     * @param hubId MsgHub 标识符
     */
    public void removeSubscribers(String hubId) {
        hubSubscribers.remove(hubId);
    }

    /**
     * 重置指定 MsgHub 的订阅者列表。
     * 用新列表替换该 hub 的现有订阅者，通常由 MsgHub 在订阅拓扑变化时调用。
     *
     * @param hubId MsgHub 标识符
     * @param subscribers 新订阅者列表（会复制）
     */
    public void resetSubscribers(String hubId, List<AgentBase> subscribers) {
        hubSubscribers.put(hubId, new ArrayList<>(subscribers));
    }

    /**
     * 检查智能体是否有订阅者。
     * 订阅者是接收通过 MsgHub 发布消息的智能体。
     *
     * @return 至少有一个订阅者时为 true
     */
    public boolean hasSubscribers() {
        return !hubSubscribers.isEmpty()
                && hubSubscribers.values().stream().anyMatch(list -> !list.isEmpty());
    }

    /**
     * 获取所有 MsgHub 的订阅者总数。
     * 订阅者是接收通过 MsgHub 发布消息的智能体。
     *
     * @return 订阅者总数
     */
    public int getSubscriberCount() {
        return hubSubscribers.values().stream().mapToInt(List::size).sum();
    }

    /**
     * 向所有 MsgHub 的全部订阅者广播消息。
     * 每次智能体调用后自动执行，用于实现 MsgHub 自动广播。
     *
     * @param msg 要广播的消息
     * @return 所有订阅者观察完消息后完成的 Mono
     */
    private Mono<Void> broadcastToSubscribers(Msg msg) {
        if (hubSubscribers.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(hubSubscribers.values())
                .flatMap(Flux::fromIterable)
                .flatMap(subscriber -> subscriber.observe(msg))
                .then();
    }

    /**
     * 观察单条消息但不生成回复。
     * 此公共 API 委托给 doObserve 实现。
     *
     * @param msg 要观察的消息
     * @return 观察完成时结束的 Mono
     */
    @Override
    public final Mono<Void> observe(Msg msg) {
        return doObserve(msg);
    }

    /**
     * 观察多条消息但不生成回复。
     * 此公共 API 委托给 doObserve 实现。
     *
     * @param msgs 要观察的消息列表
     * @return 全部观察完成时结束的 Mono
     */
    @Override
    public final Mono<Void> observe(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(msgs).flatMap(this::doObserve).then();
    }

    /**
     * 对多条输入消息进行流式处理。
     *
     * @param msgs 输入消息列表
     * @param options 流式配置选项
     * @return 执行期间发出的事件 Flux
     * @deprecated 自 2.0.0 起弃用，计划移除。请使用 {@code ReActAgent#streamEvents(List)} 获取细粒度的 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
        return createEventStream(options, () -> call(msgs));
    }

    /**
     * 对多条输入消息进行流式处理。
     *
     * @param msgs 输入消息列表
     * @param options 流式配置选项
     * @param structuredModel 可选的结构定义类
     * @return 执行期间发出的事件 Flux
     * @deprecated 自 2.0.0 起弃用，计划移除。请使用 {@code ReActAgent#streamEvents(...)} 获取细粒度的 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel) {
        return createEventStream(options, () -> call(msgs, structuredModel));
    }

    /**
     * 使用 JSON schema 对多条输入消息进行流式处理。
     *
     * @param msgs 输入消息列表
     * @param options 流式配置选项
     * @param schema 定义回复结构的 JSON schema
     * @return 执行期间发出的事件 Flux
     * @deprecated 自 2.0.0 起弃用，计划移除。请使用 {@code ReActAgent#streamEvents(...)} 获取细粒度的 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, JsonNode schema) {
        return createEventStream(options, () -> call(msgs, schema));
    }

    /**
     * 创建事件流并妥善管理钩子生命周期的辅助方法。
     *
     * <p>处理智能体执行期间流式事件的公共逻辑，包括：
     * <ul>
     * <li>创建并注册临时 StreamingHook</li>
     * <li>管理钩子生命周期（加入／移出钩子列表）</li>
     * <li>可选地将最终智能体结果作为事件发出</li>
     * <li>正确传播错误与完成信号</li>
     * </ul>
     *
     * @param options 流式配置选项
     * @param callSupplier 执行智能体调用的 Supplier（单条消息或消息列表）
     * @return 执行期间发出的事件 Flux
     */
    private Flux<Event> createEventStream(StreamOptions options, Supplier<Mono<Msg>> callSupplier) {
        return Flux.deferContextual(
                ctxView ->
                        Flux.<Event>create(
                                        sink -> {
                                            // 使用选项创建流式钩子
                                            StreamingHook streamingHook =
                                                    new StreamingHook(sink, options);

                                            // 添加临时钩子
                                            addHook(streamingHook);

                                            // 子智能体工具通过此总线推送子事件
                                            // 直接进入父级 sink，无需额外的 Flux 层。
                                            SubagentEventBus bus = sink::next;

                                            // 使用 Mono.defer 确保追踪上下文传播
                                            // 同时保留流式钩子功能
                                            Disposable callDisposable =
                                                    Mono.defer(() -> callSupplier.get())
                                                            .contextWrite(
                                                                    context ->
                                                                            context.put(
                                                                                            SubagentEventBus
                                                                                                    .CONTEXT_KEY,
                                                                                            bus)
                                                                                    .putAll(
                                                                                            ctxView))
                                                            .doFinally(
                                                                    signalType -> {
                                                                        // 移除临时钩子
                                                                        hooks.remove(streamingHook);
                                                                    })
                                                            .subscribe(
                                                                    finalMsg -> {
                                                                        if (options.shouldStream(
                                                                                EventType
                                                                                        .AGENT_RESULT)) {
                                                                            sink.next(
                                                                                    new Event(
                                                                                            EventType
                                                                                                    .AGENT_RESULT,
                                                                                            finalMsg,
                                                                                            true));
                                                                        }
                                                                    },
                                                                    sink::error,
                                                                    sink::complete);
                                            sink.onCancel(callDisposable);
                                        },
                                        FluxSink.OverflowStrategy.BUFFER)
                                .publishOn(Schedulers.boundedElastic()));
    }

    @Override
    public String toString() {
        return String.format("%s(id=%s, name=%s)", getClass().getSimpleName(), agentId, name);
    }
}
