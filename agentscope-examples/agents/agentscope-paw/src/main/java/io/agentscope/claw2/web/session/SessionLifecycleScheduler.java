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
package io.agentscope.claw2.web.session;

import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.runtime.config.SessionLifecycleConfig;
import io.agentscope.claw2.runtime.session.SessionAgentManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 驱动 {@code agentscope.json} 的 {@code session} 配置块声明的会话生命周期触发器：
 *
 * <ul>
 *   <li><b>每日重置：</b>在配置的 {@code dailyAt} 时间重置活动会话，保留会话键、归属和标签，开始新的对话记录。
 *   <li><b>空闲重置：</b>每分钟重置空闲时间超过 {@code idleMinutes} 的会话。
 *   <li><b>维护：</b>每 5 分钟调用 {@link SessionAgentManager#runMaintenance()}，按 {@code AgentManagerConfig} 策略清理过旧或超出容量的会话。
 * </ul>
 *
 * <p>未配置 {@code session} 块时不执行调度。
 */
@Component
public class SessionLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(SessionLifecycleScheduler.class);

    private final SessionAgentManager sessionAgentManager;
    private final SessionLifecycleConfig cfg;
    private final ScheduledExecutorService scheduler;

    public SessionLifecycleScheduler(ClawBootstrap builderBootstrap) {
        this.sessionAgentManager = builderBootstrap.gateway().sessionAgentManager();
        this.cfg = builderBootstrap.loadedConfig().getSession();
        this.scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "claw-session-lifecycle");
                            t.setDaemon(true);
                            return t;
                        });
    }

    @PostConstruct
    public void start() {
        if (cfg == null) {
            log.debug("No 'session' block in agentscope.json — lifecycle scheduler disabled");
            return;
        }

        // 空闲重置：每分钟运行；idleMinutes 为 null 或 0 时无操作。
        Integer idleMin = cfg.getReset() != null ? cfg.getReset().getIdleMinutes() : null;
        if (idleMin != null && idleMin > 0) {
            long idleMs = idleMin * 60_000L;
            scheduler.scheduleAtFixedRate(
                    () -> {
                        try {
                            int n = sessionAgentManager.resetIdleSessions(idleMs);
                            if (n > 0) {
                                log.debug("Idle reset cycle: reset {} sessions", n);
                            }
                        } catch (Exception e) {
                            log.warn("Idle reset failed", e);
                        }
                    },
                    1,
                    1,
                    TimeUnit.MINUTES);
            log.info("Idle reset enabled: idleMinutes={}", idleMin);
        }

        // 每日重置：先调度下一次 dailyAt，之后每隔 24 小时重复。
        String dailyAt = cfg.getReset() != null ? cfg.getReset().getDailyAt() : null;
        if (dailyAt != null && !dailyAt.isBlank()) {
            try {
                LocalTime target = LocalTime.parse(dailyAt.trim());
                long initialDelayMs = millisUntilNext(target);
                scheduler.scheduleAtFixedRate(
                        () -> {
                            try {
                                int n = sessionAgentManager.resetAllSessions();
                                log.info("Daily reset cycle: reset {} sessions", n);
                            } catch (Exception e) {
                                log.warn("Daily reset failed", e);
                            }
                        },
                        initialDelayMs,
                        TimeUnit.DAYS.toMillis(1),
                        TimeUnit.MILLISECONDS);
                log.info(
                        "Daily reset enabled: dailyAt={} (first run in {} min)",
                        target,
                        initialDelayMs / 60_000);
            } catch (Exception e) {
                log.warn("Invalid session.reset.dailyAt value '{}'; expected HH:mm", dailyAt);
            }
        }

        // 维护：每 5 分钟运行。
        scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        int n = sessionAgentManager.runMaintenance();
                        if (n > 0) {
                            log.debug("AgentStateStore maintenance: pruned {} entries", n);
                        }
                    } catch (Exception e) {
                        log.warn("Maintenance failed", e);
                    }
                },
                5,
                5,
                TimeUnit.MINUTES);
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    private static long millisUntilNext(LocalTime time) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = LocalDateTime.of(LocalDate.now(), time);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Duration.between(now, next).toMillis();
    }
}
