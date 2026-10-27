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
package io.agentscope.core.model.transport;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 管理 HttpTransport 实例的工厂。
 *
 * <p>提供以下能力：
 * <ul>
 *   <li>延迟初始化的默认 HttpTransport 单例</li>
 *   <li>注册自定义 HttpTransport 实例以管理生命周期</li>
 *   <li>通过 JVM 关闭钩子自动清理</li>
 * </ul>
 *
 * <p>用法示例：
 * <pre>{@code
 * // Get the default transport (lazily created)
 * HttpTransport transport = HttpTransportFactory.getDefault();
 *
 * // Set a custom default transport
 * HttpTransport custom = OkHttpTransport.builder()
 *     .config(HttpTransportConfig.builder()
 *         .connectTimeout(Duration.ofSeconds(30))
 *         .build())
 *     .build();
 * HttpTransportFactory.setDefault(custom);
 *
 * // Register a transport for automatic cleanup
 * HttpTransportFactory.register(myTransport);
 * }</pre>
 *
 * <p>JVM 关闭时，{@code AgentScopeJvmShutdownHook} 会在所有智能体处理结束后，
 * 统一关闭已注册的传输以及默认传输。
 */
public final class HttpTransportFactory {

    private static final Logger log = LoggerFactory.getLogger(HttpTransportFactory.class);

    /** 默认传输实例（延迟初始化）。 */
    private static volatile HttpTransport defaultTransport;

    /** 为清理而管理的全部传输列表。 */
    private static final List<HttpTransport> managedTransports = new CopyOnWriteArrayList<>();

    /** 用于同步的锁对象。 */
    private static final Object lock = new Object();

    private HttpTransportFactory() {
        // 工具类，不可实例化
    }

    /**
     * 获取默认 HttpTransport 实例。
     *
     * <p>未设置默认实例时，会延迟创建采用默认配置的 JdkHttpTransport。
     * 默认传输会自动注册，以便在 JVM 关闭时清理。
     *
     * @return 默认 HttpTransport 实例
     */
    public static HttpTransport getDefault() {
        if (defaultTransport == null) {
            synchronized (lock) {
                if (defaultTransport == null) {
                    defaultTransport = new JdkHttpTransport();
                    managedTransports.add(defaultTransport);
                    log.debug("Created default HttpTransport: {}", defaultTransport);
                }
            }
        }
        return defaultTransport;
    }

    /**
     * 设置默认 HttpTransport 实例。
     *
     * <p>提供的传输若尚未注册，会注册以便自动清理。
     * 之前的默认传输（如果存在）仍保留在清理注册表中。
     *
     * @param transport 要设为默认的传输（可为 null，表示清除）
     */
    public static void setDefault(HttpTransport transport) {
        synchronized (lock) {
            defaultTransport = transport;
            if (transport != null && !managedTransports.contains(transport)) {
                managedTransports.add(transport);
            }
            log.debug("Set default HttpTransport: {}", transport);
        }
    }

    /**
     * 注册 HttpTransport 以管理生命周期。
     *
     * <p>已注册的传输在 JVM 关闭时自动关闭。
     * 适用于不是默认传输但仍需清理的实例。
     *
     * @param transport 要注册的传输
     */
    public static void register(HttpTransport transport) {
        if (transport != null && !managedTransports.contains(transport)) {
            managedTransports.add(transport);
            log.debug("Registered HttpTransport for management: {}", transport);
        }
    }

    /**
     * 将 HttpTransport 从生命周期管理中注销。
     *
     * <p>注销后，该传输不会在关闭流程中自动关闭。
     * 注销操作本身不会立即关闭传输。
     *
     * @param transport 要注销的传输
     * @return 成功注销时为 true；原本未注册时为 false
     */
    public static boolean unregister(HttpTransport transport) {
        boolean removed = managedTransports.remove(transport);
        if (removed) {
            log.debug("Unregistered HttpTransport from management: {}", transport);
        }
        return removed;
    }

    /**
     * 关闭并释放所有受管理的传输。
     *
     * <p>由 JVM 关闭钩子自动调用，也可在测试或显式清理时手动调用。
     * 调用后默认传输为 null，受管理的传输集合被清空。
     *
     * <p>关闭期间的错误会记录到日志，但不阻止关闭其他传输。
     */
    public static void shutdown() {
        synchronized (lock) {
            log.info("Shutting down {} managed HttpTransport(s)", managedTransports.size());
            for (HttpTransport transport : managedTransports) {
                try {
                    transport.close();
                    log.debug("Closed HttpTransport: {}", transport);
                } catch (Exception e) {
                    log.warn("Error closing HttpTransport {}: {}", transport, e.getMessage());
                }
            }
            managedTransports.clear();
            defaultTransport = null;
        }
    }

    /**
     * 获取受管理的传输数量。
     *
     * <p>主要供测试使用。
     *
     * @return 受管理的传输数量
     */
    public static int getManagedTransportCount() {
        return managedTransports.size();
    }

    /**
     * 检查某个传输当前是否受到管理。
     *
     * <p>主要供测试使用。
     *
     * @param transport 要检查的传输
     * @return 传输受到管理时返回 true
     */
    public static boolean isManaged(HttpTransport transport) {
        return managedTransports.contains(transport);
    }
}
