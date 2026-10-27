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

package io.agentscope.core.a2a.agent;

import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.a2a.agent.card.FixedAgentCardResolver;
import io.agentscope.core.a2a.agent.event.ClientEventContext;
import io.agentscope.core.a2a.agent.event.ClientEventHandlerRouter;
import io.agentscope.core.a2a.agent.utils.LoggerUtil;
import io.agentscope.core.a2a.agent.utils.MessageConvertUtil;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.MiddlewareBase.ExtensionPoint;
import io.agentscope.core.middleware.MiddlewareChain;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientBuilder;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * A2A（Agent2Agent）的 Agent 实现。
 *
 * <p>智能体描述应从 AgentCard 获取；获取失败时，使用
 * {@link Agent#getDescription()} 的默认值。
 *
 * <p>使用示例：
 * <pre>{@code
 *  // 简单用法。
 *  AgentCard agentCard = generateAgentCardByCode();
 *  A2aAgent a2aAgent = A2aAgent.builder().name("remote-agent-name").agentCard(agentCard).build();
 *
 *  // 自动获取 AgentCard
 *  AgentCardResolver agentCardResolver = new WellKnownAgentCardResolver("http://127.0.0.1:8080", "/.well-known/agent-card.json", Map.of());
 *  A2aAgent a2aAgent = A2aAgent.builder().name("remote-agent-name").agentCardResolver(agentCardResolver).build();
 * }</pre>
 */
public class A2aAgent extends AgentBase {

    private static final Logger log = LoggerFactory.getLogger(A2aAgent.class);

    private static final String INTERRUPT_HINT_PATTERN = "Task %s interrupt successfully.";

    /**
     * 单次调用的 {@link ReplyEvents} 在 Reactor Context 中的键。
     */
    private static final String EVENT_EMITTER_KEY = A2aAgent.class.getName() + ".replyEvents";

    private final AgentCardResolver agentCardResolver;

    private final A2aAgentConfig a2aAgentConfig;

    /**
     * 传给构建器、由所有会话共享的记忆，或 {@code null}。
     */
    private final Memory memory;

    /**
     * 各会话状态；未设置 {@link #memory} 时由它保存历史。
     */
    private final Map<Session, AgentState> sessions = new ConcurrentHashMap<>();

    private final ClientEventHandlerRouter clientEventHandlerRouter;

    /**
     * 用于测试，替换为单次调用构建的客户端；{@code null} 表示保留原客户端。
     */
    private final UnaryOperator<Client> clientReplacer;

    /**
     * onAgent 上生效的中间件，最外层在前。
     */
    private final List<MiddlewareBase> agentMiddlewares;

    /**
     * onReasoning 上生效的中间件，最外层在前。
     */
    private final List<MiddlewareBase> reasoningMiddlewares;

    /**
     * 每个会话正在进行的调用，可由 {@link #interrupt(RuntimeContext)} 取消。
     */
    private final Map<Session, A2aCall> runningCalls = new ConcurrentHashMap<>();

    private A2aAgent(
            String name,
            String description,
            Memory memory,
            List<Hook> hooks,
            AgentCardResolver agentCardResolver,
            A2aAgentConfig a2aAgentConfig,
            UnaryOperator<Client> clientReplacer,
            List<MiddlewareBase> middlewares) {
        super(name, description, hooks);
        this.a2aAgentConfig = a2aAgentConfig;
        this.agentCardResolver = agentCardResolver;
        this.memory = memory;
        this.clientReplacer = clientReplacer;
        List<MiddlewareBase> ordered = new ArrayList<>(middlewares);
        ordered.sort(Comparator.comparingInt(MiddlewareBase::order).reversed());
        this.agentMiddlewares = activeAt(ordered, ExtensionPoint.ON_AGENT);
        this.reasoningMiddlewares = activeAt(ordered, ExtensionPoint.ON_REASONING);
        LoggerUtil.debug(log, "A2aAgent init with config: {}", a2aAgentConfig);
        this.clientEventHandlerRouter = new ClientEventHandlerRouter();
    }

    /**
     * 调用远程智能体并流式返回此次调用的事件：{@link AgentStartEvent}、
     * 远程流式文本与思考对应的 {@link TextBlockDeltaEvent} 和
     * {@link ThinkingBlockDeltaEvent}、携带与 {@code call()} 返回相同消息的
     * {@link AgentResultEvent}，以及 {@link AgentEndEvent}。工具调用与工具结果
     * 仅包含在结果消息中。调用失败时，事件流以该错误结束。
     *
     * @param msgs 输入消息
     * @return 此次调用的事件
     */
    public Flux<AgentEvent> streamEvents(List<Msg> msgs) {
        return streamEvents(msgs, null);
    }

    /**
     * 调用远程智能体并流式返回事件，参见 {@link #streamEvents(List)}。
     *
     * @param msg 输入消息
     * @return 此次调用的事件
     */
    public Flux<AgentEvent> streamEvents(Msg msg) {
        return streamEvents(List.of(msg), null);
    }

    /**
     * 调用远程智能体并流式返回事件，参见 {@link #streamEvents(List)}。
     *
     * @param msgs 输入消息
     * @param context 调用上下文，或 {@code null}
     * @return 此次调用的事件
     */
    public Flux<AgentEvent> streamEvents(List<Msg> msgs, RuntimeContext context) {
        return buildAgentStream(msgs, context, this::doCall);
    }

    /**
     * {@code call()} 从同一事件流提取结果，使两种接口保持一致。
     */
    @Override
    protected Mono<Msg> callInternal(
            List<Msg> msgs, RuntimeContext context, Function<List<Msg>, Mono<Msg>> doCallFn) {
        return buildAgentStream(msgs, context, doCallFn)
                .ofType(AgentResultEvent.class)
                .map(AgentResultEvent::getResult)
                .takeLast(1)
                .next();
    }

    private static List<MiddlewareBase> activeAt(
            List<MiddlewareBase> middlewares, ExtensionPoint point) {
        return middlewares.stream()
                .filter(each -> null == each.activePoints() || each.activePoints().contains(point))
                .toList();
    }

    private Flux<AgentEvent> buildAgentStream(
            List<Msg> msgs, RuntimeContext context, Function<List<Msg>, Mono<Msg>> doCallFn) {
        return MiddlewareChain.build(
                        agentMiddlewares,
                        this,
                        null == context ? RuntimeContext.empty() : context,
                        MiddlewareBase::onAgent,
                        input -> runAgent(input.msgs(), context, doCallFn))
                .apply(new AgentInput(null == msgs ? List.of() : msgs));
    }

    private Flux<AgentEvent> runAgent(
            List<Msg> msgs, RuntimeContext context, Function<List<Msg>, Mono<Msg>> doCallFn) {
        String replyId = UUID.randomUUID().toString().replace("-", "");
        return Flux.create(
                sink -> {
                    sink.next(
                            new AgentStartEvent(
                                    null == context ? null : context.getSessionId(),
                                    replyId,
                                    getName()));
                    Mono<Msg> lifecycle = runLifecycle(msgs, doCallFn);
                    if (null != context) {
                        lifecycle =
                                lifecycle.contextWrite(c -> c.put(RUNTIME_CONTEXT_KEY, context));
                    }
                    Disposable disposable =
                            lifecycle
                                    .contextWrite(
                                            c ->
                                                    c.put(
                                                            EVENT_EMITTER_KEY,
                                                            new ReplyEvents(replyId, sink::next)))
                                    .doFinally(
                                            signal -> {
                                                sink.next(new AgentEndEvent(replyId));
                                                sink.complete();
                                            })
                                    .contextWrite(sink.contextView())
                                    .subscribe(
                                            result -> sink.next(new AgentResultEvent(result)),
                                            sink::error);
                    sink.onCancel(disposable);
                },
                FluxSink.OverflowStrategy.BUFFER);
    }

    /**
     * 在 {@code context} 对应的会话中调用远程智能体。会话由上下文中的用户和会话 ID
     * 确定，各自保存历史，同一会话的调用逐个执行。没有会话 ID 时，
     * 使用以智能体名称命名的会话。
     *
     * @param msgs 输入消息
     * @param context 调用上下文，或 {@code null}
     * @return 远程智能体的回复
     */
    public Mono<Msg> call(List<Msg> msgs, RuntimeContext context) {
        return callInternal(msgs, context, this::doCall);
    }

    /**
     * 返回 {@code context} 对应的会话状态；未向构建器提供记忆时，由该状态保存历史。
     *
     * @param context 会话上下文；{@code null} 表示默认会话
     * @return 会话状态
     */
    public AgentState getAgentState(RuntimeContext context) {
        return stateOf(sessionOf(context));
    }

    /**
     * 返回默认会话的状态。
     *
     * @deprecated 请使用 {@link #getAgentState(RuntimeContext)}。
     */
    @Deprecated
    @Override
    public AgentState getAgentState() {
        return getAgentState(null);
    }

    /**
     * 取消 {@code context} 对应会话中正在进行调用的远程任务（若有）。
     *
     * @param context 会话上下文；{@code null} 表示默认会话
     */
    public void interrupt(RuntimeContext context) {
        cancelRemoteTask(sessionOf(context)).block();
    }

    /**
     * 同一会话的调用共享历史，因此逐个执行。
     */
    @Override
    protected Object callSerializationKey(RuntimeContext rc) {
        return sessionOf(rc);
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        return Mono.deferContextual(
                view -> {
                    Session session = sessionOf(view.getOrDefault(RUNTIME_CONTEXT_KEY, null));
                    if (msgs != null && !msgs.isEmpty()) {
                        addToHistory(session, msgs);
                    }
                    // 【学习提示】历史归属于用户／会话键，客户端则归属于单次调用；
                    // using 将客户端释放绑定到订阅终止，不能把会话历史生命周期与网络连接混为一谈。
                    // 无论调用如何结束都关闭客户端，取消时也一样。
                    return Mono.using(
                                    () -> startCall(session),
                                    call -> execute(call, session, view),
                                    this::endCall)
                            .doOnNext(reply -> addToHistory(session, List.of(reply)));
                });
    }

    /**
     * {@code context} 中的用户与会话 ID；默认使用以智能体名称命名的会话。
     */
    private Session sessionOf(RuntimeContext context) {
        String sessionId = null == context ? null : context.getSessionId();
        return new Session(
                null == context ? null : context.getUserId(),
                null == sessionId || sessionId.isBlank() ? getName() : sessionId);
    }

    private AgentState stateOf(Session session) {
        return sessions.computeIfAbsent(
                session,
                each ->
                        AgentState.builder()
                                .userId(each.userId())
                                .sessionId(each.sessionId())
                                .build());
    }

    private List<Msg> historyOf(Session session) {
        return null != memory ? memory.getMessages() : stateOf(session).getContext();
    }

    private void addToHistory(Session session, List<Msg> msgs) {
        if (null != memory) {
            msgs.forEach(memory::addMessage);
        } else {
            stateOf(session).contextMutable().addAll(msgs);
        }
    }

    private A2aCall startCall(Session session) {
        String requestId = UUID.randomUUID().toString();
        AgentCard agentCard = agentCardResolver.getAgentCard(getName());
        Client client = buildA2aClient(agentCard);
        LoggerUtil.debug(
                log, "[{}] A2aAgent build A2a Client with Agent Card: {}.", requestId, agentCard);
        A2aCall call =
                new A2aCall(requestId, session, client, new ClientEventContext(requestId, this));
        runningCalls.put(session, call);
        return call;
    }

    private Mono<Msg> execute(A2aCall call, Session session, ContextView view) {
        List<Msg> history = historyOf(session);
        LoggerUtil.info(log, "[{}] A2aAgent start call.", call.requestId());
        LoggerUtil.debug(log, "[{}] A2aAgent call with input messages: ", call.requestId());
        LoggerUtil.logTextMsgDetail(log, history);
        call.eventContext().setHooks(getSortedHooks());
        call.eventContext().setInputMessages(history);
        ReplyEvents events = view.getOrDefault(EVENT_EMITTER_KEY, null);
        RuntimeContext context = view.getOrDefault(RUNTIME_CONTEXT_KEY, RuntimeContext.empty());
        AtomicReference<Msg> reply = new AtomicReference<>();
        Function<ReasoningInput, Flux<AgentEvent>> exchange =
                input -> exchange(call, input.messages(), events, reply);
        return Mono.defer(
                        () ->
                                checkInterruptedAsync()
                                        .thenMany(
                                                MiddlewareChain.build(
                                                                reasoningMiddlewares,
                                                                this,
                                                                context,
                                                                MiddlewareBase::onReasoning,
                                                                exchange)
                                                        .apply(
                                                                new ReasoningInput(
                                                                        history,
                                                                        List.of(),
                                                                        GenerateOptions.builder()
                                                                                .build())))
                                        .doOnNext(
                                                event -> {
                                                    if (null != events) {
                                                        events.emitter().emit(event);
                                                    }
                                                })
                                        .then(Mono.fromSupplier(reply::get)))
                .doOnError(
                        e ->
                                LoggerUtil.error(
                                        log, "[{}] A2aAgent execute error.", call.requestId(), e));
    }

    /**
     * 向远程智能体发送 {@code msgs}，流式转发其回复文本与思考，
     * 并将回复保存在 {@code reply} 中。
     */
    private Flux<AgentEvent> exchange(
            A2aCall call, List<Msg> msgs, ReplyEvents events, AtomicReference<Msg> reply) {
        return Flux.create(
                sink -> {
                    call.eventContext()
                            .setEventEmitter(
                                    null == events ? call.requestId() : events.replyId(),
                                    sink::next);
                    Disposable disposable =
                            doExecute(call, MessageConvertUtil.convertFromMsg(msgs))
                                    .subscribe(
                                            msg -> {
                                                reply.set(msg);
                                                sink.complete();
                                            },
                                            sink::error);
                    sink.onCancel(disposable);
                },
                FluxSink.OverflowStrategy.BUFFER);
    }

    private void endCall(A2aCall call) {
        runningCalls.remove(call.session(), call);
        call.client().close();
        LoggerUtil.debug(log, "[{}] A2aAgent close A2a Client.", call.requestId());
    }

    /**
     * 取消默认会话中正在进行调用的远程任务（若有）。
     */
    @Override
    public void interrupt() {
        super.interrupt();
        interrupt((RuntimeContext) null);
    }

    /**
     * 取消默认会话中正在进行调用的远程任务（若有）。
     */
    @Override
    public void interrupt(Msg msg) {
        super.interrupt(msg);
        interrupt((RuntimeContext) null);
    }

    @Override
    protected Mono<Void> doObserve(Msg msg) {
        return Mono.deferContextual(
                view -> {
                    addToHistory(
                            sessionOf(view.getOrDefault(RUNTIME_CONTEXT_KEY, null)), List.of(msg));
                    return Mono.empty();
                });
    }

    @Override
    protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
        return Mono.deferContextual(
                view -> cancelRemoteTask(sessionOf(view.getOrDefault(RUNTIME_CONTEXT_KEY, null))));
    }

    /**
     * 取消 {@code session} 中正在进行调用的远程任务（如果它已拥有任务）。
     */
    private Mono<Msg> cancelRemoteTask(Session session) {
        A2aCall call = runningCalls.get(session);
        // 【学习提示】取消远程任务需要已收到的任务 ID；尚未观察到 Task 时此路径为空操作。
        // 关闭本地客户端与发出远程 cancelTask 是不同动作，不应将连接关闭推断为远程任务已取消。
        Task task = null == call ? null : call.eventContext().getTask();
        if (null == task) {
            return Mono.empty();
        }
        LoggerUtil.debug(log, "[{}] A2aAgent handle interrupt.", call.requestId());
        try {
            String taskId = task.id();
            call.client().cancelTask(new CancelTaskParams(taskId), null);
            return Mono.just(
                    Msg.builder()
                            .content(
                                    TextBlock.builder()
                                            .text(String.format(INTERRUPT_HINT_PATTERN, taskId))
                                            .build())
                            .build());
        } catch (A2AClientException e) {
            return Mono.just(
                    Msg.builder()
                            .content(TextBlock.builder().text(e.getMessage()).build())
                            .build());
        }
    }

    /**
     * 为 {@link A2aAgent} 创建新的 {@link Builder} 实例。
     *
     * @return 新构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 返回传给构建器的记忆，否则返回默认会话的历史。
     *
     * @return 记忆
     * @deprecated 请使用 {@link #getAgentState(RuntimeContext)}。
     */
    @Deprecated
    public Memory getMemory() {
        return null != memory ? memory : new StateMemory(getAgentState(null));
    }

    private Client buildA2aClient(AgentCard agentCard) {
        ClientBuilder builder = Client.builder(agentCard);
        if (this.a2aAgentConfig.clientTransports().isEmpty()) {
            // 默认添加基本 JSON-RPC 传输
            builder.withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig());
        } else {
            this.a2aAgentConfig.clientTransports().forEach(builder::withTransport);
        }
        builder.clientConfig(this.a2aAgentConfig.clientConfig());
        Client client = builder.build();
        return null == clientReplacer ? client : clientReplacer.apply(client);
    }

    private Mono<Msg> doExecute(A2aCall call, Message message) {
        return Mono.create(
                sink -> {
                    String requestId = call.requestId();
                    ClientEventContext eventContext = call.eventContext();
                    Client client = call.client();
                    eventContext.setSink(sink);
                    BiConsumer<ClientEvent, AgentCard> a2aEventConsumer =
                            (event, agentCard) -> {
                                LoggerUtil.trace(
                                        log,
                                        "[{}] A2aAgent receive event {}: ",
                                        requestId,
                                        event.getClass().getSimpleName());
                                LoggerUtil.logA2aClientEventDetail(log, event);
                                clientEventHandlerRouter.handle(event, eventContext);
                            };
                    client.sendMessage(
                            message,
                            List.of(a2aEventConsumer),
                            err -> {
                                if (err == null) {
                                    if (eventContext.isTerminalDelivered()) {
                                        return;
                                    }
                                    LoggerUtil.warn(
                                            log,
                                            "[{}] A2aAgent stream completed before final response.",
                                            requestId);
                                    eventContext.completeExceptionally(
                                            new IllegalStateException(
                                                    "A2A stream completed before final response."));
                                    return;
                                }
                                if (err instanceof CancellationException) {
                                    LoggerUtil.warn(
                                            log, "[{}] A2aAgent sendMessage cancelled.", requestId);
                                    eventContext.completeExceptionally(err);
                                    return;
                                }
                                eventContext.completeExceptionally(err);
                            });
                });
    }

    /**
     * 回复事件的接收位置。
     */
    private record ReplyEvents(String replyId, AgentEventEmitter emitter) {}

    /**
     * 单次调用的客户端与事件上下文。
     */
    private record A2aCall(
            String requestId, Session session, Client client, ClientEventContext eventContext) {}

    /**
     * 智能体的一个会话。
     */
    private record Session(String userId, String sessionId) {}

    /**
     * 以 {@link Memory} 视图表示的会话历史。
     */
    private record StateMemory(AgentState state) implements Memory {

        @Override
        public void saveTo(AgentStateStore stateStore, String userId, String sessionId) {
            throw new UnsupportedOperationException("Save the AgentState of the session instead");
        }

        @Override
        public void loadFrom(AgentStateStore stateStore, String userId, String sessionId) {
            throw new UnsupportedOperationException("Load the AgentState of the session instead");
        }

        @Override
        public void addMessage(Msg message) {
            state.contextMutable().add(message);
        }

        @Override
        public List<Msg> getMessages() {
            return state.getContext();
        }

        @Override
        public void deleteMessage(int index) {
            state.contextMutable().remove(index);
        }

        @Override
        public void clear() {
            state.contextMutable().clear();
        }
    }

    public static class Builder {

        private String name;

        private AgentCardResolver agentCardResolver;

        private A2aAgentConfig a2aAgentConfig;

        private Memory memory;

        private final List<Hook> hooks = new ArrayList<>();

        private final List<MiddlewareBase> middlewares = new ArrayList<>();

        private UnaryOperator<Client> clientReplacer;

        /**
         * 设置 A2aAgent 名称。
         *
         * @param name 待设置的名称
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 设置 A2aAgent 的 {@link AgentCard}。
         *
         * <p>它会自动包装为 {@link FixedAgentCardResolver}。
         *
         * @param agentCard 待设置的 AgentCard
         * @return 当前 Builder 实例，支持链式调用
         * @see #agentCardResolver(AgentCardResolver)
         */
        public Builder agentCard(AgentCard agentCard) {
            return agentCardResolver(FixedAgentCardResolver.builder().agentCard(agentCard).build());
        }

        /**
         * 设置 A2aAgent 的 {@link AgentCardResolver}。
         *
         * <p>在同一构建器中同时调用 {@link #agentCard(AgentCard)} 与此方法时，
         * 后调用的方法覆盖前者。
         *
         * @param agentCardResolver 待设置的 AgentCardResolver，忽略 null 值
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder agentCardResolver(AgentCardResolver agentCardResolver) {
            if (null == agentCardResolver) {
                return this;
            }
            if (null != this.agentCardResolver) {
                LoggerUtil.warn(
                        log,
                        "agentCardResolver {} will be replaced by {}",
                        this.agentCardResolver.getClass().getSimpleName(),
                        agentCardResolver.getClass().getSimpleName());
            }
            this.agentCardResolver = agentCardResolver;
            return this;
        }

        /**
         * 设置 A2aAgent 的 {@link A2aAgentConfig}。
         *
         * @param a2aAgentConfig 待设置的 A2aAgentConfig
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder a2aAgentConfig(A2aAgentConfig a2aAgentConfig) {
            this.a2aAgentConfig = a2aAgentConfig;
            return this;
        }

        /**
         * 设置 A2aAgent 所有会话共享的 {@link Memory}。未设置时，
         * 每个会话将历史保存在自己的 {@link AgentState} 中。
         *
         * @param memory 待设置的 Memory
         * @return 当前 Builder 实例，支持链式调用
         * @deprecated 请使用各会话的 {@link AgentState}，参见
         *     {@link A2aAgent#getAgentState(RuntimeContext)}。
         */
        @Deprecated
        public Builder memory(Memory memory) {
            this.memory = memory;
            return this;
        }

        /**
         * 无效果，参见 {@link AgentBase#AgentBase(String, String, boolean, List)}。
         *
         * @param checkRunning 忽略此参数
         * @return 当前 Builder 实例，支持链式调用
         * @deprecated 自 2.0.0 起弃用，无效果。
         */
        @Deprecated(since = "2.0.0")
        public Builder checkRunning(boolean checkRunning) {
            return this;
        }

        /**
         * 向 A2aAgent 添加 {@link Hook}。
         *
         * @param hook 待添加的 Hook
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder hook(Hook hook) {
            this.hooks.add(hook);
            return this;
        }

        /**
         * 向 A2aAgent 添加多个 {@link Hook}。
         *
         * @param hooks 待添加的 Hook 列表
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder hooks(List<Hook> hooks) {
            this.hooks.addAll(hooks);
            return this;
        }

        /**
         * 向 A2aAgent 添加 {@link MiddlewareBase}。{@code onAgent} 包装整个调用，
         * {@code onReasoning} 包装与远程智能体的交互：其输入是实际发送的消息，
         * 事件是远程流式返回的文本与思考。其他扩展点不适用于远程智能体。
         *
         * @param middleware 待添加的中间件
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder middleware(MiddlewareBase middleware) {
            this.middlewares.add(middleware);
            return this;
        }

        /**
         * 向 A2aAgent 添加多个 {@link MiddlewareBase}，参见 {@link #middleware(MiddlewareBase)}。
         *
         * @param middlewares 待添加的中间件
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder middlewares(List<? extends MiddlewareBase> middlewares) {
            this.middlewares.addAll(middlewares);
            return this;
        }

        /**
         * 用于测试，替换为单次调用构建的客户端。
         */
        Builder clientReplacer(UnaryOperator<Client> clientReplacer) {
            this.clientReplacer = clientReplacer;
            return this;
        }

        /**
         * 构建 A2aAgent 实例。
         *
         * @return 构建的 A2aAgent 实例
         * @throws IllegalArgumentException 未设置 agentCardResolver 时抛出
         */
        public A2aAgent build() {
            if (null == this.agentCardResolver) {
                throw new IllegalArgumentException("agentCardResolver is required");
            }
            if (null == this.a2aAgentConfig) {
                this.a2aAgentConfig = A2aAgentConfig.builder().build();
            }
            return new A2aAgent(
                    this.name,
                    getDescriptionFromAgentCard(),
                    this.memory,
                    this.hooks,
                    this.agentCardResolver,
                    this.a2aAgentConfig,
                    this.clientReplacer,
                    this.middlewares);
        }

        private String getDescriptionFromAgentCard() {
            try {
                AgentCard agentCard = this.agentCardResolver.getAgentCard(this.name);
                return null != agentCard ? agentCard.description() : null;
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
