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
package io.agentscope.extensions.sandbox.kubernetes.client.internal;

import io.agentscope.extensions.sandbox.kubernetes.client.config.DirectConnectionConfig;
import io.agentscope.extensions.sandbox.kubernetes.client.config.GatewayConnectionConfig;
import io.agentscope.extensions.sandbox.kubernetes.client.config.InClusterConnectionConfig;
import io.agentscope.extensions.sandbox.kubernetes.client.config.LocalTunnelConnectionConfig;
import io.agentscope.extensions.sandbox.kubernetes.client.config.SandboxConnectionConfig;
import io.agentscope.extensions.sandbox.kubernetes.client.exceptions.SandboxRequestException;
import io.agentscope.extensions.sandbox.kubernetes.client.internal.strategy.ConnectionStrategy;
import io.agentscope.extensions.sandbox.kubernetes.client.internal.strategy.DirectStrategy;
import io.agentscope.extensions.sandbox.kubernetes.client.internal.strategy.GatewayStrategy;
import io.agentscope.extensions.sandbox.kubernetes.client.internal.strategy.InClusterStrategy;
import io.agentscope.extensions.sandbox.kubernetes.client.internal.strategy.PortForwardStrategy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 管理沙箱 HTTP 连通性（经路由器或集群内直连），包括 URL 发现、重试逻辑和请求头注入。
 */
public class SandboxConnector {

    private static final Logger log = LoggerFactory.getLogger(SandboxConnector.class);
    private static final int MAX_ATTEMPTS = 6;
    private static final long BASE_BACKOFF_MS = 500;
    private static final long MAX_BACKOFF_MS = 8000;
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(500, 502, 503, 504);

    private final ConnectionStrategy strategy;
    private final String sandboxId;
    private final String namespace;
    private final int serverPort;
    private final Duration requestTimeout;
    private final Duration perAttemptTimeout;
    private final HttpClient httpClient;
    private final Supplier<String> podIpSupplier;

    private volatile String baseURL;
    private volatile String podIP;

    public SandboxConnector(
            String sandboxId,
            String namespace,
            SandboxConnectionConfig connectionConfig,
            K8sHelper k8sHelper,
            Supplier<String> podIpSupplier) {
        this(sandboxId, namespace, connectionConfig, k8sHelper, podIpSupplier, null, null);
    }

    public SandboxConnector(
            String sandboxId,
            String namespace,
            SandboxConnectionConfig connectionConfig,
            K8sHelper k8sHelper,
            Supplier<String> podIpSupplier,
            Duration requestTimeout,
            Duration perAttemptTimeout) {
        this.sandboxId = sandboxId;
        this.namespace = namespace;
        this.serverPort = connectionConfig.serverPort();
        this.requestTimeout = requestTimeout != null ? requestTimeout : Duration.ofSeconds(180);
        this.perAttemptTimeout =
                perAttemptTimeout != null ? perAttemptTimeout : Duration.ofSeconds(60);
        this.podIpSupplier = podIpSupplier;
        this.strategy =
                createStrategy(connectionConfig, k8sHelper, sandboxId, namespace, podIpSupplier);
        this.httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(30))
                        // 网关可能重定向编码的多段路径（例如 %2F
                        // 规范化）；跟随重定向以支持文件 API 下载。
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
    }

    /**
     * 供注入模拟策略的测试使用的包级私有构造器。
     *
     * @param strategy 连接策略
     * @param sandboxId 沙箱 ID
     * @param namespace 命名空间
     * @param serverPort 服务端口
     */
    SandboxConnector(
            ConnectionStrategy strategy, String sandboxId, String namespace, int serverPort) {
        this.strategy = strategy;
        this.sandboxId = sandboxId;
        this.namespace = namespace;
        this.serverPort = serverPort;
        this.requestTimeout = Duration.ofSeconds(180);
        this.perAttemptTimeout = Duration.ofSeconds(60);
        this.podIpSupplier = null;
        this.httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
    }

    private static ConnectionStrategy createStrategy(
            SandboxConnectionConfig config,
            K8sHelper k8sHelper,
            String sandboxId,
            String namespace,
            Supplier<String> podIpSupplier) {
        if (config instanceof DirectConnectionConfig direct) {
            return new DirectStrategy(direct);
        }
        if (config instanceof GatewayConnectionConfig gateway) {
            return new GatewayStrategy(k8sHelper.getClient(), gateway);
        }
        if (config instanceof LocalTunnelConnectionConfig tunnel) {
            return new PortForwardStrategy(k8sHelper.getClient(), tunnel);
        }
        if (config instanceof InClusterConnectionConfig inCluster) {
            return new InClusterStrategy(sandboxId, namespace, inCluster, podIpSupplier);
        }
        throw new IllegalArgumentException(
                "Unknown connection configuration type: " + config.getClass().getName());
    }

    /**
     * 通过连接策略发现基础 URL。
     *
     * @throws Exception 发现失败时抛出
     */
    public void connect() throws Exception {
        String url = strategy.connect();
        this.baseURL = url;
        refreshPodIp();
        log.debug("[sandbox-client] Connector base URL set: {}", url);
    }

    /**
     * 拆除传输连接。
     */
    public void close() {
        this.baseURL = null;
        try {
            strategy.close();
        } catch (Exception e) {
            log.debug("[sandbox-client] Error closing strategy: {}", e.getMessage());
        }
    }

    public boolean isConnected() {
        return baseURL != null;
    }

    public ConnectionStrategy getStrategy() {
        return strategy;
    }

    /**
     * 发送 HTTP 请求，支持重试逻辑和可选沙箱请求头。
     *
     * @param method HTTP 方法
     * @param endpoint 相对基础 URL 的路径
     * @param body 请求体；GET 时为 null
     * @param contentType 内容类型请求头
     * @param maxRetries 最大尝试次数（0 表示默认）
     * @return HTTP 响应
     */
    public HttpResponse<String> sendRequest(
            String method,
            String endpoint,
            HttpRequest.BodyPublisher body,
            String contentType,
            int maxRetries)
            throws IOException, InterruptedException {
        ensureConnected();
        int limit = maxRetries > 0 ? maxRetries : MAX_ATTEMPTS;
        IOException lastException = null;

        for (int attempt = 0; attempt < limit; attempt++) {
            HttpRequest.Builder reqBuilder = buildRequest(endpoint, contentType, perAttemptTimeout);
            if (body == null) {
                reqBuilder.GET();
            } else {
                reqBuilder.method(method, body);
            }

            try {
                HttpResponse<String> resp =
                        httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    return resp;
                }

                if (RETRYABLE_STATUS.contains(resp.statusCode()) && attempt < limit - 1) {
                    log.debug(
                            "[sandbox-client] Retryable status {}, attempt {}/{}",
                            resp.statusCode(),
                            attempt + 1,
                            limit);
                    sleep(backoff(attempt));
                    continue;
                }
                return resp;
            } catch (IOException e) {
                lastException = e;
                if (attempt < limit - 1) {
                    log.debug(
                            "[sandbox-client] Request failed, attempt {}/{}: {}",
                            attempt + 1,
                            limit,
                            e.getMessage());
                    sleep(backoff(attempt));
                }
            }
        }

        if (lastException != null) {
            throw lastException;
        }
        throw new SandboxRequestException("Request failed after " + limit + " attempts");
    }

    /**
     * 发送请求并返回原始字节响应（用于文件下载）。
     *
     * @param method HTTP 方法
     * @param endpoint 路径
     * @param body 请求体或 null
     * @param contentType 内容类型
     * @return 字节响应
     */
    public HttpResponse<byte[]> sendRequestForBytes(
            String method, String endpoint, HttpRequest.BodyPublisher body, String contentType)
            throws IOException, InterruptedException {
        ensureConnected();
        HttpRequest.Builder reqBuilder = buildRequest(endpoint, contentType, requestTimeout);
        if (body == null) {
            reqBuilder.GET();
        } else {
            reqBuilder.method(method, body);
        }
        return httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private void ensureConnected() throws IOException {
        if (baseURL == null) {
            try {
                connect();
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Failed to connect sandbox connector", e);
            }
        }
        if (baseURL == null) {
            throw new IOException("Sandbox connector is not connected");
        }
    }

    private HttpRequest.Builder buildRequest(
            String endpoint, String contentType, Duration timeout) {
        String reqURL = baseURL + "/" + endpoint;
        HttpRequest.Builder reqBuilder =
                HttpRequest.newBuilder().uri(URI.create(reqURL)).timeout(timeout);

        if (strategy.shouldInjectRouterHeaders()) {
            reqBuilder
                    .header("X-Sandbox-ID", sandboxId != null ? sandboxId : "")
                    .header("X-Sandbox-Namespace", namespace)
                    .header("X-Sandbox-Port", String.valueOf(serverPort));
            refreshPodIp();
            if (podIP != null && !podIP.isBlank()) {
                reqBuilder.header("X-Sandbox-Pod-IP", podIP);
            }
        }

        if (contentType != null && !contentType.isBlank()) {
            reqBuilder.header("Content-Type", contentType);
        }
        return reqBuilder;
    }

    private void refreshPodIp() {
        if (podIpSupplier != null) {
            try {
                String ip = podIpSupplier.get();
                if (ip != null && !ip.isBlank()) {
                    this.podIP = ip;
                }
            } catch (Exception e) {
                log.debug("[sandbox-client] Failed to refresh pod IP: {}", e.getMessage());
            }
        }
    }

    private static long backoff(int attempt) {
        if (attempt == 0) {
            return 0;
        }
        long d = Math.min((long) (BASE_BACKOFF_MS * Math.pow(2, attempt - 1)), MAX_BACKOFF_MS);
        long jitter = (long) (Math.random() * d / 4) - d / 8;
        return Math.max(d + jitter, 1);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
