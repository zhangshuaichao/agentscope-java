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
import java.io.InputStreamReader;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.ConnectionPool;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * HttpTransport 接口的 OkHttp 实现。
 *
 * <p>使用 OkHttp 进行 HTTP 通信，支持：
 * <ul>
 *   <li>同步 HTTP 请求</li>
 *   <li>服务器发送事件（SSE）流</li>
 *   <li>连接池</li>
 *   <li>可配置超时</li>
 * </ul>
 */
public class OkHttpTransport implements HttpTransport {

    private static final Logger log = LoggerFactory.getLogger(OkHttpTransport.class);
    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.parse("application/json; charset=utf-8");
    private static final String SSE_DATA_PREFIX = "data:";
    private static final String SSE_DONE_MARKER = "[DONE]";

    private final OkHttpClient client;
    private final HttpTransportConfig config;

    /** 使用默认配置创建新的 OkHttpTransport。 */
    public OkHttpTransport() {
        this(HttpTransportConfig.defaults());
    }

    /**
     * 使用自定义配置创建新的 OkHttpTransport。
     *
     * @param config 传输配置
     */
    public OkHttpTransport(HttpTransportConfig config) {
        this.config = config;
        this.client = buildClient(config);
    }

    /**
     * 使用现有 OkHttpClient 创建新的 OkHttpTransport。
     *
     * <p>需要在多个组件之间共享 OkHttpClient 实例时使用此构造方法。
     *
     * @param client 要使用的 OkHttpClient
     * @param config 传输配置（仅供参考）
     */
    public OkHttpTransport(OkHttpClient client, HttpTransportConfig config) {
        this.client = client;
        this.config = config;
    }

    private OkHttpClient buildClient(HttpTransportConfig config) {
        OkHttpClient.Builder builder =
                new OkHttpClient.Builder()
                        .connectTimeout(
                                config.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        // OkHttp 的 readTimeout 是套接字读取空闲超时，仍适用于流式读取，
                        // 与 HttpTransportConfig 中 JDK 流式响应的首块超时和空闲超时
                        // 是不同的配置。
                        .readTimeout(config.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .writeTimeout(config.getWriteTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .connectionPool(
                                new ConnectionPool(
                                        config.getMaxIdleConnections(),
                                        config.getKeepAliveDuration().toMillis(),
                                        TimeUnit.MILLISECONDS));

        // 配置 SSL（可选地忽略证书验证）
        if (config.isIgnoreSsl()) {
            log.error(
                    "SSL certificate verification has been disabled for this WebSocket client. This"
                        + " configuration must only be used for local development or testing with"
                        + " self-signed certificates. Do not disable SSL verification in production"
                        + " environments, as it exposes connections to man-in-the-middle attacks.");
            builder =
                    builder.sslSocketFactory(
                                    createTrustAllSslSocketFactory(), createTrustAllTrustManager())
                            .hostnameVerifier((hostname, session) -> true);
        }

        // 配置代理
        if (config.getProxyConfig() != null) {
            ProxyConfig proxyConfig = config.getProxyConfig();

            if (proxyConfig.getNonProxyHosts() != null
                    && !proxyConfig.getNonProxyHosts().isEmpty()) {
                builder.proxySelector(new NonProxyHostsSelector(proxyConfig));
            } else {
                builder.proxy(proxyConfig.toJavaProxy());
            }

            if (proxyConfig.hasAuthentication()) {
                final String username = proxyConfig.getUsername();
                final String password = proxyConfig.getPassword();
                builder.proxyAuthenticator(
                        (route, response) -> {
                            if (response.request().header("Proxy-Authorization") != null) {
                                return null; // 避免无限重试
                            }
                            String credential = Credentials.basic(username, password);
                            return response.request()
                                    .newBuilder()
                                    .header("Proxy-Authorization", credential)
                                    .build();
                        });
            }
        }

        return builder.build();
    }

    /**
     * 创建信任所有证书的 SSL 套接字工厂。
     *
     * @return SSL 套接字工厂
     */
    private static SSLSocketFactory createTrustAllSslSocketFactory() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(
                    null, new TrustManager[] {createTrustAllTrustManager()}, new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException("Failed to create trust-all SSL socket factory", e);
        }
    }

    /**
     * 创建信任所有证书的 X509 信任管理器。
     *
     * @return 信任管理器
     */
    private static X509TrustManager createTrustAllTrustManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // 信任所有证书
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                // 信任所有证书
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws HttpTransportException {
        Request okHttpRequest = buildOkHttpRequest(request);

        try (Response response = client.newCall(okHttpRequest).execute()) {
            return buildHttpResponse(response);
        } catch (IOException e) {
            throw new HttpTransportException("HTTP request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        Request okHttpRequest = buildOkHttpRequest(request);
        log.debug(
                "Streaming request: method={}, url={}",
                okHttpRequest.method(),
                okHttpRequest.url());

        boolean isNdjson =
                TransportConstants.STREAM_FORMAT_NDJSON.equals(
                        request.getHeaders().get(TransportConstants.STREAM_FORMAT_HEADER));
        return Flux.<String>create(
                        sink -> {
                            Response response = null;
                            BufferedReader reader = null;
                            try {
                                response = client.newCall(okHttpRequest).execute();

                                if (!response.isSuccessful()) {
                                    String errorBody = getResponseBodyString(response);
                                    log.error(
                                            "HTTP error: status={}, body={}",
                                            response.code(),
                                            errorBody);
                                    sink.error(
                                            new HttpTransportException(
                                                    "HTTP request failed with status "
                                                            + response.code(),
                                                    response.code(),
                                                    errorBody));
                                    return;
                                }

                                ResponseBody body = response.body();
                                if (body == null) {
                                    sink.complete();
                                    return;
                                }

                                reader =
                                        new BufferedReader(
                                                new InputStreamReader(
                                                        body.byteStream(), StandardCharsets.UTF_8));

                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (sink.isCancelled()) {
                                        break;
                                    }

                                    // 跳过空行
                                    if (line.isEmpty()) {
                                        continue;
                                    }

                                    // 处理 NDJSON 格式
                                    if (isNdjson) {
                                        sink.next(line);
                                        continue;
                                    }

                                    // 解析 SSE 数据行
                                    if (line.startsWith(SSE_DATA_PREFIX)) {
                                        String data =
                                                line.substring(SSE_DATA_PREFIX.length()).trim();

                                        // 检查流结束标记
                                        if (SSE_DONE_MARKER.equals(data)) {
                                            log.debug("Received SSE [DONE] marker");
                                            break;
                                        }

                                        if (!data.isEmpty()) {
                                            sink.next(data);
                                        }
                                    }
                                    // 跳过其他 SSE 字段（event:、id:、retry:、注释）
                                }

                                sink.complete();
                            } catch (IOException e) {
                                if (!sink.isCancelled()) {
                                    sink.error(
                                            new HttpTransportException(
                                                    "SSE stream read failed: " + e.getMessage(),
                                                    e));
                                }
                            } finally {
                                closeQuietly(reader);
                                if (response != null) {
                                    closeQuietly(response.body());
                                }
                                closeQuietly(response);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic(), false);
    }

    @Override
    public void close() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    /**
     * 获取底层 OkHttpClient 实例。
     *
     * <p>可用于高级配置或调试。
     *
     * @return OkHttpClient
     */
    public OkHttpClient getClient() {
        return client;
    }

    /**
     * 获取传输配置。
     *
     * @return 配置
     */
    public HttpTransportConfig getConfig() {
        return config;
    }

    private Request buildOkHttpRequest(HttpRequest request) {
        Request.Builder builder = new Request.Builder().url(request.getUrl());

        // 添加请求头
        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            builder.addHeader(header.getKey(), header.getValue());
        }

        // 设置方法和请求体
        String method = request.getMethod().toUpperCase();
        String body = request.getBody();

        switch (method) {
            case "GET":
                builder.get();
                break;
            case "POST":
                builder.post(
                        body != null
                                ? RequestBody.create(body, JSON_MEDIA_TYPE)
                                : RequestBody.create("", JSON_MEDIA_TYPE));
                break;
            case "PUT":
                builder.put(
                        body != null
                                ? RequestBody.create(body, JSON_MEDIA_TYPE)
                                : RequestBody.create("", JSON_MEDIA_TYPE));
                break;
            case "DELETE":
                if (body != null) {
                    builder.delete(RequestBody.create(body, JSON_MEDIA_TYPE));
                } else {
                    builder.delete();
                }
                break;
            default:
                builder.method(
                        method, body != null ? RequestBody.create(body, JSON_MEDIA_TYPE) : null);
        }

        return builder.build();
    }

    private HttpResponse buildHttpResponse(Response response) throws IOException {
        HttpResponse.Builder builder =
                HttpResponse.builder()
                        .statusCode(response.code())
                        .body(getResponseBodyString(response));

        // 复制响应头
        for (String name : response.headers().names()) {
            builder.header(name, response.header(name));
        }

        return builder.build();
    }

    private String getResponseBodyString(Response response) {
        try {
            ResponseBody body = response.body();
            return body != null ? body.string() : null;
        } catch (IOException e) {
            log.warn("Failed to read response body: {}", e.getMessage());
            return null;
        }
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
     * 创建新的 OkHttpTransport 建造者。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** OkHttpTransport 的建造者。 */
    public static class Builder {
        private HttpTransportConfig config = HttpTransportConfig.defaults();
        private OkHttpClient existingClient = null;

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
         * 使用现有 OkHttpClient 实例。
         *
         * <p>设置后，配置仅供参考，传入的客户端会原样使用。
         *
         * @param client 现有 OkHttpClient
         * @return 当前建造者
         */
        public Builder client(OkHttpClient client) {
            this.existingClient = client;
            return this;
        }

        /**
         * 构建 OkHttpTransport。
         *
         * @return 新的 OkHttpTransport 实例
         */
        public OkHttpTransport build() {
            if (existingClient != null) {
                return new OkHttpTransport(existingClient, config);
            }
            return new OkHttpTransport(config);
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
