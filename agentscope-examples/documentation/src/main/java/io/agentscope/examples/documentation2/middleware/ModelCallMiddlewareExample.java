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
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * ModelCallMiddlewareExample：通过 {@link MiddlewareBase#onModelCall(Agent, RuntimeContext, ModelCallInput, Function)} 拦截模型 API 调用。
 * <p>{@code onModelCall} 包装每次底层 LLM 调用，可查看发送前原始请求及返回事件流，适合记录模型／消息／工具数量、测量延迟及 token 吞吐、注入或转换消息、缓存或替换响应。
 * <p>{@link ModelCallInput} 字段：{@code messages()} 完整历史；{@code tools()} 可见工具模式；{@code options()} 温度与 token 上限等；{@code model()} 为 {@link io.agentscope.core.model.Model}。
 * <p>要转发原请求必须调用 {@code next}；返回其他 Flux 则整体替换响应。运行：
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.middleware.ModelCallMiddlewareExample
 * </pre>
 */
public class ModelCallMiddlewareExample {

    /**
     * 运行模型调用中间件示例。
     * @param args 命令行参数，此处忽略
     */
    public static void main(String[] args) throws java.io.IOException {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Model Call Middleware Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates onModelCall() to log request metadata and measure latency.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        AuditingMiddleware auditMiddleware = new AuditingMiddleware();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("AuditedAgent")
                        .sysPrompt("You are a concise assistant. Reply in one sentence.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-plus")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .middleware(auditMiddleware)
                        .build();

        System.out.println("Sending a few messages to observe middleware logging ...\n");
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

        System.out.println("\n--- Audit Summary ---");
        System.out.println("Total model calls intercepted: " + auditMiddleware.callCount.get());
        System.out.println("Total events received:         " + auditMiddleware.eventCount.get());
    }

    /**
     * 记录每次模型调用元数据并测量延迟的中间件。
     */
    public static class AuditingMiddleware implements MiddlewareBase {

        /**
         * 统计拦截的模型 API 调用总数。
         */
        final AtomicLong callCount = new AtomicLong();

        /**
         * 统计所有调用收到的流式事件总数。
         */
        final AtomicLong eventCount = new AtomicLong();

        /**
         * 仅声明参与已覆盖的扩展点。
         */
        @Override
        public Set<ExtensionPoint> activePoints() {
            return EnumSet.of(ExtensionPoint.ON_MODEL_CALL);
        }

        /**
         * 拦截每次模型调用，记录请求元数据并测量延迟。通过 {@code next} 转发，再包装 Flux 统计事件并计算端到端延迟。
         * @param agent 发起调用的智能体
         * @param input 模型请求，包含消息、工具、选项和模型
         * @param next 实际调用模型的下游函数
         * @return 模型事件流，可增强或转换
         */
        @Override
        public Flux<AgentEvent> onModelCall(
                Agent agent,
                RuntimeContext ctx,
                ModelCallInput input,
                Function<ModelCallInput, Flux<AgentEvent>> next) {

            long callIndex = callCount.incrementAndGet();
            long startMs = System.currentTimeMillis();

            System.out.printf(
                    "[Audit #%d] model=%s | messages=%d | tools=%d%n",
                    callIndex,
                    input.model().getClass().getSimpleName(),
                    input.messages().size(),
                    input.tools().size());

            // 转发到模型并包装响应流
            return next.apply(input)
                    .doOnNext(event -> eventCount.incrementAndGet())
                    .doOnComplete(
                            () -> {
                                long elapsed = System.currentTimeMillis() - startMs;
                                System.out.printf(
                                        "[Audit #%d] completed in %d ms (events: %d)%n",
                                        callIndex, elapsed, eventCount.get());
                            })
                    .doOnError(
                            err ->
                                    System.err.printf(
                                            "[Audit #%d] error: %s%n",
                                            callIndex, err.getMessage()));
        }
    }
}
