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
package io.agentscope.core.agui.adapter;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agui.AguiUtil;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverterRegistry;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.converter.AguiMessageConverter;
import io.agentscope.core.agui.converter.AguiToolConverter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.SchemaOnlyTool;
import io.agentscope.core.tool.ToolMergeMode;
import io.agentscope.core.tool.ToolRequestConfig;
import io.agentscope.core.tool.Toolkit;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;

/**
 * 连接 AgentScope 智能体与 AG-UI 协议的适配器。
 *
 * <p>将 AG-UI 输入转换为 AgentScope 消息，调用智能体，再将流式事件转回 AG-UI 事件。
 *
 * <p>【学习提示】这里是协议适配层：Flux.defer 让每次订阅创建独立转换状态；concatMapIterable 保持源事件顺序，不能随意改成并行 flatMap。错误可以转为协议错误事件，但客户端取消订阅不等于收到 RUN_FINISHED，也不会自动回滚已经发生的工具副作用。
 *
 * <p><b>事件映射：</b>
 * <ul>
 * <li>AgentScope REASONING/SUMMARY → AG-UI TEXT_MESSAGE_*（TextBlock）</li>
 * <li>AgentScope REASONING/SUMMARY → AG-UI REASONING_*（启用时的 ThinkingBlock）</li>
 * <li>AgentScope TOOL_RESULT → AG-UI TOOL_CALL_END</li>
 * <li>ToolUseBlock 内容 → AG-UI TOOL_CALL_START</li>
 * </ul>
 *
 * <p><b>推理支持：</b>
 * <ul>
 * <li>按照 AG-UI Reasoning 草案将 ThinkingBlock 内容转换为 REASONING_* 事件</li>
 * <li>为向后兼容默认禁用推理输出（enableReasoning=false）</li>
 * <li>在 AguiAdapterConfig 中设置 enableReasoning=true 可启用推理事件</li>
 * </ul>
 */
public class AguiAgentAdapter {

    public static final String RUNTIME_CONTEXT_THREAD_ID_KEY = "agui.threadId";
    public static final String RUNTIME_CONTEXT_RUN_ID_KEY = "agui.runId";
    public static final String RUNTIME_CONTEXT_MESSAGES_KEY = "agui.messages";
    public static final String RUNTIME_CONTEXT_TOOLS_KEY = "agui.tools";
    public static final String RUNTIME_CONTEXT_CONTEXT_KEY = "agui.context";
    public static final String RUNTIME_CONTEXT_STATE_KEY = "agui.state";
    public static final String RUNTIME_CONTEXT_FORWARDED_PROPS_KEY = "agui.forwardedProps";
    public static final String RUNTIME_CONTEXT_RESUME_KEY = "agui.resume";
    public static final String RUNTIME_CONTEXT_RESUME_INTERRUPTS_KEY = "agui.resume.interrupts";

    private final Agent agent;
    private final AguiAdapterConfig config;
    private final AguiMessageConverter messageConverter;
    private final AguiToolConverter toolConverter;
    private final AgentEventConverterRegistry agentEventConverterRegistry;

    /**
     * 创建 AguiAgentAdapter。
     *
     * @param agent 要适配的智能体
     * @param config 适配器配置
     */
    public AguiAgentAdapter(Agent agent, AguiAdapterConfig config) {
        this.agent = Objects.requireNonNull(agent, "agent cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.messageConverter = new AguiMessageConverter();
        this.toolConverter = new AguiToolConverter();
        this.agentEventConverterRegistry =
                new AgentEventConverterRegistry(
                        config.getEventConverters(),
                        config.getEventEnrichers(),
                        config.isEmitSubagentEventsAsNative());
    }

    /**
     * 使用 AG-UI 协议输入运行智能体。
     *
     * <p>转换输入消息，调用智能体流式 API，并发送 AG-UI 协议事件。
     *
     * @param input AG-UI 运行输入
     * @return AG-UI 事件 Flux
     */
    public Flux<AguiEvent> run(RunAgentInput input) {
        return run(input, null);
    }

    /**
     * 使用 AG-UI 协议输入和调用方提供的运行上下文运行智能体。
     *
     * <p>复制所提供的上下文并补充 AG-UI 运行时元数据。AG-UI 元数据和会话 ID 始终从 {@code input} 派生，因此调用方可增加自定义值而不丢失协议默认值。
     *
     * @param input AG-UI 运行输入
     * @param runtimeContext 调用方提供的可选运行上下文
     * @return AG-UI 事件 Flux
     */
    public Flux<AguiEvent> run(RunAgentInput input, RuntimeContext runtimeContext) {
        return Flux.defer(
                () -> {
                    String threadId = input.getThreadId();
                    String runId = input.getRunId();

                    RuntimeContext effectiveRuntimeContext =
                            buildRuntimeContext(input, runtimeContext);

                    // 创建流选项：使用增量模式实现真正的流式输出
                    StreamOptions options =
                            StreamOptions.builder()
                                    .eventTypes(EventType.ALL)
                                    .incremental(true)
                                    .build();

                    AgentStream agentStream;
                    try {
                        // 将 AG-UI 消息与官方恢复条目转换为 AgentScope
                        // 消息。
                        List<Msg> msgs =
                                messageConverter.toMsgList(
                                        input, resumeInterrupts(effectiveRuntimeContext));
                        agentStream =
                                streamWithRuntimeContext(
                                        msgs, options, effectiveRuntimeContext, input);
                    } catch (Throwable error) {
                        return errorEvents(threadId, runId, input, error, true);
                    }

                    AtomicBoolean eventSeen = new AtomicBoolean(false);

                    return Flux.concat(
                                    agentStream
                                            .events()
                                            .doOnNext(
                                                    event -> {
                                                        eventSeen.set(true);
                                                    }),
                                    Flux.defer(() -> agentStream.finish().get()))
                            .onErrorResume(
                                    error ->
                                            errorEvents(
                                                    threadId,
                                                    runId,
                                                    input,
                                                    error,
                                                    !eventSeen.get()));
                });
    }

    private AgentStream streamWithRuntimeContext(
            List<Msg> msgs,
            StreamOptions options,
            RuntimeContext runtimeContext,
            RunAgentInput input) {
        String threadId = input.getThreadId();
        String runId = input.getRunId();

        if (agent instanceof ReActAgent reAct) {
            AguiStreamContext context =
                    new AguiStreamContext(
                            threadId, runId, config, input, externalToolDetector(runtimeContext));
            Flux<AgentEvent> events =
                    Objects.requireNonNull(
                            reAct.streamEvents(msgs, runtimeContext), "agent stream is null");
            return new AgentStream(
                    convertAgentEvents(events, context), () -> finishPendingEvents(context));
        }
        if (AguiUtil.isHarnessAgent(agent)) {
            AguiStreamContext context =
                    new AguiStreamContext(
                            threadId, runId, config, input, externalToolDetector(runtimeContext));
            Flux<AgentEvent> events =
                    Objects.requireNonNull(
                            invokeHarnessStreamEvents(agent, msgs, runtimeContext),
                            "agent stream is null");
            return new AgentStream(
                    convertAgentEvents(events, context), () -> finishPendingEvents(context));
        }

        // 回退到 1.x
        EventConversionState state = new EventConversionState(threadId, runId);
        Flux<Event> events = agent.stream(msgs, options, runtimeContext);
        if (events == null) {
            events = agent.stream(msgs, options);
        }
        Objects.requireNonNull(events, "agent stream is null");
        return new AgentStream(
                Flux.concat(
                        Flux.just(new AguiEvent.RunStarted(threadId, runId)),
                        events.concatMapIterable(event -> convertEvent(event, state))),
                () -> finishRun(state));
    }

    private Flux<AguiEvent> convertAgentEvents(Flux<AgentEvent> events, AguiStreamContext context) {
        return events
                // 使用 concatMapIterable 保持严格的事件顺序
                .concatMapIterable(event -> agentEventConverterRegistry.convert(event, context))
                .onErrorResume(
                        error -> Flux.concat(finishPendingEvents(context), Flux.error(error)));
    }

    private Flux<AguiEvent> finishPendingEvents(AguiStreamContext context) {
        return Flux.fromIterable(
                agentEventConverterRegistry.enrich(null, context.finishPendingEvents(), context));
    }

    private record AgentStream(Flux<AguiEvent> events, Supplier<Flux<AguiEvent>> finish) {}

    @SuppressWarnings("unchecked")
    private Flux<AgentEvent> invokeHarnessStreamEvents(
            Agent harnessAgent, List<Msg> msgs, RuntimeContext runtimeContext) {
        try {
            Method method =
                    harnessAgent
                            .getClass()
                            .getMethod("streamEvents", List.class, RuntimeContext.class);
            Object result = method.invoke(harnessAgent, msgs, runtimeContext);
            if (!(result instanceof Flux<?> flux)) {
                throw new IllegalStateException("HarnessAgent streamEvents did not return Flux");
            }
            return (Flux<AgentEvent>) flux;
        } catch (InvocationTargetException e) {
            Throwable target = e.getTargetException();
            if (target instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (target instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("HarnessAgent streamEvents failed", target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "HarnessAgent does not expose streamEvents(List, RuntimeContext)", e);
        }
    }

    /**
     * 构建调用智能体所用的运行上下文。
     *
     * <p>先复制调用方上下文，再应用 AG-UI 协议元数据，确保保留必要请求值和会话隔离。根据前端工具构建单次调用工具请求配置，并通过 {@link RuntimeContext#getToolRequestConfig()} 传递，因此不会修改共享工具集，同一智能体的并发运行也彼此隔离。
     *
     * @param input AG-UI 运行输入
     * @param runtimeContext 调用方提供的可选运行上下文
     * @return 本次运行的有效上下文
     */
    protected RuntimeContext buildRuntimeContext(
            RunAgentInput input, RuntimeContext runtimeContext) {
        ToolRequestConfig perCallRequestConfig = buildToolRequestConfig(input);
        return RuntimeContext.builder(runtimeContext)
                .sessionId(input.getThreadId())
                .toolRequestConfig(perCallRequestConfig)
                .put(RunAgentInput.class, input)
                .put(RUNTIME_CONTEXT_THREAD_ID_KEY, input.getThreadId())
                .put(RUNTIME_CONTEXT_RUN_ID_KEY, input.getRunId())
                .put(RUNTIME_CONTEXT_MESSAGES_KEY, input.getMessages())
                .put(RUNTIME_CONTEXT_TOOLS_KEY, input.getTools())
                .put(RUNTIME_CONTEXT_CONTEXT_KEY, input.getContext())
                .put(RUNTIME_CONTEXT_STATE_KEY, input.getState())
                .put(RUNTIME_CONTEXT_FORWARDED_PROPS_KEY, input.getForwardedProps())
                .put(RUNTIME_CONTEXT_RESUME_KEY, input.getResume())
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, AguiEvent.Interrupt> resumeInterrupts(RuntimeContext runtimeContext) {
        if (runtimeContext == null) {
            return Map.of();
        }
        Object value = runtimeContext.get(RUNTIME_CONTEXT_RESUME_INTERRUPTS_KEY);
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, AguiEvent.Interrupt> interrupts = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key
                    && entry.getValue() instanceof AguiEvent.Interrupt interrupt) {
                interrupts.put(key, interrupt);
            }
        }
        return Map.copyOf(interrupts);
    }

    /**
     * 根据 {@code input} 携带的前端工具构建单次调用的 {@link ToolRequestConfig}。
     *
     * <p>生成不可变工具差异（外部 {@link SchemaOnlyTool} 和合并模式），而不复制或修改智能体共享工具集。配置通过 {@link RuntimeContext#getToolRequestConfig()} 传递，由执行引擎按需与共享工具集组合。
     *
     * @return 单次调用请求配置；无需覆盖时返回 {@link ToolRequestConfig#NONE}（引擎直接使用共享工具集）
     */
    private ToolRequestConfig buildToolRequestConfig(RunAgentInput input) {
        ToolMergeMode mergeMode =
                config.getToolMergeMode() != null
                        ? config.getToolMergeMode()
                        : ToolMergeMode.MERGE_EXTERNAL_PRIORITY;
        if (mergeMode == ToolMergeMode.AGENT_ONLY) {
            return ToolRequestConfig.NONE;
        }

        Toolkit source = agent.getToolkit();
        if (source == null) {
            return ToolRequestConfig.NONE;
        }

        // 外部工具按定义仅含 schema：不反向引用 groupManager/registry，
        // 也不携带状态，可安全地逐次调用构造并放入不可变请求配置。
        LinkedHashMap<String, SchemaOnlyTool> external = new LinkedHashMap<>();
        for (ToolSchema schema :
                toolConverter.toToolSchemaList(input.hasTools() ? input.getTools() : List.of())) {
            external.put(schema.getName(), new SchemaOnlyTool(schema));
        }

        return new ToolRequestConfig(external, mergeMode);
    }

    /**
     * 由实时工具集支持的外部工具检测器，依据 {@code runtimeContext} 携带的单次调用 {@link ToolRequestConfig} 解析。
     *
     * <p>用于判断禁用 {@code emitToolCallArgs} 时是否仍须发送 {@code TOOL_CALL_ARGS}：外部工具（前端提供或仅有 schema）在框架外执行，因此客户端需要参数。前端注入工具位于请求配置而非共享注册表，所以通过 {@link Toolkit#isExternalTool(String, ToolRequestConfig)} 解析。智能体未暴露工具集时返回 {@code null}，流上下文此时回退为匹配 {@link RunAgentInput#getTools()} 中的工具名。
     */
    private Predicate<String> externalToolDetector(RuntimeContext runtimeContext) {
        Toolkit toolkit = agent.getToolkit();
        if (toolkit == null) {
            return null;
        }
        ToolRequestConfig requestConfig =
                runtimeContext != null ? runtimeContext.getToolRequestConfig() : null;
        return name -> toolkit.isExternalTool(name, requestConfig);
    }

    private Flux<AguiEvent> errorEvents(
            String threadId,
            String runId,
            RunAgentInput input,
            Throwable error,
            boolean includeRunStarted) {
        String errorMessage =
                error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        List<AguiEvent> events = new ArrayList<>();
        if (includeRunStarted) {
            events.add(new AguiEvent.RunStarted(threadId, runId, null, input));
        }
        events.add(
                new AguiEvent.RunError(
                        threadId,
                        runId,
                        errorMessage,
                        mapErrorCode(error),
                        System.currentTimeMillis(),
                        null));
        if (config.isEmitRunFinishedAfterError()) {
            events.add(new AguiEvent.RunFinished(threadId, runId));
        }
        return Flux.fromIterable(events);
    }

    private static String mapErrorCode(Throwable error) {
        if (error instanceof java.util.concurrent.TimeoutException) {
            return "TIMEOUT_ERROR";
        }
        if (error instanceof java.lang.InterruptedException) {
            return "INTERRUPTED_ERROR";
        }
        if (error instanceof IllegalArgumentException || error instanceof IllegalStateException) {
            return "INVALID_INPUT_ERROR";
        }
        return "INTERNAL_ERROR";
    }

    // ========================================================================
    // 以下为旧版 v1 stream() 转换方法，保留用于通用 Agent 回退。
    // ========================================================================

    /**
     * 将 AgentScope 事件转换为 AG-UI 事件。
     *
     * @param event AgentScope 事件
     * @param state 转换状态
     * @return AG-UI 事件列表
     * @deprecated 自 2.0.0 起改用 streamEvents() 转换策略。
     */
    @Deprecated(since = "2.0.0", forRemoval = false)
    private List<AguiEvent> convertEvent(Event event, EventConversionState state) {
        List<AguiEvent> events = new ArrayList<>();
        Msg msg = event.getMessage();
        EventType type = event.getType();

        if (type == EventType.REASONING || type == EventType.SUMMARY) {
            // 处理推理/摘要事件：转换为文本消息和工具调用
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof TextBlock textBlock) {
                    String text = textBlock.getText();
                    if (text != null && !text.isEmpty()) {
                        String messageId = msg.getId();

                        // 尚未开始时启动消息
                        if (!state.hasStartedMessage(messageId)) {
                            events.add(
                                    new AguiEvent.TextMessageStart(
                                            state.threadId, state.runId, messageId, "assistant"));
                            state.startMessage(messageId);
                        }

                        if (!event.isLast()) {
                            // 增量模式下文本已经是增量
                            events.add(
                                    new AguiEvent.TextMessageContent(
                                            state.threadId, state.runId, messageId, text));
                        } else {
                            // 最后一个事件时结束消息
                            if (!state.hasEndedMessage(messageId)) {
                                events.add(
                                        new AguiEvent.TextMessageEnd(
                                                state.threadId, state.runId, messageId));
                                state.endMessage(messageId);
                            }
                        }
                    }
                } else if (block instanceof ThinkingBlock thinkingBlock) {
                    // 处理思考块：仅在启用时转换为 REASONING_* 事件
                    // 依据 AG-UI Reasoning 草案：https://docs.ag-ui.com/drafts/reasoning
                    if (config.isEnableReasoning()) {
                        String thinking = thinkingBlock.getThinking();
                        if (thinking != null && !thinking.isEmpty()) {
                            String messageId = msg.getId();

                            // 尚未开始时启动推理消息
                            if (!state.hasStartedReasoningMessage(messageId)) {
                                events.add(
                                        new AguiEvent.ReasoningMessageStart(
                                                state.threadId,
                                                state.runId,
                                                messageId,
                                                "reasoning"));
                                state.startReasoningMessage(messageId);
                            }

                            if (!event.isLast()) {
                                // 增量模式下思考内容已经是增量
                                events.add(
                                        new AguiEvent.ReasoningMessageContent(
                                                state.threadId, state.runId, messageId, thinking));
                            } else {
                                // 最后一个事件时结束推理消息
                                events.add(
                                        new AguiEvent.ReasoningMessageEnd(
                                                state.threadId, state.runId, messageId));
                                state.endReasoningMessage(messageId);
                            }
                        }
                    }
                    // 禁用推理时忽略 ThinkingBlock 内容，以保持
                    // 向后兼容。
                } else if (block instanceof ToolUseBlock toolUse) {
                    // 启动工具调用前结束所有活动文本消息
                    if (state.hasActiveTextMessage()) {
                        String activeMessageId = state.getCurrentTextMessageId();
                        events.add(
                                new AguiEvent.TextMessageEnd(
                                        state.threadId, state.runId, activeMessageId));
                        state.endMessage(activeMessageId);
                    }

                    // 启动工具调用前结束所有活动推理消息
                    if (state.hasActiveReasoningMessage()) {
                        String activeReasoningMessageId = state.getCurrentReasoningMessageId();
                        events.add(
                                new AguiEvent.ReasoningMessageEnd(
                                        state.threadId, state.runId, activeReasoningMessageId));
                        state.endReasoningMessage(activeReasoningMessageId);
                    }

                    // 发送工具调用开始事件
                    String toolCallId = toolUse.getId();
                    if (toolCallId == null) {
                        toolCallId = UUID.randomUUID().toString();
                    }

                    if (!state.hasStartedToolCall(toolCallId)) {
                        events.add(
                                new AguiEvent.ToolCallStart(
                                        state.threadId,
                                        state.runId,
                                        toolCallId,
                                        toolUse.getName()));
                        state.startToolCall(toolCallId);
                    }

                    // 启用时发送工具调用参数
                    if (config.isEmitToolCallArgs() && !event.isLast()) {
                        String args = toolUse.getContent();
                        if (args != null && !args.isEmpty()) {
                            events.add(
                                    new AguiEvent.ToolCallArgs(
                                            state.threadId, state.runId, toolCallId, args));
                        }
                    }
                }
            }
        } else if (type == EventType.TOOL_RESULT && event.isLast()) {
            // 处理工具结果
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ToolResultBlock toolResult) {
                    String toolCallId = toolResult.getId();
                    if (toolCallId == null) {
                        toolCallId = UUID.randomUUID().toString();
                    }

                    String result = extractToolResultText(toolResult);

                    boolean hasStarted = state.hasStartedToolCall(toolCallId);
                    if (!hasStarted) {
                        String toolName = toolResult.getName();
                        if (toolName == null || toolName.isBlank()) {
                            toolName = "unknown";
                        }
                        events.add(
                                new AguiEvent.ToolCallStart(
                                        state.threadId, state.runId, toolCallId, toolName));
                        state.startToolCall(toolCallId);
                    }

                    // 确保发送 ToolCallEnd 以关闭参数阶段
                    events.add(new AguiEvent.ToolCallEnd(state.threadId, state.runId, toolCallId));

                    events.add(
                            new AguiEvent.ToolCallResult(
                                    state.threadId,
                                    state.runId,
                                    toolCallId,
                                    result,
                                    "tool",
                                    msg.getId()));
                    state.endToolCall(toolCallId);
                }
            }
        }

        return events;
    }

    /**
     * 发送待处理的结束事件和 RUN_FINISHED，完成运行。
     *
     * @param state 转换状态
     * @return 最终事件 Flux
     * @deprecated 自 2.0.0 起 v2 流清理由 {@link AguiStreamContext} 处理。
     */
    @Deprecated(since = "2.0.0", forRemoval = false)
    private Flux<AguiEvent> finishRun(EventConversionState state) {
        List<AguiEvent> events = new ArrayList<>();

        // 结束尚未正确结束的消息
        for (String messageId : state.getStartedMessages()) {
            if (!state.hasEndedMessage(messageId)) {
                events.add(new AguiEvent.TextMessageEnd(state.threadId, state.runId, messageId));
            }
        }

        // 结束尚未正确结束的工具调用
        for (String toolCallId : state.getStartedToolCalls()) {
            if (!state.hasEndedToolCall(toolCallId)) {
                events.add(new AguiEvent.ToolCallEnd(state.threadId, state.runId, toolCallId));
            }
        }

        // 结束尚未正确结束的推理消息
        for (String messageId : state.getStartedReasoningMessages()) {
            if (!state.hasEndedReasoningMessage(messageId)) {
                events.add(
                        new AguiEvent.ReasoningMessageEnd(state.threadId, state.runId, messageId));
            }
        }

        // 发送 RUN_FINISHED
        events.add(new AguiEvent.RunFinished(state.threadId, state.runId));

        return Flux.fromIterable(events);
    }

    /**
     * 从工具结果块提取文本内容。
     *
     * @param toolResult 工具结果块
     * @return 文本内容；不存在时为 null
     * @deprecated 自 2.0.0 起工具结果聚合由 v2 转换器处理。
     */
    @Deprecated(since = "2.0.0", forRemoval = false)
    private String extractToolResultText(ToolResultBlock toolResult) {
        if (toolResult.getOutput() == null || toolResult.getOutput().isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (ContentBlock output : toolResult.getOutput()) {
            if (output instanceof TextBlock textBlock) {
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(textBlock.getText());
            }
        }

        return !sb.isEmpty() ? sb.toString() : null;
    }

    /**
     * 事件转换状态跟踪器。
     * 使用 LinkedHashSet 保持插入顺序，确保事件顺序正确。
     *
     * @deprecated 自 2.0.0 起使用 {@link AguiStreamContext} 保存 streamEvents() 转换状态。
     */
    @Deprecated(since = "2.0.0", forRemoval = false)
    private static class EventConversionState {
        final String threadId;
        final String runId;
        private final Set<String> startedMessages = new LinkedHashSet<>();
        private final Set<String> endedMessages = new LinkedHashSet<>();
        private final Set<String> startedToolCalls = new LinkedHashSet<>();
        private final Set<String> endedToolCalls = new LinkedHashSet<>();
        private final Set<String> startedReasoningMessages = new LinkedHashSet<>();
        private final Set<String> endedReasoningMessages = new LinkedHashSet<>();
        private String currentTextMessageId = null;
        private String currentReasoningMessageId = null;

        EventConversionState(String threadId, String runId) {
            this.threadId = threadId;
            this.runId = runId;
        }

        boolean hasStartedMessage(String messageId) {
            return startedMessages.contains(messageId);
        }

        void startMessage(String messageId) {
            startedMessages.add(messageId);
            currentTextMessageId = messageId;
        }

        void endMessage(String messageId) {
            endedMessages.add(messageId);
            if (Objects.equals(messageId, currentTextMessageId)) {
                currentTextMessageId = null;
            }
        }

        boolean hasEndedMessage(String messageId) {
            return endedMessages.contains(messageId);
        }

        String getCurrentTextMessageId() {
            return currentTextMessageId;
        }

        boolean hasActiveTextMessage() {
            return currentTextMessageId != null && !hasEndedMessage(currentTextMessageId);
        }

        Set<String> getStartedMessages() {
            return startedMessages;
        }

        boolean hasStartedToolCall(String toolCallId) {
            return startedToolCalls.contains(toolCallId);
        }

        void startToolCall(String toolCallId) {
            startedToolCalls.add(toolCallId);
        }

        void endToolCall(String toolCallId) {
            endedToolCalls.add(toolCallId);
        }

        boolean hasEndedToolCall(String toolCallId) {
            return endedToolCalls.contains(toolCallId);
        }

        Set<String> getStartedToolCalls() {
            return startedToolCalls;
        }

        boolean hasStartedReasoningMessage(String messageId) {
            return startedReasoningMessages.contains(messageId);
        }

        void startReasoningMessage(String messageId) {
            startedReasoningMessages.add(messageId);
            currentReasoningMessageId = messageId;
        }

        void endReasoningMessage(String messageId) {
            endedReasoningMessages.add(messageId);
            if (Objects.equals(messageId, currentReasoningMessageId)) {
                currentReasoningMessageId = null;
            }
        }

        boolean hasEndedReasoningMessage(String messageId) {
            return endedReasoningMessages.contains(messageId);
        }

        String getCurrentReasoningMessageId() {
            return currentReasoningMessageId;
        }

        boolean hasActiveReasoningMessage() {
            return currentReasoningMessageId != null
                    && !hasEndedReasoningMessage(currentReasoningMessageId);
        }

        Set<String> getStartedReasoningMessages() {
            return startedReasoningMessages;
        }
    }
}
