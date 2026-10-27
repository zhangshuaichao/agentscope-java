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
package io.agentscope.core.tracing;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.Model;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;
import reactor.util.context.ContextView;

/**
 * 为智能体生命周期添加 OpenTelemetry 追踪的中间件。
 *
 * <p>生成以下 span：
 * <ul>
 * <li>{@code invoke_agent <name>}：覆盖整次回复</li>
 * <li>{@code chat <model>}：覆盖每次模型 API 调用</li>
 * <li>{@code execute_tool <name>}：覆盖每次工具执行</li>
 * </ul>
 *
 * <p>模型调用 span 使用 GenAI 语义约定属性报告输入／输出 token 用量，以及可用的缓存、推理和工具提示 token 明细。
 * 工具提示明细使用 AgentScope 专用属性，因为 GenAI 约定尚未定义它。
 *
 * <p>Reactor 异步链的上下文传播由 {@link ContextPropagationOperator} 处理。
 * 首次构造时在每个 JVM 中注册一次；注册后组装的操作符会被包装，先前已组装的链不会。
 *
 * <p>未配置 OTel SDK、仅默认空操作提供方活动时，每个钩子都以接近零开销短路。
 *
 * <p>无参构造方法在钩子运行时惰性读取 {@link GlobalOpenTelemetry}。
 * 若必须向应用自有 SDK 记录 span，请传给 {@link #OtelTracingMiddleware(OpenTelemetry)}。
 * 该 SDK 生命周期由调用方管理，不替换全局 SDK。
 *
 * <p>用法：
 * <pre>{@code
 * ReActAgent agent = ReActAgent.builder()
 *     .name("assistant")
 *     .model(model)
 *     .middleware(new OtelTracingMiddleware())
 *     .build();
 * }</pre>
 *

 */
public class OtelTracingMiddleware implements MiddlewareBase {

    private static final String INSTRUMENTATION_NAME = "io.agentscope";

    private static final String GEN_AI_USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";
    private static final String GEN_AI_USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
    private static final String GEN_AI_USAGE_CACHE_CREATION_INPUT_TOKENS =
            "gen_ai.usage.cache_creation.input_tokens";
    private static final String GEN_AI_USAGE_CACHE_READ_INPUT_TOKENS =
            "gen_ai.usage.cache_read.input_tokens";
    private static final String GEN_AI_USAGE_REASONING_OUTPUT_TOKENS =
            "gen_ai.usage.reasoning.output_tokens";
    private static final String AGENTSCOPE_USAGE_TOOL_USE_PROMPT_TOKENS =
            "agentscope.usage.tool_use_prompt_tokens";

    private static volatile boolean hookRegistered = false;

    private final OpenTelemetry openTelemetry;

    private final Tracer tracer;

    /**
     * 创建在钩子执行时惰性读取 {@link GlobalOpenTelemetry} 的中间件，因此 SDK 可以在构造之后注册。
     */
    public OtelTracingMiddleware() {
        this.openTelemetry = null;
        this.tracer = null;
        registerReactorHook();
    }

    /**
     * 创建向应用自有 OpenTelemetry SDK 记录 span 的中间件。
     *
     * <p>tracer 解析一次后复用。SDK 不注册为 {@link GlobalOpenTelemetry}，生命周期归调用方管理。
     * 此构造方法仍会安装 JVM 级 Reactor 钩子。
     *
     * @param openTelemetry 应用拥有的 OpenTelemetry 实例，不能为 null
     * @throws NullPointerException {@code openTelemetry} 为 null 时抛出
     */
    public OtelTracingMiddleware(OpenTelemetry openTelemetry) {
        this.openTelemetry =
                Objects.requireNonNull(openTelemetry, "openTelemetry must not be null");
        this.tracer = this.openTelemetry.getTracer(INSTRUMENTATION_NAME);
        registerReactorHook();
    }

    private static void registerReactorHook() {
        if (!hookRegistered) {
            synchronized (OtelTracingMiddleware.class) {
                if (!hookRegistered) {
                    ContextPropagationOperator.builder().build().registerOnEachOperator();
                    hookRegistered = true;
                }
            }
        }
    }

    /**
     * 精确声明参与范围：覆盖更多钩子的子类必须扩展此集合。
     */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(
                ExtensionPoint.ON_AGENT, ExtensionPoint.ON_MODEL_CALL, ExtensionPoint.ON_ACTING);
    }

    private Tracer getTracer() {
        if (openTelemetry != null) {
            return tracer;
        }
        return GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    // ------------------------------------------------------------------
    // onAgent：invoke_agent 跨度
    // ------------------------------------------------------------------

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(
                ctxView -> {
                    Context parentContext = resolveOtelContext(ctxView);
                    Span span =
                            getTracer()
                                    .spanBuilder("invoke_agent " + agent.getName())
                                    .setParent(parentContext)
                                    .setAttribute("gen_ai.operation.name", "invoke_agent")
                                    .setAttribute("gen_ai.agent.name", agent.getName())
                                    .setAttribute(
                                            "gen_ai.agent.id",
                                            agent.getAgentId() != null ? agent.getAgentId() : "")
                                    .setAttribute(
                                            "gen_ai.request.messages.count",
                                            (long) input.msgs().size())
                                    .startSpan();

                    Context otelCtx = span.storeInContext(parentContext);
                    // 【学习提示】响应式完成、异常与取消走不同回调；共享原子标志确保 span 只结束一次。
                    // 追踪资源也必须覆盖取消路径，否则客户端中断后会留下未结束的 span。
                    AtomicReference<Boolean> ended = new AtomicReference<>(false);

                    return ContextPropagationOperator.runWithContext(
                            next.apply(input)
                                    .doOnNext(
                                            event -> {
                                                if (event instanceof AgentStartEvent rse
                                                        && rse.getSource() == null
                                                        && rse.getReplyId() != null) {
                                                    span.setAttribute(
                                                            "agentscope.agent.reply_id",
                                                            rse.getReplyId());
                                                }
                                            })
                                    .doOnComplete(
                                            () -> {
                                                if (ended.compareAndSet(false, true)) {
                                                    span.setStatus(StatusCode.OK);
                                                    span.end();
                                                }
                                            })
                                    .doOnError(
                                            e -> {
                                                if (ended.compareAndSet(false, true)) {
                                                    span.setStatus(
                                                            StatusCode.ERROR, e.getMessage());
                                                    span.recordException(e);
                                                    span.end();
                                                }
                                            })
                                    .doOnCancel(
                                            () -> {
                                                if (ended.compareAndSet(false, true)) {
                                                    span.setStatus(StatusCode.ERROR, "cancelled");
                                                    span.end();
                                                }
                                            }),
                            otelCtx);
                });
    }

    // ------------------------------------------------------------------
    // onModelCall：chat 跨度
    // ------------------------------------------------------------------

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(
                ctxView -> {
                    Context parentContext = resolveOtelContext(ctxView);
                    Model model = input.model();
                    String modelName = model != null ? model.getModelName() : "unknown";
                    Span span =
                            getTracer()
                                    .spanBuilder("chat " + modelName)
                                    .setParent(parentContext)
                                    .setAttribute("gen_ai.operation.name", "chat")
                                    .setAttribute("gen_ai.request.model", modelName)
                                    .setAttribute(
                                            "gen_ai.request.messages.count",
                                            (long) input.messages().size())
                                    .setAttribute(
                                            "gen_ai.request.tools.count",
                                            input.tools() != null
                                                    ? (long) input.tools().size()
                                                    : 0L)
                                    .startSpan();

                    Context otelCtx = span.storeInContext(parentContext);
                    AtomicReference<Boolean> ended = new AtomicReference<>(false);

                    return ContextPropagationOperator.runWithContext(
                            next.apply(input)
                                    .doOnNext(
                                            event -> {
                                                if (event instanceof ModelCallEndEvent mce) {
                                                    setModelResponseAttributes(span, mce);
                                                }
                                            })
                                    .doOnComplete(
                                            () -> {
                                                if (ended.compareAndSet(false, true)) {
                                                    span.setStatus(StatusCode.OK);
                                                    span.end();
                                                }
                                            })
                                    .doOnError(
                                            e -> {
                                                if (ended.compareAndSet(false, true)) {
                                                    span.setStatus(
                                                            StatusCode.ERROR, e.getMessage());
                                                    span.recordException(e);
                                                    span.end();
                                                }
                                            })
                                    .doOnCancel(
                                            () -> {
                                                if (ended.compareAndSet(false, true)) {
                                                    span.setStatus(StatusCode.ERROR, "cancelled");
                                                    span.end();
                                                }
                                            }),
                            otelCtx);
                });
    }

    // ------------------------------------------------------------------
    // onActing：execute_tool 跨度
    // ------------------------------------------------------------------

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(
                ctxView -> {
                    Context parentContext = resolveOtelContext(ctxView);
                    String toolNames =
                            input.toolCalls() != null
                                    ? input.toolCalls().stream()
                                            .map(ToolUseBlock::getName)
                                            .collect(Collectors.joining(", "))
                                    : "unknown";
                    String spanName = buildToolSpanName(input);

                    Span span =
                            getTracer()
                                    .spanBuilder("execute_tool " + spanName)
                                    .setParent(parentContext)
                                    .setAttribute("gen_ai.operation.name", "execute_tool")
                                    .setAttribute("gen_ai.tool.name", toolNames)
                                    .setAttribute(
                                            "gen_ai.tool.call.count",
                                            input.toolCalls() != null
                                                    ? (long) input.toolCalls().size()
                                                    : 0L)
                                    .startSpan();

                    Context otelCtx = span.storeInContext(parentContext);
                    AtomicReference<Boolean> ended = new AtomicReference<>(false);
                    Set<String> callIds = ConcurrentHashMap.newKeySet();

                    return ContextPropagationOperator.runWithContext(
                            next.apply(input)
                                    .doOnNext(
                                            event -> {
                                                if (event instanceof ToolResultEndEvent tre
                                                        && tre.getToolCallId() != null) {
                                                    callIds.add(tre.getToolCallId());
                                                }
                                            })
                                    .doOnComplete(
                                            () -> {
                                                if (ended.compareAndSet(false, true)) {
                                                    setToolCallIds(span, callIds);
                                                    span.setStatus(StatusCode.OK);
                                                    span.end();
                                                }
                                            })
                                    .doOnError(
                                            e -> {
                                                if (ended.compareAndSet(false, true)) {
                                                    setToolCallIds(span, callIds);
                                                    span.setStatus(
                                                            StatusCode.ERROR, e.getMessage());
                                                    span.recordException(e);
                                                    span.end();
                                                }
                                            })
                                    .doOnCancel(
                                            () -> {
                                                if (ended.compareAndSet(false, true)) {
                                                    setToolCallIds(span, callIds);
                                                    span.setStatus(StatusCode.ERROR, "cancelled");
                                                    span.end();
                                                }
                                            }),
                            otelCtx);
                });
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    // 优先从 Reactor ContextView 读取 OTel Context；否则回退到线程局部的
    // Context.current()
    // 使响应式管道内创建的 span 即使在线程切换后仍能找到父 span。
    private Context resolveOtelContext(ContextView ctxView) {
        return ContextPropagationOperator.getOpenTelemetryContextFromContextView(
                ctxView, Context.current());
    }

    // 使用首个工具名作为 span 名；批量时追加 "(+N more)"，限制
    // 基数。
    // 完整工具名称列表仍保存在 gen_ai.tool.name 属性中。
    private static String buildToolSpanName(ActingInput input) {
        if (input.toolCalls() == null || input.toolCalls().isEmpty()) {
            return "unknown";
        }
        String first = input.toolCalls().get(0).getName();
        int rest = input.toolCalls().size() - 1;
        return rest > 0 ? first + " (+" + rest + " more)" : first;
    }

    private void setToolCallIds(Span span, Set<String> callIds) {
        if (!callIds.isEmpty()) {
            span.setAttribute("gen_ai.tool.call.id", String.join(",", callIds));
        }
    }

    private void setModelResponseAttributes(Span span, ModelCallEndEvent event) {
        if (event.getUsage() != null) {
            var usage = event.getUsage();
            span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, (long) usage.getInputTokens());
            span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, (long) usage.getOutputTokens());
            span.setAttribute(
                    GEN_AI_USAGE_CACHE_CREATION_INPUT_TOKENS,
                    (long) usage.getCacheCreationTokens());
            span.setAttribute(GEN_AI_USAGE_CACHE_READ_INPUT_TOKENS, (long) usage.getCachedTokens());
            span.setAttribute(
                    GEN_AI_USAGE_REASONING_OUTPUT_TOKENS, (long) usage.getReasoningTokens());
            span.setAttribute(
                    AGENTSCOPE_USAGE_TOOL_USE_PROMPT_TOKENS, (long) usage.getToolUsePromptTokens());
        }
    }
}
