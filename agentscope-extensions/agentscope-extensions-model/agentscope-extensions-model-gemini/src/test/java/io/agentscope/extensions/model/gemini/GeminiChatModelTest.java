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
package io.agentscope.extensions.model.gemini;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.types.ClientOptions;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GoogleSearch;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.ProxyOptions;
import com.google.genai.types.Tool;
import com.google.genai.types.UrlContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.test.ModelTestUtils;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.extensions.model.gemini.formatter.GeminiChatFormatter;
import io.agentscope.extensions.model.gemini.formatter.GeminiMultiAgentFormatter;
import io.agentscope.extensions.model.gemini.tool.GeminiServerTool;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * GeminiChatModel 单元测试。
 *
 * <p>验证基本配置、构建器模式、流式输出、工具调用及不同 API 配置，包括 Gemini API 与 Vertex AI。
 *
 * <p>使用模拟 API 密钥，避免实际网络调用，重点验证模型构建与配置。
 *
 * <p>标记为 "unit"，不依赖外部服务的快速测试。
 */
@Tag("unit")
@DisplayName("GeminiChatModel Unit Tests")
class GeminiChatModelTest {

    private String mockApiKey;

    @BeforeEach
    void setUp() {
        mockApiKey = ModelTestUtils.createMockApiKey();
    }

    @Test
    @DisplayName("Should apply request timeout to Gemini response streams")
    void testAppliesRequestTimeout() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .timeout(Duration.ofMillis(10))
                                        .maxAttempts(1)
                                        .build())
                        .build();

        ModelException error =
                assertThrows(
                        ModelException.class,
                        () ->
                                new TestGeminiChatModel(Flux.never())
                                        .stream(List.of(), List.of(), options)
                                                .blockLast(Duration.ofSeconds(1)));

        assertTrue(error.getMessage().contains("timeout"));
    }

    @Test
    @DisplayName("Should support forcing a specific tool")
    void testSupportsSpecificToolChoice() {
        GeminiChatModel model =
                GeminiChatModel.builder().apiKey(mockApiKey).modelName("gemini-2.0-flash").build();

        assertTrue(model.supportsToolChoiceSpecific());
    }

    @Test
    @DisplayName("Should retry Gemini response streams according to execution config")
    void testRetriesRequestThroughStreamEntryPoint() {
        AtomicInteger attempts = new AtomicInteger();
        GenerateOptions options =
                GenerateOptions.builder()
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .maxAttempts(2)
                                        .initialBackoff(Duration.ofMillis(1))
                                        .maxBackoff(Duration.ofMillis(1))
                                        .retryOn(error -> true)
                                        .build())
                        .build();

        assertThrows(
                RuntimeException.class,
                () ->
                        new TestGeminiChatModel(
                                        Flux.defer(
                                                () -> {
                                                    attempts.incrementAndGet();
                                                    return Flux.error(
                                                            new IllegalStateException("failed"));
                                                }))
                                .stream(List.of(), List.of(), options).blockLast());

        assertEquals(2, attempts.get());
    }

    @Test
    @DisplayName("Should create model with valid configuration")
    void testBasicModelCreation() {
        GeminiChatModel model =
                GeminiChatModel.builder().apiKey(mockApiKey).modelName("gemini-2.0-flash").build();

        assertNotNull(model, "Model should be created");
        assertEquals("gemini-2.0-flash", model.getModelName());
    }

    @Test
    @DisplayName("Should use default model name when not specified")
    void testDefaultModelName() {
        GeminiChatModel model = GeminiChatModel.builder().apiKey(mockApiKey).build();

        assertNotNull(model, "Model should be created");
        assertEquals("gemini-2.5-flash", model.getModelName());
    }

    @Test
    @DisplayName("Should create model with Gemini API configuration")
    void testGeminiAPIConfiguration() {
        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .streamEnabled(true)
                        .build();

        assertNotNull(model, "Gemini API model should be created");
    }

    @Test
    @DisplayName("Should handle streaming configuration")
    void testStreamingConfiguration() {
        // 创建流式模型
        GeminiChatModel streamingModel =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .streamEnabled(true)
                        .build();

        assertNotNull(streamingModel, "Streaming model should be created");

        // 创建非流式模型
        GeminiChatModel nonStreamingModel =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .streamEnabled(false)
                        .build();

        assertNotNull(nonStreamingModel, "Non-streaming model should be created");
    }

    @Test
    @DisplayName("Should support tool calling configuration")
    void testToolCallConfiguration() {
        // 创建模型
        GeminiChatModel modelWithTools =
                GeminiChatModel.builder().apiKey(mockApiKey).modelName("gemini-2.0-flash").build();

        assertNotNull(modelWithTools, "Model with tools should be created");

        // 可在 stream 调用中传入工具模式
        List<ToolSchema> tools =
                List.of(ModelTestUtils.createSimpleToolSchema("test_tool", "A test tool"));

        assertNotNull(tools, "Tool schemas should be created");
    }

    @Test
    @DisplayName("Should combine function tools and Gemini server tools in request config")
    void testBuildConfigCombinesFunctionAndServerTools() {
        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .serverTools(
                                List.of(
                                        GeminiServerTool.of(
                                                Tool.builder()
                                                        .googleSearch(
                                                                GoogleSearch.builder().build())
                                                        .build()),
                                        GeminiServerTool.of(
                                                Tool.builder()
                                                        .urlContext(UrlContext.builder().build())
                                                        .build())))
                        .build();
        ToolSchema functionTool =
                ModelTestUtils.createSimpleToolSchema("test_tool", "A test function tool");

        GenerateContentConfig config =
                model.buildGenerateContentConfig(List.of(functionTool), null);

        assertEquals(3, config.tools().orElseThrow().size());
        assertTrue(config.tools().orElseThrow().get(0).functionDeclarations().isPresent());
        assertTrue(config.tools().orElseThrow().get(1).googleSearch().isPresent());
        assertTrue(config.tools().orElseThrow().get(2).urlContext().isPresent());
    }

    @Test
    @DisplayName("Should handle error gracefully when API key is invalid")
    void testInvalidApiKey() {
        // 使用无效密钥创建模型
        GeminiChatModel invalidModel =
                GeminiChatModel.builder()
                        .apiKey("invalid_key")
                        .modelName("gemini-2.0-flash")
                        .build();

        assertNotNull(invalidModel, "Model should still be created with invalid key");

        // 注意：实际 API 调用会失败，但模型创建应成功
    }

    @Test
    @DisplayName("Should require model name")
    void testModelNameRequired() {
        // 模型名称必填，但有默认值
        assertDoesNotThrow(() -> GeminiChatModel.builder().apiKey(mockApiKey).build());
    }

    @Test
    @DisplayName("Should configure default options")
    void testDefaultOptionsConfiguration() {
        GenerateOptions options =
                GenerateOptions.builder().temperature(0.7).maxTokens(2000).topP(0.95).build();

        GeminiChatModel modelWithOptions =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .defaultOptions(options)
                        .build();

        assertNotNull(modelWithOptions);
    }

    @Test
    @DisplayName("Should return correct model name")
    void testGetModelName() {
        GeminiChatModel gemini20Flash =
                GeminiChatModel.builder().apiKey(mockApiKey).modelName("gemini-2.0-flash").build();

        assertEquals("gemini-2.0-flash", gemini20Flash.getModelName());

        GeminiChatModel gemini15Pro =
                GeminiChatModel.builder().apiKey(mockApiKey).modelName("gemini-1.5-pro").build();

        assertEquals("gemini-1.5-pro", gemini15Pro.getModelName());
    }

    @Test
    @DisplayName("Should create model with custom formatter")
    void testCustomFormatter() {
        // 测试自定义格式化器
        assertDoesNotThrow(
                () -> {
                    GeminiChatModel modelWithFormatter =
                            GeminiChatModel.builder()
                                    .apiKey(mockApiKey)
                                    .modelName("gemini-2.0-flash")
                                    .formatter(new GeminiChatFormatter())
                                    .build();

                    assertNotNull(modelWithFormatter);
                });
    }

    @Test
    @DisplayName("Should handle GenerateOptions configuration")
    void testGenerateOptionsConfiguration() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.8)
                        .maxTokens(2000)
                        .topP(0.95)
                        .frequencyPenalty(0.2)
                        .presencePenalty(0.1)
                        .build();

        GeminiChatModel modelWithOptions =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .defaultOptions(options)
                        .build();

        assertNotNull(modelWithOptions);
    }

    @Test
    @DisplayName("Should build with minimal parameters")
    void testMinimalBuilder() {
        GeminiChatModel minimalModel =
                GeminiChatModel.builder().apiKey(mockApiKey).modelName("gemini-2.0-flash").build();

        assertNotNull(minimalModel);
        assertNotNull(minimalModel.getModelName());
    }

    @Test
    @DisplayName("Should handle thinking mode configuration")
    void testThinkingModeConfiguration() {
        // 测试思考预算
        GenerateOptions thinkingOptions = GenerateOptions.builder().thinkingBudget(2000).build();

        GeminiChatModel thinkingModel =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash-thinking")
                        .defaultOptions(thinkingOptions)
                        .build();

        assertNotNull(thinkingModel);
    }

    @Test
    @DisplayName("Should create vision model")
    void testVisionModelCreation() {
        GeminiChatModel visionModel =
                GeminiChatModel.builder().apiKey(mockApiKey).modelName("gemini-1.5-pro").build();

        assertNotNull(visionModel);
        assertEquals("gemini-1.5-pro", visionModel.getModelName());
    }

    @Test
    @DisplayName("Should support multiagent formatter")
    void testMultiAgentFormatterConfiguration() {
        GeminiChatModel multiAgentModel =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .formatter(new GeminiMultiAgentFormatter())
                        .build();

        assertNotNull(multiAgentModel);
    }

    @Test
    @DisplayName("Should support different formatter types")
    void testDifferentFormatterTypes() {
        // 聊天格式化器
        GeminiChatModel chatModel =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .formatter(new GeminiChatFormatter())
                        .build();
        assertNotNull(chatModel);

        // 多智能体格式化器
        GeminiChatModel multiAgentModel =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .formatter(new GeminiMultiAgentFormatter())
                        .build();
        assertNotNull(multiAgentModel);
    }

    @Test
    @DisplayName("Should configure HTTP options")
    void testHttpOptionsConfiguration() {
        HttpOptions httpOptions = HttpOptions.builder().build();

        GeminiChatModel modelWithHttpOptions =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .httpOptions(httpOptions)
                        .build();

        assertNotNull(modelWithHttpOptions);
    }

    @Test
    @DisplayName("Should configure custom base URL")
    void testBaseUrlConfiguration() throws Exception {
        String baseUrl = "https://custom-gemini-endpoint.example";

        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .baseUrl(baseUrl)
                        .build();

        assertNotNull(model);
        assertEquals(baseUrl, getHttpOptions(model).baseUrl().orElseThrow());
    }

    @Test
    @DisplayName("Should override HTTP options base URL while preserving other settings")
    void testBaseUrlOverridesHttpOptionsBaseUrl() throws Exception {
        HttpOptions httpOptions =
                HttpOptions.builder()
                        .baseUrl("https://original-gemini-endpoint.example")
                        .apiVersion("v1beta")
                        .timeout(3210)
                        .build();

        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .httpOptions(httpOptions)
                        .baseUrl("https://override-gemini-endpoint.example")
                        .build();

        HttpOptions effectiveHttpOptions = getHttpOptions(model);
        assertNotNull(effectiveHttpOptions);
        assertEquals(
                "https://override-gemini-endpoint.example",
                effectiveHttpOptions.baseUrl().orElseThrow());
        assertEquals("v1beta", effectiveHttpOptions.apiVersion().orElseThrow());
        assertEquals(3210, effectiveHttpOptions.timeout().orElseThrow());
    }

    @Test
    @DisplayName("Should handle all generation options")
    void testAllGenerateOptions() {
        GenerateOptions fullOptions =
                GenerateOptions.builder()
                        .temperature(0.7)
                        .maxTokens(1500)
                        .topP(0.9)
                        .frequencyPenalty(0.3)
                        .presencePenalty(0.2)
                        .thinkingBudget(2000)
                        .build();

        GeminiChatModel modelWithFullOptions =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .defaultOptions(fullOptions)
                        .build();

        assertNotNull(modelWithFullOptions);
    }

    @Test
    @DisplayName("Should build with all builder methods for Gemini API")
    void testCompleteBuilderForGeminiAPI() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.8)
                        .maxTokens(2000)
                        .topP(0.95)
                        .frequencyPenalty(0.2)
                        .presencePenalty(0.1)
                        .build();

        HttpOptions httpOptions = HttpOptions.builder().build();

        GeminiChatModel completeModel =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .streamEnabled(true)
                        .defaultOptions(options)
                        .formatter(new GeminiChatFormatter())
                        .httpOptions(httpOptions)
                        .build();

        assertNotNull(completeModel);
        assertEquals("gemini-2.0-flash", completeModel.getModelName());
    }

    @Test
    @DisplayName("Should support gemini-2.0-flash-exp model")
    void testExperimentalModelCreation() {
        GeminiChatModel expModel =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash-exp")
                        .build();

        assertNotNull(expModel);
        assertEquals("gemini-2.0-flash-exp", expModel.getModelName());
    }

    @Test
    @DisplayName("Should support gemini-2.0-flash-thinking model")
    void testThinkingModelCreation() {
        GeminiChatModel thinkingModel =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash-thinking")
                        .build();

        assertNotNull(thinkingModel);
        assertEquals("gemini-2.0-flash-thinking", thinkingModel.getModelName());
    }

    @Test
    @DisplayName("Should handle close operation gracefully")
    void testCloseOperation() {
        GeminiChatModel model =
                GeminiChatModel.builder().apiKey(mockApiKey).modelName("gemini-2.0-flash").build();

        assertNotNull(model);

        // 不应抛异常
        assertDoesNotThrow(() -> model.close());
    }

    @Test
    @DisplayName("Should handle multiple close calls")
    void testMultipleCloseCall() {
        GeminiChatModel model =
                GeminiChatModel.builder().apiKey(mockApiKey).modelName("gemini-2.0-flash").build();

        assertDoesNotThrow(
                () -> {
                    model.close();
                    model.close(); // 应平稳处理
                });
    }

    @Test
    @DisplayName("Should handle tool choice configuration")
    void testToolChoiceConfiguration() {
        GenerateOptions optionsWithToolChoice =
                GenerateOptions.builder().toolChoice(new ToolChoice.Auto()).build();

        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .defaultOptions(optionsWithToolChoice)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should create model with default streaming enabled")
    void testDefaultStreamingEnabled() {
        GeminiChatModel model =
                GeminiChatModel.builder().apiKey(mockApiKey).modelName("gemini-2.0-flash").build();

        assertNotNull(model);
        // 默认启用流式输出
    }

    @Test
    @DisplayName("Should handle null default options gracefully")
    void testNullDefaultOptions() {
        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .defaultOptions(null)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should handle null formatter gracefully")
    void testNullFormatter() {
        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .formatter(null)
                        .build();

        assertNotNull(model);
        // 应使用默认 GeminiChatFormatter
    }

    // ===========================================================================
    // 代理配置测试
    // ===========================================================================

    @Test
    @DisplayName("Should create model with proxy() only")
    void testProxyOnly() {
        ProxyConfig proxy = ProxyConfig.http("proxy.example.com", 8080);

        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .proxy(proxy)
                        .build();

        assertNotNull(model);
        assertEquals("gemini-2.0-flash", model.getModelName());
    }

    @Test
    @DisplayName("Should create model with clientOptions() only (no proxy)")
    void testClientOptionsOnly() {
        ClientOptions clientOptions =
                ClientOptions.builder().maxConnections(10).maxConnectionsPerHost(5).build();

        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .clientOptions(clientOptions)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should smart merge proxy() into clientOptions() when clientOptions has no proxy")
    void testSmartMergeProxyIntoClientOptions() {
        // 未配置代理的 clientOptions
        ClientOptions clientOptions =
                ClientOptions.builder().maxConnections(10).maxConnectionsPerHost(5).build();

        ProxyConfig proxy = ProxyConfig.http("proxy.example.com", 8080);

        // 应将代理合并到 clientOptions，不记录警告
        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .clientOptions(clientOptions)
                        .proxy(proxy)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should handle proxy() with SOCKS5 type")
    void testProxyWithSocks5() {
        ProxyConfig proxy = ProxyConfig.socks5("socks.example.com", 1080, "user", "pass");

        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .proxy(proxy)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should handle proxy() with httpOptions() (no conflict)")
    void testProxyWithHttpOptions() {
        ProxyConfig proxy = ProxyConfig.http("proxy.example.com", 8080);
        HttpOptions httpOptions = HttpOptions.builder().build();

        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .proxy(proxy)
                        .httpOptions(httpOptions)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should warn when both proxy() and clientOptions.proxyOptions() are set")
    void testClientOptionsWithProxyTakesPrecedence() {
        // clientOptions 已配置代理
        ProxyOptions googleProxy =
                ProxyOptions.builder()
                        .host("existing-proxy.com")
                        .port(3128)
                        .type(com.google.genai.types.ProxyType.Known.HTTP)
                        .build();

        ClientOptions clientOptionsWithProxy =
                ClientOptions.builder().maxConnections(10).proxyOptions(googleProxy).build();

        // 另外通过 proxy() 设置不同代理
        ProxyConfig proxy = ProxyConfig.http("new-proxy.com", 9090);

        // 应记录警告并使用 clientOptions 的代理
        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("gemini-2.0-flash")
                        .clientOptions(clientOptionsWithProxy)
                        .proxy(proxy)
                        .build();

        assertNotNull(model);
        // clientOptions 的代理优先，忽略 proxy()
    }

    private static HttpOptions getHttpOptions(GeminiChatModel model) throws Exception {
        Field httpOptionsField = GeminiChatModel.class.getDeclaredField("httpOptions");
        httpOptionsField.setAccessible(true);
        return (HttpOptions) httpOptionsField.get(model);
    }

    private static final class TestGeminiChatModel extends GeminiChatModel {

        private final Flux<ChatResponse> response;

        private TestGeminiChatModel(Flux<ChatResponse> response) {
            super("test-key", "gemini-test", true, null, null, null, null, null, null, null, null);
            this.response = response;
        }

        @Override
        protected Flux<ChatResponse> doStream0(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return response;
        }
    }
}
