/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.documentation2.streaming;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * StreamingWebExample：Spring Boot 与 SSE 流式智能体响应。
 * <p>共享 builder 保存应用配置；每次订阅构建自己的智能体，流完成、失败或取消时关闭。共享状态存储跨请求保留历史，应用必须串行安排同一会话请求。用法：
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn spring-boot:run -pl agentscope-examples/documentation \
 *       -Dspring-boot.run.mainClass=io.agentscope.examples.documentation2.streaming.StreamingWebExample
 *
 *   curl -N "http://localhost:8080/chat?message=Hello"
 *   curl -N "http://localhost:8080/chat?message=What+is+AI?&amp;sessionId=my-session"
 * </pre>
 * <p>【学习提示】Flux.using 将智能体实例与订阅绑定，统一处理完成、错误和取消后的关闭；共享状态存储并不自动串行化同会话并发请求。
 */
@SpringBootApplication
public class StreamingWebExample {

    /**
     * 启动 Spring Boot 应用。
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(StreamingWebExample.class, args);
    }

    /**
     * 提供流式聊天和健康端点的 REST 控制器。
     */
    @RestController
    public static class ChatController implements InitializingBean {

        private ReActAgent.Builder agentBuilder;

        @Override
        public void afterPropertiesSet() {
            String apiKey = System.getenv("DASHSCOPE_API_KEY");
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalStateException(
                        "DASHSCOPE_API_KEY environment variable is required");
            }

            Path sessionPath =
                    Paths.get(
                            System.getProperty("user.home"),
                            ".agentscope",
                            "examples",
                            "web-sessions");

            agentBuilder =
                    ReActAgent.builder()
                            .name("WebAgent")
                            .model(
                                    DashScopeChatModel.builder()
                                            .apiKey(apiKey)
                                            .modelName("qwen-plus")
                                            .stream(true)
                                            .build())
                            .stateStore(new JsonFileAgentStateStore(sessionPath));

            System.out.println("\n=== StreamingWeb Example Started ===");
            System.out.println("Server running at: http://localhost:8080");
            System.out.println("\nTry:");
            System.out.println("  curl -N \"http://localhost:8080/chat?message=Hello\"");
            System.out.println(
                    "  curl -N"
                        + " \"http://localhost:8080/chat?message=What+is+AI&sessionId=my-session\"");
            System.out.println("\nPress Ctrl+C to stop.\n");
        }

        /**
         * 通过 SSE 流式返回响应的聊天端点。
         * @param message 用户消息
         * @param sessionId 会话标识，默认 {@code "default"}
         * @return 增量文本块流
         */
        @GetMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public Flux<String> chat(
                @RequestParam String message,
                @RequestParam(defaultValue = "default") String sessionId) {

            // 此演示采用匿名会话。生产环境应从认证结果派生 userId，
            // 开始流之前验证调用方有权访问 sessionId。
            RuntimeContext ctx = RuntimeContext.builder().sessionId(sessionId).build();

            return Flux.using(
                            agentBuilder::build,
                            agent -> agent.streamEvents(new UserMessage(message), ctx),
                            ReActAgent::close)
                    .subscribeOn(Schedulers.boundedElastic())
                    .filter(event -> event instanceof TextBlockDeltaEvent)
                    .map(event -> ((TextBlockDeltaEvent) event).getDelta());
        }

        /**
         * 健康检查端点。
         * @return {@code "OK"}
         */
        @GetMapping("/health")
        public String health() {
            return "OK";
        }
    }
}
