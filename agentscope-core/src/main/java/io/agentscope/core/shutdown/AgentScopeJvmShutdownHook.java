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
package io.agentscope.core.shutdown;

import io.agentscope.core.model.transport.HttpTransportFactory;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用于 SIGTERM 优雅关闭入口的 JVM 钩子。
 *
 * <p>关闭顺序：
 * <ol>
 *   <li>触发优雅关闭（拒绝新请求，在检查点中断）</li>
 *   <li>等待所有活跃智能体请求完成或超时</li>
 *   <li>关闭 HTTP 传输层（只能在智能体处理完毕后进行）</li>
 * </ol>
 */
public final class AgentScopeJvmShutdownHook {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeJvmShutdownHook.class);
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private static final Duration INTERRUPT_GRACE_PERIOD = Duration.ofSeconds(5);

    private AgentScopeJvmShutdownHook() {}

    public static void register(GracefulShutdownManager manager) {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    try {
                                        manager.performGracefulShutdown();
                                        Duration timeout = manager.getConfig().shutdownTimeout();
                                        // 等待关闭超时时长，再额外留出一段宽限期，
                                        // 使智能体有时间处理中断并完成清理，
                                        // 然后才关闭 HTTP 传输层。
                                        Duration awaitTimeout =
                                                timeout != null
                                                        ? timeout.plus(INTERRUPT_GRACE_PERIOD)
                                                        : null;
                                        manager.awaitTermination(awaitTimeout);
                                    } catch (Exception e) {
                                        log.warn("Graceful shutdown hook failed", e);
                                    } finally {
                                        HttpTransportFactory.shutdown();
                                    }
                                },
                                "agentscope-jvm-shutdown-hook"));
    }
}
