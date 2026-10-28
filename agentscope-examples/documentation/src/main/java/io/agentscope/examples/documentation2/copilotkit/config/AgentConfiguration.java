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
package io.agentscope.examples.documentation2.copilotkit.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.strategy.AguiEventEnricher;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEvents;
import io.agentscope.core.agui.runtime.AguiRuntimeContextResolver;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JacksonJsonCodec;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.examples.documentation2.copilotkit.a2ui.A2uiComposer;
import io.agentscope.examples.documentation2.copilotkit.service.DemoThreadStore;
import io.agentscope.examples.documentation2.copilotkit.service.InMemoryAgentEventStore;
import io.agentscope.examples.documentation2.copilotkit.service.PersistingAgentEventEnricher;
import io.agentscope.examples.documentation2.copilotkit.workbench.RefundOrderTool;
import io.agentscope.examples.documentation2.copilotkit.workbench.WorkbenchAguiEventConverter;
import io.agentscope.examples.documentation2.copilotkit.workbench.WorkbenchEventMiddleware;
import io.agentscope.examples.documentation2.copilotkit.workbench.WorkbenchStateRegistry;
import io.agentscope.examples.documentation2.copilotkit.workbench.WorkbenchTools;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.spring.boot.agui.common.AguiAgentRegistryCustomizer;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import reactor.core.publisher.Flux;

/**
 * 向 AG-UI 注册表注册智能体的配置类，演示注册多个不同 ID 的智能体。
 * <p>客户端可通过 URL 路径 {@code POST /agui/run/{agentId}}、HTTP 头 {@code X-Agent-Id: agentId} 或请求体 {@code forwardedProps.agentId} 选择智能体。
 */
@Configuration
public class AgentConfiguration {

    private final WorkbenchStateRegistry workbenchStateRegistry;
    private final A2uiComposer a2uiComposer;

    public AgentConfiguration(
            WorkbenchStateRegistry workbenchStateRegistry, A2uiComposer a2uiComposer) {
        this.workbenchStateRegistry = workbenchStateRegistry;
        this.a2uiComposer = a2uiComposer;
    }

    @Bean
    public AguiAgentRegistryCustomizer aguiAgentRegistryCustomizer() {
        AguiAgentRegistryCustomizer aguiAgentRegistryCustomizer =
                registry -> {
                    // 综合展示智能体：权限控制、计划任务、共享状态和 A2UI。
                    registry.registerFactory("workbench", this::createWorkbenchAgent);

                    // 为默认智能体注册工厂
                    // 使用工厂，确保每个请求获得新的智能体实例
                    registry.registerFactory("default", this::createDefaultAgent);

                    // 注册其他不同 ID 的智能体
                    // 示例：不使用工具的简单聊天智能体
                    registry.registerFactory("chat", this::createChatAgent);

                    // 示例：专门执行计算的智能体
                    registry.registerFactory("calculator", this::createCalculatorAgent);
                };

        System.out.println(
                "Registered agents with AG-UI registry: workbench, default, chat, calculator");
        System.out.println("Access agents via:");
        System.out.println("  - POST /agui/run (uses default-agent-id from config)");
        System.out.println("  - POST /agui/run/chat (uses 'chat' agent)");
        System.out.println(
                "  - POST /agui/run/agent/{agentId}/run (CopilotKit multi-route, see"
                        + " CopilotKitRouteConfiguration)");
        System.out.println("  - POST /agui/run with X-Agent-Id header");

        return aguiAgentRegistryCustomizer;
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
     * 保存每个源 {@link AgentEvent}，使 {@code /agent/{agentId}/connect} 可通过转换器重新投影 AG-UI 帧。
     * <p>在增强器中最后执行，只记录 AgentEvent，不修改帧。
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public PersistingAgentEventEnricher persistingAgentEventEnricher(
            InMemoryAgentEventStore eventStore,
            ObjectProvider<WorkbenchAguiEventConverter> workbenchConverterProvider) {
        return new PersistingAgentEventEnricher(eventStore, workbenchConverterProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public AguiRuntimeContextResolver aguiRuntimeContextResolver() {
        return request -> {
            // 演示身份：存在 X-Token 时直接将其视为用户 ID。
            String token = request.firstHeader("X-Token");
            String userId = token == null || token.isBlank() ? DemoThreadStore.DEMO_USER_ID : token;
            return RuntimeContext.builder()
                    .sessionId(request.getInput().getThreadId())
                    .userId(userId)
                    .build();
        };
    }

    /**
     * 综合展示智能体，使一次对话可覆盖演示声明的能力。
     * <ul>
     * <li>{@code permissionContext}：三态权限引擎；只读及状态写入工具有 ALLOW 规则；{@code deploy_release} 为 ASK，暂停为 CopilotKit 确认卡渲染的 AG-UI 中断；{@code purge_production_data} 为 DENY，其他模式或规则不能解除。{@code refund_order} 故意没有规则，由其 {@code checkPermissions} 根据退款金额决定。
     * <li>{@code enableTaskList()}：注册 {@code todo_write} 与轮次任务提醒；{@code WorkbenchEventMiddleware} 将任务列表镜像到共享状态，驱动计划面板与 STEP_STARTED / STEP_FINISHED 帧。
     * <li>{@code enableThinking}：推理增量以 {@code REASONING_MESSAGE_*} 事件到达浏览器。
     * </ul>
     * <p>{@code PermissionMode.DEFAULT} 对未被前置规则或工具检查决定的调用默认询问，而非默默授予权限。
     */
    private Agent createWorkbenchAgent() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WorkbenchTools(workbenchStateRegistry, a2uiComposer));
        toolkit.registerAgentTool(new RefundOrderTool(workbenchStateRegistry));

        return ReActAgent.builder()
                .name("AgentScope_Workbench")
                .sysPrompt(WORKBENCH_SYS_PROMPT)
                .model(
                        DashScopeChatModel.builder()
                                .apiKey(getRequiredApiKey())
                                .modelName(getModelName())
                                .stream(true)
                                .enableThinking(true)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .toolkit(toolkit)
                .enableTaskList()
                .permissionContext(workbenchPermissionContext())
                .middleware(new WorkbenchEventMiddleware(workbenchStateRegistry))
                .maxIters(16)
                .build();
    }

    private static PermissionContextState workbenchPermissionContext() {
        PermissionContextState.Builder permissions =
                PermissionContextState.builder().mode(PermissionMode.DEFAULT);

        // 只读和仅写状态的工具在此演示中配置为无需打断用户。
        for (String tool :
                List.of(
                        "query_service_metrics",
                        "update_workbench_brief",
                        "set_plan_goal",
                        "render_dashboard",
                        "confirmDemoAction",
                        "renderOrderCard",
                        "todo_write")) {
            permissions.addAllowRule(
                    tool, new PermissionRule(tool, null, PermissionBehavior.ALLOW, "policy"));
        }

        // 生产发布始终要求人工介入。
        permissions.addAskRule(
                "deploy_release",
                new PermissionRule("deploy_release", null, PermissionBehavior.ASK, "policy"));

        // 破坏性且不可逆：直接阻止，拒绝规则不可绕过。
        permissions.addDenyRule(
                "purge_production_data",
                new PermissionRule(
                        "purge_production_data", null, PermissionBehavior.DENY, "policy"));

        return permissions.build();
    }

    private static final String WORKBENCH_SYS_PROMPT =
            """
            你是 AgentScope 运维工作台的智能体，运行在 AG-UI 协议之上，前端是 CopilotKit。

            工作方式：
            1. 面对多步骤任务时，先调用 set_plan_goal 声明总目标，再用 todo_write 写出 3-6 条任务清单；
               推进过程中持续用 todo_write 更新状态（同一时刻只允许一条 in_progress）。前端计划看板会实时跟随。
            2. 需要数据时调用 query_service_metrics；需要把结论沉淀到共享状态时调用 update_workbench_brief。
            3. 需要用图形化方式展示指标、计划进度或风险结论时，调用 render_dashboard 动态生成界面，
               不要用 Markdown 表格代替它。
            4. deploy_release 是高危操作，会弹出人工确认；被拒绝时不要重试，直接说明已取消。
            5. purge_production_data 被安全策略永久禁止，用户要求删除生产数据时直接拒绝并解释原因。
            6. refund_order 的放行与否取决于金额，由工具自身判断，你只需如实发起调用。

            回答保持简洁，用中文，说明你做了什么以及下一步建议。不要假装某个操作已完成。
            """;

    /**
     * 创建默认演示智能体；具体工具以本方法的注册语句为准，包含仅模式定义的 HITL 工具，不能按旧版天气／计算工具说明理解。
     */
    private Agent createDefaultAgent() {
        String apiKey = getRequiredApiKey();

        // 创建包含示例工具的工具包
        Toolkit toolkit = new Toolkit();
        // 仅模式定义的 HITL 工具：挂起运行，供 CopilotKit useInterrupt 恢复。
        // 不要同时注册为 CopilotKit 前端工具，否则自动执行会在中断界面
        // 持续显示之前清除待处理中断。
        toolkit.registerSchema(requestHumanApprovalSchema());
        //        toolkit.registerTool(new ExampleTools());

        // 创建智能体
        return ReActAgent.builder()
                .name("AG-UI Assistant")
                .sysPrompt(
                        "You are a helpful AI assistant exposed via the AG-UI protocol. When an"
                            + " operation needs explicit human confirmation, or the user asks for"
                            + " the approval interrupt demo, always call requestHumanApproval with"
                            + " a clear summary. Do not pretend the action is already done before"
                            + " approval. Be concise and helpful in your responses.")
                .model(
                        DashScopeChatModel.builder()
                                .apiKey(apiKey)
                                .modelName(getModelName())
                                .stream(true)
                                .enableThinking(true)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .toolkit(toolkit)
                //                .middleware(exampleCustomEventMiddleware())
                .maxIters(10)
                .build();
    }

    private static ToolSchema requestHumanApprovalSchema() {
        Map<String, Object> summaryProperty = new LinkedHashMap<>();
        summaryProperty.put("type", "string");
        summaryProperty.put("description", "需要用户确认的操作说明");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("summary", summaryProperty);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("summary"));

        return ToolSchema.builder()
                .name("requestHumanApproval")
                .description("当操作需要人工确认时调用，用户可以在聊天中批准或拒绝。")
                .parameters(parameters)
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
                        DashScopeChatModel.builder()
                                .apiKey(apiKey)
                                .modelName(getModelName())
                                .stream(true)
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
        //        toolkit.registerTool(new ExampleTools());

        return ReActAgent.builder()
                .name("Calculator Agent")
                .sysPrompt(
                        "You are a mathematical assistant specialized in calculations. "
                                + "Use the calculate tool to perform mathematical operations. "
                                + "Always show your work and explain the results.")
                .model(
                        DashScopeChatModel.builder()
                                .apiKey(apiKey)
                                .modelName(getModelName())
                                .stream(true)
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

    private String getModelName() {
        String modelName = System.getenv("DASHSCOPE_MODEL");
        if (modelName == null || modelName.isEmpty()) {
            return "qwen3.5-flash";
        }
        return modelName;
    }

    @PostConstruct
    public void init() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // CopilotKit 会拒绝某些 AG-UI 事件载荷中的 JSON null 字段。
        objectMapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
        JsonUtils.setJsonCodec(new JacksonJsonCodec(objectMapper));
    }
}
