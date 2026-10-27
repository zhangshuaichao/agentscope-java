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
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.coordination.LocalPeriodicGate;
import io.agentscope.harness.agent.coordination.PeriodicGate;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Duration;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.ContextView;

/**
 * 在智能体调用后执行周期性记忆维护的中间件。
 *
 * <p>在智能体流的完成信号上触发（在 {@link MemoryFlushMiddleware} 之后），
 * 通过可配置的最小间隔节流，不会每次调用都执行。
 * 维护采用“发起后不等待”方式：智能体流立即完成，维护任务在后台调度器运行。
 * 已完成订阅的 Reactor 上下文会重放到后台订阅，让整合模型调用的上下文级计量
 * （词元计费、追踪）看到与本轮相同的上下文。
 *
 * <p>维护步骤按顺序执行：
 * <ol>
 *   <li>将超过 {@code dailyFileRetentionDays} 的每日记忆文件移入
 *       {@code memory/archive/}。</li>
 *   <li>如果配置了整合器，则执行基于 LLM 的整合
 *       （{@link MemoryConsolidator#consolidate}）。</li>
 * </ol>
 *
 * <p>节流窗口按与记忆数据隔离方式一致的<em>隔离键</em>跟踪：
 * <ul>
 *   <li>{@link IsolationScope#USER}（默认）—— 每个 {@code userId} 一个窗口。</li>
 *   <li>{@link IsolationScope#SESSION} —— 每个 {@code sessionId} 一个窗口。</li>
 *   <li>{@link IsolationScope#AGENT} / {@link IsolationScope#GLOBAL} —— 整个智能体实例
 *       共用一个窗口，避免共享记忆文件上的并发维护竞争。</li>
 * </ul>
 */
public class MemoryMaintenanceMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(MemoryMaintenanceMiddleware.class);

    /** 两次维护运行之间的默认最小间隔。 */
    public static final Duration DEFAULT_MIN_GAP = Duration.ofMinutes(30);

    private final WorkspaceManager workspaceManager;
    private final MemoryConsolidator consolidator;
    private final int dailyFileRetentionDays;
    private final Duration minGap;
    private final IsolationScope isolationScope;
    private final PeriodicGate periodicGate;

    public MemoryMaintenanceMiddleware(
            WorkspaceManager workspaceManager,
            MemoryConsolidator consolidator,
            int dailyFileRetentionDays,
            Duration minGap) {
        this(
                workspaceManager,
                consolidator,
                dailyFileRetentionDays,
                minGap,
                IsolationScope.USER,
                new LocalPeriodicGate());
    }

    public MemoryMaintenanceMiddleware(
            WorkspaceManager workspaceManager,
            MemoryConsolidator consolidator,
            int dailyFileRetentionDays,
            Duration minGap,
            IsolationScope isolationScope) {
        this(
                workspaceManager,
                consolidator,
                dailyFileRetentionDays,
                minGap,
                isolationScope,
                new LocalPeriodicGate());
    }

    public MemoryMaintenanceMiddleware(
            WorkspaceManager workspaceManager,
            MemoryConsolidator consolidator,
            int dailyFileRetentionDays,
            Duration minGap,
            IsolationScope isolationScope,
            PeriodicGate periodicGate) {
        this.workspaceManager = workspaceManager;
        this.consolidator = consolidator;
        this.dailyFileRetentionDays = dailyFileRetentionDays;
        this.minGap = minGap != null ? minGap : DEFAULT_MIN_GAP;
        this.isolationScope = isolationScope != null ? isolationScope : IsolationScope.USER;
        this.periodicGate = periodicGate != null ? periodicGate : new LocalPeriodicGate();
    }

    public MemoryMaintenanceMiddleware(
            WorkspaceManager workspaceManager, MemoryConsolidator consolidator) {
        this(workspaceManager, consolidator, 90, DEFAULT_MIN_GAP);
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
        // 维护主体，包括存储后端门控所需的远程 I/O 占位操作，
        // 均在后台调度器执行。只有在途计数器同步更新，
        // 确保静止状态检查不可能在任务尚未计入时
        // 观察到空的在途集合。
        return next.apply(input)
                .doOnEach(
                        signal -> {
                            if (signal.isOnComplete()) {
                                ContextView contextView = signal.getContextView();
                                scheduleMaintenance(rc, contextView);
                            }
                        });
    }

    private void scheduleMaintenance(RuntimeContext rc, ContextView contextView) {
        MemoryBackgroundTasks.begin();
        Mono.defer(() -> doMaintenance(rc))
                .subscribeOn(Schedulers.boundedElastic())
                .contextWrite(context -> context.putAll(contextView))
                .doFinally(signal -> MemoryBackgroundTasks.end())
                .subscribe(null, e -> log.warn("Memory maintenance failed: {}", e.getMessage()));
    }

    private Mono<Void> doMaintenance(RuntimeContext rc) {
        if (!periodicGate.tryClaim(compositeTimerKey(rc), minGap)) {
            // 被节流跳过时，派发时占用的在途槽位会在此
            // Mono 完成后释放。
            return Mono.empty();
        }
        // 作为一条响应式链运行，而非阻塞主体：整合器的模型调用
        // 必须留在此订阅内，才能看到重放的本轮上下文。
        return Mono.fromRunnable(() -> expireDailyFiles(rc))
                .then(Mono.defer(() -> consolidateMemory(rc)));
    }

    /**
     * 将 {@link IsolationScope} 名称与 {@link #timerKeyFor(RuntimeContext)}
     * 返回的调用身份组合为键。操作前缀使维护与写入即便使用同一
     * {@link PeriodicGate} 也彼此独立；范围前缀防止不同隔离维度共享槽位。
     */
    private String compositeTimerKey(RuntimeContext rc) {
        return "memory-maintenance:" + isolationScope.name() + ":" + timerKeyFor(rc);
    }

    /**
     * 根据配置的 {@link IsolationScope} 和 {@link RuntimeContext} 推导组合计时键中的调用身份，
     * 与记忆数据命名空间保持一致。相同逻辑见 {@link MemoryFlushMiddleware#timerKeyFor(RuntimeContext)}。
     */
    String timerKeyFor(RuntimeContext rc) {
        return switch (isolationScope) {
            case USER -> MemoryFlushMiddleware.blankToEmpty(rc != null ? rc.getUserId() : null);
            case SESSION ->
                    MemoryFlushMiddleware.blankToEmpty(rc != null ? rc.getSessionId() : null);
            case AGENT, GLOBAL -> "";
        };
    }

    private Mono<Void> consolidateMemory(RuntimeContext rc) {
        if (consolidator == null) {
            return Mono.empty();
        }
        return consolidator
                .consolidate(rc)
                .onErrorResume(
                        e -> {
                            log.warn("Memory consolidation failed: {}", e.getMessage());
                            return Mono.empty();
                        });
    }

    private void expireDailyFiles(RuntimeContext rc) {
        AbstractFilesystem fs = workspaceManager.getFilesystem();
        if (fs == null) {
            return;
        }
        GlobResult glob = fs.glob(rc, "*.md", WorkspaceConstants.MEMORY_DIR);
        if (glob == null || glob.matches() == null) {
            return;
        }

        LocalDate cutoff = LocalDate.now().minusDays(dailyFileRetentionDays);
        for (FileInfo fi : glob.matches()) {
            if (fi.isDirectory()) {
                continue;
            }
            String fileName = fileName(fi.path());
            if (fileName.startsWith(".")) {
                continue;
            }
            String baseName =
                    fileName.endsWith(".md")
                            ? fileName.substring(0, fileName.length() - 3)
                            : fileName;
            try {
                LocalDate fileDate = LocalDate.parse(baseName);
                if (fileDate.isBefore(cutoff)) {
                    String fromPath = WorkspaceConstants.MEMORY_DIR + "/" + fileName;
                    String toPath = WorkspaceConstants.MEMORY_DIR + "/archive/" + fileName;
                    fs.move(rc, fromPath, toPath);
                    log.debug("Archived expired daily file: {}", fileName);
                }
            } catch (Exception e) {
                // 不是按日期命名的文件，跳过
            }
        }
    }

    private static String fileName(String path) {
        if (path == null) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
