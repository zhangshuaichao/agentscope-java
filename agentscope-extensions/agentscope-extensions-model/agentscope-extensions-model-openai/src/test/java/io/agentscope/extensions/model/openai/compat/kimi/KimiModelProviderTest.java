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
package io.agentscope.extensions.model.openai.compat.kimi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.dto.OpenAITool;
import io.agentscope.extensions.model.openai.dto.OpenAIToolFunction;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class KimiModelProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        ModelRegistry.reset();
    }

    @Test
    void providerIdIsKimi() {
        assertEquals("kimi", new KimiModelProvider().providerId());
    }

    @Test
    void supportsKimiModelIds() {
        KimiModelProvider provider = new KimiModelProvider();
        assertTrue(provider.supports("kimi:kimi-k3"));
        assertTrue(provider.supports("kimi:kimi-k2.6"));
        assertTrue(provider.supports("kimi:moonshot-v1-8k"));
        assertFalse(provider.supports("kimi:"));
        assertFalse(provider.supports("openai:gpt-4o-mini"));
        assertFalse(provider.supports(null));
    }

    @Test
    void createTrimsModelName() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder().apiKey("test-kimi-key").build();

        Model model = provider.create("kimi: kimi-k3", context);

        assertEquals("kimi-k3", model.getModelName());
    }

    @Test
    void createRejectsUnsupportedModelIdsBeforeReadingEnvironment() {
        KimiModelProvider provider = new KimiModelProvider();

        assertThrows(IllegalArgumentException.class, () -> provider.create("kimi:"));
        assertThrows(IllegalArgumentException.class, () -> provider.create("kimi-k3"));
        assertThrows(IllegalArgumentException.class, () -> provider.create(null));
    }

    @Test
    void createUsesModelCreationContext() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder().apiKey("test-kimi-key").stream(false)
                        .component(GenerateOptions.class, GenerateOptions.builder().build())
                        .component(ProxyConfig.class, ProxyConfig.http("localhost", 8080))
                        .option("contextWindowSize", 262144)
                        .build();

        Model model = provider.create("kimi:kimi-k3", context);

        assertTrue(model instanceof OpenAIChatModel);
        assertEquals("kimi-k3", model.getModelName());
        assertEquals(262144, model.getContextWindowSize());
    }

    @Test
    void createUsesKnownKimiContextWindows() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder().apiKey("test-kimi-key").build();

        assertEquals(1_048_576, provider.create("kimi:kimi-k3", context).getContextWindowSize());
        assertEquals(
                262_144,
                provider.create("kimi:kimi-k2.7-code-highspeed", context).getContextWindowSize());
        assertEquals(262_144, provider.create("kimi:kimi-k2.6", context).getContextWindowSize());
        assertEquals(
                131_072, provider.create("kimi:moonshot-v1-128k", context).getContextWindowSize());
        assertEquals(
                8_192,
                provider.create("kimi:moonshot-v1-8k-vision-preview", context)
                        .getContextWindowSize());
    }

    @Test
    void createAppliesUserGenerateOptions() throws Exception {
        GenerateOptions options =
                GenerateOptions.builder()
                        .reasoningEffort("high")
                        .additionalBodyParam("thinking", Map.of("type", "disabled"))
                        .build();

        assertEquals("high", requestBodyFor("kimi:kimi-k3", options).get("reasoning_effort"));
        assertEquals(
                Map.of("type", "disabled"),
                requestBodyFor("kimi:kimi-k2.6", options).get("thinking"));
    }

    @Test
    void nativeStructuredOutputEnabledByDefault() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder().apiKey("test-kimi-key").build();

        Model model = provider.create("kimi:kimi-k3", context);

        // 此 Kimi 配置的 response_format 支持严格约束解码的 json_schema
        assertTrue(model.supportsNativeStructuredOutput());
    }

    @Test
    void nativeStructuredOutputCanBeDisabledByOption() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-kimi-key")
                        .option("nativeStructuredOutput", false)
                        .build();

        Model model = provider.create("kimi:kimi-k3", context);

        assertFalse(model.supportsNativeStructuredOutput());
    }

    @Test
    void nativeStructuredOutputWithToolsDisabledByDefault() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder().apiKey("test-kimi-key").build();

        Model model = provider.create("kimi:kimi-k3", context);

        // 原有真实 API 测试观察到 Kimi 在两者并存时优先 response_format，
        // 而非工具调用，因此工具回退到 generate_response
        assertFalse(model.supportsNativeStructuredOutputWithTools());
    }

    @Test
    void nativeStructuredOutputWithToolsCanBeEnabledByOption() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-kimi-key")
                        .option("nativeStructuredOutputWithTools", true)
                        .build();

        Model model = provider.create("kimi:kimi-k3", context);

        // 供确认自身端点正确支持此组合的用户显式启用
        assertTrue(model.supportsNativeStructuredOutputWithTools());
        assertTrue(model.supportsNativeStructuredOutput());
    }

    @Test
    void supportsToolChoiceSpecificDisabledForThinkingModels() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder().apiKey("test-kimi-key").build();

        // 始终思考模型中，KimiFormatter 将 ToolChoice.Specific 降级为 "auto"，
        // 因此智能体改用提示词提醒强制 generate_response
        assertFalse(provider.create("kimi:kimi-k3", context).supportsToolChoiceSpecific());
        assertFalse(
                provider.create("kimi:kimi-k2.7-code-highspeed", context)
                        .supportsToolChoiceSpecific());

        // 可配置思考模型中，Specific 仅在 thinking.type=disabled 时有效，
        // 静态逐模型标志无法表达此条件，因此保守报告不支持
        assertFalse(provider.create("kimi:kimi-k2.6", context).supportsToolChoiceSpecific());
        assertFalse(provider.create("kimi:kimi-k2.5", context).supportsToolChoiceSpecific());

        // 其他 Kimi 模型将 ToolChoice.Specific 作为具名函数选择传递
        assertTrue(
                provider.create("kimi:kimi-k2-0905-preview", context).supportsToolChoiceSpecific());
        assertTrue(provider.create("kimi:moonshot-v1-8k", context).supportsToolChoiceSpecific());
    }

    @Test
    void supportsToolChoiceSpecificMirrorsFormatterDegradation() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder().apiKey("test-kimi-key").build();

        // 防止 KimiModelProvider.create() 所述耦合漂移：标志必须等于
        // KimiFormatter.applyKimiToolChoice 对 ToolChoice.Specific 的处理结果，
        // 采用默认配置（无 thinking 参数）。若格式化器的
        // 降级条件不再匹配提供方的计算，此测试将
        // 失败，需要同步更新两者。增加 Kimi 模型时扩展模型列表。
        List<String> models =
                List.of(
                        "kimi-k3",
                        "kimi-k2.7-code-highspeed",
                        "kimi-k2.6",
                        "kimi-k2.5",
                        "kimi-k2-0905-preview",
                        "moonshot-v1-8k");

        for (String model : models) {
            assertEquals(
                    formatterKeepsSpecificToolChoice(model),
                    provider.create("kimi:" + model, context).supportsToolChoiceSpecific(),
                    model);
        }
    }

    // 判定基准：对默认请求运行真实格式化器降级逻辑（无 thinking
    // 参数），检查具名函数 tool_choice 是否保留。
    private static boolean formatterKeepsSpecificToolChoice(String model) {
        OpenAIRequest request = requestWithSingleTool(model);

        KimiFormatter.applyKimiToolChoice(request, new ToolChoice.Specific("get_weather"));

        return request.getToolChoice() instanceof Map;
    }

    private static OpenAIRequest requestWithSingleTool(String model) {
        OpenAIToolFunction function = new OpenAIToolFunction();
        function.setName("get_weather");
        OpenAITool tool = new OpenAITool();
        tool.setFunction(function);
        tool.setType("function");
        return OpenAIRequest.builder()
                .model(model)
                .messages(List.of())
                .tools(List.of(tool))
                .build();
    }

    @Test
    void contextWindowSizeOptionMustBeANumber() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-kimi-key")
                        .option("contextWindowSize", "not-a-number")
                        .build();

        assertThrows(
                IllegalArgumentException.class, () -> provider.create("kimi:kimi-k3", context));
    }

    @Test
    void nativeStructuredOutputOptionsMustBeBooleans() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext structuredOutputContext =
                ModelCreationContext.builder()
                        .apiKey("test-kimi-key")
                        .option("nativeStructuredOutput", "yes")
                        .build();
        ModelCreationContext structuredOutputWithToolsContext =
                ModelCreationContext.builder()
                        .apiKey("test-kimi-key")
                        .option("nativeStructuredOutputWithTools", "yes")
                        .build();

        assertThrows(
                IllegalArgumentException.class,
                () -> provider.create("kimi:kimi-k3", structuredOutputContext));
        assertThrows(
                IllegalArgumentException.class,
                () -> provider.create("kimi:kimi-k3", structuredOutputWithToolsContext));
    }

    @Test
    void createWithoutApiKeyThrowsIllegalStateException() {
        // 仅当环境也未提供 Kimi API 密钥时有意义
        assumeTrue(isBlank(System.getenv("MOONSHOT_API_KEY")));
        assumeTrue(isBlank(System.getenv("KIMI_API_KEY")));

        KimiModelProvider provider = new KimiModelProvider();

        IllegalStateException fromEmptyContext =
                assertThrows(IllegalStateException.class, () -> provider.create("kimi:kimi-k3"));
        assertTrue(fromEmptyContext.getMessage().contains("MOONSHOT_API_KEY"));
        assertTrue(fromEmptyContext.getMessage().contains("KIMI_API_KEY"));

        // 上下文 API 密钥为空白时必须继续从环境查找
        ModelCreationContext blankKeyContext = ModelCreationContext.builder().apiKey("   ").build();
        assertThrows(
                IllegalStateException.class,
                () -> provider.create("kimi:kimi-k3", blankKeyContext));
    }

    @Test
    void endpointPathIsApplied() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-kimi-key")
                        .endpointPath("/custom/chat/completions")
                        .build();

        Model model = provider.create("kimi:kimi-k3", context);

        assertTrue(model instanceof OpenAIChatModel);
    }

    @Test
    void httpTransportComponentIsApplied() {
        KimiModelProvider provider = new KimiModelProvider();
        HttpTransport transport =
                new HttpTransport() {
                    @Override
                    public HttpResponse execute(HttpRequest request) {
                        throw new UnsupportedOperationException("not used in this test");
                    }

                    @Override
                    public Flux<String> stream(HttpRequest request) {
                        return Flux.empty();
                    }

                    @Override
                    public void close() {}
                };
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-kimi-key")
                        .component(HttpTransport.class, transport)
                        .build();

        Model model = provider.create("kimi:kimi-k3", context);

        assertTrue(model instanceof OpenAIChatModel);
    }

    @Test
    void customFormatterComponentTakesPrecedence() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-kimi-key")
                        .component(KimiMultiAgentFormatter.class, new KimiMultiAgentFormatter())
                        .build();

        Model model = provider.create("kimi:kimi-k3", context);

        assertTrue(model instanceof OpenAIChatModel);
    }

    @Test
    void customBaseUrlOverridesDefault() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-kimi-key")
                        .baseUrl("https://kimi.example.com/v1")
                        .build();

        Model model = provider.create("kimi:kimi-k3", context);

        assertTrue(model instanceof OpenAIChatModel);
    }

    @Test
    void modelRegistryFindsKimiProviderFromServiceLoader() {
        assertTrue(ModelRegistry.canResolve("kimi:kimi-k3"));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static Map<String, Object> requestBodyFor(String modelId, GenerateOptions options)
            throws Exception {
        CapturingTransport transport = new CapturingTransport();
        ModelCreationContext.Builder contextBuilder =
                ModelCreationContext.builder().apiKey("test-kimi-key").stream(false)
                        .component(HttpTransport.class, transport);
        if (options != null) {
            contextBuilder.component(GenerateOptions.class, options);
        }

        OpenAIChatModel model =
                (OpenAIChatModel) new KimiModelProvider().create(modelId, contextBuilder.build());
        model.stream(userMessages(), null, null).blockLast();
        return parseBody(transport.request.getBody());
    }

    private static List<Msg> userMessages() {
        return List.of(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Reply with pong").build()))
                        .build());
    }

    private static Map<String, Object> parseBody(String body) throws Exception {
        return MAPPER.readValue(body, new TypeReference<Map<String, Object>>() {});
    }

    private static final class CapturingTransport implements HttpTransport {
        private HttpRequest request;

        @Override
        public HttpResponse execute(HttpRequest request) {
            this.request = request;
            return HttpResponse.builder()
                    .statusCode(200)
                    .body(
                            """
                            {
                              "id": "kimi-test",
                              "object": "chat.completion",
                              "created": 1,
                              "model": "kimi-k3",
                              "choices": [{
                                "index": 0,
                                "message": {
                                  "role": "assistant",
                                  "content": "pong"
                                },
                                "finish_reason": "stop"
                              }],
                              "usage": {
                                "prompt_tokens": 1,
                                "completion_tokens": 1,
                                "total_tokens": 2
                              }
                            }
                            """)
                    .build();
        }

        @Override
        public Flux<String> stream(HttpRequest request) {
            this.request = request;
            return Flux.empty();
        }

        @Override
        public void close() {}
    }
}
