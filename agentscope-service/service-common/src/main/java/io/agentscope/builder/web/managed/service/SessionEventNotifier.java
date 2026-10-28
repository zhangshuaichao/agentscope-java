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
package io.agentscope.builder.web.managed.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** 唤醒会话事件读取者，避免持续轮询事件表。<p>每次追加发出进程内信号。PostgreSQL 部署还会发布并监听数据库通道，使其他数据平面副本的订阅者及时醒来。丢失数据库通知不影响持久事实：{@link SessionEventLog} 定期从持久化序号游标执行低频恢复读取。 */
@Service
public class SessionEventNotifier {

    private static final Logger log = LoggerFactory.getLogger(SessionEventNotifier.class);
    private static final String CHANNEL = "builder_session_events";

    private final DataSource dataSource;
    private final long reconnectDelayMs;
    private final String instanceId = UUID.randomUUID().toString();
    private final Sinks.Many<String> wakeups =
            Sinks.many().multicast().onBackpressureBuffer(256, false);
    private final AtomicBoolean running = new AtomicBoolean();
    private ExecutorService listenerExecutor;
    private volatile boolean postgres;

    public SessionEventNotifier(
            DataSource dataSource,
            @Value("${builder.session-event.notification-reconnect-ms:1000}")
                    long reconnectDelayMs) {
        this.dataSource = dataSource;
        this.reconnectDelayMs = Math.max(100L, reconnectDelayMs);
    }

    @PostConstruct
    void start() {
        postgres = detectPostgres();
        if (!postgres || !running.compareAndSet(false, true)) {
            return;
        }
        listenerExecutor =
                Executors.newSingleThreadExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "session-event-notifications");
                            thread.setDaemon(true);
                            return thread;
                        });
        listenerExecutor.submit(this::listenLoop);
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (listenerExecutor != null) {
            listenerExecutor.shutdownNow();
        }
    }

    /** 事件提交后发出本地唤醒，并尽力发送 PostgreSQL 通知。 */
    public void publish(String sessionId) {
        wakeups.tryEmitNext(sessionId);
        if (!postgres) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT pg_notify('" + CHANNEL + "', ?)")) {
            statement.setString(1, instanceId + "\n" + sessionId);
            statement.execute();
        } catch (SQLException ex) {
            // 持久化行已提交。即使此临时通知失败，
            // 恢复读取仍会发现它。
            log.debug("Failed to publish session-event notification for {}", sessionId, ex);
        }
    }

    /** 返回单个会话可合并的唤醒信号。 */
    public Flux<String> wakeups(String sessionId) {
        return wakeups.asFlux().filter(sessionId::equals);
    }

    private boolean detectPostgres() {
        try (Connection connection = dataSource.getConnection()) {
            return connection
                    .getMetaData()
                    .getDatabaseProductName()
                    .toLowerCase()
                    .contains("postgres");
        } catch (SQLException ex) {
            log.warn("Cannot inspect session-event database; using local notifications only", ex);
            return false;
        }
    }

    private void listenLoop() {
        while (running.get()) {
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                connection.setAutoCommit(true);
                statement.execute("LISTEN " + CHANNEL);
                PGConnection pg = connection.unwrap(PGConnection.class);
                while (running.get() && !Thread.currentThread().isInterrupted()) {
                    PGNotification[] notifications = pg.getNotifications(10_000);
                    if (notifications == null) {
                        continue;
                    }
                    for (PGNotification notification : notifications) {
                        String payload = notification.getParameter();
                        int separator = payload == null ? -1 : payload.indexOf('\n');
                        if (separator > 0 && instanceId.equals(payload.substring(0, separator))) {
                            continue;
                        }
                        String sessionId =
                                separator > 0 ? payload.substring(separator + 1) : payload;
                        if (sessionId != null && !sessionId.isBlank()) {
                            wakeups.tryEmitNext(sessionId);
                        }
                    }
                }
            } catch (SQLException ex) {
                if (running.get()) {
                    log.warn("Session-event notification listener disconnected; reconnecting", ex);
                    sleepBeforeReconnect();
                }
            }
        }
    }

    private void sleepBeforeReconnect() {
        try {
            Thread.sleep(reconnectDelayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
