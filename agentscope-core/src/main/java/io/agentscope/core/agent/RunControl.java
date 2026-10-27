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

import io.agentscope.core.interruption.InterruptControl;
import io.agentscope.core.interruption.InterruptSource;
import io.agentscope.core.message.Msg;
import java.util.concurrent.CancellationException;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * 单次执行的纯运行时控制对象；绝不存入会话状态。
 */
public final class RunControl {
    static final Object CONTEXT_KEY = new Object();
    private final String agentId;
    private final String runId;
    private final InterruptControl interruption = new InterruptControl();
    private final Sinks.Empty<Void> cancellation = Sinks.empty();
    private final Sinks.One<AgentRun.Status> termination = Sinks.one();
    private volatile AgentRun.Status status = AgentRun.Status.CREATED;

    public RunControl(String agentId) {
        this(agentId, null);
    }

    public RunControl(String agentId, String runId) {
        this.agentId = agentId;
        this.runId = runId == null || runId.isBlank() ? RuntimeContext.generateRunId() : runId;
    }

    /**
     * 与 AgentRun 及原生 SessionEvent 的 executionRunId 使用相同的标识符。
     */
    public String runId() {
        return runId;
    }

    /**
     * 用于运行时传播；未携带此控制对象的嵌套调用将分配独立的运行。
     */
    public static RunControl current(ContextView context, String agentId) {
        RunControl control = context.getOrDefault(CONTEXT_KEY, null);
        return control != null && control.belongsTo(agentId) ? control : null;
    }

    public Context attach(Context context) {
        return context.put(CONTEXT_KEY, this);
    }

    boolean belongsTo(String id) {
        return agentId.equals(id);
    }

    public AgentRun.Status status() {
        return status;
    }

    public InterruptControl interruption() {
        return interruption;
    }

    synchronized boolean queue() {
        if (status != AgentRun.Status.CREATED) {
            return false;
        }
        status = AgentRun.Status.QUEUED;
        return true;
    }

    synchronized boolean start() {
        if (status.isTerminal()) {
            return false;
        }
        status = AgentRun.Status.RUNNING;
        return true;
    }

    /**
     * 立即取消本次执行，包括订阅之前或排队期间。
     */
    public boolean cancel() {
        synchronized (this) {
            if (status.isTerminal()) {
                return false;
            }
            status = AgentRun.Status.CANCELLED;
        }
        cancellation.tryEmitEmpty();
        termination.tryEmitValue(AgentRun.Status.CANCELLED);
        return true;
    }

    /**
     * 正在运行的调用采用协作式中断；尚未获准进入的调用则被取消。
     */
    public boolean interrupt(InterruptSource source, Msg message) {
        synchronized (this) {
            if (status.isTerminal()) {
                return false;
            }
            if (status == AgentRun.Status.RUNNING) {
                interruption.trigger(source, message);
                return true;
            }
        }
        return cancel();
    }

    public void finish(AgentRun.Status outcome) {
        synchronized (this) {
            if (status.isTerminal()) {
                return;
            }
            status = outcome;
        }
        termination.tryEmitValue(outcome);
    }

    /**
     * 先取消上游，再报告取消，而不是将其视为成功的空完成。
     */
    <T> Flux<T> guard(Publisher<T> source) {
        return Flux.from(source)
                .takeUntilOther(cancellation.asMono())
                .concatWith(
                        Mono.defer(
                                () ->
                                        status == AgentRun.Status.CANCELLED
                                                ? Mono.error(
                                                        new CancellationException(
                                                                "Agent run cancelled"))
                                                : Mono.empty()));
    }

    public Mono<AgentRun.Status> termination() {
        return termination.asMono();
    }
}
