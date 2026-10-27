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
package io.agentscope.extensions.mysql.sandbox;

import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 使用 MySQL {@code GET_LOCK()} / {@code RELEASE_LOCK()} 的 JDBC {@link SandboxExecutionGuard}。
 *
 * <p>每个锁由根据 {@link SandboxIsolationKey} 的作用域和值生成的字符串键标识。{@code GET_LOCK()} 阻塞直到锁可用或超时。
 * 锁绑定数据库连接，物理连接关闭时自动释放。
 *
 * <p>返回的 {@link SandboxLease#close()} 调用 {@code RELEASE_LOCK()} 并关闭底层连接。
 *
 * <p><b>重要：</b>MySQL 命名锁属于服务器作用域，而非数据库作用域。使用唯一 {@code keyPrefix}，避免与同一 MySQL 服务器上的其他应用冲突。
 *
 * <p>【学习提示】必须关闭返回的租约；close 内的释放逻辑不会在“忘记调用 close”时自行执行。连接池的逻辑 close 也不能一概等同于物理断连。
 */
public final class JdbcSandboxExecutionGuard implements SandboxExecutionGuard {

    private static final Logger log = LoggerFactory.getLogger(JdbcSandboxExecutionGuard.class);

    private static final String DEFAULT_KEY_PREFIX = "agentscope:sandbox:lock:";

    private final DataSource dataSource;
    private final String keyPrefix;
    private final int lockTimeoutSeconds;

    private JdbcSandboxExecutionGuard(Builder builder) {
        this.dataSource = builder.dataSource;
        this.keyPrefix = builder.keyPrefix;
        this.lockTimeoutSeconds = builder.lockTimeoutSeconds;
    }

    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }

    @Override
    public SandboxLease tryEnter(SandboxIsolationKey key) throws InterruptedException {
        String lockName = composeLockName(key);
        log.debug("[sandbox-guard] Acquiring MySQL lock: {}", lockName);

        try {
            Connection conn = dataSource.getConnection();
            try (PreparedStatement ps = conn.prepareStatement("SELECT GET_LOCK(?, ?)")) {
                ps.setString(1, lockName);
                ps.setInt(2, lockTimeoutSeconds);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) == 1) {
                        log.debug("[sandbox-guard] Acquired MySQL lock: {}", lockName);
                        return new MysqlLease(conn, lockName);
                    }
                }
            } catch (Exception e) {
                conn.close();
                throw e;
            }
            conn.close();
            throw new InterruptedException(
                    "Timed out waiting for MySQL lock: "
                            + lockName
                            + " (timeout="
                            + lockTimeoutSeconds
                            + "s)");
        } catch (InterruptedException e) {
            throw e;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire MySQL lock: " + lockName, e);
        }
    }

    private String composeLockName(SandboxIsolationKey key) {
        String raw = keyPrefix + key.getScope().name().toLowerCase() + ":" + key.getValue();
        if (raw.length() <= 64) {
            return raw;
        }
        // MySQL GET_LOCK() 最多支持 64 个字符，超长时使用哈希
        int hash = raw.hashCode();
        return raw.substring(0, 50) + ":" + Integer.toHexString(hash);
    }

    private static final class MysqlLease implements SandboxLease {

        private final Connection conn;
        private final String lockName;

        MysqlLease(Connection conn, String lockName) {
            this.conn = conn;
            this.lockName = lockName;
        }

        @Override
        public void close() {
            try {
                try (PreparedStatement ps = conn.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    ps.setString(1, lockName);
                    ps.executeQuery();
                    log.debug("[sandbox-guard] Released MySQL lock: {}", lockName);
                }
            } catch (Exception e) {
                log.warn(
                        "[sandbox-guard] Failed to release MySQL lock {}: {}",
                        lockName,
                        e.getMessage(),
                        e);
            } finally {
                try {
                    conn.close();
                } catch (SQLException e) {
                    log.warn("[sandbox-guard] Failed to close lock connection: {}", e.getMessage());
                }
            }
        }
    }

    public static final class Builder {

        private static final int DEFAULT_LOCK_TIMEOUT_SECONDS = 1800; // 30 分钟

        private final DataSource dataSource;
        private String keyPrefix = DEFAULT_KEY_PREFIX;
        private int lockTimeoutSeconds = DEFAULT_LOCK_TIMEOUT_SECONDS;

        private Builder(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        }

        public Builder keyPrefix(String keyPrefix) {
            if (keyPrefix == null || keyPrefix.isBlank()) {
                throw new IllegalArgumentException("keyPrefix must not be blank");
            }
            this.keyPrefix = keyPrefix.endsWith(":") ? keyPrefix : keyPrefix + ":";
            return this;
        }

        public Builder lockTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.lockTimeoutSeconds = (int) timeout.toSeconds();
            return this;
        }

        public JdbcSandboxExecutionGuard build() {
            return new JdbcSandboxExecutionGuard(this);
        }
    }
}
