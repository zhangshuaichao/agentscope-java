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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.coordination.LocalPeriodicGate;
import io.agentscope.harness.agent.coordination.PeriodicGate;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.ContextView;

/**
 * 在每次智能体调用结束时触发记忆提取的中间件。
 *
 * <p>在智能体流的完成信号（{@link #onAgent}）上运行，因此即使本次未触发对话压缩，
 * 也会在调用后提取并持久化长期记忆。写入采用“发起后不等待”方式：智能体流立即完成，
 * 提取任务在后台调度器执行。已完成订阅的 Reactor 上下文会重放到后台订阅，
 * 让模型调用的上下文级计量（词元计费、追踪）看到与本轮相同的上下文。
 * 启用 {@link CompactionMiddleware} 时，由它提取被摘要消息的记忆；
 * 此中间件覆盖剩余的原文尾部消息。
 *
 * <p>写入受 {@link MemoryConfig.FlushTrigger} 门控：
 * <ul>
 *   <li>{@link MemoryConfig.FlushMode#ALWAYS}（默认）—— 每次调用后写入。</li>
 *   <li>{@link MemoryConfig.FlushMode#NEVER} —— 不经此中间件写入。
 *       CompactionMiddleware 与溢出恢复路径触发时仍会自行执行记忆提取。</li>
 *   <li>{@link MemoryConfig.FlushMode#THROTTLED} —— 每个
 *       {@link MemoryConfig.FlushTrigger#minGap()} 间隔最多写入一次。</li>
 * </ul>
 *
 * <p>原生会话日志独立于记忆提取运行，因此关闭记忆写入不影响持久化执行历史。
 *
 * <p>同一隔离键的并发写入串行化：每个键同时最多运行一次写入；
 * 同一对话待处理的写入合并成一个排队任务，因为任务直到执行时才读取对话状态，
 * 最新任务已经包含它替代的旧任务需要读取的内容。
 * 同一键下不同对话的写入仍分别排队。
 *
 * <p>此类线程安全：实例只保存配置，每个键的队列位于静态映射中并在内部同步。
 *
 * <p>节流窗口按与记忆数据隔离方式一致的<em>隔离键</em>跟踪：
 * <ul>
 *   <li>{@link IsolationScope#USER}（默认）—— 每个 {@code userId} 一个窗口。</li>
 *   <li>{@link IsolationScope#SESSION} —— 每个 {@code sessionId} 一个窗口。</li>
 *   <li>{@link IsolationScope#AGENT} / {@link IsolationScope#GLOBAL} —— 整个智能体实例
 *       共用一个窗口，避免共享记忆文件上的并发写入竞争。</li>
 * </ul>
 *
 * <p>【学习提示】记忆提取是后台派生工作，不是回复完成的同义词。
 * 任务排队、合并与节流必须以正确的数据隔离维度为键，否则可能丢更新或跨会话干扰。
 */
public class MemoryFlushMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(MemoryFlushMiddleware.class);

    private final WorkspaceManager workspaceManager;
    private final Model model;
    private final String flushPrompt;
    private final MemoryConfig.FlushTrigger flushTrigger;
    private final IsolationScope isolationScope;
    private final PeriodicGate periodicGate;

    public MemoryFlushMiddleware(WorkspaceManager workspaceManager, Model model) {
        this(
                workspaceManager,
                model,
                MemoryFlushManager.DEFAULT_FLUSH_PROMPT,
                MemoryConfig.FlushTrigger.always(),
                IsolationScope.USER,
                new LocalPeriodicGate());
    }

    public MemoryFlushMiddleware(
            WorkspaceManager workspaceManager,
            Model model,
            String flushPrompt,
            MemoryConfig.FlushTrigger flushTrigger) {
        this(
                workspaceManager,
                model,
                flushPrompt,
                flushTrigger,
                IsolationScope.USER,
                new LocalPeriodicGate());
    }

    public MemoryFlushMiddleware(
            WorkspaceManager workspaceManager,
            Model model,
            String flushPrompt,
            MemoryConfig.FlushTrigger flushTrigger,
            IsolationScope isolationScope) {
        this(
                workspaceManager,
                model,
                flushPrompt,
                flushTrigger,
                isolationScope,
                new LocalPeriodicGate());
    }

    public MemoryFlushMiddleware(
            WorkspaceManager workspaceManager,
            Model model,
            String flushPrompt,
            MemoryConfig.FlushTrigger flushTrigger,
            IsolationScope isolationScope,
            PeriodicGate periodicGate) {
        this.workspaceManager = workspaceManager;
        this.model = model;
        this.flushPrompt =
                flushPrompt != null ? flushPrompt : MemoryFlushManager.DEFAULT_FLUSH_PROMPT;
        this.flushTrigger =
                flushTrigger != null ? flushTrigger : MemoryConfig.FlushTrigger.always();
        this.isolationScope = isolationScope != null ? isolationScope : IsolationScope.USER;
        this.periodicGate = periodicGate != null ? periodicGate : new LocalPeriodicGate();
    }

    /** 仅声明所需扩展点；子类若重写更多钩子，必须扩展此集合。 */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_AGENT);
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        final RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        // 捕获正在完成的订阅上下文，让不等待结果的后台记忆写入能够重放它；
        // 新的后台订阅不会自动继承此上下文，否则模型调用上的
        // 上下文级计量（词元用量、追踪）将看不到任何信息。
        return next.apply(input)
                .doOnEach(
                        signal -> {
                            if (signal.isOnComplete()) {
                                scheduleFlush(agent, rc, signal.getContextView());
                            }
                        });
    }

    private void scheduleFlush(Agent agent, RuntimeContext rc, ContextView contextView) {
        // 在派发时而非任务内部占用在途槽位：已排队的写入必须
        // 立刻计入，否则静止状态检查可能在仍有待执行任务时
        // 观察到空的在途集合。
        MemoryBackgroundTasks.begin();
        String key = compositeTimerKey(rc);
        // 任务的合并键标识它执行时读取的对话。智能体引用用于区分
        // 服务相同用户与会话 ID 的不同智能体实例，
        // 这些实例的写入不能相互替代。
        ConversationKey conversationKey =
                new ConversationKey(
                        agent, blankToEmpty(rc.getUserId()), blankToEmpty(rc.getSessionId()));
        Runnable task = () -> runFlush(key, agent, rc, contextView);
        Runnable[] starter = new Runnable[1];
        Runnable[] displaced = new Runnable[1];
        FLUSH_QUEUES.compute(
                key,
                (k, queue) -> {
                    FlushQueue q = queue == null ? new FlushQueue() : queue;
                    if (q.running) {
                        // 排队任务只在执行时读取对话状态，而且同一对话的调用
                        // 会按智能体的 (userId, sessionId) 键串行化，
                        // 因此最新任务的状态已经包含
                        // 所有被替代任务原本会读取的内容。
                        displaced[0] = q.pending.put(conversationKey, task);
                    } else {
                        q.running = true;
                        starter[0] = task;
                    }
                    return q;
                });
        if (displaced[0] != null) {
            // 被替代任务永远不会执行，需释放它在派发时占用的在途槽位。
            // 在 compute 之外完成释放，避免在映射的桶锁内执行。
            MemoryBackgroundTasks.end();
        }
        if (starter[0] != null) {
            starter[0].run();
        }
    }

    private void runFlush(String key, Agent agent, RuntimeContext rc, ContextView contextView) {
        Mono.defer(() -> doFlush(agent, rc))
                .subscribeOn(Schedulers.boundedElastic())
                .contextWrite(context -> context.putAll(contextView))
                .doFinally(
                        signal -> {
                            MemoryBackgroundTasks.end();
                            drainFlushQueue(key);
                        })
                .subscribe(null, e -> log.warn("Memory flush failed: {}", e.getMessage()));
    }

    private void drainFlushQueue(String key) {
        Runnable[] next = new Runnable[1];
        FLUSH_QUEUES.compute(
                key,
                (k, queue) -> {
                    if (queue == null) {
                        return null;
                    }
                    Iterator<Runnable> it = queue.pending.values().iterator();
                    if (it.hasNext()) {
                        next[0] = it.next();
                        it.remove();
                    }
                    if (next[0] == null) {
                        return null; // 空闲：移除条目，使映射只持有活动键
                    }
                    return queue; // 出队的任务继续作为正在运行的写入执行
                });
        if (next[0] != null) {
            next[0].run();
        }
    }

    /**
     * 每个隔离键下的待处理写入任务，保证每个记忆命名空间最多一个写入在途。
     * 字段只在 {@link ConcurrentHashMap#compute} 内修改（持有按键的桶锁），
     * 因此无需额外同步；键一旦空闲便移除条目。
     */
    private static final class FlushQueue {
        final LinkedHashMap<ConversationKey, Runnable> pending = new LinkedHashMap<>();
        boolean running;
    }

    private static final ConcurrentHashMap<String, FlushQueue> FLUSH_QUEUES =
            new ConcurrentHashMap<>();

    private Mono<Void> doFlush(Agent agent, RuntimeContext rc) {
        AgentState state = RuntimeContext.resolveAgentState(rc, agent);
        if (state == null) {
            return Mono.empty();
        }
        // 在执行而非调度时读取状态：排队等待的任务可以看到期间
        // 追加的全部内容，因此合并排队任务不会丢失
        // 消息。
        List<Msg> messages = new ArrayList<>(state.getContext());
        if (messages.isEmpty()) {
            return Mono.empty();
        }
        if (!shouldFlushNow(rc)) {
            log.debug("Memory flush skipped (trigger={})", flushTrigger);
            return Mono.empty();
        }

        MemoryFlushManager flushManager =
                new MemoryFlushManager(workspaceManager, model, flushPrompt);
        return flushManager
                .flushMemories(rc, messages)
                .doOnSuccess(v -> log.debug("Memory flush completed"))
                .onErrorResume(
                        e -> {
                            log.warn("Memory flush failed: {}", e.getMessage());
                            return Mono.empty();
                        });
    }

    /**
     * 应用配置的触发策略，判断本次调用是否应触发写入。
     * 对于 {@link MemoryConfig.FlushMode#THROTTLED}，使用 {@link AtomicReference#compareAndSet}
     * 竞争，确保 {@code minGap} 内最多一个调用方占用槽位。
     *
     * <p>节流窗口的键按记忆数据命名空间对应的隔离维度生成
     * （见 {@link #timerKeyFor(RuntimeContext)}）。
     *
     * <p>包级可见，便于无需构建完整 {@code Agent} 就测试触发门控。
     */
    boolean shouldFlushNow(RuntimeContext rc) {
        switch (flushTrigger.mode()) {
            case ALWAYS:
                return true;
            case NEVER:
                return false;
            case THROTTLED:
                return periodicGate.tryClaim(compositeTimerKey(rc), flushTrigger.minGap());
            default:
                return true;
        }
    }

    /**
     * 将 {@link IsolationScope} 名称与 {@link #timerKeyFor(RuntimeContext)}
     * 返回的调用身份组合为键。操作前缀使写入与维护即便使用同一
     * {@link PeriodicGate} 也彼此独立；范围前缀防止不同隔离维度共享槽位。
     */
    private String compositeTimerKey(RuntimeContext rc) {
        return "memory-flush:" + isolationScope.name() + ":" + timerKeyFor(rc);
    }

    /** 排队写入在执行时读取的对话：智能体实例、用户、会话。 */
    private record ConversationKey(Agent agent, String userId, String sessionId) {}

    /**
     * 将 {@code null} 和空白标识符规范化为 {@code ""}，使缺失 ID 共享一个槽位。
     * 包级可见，与 {@link MemoryMaintenanceMiddleware#timerKeyFor} 共用。
     */
    static String blankToEmpty(String s) {
        return (s != null && !s.isBlank()) ? s : "";
    }

    /**
     * 根据配置的 {@link IsolationScope} 和 {@link RuntimeContext}
     * 推导组合计时键中的调用身份部分，与记忆数据命名空间保持一致：
     * <ul>
     *   <li>{@link IsolationScope#USER} — {@code userId}（匿名时为空字符串）</li>
     *   <li>{@link IsolationScope#SESSION} — {@code sessionId}（缺失时为空字符串）</li>
     *   <li>{@link IsolationScope#AGENT} / {@link IsolationScope#GLOBAL} — 常量 {@code ""}，
     *       使所有调用方共用节流槽位，串行化共享记忆文件上的写入</li>
     * </ul>
     */
    String timerKeyFor(RuntimeContext rc) {
        return switch (isolationScope) {
            case USER -> blankToEmpty(rc != null ? rc.getUserId() : null);
            case SESSION -> blankToEmpty(rc != null ? rc.getSessionId() : null);
            case AGENT, GLOBAL -> "";
        };
    }
}
