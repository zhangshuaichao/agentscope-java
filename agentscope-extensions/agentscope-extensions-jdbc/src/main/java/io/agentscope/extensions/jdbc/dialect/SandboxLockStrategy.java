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
package io.agentscope.extensions.jdbc.dialect;

import io.agentscope.harness.agent.sandbox.SandboxLease;

/**
 * 由 {@link AbstractJdbcDialect} 实现的分布式锁契约。
 *
 * <p>{@link AbstractJdbcDialect#tryEnter} 的默认实现使用可移植的表锁
 * （INSERT／DELETE 加轮询）。支持原生建议锁的专用方言覆盖 {@code tryEnter}，
 * 例如 {@code MysqlDialect} 使用 {@code GET_LOCK}，
 * {@code PostgresDialect} 使用 {@code pg_try_advisory_lock}。
 *
 * <p>新增支持原生锁的数据库时，只需在专用方言类中覆盖 {@code tryEnter()}，无需独立策略类。
 *
 * @author shanhongyu
 */
public interface SandboxLockStrategy {

    /**
     * 获取命名锁，阻塞直到可用或超时。
     *
     * @param lockName 唯一且已验证的锁名
     * @param timeoutSeconds 最长等待时间，单位秒
     * @return 关闭时释放锁的租约
     * @throws InterruptedException 调用线程被中断时抛出
     */
    SandboxLease tryEnter(String lockName, int timeoutSeconds) throws InterruptedException;
}
