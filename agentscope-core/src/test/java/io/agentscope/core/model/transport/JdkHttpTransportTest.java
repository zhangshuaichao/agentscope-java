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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * JdkHttpTransport 实现测试。
 */
class JdkHttpTransportTest {

    private MockWebServer mockServer;
    private JdkHttpTransport transport;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();

        HttpTransportConfig config =
                HttpTransportConfig.builder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .readTimeout(Duration.ofSeconds(10)) // 同步调用的全局超时
                        .responseTimeout(
                                Duration.ofSeconds(10)) // 流式调用首个发出块
                        .streamIdleTimeout(Duration.ofSeconds(1)) // 流式调用 token 间隔
                        .build();
        transport = new JdkHttpTransport(config);
    }

    @AfterEach
    void tearDown() throws Exception {
        transport.close();
        mockServer.shutdown();
    }

    @Test
    void testExecuteSuccessfulRequest() throws Exception {
        String responseBody = "{\"message\": \"hello\"}";
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(responseBody)
                        .setHeader("Content-Type", "application/json"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/test").toString())
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .body("{\"input\": \"test\"}")
                        .build();

        HttpResponse response = transport.execute(request);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertTrue(response.isSuccessful());
        assertEquals(responseBody, response.getBody());

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("/test", recorded.getPath());
        assertEquals("{\"input\": \"test\"}", recorded.getBody().readUtf8());
    }

    @Test
    void testExecuteGetRequest() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"status\": \"ok\"}")
                        .setHeader("Content-Type", "application/json"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/get-test").toString())
                        .method("GET")
                        .build();

        HttpResponse response = transport.execute(request);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertTrue(response.isSuccessful());

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("GET", recorded.getMethod());
        assertEquals("/get-test", recorded.getPath());
    }

    @Test
    void testExecutePutRequest() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"updated\": true}"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/put-test").toString())
                        .method("PUT")
                        .header("Content-Type", "application/json")
                        .body("{\"name\": \"updated\"}")
                        .build();

        HttpResponse response = transport.execute(request);

        assertEquals(200, response.getStatusCode());

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("PUT", recorded.getMethod());
        assertEquals("{\"name\": \"updated\"}", recorded.getBody().readUtf8());
    }

    @Test
    void testExecuteDeleteRequest() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(204));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/delete-test").toString())
                        .method("DELETE")
                        .build();

        HttpResponse response = transport.execute(request);

        assertEquals(204, response.getStatusCode());

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("DELETE", recorded.getMethod());
    }

    @Test
    void testExecuteErrorResponse() {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(400)
                        .setBody("{\"error\": \"bad request\"}")
                        .setHeader("Content-Type", "application/json"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/error").toString())
                        .method("GET")
                        .build();

        HttpResponse response = transport.execute(request);

        assertNotNull(response);
        assertEquals(400, response.getStatusCode());
        assertFalse(response.isSuccessful());
    }

    @Test
    void testStreamSseEvents() {
        String sseResponse =
                "data: {\"id\":\"1\",\"output\":{\"text\":\"Hello\"}}\n\n"
                        + "data: {\"id\":\"2\",\"output\":{\"text\":\" World\"}}\n\n"
                        + "data: [DONE]\n\n";

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(sseResponse)
                        .setHeader("Content-Type", "text/event-stream"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/stream").toString())
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .body("{}")
                        .build();

        List<String> events = new ArrayList<>();
        transport.stream(request).doOnNext(events::add).blockLast();

        assertEquals(2, events.size());
        assertTrue(events.get(0).contains("\"id\":\"1\""));
        assertTrue(events.get(1).contains("\"id\":\"2\""));
    }

    @Test
    void testStreamHandlesEmptyLines() {
        String sseResponse = "\n\ndata: {\"id\":\"1\"}\n\n\n\ndata: [DONE]\n";

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(sseResponse)
                        .setHeader("Content-Type", "text/event-stream"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/stream").toString())
                        .method("POST")
                        .body("{}")
                        .build();

        StepVerifier.create(transport.stream(request))
                .expectNextMatches(data -> data.contains("\"id\":\"1\""))
                .verifyComplete();
    }

    @Test
    void testStreamErrorResponse() {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(500)
                        .setBody("{\"error\": \"internal error\"}")
                        .setHeader("Content-Type", "application/json"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/stream-error").toString())
                        .method("POST")
                        .body("{}")
                        .build();

        StepVerifier.create(transport.stream(request))
                .expectErrorMatches(
                        e ->
                                e instanceof HttpTransportException
                                        && Integer.valueOf(500)
                                                .equals(
                                                        ((HttpTransportException) e)
                                                                .getStatusCode()))
                .verify();
    }

    @Test
    void testRequestHeaders() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/headers").toString())
                        .method("POST")
                        .header("Authorization", "Bearer test-key")
                        .header("X-Custom-Header", "custom-value")
                        .body("{}")
                        .build();

        transport.execute(request);

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"));
        assertEquals("custom-value", recorded.getHeader("X-Custom-Header"));
    }

    @Test
    void testConnectionRefused() throws Exception {
        mockServer.shutdown();
        int port = mockServer.getPort();

        HttpTransportConfig config = HttpTransportConfig.defaults();
        JdkHttpTransport myTransport = new JdkHttpTransport(config);

        try {
            HttpRequest request =
                    HttpRequest.builder()
                            .url("http://localhost:" + port + "/timeout")
                            .method("GET")
                            .build();

            assertThrows(HttpTransportException.class, () -> myTransport.execute(request));
        } finally {
            myTransport.close();
        }
    }

    @Test
    void testJdkHttpTransportBuilder() {
        HttpTransportConfig config =
                HttpTransportConfig.builder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .readTimeout(Duration.ofSeconds(30))
                        .responseTimeout(Duration.ofSeconds(45))
                        .streamIdleTimeout(Duration.ofSeconds(15))
                        .build();

        JdkHttpTransport builtTransport = JdkHttpTransport.builder().config(config).build();

        assertNotNull(builtTransport);
        assertNotNull(builtTransport.getClient());
        assertEquals(config, builtTransport.getConfig());
        assertEquals(Duration.ofSeconds(45), builtTransport.getConfig().getResponseTimeout());
        assertEquals(Duration.ofSeconds(15), builtTransport.getConfig().getStreamIdleTimeout());
        assertFalse(builtTransport.isClosed());
        builtTransport.close();
        assertTrue(builtTransport.isClosed());
    }

    @Test
    void testBuilderWithExistingClient() {
        HttpClient existingClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

        HttpTransportConfig config = HttpTransportConfig.defaults();
        JdkHttpTransport builtTransport =
                JdkHttpTransport.builder().client(existingClient).config(config).build();

        assertNotNull(builtTransport);
        assertEquals(existingClient, builtTransport.getClient());
        builtTransport.close();
    }

    @Test
    void testDelayedResponse() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"delayed\": true}")
                        .setBodyDelay(100, TimeUnit.MILLISECONDS));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/delayed").toString())
                        .method("GET")
                        .build();

        HttpResponse response = transport.execute(request);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("delayed"));
    }

    @Test
    void testDefaultConstructor() {
        JdkHttpTransport defaultTransport = new JdkHttpTransport();
        assertNotNull(defaultTransport);
        assertNotNull(defaultTransport.getClient());
        assertNotNull(defaultTransport.getConfig());
        defaultTransport.close();
    }

    @Test
    void testCloseIdempotent() {
        JdkHttpTransport testTransport = new JdkHttpTransport();
        assertFalse(testTransport.isClosed());

        testTransport.close();
        assertTrue(testTransport.isClosed());

        // 第二次关闭应幂等
        testTransport.close();
        assertTrue(testTransport.isClosed());
    }

    @Test
    void testExecuteAfterClose() {
        JdkHttpTransport testTransport = new JdkHttpTransport();
        testTransport.close();

        HttpRequest request =
                HttpRequest.builder().url("http://localhost:8080/test").method("GET").build();

        assertThrows(HttpTransportException.class, () -> testTransport.execute(request));
    }

    @Test
    void testStreamAfterClose() {
        JdkHttpTransport testTransport = new JdkHttpTransport();
        testTransport.close();

        HttpRequest request =
                HttpRequest.builder().url("http://localhost:8080/test").method("GET").build();

        StepVerifier.create(testTransport.stream(request))
                .expectError(HttpTransportException.class)
                .verify();
    }

    @Test
    void testResponseHeaders() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{}")
                        .setHeader("Content-Type", "application/json")
                        .setHeader("X-Request-Id", "test-123"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/response-headers").toString())
                        .method("GET")
                        .build();

        HttpResponse response = transport.execute(request);

        assertEquals(200, response.getStatusCode());
        assertEquals("application/json", response.getHeaders().get("content-type"));
        assertEquals("test-123", response.getHeaders().get("x-request-id"));
    }

    @Test
    void testStreamMultipleEvents() {
        StringBuilder sseBuilder = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            sseBuilder.append("data: {\"id\":\"").append(i).append("\"}\n\n");
        }
        sseBuilder.append("data: [DONE]\n\n");

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(sseBuilder.toString())
                        .setHeader("Content-Type", "text/event-stream"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/multi-stream").toString())
                        .method("POST")
                        .body("{}")
                        .build();

        StepVerifier.create(transport.stream(request)).expectNextCount(5).verifyComplete();
    }

    @Test
    void testExecutePatchRequest() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"patched\": true}"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/patch-test").toString())
                        .method("PATCH")
                        .header("Content-Type", "application/json")
                        .body("{\"field\": \"value\"}")
                        .build();

        HttpResponse response = transport.execute(request);

        assertEquals(200, response.getStatusCode());

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("PATCH", recorded.getMethod());
        assertEquals("{\"field\": \"value\"}", recorded.getBody().readUtf8());
    }

    @Test
    void testExecuteCustomHttpMethod() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/custom").toString())
                        .method("OPTIONS")
                        .build();

        HttpResponse response = transport.execute(request);

        assertEquals(200, response.getStatusCode());

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("OPTIONS", recorded.getMethod());
    }

    @Test
    void testStreamWithEmptyDataLines() {
        // 测试带 data: 前缀但内容为空的 SSE
        String sseResponse =
                "data: \n\n" + "data:   \n\n" + "data: {\"id\":\"1\"}\n\n" + "data: [DONE]\n\n";

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(sseResponse)
                        .setHeader("Content-Type", "text/event-stream"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/stream-empty-data").toString())
                        .method("POST")
                        .body("{}")
                        .build();

        // 仅非空 data 行应发出
        StepVerifier.create(transport.stream(request))
                .expectNextMatches(data -> data.contains("\"id\":\"1\""))
                .verifyComplete();
    }

    @Test
    void testStreamWithNonDataLines() {
        // 测试 SSE 的 event/id/retry 行，应忽略
        String sseResponse =
                """
                event: message
                id: 123
                retry: 5000
                data: {"id":"1"}

                data: [DONE]

                """;

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(sseResponse)
                        .setHeader("Content-Type", "text/event-stream"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/stream-non-data").toString())
                        .method("POST")
                        .body("{}")
                        .build();

        StepVerifier.create(transport.stream(request))
                .expectNextMatches(data -> data.contains("\"id\":\"1\""))
                .verifyComplete();
    }

    @Test
    void testStreamCancellation() throws Exception {
        // 创建长 SSE 响应
        StringBuilder sseBuilder = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            sseBuilder.append("data: {\"id\":\"").append(i).append("\"}\n\n");
        }
        sseBuilder.append("data: [DONE]\n\n");

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(sseBuilder.toString())
                        .setHeader("Content-Type", "text/event-stream")
                        .throttleBody(50, 100, TimeUnit.MILLISECONDS));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/stream-cancel").toString())
                        .method("POST")
                        .body("{}")
                        .build();

        AtomicInteger count = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        Disposable disposable =
                transport.stream(request)
                        .doOnNext(
                                data -> {
                                    count.incrementAndGet();
                                    if (count.get() >= 3) {
                                        latch.countDown();
                                    }
                                })
                        .subscribe();

        // 等待至少 3 个事件后取消
        latch.await(5, TimeUnit.SECONDS);
        disposable.dispose();

        // 给清理留出时间
        Thread.sleep(100);

        assertTrue(count.get() >= 3, "Should have received at least 3 events before cancel");
    }

    @Test
    void testStreamConnectionError() throws Exception {
        // 立即断开以模拟连接错误
        mockServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/stream-error").toString())
                        .method("POST")
                        .body("{}")
                        .build();

        StepVerifier.create(transport.stream(request))
                .expectError(HttpTransportException.class)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void testStreamConnectionDropDuringRead() throws Exception {
        // 发送部分响应后断开
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("data: {\"id\":\"1\"}\n\n")
                        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/stream-disconnect").toString())
                        .method("POST")
                        .body("{}")
                        .build();

        // 应带已接收数据完成或报错
        AtomicBoolean hasError = new AtomicBoolean(false);
        List<String> received = new ArrayList<>();

        transport.stream(request)
                .doOnNext(received::add)
                .doOnError(e -> hasError.set(true))
                .onErrorResume(e -> Flux.empty())
                .blockLast(Duration.ofSeconds(5));

        // 收到部分数据或得到错误均为有效结果
        assertTrue(received.size() > 0 || hasError.get());
    }

    @Test
    void testExecuteDeleteWithBody() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"deleted\": true}"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/delete-with-body").toString())
                        .method("DELETE")
                        .header("Content-Type", "application/json")
                        .body("{\"id\": 123}")
                        .build();

        HttpResponse response = transport.execute(request);

        assertEquals(200, response.getStatusCode());

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("DELETE", recorded.getMethod());
        assertEquals("{\"id\": 123}", recorded.getBody().readUtf8());
    }

    @Test
    void testExecutePostWithNullBody() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/post-no-body").toString())
                        .method("POST")
                        .build();

        HttpResponse response = transport.execute(request);

        assertEquals(200, response.getStatusCode());

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("", recorded.getBody().readUtf8());
    }

    @Test
    void testStream401ErrorResponse() {
        mockServer.enqueue(
                new MockResponse().setResponseCode(401).setBody("{\"error\": \"unauthorized\"}"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/stream-401").toString())
                        .method("POST")
                        .body("{}")
                        .build();

        StepVerifier.create(transport.stream(request))
                .expectErrorMatches(
                        e ->
                                e instanceof HttpTransportException
                                        && Integer.valueOf(401)
                                                .equals(
                                                        ((HttpTransportException) e)
                                                                .getStatusCode())
                                        && ((HttpTransportException) e)
                                                .getResponseBody()
                                                .contains("unauthorized"))
                .verify();
    }

    @Test
    void testStream429ErrorResponse() {
        mockServer.enqueue(
                new MockResponse().setResponseCode(429).setBody("{\"error\": \"rate limited\"}"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/stream-429").toString())
                        .method("POST")
                        .body("{}")
                        .build();

        StepVerifier.create(transport.stream(request))
                .expectErrorMatches(
                        e ->
                                e instanceof HttpTransportException
                                        && Integer.valueOf(429)
                                                .equals(
                                                        ((HttpTransportException) e)
                                                                .getStatusCode()))
                .verify();
    }

    @Test
    void testConstructorWithExistingClient() throws Exception {
        HttpClient existingClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

        HttpTransportConfig config = HttpTransportConfig.defaults();
        JdkHttpTransport customTransport = new JdkHttpTransport(existingClient, config);

        try {
            mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

            HttpRequest request =
                    HttpRequest.builder()
                            .url(mockServer.url("/existing-client").toString())
                            .method("GET")
                            .build();

            HttpResponse response = customTransport.execute(request);
            assertEquals(200, response.getStatusCode());
            assertEquals(existingClient, customTransport.getClient());
        } finally {
            customTransport.close();
        }
    }

    @Test
    void testCloseWithExistingClient() {
        HttpClient existingClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

        HttpTransportConfig config = HttpTransportConfig.defaults();
        JdkHttpTransport customTransport = new JdkHttpTransport(existingClient, config);

        // 即使 executor 为 null，关闭也应安全
        customTransport.close();
        assertTrue(customTransport.isClosed());

        // 再次关闭应幂等
        customTransport.close();
        assertTrue(customTransport.isClosed());
    }

    @Test
    void testMaxIdleConnectionsConfig() {
        HttpTransportConfig config = HttpTransportConfig.builder().maxIdleConnections(10).build();

        JdkHttpTransport customTransport = new JdkHttpTransport(config);

        try {
            assertNotNull(customTransport.getClient());
            assertEquals(10, customTransport.getConfig().getMaxIdleConnections());
        } finally {
            customTransport.close();
        }
    }

    @Test
    void testExecute500ErrorResponse() {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(500)
                        .setBody("{\"error\": \"internal server error\"}"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/500-error").toString())
                        .method("GET")
                        .build();

        HttpResponse response = transport.execute(request);

        assertEquals(500, response.getStatusCode());
        assertFalse(response.isSuccessful());
        assertTrue(response.getBody().contains("internal server error"));
    }

    @Test
    void testLowercaseHttpMethod() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/lowercase").toString())
                        .method("get")
                        .build();

        HttpResponse response = transport.execute(request);

        assertEquals(200, response.getStatusCode());

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("GET", recorded.getMethod());
    }

    @Test
    void testIgnoreSslConfiguration() {
        HttpTransportConfig config = HttpTransportConfig.builder().ignoreSsl(true).build();

        JdkHttpTransport sslIgnoreTransport = new JdkHttpTransport(config);

        try {
            assertNotNull(sslIgnoreTransport.getClient());
            assertTrue(sslIgnoreTransport.getConfig().isIgnoreSsl());
        } finally {
            sslIgnoreTransport.close();
        }
    }

    @Test
    void testIgnoreSslDefaultFalse() {
        HttpTransportConfig config = HttpTransportConfig.defaults();

        assertFalse(config.isIgnoreSsl());

        JdkHttpTransport defaultTransport = new JdkHttpTransport(config);
        try {
            assertFalse(defaultTransport.getConfig().isIgnoreSsl());
        } finally {
            defaultTransport.close();
        }
    }

    @Test
    void testStreamErrorResponseContainsFullBody() {
        String errorBody = "{\"error\": \"detailed error message\", \"code\": \"ERR001\"}";
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(400)
                        .setBody(errorBody)
                        .setHeader("Content-Type", "application/json"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/stream-error-body").toString())
                        .method("POST")
                        .body("{}")
                        .build();

        StepVerifier.create(transport.stream(request))
                .expectErrorMatches(
                        e ->
                                e instanceof HttpTransportException
                                        && Integer.valueOf(400)
                                                .equals(
                                                        ((HttpTransportException) e)
                                                                .getStatusCode())
                                        && ((HttpTransportException) e)
                                                .getResponseBody()
                                                .contains("detailed error message")
                                        && ((HttpTransportException) e)
                                                .getResponseBody()
                                                .contains("ERR001"))
                .verify();
    }

    @Test
    void testStreamErrorBodyForDifferentStatusCodes() {
        // 测试 403 Forbidden
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(403)
                        .setBody("{\"error\": \"forbidden\", \"reason\": \"access denied\"}"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/stream-403").toString())
                        .method("POST")
                        .body("{}")
                        .build();

        StepVerifier.create(transport.stream(request))
                .expectErrorMatches(
                        e ->
                                e instanceof HttpTransportException
                                        && Integer.valueOf(403)
                                                .equals(
                                                        ((HttpTransportException) e)
                                                                .getStatusCode())
                                        && ((HttpTransportException) e)
                                                .getResponseBody()
                                                .contains("forbidden"))
                .verify();

        // 测试 502 Bad Gateway
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(502)
                        .setBody("{\"error\": \"bad gateway\", \"upstream\": \"timeout\"}"));

        HttpRequest request2 =
                HttpRequest.builder()
                        .url(mockServer.url("/stream-502").toString())
                        .method("POST")
                        .body("{}")
                        .build();

        StepVerifier.create(transport.stream(request2))
                .expectErrorMatches(
                        e ->
                                e instanceof HttpTransportException
                                        && Integer.valueOf(502)
                                                .equals(
                                                        ((HttpTransportException) e)
                                                                .getStatusCode())
                                        && ((HttpTransportException) e)
                                                .getResponseBody()
                                                .contains("bad gateway"))
                .verify();

        // 测试 503 Service Unavailable
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(503)
                        .setBody("{\"error\": \"service unavailable\"}"));

        HttpRequest request3 =
                HttpRequest.builder()
                        .url(mockServer.url("/stream-503").toString())
                        .method("POST")
                        .body("{}")
                        .build();

        StepVerifier.create(transport.stream(request3))
                .expectErrorMatches(
                        e ->
                                e instanceof HttpTransportException
                                        && Integer.valueOf(503)
                                                .equals(
                                                        ((HttpTransportException) e)
                                                                .getStatusCode())
                                        && ((HttpTransportException) e)
                                                .getResponseBody()
                                                .contains("service unavailable"))
                .verify();
    }

    @Test
    void testStreamErrorResponseWithEmptyBody() {
        mockServer.enqueue(new MockResponse().setResponseCode(500).setBody(""));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/stream-empty-error").toString())
                        .method("POST")
                        .body("{}")
                        .build();

        StepVerifier.create(transport.stream(request))
                .expectErrorMatches(
                        e ->
                                e instanceof HttpTransportException
                                        && Integer.valueOf(500)
                                                .equals(
                                                        ((HttpTransportException) e)
                                                                .getStatusCode()))
                .verify();
    }

    @Test
    void testStreamNdJsonFormat() {
        // NDJSON 响应，每行是独立 JSON 对象
        String ndJsonResponse =
                "{\"id\":1,\"text\":\"Hello\"}\n"
                        + "{\"id\":2,\"text\":\"World\"}\n"
                        + "{\"id\":3,\"text\":\"NDJSON\"}\n";

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(ndJsonResponse)
                        .setHeader("Content-Type", "application/x-ndjson"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/stream-ndjson").toString())
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .header(
                                TransportConstants.STREAM_FORMAT_HEADER,
                                TransportConstants.STREAM_FORMAT_NDJSON)
                        .body("{}")
                        .build();

        StepVerifier.create(transport.stream(request))
                .expectNext("{\"id\":1,\"text\":\"Hello\"}")
                .expectNext("{\"id\":2,\"text\":\"World\"}")
                .expectNext("{\"id\":3,\"text\":\"NDJSON\"}")
                .verifyComplete();
    }

    @Test
    void testStreamNdJsonWithEmptyLines() {
        // 含空行的 NDJSON 响应
        String ndJsonResponse =
                "{\"id\":1,\"text\":\"Hello\"}\n\n" + "{\"id\":2,\"text\":\"World\"}\n";

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(ndJsonResponse)
                        .setHeader("Content-Type", "application/x-ndjson"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/stream-ndjson-empty").toString())
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .header(
                                TransportConstants.STREAM_FORMAT_HEADER,
                                TransportConstants.STREAM_FORMAT_NDJSON)
                        .body("{}")
                        .build();

        StepVerifier.create(transport.stream(request))
                .expectNext("{\"id\":1,\"text\":\"Hello\"}")
                .expectNext("{\"id\":2,\"text\":\"World\"}")
                .verifyComplete();
    }

    @Test
    void testStreamNdJsonFormatWithoutHeaderDefaultsToSse() {
        // 未提供 NDJSON 请求头，因此应按 SSE 处理
        String sseResponse =
                "data: {\"id\":\"1\",\"text\":\"Hello\"}\n\n"
                        + "data: {\"id\":\"2\",\"text\":\"World\"}\n\n"
                        + "data: [DONE]\n\n";

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(sseResponse)
                        .setHeader("Content-Type", "text/event-stream"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/stream-sse-default").toString())
                        .method("POST")
                        .header("Content-Type", "application/json")
                        // 没有 STREAM_FORMAT_HEADER，应默认 SSE
                        .body("{}")
                        .build();

        StepVerifier.create(transport.stream(request))
                .expectNext("{\"id\":\"1\",\"text\":\"Hello\"}")
                .expectNext(
                        "{\"id\":\"2\",\"text\":\"World\"}") // 此内容在 [DONE] 标记前发送
                .verifyComplete();
    }

    @Test
    void testHttpVersionConfig() {
        HttpTransportConfig defaults = HttpTransportConfig.defaults();
        HttpTransportConfig config =
                HttpTransportConfig.builder().httpVersion(HttpVersion.HTTP_1_1).build();
        JdkHttpTransport jdkHttpTransport = JdkHttpTransport.builder().config(defaults).build();
        JdkHttpTransport jdkHttpTransport2 = JdkHttpTransport.builder().config(config).build();
        assertNull(defaults.getHttpVersion());
        assertSame(HttpVersion.HTTP_1_1, config.getHttpVersion());
        assertEquals(HttpClient.Version.HTTP_1_1, config.getHttpVersion().toJdkHttpVersion());
        assertEquals(HttpClient.Version.HTTP_2, HttpVersion.HTTP_2.toJdkHttpVersion());
        assertNotNull(jdkHttpTransport);
        assertNotNull(jdkHttpTransport2);
    }

    @Test
    void testResolveRequestVersion() {
        // auto：明文使用 HTTP/1.1，不尝试 h2c 升级；https 返回 null 继承客户端
        // 版本，缺少协议时按明文处理
        assertEquals(
                HttpClient.Version.HTTP_1_1,
                JdkHttpTransport.resolveRequestVersion(
                        null, URI.create("http://localhost:8080/v1/chat/completions")));
        assertNull(
                JdkHttpTransport.resolveRequestVersion(
                        null, URI.create("https://api.example.com/v1/chat/completions")));
        assertEquals(
                HttpClient.Version.HTTP_1_1,
                JdkHttpTransport.resolveRequestVersion(null, URI.create("localhost/v1")));
        // 显式值原样优先，包括在明文上显式选择 HTTP_2 以启用 h2c
        assertEquals(
                HttpClient.Version.HTTP_1_1,
                JdkHttpTransport.resolveRequestVersion(
                        HttpVersion.HTTP_1_1, URI.create("https://api.example.com/v1")));
        assertEquals(
                HttpClient.Version.HTTP_1_1,
                JdkHttpTransport.resolveRequestVersion(
                        HttpVersion.HTTP_1_1, URI.create("http://localhost:8080/v1")));
        assertEquals(
                HttpClient.Version.HTTP_2,
                JdkHttpTransport.resolveRequestVersion(
                        HttpVersion.HTTP_2, URI.create("http://localhost:8080/v1")));
        assertEquals(
                HttpClient.Version.HTTP_2,
                JdkHttpTransport.resolveRequestVersion(
                        HttpVersion.HTTP_2, URI.create("https://api.example.com/v1")));
    }

    @Test
    void testRequestHttpVersionAppliedPerRequest() {
        // 默认配置：明文使用 HTTP/1.1，https 继承客户端级版本
        assertEquals(
                Optional.of(HttpClient.Version.HTTP_1_1),
                executeAndCaptureVersion(
                        HttpTransportConfig.defaults(),
                        "http://localhost:8080/v1/chat/completions"));
        assertEquals(
                Optional.empty(),
                executeAndCaptureVersion(
                        HttpTransportConfig.defaults(),
                        "https://api.example.com/v1/chat/completions"));
        // 即使注入不可重配客户端，请求中的显式 HTTP_2 仍必须优先
        assertEquals(
                Optional.of(HttpClient.Version.HTTP_2),
                executeAndCaptureVersion(
                        HttpTransportConfig.builder().httpVersion(HttpVersion.HTTP_2).build(),
                        "http://localhost:8080/v1/chat/completions"));
    }

    /**
     * 通过捕获客户端执行 POST，返回所构建请求的版本。
     */
    private Optional<HttpClient.Version> executeAndCaptureVersion(
            HttpTransportConfig config, String url) {
        CapturingHttpClient capturingClient = new CapturingHttpClient();
        JdkHttpTransport transport = new JdkHttpTransport(capturingClient, config);
        HttpRequest request =
                HttpRequest.builder()
                        .url(url)
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .body("{}")
                        .build();
        transport.execute(request);
        return capturingClient.capturedRequest().version();
    }

    @Test
    void testCleartextRequestAvoidsH2cUpgradeOnTheWire() throws Exception {
        // #1121 回归：默认不得对明文 URL 尝试 h2c 升级
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));

        transport.execute(
                HttpRequest.builder()
                        .url(mockServer.url("/v1/chat/completions").toString())
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .body("{\"input\": \"test\"}")
                        .build());

        RecordedRequest recorded = mockServer.takeRequest();
        assertNull(recorded.getHeader("Upgrade"));
        String connection = recorded.getHeader("Connection");
        assertTrue(connection == null || !connection.contains("HTTP2-Settings"));
        assertEquals("{\"input\": \"test\"}", recorded.getBody().readUtf8());

        // 对比：显式 HTTP_2 确实会在线协议中发送 h2c 升级
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));
        JdkHttpTransport h2cTransport =
                new JdkHttpTransport(
                        HttpTransportConfig.builder().httpVersion(HttpVersion.HTTP_2).build());
        try {
            h2cTransport.execute(
                    HttpRequest.builder()
                            .url(mockServer.url("/v1/chat/completions").toString())
                            .method("POST")
                            .header("Content-Type", "application/json")
                            .body("{}")
                            .build());
        } finally {
            h2cTransport.close();
        }
        assertEquals("h2c", mockServer.takeRequest().getHeader("Upgrade"));
    }

    @Test
    void testStreamColdStartSurvivesGlobalTimeout() throws Exception {
        // 复现 issue 1302 报告的问题

        HttpTransportConfig customConfig =
                HttpTransportConfig.builder()
                        .readTimeout(Duration.ofSeconds(1)) // 非常短的全局超时
                        .responseTimeout(Duration.ofSeconds(4)) // 充足的首块超时
                        .streamIdleTimeout(Duration.ofSeconds(2))
                        .build();

        JdkHttpTransport customTransport = new JdkHttpTransport(customConfig);

        try {
            // 将响应头延迟 2 秒，模拟冷启动开销
            // 与 LLM 思考时间。
            mockServer.enqueue(
                    new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody("data: {\"id\":\"1\"}\n\ndata: [DONE]\n\n")
                            .setHeadersDelay(2, TimeUnit.SECONDS));

            HttpRequest request =
                    HttpRequest.builder()
                            .url(mockServer.url("/cold-start-bug-reproduction").toString())
                            .method("POST")
                            .body("{}")
                            .build();

            // 只有流在 2 秒初始延迟期间存活，
            // 未被 1 秒全局 readTimeout 终止，测试才成功。
            StepVerifier.create(customTransport.stream(request))
                    .expectNextMatches(data -> data.contains("\"id\":\"1\""))
                    .verifyComplete();
        } finally {
            customTransport.close();
        }
    }

    @Test
    void testStreamResponseTimeout() {
        // 测试超时策略 1：
        // 响应头延迟 3 秒，超过配置的 responseTimeout 2 秒。
        HttpTransportConfig customConfig =
                HttpTransportConfig.builder().responseTimeout(Duration.ofSeconds(2)).build();
        JdkHttpTransport customTransport = new JdkHttpTransport(customConfig);

        try {
            mockServer.enqueue(
                    new MockResponse()
                            .setResponseCode(200)
                            .setBody("data: {\"id\":\"1\"}\n\ndata: [DONE]\n\n")
                            .setHeader("Content-Type", "text/event-stream")
                            .setHeadersDelay(3, TimeUnit.SECONDS));

            HttpRequest request =
                    HttpRequest.builder()
                            .url(mockServer.url("/ttft-timeout").toString())
                            .method("POST")
                            .body("{}")
                            .build();

            StepVerifier.create(customTransport.stream(request))
                    .expectErrorMatches(
                            e ->
                                    e instanceof HttpTransportException
                                            && e.getMessage().contains("Stream timeout"))
                    .verify(Duration.ofSeconds(5));
        } finally {
            customTransport.close();
        }
    }

    @Test
    void testStreamIdleTimeout() {
        // 测试超时策略 2，即 token 间隔：
        // 立即发出首个完整事件，再足够长地延迟第二个事件，
        // 使其超过 streamIdleTimeout。
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody("data: {\"id\":\"1\"}\n\ndata: {\"id\":\"2\"}\n\n")
                        .throttleBody(19, 2, TimeUnit.SECONDS));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/idle-timeout").toString())
                        .method("POST")
                        .body("{}")
                        .build();

        StepVerifier.create(transport.stream(request))
                .expectNextMatches(data -> data.contains("\"id\":\"1\""))
                .expectErrorMatches(
                        e ->
                                e instanceof HttpTransportException
                                        && e.getMessage().contains("Stream timeout"))
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void testStreamTimeoutClosesBodyWhenFutureCompletesAfterCancellation() {
        HttpTransportConfig customConfig =
                HttpTransportConfig.builder()
                        .responseTimeout(Duration.ofMillis(100))
                        .streamIdleTimeout(Duration.ofSeconds(1))
                        .build();
        BlockingInputStream body = new BlockingInputStream();
        AtomicReference<CompletableFuture<java.net.http.HttpResponse<InputStream>>> futureRef =
                new AtomicReference<>();
        JdkHttpTransport customTransport =
                new JdkHttpTransport(new DeferredBodyHttpClient(futureRef, body), customConfig);

        try {
            HttpRequest request =
                    HttpRequest.builder()
                            .url("http://localhost/deferred-body")
                            .method("POST")
                            .body("{}")
                            .build();

            StepVerifier.create(customTransport.stream(request))
                    .expectErrorMatches(
                            e ->
                                    e instanceof HttpTransportException
                                            && e.getMessage().contains("Stream timeout"))
                    .verify(Duration.ofSeconds(2));

            CompletableFuture<java.net.http.HttpResponse<InputStream>> future = futureRef.get();
            assertNotNull(future);
            assertTrue(future.isCancelled(), "Timeout should cancel the pending async request");

            future.complete(new TestHttpResponse(200, body));
            assertTrue(body.awaitClosed(), "Response body must be closed after late completion");
        } finally {
            customTransport.close();
        }
    }

    @Test
    void testStreamSurvivesGlobalReadTimeout() {
        // 验证流式请求不会被全局 readTimeout 终止。
        // 自定义配置将 readTimeout 保持 2 秒，但使整个流耗时约
        // 3 秒。每 500ms 限速发送 10 字节，token 间隔小于 1 秒，
        // 因此遵守 streamIdleTimeout。
        HttpTransportConfig customConfig =
                HttpTransportConfig.builder()
                        .readTimeout(Duration.ofSeconds(2))
                        .streamIdleTimeout(Duration.ofSeconds(1))
                        .build();
        JdkHttpTransport customTransport = new JdkHttpTransport(customConfig);

        try {
            String sseBody =
                    "data: 1\n\n"
                            + "data: 2\n\n"
                            + "data: 3\n\n"
                            + "data: 4\n\n"
                            + "data: 5\n\n"
                            + "data: [DONE]\n\n";

            mockServer.enqueue(
                    new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(sseBody)
                            .throttleBody(10, 500, TimeUnit.MILLISECONDS));

            HttpRequest request =
                    HttpRequest.builder()
                            .url(mockServer.url("/survive-timeout").toString())
                            .method("POST")
                            .body("{}")
                            .build();

            StepVerifier.create(customTransport.stream(request))
                    .expectNextCount(5) // 应成功接收全部 5 个数据块
                    .verifyComplete();
        } finally {
            customTransport.close();
        }
    }

    /**
     * 记录构建的 JDK 请求供断言使用。
     */
    private static class CapturingHttpClient extends HttpClient {
        private final AtomicReference<java.net.http.HttpRequest> capturedRequest =
                new AtomicReference<>();

        java.net.http.HttpRequest capturedRequest() {
            return capturedRequest.get();
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_2;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> java.net.http.HttpResponse<T> send(
                java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler) {
            capturedRequest.set(request);
            return new java.net.http.HttpResponse<>() {
                @Override
                public int statusCode() {
                    return 200;
                }

                @Override
                public java.net.http.HttpRequest request() {
                    return request;
                }

                @Override
                public Optional<java.net.http.HttpResponse<T>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public java.net.http.HttpHeaders headers() {
                    return java.net.http.HttpHeaders.of(Map.of(), (name, value) -> true);
                }

                @Override
                public T body() {
                    return null;
                }

                @Override
                public Optional<SSLSession> sslSession() {
                    return Optional.empty();
                }

                @Override
                public URI uri() {
                    return request.uri();
                }

                @Override
                public Version version() {
                    return Version.HTTP_1_1;
                }
            };
        }

        @Override
        public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("sendAsync is not used in this test");
        }

        @Override
        public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler,
                java.net.http.HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException("sendAsync is not used in this test");
        }
    }

    private static class DeferredBodyHttpClient extends HttpClient {
        private final AtomicReference<CompletableFuture<java.net.http.HttpResponse<InputStream>>>
                futureRef;
        private final InputStream body;

        DeferredBodyHttpClient(
                AtomicReference<CompletableFuture<java.net.http.HttpResponse<InputStream>>>
                        futureRef,
                InputStream body) {
            this.futureRef = futureRef;
            this.body = body;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_2;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> java.net.http.HttpResponse<T> send(
                java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("send is not used in this test");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler) {
            CompletableFuture<java.net.http.HttpResponse<T>> future = new LateCompletableFuture<>();
            futureRef.set(
                    (CompletableFuture<java.net.http.HttpResponse<InputStream>>)
                            (CompletableFuture<?>) future);
            return future;
        }

        @Override
        public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler,
                java.net.http.HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }
    }

    private static class LateCompletableFuture<T> extends CompletableFuture<T> {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled.set(true);
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    private static class TestHttpResponse implements java.net.http.HttpResponse<InputStream> {
        private final int statusCode;
        private final InputStream body;

        TestHttpResponse(int statusCode, InputStream body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public java.net.http.HttpRequest request() {
            return null;
        }

        @Override
        public Optional<java.net.http.HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public java.net.http.HttpHeaders headers() {
            return java.net.http.HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public InputStream body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("http://localhost/deferred-body");
        }

        @Override
        public Version version() {
            return Version.HTTP_2;
        }
    }

    private static class BlockingInputStream extends InputStream {
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() {
            return -1;
        }

        @Override
        public byte[] readAllBytes() {
            return "closed".getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws IOException {
            closed.countDown();
            super.close();
        }

        boolean awaitClosed() {
            try {
                return closed.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
