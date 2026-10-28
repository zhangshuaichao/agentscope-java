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
package io.agentscope.extensions.model.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.formatter.JsonSchema;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.model.transport.OkHttpTransport;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** OpenAIChatModel 集成测试。<p>使用 MockWebServer 模拟 OpenAI API 响应，验证非流式/流式聊天补全、工具调用、错误处理及选项应用。 */
@Tag("integration")
@DisplayName("OpenAIChatModel Integration Tests")
class OpenAIChatModelTest {

    private MockWebServer mockServer;
    private OpenAIChatModel model;
    private HttpTransport transport;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        String baseUrl = mockServer.url("/").toString();
        // 移除末尾斜杠
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        transport = HttpTransportFactory.getDefault();
        model =
                OpenAIChatModel.builder().apiKey("test-api-key").modelName("gpt-4").stream(false)
                        .baseUrl(baseUrl)
                        .formatter(new OpenAIChatFormatter())
                        .httpTransport(transport)
                        .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
        // 无状态模型无需 close()
    }

    @Test
    @DisplayName("Default builder enables native structured output")
    void testNativeStructuredOutputDefaultTrue() {
        OpenAIChatModel m = OpenAIChatModel.builder().apiKey("k").modelName("gpt-4").build();
        assertTrue(m.supportsNativeStructuredOutput());
    }

    @Test
    @DisplayName("Generic OpenAI builder does not assume specific tool_choice support")
    void testSupportsToolChoiceSpecificDefaultFalse() {
        OpenAIChatModel m = OpenAIChatModel.builder().apiKey("k").modelName("gpt-4").build();

        assertFalse(m.supportsToolChoiceSpecific());
    }

    @Test
    @DisplayName("Builder can disable native structured output for compatible gateways")
    void testNativeStructuredOutputDisabled() {
        OpenAIChatModel m =
                OpenAIChatModel.builder()
                        .apiKey("k")
                        .modelName("gpt-4")
                        .nativeStructuredOutput(false)
                        .build();
        assertFalse(m.supportsNativeStructuredOutput());
    }

    @Test
    @DisplayName("Should make non-streaming call successfully")
    void testNonStreamingCall() throws Exception {
        String responseJson =
                """
                {
                    "id": "chatcmpl-123",
                    "object": "chat.completion",
                    "created": 1677652280,
                    "model": "gpt-4",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Hello! How can I help you?"
                        },
                        "finish_reason": "stop"
                    }],
                    "usage": {
                        "prompt_tokens": 10,
                        "completion_tokens": 20,
                        "total_tokens": 30
                    }
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json"));

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Hello").build()))
                                .build());

        StepVerifier.create(model.stream(messages, null, null))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                            assertEquals(
                                    "Hello! How can I help you?",
                                    ((TextBlock) response.getContent().get(0)).getText());
                            assertNotNull(response.getUsage());
                            assertEquals(10, response.getUsage().getInputTokens());
                            assertEquals(20, response.getUsage().getOutputTokens());
                        })
                .verifyComplete();

        // 验证请求
        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("POST", request.getMethod());
        assertEquals("/v1/chat/completions", request.getPath());
        assertTrue(request.getHeader("Authorization").contains("Bearer test-api-key"));
    }

    @Test
    @DisplayName("Should handle streaming call")
    void testStreamingCall() {
        // 创建流式模型
        OpenAIChatModel streamingModel =
                OpenAIChatModel.builder().apiKey("test-api-key").modelName("gpt-4").stream(true)
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .formatter(new OpenAIChatFormatter())
                        .httpTransport(transport)
                        .build();

        String sseResponse =
                "data:"
                    + " {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}\n\n"
                    + "data:"
                    + " {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}\n\n"
                    + "data:"
                    + " {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"!\"},\"finish_reason\":null}]}\n\n"
                    + "data:"
                    + " {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n";

        mockServer.enqueue(
                new MockResponse()
                        .setBody(sseResponse)
                        .setHeader("Content-Type", "text/event-stream"));

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Say hello").build()))
                                .build());

        StepVerifier.create(streamingModel.stream(messages, null, null))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            // 第一个分块可能仅分配角色
                        })
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                        })
                .thenCancel()
                .verify();
    }

    @Test
    @DisplayName("Should apply generation options")
    void testApplyOptions() throws Exception {
        String responseJson =
                """
                {
                    "id": "chatcmpl-123",
                    "object": "chat.completion",
                    "created": 1677652280,
                    "model": "gpt-4",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Response"
                        },
                        "finish_reason": "stop"
                    }]
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json"));

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Hello").build()))
                                .build());

        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.7)
                        .maxTokens(1000)
                        .parallelToolCalls(true)
                        .build();

        StepVerifier.create(model.stream(messages, null, options))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();

        // 验证请求包含选项
        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"temperature\":0.7"));
        assertTrue(body.contains("\"max_tokens\":1000"));
        assertTrue(body.contains("\"parallel_tool_calls\":true"));
    }

    @Test
    @DisplayName("Model-level strict JSON schema default respects request-scoped strict")
    void testStrictJsonSchemaDefault() throws Exception {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Hello").build()))
                                .build());

        ResponseFormat unsetStrict = responseFormat(null);
        GenerateOptions options = GenerateOptions.builder().responseFormat(unsetStrict).build();

        enqueueCompletionResponse();
        StepVerifier.create(model.stream(messages, null, options))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();
        JsonNode defaultStrict = responseFormatStrict(mockServer.takeRequest());
        assertTrue(defaultStrict.isMissingNode(), "Default model should omit strict");

        OpenAIChatModel strictModel = modelWithStrictJsonSchema(true);
        enqueueCompletionResponse();
        StepVerifier.create(strictModel.stream(messages, null, options))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();
        JsonNode modelDefaultStrict = responseFormatStrict(mockServer.takeRequest());
        assertTrue(modelDefaultStrict.isBoolean());
        assertTrue(modelDefaultStrict.asBoolean());

        GenerateOptions requestOverride =
                GenerateOptions.builder().responseFormat(responseFormat(false)).build();
        enqueueCompletionResponse();
        StepVerifier.create(strictModel.stream(messages, null, requestOverride))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();
        JsonNode overriddenStrict = responseFormatStrict(mockServer.takeRequest());
        assertTrue(overriddenStrict.isBoolean());
        assertFalse(overriddenStrict.asBoolean());
    }

    private static ResponseFormat responseFormat(Boolean strict) {
        return ResponseFormat.jsonSchema(
                JsonSchema.builder()
                        .name("test_response")
                        .schema(Map.of("type", "object", "properties", Map.of()))
                        .strict(strict)
                        .build());
    }

    private OpenAIChatModel modelWithStrictJsonSchema(Boolean strictJsonSchema) {
        return OpenAIChatModel.builder().apiKey("test-api-key").modelName("gpt-4").stream(false)
                .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                .formatter(new OpenAIChatFormatter())
                .httpTransport(transport)
                .strictJsonSchema(strictJsonSchema)
                .build();
    }

    private void enqueueCompletionResponse() {
        mockServer.enqueue(
                new MockResponse()
                        .setBody(
                                """
                                {
                                    "id": "chatcmpl-123",
                                    "object": "chat.completion",
                                    "created": 1677652280,
                                    "model": "gpt-4",
                                    "choices": [{
                                        "index": 0,
                                        "message": {
                                            "role": "assistant",
                                            "content": "Response"
                                        },
                                        "finish_reason": "stop"
                                    }]
                                }
                                """)
                        .setHeader("Content-Type", "application/json"));
    }

    private static JsonNode responseFormatStrict(RecordedRequest request) throws Exception {
        assertNotNull(request);
        JsonNode body =
                JsonUtils.getJsonCodec().fromJson(request.getBody().readUtf8(), JsonNode.class);
        return body.path("response_format").path("json_schema").path("strict");
    }

    @Test
    @DisplayName("Should return model name")
    void testGetModelName() {
        assertEquals("gpt-4", model.getModelName());
    }

    @Test
    @DisplayName("Should build model with custom endpoint path")
    void testBuildModelWithEndpointPath() throws Exception {
        String responseJson =
                """
                {
                    "id": "chatcmpl-123",
                    "object": "chat.completion",
                    "created": 1677652280,
                    "model": "gpt-4",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Response"
                        },
                        "finish_reason": "stop"
                    }]
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json"));

        // 使用自定义端点路径构建模型
        OpenAIChatModel customPathModel =
                OpenAIChatModel.builder().apiKey("test-api-key").modelName("gpt-4").stream(false)
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .endpointPath("/v4/chat/completions")
                        .formatter(new OpenAIChatFormatter())
                        .httpTransport(transport)
                        .build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Hello").build()))
                                .build());

        StepVerifier.create(customPathModel.stream(messages, null, null))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();

        // 验证请求使用自定义端点路径
        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertTrue(
                request.getPath().contains("/v4/chat/completions"),
                "Path should contain custom endpoint path: " + request.getPath());
    }

    @Test
    @DisplayName("Should apply cache_control to request when cacheControl option is enabled")
    void testCacheControlApplied() throws Exception {
        String responseJson =
                """
                {
                    "id": "chatcmpl-123",
                    "object": "chat.completion",
                    "created": 1677652280,
                    "model": "gpt-4",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Response"
                        },
                        "finish_reason": "stop"
                    }]
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json"));

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.SYSTEM)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("You are helpful.")
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Hello").build()))
                                .build());

        GenerateOptions options = GenerateOptions.builder().cacheControl(true).build();

        StepVerifier.create(model.stream(messages, null, options))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        String body = request.getBody().readUtf8();
        assertTrue(
                body.contains("\"cache_control\""),
                "Request body should contain cache_control: " + body);
        assertTrue(
                body.contains("\"ephemeral\""),
                "Request body should contain ephemeral cache type: " + body);
    }

    @Test
    @DisplayName("Should NOT apply cache_control when cacheControl option is not enabled")
    void testCacheControlNotAppliedWhenDisabled() throws Exception {
        String responseJson =
                """
                {
                    "id": "chatcmpl-123",
                    "object": "chat.completion",
                    "created": 1677652280,
                    "model": "gpt-4",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Response"
                        },
                        "finish_reason": "stop"
                    }]
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json"));

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.SYSTEM)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("You are helpful.")
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Hello").build()))
                                .build());

        StepVerifier.create(model.stream(messages, null, null))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        String body = request.getBody().readUtf8();
        assertFalse(
                body.contains("\"cache_control\""),
                "Request body should NOT contain cache_control when disabled: " + body);
    }

    @Test
    @DisplayName("Should build model with default endpoint path when not specified")
    void testBuildModelWithDefaultEndpointPath() throws Exception {
        String responseJson =
                """
                {
                    "id": "chatcmpl-123",
                    "object": "chat.completion",
                    "created": 1677652280,
                    "model": "gpt-4",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Response"
                        },
                        "finish_reason": "stop"
                    }]
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json"));

        // 构建模型时不设置端点路径，应使用默认值
        OpenAIChatModel defaultPathModel =
                OpenAIChatModel.builder().apiKey("test-api-key").modelName("gpt-4").stream(false)
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .formatter(new OpenAIChatFormatter())
                        .httpTransport(transport)
                        .build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Hello").build()))
                                .build());

        StepVerifier.create(defaultPathModel.stream(messages, null, null))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();

        // 验证请求使用默认端点路径
        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertTrue(
                request.getPath().endsWith("/chat/completions")
                        || request.getPath().contains("/v1/chat/completions"),
                "Path should contain default endpoint path: " + request.getPath());
    }

    // ==========================================================================
    // 代理配置测试
    // ==========================================================================

    @Test
    @DisplayName("Should create model with proxy() only")
    void testProxyOnly() {
        ProxyConfig proxy = ProxyConfig.http("proxy.example.com", 8080);

        OpenAIChatModel model =
                OpenAIChatModel.builder().apiKey("test-api-key").modelName("gpt-4").stream(false)
                        .proxy(proxy)
                        .build();

        assertNotNull(model);
        assertEquals("gpt-4", model.getModelName());
    }

    @Test
    @DisplayName("Should create model with httpTransport() only (no proxy)")
    void testHttpTransportOnly() {
        HttpTransport transport = HttpTransportFactory.getDefault();

        OpenAIChatModel model =
                OpenAIChatModel.builder().apiKey("test-api-key").modelName("gpt-4").stream(false)
                        .httpTransport(transport)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName(
            "Should use factory default transport when neither proxy() nor httpTransport() is set")
    void testDefaultTransportWhenNoneSet() {
        // 均未设置 proxy() 或 httpTransport()，应使用 HttpTransportFactory.getDefault()
        OpenAIChatModel model =
                OpenAIChatModel.builder().apiKey("test-api-key").modelName("gpt-4").stream(false)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should prefer httpTransport() over proxy() with warning")
    void testHttpTransportTakesPrecedenceOverProxy() {
        HttpTransport transport = HttpTransportFactory.getDefault();
        ProxyConfig proxy = ProxyConfig.http("proxy.example.com", 8080);

        // 两者均设置时，httpTransport 应优先
        // 应记录警告，但仍成功构建
        OpenAIChatModel model =
                OpenAIChatModel.builder().apiKey("test-api-key").modelName("gpt-4").stream(false)
                        .httpTransport(transport)
                        .proxy(proxy)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should create proxy-only transport without httpTransport()")
    void testProxyCreatesDefaultTransport() {
        ProxyConfig proxy = ProxyConfig.socks5("socks.example.com", 1080);

        // 仅设置 proxy()，应使用带代理配置的默认 OkHttpTransport
        OpenAIChatModel model =
                OpenAIChatModel.builder().apiKey("test-api-key").modelName("gpt-4").stream(false)
                        .proxy(proxy)
                        .build();

        assertNotNull(model);
        // 模型应使用已配置代理的默认传输创建
    }

    @Test
    @DisplayName("Should build proxy with custom HttpTransport containing proxy")
    void testProxyInHttpTransportConfig() {
        // 高级用法：在传输自身内部配置代理
        ProxyConfig proxy = ProxyConfig.http("proxy.example.com", 8080);
        HttpTransport transport =
                OkHttpTransport.builder()
                        .config(
                                HttpTransportConfig.builder()
                                        .proxy(proxy)
                                        .connectTimeout(Duration.ofSeconds(60))
                                        .build())
                        .build();

        OpenAIChatModel model =
                OpenAIChatModel.builder().apiKey("test-api-key").modelName("gpt-4").stream(false)
                        .httpTransport(transport)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should enable parallel tool calls when set parallel_tool_calls to true")
    void testEnableParallelToolCalls() throws Exception {
        String responseJson =
                """
                {
                    "id": "chatcmpl-123",
                    "object": "chat.completion",
                    "created": 1677652280,
                    "model": "gpt-4",
                    "choices": []
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json"));

        ToolSchema tool1 =
                ToolSchema.builder()
                        .name("get_weather")
                        .description("Get weather information")
                        .build();
        ToolSchema tool2 =
                ToolSchema.builder().name("calculate").description("Perform calculations").build();

        model.doStream(
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.SYSTEM)
                                        .content(
                                                TextBlock.builder()
                                                        .text("You are helpful.")
                                                        .build())
                                        .build(),
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .content(
                                                TextBlock.builder()
                                                        .text(
                                                                "Get the weather of Shanghai and"
                                                                        + " calculate 1+2+3.")
                                                        .build())
                                        .build()),
                        List.of(tool1, tool2),
                        GenerateOptions.builder().parallelToolCalls(true).build())
                .blockLast();

        RecordedRequest recorded = mockServer.takeRequest();
        String body = recorded.getBody().readUtf8();

        assertTrue(
                body.contains("\"parallel_tool_calls\":true"),
                "Request body should contain parallel_tool_calls with true" + body);

        mockServer.shutdown();
    }

    @Test
    @DisplayName("OpenAIChatModel - Should be NON-BLOCKING in non-streaming mode")
    void testOpenAIChatModelNonBlocking() throws Exception {
        String responseJson =
                """
                {
                    "id": "chatcmpl-123",
                    "object": "chat.completion",
                    "created": 1677652280,
                    "model": "gpt-4",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Hello!"
                        },
                        "finish_reason": "stop"
                    }],
                    "usage": {
                        "prompt_tokens": 10,
                        "completion_tokens": 5,
                        "total_tokens": 15
                    }
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json")
                        .setBodyDelay(500, TimeUnit.MILLISECONDS));

        OpenAIChatModel model =
                OpenAIChatModel.builder().apiKey("test-key").modelName("gpt-4").stream(false)
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Hello").build()))
                                .build());

        CountDownLatch latch = new CountDownLatch(1);
        String currentThreadName = Thread.currentThread().getName();
        AtomicReference<String> streamThreadName = new AtomicReference<>();

        model.stream(messages, null, null)
                .subscribe(
                        response -> {
                            streamThreadName.set(Thread.currentThread().getName());
                            latch.countDown();
                        },
                        error -> latch.countDown());

        latch.await(5, TimeUnit.SECONDS);
        assertNotNull(streamThreadName.get());
        assertNotEquals(
                currentThreadName,
                streamThreadName.get(),
                "OpenAIChatModel should be NON-BLOCKING");
    }
}
