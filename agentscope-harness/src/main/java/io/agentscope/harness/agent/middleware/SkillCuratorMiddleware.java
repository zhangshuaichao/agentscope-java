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
import io.agentscope.harness.agent.skill.curator.SkillCurator;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 安排 {@link SkillCurator} 在主 {@code call()} 完成后执行。
 * 使用整理器间隔和 {@code PeriodicGate} 门控，在单线程守护执行器中运行，
 * 不会阻塞智能体循环。
 */
public class SkillCuratorMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(SkillCuratorMiddleware.class);

    private final SkillCurator curator;
    private final ScheduledExecutorService executor;
    private volatile boolean shutdown = false;

    public SkillCuratorMiddleware(SkillCurator curator) {
        this.curator = java.util.Objects.requireNonNull(curator, "curator");
        this.executor =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "skill-curator-mw");
                            t.setDaemon(true);
                            return t;
                        });
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
        return next.apply(input).doOnComplete(this::maybeRunCurator);
    }

    /**
     * 从 {@code onAgent} 的 doOnComplete 调用：如果整理器门控允许，
     * 则将一次运行派发到守护执行器。
     */
    private void maybeRunCurator() {
        if (shutdown) {
            return;
        }
        Instant now = Instant.now();
        if (!curator.shouldRunNow(now)) {
            return;
        }
        executor.submit(
                () -> {
                    try {
                        SkillCurator.CuratorRunReport report = curator.runOnce(Instant.now());
                        log.info(
                                "skill-curator ran: transitions={} report={} duration_ms={}",
                                report.transitions(),
                                report.dryRunReportPath(),
                                report.durationMs());
                    } catch (Exception e) {
                        log.warn("skill-curator run failed: {}", e.getMessage(), e);
                    }
                });
    }

    /** 停止接收新的后台工作；此操作幂等。 */
    public void close() {
        shutdown = true;
        executor.shutdownNow();
    }

    /** 直接访问底层整理器（供 {@code agent.runCuratorOnce} 使用）。 */
    public SkillCurator curator() {
        return curator;
    }
}
