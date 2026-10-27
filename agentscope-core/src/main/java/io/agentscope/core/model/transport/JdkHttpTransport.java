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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * HttpTransport 接口的纯 JDK 实现。
 *
 * <p>使用 JDK 内置 HttpClient（java.net.http.HttpClient）进行 HTTP 通信，支持：
 * <ul>
 *   <li>同步 HTTP 请求</li>
 *   <li>服务器发送事件（SSE）流</li>
 *   <li>HTTP/2，并可回退到 HTTP/1.1</li>
 *   <li>内置连接池</li>
 *   <li>可配置超时</li>
 * </ul>
 *
 * <p>除 JDK 外无需其他依赖。
 */
public class JdkHttpTransport implements HttpTransport {

    private static final Logger log = LoggerFactory.getLogger(JdkHttpTransport.class);
    private static final String SSE_DATA_PREFIX = "data:";
    private static final String SSE_DONE_MARKER = "[DONE]";

    private final HttpClient client;
    private final HttpTransportConfig config;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 使用默认配置创建新的 JdkHttpTransport。 */
    JdkHttpTransport() {
        this(HttpTransportConfig.defaults());
    }

    /**
     * 使用自定义配置创建新的 JdkHttpTransport。
     *
     * @param config 传输配置
     * @throws NullPointerException config 为 null 时抛出
     */
    JdkHttpTransport(HttpTransportConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.client = buildClient(config);
    }

    /**
     * 使用现有 HttpClient 创建新的 JdkHttpTransport。
     *
     * <p>需要在多个组件之间共享 HttpClient 实例时使用此构造方法。
     *
     * @param client 要使用的 HttpClient
     * @param config 传输配置（仅供参考）
     * @throws NullPointerException client 或 config 为 null 时抛出
     */
    public JdkHttpTransport(HttpClient client, HttpTransportConfig config) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    private static HttpClient buildClient(HttpTransportConfig config) {
        HttpClient.Builder builder =
                HttpClient.newBuilder()
                        // 客户端级 HTTP/2 通过 ALPN 服务于 https；明文请求会在
                        // buildJdkRequest 中逐请求降级。因此，每个源站按每个协议版本
                        // 分别建立连接，这是有意的设计。
                        .version(
                                config.getHttpVersion() != null
                                        ? config.getHttpVersion().toJdkHttpVersion()
                                        : HttpClient.Version.HTTP_2)
                        .followRedirects(Redirect.NORMAL)
                        .connectTimeout(config.getConnectTimeout());

        if (config.isIgnoreSsl()) {
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(
                        null, new TrustManager[] {new TrustAllManager()}, new SecureRandom());
                builder.sslContext(sslContext);
                log.error(
                        "SSL certificate verification has been disabled for this WebSocket client."
                            + " This configuration must only be used for local development or"
                            + " testing with self-signed certificates. Do not disable SSL"
                            + " verification in production environments, as it exposes connections"
                            + " to man-in-the-middle attacks.");
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                throw new HttpTransportException("Failed to create insecure SSL context", e);
            }
        }

        // 配置代理
        if (config.getProxyConfig() != null) {
            ProxyConfig proxyConfig = config.getProxyConfig();

            if (proxyConfig.getNonProxyHosts() != null
                    && !proxyConfig.getNonProxyHosts().isEmpty()) {
                builder.proxy(new NonProxyHostsSelector(proxyConfig));
            } else {
                builder.proxy(
                        ProxySelector.of(
                                new InetSocketAddress(
                                        proxyConfig.getHost(), proxyConfig.getPort())));
            }

            // 注意：JDK HttpClient 不直接支持 SOCKS5 认证。
            // HTTP 代理认证使用 Authenticator。
            if (proxyConfig.hasAuthentication() && proxyConfig.getType() == ProxyType.HTTP) {
                final String username = proxyConfig.getUsername();
                final String password = proxyConfig.getPassword();
                builder.authenticator(
                        new Authenticator() {
                            @Override
                            protected PasswordAuthentication getPasswordAuthentication() {
                                if (getRequestorType() == RequestorType.PROXY) {
                                    return new PasswordAuthentication(
                                            username, password.toCharArray());
                                }
                                return null;
                            }
                        });
            }
        }

        return builder.build();
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws HttpTransportException {
        if (closed.get()) {
            throw new HttpTransportException("Transport has been closed");
        }

        var jdkRequest = buildJdkRequest(request, false);

        try {
            var response = client.send(jdkRequest, BodyHandlers.ofString());
            return buildHttpResponse(response);
        } catch (IOException e) {
            throw new HttpTransportException("HTTP request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HttpTransportException("HTTP request interrupted", e);
        }
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        if (closed.get()) {
            return Flux.error(new HttpTransportException("Transport has been closed"));
        }

        var jdkRequest = buildJdkRequest(request, true);

        return Flux.defer(
                () -> {
                    AtomicReference<InputStream> responseBody = new AtomicReference<>();
                    long requestStartNanos = System.nanoTime();
                    return sendInputStreamAsync(jdkRequest, responseBody)
                            .timeout(Mono.delay(streamResponseTimeout()))
                            .flatMapMany(
                                    response ->
                                            handleStreamResponse(
                                                    response,
                                                    request,
                                                    responseBody,
                                                    requestStartNanos))
                            .doFinally(signal -> closeQuietly(responseBody.getAndSet(null)))
                            .onErrorMap(this::mapStreamError);
                });
    }

    /**
     * 发送流式请求，并将 Reactor 取消信号传播到 JDK future／套接字。
     *
     * <p>【学习提示】取消订阅不仅意味着下游不再读取；还需停止在途网络工作并释放资源，
     * 否则可能出现用户已停止但连接仍占用的情况。
     */
    private Mono<java.net.http.HttpResponse<InputStream>> sendInputStreamAsync(
            java.net.http.HttpRequest request, AtomicReference<InputStream> responseBody) {
        return Mono.create(
                sink -> {
                    AtomicBoolean cancelled = new AtomicBoolean(false);
                    var future = client.sendAsync(request, BodyHandlers.ofInputStream());
                    sink.onCancel(
                            () -> {
                                cancelled.set(true);
                                future.cancel(true);
                                closeQuietly(responseBody.getAndSet(null));
                            });
                    future.whenComplete(
                            (response, error) -> {
                                if (error != null) {
                                    if (!cancelled.get()) {
                                        sink.error(error);
                                    }
                                    return;
                                }

                                responseBody.set(response.body());
                                if (cancelled.get()) {
                                    closeQuietly(responseBody.getAndSet(null));
                                    return;
                                }
                                sink.success(response);
                            });
                });
    }

    /** 验证流式响应，并应用首块超时和数据块间隔超时。 */
    private Flux<String> handleStreamResponse(
            java.net.http.HttpResponse<InputStream> response,
            HttpRequest request,
            AtomicReference<InputStream> responseBody,
            long requestStartNanos) {
        InputStream inputStream = response.body();
        responseBody.set(inputStream);

        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            return readInputStreamAsync(inputStream)
                    .timeout(streamResponseTimeout())
                    .onErrorReturn("")
                    .flatMapMany(
                            errorBody -> {
                                log.warn(
                                        "HTTP request failed. URL: {} | Status: {} | Error: {}",
                                        request.getUrl(),
                                        statusCode,
                                        errorBody);
                                return Flux.error(
                                        new HttpTransportException(
                                                "HTTP request failed with status "
                                                        + statusCode
                                                        + " | "
                                                        + errorBody,
                                                statusCode,
                                                errorBody));
                            });
        }

        return processStreamResponse(inputStream, request)
                .timeout(
                        // 超时策略 1：等待首个数据块的时间。
                        // 使用从请求开始计算的响应超时预算中的剩余时间。
                        Mono.delay(remainingResponseTimeout(requestStartNanos)),

                        // 超时策略 2：数据块之间的间隔（流空闲超时）。
                        // 限制接收连续两个数据块之间的最长等待时间。
                        data -> Mono.delay(streamIdleTimeout()));
    }

    /**
     * 在有界弹性工作线程上解析 SSE 或 NDJSON 行，因为 BufferedReader 会阻塞。
     *
     * <p>【学习提示】响应式返回类型不会自动让底层 I/O 变成非阻塞。
     * 阻塞读取必须安排到合适的调度器，同时保留资源关闭与取消链路。
     */
    private Flux<String> processStreamResponse(InputStream inputStream, HttpRequest request) {
        if (inputStream == null) {
            return Flux.empty();
        }

        // 检查请求是否带有 NDJSON 格式请求头
        boolean isNdjson =
                TransportConstants.STREAM_FORMAT_NDJSON.equals(
                        request.getHeaders().get(TransportConstants.STREAM_FORMAT_HEADER));

        // 使用 Flux.using 管理资源生命周期
        return Flux.using(
                        () ->
                                new BufferedReader(
                                        new InputStreamReader(inputStream, StandardCharsets.UTF_8)),
                        reader -> isNdjson ? readNdJsonLines(reader) : readSseLines(reader),
                        this::closeQuietly)
                // reader.lines() 内部使用阻塞 I/O
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 提取非空 SSE 数据载荷，并在终止标记前停止。 */
    private Flux<String> readSseLines(BufferedReader reader) {
        return Flux.fromStream(reader.lines())
                .filter(line -> line.startsWith(SSE_DATA_PREFIX))
                .map(line -> line.substring(SSE_DATA_PREFIX.length()).trim())
                .takeWhile(data -> !SSE_DONE_MARKER.equals(data))
                .doOnNext(data -> log.debug("Received SSE data chunk"))
                .filter(data -> !data.isEmpty());
    }

    /** 提取非空 NDJSON 记录，作为已经分隔好的流数据块。 */
    private Flux<String> readNdJsonLines(BufferedReader reader) {
        return Flux.fromStream(reader.lines())
                .doOnNext(line -> log.debug("Received NDJSON line"))
                .filter(line -> !line.isEmpty());
    }

    @Override
    public void close() {
        closed.set(true);
        // HttpClient 不需要显式清理，它自行管理资源
    }

    /**
     * 获取底层 HttpClient 实例。
     *
     * <p>可用于高级配置或调试。
     *
     * @return HttpClient
     */
    HttpClient getClient() {
        return client;
    }

    /**
     * 获取传输配置。
     *
     * @return 配置
     */
    HttpTransportConfig getConfig() {
        return config;
    }

    /**
     * 检查传输是否已关闭。
     *
     * @return 已关闭时返回 true
     */
    public boolean isClosed() {
        return closed.get();
    }

    private java.net.http.HttpRequest buildJdkRequest(HttpRequest request, boolean isStreaming) {
        URI uri;
        try {
            uri = URI.create(request.getUrl());
        } catch (IllegalArgumentException e) {
            throw new HttpTransportException("Invalid URL: " + request.getUrl(), e);
        }

        var builder = java.net.http.HttpRequest.newBuilder().uri(uri);

        HttpClient.Version requestVersion = resolveRequestVersion(config.getHttpVersion(), uri);
        if (requestVersion != null) {
            builder.version(requestVersion);
        }

        if (!isStreaming && config.getReadTimeout() != null) {
            builder.timeout(config.getReadTimeout());
        }

        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }

        String method = request.getMethod().toUpperCase();
        String body = request.getBody();

        switch (method) {
            case "GET":
                builder.GET();
                break;
            case "POST":
                builder.POST(bodyPublisher(body));
                break;
            case "PUT":
                builder.PUT(bodyPublisher(body));
                break;
            case "DELETE":
                builder.method("DELETE", bodyPublisher(body));
                break;
            default:
                builder.method(method, bodyPublisher(body));
        }

        return builder.build();
    }

    /**
     * 确定逐请求的 HTTP 版本：显式配置原样优先（包括在明文 URL 上显式启用 HTTP_2 的 h2c 升级）。
     * null（自动）会将明文请求降级为 HTTP/1.1，避免 JDK 的 h2c 升级导致某些服务端丢弃请求体；
     * https 请求则返回 null，继承客户端级版本（通过 ALPN 使用 HTTP/2）。
     *
     * @param configured 配置的版本；null 表示自动选择
     * @param uri 请求 URI
     * @return 要设置到请求上的版本；null 表示继承客户端版本
     */
    static HttpClient.Version resolveRequestVersion(HttpVersion configured, URI uri) {
        if (configured != null) {
            return configured.toJdkHttpVersion();
        }
        boolean isCleartext = uri == null || !"https".equalsIgnoreCase(uri.getScheme());
        return isCleartext ? HttpClient.Version.HTTP_1_1 : null;
    }

    private java.net.http.HttpRequest.BodyPublisher bodyPublisher(String body) {
        return body != null
                ? java.net.http.HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                : java.net.http.HttpRequest.BodyPublishers.noBody();
    }

    private HttpResponse buildHttpResponse(java.net.http.HttpResponse<String> response) {
        HttpResponse.Builder builder =
                HttpResponse.builder().statusCode(response.statusCode()).body(response.body());

        response.headers()
                .map()
                .forEach(
                        (name, values) -> {
                            if (!values.isEmpty()) {
                                builder.header(name, values.get(0));
                            }
                        });

        return builder.build();
    }

    private String readInputStream(InputStream inputStream) {
        if (inputStream == null) {
            return null;
        }
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read response body: {}", e.getMessage());
            return null;
        }
    }

    /** 在 JDK HTTP 工作线程之外读取错误响应体。 */
    private Mono<String> readInputStreamAsync(InputStream inputStream) {
        return Mono.fromCallable(() -> readInputStream(inputStream))
                .defaultIfEmpty("")
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 解析配置的请求开始至首个数据块的超时。 */
    private Duration streamResponseTimeout() {
        return config.getResponseTimeout() != null
                ? config.getResponseTimeout()
                : HttpTransportConfig.DEFAULT_RESPONSE_TIMEOUT;
    }

    /**
     * 让首块超时在等待响应头和解析响应体期间共享同一个时间预算。
     *
     * <p>【学习提示】如果两个阶段各重新计时一次，实际等待可能超过用户配置。
     * 这里扣除已经消耗的时间，而不是给后续阶段重新分配完整预算。
     */
    private Duration remainingResponseTimeout(long requestStartNanos) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - requestStartNanos);
        Duration remaining = streamResponseTimeout().minus(elapsed);
        return remaining.isNegative() || remaining.isZero() ? Duration.ZERO : remaining;
    }

    /** 解析已发出流数据块之间允许的最大空闲间隔。 */
    private Duration streamIdleTimeout() {
        return config.getStreamIdleTimeout() != null
                ? config.getStreamIdleTimeout()
                : HttpTransportConfig.DEFAULT_STREAM_IDLE_TIMEOUT;
    }

    /** 将底层流式失败统一转换为传输异常类型。 */
    private Throwable mapStreamError(Throwable e) {
        if (e instanceof TimeoutException) {
            return new HttpTransportException("Stream timeout: " + e.getMessage(), e);
        }
        if (e instanceof HttpTransportException) {
            return e;
        }
        Throwable cause = e instanceof CompletionException ? e.getCause() : e;
        if (cause instanceof HttpTransportException) {
            return cause;
        }
        return new HttpTransportException("SSE/NDJSON stream failed: " + e.getMessage(), e);
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.debug("Error closing resource: {}", e.getMessage());
            }
        }
    }

    /**
     * 创建新的 JdkHttpTransport 建造者。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** JdkHttpTransport 的建造者。 */
    public static class Builder {
        private HttpTransportConfig config = HttpTransportConfig.defaults();
        private HttpClient existingClient = null;

        /**
         * 设置传输配置。
         *
         * @param config 配置
         * @return 当前建造者
         */
        public Builder config(HttpTransportConfig config) {
            this.config = config;
            return this;
        }

        /**
         * 使用现有 HttpClient 实例。
         *
         * <p>设置后，配置仅供参考，传入的客户端会原样使用。
         *
         * @param client 现有 HttpClient
         * @return 当前建造者
         */
        public Builder client(HttpClient client) {
            this.existingClient = client;
            return this;
        }

        /**
         * 构建 JdkHttpTransport。
         *
         * @return 新的 JdkHttpTransport 实例
         */
        public JdkHttpTransport build() {
            if (existingClient != null) {
                return new JdkHttpTransport(existingClient, config);
            }
            return new JdkHttpTransport(config);
        }
    }

    /**
     * 信任所有证书的 TrustManager。
     *
     * <p><b>警告：</b>这会禁用 SSL 证书验证，仅应用于测试或可信自签名证书场景。
     */
    private static class TrustAllManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // 信任所有客户端证书
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // 信任所有服务端证书
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /** 遵循非代理主机配置的 ProxySelector。 */
    private static class NonProxyHostsSelector extends ProxySelector {
        private final ProxyConfig proxyConfig;
        private final List<Proxy> proxyList;

        NonProxyHostsSelector(ProxyConfig proxyConfig) {
            this.proxyConfig = proxyConfig;
            this.proxyList = Collections.singletonList(proxyConfig.toJavaProxy());
        }

        @Override
        public List<Proxy> select(URI uri) {
            if (uri == null || uri.getHost() == null) {
                return proxyList;
            }
            if (proxyConfig.shouldBypass(uri.getHost())) {
                return Collections.singletonList(Proxy.NO_PROXY);
            }
            return proxyList;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            log.warn("Proxy connection failed: uri={}, address={}", uri, sa, ioe);
        }
    }
}
