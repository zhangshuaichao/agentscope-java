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
package io.agentscope.extensions.controlplane.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 用于控制平面 REST 调用的共享 JDK {@link HttpClient} 包装器。
 *
 * <p>每个请求都发送 {@code X-Builder-Internal-Token}，并强制 10 秒超时。
 */
public final class ControlPlaneHttpClient {

    /**
 * 已解码为字符串正文的 HTTP 响应。
 */
    public record Response(int status, String body) {}

    /**
 * 原始字节正文的 HTTP 响应，例如快照字节流。
 */
    public record BytesResponse(int status, byte[] body) {}

    static final String INTERNAL_TOKEN_HEADER = "X-Builder-Internal-Token";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String internalToken;
    private final HttpClient http;

    /**
     * 创建连接给定控制平面基础 URL 的客户端。
     *
     * @param baseUrl 控制平面 HTTP 基础地址，移除尾部斜杠
     * @param internalToken {@code X-Builder-Internal-Token} 的值
     */
    public ControlPlaneHttpClient(String baseUrl, String internalToken) {
        this.baseUrl = trimSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.internalToken = Objects.requireNonNull(internalToken, "internalToken");
        this.http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    /**
 * 返回已裁剪的基础 URL。
 */
    public String baseUrl() {
        return baseUrl;
    }

    /**
 * 返回控制平面客户端共享的 Jackson 映射器。
 */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * 发送 JSON 请求并返回字符串正文。
     *
     * @param method HTTP 方法
     * @param path 以 {@code /} 开头的绝对路径，可含查询字符串
     * @param jsonBody 使用 Jackson 序列化的请求正文对象，{@code null} 表示无正文
     */
    public Response send(String method, String path, Object jsonBody)
            throws IOException, InterruptedException {
        return send(method, path, jsonBody, Map.of());
    }

    /**
     * 发送带额外请求头的 JSON 请求。
     *
     * @param method HTTP 方法
     * @param path 以 {@code /} 开头的绝对路径
     * @param jsonBody 请求正文对象或 {@code null}
     * @param extraHeaders 额外请求头
     */
    public Response send(
            String method, String path, Object jsonBody, Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = newRequest(method, path, extraHeaders);
        if (jsonBody == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            byte[] json = MAPPER.writeValueAsBytes(jsonBody);
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(json));
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(resp.statusCode(), resp.body() == null ? "" : resp.body());
    }

    /**
     * 发送原始字节请求（例如快照上传），返回字符串正文。
     *
     * @param method HTTP 方法
     * @param path 以 {@code /} 开头的绝对路径
     * @param contentType {@code Content-Type} 请求头，{@code null} 表示省略
     * @param body 请求正文字节，{@code null}/{@code empty} 表示无正文
     */
    public Response sendBytes(String method, String path, String contentType, byte[] body)
            throws IOException, InterruptedException {
        return sendBytes(method, path, contentType, body, Map.of());
    }

    /**
 * 发送带额外请求头的原始字节。
 */
    public Response sendBytes(
            String method,
            String path,
            String contentType,
            byte[] body,
            Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = newRequest(method, path, extraHeaders);
        if (contentType != null && !contentType.isBlank()) {
            b.header("Content-Type", contentType);
        }
        if (body == null || body.length == 0) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(resp.statusCode(), resp.body() == null ? "" : resp.body());
    }

    /**
     * 发送请求并返回原始响应字节，用于快照下载。
     *
     * @param method HTTP 方法
     * @param path 以 {@code /} 开头的绝对路径
     * @param contentType 请求正文的可选内容类型
     * @param body 可选请求正文
     */
    public BytesResponse sendForBytes(String method, String path, String contentType, byte[] body)
            throws IOException, InterruptedException {
        return sendForBytes(method, path, contentType, body, Map.of());
    }

    /**
 * 接收带额外请求头的原始字节响应。
 */
    public BytesResponse sendForBytes(
            String method,
            String path,
            String contentType,
            byte[] body,
            Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = newRequest(method, path, extraHeaders);
        if (contentType != null && !contentType.isBlank()) {
            b.header("Content-Type", contentType);
        }
        if (body == null || body.length == 0) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        }
        HttpResponse<byte[]> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        byte[] respBody = resp.body() == null ? new byte[0] : resp.body();
        return new BytesResponse(resp.statusCode(), respBody);
    }

    private HttpRequest.Builder newRequest(
            String method, String path, Map<String, String> extraHeaders) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        HttpRequest.Builder b =
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + path))
                        .timeout(HTTP_TIMEOUT)
                        .header(INTERNAL_TOKEN_HEADER, internalToken);
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    b.header(e.getKey(), e.getValue());
                }
            }
        }
        return b;
    }

    static String trimSlash(String url) {
        String t = url.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
