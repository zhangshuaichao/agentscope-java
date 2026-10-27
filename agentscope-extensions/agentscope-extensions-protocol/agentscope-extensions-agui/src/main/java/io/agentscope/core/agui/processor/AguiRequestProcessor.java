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
package io.agentscope.core.agui.processor;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.AguiUtil;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.adapter.AguiAgentAdapterFactory;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import io.agentscope.core.agui.runtime.AguiRuntimeContextResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * AG-UI 请求的核心处理器。
 *
 * <p>封装公共请求处理逻辑，从 MVC 和 WebFlux 处理器中提取以避免重复代码。
 *
 * <p>【学习提示】正常完成和错误传播前先清除活动标记，避免客户端立即恢复时读到旧状态；取消则由 doFinally 清理。该清理仅维护协议运行标记，不等于撤销外部操作。传输层需要中断实际会话时应使用 ProcessResult.interrupt，保留用户身份并定位当前 threadId。
 *
 * <p><b>职责：</b>
 * <ul>
 * <li>从多个来源解析智能体 ID</li>
 * <li>为服务端记忆场景提取消息</li>
 * <li>通过 {@link AgentResolver} 解析智能体</li>
 * <li>通过 {@link AguiAgentAdapter} 生成事件流</li>
 * </ul>
 *
 * <p><b>用法：</b>
 * <pre>{@code
 * AguiRequestProcessor processor = AguiRequestProcessor.builder()
 *     .agentResolver(resolver)
 *     .config(AguiAdapterConfig.defaultConfig())
 *     .build();
 *
 * AguiRuntimeContextRequest<?> request = AguiRuntimeContextRequest.builder()
 *         .input(input)
 *         .build();
 * ProcessResult result = processor.process(request);
 * Flux<AguiEvent> events = result.events();
 * }</pre>
 */
public class AguiRequestProcessor {

    private static final Logger logger = LoggerFactory.getLogger(AguiRequestProcessor.class);

    private final AgentResolver agentResolver;
    private final AguiAdapterConfig config;
    private final AguiAgentAdapterFactory adapterFactory;
    private final AguiResumeCoordinator resumeCoordinator;
    private final AguiRuntimeContextResolver runtimeContextResolver;

    private AguiRequestProcessor(Builder builder) {
        this.agentResolver =
                Objects.requireNonNull(builder.agentResolver, "agentResolver cannot be null");
        this.config = builder.config != null ? builder.config : AguiAdapterConfig.defaultConfig();
        this.adapterFactory =
                builder.adapterFactory != null
                        ? builder.adapterFactory
                        : AguiAgentAdapterFactory.defaultFactory();
        this.resumeCoordinator = new AguiResumeCoordinator();
        this.runtimeContextResolver = builder.runtimeContextResolver;
    }

    /**
     * AG-UI 请求处理结果。
     *
     * <p>包含已解析智能体（用于中断处理）和事件流。
     *
     * @param agent 已解析的智能体实例
     * @param events 事件流
     * @param runtimeContext 已解析的调用方运行上下文，可为 null
     */
    public record ProcessResult(
            Agent agent, Flux<AguiEvent> events, RuntimeContext runtimeContext) {

        /**
         * 中断本请求的活动会话。
         *
         * <p>AG-UI 使用 {@code threadId} 作为会话 ID。对于多会话 {@link ReActAgent}，应保留调用方用户 ID 并定位该会话，而非调用已弃用的无参中断方法，后者始终指向默认会话。
         *
         * @param threadId 本请求的 AG-UI 线程 ID
         */
        public void interrupt(String threadId) {
            ReActAgent reActAgent = AguiUtil.asReActAgent(agent);
            if (reActAgent != null) {
                RuntimeContext interruptContext =
                        RuntimeContext.builder(runtimeContext).sessionId(threadId).build();
                reActAgent.interrupt(interruptContext);
            } else {
                agent.interrupt();
            }
        }
    }

    /**
     * 处理 AG-UI 请求，返回包含智能体和事件流的结果。
     *
     * <p>若配置了 {@link AguiRuntimeContextResolver}，则以给定请求调用它来取得调用方 {@link RuntimeContext}。{@link AguiAgentAdapter} 复制并增强该上下文，允许调用方添加自定义属性而不替换标准 AG-UI 元数据。
     *
     * @param request 携带输入、智能体 ID、传输详情和原生请求的 AG-UI 请求上下文
     * @return 包含智能体与事件流的 ProcessResult
     */
    public ProcessResult process(AguiRuntimeContextRequest<?> request) {
        RunAgentInput input = request.getInput();
        String headerAgentId = request.getHeaderAgentId();
        String pathAgentId = request.getPathAgentId();
        String threadId = input.getThreadId();
        String runId = input.getRunId();

        RuntimeContext resolved =
                runtimeContextResolver != null ? runtimeContextResolver.resolve(request) : null;
        RuntimeContext runtimeContext =
                RuntimeContext.builder(resolved).sessionId(threadId).build();

        // 解析智能体 ID
        String agentId = resolveAgentId(input, headerAgentId, pathAgentId);

        // 解析智能体
        Agent agent = agentResolver.resolveAgent(agentId, threadId, runtimeContext.getUserId());

        Flux<AguiEvent> events =
                Flux.defer(
                        () -> {
                            AguiResumeCoordinator.ResumeContractResult beginResult =
                                    resumeCoordinator.beginRun(input);
                            if (beginResult.isError()) {
                                return Flux.fromIterable(
                                        resumeCoordinator.contractErrorEvents(
                                                input,
                                                beginResult.message(),
                                                config.isEmitRunFinishedAfterError()));
                            }

                            try {
                                // 根据服务端记忆确定有效输入
                                RunAgentInput effectiveInput = input;
                                if (agentResolver.hasMemory(runtimeContext)) {
                                    logger.debug(
                                            "Using server-side memory for thread {} user {},"
                                                    + " extracting follow-up messages",
                                            threadId,
                                            runtimeContext.getUserId());
                                    effectiveInput = extractLatestUserMessage(input);
                                }

                                RuntimeContext effectiveRuntimeContext =
                                        resumeCoordinator.addResumeInterrupts(
                                                input, runtimeContext);

                                // 创建适配器并运行
                                AguiAgentAdapter adapter = adapterFactory.create(agent, config);
                                AtomicBoolean runErrorSeen = new AtomicBoolean(false);
                                return Objects.requireNonNull(
                                                adapter.run(
                                                        effectiveInput, effectiveRuntimeContext),
                                                "adapter event stream is null")
                                        .doOnNext(
                                                event -> {
                                                    if (event instanceof AguiEvent.RunError) {
                                                        runErrorSeen.set(true);
                                                    }
                                                    resumeCoordinator.trackPendingInterrupts(
                                                            threadId,
                                                            runId,
                                                            event,
                                                            runErrorSeen.get());
                                                })
                                        // 在终止信号到达调用方前完成运行。
                                        // doFinally 在 onComplete/onError 传播后执行，
                                        // 因此收集完本轮事件的调用方若立即
                                        // 在同一线程启动下一轮运行
                                        // （常见恢复流程），可能观察到
                                        // 过期的活动运行标记并被拒绝，报错为
                                        // AGUI_INTERRUPT_CONTRACT_ERROR。doOnComplete/doOnError
                                        // 在传播前执行，提供 happens-before
                                        // 顺序保证；finishRun 幂等，doFinally
                                        // 仍覆盖取消路径。
                                        .doOnComplete(
                                                () -> resumeCoordinator.finishRun(threadId, runId))
                                        .doOnError(
                                                error ->
                                                        resumeCoordinator.finishRun(
                                                                threadId, runId))
                                        .doFinally(
                                                signalType ->
                                                        resumeCoordinator.finishRun(
                                                                threadId, runId));
                            } catch (Throwable error) {
                                resumeCoordinator.finishRun(threadId, runId);
                                return processorErrorEvents(input, error);
                            }
                        });
        return new ProcessResult(agent, events, runtimeContext);
    }

    private Flux<AguiEvent> processorErrorEvents(RunAgentInput input, Throwable error) {
        String errorMessage =
                error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        List<AguiEvent> events = new ArrayList<>();
        events.add(new AguiEvent.RunStarted(input.getThreadId(), input.getRunId(), null, input));
        events.add(
                new AguiEvent.RunError(
                        input.getThreadId(),
                        input.getRunId(),
                        errorMessage,
                        mapErrorCode(error),
                        System.currentTimeMillis(),
                        null));
        if (config.isEmitRunFinishedAfterError()) {
            events.add(new AguiEvent.RunFinished(input.getThreadId(), input.getRunId()));
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

    /**
     * 从多个来源解析智能体 ID。
     *
     * <p>优先级如下：
     * <ol>
     * <li>URL 路径变量（若提供）</li>
     * <li>HTTP 请求头（若提供）</li>
     * <li>请求体的 forwardedProps.agentId</li>
     * <li>config.defaultAgentId</li>
     * <li>"default"</li>
     * </ol>
     *
     * @param input 请求输入
     * @param headerAgentId HTTP 请求头中的智能体 ID（可为 null）
     * @param pathAgentId URL 路径变量中的智能体 ID（可为 null）
     * @return 解析出的智能体 ID
     */
    public String resolveAgentId(RunAgentInput input, String headerAgentId, String pathAgentId) {
        // 1. URL 路径变量优先级最高
        if (pathAgentId != null && !pathAgentId.isEmpty()) {
            logger.debug("Using agent ID from path variable: {}", pathAgentId);
            return pathAgentId;
        }

        // 2. 检查 HTTP 请求头
        if (headerAgentId != null && !headerAgentId.isEmpty()) {
            logger.debug("Using agent ID from header: {}", headerAgentId);
            return headerAgentId;
        }

        // 3. 检查 forwardedProps 中的 agentId
        Object agentIdProp = input.getForwardedProp("agentId");
        if (agentIdProp != null) {
            String propsAgentId = agentIdProp.toString();
            logger.debug("Using agent ID from forwardedProps: {}", propsAgentId);
            return propsAgentId;
        }

        // 4. 使用配置默认值
        if (config.getDefaultAgentId() != null) {
            logger.debug("Using default agent ID from config: {}", config.getDefaultAgentId());
            return config.getDefaultAgentId();
        }

        // 5. 回退为 "default"
        logger.debug("Using fallback agent ID: default");
        return "default";
    }

    /**
     * 提取最后一轮助手消息之后到达的消息。
     *
     * <p>启用服务端记忆时，智能体已保存此前轮次。CopilotKit 等客户端仍会发送完整对话，包括最后一条助手消息后的 HITL 工具结果。应仅追加这些尾部消息。
     *
     * <p>若对话尚无助手消息，原样返回输入。如果对话以助手轮次结束（重新生成/继续流程）且无后续消息，则返回该轮之前最后一条用户消息，使智能体获得重新生成的提示，而非空输入。
     *
     * @param input 原始输入
     * @return 仅含后续消息的新输入，或原始输入
     */
    public RunAgentInput extractLatestUserMessage(RunAgentInput input) {
        List<AguiMessage> messages = input.getMessages();
        if (messages == null || messages.isEmpty()) {
            return input;
        }

        int lastAssistantIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("assistant".equalsIgnoreCase(messages.get(i).getRole())) {
                lastAssistantIdx = i;
                break;
            }
        }
        if (lastAssistantIdx < 0) {
            return input;
        }

        List<AguiMessage> after =
                lastAssistantIdx < messages.size() - 1
                        ? List.copyOf(messages.subList(lastAssistantIdx + 1, messages.size()))
                        : List.of();

        if (after.isEmpty()) {
            for (int i = lastAssistantIdx - 1; i >= 0; i--) {
                if ("user".equalsIgnoreCase(messages.get(i).getRole())) {
                    after = List.of(messages.get(i));
                    break;
                }
            }
        }

        return RunAgentInput.builder()
                .threadId(input.getThreadId())
                .runId(input.getRunId())
                .messages(after)
                .tools(input.getTools())
                .context(input.getContext())
                .state(input.getState())
                .forwardedProps(input.getForwardedProps())
                .resume(input.getResume())
                .build();
    }

    /**
     * 创建 AguiRequestProcessor 构建器。
     *
     * @return 新构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * AguiRequestProcessor 的构建器。
     */
    public static class Builder {

        private AgentResolver agentResolver;
        private AguiAdapterConfig config;
        private AguiAgentAdapterFactory adapterFactory;
        private AguiRuntimeContextResolver runtimeContextResolver;

        /**
         * 设置智能体解析器。
         *
         * @param agentResolver 智能体解析器
         * @return 当前构建器
         */
        public Builder agentResolver(AgentResolver agentResolver) {
            this.agentResolver = agentResolver;
            return this;
        }

        /**
         * 设置适配器配置。
         *
         * @param config 适配器配置
         * @return 当前构建器
         */
        public Builder config(AguiAdapterConfig config) {
            this.config = config;
            return this;
        }

        /**
         * 设置适配器工厂。
         *
         * @param adapterFactory 创建逐请求适配器的工厂
         * @return 当前构建器
         */
        public Builder adapterFactory(AguiAgentAdapterFactory adapterFactory) {
            this.adapterFactory = adapterFactory;
            return this;
        }

        /**
         * 设置每次请求调用的运行上下文解析器，以产生调用方 {@link RuntimeContext}。可选；为 null 时不附加调用方上下文。
         *
         * @param runtimeContextResolver 每次请求使用的解析器
         * @return 当前构建器
         */
        public Builder runtimeContextResolver(AguiRuntimeContextResolver runtimeContextResolver) {
            this.runtimeContextResolver = runtimeContextResolver;
            return this;
        }

        /**
         * 构建处理器。
         *
         * @return 构建完成的处理器
         * @throws NullPointerException 未设置 agentResolver 时抛出
         */
        public AguiRequestProcessor build() {
            return new AguiRequestProcessor(this);
        }
    }
}
