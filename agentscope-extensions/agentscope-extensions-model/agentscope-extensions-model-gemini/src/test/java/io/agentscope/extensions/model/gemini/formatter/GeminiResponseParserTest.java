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
package io.agentscope.extensions.model.gemini.formatter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.types.Candidate;
import com.google.genai.types.CodeExecutionResult;
import com.google.genai.types.Content;
import com.google.genai.types.ExecutableCode;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.GroundingMetadata;
import com.google.genai.types.Language;
import com.google.genai.types.Outcome;
import com.google.genai.types.Part;
import com.google.genai.types.ToolCall;
import com.google.genai.types.ToolResponse;
import com.google.genai.types.ToolType;
import com.google.genai.types.UrlContextMetadata;
import com.google.genai.types.UrlMetadata;
import com.google.genai.types.UrlRetrievalStatus;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * GeminiResponseParser 单元测试。
 */
class GeminiResponseParserTest {

    private final GeminiResponseParser parser = new GeminiResponseParser();
    private final Instant startTime = Instant.now();

    @Test
    void testParseSimpleTextResponse() {
        // 构建响应
        Part textPart = Part.builder().text("Hello, how can I help you?").build();

        Content content = Content.builder().role("model").parts(List.of(textPart)).build();

        Candidate candidate = Candidate.builder().content(content).build();

        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-123")
                        .candidates(List.of(candidate))
                        .build();

        // 解析
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // 验证
        assertNotNull(chatResponse);
        assertEquals("response-123", chatResponse.getId());
        assertEquals(1, chatResponse.getContent().size());

        ContentBlock block = chatResponse.getContent().get(0);
        assertInstanceOf(TextBlock.class, block);
        assertEquals("Hello, how can I help you?", ((TextBlock) block).getText());
    }

    @Test
    void testParseFinishReasonUsesTheFinishReasonCode() {
        Part textPart = Part.builder().text("Cut off").build();
        Content content = Content.builder().role("model").parts(List.of(textPart)).build();
        Candidate candidate =
                Candidate.builder()
                        .content(content)
                        .finishReason(FinishReason.Known.MAX_TOKENS)
                        .finishMessage("The maximum number of tokens was reached.")
                        .build();
        GenerateContentResponse response =
                GenerateContentResponse.builder().candidates(List.of(candidate)).build();

        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        assertEquals("MAX_TOKENS", chatResponse.getFinishReason());
    }

    @Test
    void testParseFinishReasonIsNullWhenTheCandidateHasNone() {
        Part textPart = Part.builder().text("Hello").build();
        Content content = Content.builder().role("model").parts(List.of(textPart)).build();
        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .candidates(List.of(Candidate.builder().content(content).build()))
                        .build();

        assertNull(parser.parseResponse(response, startTime).getFinishReason());
    }

    @Test
    void testParseThinkingResponse() {
        // 构建含思考内容的响应，thought=true
        Part thinkingPart =
                Part.builder().text("Let me think about this problem...").thought(true).build();

        Part textPart = Part.builder().text("The answer is 42.").build();

        Content content =
                Content.builder().role("model").parts(List.of(thinkingPart, textPart)).build();

        Candidate candidate = Candidate.builder().content(content).build();

        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-456")
                        .candidates(List.of(candidate))
                        .build();

        // 解析
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // 验证
        assertNotNull(chatResponse);
        assertEquals(2, chatResponse.getContent().size());

        // 第一个应为 ThinkingBlock
        ContentBlock block1 = chatResponse.getContent().get(0);
        assertInstanceOf(ThinkingBlock.class, block1);
        assertEquals("Let me think about this problem...", ((ThinkingBlock) block1).getThinking());

        // 第二个应为 TextBlock
        ContentBlock block2 = chatResponse.getContent().get(1);
        assertInstanceOf(TextBlock.class, block2);
        assertEquals("The answer is 42.", ((TextBlock) block2).getText());
    }

    @Test
    void testParseToolCallResponse() {
        // 构建含函数调用的响应
        Map<String, Object> args = new HashMap<>();
        args.put("city", "Tokyo");

        FunctionCall functionCall =
                FunctionCall.builder().id("call-123").name("get_weather").args(args).build();

        Part functionCallPart = Part.builder().functionCall(functionCall).build();

        Content content = Content.builder().role("model").parts(List.of(functionCallPart)).build();

        Candidate candidate = Candidate.builder().content(content).build();

        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-789")
                        .candidates(List.of(candidate))
                        .build();

        // 解析
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // 验证
        assertNotNull(chatResponse);
        assertEquals(1, chatResponse.getContent().size());

        ContentBlock block = chatResponse.getContent().get(0);
        assertInstanceOf(ToolUseBlock.class, block);

        ToolUseBlock toolUse = (ToolUseBlock) block;
        assertEquals("call-123", toolUse.getId());
        assertEquals("get_weather", toolUse.getName());
        assertEquals("Tokyo", toolUse.getInput().get("city"));
        assertFalse(toolUse.isServerTool());
    }

    @Test
    void testParseMixedContentResponse() {
        // 构建含思考、文本与工具调用的响应
        Part thinkingPart =
                Part.builder().text("I need to check the weather first.").thought(true).build();

        Map<String, Object> args = new HashMap<>();
        args.put("city", "Tokyo");

        FunctionCall functionCall =
                FunctionCall.builder().id("call-456").name("get_weather").args(args).build();

        Part functionCallPart = Part.builder().functionCall(functionCall).build();

        Part textPart = Part.builder().text("Let me check that for you.").build();

        Content content =
                Content.builder()
                        .role("model")
                        .parts(List.of(thinkingPart, textPart, functionCallPart))
                        .build();

        Candidate candidate = Candidate.builder().content(content).build();

        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-mixed")
                        .candidates(List.of(candidate))
                        .build();

        // 解析
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // 验证
        assertNotNull(chatResponse);
        assertEquals(3, chatResponse.getContent().size());

        // 第一块：ThinkingBlock
        assertInstanceOf(ThinkingBlock.class, chatResponse.getContent().get(0));
        assertEquals(
                "I need to check the weather first.",
                ((ThinkingBlock) chatResponse.getContent().get(0)).getThinking());

        // 第二块：TextBlock
        assertInstanceOf(TextBlock.class, chatResponse.getContent().get(1));
        assertEquals(
                "Let me check that for you.",
                ((TextBlock) chatResponse.getContent().get(1)).getText());

        // 第三块：ToolUseBlock
        assertInstanceOf(ToolUseBlock.class, chatResponse.getContent().get(2));
        ToolUseBlock toolUse = (ToolUseBlock) chatResponse.getContent().get(2);
        assertEquals("get_weather", toolUse.getName());
    }

    @Test
    void testParseUsageMetadata() {
        // 构建含用量元数据的响应
        Part textPart = Part.builder().text("Response text").build();

        Content content = Content.builder().role("model").parts(List.of(textPart)).build();

        Candidate candidate = Candidate.builder().content(content).build();

        GenerateContentResponseUsageMetadata usageMetadata =
                GenerateContentResponseUsageMetadata.builder()
                        .promptTokenCount(100)
                        .candidatesTokenCount(60)
                        .thoughtsTokenCount(10) // 思考 token
                        .totalTokenCount(170)
                        .build();

        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-usage")
                        .candidates(List.of(candidate))
                        .usageMetadata(usageMetadata)
                        .build();

        // 解析
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // 验证用量
        assertNotNull(chatResponse.getUsage());
        ChatUsage usage = chatResponse.getUsage();

        // 输入 token 数 = promptTokenCount
        assertEquals(100, usage.getInputTokens());
        assertEquals(0, usage.getToolUsePromptTokens());

        // 输出 token 数包含候选内容及模型生成的思考 token。
        assertEquals(70, usage.getOutputTokens());
        assertEquals(10, usage.getReasoningTokens());

        // 耗时应 > 0
        assertTrue(usage.getTime() >= 0);
    }

    @Test
    void testParseUsageMetadataClassifiesToolUseTokensAsInput() {
        GenerateContentResponseUsageMetadata usageMetadata =
                GenerateContentResponseUsageMetadata.builder()
                        .promptTokenCount(500)
                        .candidatesTokenCount(120)
                        .toolUsePromptTokenCount(300)
                        .thoughtsTokenCount(10)
                        .totalTokenCount(930)
                        .build();

        GenerateContentResponse response =
                GenerateContentResponse.builder().usageMetadata(usageMetadata).build();

        ChatUsage usage = parser.parseResponse(response, startTime).getUsage();

        assertNotNull(usage);
        assertEquals(800, usage.getInputTokens());
        assertEquals(300, usage.getToolUsePromptTokens());
        assertEquals(130, usage.getOutputTokens());
    }

    @Test
    void testParseUsageMetadataIgnoresTotalWhenCandidateCountIsMissing() {
        GenerateContentResponseUsageMetadata usageMetadata =
                GenerateContentResponseUsageMetadata.builder()
                        .promptTokenCount(500)
                        .toolUsePromptTokenCount(300)
                        .thoughtsTokenCount(10)
                        .totalTokenCount(930)
                        .build();

        GenerateContentResponse response =
                GenerateContentResponse.builder().usageMetadata(usageMetadata).build();

        ChatUsage usage = parser.parseResponse(response, startTime).getUsage();

        assertNotNull(usage);
        assertEquals(800, usage.getInputTokens());
        assertEquals(10, usage.getOutputTokens());
    }

    @Test
    void testParseUsageMetadataUsesThinkingWhenCandidateAndTotalCountsAreMissing() {
        GenerateContentResponseUsageMetadata usageMetadata =
                GenerateContentResponseUsageMetadata.builder()
                        .promptTokenCount(500)
                        .thoughtsTokenCount(10)
                        .build();

        GenerateContentResponse response =
                GenerateContentResponse.builder().usageMetadata(usageMetadata).build();

        ChatUsage usage = parser.parseResponse(response, startTime).getUsage();

        assertNotNull(usage);
        assertEquals(500, usage.getInputTokens());
        assertEquals(10, usage.getOutputTokens());
    }

    @Test
    void testParseUsageMetadataReadsCachedContentTokenCount() {
        // Gemini 报告的 cachedContentTokenCount 必须透传到 ChatUsage.cachedTokens,
        // 否则下游记账无法识别缓存命中、定价会按全量提示词估算。
        Part textPart = Part.builder().text("Response text").build();

        Content content = Content.builder().role("model").parts(List.of(textPart)).build();

        Candidate candidate = Candidate.builder().content(content).build();

        GenerateContentResponseUsageMetadata usageMetadata =
                GenerateContentResponseUsageMetadata.builder()
                        // cachedContentTokenCount 是 promptTokenCount 的子集(Gemini SDK 文档:
                        // promptTokenCount 包含 cachedContentTokenCount),故 prompt 必须 > cached
                        .promptTokenCount(500)
                        .candidatesTokenCount(60)
                        .thoughtsTokenCount(10)
                        .totalTokenCount(560)
                        .cachedContentTokenCount(300)
                        .build();

        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-cached")
                        .candidates(List.of(candidate))
                        .usageMetadata(usageMetadata)
                        .build();

        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        assertNotNull(chatResponse.getUsage());
        assertEquals(300, chatResponse.getUsage().getCachedTokens());
        assertEquals(10, chatResponse.getUsage().getReasoningTokens());
        assertEquals(0, chatResponse.getUsage().getToolUsePromptTokens());
    }

    @Test
    void testParseEmptyResponse() {
        // 构建空响应，不含候选项
        GenerateContentResponse response =
                GenerateContentResponse.builder().responseId("response-empty").build();

        // 解析
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // 验证
        assertNotNull(chatResponse);
        assertEquals("response-empty", chatResponse.getId());
        assertEquals(0, chatResponse.getContent().size());
    }

    @Test
    void testParseResponseWithoutId() {
        // 构建无 responseId 的响应
        Part textPart = Part.builder().text("Hello").build();

        Content content = Content.builder().role("model").parts(List.of(textPart)).build();

        Candidate candidate = Candidate.builder().content(content).build();

        GenerateContentResponse response =
                GenerateContentResponse.builder().candidates(List.of(candidate)).build();

        // 解析
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // 验证应平稳处理 null ID
        assertNotNull(chatResponse);
        assertEquals(1, chatResponse.getContent().size());
    }

    @Test
    void testParseGroundingAndUrlContextMetadata() {
        Part textPart = Part.builder().text("Search result").build();
        Content content = Content.builder().role("model").parts(List.of(textPart)).build();

        GroundingMetadata groundingMetadata =
                GroundingMetadata.builder()
                        .webSearchQueries(List.of("agentscope java"))
                        .googleMapsWidgetContextToken("maps-token")
                        .build();
        UrlContextMetadata urlContextMetadata =
                UrlContextMetadata.builder()
                        .urlMetadata(
                                List.of(
                                        UrlMetadata.builder()
                                                .retrievedUrl("https://example.com")
                                                .urlRetrievalStatus(
                                                        UrlRetrievalStatus.Known
                                                                .URL_RETRIEVAL_STATUS_SUCCESS)
                                                .build()))
                        .build();

        Candidate candidate =
                Candidate.builder()
                        .content(content)
                        .groundingMetadata(groundingMetadata)
                        .urlContextMetadata(urlContextMetadata)
                        .build();
        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-grounding")
                        .candidates(List.of(candidate))
                        .build();

        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        assertNotNull(chatResponse.getMetadata());
        Map<String, Object> grounding =
                assertInstanceOf(
                        Map.class,
                        chatResponse.getMetadata().get(GeminiResponseParser.METADATA_GROUNDING));
        assertEquals(List.of("agentscope java"), grounding.get("webSearchQueries"));
        assertEquals("maps-token", grounding.get("googleMapsWidgetContextToken"));

        Map<String, Object> urlContext =
                assertInstanceOf(
                        Map.class,
                        chatResponse.getMetadata().get(GeminiResponseParser.METADATA_URL_CONTEXT));
        List<?> urls = assertInstanceOf(List.class, urlContext.get("urlMetadata"));
        assertEquals(1, urls.size());
        Map<?, ?> url = assertInstanceOf(Map.class, urls.get(0));
        assertEquals("https://example.com", url.get("retrievedUrl"));
        assertEquals("URL_RETRIEVAL_STATUS_SUCCESS", url.get("urlRetrievalStatus").toString());
    }

    @Test
    void testParseToolCallWithoutId() {
        // 构建未显式指定 ID 的函数调用
        Map<String, Object> args = new HashMap<>();
        args.put("query", "test");

        FunctionCall functionCall = FunctionCall.builder().name("search").args(args).build();

        Part functionCallPart = Part.builder().functionCall(functionCall).build();

        Content content = Content.builder().role("model").parts(List.of(functionCallPart)).build();

        Candidate candidate = Candidate.builder().content(content).build();

        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-no-tool-id")
                        .candidates(List.of(candidate))
                        .build();

        // 解析
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // 验证应生成 ID
        assertNotNull(chatResponse);
        assertEquals(1, chatResponse.getContent().size());

        ToolUseBlock toolUse = (ToolUseBlock) chatResponse.getContent().get(0);
        assertNotNull(toolUse.getId());
        assertTrue(toolUse.getId().startsWith("tool_call_"));
        assertEquals("search", toolUse.getName());
    }

    @Test
    void testParseToolCallWithThoughtSignature() {
        // 构建含思考签名的函数调用，适用于 Gemini 3 Pro
        Map<String, Object> args = new HashMap<>();
        args.put("city", "Tokyo");

        FunctionCall functionCall =
                FunctionCall.builder().id("call-with-sig").name("get_weather").args(args).build();

        byte[] thoughtSignature = "test-signature-bytes".getBytes();
        Part functionCallPart =
                Part.builder()
                        .functionCall(functionCall)
                        .thoughtSignature(thoughtSignature)
                        .build();

        Content content = Content.builder().role("model").parts(List.of(functionCallPart)).build();

        Candidate candidate = Candidate.builder().content(content).build();

        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-with-sig")
                        .candidates(List.of(candidate))
                        .build();

        // 解析
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // 验证
        assertNotNull(chatResponse);
        assertEquals(1, chatResponse.getContent().size());

        ToolUseBlock toolUse = (ToolUseBlock) chatResponse.getContent().get(0);
        assertEquals("call-with-sig", toolUse.getId());
        assertEquals("get_weather", toolUse.getName());

        // 验证思考签名存于元数据
        assertNotNull(toolUse.getMetadata());
        assertTrue(toolUse.getMetadata().containsKey(ToolUseBlock.METADATA_THOUGHT_SIGNATURE));
        byte[] extractedSig =
                (byte[]) toolUse.getMetadata().get(ToolUseBlock.METADATA_THOUGHT_SIGNATURE);
        assertArrayEquals(thoughtSignature, extractedSig);
    }

    @Test
    void testParseToolCallWithoutThoughtSignature() {
        // 构建无思考签名的函数调用
        Map<String, Object> args = new HashMap<>();
        args.put("city", "London");

        FunctionCall functionCall =
                FunctionCall.builder().id("call-no-sig").name("get_weather").args(args).build();

        Part functionCallPart = Part.builder().functionCall(functionCall).build();

        Content content = Content.builder().role("model").parts(List.of(functionCallPart)).build();

        Candidate candidate = Candidate.builder().content(content).build();

        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-no-sig")
                        .candidates(List.of(candidate))
                        .build();

        // 解析
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // 验证元数据应为空，不含 thoughtSignature
        assertNotNull(chatResponse);
        assertEquals(1, chatResponse.getContent().size());

        ToolUseBlock toolUse = (ToolUseBlock) chatResponse.getContent().get(0);
        assertTrue(toolUse.getMetadata().isEmpty());
    }

    @Test
    void testParseParallelFunctionCallsWithThoughtSignature() {
        // Gemini 3 Pro 并行函数调用：仅第一个有思考签名
        Map<String, Object> args1 = new HashMap<>();
        args1.put("city", "Paris");

        Map<String, Object> args2 = new HashMap<>();
        args2.put("city", "London");

        byte[] thoughtSignature = "parallel-sig".getBytes();

        // 第一个函数调用带签名
        FunctionCall fc1 =
                FunctionCall.builder().id("call-1").name("get_weather").args(args1).build();
        Part part1 = Part.builder().functionCall(fc1).thoughtSignature(thoughtSignature).build();

        // 第二个函数调用无签名
        FunctionCall fc2 =
                FunctionCall.builder().id("call-2").name("get_weather").args(args2).build();
        Part part2 = Part.builder().functionCall(fc2).build();

        Content content = Content.builder().role("model").parts(List.of(part1, part2)).build();

        Candidate candidate = Candidate.builder().content(content).build();

        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-parallel")
                        .candidates(List.of(candidate))
                        .build();

        // 解析
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // 验证
        assertNotNull(chatResponse);
        assertEquals(2, chatResponse.getContent().size());

        // 第一个工具调用应含签名
        ToolUseBlock toolUse1 = (ToolUseBlock) chatResponse.getContent().get(0);
        assertEquals("call-1", toolUse1.getId());
        assertTrue(toolUse1.getMetadata().containsKey(ToolUseBlock.METADATA_THOUGHT_SIGNATURE));

        // 第二个工具调用不应含签名
        ToolUseBlock toolUse2 = (ToolUseBlock) chatResponse.getContent().get(1);
        assertEquals("call-2", toolUse2.getId());
        assertTrue(toolUse2.getMetadata().isEmpty());
    }

    @Test
    void testParseMixedServerAndLocalTools() {
        // 构建混合服务端工具和本地函数调用的响应
        Map<String, Object> searchArgs = new HashMap<>();
        searchArgs.put("queries", List.of("southernmost city in China"));

        ToolCall toolCall =
                ToolCall.builder()
                        .id("search-call")
                        .toolType(ToolType.Known.GOOGLE_SEARCH_WEB)
                        .args(searchArgs)
                        .build();
        Part toolCallPart =
                Part.builder()
                        .toolCall(toolCall)
                        .thoughtSignature("server-call-signature".getBytes())
                        .build();

        Map<String, Object> searchResult = new HashMap<>();
        searchResult.put("search_suggestions", "Sansha");
        ToolResponse toolResponse =
                ToolResponse.builder()
                        .id("search-call")
                        .toolType(ToolType.Known.GOOGLE_SEARCH_WEB)
                        .response(searchResult)
                        .build();
        Part toolResponsePart =
                Part.builder()
                        .toolResponse(toolResponse)
                        .thoughtSignature("server-result-signature".getBytes())
                        .build();

        Map<String, Object> weatherArgs = new HashMap<>();
        weatherArgs.put("location", "Sansha, China");
        FunctionCall functionCall =
                FunctionCall.builder()
                        .id("weather-call")
                        .name("getWeather")
                        .args(weatherArgs)
                        .build();
        Part functionCallPart = Part.builder().functionCall(functionCall).build();

        Content content =
                Content.builder()
                        .role("model")
                        .parts(List.of(toolCallPart, toolResponsePart, functionCallPart))
                        .build();

        Candidate candidate = Candidate.builder().content(content).build();

        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-mixed-tools")
                        .candidates(List.of(candidate))
                        .build();

        // 解析
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // 验证
        assertNotNull(chatResponse);
        assertEquals(3, chatResponse.getContent().size());

        // 第一块：服务端工具使用
        ToolUseBlock serverToolUse = (ToolUseBlock) chatResponse.getContent().get(0);
        assertEquals("search-call", serverToolUse.getId());
        assertEquals("GOOGLE_SEARCH_WEB", serverToolUse.getName());
        assertTrue(serverToolUse.isServerTool());
        assertArrayEquals(
                "server-call-signature".getBytes(),
                (byte[]) serverToolUse.getMetadata().get(ToolUseBlock.METADATA_THOUGHT_SIGNATURE));

        // 第二块：服务端工具结果
        ToolResultBlock serverToolResult = (ToolResultBlock) chatResponse.getContent().get(1);
        assertEquals("search-call", serverToolResult.getId());
        assertEquals("GOOGLE_SEARCH_WEB", serverToolResult.getName());
        assertTrue(serverToolResult.isServerTool());
        assertEquals(ToolResultState.SUCCESS, serverToolResult.getState());
        assertArrayEquals(
                "server-result-signature".getBytes(),
                (byte[])
                        serverToolResult
                                .getMetadata()
                                .get(ToolUseBlock.METADATA_THOUGHT_SIGNATURE));
        String rawJson =
                assertInstanceOf(
                        String.class,
                        serverToolResult
                                .getMetadata()
                                .get(GeminiResponseParser.METADATA_SERVER_TOOL_RESPONSE));
        assertTrue(rawJson.contains("search-call"));
        assertTrue(rawJson.contains("Sansha"));

        // 第三块：本地函数调用
        ToolUseBlock localToolUse = (ToolUseBlock) chatResponse.getContent().get(2);
        assertEquals("weather-call", localToolUse.getId());
        assertEquals("getWeather", localToolUse.getName());
        assertFalse(localToolUse.isServerTool());
    }

    @Test
    void testParseCodeExecutionResponse() {
        ExecutableCode executableCode =
                ExecutableCode.builder()
                        .id("code-execution-1")
                        .language(Language.Known.PYTHON)
                        .code("print(1 + 1)")
                        .build();
        Part executableCodePart =
                Part.builder()
                        .executableCode(executableCode)
                        .thoughtSignature("code-signature".getBytes())
                        .build();

        CodeExecutionResult codeExecutionResult =
                CodeExecutionResult.builder()
                        .id("code-execution-1")
                        .outcome(Outcome.Known.OUTCOME_OK)
                        .output("2")
                        .build();
        Part codeExecutionResultPart =
                Part.builder()
                        .codeExecutionResult(codeExecutionResult)
                        .thoughtSignature("result-signature".getBytes())
                        .build();

        Content content =
                Content.builder()
                        .role("model")
                        .parts(List.of(executableCodePart, codeExecutionResultPart))
                        .build();
        Candidate candidate = Candidate.builder().content(content).build();
        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-code-execution")
                        .candidates(List.of(candidate))
                        .build();

        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        assertNotNull(chatResponse);
        assertEquals(2, chatResponse.getContent().size());

        ToolUseBlock executableCodeBlock = (ToolUseBlock) chatResponse.getContent().get(0);
        assertEquals("code-execution-1", executableCodeBlock.getId());
        assertEquals("CODE_EXECUTION", executableCodeBlock.getName());
        assertEquals("print(1 + 1)", executableCodeBlock.getInput().get("code"));
        assertEquals("PYTHON", executableCodeBlock.getInput().get("language"));
        assertTrue(executableCodeBlock.isServerTool());
        assertTrue(
                executableCodeBlock
                        .getMetadata()
                        .containsKey(GeminiResponseParser.METADATA_CODE_EXECUTION));
        assertEquals(ToolCallState.FINISHED, executableCodeBlock.getState());
        assertArrayEquals(
                "code-signature".getBytes(),
                (byte[])
                        executableCodeBlock
                                .getMetadata()
                                .get(ToolUseBlock.METADATA_THOUGHT_SIGNATURE));

        ToolResultBlock resultBlock = (ToolResultBlock) chatResponse.getContent().get(1);
        assertEquals("code-execution-1", resultBlock.getId());
        assertEquals("CODE_EXECUTION", resultBlock.getName());
        assertEquals(ToolResultState.SUCCESS, resultBlock.getState());
        assertEquals("2", ((TextBlock) resultBlock.getOutput().get(0)).getText());
        assertTrue(resultBlock.isServerTool());
        assertEquals(
                "OUTCOME_OK",
                resultBlock
                        .getMetadata()
                        .get(GeminiResponseParser.METADATA_CODE_EXECUTION_OUTCOME));
        assertArrayEquals(
                "result-signature".getBytes(),
                (byte[]) resultBlock.getMetadata().get(ToolUseBlock.METADATA_THOUGHT_SIGNATURE));
    }

    @Test
    void testParseFailedCodeExecutionResult() {
        CodeExecutionResult codeExecutionResult =
                CodeExecutionResult.builder()
                        .id("code-execution-failed")
                        .outcome(Outcome.Known.OUTCOME_FAILED)
                        .output("NameError")
                        .build();
        Part part = Part.builder().codeExecutionResult(codeExecutionResult).build();
        Content content = Content.builder().role("model").parts(List.of(part)).build();
        Candidate candidate = Candidate.builder().content(content).build();
        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-code-execution-failed")
                        .candidates(List.of(candidate))
                        .build();

        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        ToolResultBlock resultBlock = (ToolResultBlock) chatResponse.getContent().get(0);
        assertEquals(ToolResultState.ERROR, resultBlock.getState());
        assertEquals(
                "OUTCOME_FAILED",
                resultBlock
                        .getMetadata()
                        .get(GeminiResponseParser.METADATA_CODE_EXECUTION_OUTCOME));
    }
}
