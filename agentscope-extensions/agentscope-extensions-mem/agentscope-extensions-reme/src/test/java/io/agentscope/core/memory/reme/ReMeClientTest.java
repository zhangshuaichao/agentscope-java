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
package io.agentscope.core.memory.reme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.util.JsonException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * {@link ReMeClient} 单元测试。
 */
class ReMeClientTest {

    private MockWebServer mockServer;
    private ReMeClient client;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
        String baseUrl = mockServer.url("/").toString();
        // 移除末尾斜杠。
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        client = new ReMeClient(baseUrl);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.shutdown();
        }
        if (mockServer != null) {
            mockServer.shutdown();
        }
    }

    @Test
    void testConstructorWithBaseUrl() {
        String baseUrl = "http://localhost:8002";
        ReMeClient client = new ReMeClient(baseUrl);
        assertNotNull(client);
        client.shutdown();
    }

    @Test
    void testConstructorWithTrailingSlash() {
        String baseUrl = "http://localhost:8002/";
        ReMeClient client = new ReMeClient(baseUrl);
        assertNotNull(client);
        client.shutdown();
    }

    @Test
    void testConstructorWithCustomTimeout() {
        String baseUrl = "http://localhost:8002";
        ReMeClient client = new ReMeClient(baseUrl, Duration.ofSeconds(30));
        assertNotNull(client);
        client.shutdown();
    }

    @Test
    void testAddRequestSuccess() throws Exception {
        // 模拟符合实际 API 格式的成功响应。
        String responseJson =
                "{\"answer\":\"\",\"success\":true,\"metadata\":{\"memory_list\":[{"
                    + "\"workspace_id\":\"task_workspace\",\"memory_id\":\"test_memory_id\",\"memory_type\":\"personal\",\"when_to_use\":\"coffee,"
                    + " morning, work\",\"content\":\"user drinks coffee in the morning while"
                    + " working\",\"score\":0.0,\"time_created\":\"2025-12-10"
                    + " 10:30:43\",\"time_modified\":\"2025-12-10"
                    + " 10:30:43\",\"author\":\"test-author\",\"metadata\":{\"keywords\":\"coffee,"
                    + " morning, work\",\"time_info\":\"\",\"source_message\":\"I like to drink"
                    + " coffee while working in the"
                    + " morning\",\"observation_type\":\"personal_info_with_time\"},"
                    + "\"target\":\"user\",\"reflection_subject\":\"\"}],\"deleted_memory_ids\":[],"
                    + "\"update_result\":{\"deleted_count\":0,\"inserted_count\":1}}}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        // 创建请求。
        ReMeMessage message1 =
                ReMeMessage.builder()
                        .role("user")
                        .content("I like to drink coffee while working in the morning")
                        .build();
        ReMeMessage message2 =
                ReMeMessage.builder()
                        .role("assistant")
                        .content(
                                "I understand, you prefer to start your workday with coffee to stay"
                                        + " energized")
                        .build();

        ReMeTrajectory trajectory =
                ReMeTrajectory.builder().messages(List.of(message1, message2)).build();

        ReMeAddRequest request =
                ReMeAddRequest.builder()
                        .workspaceId("task_workspace")
                        .trajectories(List.of(trajectory))
                        .build();

        // 执行并验证。
        StepVerifier.create(client.add(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertEquals(true, response.getSuccess());
                            assertEquals("", response.getAnswer());
                            assertNotNull(response.getMetadata());
                            assertNotNull(response.getMetadata().getMemoryList());
                            assertEquals(1, response.getMetadata().getMemoryList().size());
                            ReMeAddResponse.MemoryItem memory =
                                    response.getMetadata().getMemoryList().get(0);
                            assertEquals("task_workspace", memory.getWorkspaceId());
                            assertEquals(
                                    "user drinks coffee in the morning while working",
                                    memory.getContent());
                            assertNotNull(response.getMetadata().getUpdateResult());
                            assertEquals(
                                    1, response.getMetadata().getUpdateResult().getInsertedCount());
                        })
                .verifyComplete();

        // 验证请求。
        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertEquals("POST", recordedRequest.getMethod());
        assertEquals("/summary_personal_memory", recordedRequest.getPath());
        assertTrue(recordedRequest.getHeader("Content-Type").contains("application/json"));

        // 验证请求体。
        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"workspace_id\":\"task_workspace\""));
        assertTrue(requestBody.contains("\"trajectories\""));
        assertTrue(requestBody.contains("\"role\":\"user\""));
        assertTrue(requestBody.contains("\"content\":\"I like to drink coffee"));
    }

    @Test
    void testAddRequestWithEmptyResponse() throws Exception {
        // 模拟空响应体，应返回空对象。
        mockServer.enqueue(new MockResponse().setBody("").setResponseCode(200));

        ReMeMessage message = ReMeMessage.builder().role("user").content("Test").build();
        ReMeTrajectory trajectory = ReMeTrajectory.builder().messages(List.of(message)).build();

        ReMeAddRequest request =
                ReMeAddRequest.builder()
                        .workspaceId("test_workspace")
                        .trajectories(List.of(trajectory))
                        .build();

        // 应妥善处理空响应。
        StepVerifier.create(client.add(request))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();
    }

    @Test
    void testAddRequestHttpError() {
        mockServer.enqueue(
                new MockResponse().setBody("{\"error\":\"Bad request\"}").setResponseCode(400));

        ReMeMessage message = ReMeMessage.builder().role("user").content("Test").build();
        ReMeTrajectory trajectory = ReMeTrajectory.builder().messages(List.of(message)).build();

        ReMeAddRequest request =
                ReMeAddRequest.builder()
                        .workspaceId("test_workspace")
                        .trajectories(List.of(trajectory))
                        .build();

        StepVerifier.create(client.add(request))
                .expectErrorMatches(
                        error ->
                                error.getMessage().contains("failed with status 400")
                                        && error.getMessage().contains("summary_personal_memory"))
                .verify();
    }

    @Test
    void testAddRequestInvalidJson() {
        mockServer.enqueue(new MockResponse().setBody("invalid json").setResponseCode(200));

        ReMeMessage message = ReMeMessage.builder().role("user").content("Test").build();
        ReMeTrajectory trajectory = ReMeTrajectory.builder().messages(List.of(message)).build();

        ReMeAddRequest request =
                ReMeAddRequest.builder()
                        .workspaceId("test_workspace")
                        .trajectories(List.of(trajectory))
                        .build();

        StepVerifier.create(client.add(request))
                .expectErrorMatches(
                        error ->
                                error.getMessage().contains("Failed to parse response")
                                        || error instanceof JsonException)
                .verify();
    }

    @Test
    void testSearchRequestSuccess() throws Exception {
        // 模拟符合实际 API 格式的成功搜索响应。
        String responseJson =
                "{\"answer\":\"user drinks coffee in the morning while working\",\"success\":true,"
                    + "\"metadata\":{\"memory_list\":[{\"workspace_id\":\"task_workspace\","
                    + "\"memory_id\":\"test_memory_id\",\"memory_type\":\"personal\",\"when_to_use\":\"coffee,"
                    + " morning, work\",\"content\":\"user drinks coffee in the morning while"
                    + " working\",\"score\":0.048747035796754684,\"time_created\":\"2025-12-10"
                    + " 10:30:43\",\"time_modified\":\"2025-12-10"
                    + " 10:30:43\",\"author\":\"test-author\",\"metadata\":{\"keywords\":\"coffee,"
                    + " morning, work\",\"time_info\":\"\",\"source_message\":\"I like to drink"
                    + " coffee while working in the"
                    + " morning\",\"observation_type\":\"personal_info_with_time\"},"
                    + "\"target\":\"user\",\"reflection_subject\":\"\"}]}}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        // 创建请求。
        ReMeSearchRequest request =
                ReMeSearchRequest.builder()
                        .workspaceId("task_workspace")
                        .query("What are the user's work habits?")
                        .topK(5)
                        .build();

        // 执行并验证。
        StepVerifier.create(client.search(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertEquals(true, response.getSuccess());
                            assertEquals(
                                    "user drinks coffee in the morning while working",
                                    response.getAnswer());
                            assertNotNull(response.getMetadata());
                            assertNotNull(response.getMetadata().getMemoryList());
                            assertEquals(1, response.getMetadata().getMemoryList().size());
                            ReMeSearchResponse.MemoryItem memory =
                                    response.getMetadata().getMemoryList().get(0);
                            assertEquals("task_workspace", memory.getWorkspaceId());
                            assertEquals(
                                    "user drinks coffee in the morning while working",
                                    memory.getContent());
                            // 测试向后兼容，getMemories() 应提取内容。
                            assertNotNull(response.getMemories());
                            assertEquals(1, response.getMemories().size());
                            assertEquals(
                                    "user drinks coffee in the morning while working",
                                    response.getMemories().get(0));
                        })
                .verifyComplete();

        // 验证请求。
        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertEquals("POST", recordedRequest.getMethod());
        assertEquals("/retrieve_personal_memory", recordedRequest.getPath());
        assertTrue(recordedRequest.getHeader("Content-Type").contains("application/json"));

        // 验证请求体。
        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"workspace_id\":\"task_workspace\""));
        assertTrue(requestBody.contains("\"query\":\"What are the user's work habits?\""));
        assertTrue(requestBody.contains("\"top_k\":5"));
    }

    @Test
    void testSearchRequestEmptyResults() throws Exception {
        String responseJson =
                "{\"answer\":\"\",\"success\":true,\"metadata\":{\"memory_list\":[]}}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        ReMeSearchRequest request =
                ReMeSearchRequest.builder()
                        .workspaceId("test_workspace")
                        .query("test query")
                        .topK(5)
                        .build();

        StepVerifier.create(client.search(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertEquals(true, response.getSuccess());
                            assertEquals("", response.getAnswer());
                            assertNotNull(response.getMetadata());
                            assertNotNull(response.getMetadata().getMemoryList());
                            assertEquals(0, response.getMetadata().getMemoryList().size());
                            assertEquals(0, response.getMemories().size());
                        })
                .verifyComplete();
    }

    @Test
    void testSearchRequestWithDefaultTopK() throws Exception {
        String responseJson =
                "{\"answer\":\"Memory"
                    + " 1\",\"success\":true,\"metadata\":{\"memory_list\":[{\"workspace_id\":\"test_workspace\",\"memory_id\":\"mem1\",\"content\":\"Memory"
                    + " 1\"}]}}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        ReMeSearchRequest request =
                ReMeSearchRequest.builder()
                        .workspaceId("test_workspace")
                        .query("test")
                        .build(); // 未设置 topK，应使用默认值。

        StepVerifier.create(client.search(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertEquals(true, response.getSuccess());
                            assertEquals("Memory 1", response.getAnswer());
                        })
                .verifyComplete();

        // 验证使用默认 topK。
        RecordedRequest recordedRequest = mockServer.takeRequest();
        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"top_k\":5")); // 默认值
    }

    @Test
    void testSearchRequestHttpError() {
        mockServer.enqueue(
                new MockResponse().setBody("{\"error\":\"Not found\"}").setResponseCode(404));

        ReMeSearchRequest request =
                ReMeSearchRequest.builder()
                        .workspaceId("test_workspace")
                        .query("test query")
                        .topK(5)
                        .build();

        StepVerifier.create(client.search(request))
                .expectErrorMatches(
                        error ->
                                error.getMessage().contains("failed with status 404")
                                        && error.getMessage().contains("retrieve_personal_memory"))
                .verify();
    }

    @Test
    void testSearchRequestInvalidJson() {
        mockServer.enqueue(new MockResponse().setBody("not valid json").setResponseCode(200));

        ReMeSearchRequest request =
                ReMeSearchRequest.builder().workspaceId("test_workspace").query("test").build();

        StepVerifier.create(client.search(request))
                .expectErrorMatches(
                        error ->
                                error.getMessage().contains("Failed to parse response")
                                        || error instanceof JsonException)
                .verify();
    }

    @Test
    void testSearchRequestWithMultipleMemories() throws Exception {
        String responseJson =
                "{\"answer\":\"First memory fragment. Second memory fragment. Third memory"
                    + " fragment\",\"success\":true,\"metadata\":{\"memory_list\":[{"
                    + "\"workspace_id\":\"test_workspace\",\"memory_id\":\"mem1\",\"content\":\"First"
                    + " memory fragment\",\"score\":0.9"
                    + "},{\"workspace_id\":\"test_workspace\",\"memory_id\":\"mem2\",\"content\":\"Second"
                    + " memory fragment\",\"score\":0.8"
                    + "},{\"workspace_id\":\"test_workspace\",\"memory_id\":\"mem3\",\"content\":\"Third"
                    + " memory fragment\",\"score\":0.7}]}}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        ReMeSearchRequest request =
                ReMeSearchRequest.builder()
                        .workspaceId("test_workspace")
                        .query("test query")
                        .topK(10)
                        .build();

        StepVerifier.create(client.search(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertEquals(true, response.getSuccess());
                            assertNotNull(response.getMetadata());
                            assertNotNull(response.getMetadata().getMemoryList());
                            assertEquals(3, response.getMetadata().getMemoryList().size());
                            // 测试向后兼容。
                            assertNotNull(response.getMemories());
                            assertEquals(3, response.getMemories().size());
                            assertEquals("First memory fragment", response.getMemories().get(0));
                            assertEquals("Second memory fragment", response.getMemories().get(1));
                            assertEquals("Third memory fragment", response.getMemories().get(2));
                        })
                .verifyComplete();
    }

    @Test
    void testShutdown() {
        ReMeClient client = new ReMeClient("http://localhost:8002");
        // 不应抛异常。
        client.shutdown();
    }

    @Test
    void testRequestBodySerialization() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody(
                                "{\"answer\":\"\",\"success\":true,\"metadata\":{\"memory_list\":[],\"deleted_memory_ids\":[],\"update_result\":{\"deleted_count\":0,\"inserted_count\":0}}}")
                        .setResponseCode(200));

        ReMeMessage message1 = ReMeMessage.builder().role("user").content("Test message 1").build();
        ReMeMessage message2 =
                ReMeMessage.builder().role("assistant").content("Test response 1").build();
        ReMeMessage message3 = ReMeMessage.builder().role("user").content("Test message 2").build();

        ReMeTrajectory trajectory =
                ReMeTrajectory.builder().messages(List.of(message1, message2, message3)).build();

        ReMeAddRequest request =
                ReMeAddRequest.builder()
                        .workspaceId("test_workspace")
                        .trajectories(List.of(trajectory))
                        .build();

        StepVerifier.create(client.add(request))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();

        // 验证请求体包含预期字段。
        RecordedRequest recordedRequest = mockServer.takeRequest();
        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"workspace_id\":\"test_workspace\""));
        assertTrue(requestBody.contains("\"trajectories\""));
        assertTrue(requestBody.contains("\"role\":\"user\""));
        assertTrue(requestBody.contains("\"role\":\"assistant\""));
        assertTrue(requestBody.contains("\"content\":\"Test message 1\""));
        assertTrue(requestBody.contains("\"content\":\"Test response 1\""));
        assertTrue(requestBody.contains("\"content\":\"Test message 2\""));
    }

    @Test
    void testSearchRequestBodySerialization() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody(
                                "{\"answer\":\"Memory"
                                    + " 1\",\"success\":true,\"metadata\":{\"memory_list\":[{\"workspace_id\":\"test_workspace\",\"memory_id\":\"mem1\",\"content\":\"Memory"
                                    + " 1\"}]}}")
                        .setResponseCode(200));

        ReMeSearchRequest request =
                ReMeSearchRequest.builder()
                        .workspaceId("test_workspace")
                        .query("What are the user preferences?")
                        .topK(10)
                        .build();

        StepVerifier.create(client.search(request))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();

        // 验证请求体包含预期字段。
        RecordedRequest recordedRequest = mockServer.takeRequest();
        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"workspace_id\":\"test_workspace\""));
        assertTrue(requestBody.contains("\"query\":\"What are the user preferences?\""));
        assertTrue(requestBody.contains("\"top_k\":10"));
    }

    @Test
    void testHttpTimeout() {
        // 创建超时时间极短的客户端。
        String baseUrl = mockServer.url("/").toString();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        ReMeClient shortTimeoutClient = new ReMeClient(baseUrl, Duration.ofMillis(1));

        // 将延迟响应入队。
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"status\":\"success\"}")
                        .setResponseCode(200)
                        .setBodyDelay(1000, TimeUnit.MILLISECONDS));

        ReMeMessage message = ReMeMessage.builder().role("user").content("Test").build();
        ReMeTrajectory trajectory = ReMeTrajectory.builder().messages(List.of(message)).build();

        ReMeAddRequest request =
                ReMeAddRequest.builder()
                        .workspaceId("test_workspace")
                        .trajectories(List.of(trajectory))
                        .build();

        // 应超时。
        StepVerifier.create(shortTimeoutClient.add(request)).expectError().verify();

        shortTimeoutClient.shutdown();
    }

    @Test
    void testMultipleTrajectoriesInAddRequest() throws Exception {
        String responseJson =
                "{\"answer\":\"\",\"success\":true,\"metadata\":{\"memory_list\":[],\"deleted_memory_ids\":[],\"update_result\":{\"deleted_count\":0,\"inserted_count\":2}}}";

        mockServer.enqueue(new MockResponse().setBody(responseJson).setResponseCode(200));

        // 创建多条轨迹。
        ReMeMessage msg1 = ReMeMessage.builder().role("user").content("Message 1").build();
        ReMeMessage msg2 = ReMeMessage.builder().role("assistant").content("Response 1").build();
        ReMeTrajectory trajectory1 = ReMeTrajectory.builder().messages(List.of(msg1, msg2)).build();

        ReMeMessage msg3 = ReMeMessage.builder().role("user").content("Message 2").build();
        ReMeMessage msg4 = ReMeMessage.builder().role("assistant").content("Response 2").build();
        ReMeTrajectory trajectory2 = ReMeTrajectory.builder().messages(List.of(msg3, msg4)).build();

        ReMeAddRequest request =
                ReMeAddRequest.builder()
                        .workspaceId("test_workspace")
                        .trajectories(List.of(trajectory1, trajectory2))
                        .build();

        StepVerifier.create(client.add(request))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertEquals(true, response.getSuccess());
                            assertNotNull(response.getMetadata());
                            assertNotNull(response.getMetadata().getUpdateResult());
                            assertEquals(
                                    2, response.getMetadata().getUpdateResult().getInsertedCount());
                        })
                .verifyComplete();

        // 验证请求包含两条轨迹。
        RecordedRequest recordedRequest = mockServer.takeRequest();
        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"trajectories\""));
        // 应包含两条轨迹的消息。
        assertTrue(requestBody.contains("\"content\":\"Message 1\""));
        assertTrue(requestBody.contains("\"content\":\"Message 2\""));
    }
}
