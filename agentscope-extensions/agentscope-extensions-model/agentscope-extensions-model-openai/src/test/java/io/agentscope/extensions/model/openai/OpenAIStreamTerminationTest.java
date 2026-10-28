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

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** 验证 OpenAI 流式响应由 SSE {@code [DONE]} 标记确定性终止，不依赖底层 HTTP 连接生命周期。<p>回归场景：旧逻辑只过滤 {@code [DONE]} 而不结束 {@link Flux}；兼容网关发出 {@code [DONE]} 后继续保持连接时，流要等 socket 空闲超时关闭才结束，可能使 ReAct 轮次停滞近一分钟。 */
@Tag("unit")
@DisplayName("OpenAI Stream Termination Tests")
class OpenAIStreamTerminationTest {

    private static final String CHUNK_CONTENT =
            "{\"id\":\"1\",\"choices\":[{\"delta\":{\"content\":\"hello\"},\"index\":0}]}";
    private static final String CHUNK_FINISH =
            "{\"id\":\"1\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}";

    /** 先发出两个内容分块及 {@code [DONE]}，随后永不完成（{@link Flux#never()}）的传输，用于模拟发出 {@code [DONE]} 后仍保持连接的网关。 */
    private static HttpTransport keepAliveTransportAfterDone() {
        return new HttpTransport() {
            @Override
            public HttpResponse execute(HttpRequest request) {
                throw new UnsupportedOperationException("not used in this test");
            }

            @Override
            public Flux<String> stream(HttpRequest request) {
                return Flux.just(CHUNK_CONTENT, CHUNK_FINISH, "[DONE]").concatWith(Flux.never());
            }

            @Override
            public void close() {}
        };
    }

    private static OpenAIRequest sampleRequest() {
        return OpenAIRequest.builder()
                .model("gpt-4o-mini")
                .messages(List.of(OpenAIMessage.builder().role("user").content("hi").build()))
                .build();
    }

    @Test
    @DisplayName("Stream should complete on [DONE] even if the connection stays open")
    void streamCompletesOnDoneSentinelWithoutWaitingForConnectionClose() {
        OpenAIClient client = new OpenAIClient(keepAliveTransportAfterDone());

        StepVerifier.create(
                        client.stream(
                                "test-key",
                                "http://localhost",
                                sampleRequest(),
                                GenerateOptions.builder().build()))
                .expectNextCount(2)
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }
}
