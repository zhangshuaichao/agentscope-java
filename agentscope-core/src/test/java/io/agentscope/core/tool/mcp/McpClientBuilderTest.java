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
package io.agentscope.core.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.Version;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpClientBuilderTest {

    @Test
    void testCreate_WithValidName() {
        McpClientBuilder builder = McpClientBuilder.create("test-client");
        assertNotNull(builder);
    }

    @Test
    void testCreate_WithNullName() {
        assertThrows(IllegalArgumentException.class, () -> McpClientBuilder.create(null));
    }

    @Test
    void testCreate_WithEmptyName() {
        assertThrows(IllegalArgumentException.class, () -> McpClientBuilder.create(""));
    }

    @Test
    void testCreate_WithWhitespaceName() {
        assertThrows(IllegalArgumentException.class, () -> McpClientBuilder.create("   "));
    }

    @Test
    void testStdioTransport_BasicCommand() {
        McpClientBuilder builder =
                McpClientBuilder.create("stdio-client")
                        .stdioTransport("python", "-m", "mcp_server_time");

        assertNotNull(builder);
    }

    @Test
    void testStdioTransport_WithNoArgs() {
        McpClientBuilder builder = McpClientBuilder.create("stdio-client").stdioTransport("node");

        assertNotNull(builder);
    }

    @Test
    void testStdioTransport_WithEnvironmentVariables() {
        Map<String, String> env = new HashMap<>();
        env.put("DEBUG", "true");
        env.put("LOG_LEVEL", "info");

        McpClientBuilder builder =
                McpClientBuilder.create("stdio-client")
                        .stdioTransport("python", List.of("-m", "mcp_server_time"), env);

        assertNotNull(builder);
    }

    @Test
    void testSseTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("sse-client").sseTransport("https://mcp.example.com/sse");

        assertNotNull(builder);
    }

    @Test
    void testStreamableHttpTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("http-client")
                        .streamableHttpTransport("https://mcp.example.com/http");

        assertNotNull(builder);
    }

    @Test
    void testHeader_OnHttpTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("http-client")
                        .sseTransport("https://mcp.example.com/sse")
                        .header("Authorization", "Bearer token123")
                        .header("X-Custom-Header", "custom-value");

        assertNotNull(builder);
    }

    @Test
    void testHeader_OnStdioTransport() {
        // 为 stdio 传输添加请求头不应出错，仅忽略
        McpClientBuilder builder =
                McpClientBuilder.create("stdio-client")
                        .stdioTransport("python", "-m", "mcp_server_time")
                        .header("Authorization", "Bearer token123");

        assertNotNull(builder);
    }

    @Test
    void testHeaders_MultipleHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer token123");
        headers.put("X-API-Key", "api-key-456");
        headers.put("Content-Type", "application/json");

        McpClientBuilder builder =
                McpClientBuilder.create("http-client")
                        .sseTransport("https://mcp.example.com/sse")
                        .headers(headers);

        assertNotNull(builder);
    }

    @Test
    void testTimeout() {
        McpClientBuilder builder =
                McpClientBuilder.create("client").timeout(Duration.ofSeconds(90));

        assertNotNull(builder);
    }

    @Test
    void testInitializationTimeout() {
        McpClientBuilder builder =
                McpClientBuilder.create("client").initializationTimeout(Duration.ofSeconds(45));

        assertNotNull(builder);
    }

    @Test
    void testFluentApi_ChainedCalls() {
        McpClientBuilder builder =
                McpClientBuilder.create("test-client")
                        .sseTransport("https://mcp.example.com/sse")
                        .header("Authorization", "Bearer token")
                        .timeout(Duration.ofSeconds(60))
                        .initializationTimeout(Duration.ofSeconds(30));

        assertNotNull(builder);
    }

    @Test
    void testBuildAsync_WithoutTransport() {
        McpClientBuilder builder = McpClientBuilder.create("client");

        Exception exception =
                assertThrows(IllegalStateException.class, () -> builder.buildAsync().block());

        assertTrue(exception.getMessage().contains("Transport must be configured"));
    }

    @Test
    void testBuildSync_WithoutTransport() {
        McpClientBuilder builder = McpClientBuilder.create("client");

        assertThrows(
                IllegalStateException.class,
                () -> builder.buildSync(),
                "Transport must be configured");
    }

    @Test
    void testBuildAsync_ReturnsWrapper() {
        McpClientBuilder builder =
                McpClientBuilder.create("test-client").stdioTransport("echo", "hello");

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
        assertEquals("test-client", wrapper.getName());
        assertFalse(wrapper.isInitialized());
        assertTrue(wrapper instanceof McpAsyncClientWrapper);
    }

    @Test
    void testBuildSync_ReturnsWrapper() {
        McpClientBuilder builder =
                McpClientBuilder.create("test-sync-client").stdioTransport("echo", "hello");

        McpClientWrapper wrapper = builder.buildSync();

        assertNotNull(wrapper);
        assertEquals("test-sync-client", wrapper.getName());
        assertFalse(wrapper.isInitialized());
        assertTrue(wrapper instanceof McpSyncClientWrapper);
    }

    @Test
    void testPropagateMeta_DefaultsToTrue() {
        McpClientWrapper asyncWrapper =
                McpClientBuilder.create("meta-default-async")
                        .stdioTransport("echo", "hello")
                        .buildAsync()
                        .block();
        assertNotNull(asyncWrapper);
        assertTrue(asyncWrapper.isPropagateMeta());

        McpClientWrapper syncWrapper =
                McpClientBuilder.create("meta-default-sync")
                        .stdioTransport("echo", "hello")
                        .buildSync();
        assertNotNull(syncWrapper);
        assertTrue(syncWrapper.isPropagateMeta());
    }

    @Test
    void testPropagateMeta_DisabledAppliedToBuiltWrappers() {
        McpClientWrapper asyncWrapper =
                McpClientBuilder.create("meta-off-async")
                        .propagateMeta(false)
                        .stdioTransport("echo", "hello")
                        .buildAsync()
                        .block();
        assertNotNull(asyncWrapper);
        assertFalse(asyncWrapper.isPropagateMeta());

        McpClientWrapper syncWrapper =
                McpClientBuilder.create("meta-off-sync")
                        .propagateMeta(false)
                        .stdioTransport("echo", "hello")
                        .buildSync();
        assertNotNull(syncWrapper);
        assertFalse(syncWrapper.isPropagateMeta());
    }

    @Test
    void testBuildAsync_WithStdioTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("stdio-client")
                        .stdioTransport("python", "-m", "mcp_server_time")
                        .timeout(Duration.ofSeconds(60))
                        .initializationTimeout(Duration.ofSeconds(30));

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
        assertTrue(wrapper instanceof McpAsyncClientWrapper);
    }

    @Test
    void testBuildAsync_WithSseTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("sse-client")
                        .sseTransport("https://mcp.example.com/sse")
                        .header("Authorization", "Bearer token")
                        .timeout(Duration.ofSeconds(120));

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
        assertEquals("sse-client", wrapper.getName());
        assertTrue(wrapper instanceof McpAsyncClientWrapper);
    }

    @Test
    void testBuildAsync_WithStreamableHttpTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("http-client")
                        .streamableHttpTransport("https://mcp.example.com/http")
                        .header("X-API-Key", "key123")
                        .timeout(Duration.ofSeconds(90))
                        .initializationTimeout(Duration.ofSeconds(20));

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
        assertTrue(wrapper instanceof McpAsyncClientWrapper);
    }

    @Test
    void testBuildSync_WithStdioTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("stdio-sync-client")
                        .stdioTransport("node", "server.js")
                        .timeout(Duration.ofSeconds(45));

        McpClientWrapper wrapper = builder.buildSync();

        assertNotNull(wrapper);
        assertTrue(wrapper instanceof McpSyncClientWrapper);
        assertEquals("stdio-sync-client", wrapper.getName());
    }

    @Test
    void testBuildSync_WithSseTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("sse-sync-client")
                        .sseTransport("https://mcp.example.com/sse");

        McpClientWrapper wrapper = builder.buildSync();

        assertNotNull(wrapper);
        assertTrue(wrapper instanceof McpSyncClientWrapper);
    }

    @Test
    void testBuildSync_WithStreamableHttpTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("http-sync-client")
                        .streamableHttpTransport("https://mcp.example.com/http");

        McpClientWrapper wrapper = builder.buildSync();

        assertNotNull(wrapper);
        assertTrue(wrapper instanceof McpSyncClientWrapper);
    }

    @Test
    void testMultipleBuilds_FromSameBuilder() {
        McpClientBuilder builder =
                McpClientBuilder.create("multi-client").stdioTransport("echo", "test");

        // 构建异步客户端
        McpClientWrapper asyncWrapper = builder.buildAsync().block();
        assertNotNull(asyncWrapper);

        // 复用同一构建器构建同步客户端
        McpClientWrapper syncWrapper = builder.buildSync();
        assertNotNull(syncWrapper);
    }

    @Test
    void testStdioTransport_EmptyArgs() {
        McpClientBuilder builder = McpClientBuilder.create("client").stdioTransport("command");

        assertNotNull(builder);
    }

    @Test
    void testStdioTransport_WithEmptyEnv() {
        McpClientBuilder builder =
                McpClientBuilder.create("client")
                        .stdioTransport("python", List.of("-m", "server"), new HashMap<>());

        assertNotNull(builder);
    }

    @Test
    void testHeaders_OnStdioTransport() {
        Map<String, String> headers = new HashMap<>();
        headers.put("key", "value");

        McpClientBuilder builder =
                McpClientBuilder.create("client")
                        .stdioTransport("python", "server.py")
                        .headers(headers);

        // stdio 传输下不应抛异常，仅忽略
        assertNotNull(builder);
    }

    @Test
    void testHeaders_OverwritePreviousHeaders() {
        Map<String, String> headers1 = new HashMap<>();
        headers1.put("key1", "value1");

        Map<String, String> headers2 = new HashMap<>();
        headers2.put("key2", "value2");

        McpClientBuilder builder =
                McpClientBuilder.create("client")
                        .sseTransport("https://example.com")
                        .headers(headers1)
                        .headers(headers2); // 应覆盖

        assertNotNull(builder);
    }

    @Test
    void testHeader_AddMultipleTimes() {
        McpClientBuilder builder =
                McpClientBuilder.create("client")
                        .sseTransport("https://example.com")
                        .header("key1", "value1")
                        .header("key2", "value2")
                        .header("key3", "value3");

        assertNotNull(builder);
    }

    @Test
    void testComplexConfiguration() {
        Map<String, String> env = new HashMap<>();
        env.put("DEBUG", "true");

        McpClientBuilder builder =
                McpClientBuilder.create("complex-client")
                        .stdioTransport(
                                "uvx", List.of("mcp-server-time", "--local-timezone=UTC"), env)
                        .timeout(Duration.ofSeconds(120))
                        .initializationTimeout(Duration.ofSeconds(45));

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
        assertEquals("complex-client", wrapper.getName());
    }

    @Test
    void testSseTransport_WithCompleteConfiguration() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer token");
        headers.put("X-Client-Version", Version.VERSION);

        McpClientBuilder builder =
                McpClientBuilder.create("full-sse-client")
                        .sseTransport("https://mcp.higress.ai/sse")
                        .headers(headers)
                        .timeout(Duration.ofSeconds(90))
                        .initializationTimeout(Duration.ofSeconds(30));

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
        assertEquals("full-sse-client", wrapper.getName());
    }

    @Test
    void testStreamableHttpTransport_WithCompleteConfiguration() {
        McpClientBuilder builder =
                McpClientBuilder.create("full-http-client")
                        .streamableHttpTransport("https://mcp.higress.ai/http")
                        .header("X-API-Key", "secret-key")
                        .header("X-Request-ID", "request-123")
                        .timeout(Duration.ofMinutes(2))
                        .initializationTimeout(Duration.ofSeconds(45));

        McpClientWrapper wrapper = builder.buildSync();

        assertNotNull(wrapper);
        assertEquals("full-http-client", wrapper.getName());
        assertFalse(wrapper.isInitialized());
    }

    @Test
    void testDefaultTimeouts() {
        // 测试未显式设置超时时，构建器使用默认值正常工作
        McpClientBuilder builder =
                McpClientBuilder.create("default-timeouts").stdioTransport("echo", "test");

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
    }

    @Test
    void testBuildAsync_CreatesNewInstanceEachTime() {
        McpClientBuilder builder =
                McpClientBuilder.create("multi-instance").stdioTransport("echo", "test");

        McpClientWrapper wrapper1 = builder.buildAsync().block();
        McpClientWrapper wrapper2 = builder.buildAsync().block();

        assertNotNull(wrapper1);
        assertNotNull(wrapper2);
        // 每次构建应创建新实例
        assertTrue(wrapper1 != wrapper2);
    }

    @Test
    void testBuildSync_CreatesNewInstanceEachTime() {
        McpClientBuilder builder =
                McpClientBuilder.create("multi-instance-sync").stdioTransport("echo", "test");

        McpClientWrapper wrapper1 = builder.buildSync();
        McpClientWrapper wrapper2 = builder.buildSync();

        assertNotNull(wrapper1);
        assertNotNull(wrapper2);
        // 每次构建应创建新实例
        assertTrue(wrapper1 != wrapper2);
    }

    // ==================== extractEndpoint 测试 ====================

    /**
     * 通过反射调用 HttpTransportConfig 的 extractEndpoint 方法。
     * 使用指定 URL 和查询参数创建构建器，再提取端点。
     */
    private String invokeExtractEndpoint(String url, Map<String, String> queryParams)
            throws Exception {
        McpClientBuilder builder = McpClientBuilder.create("test").sseTransport(url);

        if (queryParams != null) {
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                builder.queryParam(entry.getKey(), entry.getValue());
            }
        }

        Field transportConfigField = McpClientBuilder.class.getDeclaredField("transportConfig");
        transportConfigField.setAccessible(true);
        Object transportConfig = transportConfigField.get(builder);

        Method method =
                transportConfig.getClass().getSuperclass().getDeclaredMethod("extractEndpoint");
        method.setAccessible(true);
        return (String) method.invoke(transportConfig);
    }

    private String invokeExtractEndpoint(String url) throws Exception {
        return invokeExtractEndpoint(url, null);
    }

    @Test
    void testExtractEndpoint_WithSingleQueryParameter() throws Exception {
        String url = "https://mcp.example.com/sse?token=abc123";
        String endpoint = invokeExtractEndpoint(url);
        assertEquals("/sse?token=abc123", endpoint);
    }

    @Test
    void testExtractEndpoint_WithMultipleQueryParameters() throws Exception {
        String url = "https://mcp.example.com/sse?token=abc123&user=test&version=1.0";
        String endpoint = invokeExtractEndpoint(url);
        // 注意：HashMap 可能导致参数顺序变化
        assertTrue(endpoint.startsWith("/sse?"));
        assertTrue(endpoint.contains("token=abc123"));
        assertTrue(endpoint.contains("user=test"));
        assertTrue(endpoint.contains("version=1.0"));
    }

    @Test
    void testExtractEndpoint_WithoutQueryParameters() throws Exception {
        String url = "https://mcp.example.com/sse";
        String endpoint = invokeExtractEndpoint(url);
        assertEquals("/sse", endpoint);
    }

    @Test
    void testExtractEndpoint_WithRootPath() throws Exception {
        String url = "https://mcp.example.com/?key=value";
        String endpoint = invokeExtractEndpoint(url);
        assertEquals("/?key=value", endpoint);
    }

    @Test
    void testExtractEndpoint_WithEncodedQueryParameters() throws Exception {
        // 新实现对查询参数进行 URL 编码
        String url = "https://mcp.example.com/api?name=John%20Doe&email=test%40example.com";
        String endpoint = invokeExtractEndpoint(url);
        // 结果中的参数经过 URL 编码
        assertTrue(endpoint.startsWith("/api?"));
        assertTrue(endpoint.contains("name=John") || endpoint.contains("name=John+Doe"));
        assertTrue(endpoint.contains("email=test%40example.com"));
    }

    @Test
    void testExtractEndpoint_WithComplexPath() throws Exception {
        String url = "https://mcp.example.com/api/v1/sse?token=secret";
        String endpoint = invokeExtractEndpoint(url);
        assertEquals("/api/v1/sse?token=secret", endpoint);
    }

    @Test
    void testExtractEndpoint_WithFragment_ShouldIgnore() throws Exception {
        // 片段 #section 不应包含在端点中
        String url = "https://mcp.example.com/sse?token=abc#section";
        String endpoint = invokeExtractEndpoint(url);
        assertEquals("/sse?token=abc", endpoint);
    }

    // ==================== 查询参数 API 测试 ====================

    @Test
    void testQueryParam_OnHttpTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("http-client")
                        .sseTransport("https://mcp.example.com/sse")
                        .queryParam("token", "abc123")
                        .queryParam("tenant", "my-tenant");

        assertNotNull(builder);
    }

    @Test
    void testQueryParam_OnStdioTransport() {
        // 为 stdio 传输添加查询参数不应出错，仅忽略
        McpClientBuilder builder =
                McpClientBuilder.create("stdio-client")
                        .stdioTransport("python", "-m", "mcp_server_time")
                        .queryParam("token", "abc123");

        assertNotNull(builder);
    }

    @Test
    void testQueryParams_MultipleParams() {
        Map<String, String> params = new HashMap<>();
        params.put("token", "abc123");
        params.put("tenant", "my-tenant");
        params.put("version", "v1");

        McpClientBuilder builder =
                McpClientBuilder.create("http-client")
                        .sseTransport("https://mcp.example.com/sse")
                        .queryParams(params);

        assertNotNull(builder);
    }

    @Test
    void testQueryParams_OverwritePreviousParams() {
        Map<String, String> params1 = new HashMap<>();
        params1.put("key1", "value1");

        Map<String, String> params2 = new HashMap<>();
        params2.put("key2", "value2");

        McpClientBuilder builder =
                McpClientBuilder.create("client")
                        .sseTransport("https://example.com")
                        .queryParams(params1)
                        .queryParams(params2); // 应覆盖

        assertNotNull(builder);
    }

    @Test
    void testQueryParam_AddMultipleTimes() {
        McpClientBuilder builder =
                McpClientBuilder.create("client")
                        .sseTransport("https://example.com")
                        .queryParam("key1", "value1")
                        .queryParam("key2", "value2")
                        .queryParam("key3", "value3");

        assertNotNull(builder);
    }

    @Test
    void testQueryParam_WithHeadersCombined() {
        McpClientBuilder builder =
                McpClientBuilder.create("client")
                        .sseTransport("https://mcp.example.com/sse")
                        .header("Authorization", "Bearer token")
                        .queryParam("tenant", "my-tenant")
                        .header("X-Client-Version", "1.0")
                        .queryParam("version", "v1");

        assertNotNull(builder);
    }

    @Test
    void testQueryParams_OnStdioTransport() {
        Map<String, String> params = new HashMap<>();
        params.put("key", "value");

        McpClientBuilder builder =
                McpClientBuilder.create("client")
                        .stdioTransport("python", "server.py")
                        .queryParams(params);

        // stdio 传输下不应抛异常，仅忽略
        assertNotNull(builder);
    }

    @Test
    void testBuildAsync_WithQueryParams() {
        McpClientBuilder builder =
                McpClientBuilder.create("query-client")
                        .sseTransport("https://mcp.example.com/sse")
                        .queryParam("token", "secret123")
                        .queryParam("env", "production");

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
        assertEquals("query-client", wrapper.getName());
    }

    @Test
    void testBuildSync_WithQueryParams() {
        McpClientBuilder builder =
                McpClientBuilder.create("query-sync-client")
                        .streamableHttpTransport("https://mcp.example.com/http")
                        .queryParams(Map.of("token", "abc", "user", "test"));

        McpClientWrapper wrapper = builder.buildSync();
        assertNotNull(wrapper);
        assertEquals("query-sync-client", wrapper.getName());
    }

    @Test
    void testFluentApi_WithQueryParamsAndHeaders() {
        McpClientBuilder builder =
                McpClientBuilder.create("full-client")
                        .sseTransport("https://mcp.example.com/sse?existing=param")
                        .header("Authorization", "Bearer token")
                        .queryParam("token", "abc123")
                        .queryParam("tenant", "my-tenant")
                        .timeout(Duration.ofSeconds(60))
                        .initializationTimeout(Duration.ofSeconds(30));

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
    }

    // ==================== 带查询参数的 extractEndpoint 测试
    // ====================

    @Test
    void testExtractEndpoint_NoAdditionalParams() throws Exception {
        String endpoint = invokeExtractEndpoint("https://example.com/sse", new HashMap<>());
        assertEquals("/sse", endpoint);
    }

    @Test
    void testExtractEndpoint_OnlyAdditionalParams() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("token", "abc123");

        String endpoint = invokeExtractEndpoint("https://example.com/sse", params);
        assertEquals("/sse?token=abc123", endpoint);
    }

    @Test
    void testExtractEndpoint_MergeWithUrlParams() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("newParam", "newValue");

        String endpoint =
                invokeExtractEndpoint(
                        "https://example.com/sse?existingParam=existingValue", params);

        // 应包含两个参数
        assertTrue(endpoint.startsWith("/sse?"));
        assertTrue(endpoint.contains("existingParam=existingValue"));
        assertTrue(endpoint.contains("newParam=newValue"));
    }

    @Test
    void testExtractEndpoint_AdditionalParamOverridesUrlParam() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("token", "newToken");

        String endpoint = invokeExtractEndpoint("https://example.com/sse?token=oldToken", params);

        // 额外参数应覆盖
        assertTrue(endpoint.contains("token=newToken"));
        assertFalse(endpoint.contains("oldToken"));
    }

    @Test
    void testExtractEndpoint_MultipleAdditionalParams() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("token", "abc");
        params.put("tenant", "xyz");

        String endpoint = invokeExtractEndpoint("https://example.com/api/sse", params);

        assertTrue(endpoint.startsWith("/api/sse?"));
        assertTrue(endpoint.contains("token=abc"));
        assertTrue(endpoint.contains("tenant=xyz"));
    }

    @Test
    void testExtractEndpoint_UrlEncodesSpecialChars() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("name", "John Doe");
        params.put("email", "test@example.com");

        String endpoint = invokeExtractEndpoint("https://example.com/sse", params);

        // 应进行 URL 编码
        assertTrue(endpoint.contains("name=John+Doe") || endpoint.contains("name=John%20Doe"));
        assertTrue(endpoint.contains("email=test%40example.com"));
    }

    @Test
    void testExtractEndpoint_ComplexPathWithMergedParams() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("version", "v1");

        String endpoint =
                invokeExtractEndpoint("https://example.com/api/v2/mcp/sse?token=secret", params);

        assertTrue(endpoint.startsWith("/api/v2/mcp/sse?"));
        assertTrue(endpoint.contains("token=secret"));
        assertTrue(endpoint.contains("version=v1"));
    }

    // ==================== null 验证测试 ====================

    @Test
    void testQueryParam_WithNullKey() {
        McpClientBuilder builder =
                McpClientBuilder.create("client").sseTransport("https://example.com/sse");

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class, () -> builder.queryParam(null, "value"));

        assertEquals("Query parameter key cannot be null", exception.getMessage());
    }

    @Test
    void testQueryParam_WithNullValue() {
        McpClientBuilder builder =
                McpClientBuilder.create("client").sseTransport("https://example.com/sse");

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> builder.queryParam("key", null));

        assertEquals("Query parameter value cannot be null", exception.getMessage());
    }

    @Test
    void testQueryParam_WithBothNull() {
        McpClientBuilder builder =
                McpClientBuilder.create("client").sseTransport("https://example.com/sse");

        // 应首先因 null 键抛异常
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> builder.queryParam(null, null));

        assertEquals("Query parameter key cannot be null", exception.getMessage());
    }

    @Test
    void testQueryParams_WithNullMap() {
        McpClientBuilder builder =
                McpClientBuilder.create("client").sseTransport("https://example.com/sse");

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> builder.queryParams(null));

        assertEquals("Query parameters map cannot be null", exception.getMessage());
    }

    @Test
    void testHeader_WithNullKey() {
        // 允许 null 请求头键，原样存入映射
        McpClientBuilder builder =
                McpClientBuilder.create("client").sseTransport("https://example.com/sse");

        // 不应抛异常，接受 null 键
        assertNotNull(builder.header(null, "value"));
    }

    @Test
    void testQueryParam_NullOnStdioTransport_ShouldNotThrow() {
        // StdIO 传输忽略查询参数，因此 null 不应抛异常
        McpClientBuilder builder =
                McpClientBuilder.create("client").stdioTransport("python", "server.py");

        // 不应抛异常，因为方法直接返回，不调用
        // addQueryParam
        assertNotNull(builder.queryParam(null, "value"));
        assertNotNull(builder.queryParam("key", null));
    }

    @Test
    void testQueryParams_NullOnStdioTransport_ShouldNotThrow() {
        // StdIO 传输忽略查询参数，因此 null 不应抛异常
        McpClientBuilder builder =
                McpClientBuilder.create("client").stdioTransport("python", "server.py");

        // 不应抛异常，因为方法直接返回，不调用
        // 设置查询参数：setQueryParams
        assertNotNull(builder.queryParams(null));
    }

    // ==================== extractEndpoint 边界测试 ====================

    @Test
    void testExtractEndpoint_WithEmptyPath_ShouldDefaultToSlash() throws Exception {
        // 无路径 URL 应默认为 "/"
        String url = "https://example.com?token=abc";
        String endpoint = invokeExtractEndpoint(url);
        assertTrue(endpoint.startsWith("/?") || endpoint.equals("/"));
        assertTrue(endpoint.contains("token=abc"));
    }

    @Test
    void testExtractEndpoint_WithOnlyHost_ShouldDefaultToSlash() throws Exception {
        // URL 仅含主机，无路径和查询
        String url = "https://example.com";
        String endpoint = invokeExtractEndpoint(url);
        assertEquals("/", endpoint);
    }

    @Test
    void testExtractEndpoint_WithOnlyHostAndQueryParams() throws Exception {
        // URL 含主机及额外查询参数，但无路径
        Map<String, String> params = new HashMap<>();
        params.put("token", "secret");

        String endpoint = invokeExtractEndpoint("https://example.com", params);
        assertEquals("/?token=secret", endpoint);
    }

    @Test
    void testExtractEndpoint_InvalidUrl_ShouldThrowIllegalArgumentException() {
        // 无效 URL 应抛 IllegalArgumentException
        McpClientBuilder builder = McpClientBuilder.create("test").sseTransport("not a valid url");

        Exception exception =
                assertThrows(
                        Exception.class,
                        () -> {
                            Field transportConfigField =
                                    McpClientBuilder.class.getDeclaredField("transportConfig");
                            transportConfigField.setAccessible(true);
                            Object transportConfig = transportConfigField.get(builder);

                            Method method =
                                    transportConfig
                                            .getClass()
                                            .getSuperclass()
                                            .getDeclaredMethod("extractEndpoint");
                            method.setAccessible(true);
                            method.invoke(transportConfig);
                        });

        // 原因应为 IllegalArgumentException
        Throwable cause = exception.getCause();
        assertTrue(
                cause instanceof IllegalArgumentException,
                "Expected IllegalArgumentException but got: " + cause.getClass().getName());
        assertTrue(cause.getMessage().contains("Invalid URL format"));
    }

    @Test
    void testExtractEndpoint_WithEmptyQueryValue() throws Exception {
        // URL 参数的值为空：?key=
        String url = "https://example.com/sse?emptyKey=";
        String endpoint = invokeExtractEndpoint(url);
        assertTrue(endpoint.contains("emptyKey="));
    }

    @Test
    void testExtractEndpoint_WithParameterWithoutValue() throws Exception {
        // URL 参数完全无值：?flag
        String url = "https://example.com/sse?flag";
        String endpoint = invokeExtractEndpoint(url);
        assertTrue(endpoint.contains("flag="));
    }

    @Test
    void testExtractEndpoint_WithConsecutiveAmpersands() throws Exception {
        // URL 连续 && 应跳过空参数
        String url = "https://example.com/sse?key1=value1&&key2=value2";
        String endpoint = invokeExtractEndpoint(url);
        assertTrue(endpoint.contains("key1=value1"));
        assertTrue(endpoint.contains("key2=value2"));
    }

    @Test
    void testExtractEndpoint_WithSpecialCharactersInValue() throws Exception {
        // URL 含需编码的特殊字符
        Map<String, String> params = new HashMap<>();
        params.put("message", "Hello World!");
        params.put("symbol", "@#$%");

        String endpoint = invokeExtractEndpoint("https://example.com/api", params);
        assertTrue(endpoint.startsWith("/api?"));
        // 值应进行 URL 编码
        assertTrue(
                endpoint.contains("message=Hello+World%21")
                        || endpoint.contains("message=Hello%20World%21"));
    }

    @Test
    void testExtractEndpoint_WithChineseCharacters() throws Exception {
        // URL 含中文字符
        Map<String, String> params = new HashMap<>();
        params.put("name", "测试");

        String endpoint = invokeExtractEndpoint("https://example.com/api", params);
        assertTrue(endpoint.startsWith("/api?"));
        // 中文字符应进行 URL 编码
        assertTrue(endpoint.contains("name=%E6%B5%8B%E8%AF%95"));
    }

    @Test
    void testExtractEndpoint_PreservesPathStructure() throws Exception {
        // 应保留复杂嵌套路径
        String url = "https://example.com/api/v1/mcp/sse/endpoint";
        String endpoint = invokeExtractEndpoint(url);
        assertEquals("/api/v1/mcp/sse/endpoint", endpoint);
    }

    @Test
    void testExtractEndpoint_WithTrailingSlash() throws Exception {
        // 应保留路径末尾斜杠
        String url = "https://example.com/api/";
        String endpoint = invokeExtractEndpoint(url);
        assertEquals("/api/", endpoint);
    }

    // ==================== HTTP 客户端定制测试 ====================

    @Test
    void testCustomizeSseClient_WithValidCustomizer() {
        McpClientBuilder builder =
                McpClientBuilder.create("custom-sse-client")
                        .sseTransport("https://mcp.example.com/sse")
                        .customizeSseClient(
                                clientBuilder -> {
                                    // 定制 HTTP 客户端，例如 HTTP/2、超时等
                                    clientBuilder.connectTimeout(Duration.ofSeconds(10));
                                });

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
        assertEquals("custom-sse-client", wrapper.getName());
    }

    @Test
    void testCustomizeSseClient_MultipleCustomizations() {
        McpClientBuilder builder =
                McpClientBuilder.create("multi-custom-sse")
                        .sseTransport("https://mcp.example.com/sse")
                        .customizeSseClient(
                                clientBuilder -> {
                                    clientBuilder.connectTimeout(Duration.ofSeconds(10));
                                })
                        .customizeSseClient(
                                clientBuilder -> {
                                    // 第二次定制也应应用
                                    clientBuilder.followRedirects(HttpClient.Redirect.NORMAL);
                                })
                        .header("Authorization", "Bearer token")
                        .queryParam("tenant", "test");

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
    }

    @Test
    void testCustomizeSseClient_OnStdioTransport_ShouldBeIgnored() {
        // 在 stdio 传输上定制 SSE 客户端不应出错，
        // 仅忽略
        McpClientBuilder builder =
                McpClientBuilder.create("stdio-client")
                        .stdioTransport("python", "-m", "mcp_server_time")
                        .customizeSseClient(
                                clientBuilder -> {
                                    clientBuilder.connectTimeout(Duration.ofSeconds(10));
                                });

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
    }

    @Test
    void testCustomizeSseClient_OnStreamableHttpTransport_ShouldBeIgnored() {
        // 在 streamable http 传输上定制 SSE 客户端不应出错
        // 而只是
        // 仅忽略
        McpClientBuilder builder =
                McpClientBuilder.create("http-client")
                        .streamableHttpTransport("https://mcp.example.com/http")
                        .customizeSseClient(
                                clientBuilder -> {
                                    clientBuilder.connectTimeout(Duration.ofSeconds(10));
                                });

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
    }

    @Test
    void testCustomizeStreamableHttpClient_WithValidCustomizer() {
        McpClientBuilder builder =
                McpClientBuilder.create("custom-http-client")
                        .streamableHttpTransport("https://mcp.example.com/http")
                        .customizeStreamableHttpClient(
                                clientBuilder -> {
                                    // 定制 HTTP 客户端
                                    clientBuilder.connectTimeout(Duration.ofSeconds(15));
                                });

        McpClientWrapper wrapper = builder.buildSync();
        assertNotNull(wrapper);
        assertEquals("custom-http-client", wrapper.getName());
    }

    @Test
    void testCustomizeStreamableHttpClient_MultipleCustomizations() {
        McpClientBuilder builder =
                McpClientBuilder.create("multi-custom-http")
                        .streamableHttpTransport("https://mcp.example.com/http")
                        .customizeStreamableHttpClient(
                                clientBuilder -> {
                                    clientBuilder.connectTimeout(Duration.ofSeconds(10));
                                })
                        .customizeStreamableHttpClient(
                                clientBuilder -> {
                                    clientBuilder.followRedirects(HttpClient.Redirect.ALWAYS);
                                })
                        .header("X-API-Key", "secret")
                        .queryParam("version", "v1");

        McpClientWrapper wrapper = builder.buildSync();
        assertNotNull(wrapper);
    }

    @Test
    void testCustomizeStreamableHttpClient_OnStdioTransport_ShouldBeIgnored() {
        // 在 stdio 传输上定制 streamable http 客户端不应出错
        // 而只是
        // 仅忽略
        McpClientBuilder builder =
                McpClientBuilder.create("stdio-client")
                        .stdioTransport("python", "server.py")
                        .customizeStreamableHttpClient(
                                clientBuilder -> {
                                    clientBuilder.connectTimeout(Duration.ofSeconds(10));
                                });

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
    }

    @Test
    void testCustomizeStreamableHttpClient_OnSseTransport_ShouldBeIgnored() {
        // 在 SSE 传输上定制 streamable http 客户端不应出错
        // 而只是
        // 仅忽略
        McpClientBuilder builder =
                McpClientBuilder.create("sse-client")
                        .sseTransport("https://mcp.example.com/sse")
                        .customizeStreamableHttpClient(
                                clientBuilder -> {
                                    clientBuilder.connectTimeout(Duration.ofSeconds(10));
                                });

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
    }

    @Test
    void testCustomizeSseClient_WithHttp2() {
        McpClientBuilder builder =
                McpClientBuilder.create("http2-sse-client")
                        .sseTransport("https://mcp.example.com/sse")
                        .customizeSseClient(
                                clientBuilder -> {
                                    clientBuilder.version(HttpClient.Version.HTTP_2);
                                });

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
    }

    @Test
    void testCustomizeStreamableHttpClient_WithHttp2() {
        McpClientBuilder builder =
                McpClientBuilder.create("http2-streamable-client")
                        .streamableHttpTransport("https://mcp.example.com/http")
                        .customizeStreamableHttpClient(
                                clientBuilder -> {
                                    clientBuilder.version(HttpClient.Version.HTTP_2);
                                });

        McpClientWrapper wrapper = builder.buildSync();
        assertNotNull(wrapper);
    }

    @Test
    void testCompleteConfiguration_WithClientCustomization() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer token123");
        headers.put("X-Client-Version", Version.VERSION);

        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("tenant", "acme");
        queryParams.put("env", "production");

        McpClientBuilder builder =
                McpClientBuilder.create("fully-configured-client")
                        .sseTransport("https://mcp.example.com/api/sse")
                        .customizeSseClient(
                                clientBuilder -> {
                                    clientBuilder
                                            .version(HttpClient.Version.HTTP_2)
                                            .connectTimeout(Duration.ofSeconds(10))
                                            .followRedirects(HttpClient.Redirect.NORMAL);
                                })
                        .headers(headers)
                        .queryParams(queryParams)
                        .timeout(Duration.ofSeconds(120))
                        .initializationTimeout(Duration.ofSeconds(45));

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
        assertEquals("fully-configured-client", wrapper.getName());
    }

    @Test
    void testFluentApi_AllFeaturesCombined() {
        McpClientBuilder builder =
                McpClientBuilder.create("all-features-client")
                        .streamableHttpTransport("https://mcp.higress.ai/mcp/http?base=param")
                        .customizeStreamableHttpClient(
                                clientBuilder -> {
                                    clientBuilder
                                            .connectTimeout(Duration.ofSeconds(15))
                                            .version(HttpClient.Version.HTTP_1_1);
                                })
                        .header("Authorization", "Bearer secret-token")
                        .header("X-Request-ID", "req-12345")
                        .queryParam("tenant", "my-tenant")
                        .queryParam("version", "v2")
                        .timeout(Duration.ofMinutes(3))
                        .initializationTimeout(Duration.ofSeconds(60));

        McpClientWrapper wrapper = builder.buildSync();
        assertNotNull(wrapper);
        assertEquals("all-features-client", wrapper.getName());
        assertFalse(wrapper.isInitialized());
    }

    // ==================== Elicitation 信息征询测试 ====================

    @Test
    void testAsyncElicitation_WithHandler() {
        // 测试可调用 asyncElicitation 并返回构建器
        McpClientBuilder builder =
                McpClientBuilder.create("async-elicit-client")
                        .stdioTransport("echo", "test")
                        .asyncElicitation(request -> reactor.core.publisher.Mono.empty());

        assertNotNull(builder);
        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
        assertTrue(wrapper instanceof McpAsyncClientWrapper);
    }

    @Test
    void testSyncElicitation_WithHandler() {
        // 测试可调用 syncElicitation 并返回构建器
        McpClientBuilder builder =
                McpClientBuilder.create("sync-elicit-client")
                        .stdioTransport("echo", "test")
                        .syncElicitation(request -> null);

        assertNotNull(builder);
        McpClientWrapper wrapper = builder.buildSync();
        assertNotNull(wrapper);
        assertTrue(wrapper instanceof McpSyncClientWrapper);
    }

    @Test
    void testAsyncElicitation_WithoutHandler() {
        // 测试不带征询处理器也可正常构建
        McpClientBuilder builder =
                McpClientBuilder.create("no-elicit-async").stdioTransport("echo", "test");

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
        assertTrue(wrapper instanceof McpAsyncClientWrapper);
    }

    @Test
    void testSyncElicitation_WithoutHandler() {
        // 测试不带征询处理器也可正常构建
        McpClientBuilder builder =
                McpClientBuilder.create("no-elicit-sync").stdioTransport("echo", "test");

        McpClientWrapper wrapper = builder.buildSync();
        assertNotNull(wrapper);
        assertTrue(wrapper instanceof McpSyncClientWrapper);
    }

    @Test
    void testAsyncElicitation_FluentApi() {
        // 测试带 asyncElicitation 的流式 API
        McpClientBuilder builder =
                McpClientBuilder.create("fluent-async-elicit")
                        .sseTransport("https://mcp.example.com/sse")
                        .header("Authorization", "Bearer token")
                        .asyncElicitation(request -> reactor.core.publisher.Mono.empty())
                        .timeout(Duration.ofSeconds(60));

        assertNotNull(builder);
        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
    }

    @Test
    void testSyncElicitation_FluentApi() {
        // 测试带 syncElicitation 的流式 API
        McpClientBuilder builder =
                McpClientBuilder.create("fluent-sync-elicit")
                        .streamableHttpTransport("https://mcp.example.com/http")
                        .queryParam("token", "abc123")
                        .syncElicitation(request -> null)
                        .timeout(Duration.ofSeconds(90));

        assertNotNull(builder);
        McpClientWrapper wrapper = builder.buildSync();
        assertNotNull(wrapper);
    }

    @Test
    void testElicitation_BothHandlersSet() {
        // 测试同时设置两个处理器不会出问题
        // 根据构建方法仅使用对应处理器
        McpClientBuilder builder =
                McpClientBuilder.create("both-handlers")
                        .stdioTransport("echo", "test")
                        .asyncElicitation(request -> reactor.core.publisher.Mono.empty())
                        .syncElicitation(request -> null);

        // 构建异步客户端：应使用异步处理器
        McpClientWrapper asyncWrapper = builder.buildAsync().block();
        assertNotNull(asyncWrapper);
        assertTrue(asyncWrapper instanceof McpAsyncClientWrapper);

        // 构建同步客户端，应使用同步处理器
        McpClientWrapper syncWrapper = builder.buildSync();
        assertNotNull(syncWrapper);
        assertTrue(syncWrapper instanceof McpSyncClientWrapper);
    }

    // ==================== HttpRequestCustomizer 测试 ====================

    @Test
    void testHttpRequestCustomizer_OnSseTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("http-customizer-sse")
                        .sseTransport("https://mcp.example.com/sse")
                        .httpRequestCustomizer(
                                (builder1, method, uri, body, ctx) -> {
                                    builder1.header("Authorization", "Bearer dynamic-token");
                                });

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
        assertEquals("http-customizer-sse", wrapper.getName());
    }

    @Test
    void testHttpRequestCustomizer_OnStreamableHttpTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("http-customizer-streamable")
                        .streamableHttpTransport("https://mcp.example.com/http")
                        .httpRequestCustomizer(
                                (builder1, method, uri, body, ctx) -> {
                                    builder1.header("X-Custom", "custom-value");
                                    builder1.header("Authorization", "Bearer token");
                                });

        McpClientWrapper wrapper = builder.buildSync();
        assertNotNull(wrapper);
        assertEquals("http-customizer-streamable", wrapper.getName());
    }

    @Test
    void testHttpRequestCustomizer_OnStdioTransport_ShouldBeIgnored() {
        // stdio 传输使用 httpRequestCustomizer 不应出错，仅忽略
        McpClientBuilder builder =
                McpClientBuilder.create("customizer-stdio")
                        .stdioTransport("python", "-m", "mcp_server_time")
                        .httpRequestCustomizer(
                                (builder1, method, uri, body, ctx) -> {
                                    builder1.header("Authorization", "Bearer token");
                                });

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
    }

    @Test
    void testHttpRequestCustomizer_CombinedWithHeaders() {
        McpClientBuilder builder =
                McpClientBuilder.create("customizer-combined")
                        .sseTransport("https://mcp.example.com/sse")
                        .header("X-Static-Header", "static-value")
                        .httpRequestCustomizer(
                                (builder1, method, uri, body, ctx) -> {
                                    builder1.header("X-Dynamic-Header", "dynamic-value");
                                });

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
        assertEquals("customizer-combined", wrapper.getName());
    }

    @Test
    void testHttpRequestCustomizer_CombinedWithAllFeatures() {
        McpClientBuilder builder =
                McpClientBuilder.create("customizer-all-features")
                        .streamableHttpTransport("https://mcp.example.com/http")
                        .header("X-Static", "static-value")
                        .queryParam("env", "prod")
                        .httpRequestCustomizer(
                                (builder1, method, uri, body, ctx) -> {
                                    builder1.header("Authorization", "Bearer dynamic");
                                    builder1.header("X-Trace-Id", "trace-123");
                                })
                        .timeout(Duration.ofSeconds(60));

        McpClientWrapper wrapper = builder.buildSync();
        assertNotNull(wrapper);
        assertEquals("customizer-all-features", wrapper.getName());
    }

    @Test
    void testHttpRequestCustomizer_FluentApiWithBothTransports() {
        // 测试 SSE 的 httpRequestCustomizer 配合 buildAsync 正常工作
        McpClientBuilder builder =
                McpClientBuilder.create("fluent-customizer")
                        .sseTransport("https://mcp.example.com/sse")
                        .httpRequestCustomizer(
                                (builder1, method, uri, body, ctx) -> {
                                    // 空操作定制器，仅验证构建器正常工作
                                });

        assertNotNull(builder);
        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
    }

    @Test
    void testHttpRequestCustomizer_MultipleCalls_LastOneWins() {
        // 多次调用应覆盖前一个定制器
        McpClientBuilder builder =
                McpClientBuilder.create("multi-customizer")
                        .sseTransport("https://mcp.example.com/sse")
                        .httpRequestCustomizer(
                                (builder1, method, uri, body, ctx) -> {
                                    builder1.header("First", "first-value");
                                })
                        .httpRequestCustomizer(
                                (builder1, method, uri, body, ctx) -> {
                                    builder1.header("Second", "second-value");
                                });

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
    }

    @Test
    void testHttpRequestCustomizer_WithNullCustomizer() {
        // 应允许 null 定制器，表示没有定制器
        McpClientBuilder builder =
                McpClientBuilder.create("null-customizer")
                        .sseTransport("https://mcp.example.com/sse")
                        .httpRequestCustomizer(null);

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
    }

    // ==================== 协议版本测试 ====================

    @Test
    void testProtocolVersions_SingleVersion() {
        McpClientBuilder builder =
                McpClientBuilder.create("pv-client")
                        .stdioTransport("echo", "test")
                        .protocolVersions("2025-03-26");

        assertNotNull(builder);
    }

    @Test
    void testProtocolVersions_MultipleVersions() {
        McpClientBuilder builder =
                McpClientBuilder.create("pv-client")
                        .stdioTransport("echo", "test")
                        .protocolVersions("2024-11-05", "2025-03-26", "2025-06-18");

        assertNotNull(builder);
    }

    @Test
    void testProtocolVersions_NullThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        McpClientBuilder.create("pv-client")
                                .stdioTransport("echo", "test")
                                .protocolVersions((String[]) null));
    }

    @Test
    void testProtocolVersions_EmptyThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        McpClientBuilder.create("pv-client")
                                .stdioTransport("echo", "test")
                                .protocolVersions());
    }

    @Test
    void testProtocolVersions_BuildAsyncWithVersions() {
        McpClientBuilder builder =
                McpClientBuilder.create("pv-async")
                        .stdioTransport("echo", "test")
                        .protocolVersions("2024-11-05", "2025-03-26");

        McpClientWrapper wrapper = builder.buildAsync().block();
        assertNotNull(wrapper);
        assertTrue(wrapper instanceof McpAsyncClientWrapper);
    }

    @Test
    void testProtocolVersions_BuildSyncWithVersions() {
        McpClientBuilder builder =
                McpClientBuilder.create("pv-sync")
                        .stdioTransport("echo", "test")
                        .protocolVersions("2024-11-05", "2025-03-26");

        McpClientWrapper wrapper = builder.buildSync();
        assertNotNull(wrapper);
        assertTrue(wrapper instanceof McpSyncClientWrapper);
    }

    @Test
    void testProtocolVersions_WithSseTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("pv-sse")
                        .sseTransport("https://mcp.example.com/sse")
                        .protocolVersions("2024-11-05", "2025-03-26");

        assertNotNull(builder);
    }

    @Test
    void testProtocolVersions_WithStreamableHttpTransport() {
        McpClientBuilder builder =
                McpClientBuilder.create("pv-http")
                        .streamableHttpTransport("https://mcp.example.com/http")
                        .protocolVersions("2024-11-05", "2025-03-26");

        assertNotNull(builder);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProtocolVersions_OverrideTransportIsApplied() throws Exception {
        // 验证设置 protocolVersions 后包装传输层
        McpClientBuilder builder =
                McpClientBuilder.create("pv-verify")
                        .stdioTransport("echo", "test")
                        .protocolVersions("2024-11-05", "2025-03-26");

        // 通过反射访问 protocolVersions 字段
        Field pvField = McpClientBuilder.class.getDeclaredField("protocolVersions");
        pvField.setAccessible(true);
        List<String> versions = (List<String>) pvField.get(builder);

        assertNotNull(versions);
        assertEquals(2, versions.size());
        assertEquals("2024-11-05", versions.get(0));
        assertEquals("2025-03-26", versions.get(1));
    }

    @Test
    void testProtocolVersions_WithoutSettingUsesDefault() throws Exception {
        // 验证未设置 protocolVersions 时字段保持 null
        McpClientBuilder builder =
                McpClientBuilder.create("pv-default").stdioTransport("echo", "test");

        Field pvField = McpClientBuilder.class.getDeclaredField("protocolVersions");
        pvField.setAccessible(true);
        Object versions = pvField.get(builder);

        // 应为 null，表示使用传输层默认 protocolVersions()
        assertEquals(null, versions);
    }

    @Test
    void testProtocolVersions_NullElementsAreFiltered() throws Exception {
        // 验证静默过滤可变参数中的 null 元素
        McpClientBuilder builder =
                McpClientBuilder.create("pv-null-filter")
                        .stdioTransport("echo", "test")
                        .protocolVersions("2024-11-05", null, "2025-03-26");

        Field pvField = McpClientBuilder.class.getDeclaredField("protocolVersions");
        pvField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> versions = (List<String>) pvField.get(builder);

        assertNotNull(versions);
        assertEquals(2, versions.size());
        assertEquals("2024-11-05", versions.get(0));
        assertEquals("2025-03-26", versions.get(1));
    }

    @Test
    void testProtocolVersions_AllNullElementsThrows() {
        // 验证仅传 null 元素时抛异常
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        McpClientBuilder.create("pv-all-null")
                                .stdioTransport("echo", "test")
                                .protocolVersions(null, null));
    }
}
