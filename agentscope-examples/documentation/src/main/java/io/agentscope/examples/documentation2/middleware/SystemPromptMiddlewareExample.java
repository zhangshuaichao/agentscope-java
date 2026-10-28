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
package io.agentscope.examples.documentation2.middleware;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import reactor.core.publisher.Mono;

/**
 * SystemPromptMiddlewareExample：覆盖 {@link MiddlewareBase#onSystemPrompt(Agent, RuntimeContext, String)} 动态注入系统提示词。
 * <p>{@code onSystemPrompt} 在准备模型上下文时参与提示构建；具体调用频率以核心推理链为准，不能假定每次 {@code agent.call()} 只调用一次。各中间件接收前一个输出，可分层加入当前时间、用户语言／角色／偏好、测试或生产环境说明。
 * <p>运行：
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.middleware.SystemPromptMiddlewareExample
 * </pre>
 */
public class SystemPromptMiddlewareExample {

    /**
     * 运行系统提示词中间件示例。
     * @param args 命令行参数，此处忽略
     */
    public static void main(String[] args) throws java.io.IOException {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("System Prompt Middleware Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates onSystemPrompt() to inject timestamp and environment info.\n"
                        + "Ask 'What time is it?' — the agent knows the current UTC time\n"
                        + "because the middleware injects it on every call.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        ReActAgent agent =
                ReActAgent.builder()
                        .name("ContextAwareAgent")
                        .sysPrompt("You are a helpful assistant.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-plus")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .middleware(new TimestampMiddleware())
                        .middleware(new EnvironmentMiddleware("demo", "user-42"))
                        .build();

        System.out.println("Try: 'What time is it?' or 'Who am I?'\n");

        Msg response =
                agent.call(new UserMessage("user", "What time is it and what environment am I in?"))
                        .block();
        System.out.println("Agent: " + (response != null ? response.getTextContent() : "(null)"));

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Chat started. Type 'exit' to quit.\n");

        while (true) {
            System.out.print("You: ");
            String input = reader.readLine();
            if (input == null || input.trim().equalsIgnoreCase("exit")) {
                System.out.println("\nGoodbye!");
                break;
            }
            if (input.isBlank()) {
                continue;
            }
            Msg userMsg = new UserMessage(input.trim());
            System.out.print("\nAgent: ");
            agent.streamEvents(userMsg)
                    .doOnNext(
                            event -> {
                                if (event instanceof TextBlockDeltaEvent e) {
                                    System.out.print(e.getDelta());
                                }
                            })
                    .blockLast();
            System.out.println("\n");
        }
    }

    /**
     * 将当前 UTC 时间戳追加到系统提示词，使智能体无需时钟工具也可参考时间。
     */
    public static class TimestampMiddleware implements MiddlewareBase {

        /**
         * 仅声明参与已覆盖的扩展点。
         */
        @Override
        public Set<ExtensionPoint> activePoints() {
            return EnumSet.of(ExtensionPoint.ON_SYSTEM_PROMPT);
        }

        /**
         * 将当前 UTC 时间戳追加到系统提示词。
         * @param agent 调用智能体，可用于检查上下文
         * @param currentPrompt 前置中间件已构建的提示词
         * @return 追加时间戳后的提示词
         */
        @Override
        public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
            String timestamp = Instant.now().toString();
            String appended = currentPrompt + "\n\n[Context] Current UTC time: " + timestamp;
            return Mono.just(appended);
        }
    }

    /**
     * 将环境与用户信息追加到系统提示词的中间件。
     */
    public static class EnvironmentMiddleware implements MiddlewareBase {

        private final String environment;
        private final String userId;

        /**
         * 创建环境中间件。
         * @param environment 部署环境标签，如 "demo"、"production"
         * @param userId 当前用户标识
         */
        public EnvironmentMiddleware(String environment, String userId) {
            this.environment = environment;
            this.userId = userId;
        }

        /**
         * 仅声明参与已覆盖的扩展点。
         */
        @Override
        public Set<ExtensionPoint> activePoints() {
            return EnumSet.of(ExtensionPoint.ON_SYSTEM_PROMPT);
        }

        /**
         * 追加环境与用户信息。
         * @param agent 调用智能体
         * @param currentPrompt 前置中间件构建的提示词
         * @return 加入环境上下文后的提示词
         */
        @Override
        public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
            String appended =
                    currentPrompt
                            + "\n[Context] Environment: "
                            + environment
                            + " | User ID: "
                            + userId;
            return Mono.just(appended);
        }
    }
}
