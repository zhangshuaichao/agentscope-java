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
package io.agentscope.examples.documentation2.agui.config;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverter;
import io.agentscope.core.agui.adapter.strategy.AguiEventEnricher;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEvents;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.documentation2.agui.tools.ExampleTools;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.spring.boot.agui.common.AguiAgentRegistryCustomizer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

/**
 * 向 AG-UI 注册表注册智能体的配置类，演示注册多个不同 ID 的智能体。
 * <p>客户端可通过 URL 路径 {@code POST /agui/run/{agentId}}、HTTP 头 {@code X-Agent-Id: agentId} 或请求体 {@code forwardedProps.agentId} 选择智能体。
 */
@Configuration
public class AgentConfiguration {

    @Bean
    public AguiAgentRegistryCustomizer aguiAgentRegistryCustomizer() {
        AguiAgentRegistryCustomizer aguiAgentRegistryCustomizer =
                registry -> {
                    // 为默认智能体注册工厂
                    // 使用工厂，确保每个请求获得新的智能体实例
                    registry.registerFactory("default", this::createDefaultAgent);

                    // 注册其他不同 ID 的智能体
                    // 示例：不使用工具的简单聊天智能体
                    registry.registerFactory("chat", this::createChatAgent);

                    // 示例：专门执行计算的智能体
                    registry.registerFactory("calculator", this::createCalculatorAgent);
                };

        System.out.println("Registered agents with AG-UI registry: default, chat, calculator");
        System.out.println("Access agents via:");
        System.out.println("  - POST /agui/run (uses default-agent-id from config)");
        System.out.println("  - POST /agui/run/chat (uses 'chat' agent)");
        System.out.println("  - POST /agui/run with X-Agent-Id header");

        return aguiAgentRegistryCustomizer;
    }

    @Bean
    public AgentEventConverter exampleAgentEventConverter() {
        return new AgentEventConverter() {
            @Override
            public Set<Class<? extends AgentEvent>> eventTypes() {
                return Set.of(CustomEvent.class);
            }

            @Override
            public void convert(AgentEvent event, AguiStreamContext context) {
                CustomEvent customEvent = (CustomEvent) event;
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("originalName", customEvent.getName());
                value.put("originalValue", customEvent.getValue());
                value.put("convertedBy", "exampleAgentEventConverter");
                context.emit(
                        new AguiEvent.Custom(
                                context.getThreadId(),
                                context.getRunId(),
                                "example_custom_event",
                                value));
            }
        };
    }

    @Bean
    public AguiEventEnricher exampleAguiEventEnricher() {
        return (source, events, context) -> {
            long timestamp = System.currentTimeMillis();
            return events.stream()
                    .map(event -> enrichExampleEvent(source, event, timestamp))
                    .toList();
        };
    }

    /**
     * 创建默认智能体实例，配置启用流式输出的 DashScope qwen-plus 模型、示例工具 get_weather/calculate 和内存对话状态。
     */
    private Agent createDefaultAgent() {
        String apiKey = getRequiredApiKey();

        // 创建包含示例工具的工具包
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ExampleTools());

        // 创建智能体
        return ReActAgent.builder()
                .name("AG-UI Assistant")
                .sysPrompt(
                        "You are a helpful AI assistant exposed via the AG-UI protocol. "
                                + "You can help users with various tasks including weather queries "
                                + "and calculations. Be concise and helpful in your responses.")
                .model(
                        DashScopeChatModel.builder()
                                .apiKey(apiKey)
                                .modelName("qwen3.7-plus")
                                .stream(true)
                                .enableThinking(true)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .toolkit(toolkit)
                //                .middleware(exampleCustomEventMiddleware())
                .maxIters(10)
                .build();
    }

    /**
     * 创建无工具的简单聊天智能体，作为纯对话助手。
     */
    private Agent createChatAgent() {
        String apiKey = getRequiredApiKey();

        return ReActAgent.builder()
                .name("Chat Assistant")
                .sysPrompt(
                        "You are a friendly conversational assistant. "
                                + "Engage in natural conversation and help users "
                                + "with general questions and discussions.")
                .model(
                        DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(
                                        true)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .middleware(exampleCustomEventMiddleware())
                .maxIters(1)
                .build();
    }

    /**
     * 创建专门进行数学运算的计算智能体。
     */
    private Agent createCalculatorAgent() {
        String apiKey = getRequiredApiKey();

        // 创建仅含计算工具的工具包
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ExampleTools());

        return ReActAgent.builder()
                .name("Calculator Agent")
                .sysPrompt(
                        "You are a mathematical assistant specialized in calculations. "
                                + "Use the calculate tool to perform mathematical operations. "
                                + "Always show your work and explain the results.")
                .model(
                        DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(
                                        true)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .toolkit(toolkit)
                .middleware(exampleCustomEventMiddleware())
                .maxIters(5)
                .build();
    }

    private static AguiEvent enrichExampleEvent(
            AgentEvent source, AguiEvent event, long fallbackTimestamp) {
        Long timestamp = event.timestamp() != null ? event.timestamp() : fallbackTimestamp;
        Object rawEvent = event.rawEvent();
        if (rawEvent == null && source instanceof CustomEvent customEvent) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("agentEventType", source.getType().name());
            if (customEvent.getName() != null) {
                value.put("name", customEvent.getName());
            }
            rawEvent = value;
        }
        return AguiEvents.withBaseProperties(event, timestamp, rawEvent);
    }

    private static MiddlewareBase exampleCustomEventMiddleware() {
        return new MiddlewareBase() {
            @Override
            public Flux<AgentEvent> onAgent(
                    Agent agent,
                    RuntimeContext ctx,
                    AgentInput input,
                    Function<AgentInput, Flux<AgentEvent>> next) {
                CustomEvent event =
                        new CustomEvent("example_agent_event", exampleCustomEventValue(agent, ctx));
                return next.apply(input)
                        .concatMap(
                                agentEvent ->
                                        agentEvent instanceof AgentStartEvent
                                                ? Flux.just(agentEvent, event)
                                                : Flux.just(agentEvent));
            }
        };
    }

    private static Map<String, Object> exampleCustomEventValue(Agent agent, RuntimeContext ctx) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("agentName", agent.getName());
        if (ctx != null && ctx.getSessionId() != null) {
            value.put("sessionId", ctx.getSessionId());
        }
        return value;
    }

    private String getRequiredApiKey() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException(
                    "DASHSCOPE_API_KEY environment variable is required. "
                            + "Please set it before starting the application.");
        }
        return apiKey;
    }
}
