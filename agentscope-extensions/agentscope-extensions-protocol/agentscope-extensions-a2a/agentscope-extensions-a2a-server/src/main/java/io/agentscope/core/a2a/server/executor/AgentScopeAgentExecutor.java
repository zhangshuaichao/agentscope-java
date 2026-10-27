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

package io.agentscope.core.a2a.server.executor;

import io.agentscope.core.a2a.agent.utils.LoggerUtil;
import io.agentscope.core.a2a.server.constants.A2aServerConstants;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.utils.MessageConvertUtil;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolProgressEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/**
 * AgentScope 的 A2A {@link AgentExecutor} 实现。
 *
 * <p>智能体实例的创建委托给 AgentRunner。默认 ReActAgent 运行器为每个请求创建新的
 * {@link io.agentscope.core.agent.Agent}；自定义运行器的实例复用策略由其实现决定。
 */
public class AgentScopeAgentExecutor implements AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeAgentExecutor.class);

    private final Map<String, Subscription> subscriptions;

    private final AgentRunner agentRunner;

    private final AgentExecuteProperties agentExecuteProperties;

    public AgentScopeAgentExecutor(
            AgentRunner agentRunner, AgentExecuteProperties agentExecuteProperties) {
        this.agentRunner = agentRunner;
        this.agentExecuteProperties = agentExecuteProperties;
        this.subscriptions = new ConcurrentHashMap<>();
    }

    @Override
    public void cancel(RequestContext context, AgentEmitter emitter) throws A2AError {
        String taskId = context.getTaskId();
        try {
            log.info("[{}] Start to Cancel Task", taskId);
            // 【学习提示】取消横跨三层：协议任务标记取消、运行器中断智能体、取消响应式订阅。
            // 只停止发送事件不等于停止智能体工作；这里分别通知各层结束当前任务。
            emitter.cancel();
            agentRunner.stop(taskId);
            Subscription subscription = subscriptions.get(taskId);
            if (null == subscription) {
                log.warn("[{}] Not found Subscription for Task.", taskId);
                return;
            }
            subscription.cancel();
        } catch (Exception e) {
            log.error("[{}] Error while cancelling task.", context.getTaskId(), e);
        }
    }

    @Override
    public void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
        try {
            List<Msg> inputMessages =
                    MessageConvertUtil.convertFromMessageToMsgs(context.getMessage());
            AgentRequestOptions requestOptions = buildAgentRequestOptions(context);
            Flux<AgentEvent> resultFlux = agentRunner.streamEvents(inputMessages, requestOptions);

            Task task = context.getTask();
            if (task == null) {
                task = newTask(context);
                log.info("[{}] Created new task.", task.id());
            } else {
                log.info("[{}] Using existing task.", task.id());
            }
            if (isBlockRequest(context)) {
                processTaskBlocking(context, emitter, task, resultFlux);
            } else {
                processTaskNonBlocking(context, emitter, task, resultFlux);
            }
            log.info("[{}] Agent execution completed successfully", context.getTaskId());
        } catch (Exception e) {
            log.error("[{}] Agent execution failed", context.getTaskId(), e);
            emitter.sendMessage("Agent execution failed: " + e.getMessage());
        }
    }

    private AgentRequestOptions buildAgentRequestOptions(RequestContext context) {
        Message message = context.getMessage();
        AgentRequestOptions requestOptions = new AgentRequestOptions();
        requestOptions.setTaskId(context.getTaskId());
        requestOptions.setUserId(getUserId(message));
        requestOptions.setSessionId(getSessionId(context));
        return requestOptions;
    }

    private String getUserId(Message message) {
        if (message.metadata() != null && message.metadata().containsKey("userId")) {
            return String.valueOf(message.metadata().get("userId"));
        }
        return "";
    }

    private String getSessionId(RequestContext context) {
        Message message = context.getMessage();
        Object legacySessionId =
                message.metadata() == null ? null : message.metadata().get("sessionId");
        if (legacySessionId != null) {
            String sessionId = String.valueOf(legacySessionId);
            if (!sessionId.isBlank()) {
                return sessionId;
            }
        }
        String contextId = context.getContextId();
        return contextId == null ? "" : contextId;
    }

    private Task newTask(RequestContext context) {
        return new Task(
                context.getTaskId(),
                context.getContextId(),
                new TaskStatus(TaskState.TASK_STATE_SUBMITTED),
                null,
                List.of(context.getMessage()),
                null);
    }

    private boolean isBlockRequest(RequestContext context) {
        // 流式请求必须非阻塞。
        ServerCallContext callContext = context.getCallContext();
        Object isStreaming =
                callContext
                        .getState()
                        .getOrDefault(A2aServerConstants.ContextKeys.IS_STREAM_KEY, Boolean.FALSE);
        if (Boolean.TRUE.equals(isStreaming)) {
            return false;
        }
        if (null == context.getConfiguration()) {
            return true;
        }
        return !Boolean.TRUE.equals(context.getConfiguration().returnImmediately());
    }

    private void processTaskBlocking(
            RequestContext context, AgentEmitter emitter, Task task, Flux<AgentEvent> resultFlux) {
        BlockingFluxEventHandler eventHandler =
                new BlockingFluxEventHandler(context, agentExecuteProperties, emitter);
        log.info("[{}] Starting blocking request processing", context.getTaskId());
        resultFlux
                .doOnSubscribe(s -> saveSubscription(context.getTaskId(), s))
                .doOnNext(eventHandler::doOnNext)
                .doOnComplete(eventHandler::doOnComplete)
                .doOnError(eventHandler::doOnError)
                .doFinally(signal -> removeSubscription(context.getTaskId(), signal))
                .blockLast();
    }

    private void processTaskNonBlocking(
            RequestContext context, AgentEmitter emitter, Task task, Flux<AgentEvent> resultFlux) {
        try {
            emitter.addTask(task);
            log.info("[{}] Starting streaming request processing", context.getTaskId());
            processStreamingOutput(resultFlux, emitter, context);
        } catch (Exception e) {
            log.error("[{}] Error processing streaming output", context.getTaskId(), e);
            try {
                emitter.fail(
                        emitter.newAgentMessage(
                                List.of(
                                        new TextPart(
                                                "Error processing streaming output: "
                                                        + e.getMessage())),
                                Map.of()));
            } catch (IllegalStateException ignored) {
                // doOnError 已将任务转为终态，无需再处理。
            }
        }
    }

    /**
     * 处理流式输出数据
     */
    private void processStreamingOutput(
            Flux<AgentEvent> resultFlux, AgentEmitter emitter, RequestContext context) {
        StreamingFluxEventHandler eventHandler =
                new StreamingFluxEventHandler(context, agentExecuteProperties, emitter);
        resultFlux
                .doOnSubscribe(
                        s -> {
                            saveSubscription(context.getTaskId(), s);
                            emitter.startWork();
                        })
                .doOnNext(eventHandler::doOnNext)
                .doOnComplete(eventHandler::doOnComplete)
                .doOnError(eventHandler::doOnError)
                .doFinally(signal -> removeSubscription(context.getTaskId(), signal))
                .blockLast();
    }

    private void saveSubscription(String taskId, Subscription subscription) {
        log.info("[{}] Subscribed to executeFunction result stream", taskId);
        subscriptions.put(taskId, subscription);
    }

    private void removeSubscription(String taskId, SignalType signal) {
        log.info("[{}] Subscribe and process stream output terminated: {}", taskId, signal);
        subscriptions.remove(taskId);
    }

    private abstract static class BaseFluxEventHandler {

        protected final RequestContext context;

        protected final List<Msg> accumulatedOutput;

        protected final AgentExecuteProperties executeProperties;

        private final Set<AgentEventType> requiredEventTypes;

        private BaseFluxEventHandler(
                RequestContext context, AgentExecuteProperties executeProperties) {
            this.context = context;
            this.executeProperties = executeProperties;
            this.accumulatedOutput = new LinkedList<>();
            this.requiredEventTypes = generateRequiredEventTypes(executeProperties);
        }

        private Set<AgentEventType> generateRequiredEventTypes(
                AgentExecuteProperties executeProperties) {
            if (executeProperties.isRequireInnerMessage()) {
                return Set.of(
                        AgentEventType.TEXT_BLOCK_DELTA,
                        AgentEventType.THINKING_BLOCK_DELTA,
                        AgentEventType.TOOL_PROGRESS,
                        AgentEventType.TOOL_RESULT_TEXT_DELTA,
                        AgentEventType.TOOL_RESULT_DATA_DELTA,
                        AgentEventType.HINT_BLOCK);
            }
            return Set.of(AgentEventType.TEXT_BLOCK_DELTA, AgentEventType.THINKING_BLOCK_DELTA);
        }

        /**
         * 用于 Flux doOnNext 处理事件的模板。
         *
         * @param output 智能体流式执行的输出事件
         */
        void doOnNext(AgentEvent output) {
            LoggerUtil.debug(
                    log, "[{}] Handle Agent execute output event: {}", context.getTaskId(), output);
            Msg responseMessage = convertToResponseMessage(output);
            // 进度是实时更新，不属于工具结果；若累积它，
            // compactStreamingChunks 会将其合并到完整消息的结果文本。
            if (responseMessage != null && !(output instanceof ToolProgressEvent)) {
                accumulatedOutput.add(responseMessage);
            }
            handleEvent(output, responseMessage);
        }

        /**
         * 通过 Flux doOnComplete 处理智能体执行完成。
         */
        abstract void doOnComplete();

        /**
         * 通过 Flux doOnError 处理智能体执行错误。
         *
         * @param t Flux 执行期间的错误
         */
        void doOnError(Throwable t) {
            log.error("[{}] Handle Agent execute error: ", context.getTaskId(), t);
            String errorMessage = "Handle Agent execute error: " + t.getMessage();
            sendErrorMessage(
                    Message.builder()
                            .role(Message.Role.ROLE_AGENT)
                            .parts(new TextPart(errorMessage))
                            .contextId(context.getContextId())
                            .taskId(context.getTaskId())
                            .build());
        }

        /**
         * 判断是否不应将事件作为响应发送给 A2A 客户端，
         * 例如生命周期事件或配置中禁用的内部事件。
         *
         * @param output 智能体输出事件
         * @return 不应响应时返回 {@code true}，否则返回 {@code false}
         */
        protected boolean isNoResponseEvent(AgentEvent output) {
            return !requiredEventTypes.contains(output.getType());
        }

        private Msg convertToResponseMessage(AgentEvent output) {
            if (isNoResponseEvent(output)) {
                return null;
            }
            if (output instanceof TextBlockDeltaEvent event) {
                return assistantMessage(
                        event,
                        event.getReplyId(),
                        TextBlock.builder().text(event.getDelta()).build());
            }
            if (output instanceof ThinkingBlockDeltaEvent event) {
                return assistantMessage(
                        event,
                        event.getReplyId(),
                        ThinkingBlock.builder().thinking(event.getDelta()).build());
            }
            if (output instanceof ToolProgressEvent event) {
                ContentBlock progress = event.getContent();
                if (progress == null) {
                    return null;
                }
                return toolResultMessage(
                        event,
                        event.getReplyId(),
                        ToolResultBlock.builder()
                                .id(event.getToolCallId())
                                .name(event.getToolCallName())
                                .output(progress)
                                .metadata(event.getMetadata())
                                .build());
            }
            if (output instanceof ToolResultTextDeltaEvent event) {
                return toolResultMessage(
                        event,
                        event.getReplyId(),
                        ToolResultBlock.builder()
                                .id(event.getToolCallId())
                                .name(event.getToolCallName())
                                .output(TextBlock.builder().text(event.getDelta()).build())
                                .metadata(event.getMetadata())
                                .build());
            }
            if (output instanceof ToolResultDataDeltaEvent event) {
                ContentBlock data = event.getData();
                if (data == null) {
                    return null;
                }
                return toolResultMessage(
                        event,
                        event.getReplyId(),
                        ToolResultBlock.builder()
                                .id(event.getToolCallId())
                                .name(event.getToolCallName())
                                .output(data)
                                .metadata(event.getMetadata())
                                .build());
            }
            if (output instanceof HintBlockEvent event) {
                return assistantMessage(
                        event,
                        event.getReplyId(),
                        new HintBlock(event.getBlockId(), event.getHint(), event.getHintSource()));
            }
            LoggerUtil.warn(
                    log,
                    "[{}] Event passed required check but matched no conversion branch: {} ({})",
                    context.getTaskId(),
                    output.getType(),
                    output.getClass().getName());
            return null;
        }

        private Msg assistantMessage(AgentEvent event, String replyId, ContentBlock contentBlock) {
            return Msg.builder()
                    .id(replyId)
                    .role(MsgRole.ASSISTANT)
                    .content(contentBlock)
                    .metadata(event.getMetadata())
                    .build();
        }

        private Msg toolResultMessage(
                AgentEvent event, String replyId, ToolResultBlock toolResultBlock) {
            return Msg.builder()
                    .id(replyId)
                    .role(MsgRole.TOOL)
                    .content(toolResultBlock)
                    .metadata(event.getMetadata())
                    .build();
        }

        /**
         * 处理事件。
         *
         * @param output 智能体流式执行输出事件
         * @param responseMessage 转换后的响应消息；事件不应发送给 A2A 客户端时为 {@code null}
         */
        protected abstract void handleEvent(AgentEvent output, Msg responseMessage);

        /**
         * 向 A2A 客户端发送错误消息。
         *
         * @param errorMessage 待发送的错误消息
         */
        protected abstract void sendErrorMessage(Message errorMessage);
    }

    private static class BlockingFluxEventHandler extends BaseFluxEventHandler {

        private final AtomicReference<Message> resultMessageRef;

        private final AgentEmitter emitter;

        private BlockingFluxEventHandler(
                RequestContext context,
                AgentExecuteProperties executeProperties,
                AgentEmitter emitter) {
            super(context, executeProperties);
            this.emitter = emitter;
            this.resultMessageRef = new AtomicReference<>();
        }

        @Override
        void doOnComplete() {
            log.info(
                    "[{}] Process agent output for blocking request completed.",
                    context.getTaskId());
            Message resultMessage =
                    null != resultMessageRef.get()
                            ? resultMessageRef.get()
                            : MessageConvertUtil.convertFromMsgToMessage(
                                    MessageConvertUtil.compactStreamingChunks(accumulatedOutput),
                                    context.getTaskId(),
                                    context.getContextId());
            emitter.sendMessage(resultMessage);
        }

        @Override
        protected void handleEvent(AgentEvent output, Msg responseMessage) {
            if (!(output instanceof AgentResultEvent resultEvent)) {
                // 非 AGENT_RESULT 消息应忽略，并按属性配置
                // 保存到 accumulatedOutput。
                return;
            }
            Msg outputMessage = resultEvent.getResult();
            Message message =
                    MessageConvertUtil.convertFromMsgToMessage(
                            outputMessage, context.getTaskId(), context.getContextId());
            resultMessageRef.set(message);
        }

        @Override
        protected void sendErrorMessage(Message errorMessage) {
            emitter.sendMessage(errorMessage);
        }
    }

    private static class StreamingFluxEventHandler extends BaseFluxEventHandler {

        private final AgentEmitter emitter;

        private final String artifactId;

        private final AtomicBoolean isFirstArtifact;

        private StreamingFluxEventHandler(
                RequestContext context,
                AgentExecuteProperties executeProperties,
                AgentEmitter emitter) {
            super(context, executeProperties);
            this.emitter = emitter;
            this.artifactId = UUID.randomUUID().toString();
            this.isFirstArtifact = new AtomicBoolean(true);
        }

        @Override
        void doOnComplete() {
            log.info(
                    "[{}] Process agent output for non-blocking request completed.",
                    context.getTaskId());
            Message completeMessage =
                    executeProperties.isCompleteWithMessage()
                            ? MessageConvertUtil.convertFromMsgToMessage(
                                    MessageConvertUtil.compactStreamingChunks(accumulatedOutput),
                                    context.getTaskId(),
                                    context.getContextId())
                            : null;
            emitter.complete(completeMessage);
        }

        @Override
        protected void handleEvent(AgentEvent output, Msg responseMessage) {
            if (responseMessage == null) {
                return;
            }
            List<Part<?>> responseParts =
                    MessageConvertUtil.convertFromContentBlocks(
                            responseMessage, isStreamingChunk(output));
            emitter.addArtifact(
                    responseParts,
                    artifactId,
                    "agent-response",
                    MessageConvertUtil.convertMetadata(responseMessage),
                    !isFirstArtifact.getAndSet(false),
                    false);
        }

        private boolean isStreamingChunk(AgentEvent output) {
            return output instanceof TextBlockDeltaEvent
                    || output instanceof ThinkingBlockDeltaEvent
                    || output instanceof ToolProgressEvent
                    || output instanceof ToolResultTextDeltaEvent
                    || output instanceof ToolResultDataDeltaEvent;
        }

        @Override
        protected void sendErrorMessage(Message errorMessage) {
            emitter.fail(errorMessage);
        }
    }
}
