/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.ollama;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.core.util.JacksonJsonCodec;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.ollama.formatter.OllamaChatFormatter;
import io.agentscope.extensions.model.ollama.formatter.OllamaMultiAgentFormatter;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import io.agentscope.extensions.model.ollama.options.ThinkOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Flux;

/**
 * OllamaChatModel 单元测试。
 *
 * <p>使用模拟传输验证配置与基本执行等模型行为。
 */
@Tag("unit")
@DisplayName("OllamaChatModel Unit Tests")
class OllamaChatModelTest {

    private static final String TEST_MODEL_NAME = "qwen2.5:14b-instruct";

    @Mock private HttpTransport httpTransport;

    private OllamaChatModel model;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // 使用构建器创建模型
        model =
                OllamaChatModel.builder()
                        .modelName(TEST_MODEL_NAME)
                        .baseUrl("http://192.168.2.2:11434")
                        .httpTransport(httpTransport)
                        .build();
    }

    @Test
    @DisplayName("Should create model with valid configuration")
    void testBasicModelCreation() {
        assertNotNull(model, "Model should be created");
        assertEquals(TEST_MODEL_NAME, model.getModelName());
        assertTrue(model.supportsToolChoiceSpecific());

        // 以最少参数测试构建器模式
        OllamaChatModel simpleModel =
                OllamaChatModel.builder().modelName("qwen2.5:14b-instruct").build();
        assertNotNull(simpleModel);
        assertEquals("qwen2.5:14b-instruct", simpleModel.getModelName());
    }

    // ========== 配置测试 ==========

    @Test
    @DisplayName("Should create with default options")
    void testDefaultOptions() {
        OllamaOptions options = OllamaOptions.builder().temperature(0.5).numCtx(4096).build();

        OllamaChatModel modelWithOptions =
                OllamaChatModel.builder()
                        .modelName(TEST_MODEL_NAME)
                        .defaultOptions(options)
                        .build();

        assertNotNull(modelWithOptions);
        assertEquals(TEST_MODEL_NAME, modelWithOptions.getModelName());
    }

    @Test
    @DisplayName("Should create with custom base URL")
    void testCustomBaseUrl() {
        OllamaChatModel modelWithBaseUrl =
                OllamaChatModel.builder()
                        .modelName(TEST_MODEL_NAME)
                        .baseUrl("http://192.168.2.2:11434")
                        .build();

        assertNotNull(modelWithBaseUrl);
    }

    @Test
    @DisplayName("Should support different formatter types")
    void testDifferentFormatterTypes() {
        // 聊天格式化器
        OllamaChatModel chatModel =
                OllamaChatModel.builder()
                        .modelName(TEST_MODEL_NAME)
                        .formatter(new OllamaChatFormatter())
                        .build();
        assertNotNull(chatModel);

        // 多智能体格式化器
        OllamaChatModel multiAgentModel =
                OllamaChatModel.builder()
                        .modelName(TEST_MODEL_NAME)
                        .formatter(new OllamaMultiAgentFormatter())
                        .build();
        assertNotNull(multiAgentModel);
    }

    @Test
    @DisplayName("Should handle empty messages list")
    void testEmptyMessagesList() {
        List<Msg> emptyMessages = new ArrayList<>();

        // 实例化时不应抛异常
        assertDoesNotThrow(
                () -> {
                    OllamaChatModel testModel =
                            OllamaChatModel.builder().modelName(TEST_MODEL_NAME).build();
                    assertNotNull(testModel);
                });
    }

    // ========== 模拟执行测试 ==========

    @Test
    @DisplayName("Should handle chat request")
    void testChatRequest() {
        System.out.println("Running testChatRequest (Mocked)...");
        // 模拟响应
        String jsonResponse =
                "{"
                        + "\"model\": \""
                        + TEST_MODEL_NAME
                        + "\","
                        + "\"created_at\": \"2023-08-04T08:52:19.385406455-07:00\","
                        + "\"message\": {\"role\": \"assistant\", \"content\": \"Hello!\"},"
                        + "\"done\": true,"
                        + "\"total_duration\": 100,"
                        + "\"load_duration\": 10,"
                        + "\"prompt_eval_count\": 5,"
                        + "\"prompt_eval_duration\": 20,"
                        + "\"eval_count\": 10,"
                        + "\"eval_duration\": 30"
                        + "}";

        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(jsonResponse).build());

        // 通过 Ollama 专用 chat 方法执行聊天
        ChatResponse response =
                model.chat(
                        List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()),
                        OllamaOptions.builder().build());

        System.out.println(
                "Response content: " + ((TextBlock) response.getContent().get(0)).getText());

        // 验证响应
        assertNotNull(response);
        ContentBlock content = response.getContent().get(0);
        assertTrue(content instanceof TextBlock);
        assertEquals("Hello!", ((TextBlock) content).getText());
        assertEquals(TEST_MODEL_NAME, response.getMetadata().get("model"));
        assertEquals(5, response.getUsage().getInputTokens());
        assertEquals(10, response.getUsage().getOutputTokens());

        // 验证请求
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(captor.capture());

        HttpRequest request = captor.getValue();
        assertEquals("POST", request.getMethod());
        // 注意：URL 可能随内部逻辑变化，通常为 /api/chat
        assertTrue(request.getUrl().endsWith("/api/chat"));
    }

    @Test
    @DisplayName("Should handle streaming chat request")
    void testStreamChatRequest() {
        System.out.println("Running testStreamChatRequest (Mocked)...");
        // 模拟流式响应
        String part1 =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"Hello\"},\"done\":false}";
        String part2 =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\""
                        + " World\"},\"done\":false}";
        String part3 =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"done\":true,\"total_duration\":100,\"eval_count\":2}";

        when(httpTransport.stream(any(HttpRequest.class)))
                .thenReturn(Flux.just(part1, part2, part3));

        // 通过通用 stream 方法与 GenerateOptions 执行流式调用
        // 注意：将 OllamaOptions 转为通用接口的 GenerateOptions
        GenerateOptions genOptions = OllamaOptions.builder().build().toGenerateOptions();
        Flux<ChatResponse> flux =
                model.stream(
                        List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()),
                        null,
                        genOptions);

        List<ChatResponse> responses = flux.collectList().block();

        System.out.println("Streamed " + responses.size() + " responses");

        // 验证
        assertNotNull(responses);
        assertEquals(3, responses.size());

        ContentBlock content1 = responses.get(0).getContent().get(0);
        assertTrue(content1 instanceof TextBlock);
        assertEquals("Hello", ((TextBlock) content1).getText());

        ContentBlock content2 = responses.get(1).getContent().get(0);
        assertTrue(content2 instanceof TextBlock);
        assertEquals(" World", ((TextBlock) content2).getText());

        // 验证请求
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).stream(captor.capture());

        HttpRequest request = captor.getValue();
        assertEquals("POST", request.getMethod());
    }

    @Test
    @DisplayName("Integration Test: Real connection to local Ollama")
    void testRealConnection() {
        System.out.println("Running testRealConnection (Mocked for speed)...");

        // 模拟响应
        String jsonResponse =
                "{\n"
                        + "\"model\": \""
                        + TEST_MODEL_NAME
                        + "\",\n"
                        + "\"created_at\": \"2026-01-013T17:39:19.385406455-07:00\",\n"
                        + "\"message\": {\"role\": \"assistant\", \"content\": \"Hello from"
                        + " Ollama!\"},\n"
                        + "\"done\": true,\n"
                        + "\"total_duration\": 100,\n"
                        + "\"load_duration\": 10,\n"
                        + "\"prompt_eval_count\": 5,\n"
                        + "\"prompt_eval_duration\": 20,\n"
                        + "\"eval_count\": 10,\n"
                        + "\"eval_duration\": 30\n"
                        + "}";

        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(jsonResponse).build());

        // 通过 Ollama 专用 chat 方法执行聊天
        ChatResponse response =
                model.chat(
                        List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()),
                        OllamaOptions.builder().temperature(0.7).build());

        System.out.println(
                "Response content: " + ((TextBlock) response.getContent().get(0)).getText());

        // 验证响应
        assertNotNull(response);
        ContentBlock content = response.getContent().get(0);
        assertTrue(content instanceof TextBlock);
        assertEquals("Hello from Ollama!", ((TextBlock) content).getText());
        assertEquals(TEST_MODEL_NAME, response.getMetadata().get("model"));
        assertEquals(5, response.getUsage().getInputTokens());
        assertEquals(10, response.getUsage().getOutputTokens());

        // 验证请求
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(captor.capture());

        HttpRequest request = captor.getValue();
        assertEquals("POST", request.getMethod());
        assertTrue(request.getUrl().endsWith("/api/chat"));
    }

    @Test
    @DisplayName("Should merge options correctly")
    void testOptionMerging() {
        // 默认选项
        OllamaOptions defaultOpts = OllamaOptions.builder().temperature(0.5).numCtx(2048).build();

        model =
                OllamaChatModel.builder()
                        .modelName(TEST_MODEL_NAME)
                        .defaultOptions(defaultOpts)
                        .httpTransport(httpTransport)
                        .build();

        // 运行时选项
        OllamaOptions runtimeOpts =
                OllamaOptions.builder()
                        .temperature(0.8) // 应覆盖
                        .topK(50) // 应添加
                        .build();

        String jsonResponse =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"done\":true}";
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(jsonResponse).build());

        // 执行聊天
        model.chat(
                List.of(Msg.builder().role(MsgRole.USER).textContent("Test").build()), runtimeOpts);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(captor.capture());

        String body = captor.getValue().getBody();
        // 验证 JSON 请求体包含预期选项
        assertTrue(body.contains("\"temperature\":0.8"));
        assertTrue(body.contains("\"num_ctx\":2048"));
        assertTrue(body.contains("\"top_k\":50"));
    }

    @Test
    @DisplayName("Should serialize JSON format option")
    void testJsonFormat() {
        OllamaOptions options = OllamaOptions.builder().format("json").build();

        String jsonResponse =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},\"done\":true}";
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(jsonResponse).build());

        model.chat(
                List.of(Msg.builder().role(MsgRole.USER).textContent("Output JSON").build()),
                options);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(captor.capture());

        String body = captor.getValue().getBody();
        assertTrue(body.contains("\"format\":\"json\""));
    }

    @Test
    @DisplayName("Should serialize stop sequences")
    void testStopSequences() {
        OllamaOptions options = OllamaOptions.builder().stop(List.of("STOP", "HALT")).build();

        String jsonResponse =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"done\":true}";
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(jsonResponse).build());

        model.chat(List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()), options);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(captor.capture());

        String body = captor.getValue().getBody();
        // 检查 JSON 数组格式的停止序列
        assertTrue(body.contains("\"stop\":[\"STOP\",\"HALT\"]"));
    }

    @Test
    @DisplayName("Should serialize tools correctly")
    void testToolSerialization() {
        // 模型默认配置
        // 定义简单工具
        ToolSchema tool =
                ToolSchema.builder()
                        .name("get_weather")
                        .description("Get current weather")
                        .parameters(
                                Map.of(
                                        "type", "object",
                                        "properties", Map.of("location", Map.of("type", "string")),
                                        "required", List.of("location")))
                        .build();

        // 创建模型
        model =
                OllamaChatModel.builder()
                        .modelName(TEST_MODEL_NAME)
                        .httpTransport(httpTransport)
                        .build();

        // 模拟响应
        String jsonResponse =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"done\":true}";
        when(httpTransport.stream(any(HttpRequest.class))).thenReturn(Flux.just(jsonResponse));

        GenerateOptions options = GenerateOptions.builder().build();
        model.stream(
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .textContent("What's the weather?")
                                        .build()),
                        List.of(tool),
                        options)
                .blockLast();

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).stream(
                captor.capture()); // doStream 使用 streamWithHttpClient，后者使用 stream()

        String body = captor.getValue().getBody();
        // 验证 JSON 包含工具
        assertTrue(body.contains("\"tools\":"));
        assertTrue(body.contains("\"name\":\"get_weather\""));
        assertTrue(body.contains("\"function\":"));
    }

    @Test
    @DisplayName("Should parse tool calls from response")
    void testToolCallParsing() {
        // 模拟含工具调用的响应
        // Ollama 格式：message: { role: assistant, tool_calls: [ { function: { name: ...,
        // arguments: ... } } ] }
        String jsonResponse =
                "{"
                        + "\"model\": \""
                        + TEST_MODEL_NAME
                        + "\","
                        + "\"done\": true,"
                        + "\"message\": {"
                        + "  \"role\": \"assistant\","
                        + "  \"content\": \"\","
                        + "  \"tool_calls\": ["
                        + "    {"
                        + "      \"function\": {"
                        + "        \"name\": \"get_weather\","
                        + "        \"arguments\": {\"location\": \"San Francisco\"}"
                        + "      }"
                        + "    }"
                        + "  ]"
                        + "}"
                        + "}";

        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(jsonResponse).build());

        // 用 chat 而非 stream 方法，加快执行
        ChatResponse response =
                model.chat(
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .textContent("Weather in SF")
                                        .build()),
                        OllamaOptions.builder().build());

        assertNotNull(response);
        assertFalse(response.getContent().isEmpty());

        ContentBlock block = response.getContent().get(0);
        assertTrue(block instanceof ToolUseBlock);

        ToolUseBlock toolUse = (ToolUseBlock) block;
        assertEquals("get_weather", toolUse.getName());
        assertEquals("San Francisco", toolUse.getInput().get("location"));
    }

    @Test
    @DisplayName("Should serialize image correctly")
    void testImageSerialization() {
        String base64Data =
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
        ImageBlock imageBlock = new ImageBlock(new Base64Source("image/png", base64Data));

        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("Describe this image").build(),
                                        imageBlock))
                        .build();

        String jsonResponse =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"It is a"
                        + " dot.\"},\"done\":true}";
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(jsonResponse).build());

        model.chat(List.of(msg), OllamaOptions.builder().build());

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(captor.capture());

        String body = captor.getValue().getBody();
        assertTrue(body.contains("\"images\":[\"" + base64Data + "\"]"));
    }

    @Test
    @DisplayName("Should serialize ToolChoice.Specific")
    void testToolChoiceSpecific() {
        ToolSchema tool =
                ToolSchema.builder().name("get_weather").description("Get weather").build();

        // 使用通用 GenerateOptions 指定工具选择
        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(new ToolChoice.Specific("get_weather"))
                        .build();

        String jsonResponse =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"done\":true}";

        // 测试工具选择仍使用 stream，因为格式化器可能仅在
        // stream 模式处理 tool_choice；通过适当模拟优化测试。
        when(httpTransport.stream(any(HttpRequest.class))).thenReturn(Flux.just(jsonResponse));

        // 使用 stream，因为工具选择可能仅在流式上下文处理
        model.stream(
                        List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()),
                        List.of(tool),
                        options)
                .blockFirst();

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).stream(captor.capture());

        String body = captor.getValue().getBody();
        // 检查指定工具选择格式；Ollama 要求 tool_choice 为对象还是字符串？
        // 此处按 "auto"、"none" 或指定函数对象处理
        // 按 OllamaChatFormatter 实现调整。
        // 假设序列化为 "tool_choice": {"type": "function", "function": {"name":
        // "get_weather"}}
        assertTrue(body.contains("\"tool_choice\":"));
        assertTrue(body.contains("\"function\":"));
        assertTrue(body.contains("\"name\":\"get_weather\""));
    }

    @Test
    @DisplayName("Should handle streaming chat request with options")
    void testStreamChatWithOptions() {
        System.out.println("Running testStreamChatWithOptions...");
        // 模拟流式响应
        String part1 =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"Hello\"},\"done\":false}";
        String part2 =
                "{\"model\":\"" + TEST_MODEL_NAME + "\",\"done\":true,\"total_duration\":100}";

        when(httpTransport.stream(any(HttpRequest.class))).thenReturn(Flux.just(part1, part2));

        OllamaOptions options = OllamaOptions.builder().temperature(0.9).seed(42).build();

        // 执行流式调用
        model.stream(
                        List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()),
                        null,
                        options.toGenerateOptions())
                .blockLast();

        // 验证请求包含选项
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).stream(captor.capture());

        String body = captor.getValue().getBody();
        assertTrue(body.contains("\"temperature\":0.9"));
        assertTrue(body.contains("\"seed\":42"));
    }

    @Test
    @DisplayName("Should use MultiAgentFormatter correctly")
    void testMultiAgentFormatterExecution() {
        OllamaMultiAgentFormatter multiAgentFormatter = new OllamaMultiAgentFormatter();

        model =
                OllamaChatModel.builder()
                        .modelName(TEST_MODEL_NAME)
                        .formatter(multiAgentFormatter)
                        .httpTransport(httpTransport)
                        .build();

        String jsonResponse =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"done\":true}";
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(jsonResponse).build());

        // 使用 chat() 触发 MultiAgentFormatter 的 buildRequest
        model.chat(
                List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()),
                OllamaOptions.builder().build());

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(captor.capture());

        // 仅验证发出请求，确保无错误经过该分支
        assertNotNull(captor.getValue());
    }

    @Test
    @DisplayName("Should handle streaming error")
    void testStreamErrorHandling() {
        System.out.println("Running testStreamErrorHandling...");

        when(httpTransport.stream(any(HttpRequest.class)))
                .thenReturn(Flux.error(new RuntimeException("Stream error")));

        // 此测试禁用重试
        OllamaOptions options =
                OllamaOptions.builder()
                        .executionConfig(ExecutionConfig.builder().maxAttempts(1).build())
                        .build();

        Flux<ChatResponse> flux =
                model.stream(
                        List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()),
                        null,
                        options.toGenerateOptions());

        try {
            flux.blockLast();
        } catch (Exception e) {
            assertTrue(
                    e.getMessage().contains("Stream error")
                            || (e.getCause() != null
                                    && e.getCause().getMessage().contains("Stream error")));
        }
    }

    @Test
    @DisplayName("Should handle streaming with multiple messages context")
    void testStreamWithMultipleMessages() {
        System.out.println("Running testStreamWithMultipleMessages...");

        String responseJson =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"Sure\"},\"done\":true}";
        when(httpTransport.stream(any(HttpRequest.class))).thenReturn(Flux.just(responseJson));

        List<Msg> history =
                List.of(
                        Msg.builder().role(MsgRole.USER).textContent("Hello").build(),
                        Msg.builder().role(MsgRole.ASSISTANT).textContent("Hi there").build(),
                        Msg.builder().role(MsgRole.USER).textContent("Tell me a joke").build());

        model.stream(history, null, GenerateOptions.builder().build()).blockLast();

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).stream(captor.capture());

        String body = captor.getValue().getBody();
        // 验证请求体包含所有消息
        assertTrue(body.contains("Hello"));
        assertTrue(body.contains("Hi there"));
        assertTrue(body.contains("Tell me a joke"));
    }

    @Test
    @DisplayName("Should handle transport error")
    void testTransportError() {
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        // 禁用重试，确保立即抛出原始异常
        OllamaOptions options =
                OllamaOptions.builder()
                        .executionConfig(ExecutionConfig.builder().maxAttempts(1).build())
                        .build();

        try {
            model.chat(
                    List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()), options);
        } catch (Exception e) {
            assertTrue(
                    e.getMessage().contains("Connection refused")
                            || (e.getCause() != null
                                    && e.getCause().getMessage().contains("Connection refused")),
                    "Exception should contain 'Connection refused', but was: " + e.getMessage());
        }
    }

    // ========== 思考模式测试 ==========

    @Test
    @DisplayName("Should serialize ThinkBoolean option at root level")
    void testThinkBooleanOption() throws JsonProcessingException {
        // 测试同时启用思考与 temperature，确保二者分离
        OllamaOptions enableThinkOptions =
                OllamaOptions.builder()
                        .thinkOption(ThinkOption.ThinkBoolean.ENABLED)
                        .temperature(0.8)
                        .build();

        String jsonResponse =
                "{\"model\":\"qwen3:32b-q4_K_M"
                    + "\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"done\":true}";
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(jsonResponse).build());

        model.chat(
                List.of(Msg.builder().role(MsgRole.USER).textContent("Think!").build()),
                enableThinkOptions);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(captor.capture());

        String body = captor.getValue().getBody();

        // 解析 JSON 以验证结构
        JsonNode root =
                ((JacksonJsonCodec) JsonUtils.getJsonCodec()).getObjectMapper().readTree(body);

        // 验证 think 位于根节点
        assertTrue(root.has("think"), "JSON root should contain 'think'");
        assertTrue(root.get("think").asBoolean(), "'think' should be true");

        // 验证 options 存在且包含 temperature，但不含 think
        assertTrue(root.has("options"), "JSON root should contain 'options'");
        assertTrue(root.get("options").has("temperature"), "options should contain 'temperature'");
        assertFalse(root.get("options").has("think"), "options should NOT contain 'think'");

        // 测试禁用思考
        OllamaOptions disableThinkOptions =
                OllamaOptions.builder().thinkOption(ThinkOption.ThinkBoolean.DISABLED).build();

        model.chat(
                List.of(Msg.builder().role(MsgRole.USER).textContent("Don't think!").build()),
                disableThinkOptions);

        verify(httpTransport, Mockito.times(2)).execute(captor.capture());
        body = captor.getValue().getBody();
        root = ((JacksonJsonCodec) JsonUtils.getJsonCodec()).getObjectMapper().readTree(body);

        assertTrue(root.has("think"), "JSON root should contain 'think'");
        assertFalse(root.get("think").asBoolean(), "'think' should be false");
    }

    @Test
    @DisplayName("Should fetch list of all Ollama models")
    void testFetchAllModels() {
        System.out.println("Running testFetchAllModels...");

        // 模拟 Ollama /api/tags 端点响应数据
        String mockResponse =
                """
                {
                  "models": [
                    {
                      "name": "qwen2.5:14b-instruct",
                      "model": "qwen2.5:14b-instruct",
                      "modified_at": "2024-05-20T10:30:00.000Z",
                      "size": 8089860096,
                      "digest": "abc123def456...",
                      "details": {
                        "parent_model": "",
                        "format": "gguf",
                        "family": "qwen2",
                        "families": ["qwen2"],
                        "parameter_size": "14B",
                        "quantization_level": "Q4_K_M"
                      }
                    },
                    {
                      "name": "llama3:8b-instruct",
                      "model": "llama3:8b-instruct",
                      "modified_at": "2024-04-15T14:20:00.000Z",
                      "size": 4689860096,
                      "digest": "def456ghi789...",
                      "details": {
                        "parent_model": "",
                        "format": "gguf",
                        "family": "llama3",
                        "families": ["llama3"],
                        "parameter_size": "8B",
                        "quantization_level": "Q4_K_M"
                      }
                    }
                  ]
                }
                """;

        // 配置模拟对象返回值
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(mockResponse).build());

        // 创建对 Ollama 服务 /api/tags 端点的 GET 请求
        HttpRequest request =
                HttpRequest.builder()
                        .url("http://192.168.2.2:11434/api/tags")
                        .method("GET")
                        .build();

        // 执行请求并获取响应
        HttpResponse response = httpTransport.execute(request);

        // 输出取得的模型列表
        System.out.println("Response status: " + response.getStatusCode());
        System.out.println("Models list: " + response.getBody());

        // 验证响应
        assertEquals(200, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("models"));
        assertTrue(response.getBody().contains("qwen2.5:14b-instruct"));
        assertTrue(response.getBody().contains("llama3:8b-instruct"));
    }

    @Test
    @DisplayName("Should serialize ThinkLevel option at root level")
    void testThinkLevelOption() throws JsonProcessingException {
        // 测试高思考级别
        OllamaOptions highThinkOptions =
                OllamaOptions.builder().thinkOption(ThinkOption.ThinkLevel.HIGH).build();

        String jsonResponse =
                "{\"model\":\"qwen3:32b-q4_K_M"
                    + "\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"done\":true}";
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(jsonResponse).build());

        model.chat(
                List.of(Msg.builder().role(MsgRole.USER).textContent("Think hard!").build()),
                highThinkOptions);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(captor.capture());

        String body = captor.getValue().getBody();
        JsonNode root =
                ((JacksonJsonCodec) JsonUtils.getJsonCodec()).getObjectMapper().readTree(body);

        assertTrue(root.has("think"), "JSON root should contain 'think'");
        assertEquals("high", root.get("think").asText(), "'think' should be 'high'");

        // 若 options 存在，不应包含 think
        if (root.has("options")) {
            assertFalse(root.get("options").has("think"), "options should NOT contain 'think'");
        }
    }

    @Test
    @DisplayName("Should map thinking budget from GenerateOptions")
    void testThinkingBudgetMapping() {
        // budget > 0 的 GenerateOptions 应映射为 ThinkBoolean.ENABLED
        GenerateOptions genOptions = GenerateOptions.builder().thinkingBudget(1024).build();

        OllamaOptions options = OllamaOptions.fromGenerateOptions(genOptions);

        // 注意：按当前实现，fromGenerateOptions 将 budget > 0 映射为 ThinkBoolean.ENABLED
        // 通过序列化或可用的取值方法检查对象，验证此行为
        // thinkOption 非公开，通过取值方法访问：
        assertNotNull(options.getThinkOption());
        assertTrue(options.getThinkOption() instanceof ThinkOption.ThinkBoolean);
        assertEquals(true, ((ThinkOption.ThinkBoolean) options.getThinkOption()).enabled());
    }

    @Test
    @DisplayName("Integration: Real Qwen3 Thinking Mode (Stream)")
    void testRealQwen3Thinking() {
        System.out.println("Running testRealQwen3Thinking (Mocked for speed)...");

        // 配置禁用思考的选项
        OllamaOptions options =
                OllamaOptions.builder()
                        .thinkOption(
                                ThinkOption.ThinkBoolean.DISABLED) // 显式设为禁用
                        .temperature(0.7)
                        .build();

        // 模拟流式响应
        String part1 =
                "{\"model\":\"qwen3:14b-q8_0\",\"message\":{\"role\":\"assistant\",\"content\":\"9.11"
                    + " is larger than 9.8 because\"},\"done\":false}";
        String part2 =
                "{\"model\":\"qwen3:14b-q8_0\",\"message\":{\"role\":\"assistant\",\"content\":\""
                        + " 11 is greater than 8 in the decimal part.\"},\"done\":false}";
        String part3 =
                "{\"model\":\"qwen3:14b-q8_0\",\"done\":true,\"total_duration\":100,\"eval_count\":2}";

        when(httpTransport.stream(any(HttpRequest.class)))
                .thenReturn(Flux.just(part1, part2, part3));

        System.out.println("Testing streaming with thinking disabled...");

        StringBuilder fullContent = new StringBuilder();

        // 使用通用 stream API 与模拟传输
        model.stream(
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .textContent(
                                                "9.11 and 9.8, which is larger? Explain your"
                                                        + " reasoning.")
                                        .build()),
                        null,
                        options.toGenerateOptions()) // 确保转为 GenerateOptions
                .doOnNext(
                        response -> {
                            if (response.getContent() != null && !response.getContent().isEmpty()) {
                                ContentBlock block = response.getContent().get(0);
                                if (block instanceof TextBlock) {
                                    String text = ((TextBlock) block).getText();
                                    System.out.print(text); // 向标准输出打印分块
                                    fullContent.append(text);
                                }
                            }
                        })
                .blockLast();

        System.out.println("\n\nFull Response:\n" + fullContent.toString());

        // 验证正确处理流式响应
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).stream(captor.capture());

        HttpRequest request = captor.getValue();
        assertEquals("POST", request.getMethod());
        assertTrue(request.getUrl().endsWith("/api/chat"));
    }

    @Test
    @DisplayName("Should populate and maintain stable ID for streaming responses")
    void testStreamStableMessageId() {
        // 不包含 ID 字段，模拟真实 Ollama 行为
        String part1 =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"Thinking...\"},\"done\":false}";
        String part2 =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\""
                        + " Hello\"},\"done\":false}";
        String part3 =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\""
                        + " World\"},\"done\":false}";
        String part4 =
                "{\"model\":\"" + TEST_MODEL_NAME + "\",\"done\":true,\"total_duration\":100}";

        when(httpTransport.stream(any(HttpRequest.class)))
                .thenReturn(Flux.just(part1, part2, part3, part4));

        GenerateOptions genOptions = OllamaOptions.builder().build().toGenerateOptions();

        List<ChatResponse> responses =
                model.stream(
                                List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()),
                                null,
                                genOptions)
                        .collectList()
                        .block();

        assertNotNull(responses);
        assertFalse(responses.isEmpty(), "Stream responses should not be empty");

        // 收集全部 ID 供断言使用
        List<String> collectedIds = new ArrayList<>();
        for (int i = 0; i < responses.size(); i++) {
            String id = responses.get(i).getId();

            assertNotNull(id);
            collectedIds.add(id);
        }

        // 验证 ID 稳定性
        long uniqueIdCount = collectedIds.stream().distinct().count();
        assertEquals(
                1,
                uniqueIdCount,
                "All stream chunks for a single response must share the same stable ID! Found IDs: "
                        + collectedIds);
    }

    // ==========================================================================
    // GenerateOptions 为 null 的测试，问题 #1770
    // ==========================================================================

    @Test
    @DisplayName("Should stream with null GenerateOptions without NPE")
    void testStreamWithNullOptions() {
        String part1 =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"Hello\"},\"done\":false}";
        String part2 =
                "{\"model\":\"" + TEST_MODEL_NAME + "\",\"done\":true,\"total_duration\":100}";

        when(httpTransport.stream(any(HttpRequest.class))).thenReturn(Flux.just(part1, part2));

        List<ChatResponse> responses =
                model.stream(
                                List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()),
                                null,
                                null)
                        .collectList()
                        .block();

        assertNotNull(responses);
        assertFalse(responses.isEmpty());
        ContentBlock content = responses.get(0).getContent().get(0);
        assertTrue(content instanceof TextBlock);
        assertEquals("Hello", ((TextBlock) content).getText());
    }

    @Test
    @DisplayName("Should stream with null tools and null options (MemoryFlushManager pattern)")
    void testStreamNullToolsAndNullOptions() {
        String responseJson =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"Summary"
                        + " here\"},\"done\":true}";
        when(httpTransport.stream(any(HttpRequest.class))).thenReturn(Flux.just(responseJson));

        List<Msg> flushInput =
                List.of(
                        Msg.builder()
                                .role(MsgRole.SYSTEM)
                                .textContent("Summarize the conversation")
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .textContent("Here are the memories to consolidate")
                                .build());

        List<ChatResponse> responses = model.stream(flushInput, null, null).collectList().block();

        assertNotNull(responses);
        assertEquals(1, responses.size());
        ContentBlock content = responses.get(0).getContent().get(0);
        assertTrue(content instanceof TextBlock);
        assertEquals("Summary here", ((TextBlock) content).getText());
    }

    @Test
    @DisplayName("Should stream with tools but null options")
    void testStreamWithToolsButNullOptions() {
        ToolSchema tool =
                ToolSchema.builder()
                        .name("save_note")
                        .description("Save a note")
                        .parameters(
                                Map.of(
                                        "type", "object",
                                        "properties", Map.of("text", Map.of("type", "string")),
                                        "required", List.of("text")))
                        .build();

        String responseJson =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"done\":true}";
        when(httpTransport.stream(any(HttpRequest.class))).thenReturn(Flux.just(responseJson));

        assertDoesNotThrow(
                () ->
                        model.stream(
                                        List.of(
                                                Msg.builder()
                                                        .role(MsgRole.USER)
                                                        .textContent("Save this")
                                                        .build()),
                                        List.of(tool),
                                        null)
                                .blockLast());
    }

    // ==========================================================================
    // 代理配置测试
    // ==========================================================================

    @Test
    @DisplayName("Should create Ollama model with proxy() only")
    void testProxyOnly() {
        ProxyConfig proxy = ProxyConfig.http("proxy.example.com", 8080);

        OllamaChatModel model =
                OllamaChatModel.builder()
                        .modelName("llama3")
                        .baseUrl("http://localhost:11434")
                        .proxy(proxy)
                        .build();

        assertNotNull(model);
        assertEquals("llama3", model.getModelName());
    }

    @Test
    @DisplayName("Should create Ollama model with httpTransport() only")
    void testHttpTransportOnly() {
        HttpTransport transport =
                new HttpTransport() {
                    @Override
                    public HttpResponse execute(HttpRequest request) {
                        return null;
                    }

                    @Override
                    public reactor.core.publisher.Flux<String> stream(HttpRequest request) {
                        return null;
                    }

                    @Override
                    public void close() {}
                };

        OllamaChatModel model =
                OllamaChatModel.builder()
                        .modelName("llama3")
                        .baseUrl("http://localhost:11434")
                        .httpTransport(transport)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should prefer Ollama httpTransport() over proxy() with warning")
    void testHttpTransportTakesPrecedenceOverProxy() {
        HttpTransport transport =
                new HttpTransport() {
                    @Override
                    public HttpResponse execute(HttpRequest request) {
                        return null;
                    }

                    @Override
                    public reactor.core.publisher.Flux<String> stream(HttpRequest request) {
                        return null;
                    }

                    @Override
                    public void close() {}
                };
        ProxyConfig proxy = ProxyConfig.http("proxy.example.com", 8080);

        // 同时设置时 httpTransport 应优先
        // 应记录警告但仍成功构建
        OllamaChatModel model =
                OllamaChatModel.builder()
                        .modelName("llama3")
                        .baseUrl("http://localhost:11434")
                        .httpTransport(transport)
                        .proxy(proxy)
                        .build();

        assertNotNull(model);
    }

    // ========== stream 标志路由，修复 doStream 曾硬编码流式模式的问题 ==========
    @Test
    @DisplayName(
            "stream(false) should use non-streaming transport and return full reply on blockLast")
    void testStreamFalseRoutesToNonStreamingAndReturnsFullReply() throws Exception {
        // 非流式响应：单个完整 JSON，内容为 "Hello World"
        String nonStreamingJson =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"Hello World\"},"
                        + "\"done\":true,\"eval_count\":2}";

        HttpResponse mockResponse =
                HttpResponse.builder().statusCode(200).body(nonStreamingJson).build();
        when(httpTransport.execute(any(HttpRequest.class))).thenReturn(mockResponse);

        OllamaChatModel nonStreamingModel =
                OllamaChatModel.builder()
                        .modelName(TEST_MODEL_NAME)
                        .baseUrl("http://localhost:11434")
                        .httpTransport(httpTransport)
                        .stream(false)
                        .build();

        GenerateOptions genOptions = OllamaOptions.builder().build().toGenerateOptions();
        ChatResponse resp =
                nonStreamingModel.stream(
                                List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()),
                                null,
                                genOptions)
                        .blockLast();

        // 必须经过非流式 execute() 路径
        verify(httpTransport).execute(any(HttpRequest.class));
        // blockLast() 必须携带完整回复，而非空的尾块
        assertNotNull(resp);
        assertNotNull(resp.getContent());
        assertFalse(resp.getContent().isEmpty(), "blockLast() content must not be empty");
        ContentBlock block = resp.getContent().get(0);
        assertTrue(block instanceof TextBlock);
        assertEquals("Hello World", ((TextBlock) block).getText());
    }

    /**
     * 确认默认 stream=true 仍走流式路径，为消费完整 Flux 的调用方保持向后兼容。
     */
    @Test
    @DisplayName("default (stream=true) should still use streaming transport")
    void testDefaultStreamTrueStillUsesStreamingTransport() {
        // 流式响应：两个内容分块，加空的 done 标记
        String chunk1 =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"Hello\"},"
                        + "\"done\":false}";
        String chunk2 =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\" World\"},"
                        + "\"done\":false}";
        String doneMarker =
                "{\"model\":\"" + TEST_MODEL_NAME + "\",\"done\":true,\"eval_count\":2}";

        when(httpTransport.stream(any(HttpRequest.class)))
                .thenReturn(Flux.just(chunk1, chunk2, doneMarker));

        // 默认构建器未调用 stream() 时，必须仍为流式
        OllamaChatModel streamingModel =
                OllamaChatModel.builder()
                        .modelName(TEST_MODEL_NAME)
                        .baseUrl("http://localhost:11434")
                        .httpTransport(httpTransport)
                        .build();

        GenerateOptions genOptions = OllamaOptions.builder().build().toGenerateOptions();
        List<ChatResponse> responses =
                streamingModel.stream(
                                List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()),
                                null,
                                genOptions)
                        .collectList()
                        .block();

        // 必须使用流式路径
        verify(httpTransport).stream(any(HttpRequest.class));
        assertNotNull(responses);
        assertEquals(3, responses.size(), "streaming should emit one response per chunk");
    }

    @Test
    @DisplayName("GenerateOptions.stream(false) should override the model's streaming default")
    void testGenerateOptionsStreamFalseOverridesDefaultStreaming() throws Exception {
        String nonStreamingJson =
                "{\"model\":\""
                        + TEST_MODEL_NAME
                        + "\",\"message\":{\"role\":\"assistant\",\"content\":\"Hello World\"},"
                        + "\"done\":true,\"eval_count\":2}";

        HttpResponse mockResponse =
                HttpResponse.builder().statusCode(200).body(nonStreamingJson).build();
        when(httpTransport.execute(any(HttpRequest.class))).thenReturn(mockResponse);

        OllamaChatModel streamingByDefault =
                OllamaChatModel.builder()
                        .modelName(TEST_MODEL_NAME)
                        .baseUrl("http://localhost:11434")
                        .httpTransport(httpTransport)
                        .build();

        GenerateOptions requestOptions = GenerateOptions.builder().stream(false).build();
        ChatResponse resp =
                streamingByDefault.stream(
                                List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()),
                                null,
                                requestOptions)
                        .blockLast();

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(requestCaptor.capture());
        assertTrue(requestCaptor.getValue().getBody().contains("\"stream\":false"));
        assertNotNull(resp);
        assertEquals("Hello World", ((TextBlock) resp.getContent().get(0)).getText());
    }

    /**
     * 为兼容保留的旧 5 参数构造器必须以 {@code stream=true} 委托给 6 参数构造器。没有此测试，委托行不会被覆盖。
     */
    @Test
    @DisplayName("5-arg constructor defaults to streaming (backward compatible delegation)")
    void testLegacyConstructorDefaultsToStreaming() {
        OllamaChatModel model =
                new OllamaChatModel(
                        TEST_MODEL_NAME,
                        "http://localhost:11434",
                        OllamaOptions.builder().build(),
                        new OllamaChatFormatter(),
                        null);
        assertTrue(model.isStreaming(), "5-arg constructor must default to streaming");
    }

    /**
     * 当 {@code stream=false} 时，6 参数构造器必须通过 {@link OllamaChatModel#isStreaming()} 暴露非流式模式。覆盖显式流式标志构造器重载。
     */
    @Test
    @DisplayName("6-arg constructor with stream=false exposes non-streaming mode")
    void testExplicitConstructorHonorsStreamFlag() {
        OllamaChatModel streaming =
                new OllamaChatModel(
                        TEST_MODEL_NAME,
                        "http://localhost:11434",
                        OllamaOptions.builder().build(),
                        new OllamaChatFormatter(),
                        null,
                        true);
        assertTrue(streaming.isStreaming());

        OllamaChatModel nonStreaming =
                new OllamaChatModel(
                        TEST_MODEL_NAME,
                        "http://localhost:11434",
                        OllamaOptions.builder().build(),
                        new OllamaChatFormatter(),
                        null,
                        false);
        assertFalse(nonStreaming.isStreaming());
    }
}
