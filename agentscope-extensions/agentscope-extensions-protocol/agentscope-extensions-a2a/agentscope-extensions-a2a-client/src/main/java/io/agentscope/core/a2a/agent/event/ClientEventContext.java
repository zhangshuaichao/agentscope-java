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

package io.agentscope.core.a2a.agent.event;

import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.a2aproject.sdk.spec.Task;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

/**
 * 处理 {@link org.a2aproject.sdk.client.ClientEvent} 的上下文。
 *
 * <p>单个 A2A 任务可能多次响应，因此需要上下文保存响应。
 */
public class ClientEventContext {

    private final String currentRequestId;

    private final A2aAgent agent;

    private MonoSink<Msg> sink;

    private List<Hook> hooks;

    /**
     * 接收回复事件；无人流式订阅时为 {@code null}。
     */
    private AgentEventEmitter eventEmitter;

    private String replyId;

    private Task task;

    /**
     * 暂存本次调用时的完整历史对话上下文，专供
     * {@link #publishPreReasoning()} 构造推理前事件使用。
     */
    private List<Msg> inputMessages;

    // 确保生命周期事件只触发一次
    private final AtomicBoolean preReasoningFired = new AtomicBoolean(false);
    private final AtomicBoolean postReasoningFired = new AtomicBoolean(false);
    private final AtomicBoolean terminalDelivered = new AtomicBoolean(false);

    public ClientEventContext(String currentRequestId, A2aAgent agent) {
        this.currentRequestId = currentRequestId;
        this.agent = agent;
    }

    public String getCurrentRequestId() {
        return currentRequestId;
    }

    public A2aAgent getAgent() {
        return agent;
    }

    public MonoSink<Msg> getSink() {
        return sink;
    }

    public void setSink(MonoSink<Msg> sink) {
        this.sink = sink;
    }

    public List<Hook> getHooks() {
        return hooks;
    }

    public void setHooks(List<Hook> hooks) {
        this.hooks = hooks;
    }

    public Task getTask() {
        return task;
    }

    public void setTask(Task task) {
        this.task = task;
    }

    /**
     * 设置回复事件的接收位置，参见 {@link A2aAgent#streamEvents(List)}。
     *
     * @param replyId 这些事件所属回复的 ID
     * @param eventEmitter 事件接收器
     */
    public void setEventEmitter(String replyId, AgentEventEmitter eventEmitter) {
        this.replyId = replyId;
        this.eventEmitter = eventEmitter;
    }

    public void setInputMessages(List<Msg> inputMessages) {
        this.inputMessages = inputMessages;
    }

    public boolean isTerminalDelivered() {
        return terminalDelivered.get();
    }

    public boolean complete(Msg msg) {
        if (sink == null || !terminalDelivered.compareAndSet(false, true)) {
            return false;
        }
        sink.success(msg);
        return true;
    }

    public void completeExceptionally(Throwable error) {
        if (sink == null || !terminalDelivered.compareAndSet(false, true)) {
            return;
        }
        sink.error(error);
    }

    // ==========================================
    // 统一事件发布 API
    // ==========================================

    /**
     * 触发 PreReasoningEvent（只触发一次）
     */
    void publishPreReasoning() {
        if (hooks != null && !hooks.isEmpty() && preReasoningFired.compareAndSet(false, true)) {
            List<Msg> msgs = inputMessages == null ? List.of() : inputMessages;
            PreReasoningEvent preEvent = new PreReasoningEvent(agent, "A2A", null, msgs);

            Mono<PreReasoningEvent> eventMono = Mono.just(preEvent);
            for (Hook hook : hooks) {
                eventMono = eventMono.flatMap(hook::onEvent);
            }
            eventMono.block();
        }
    }

    /**
     * 触发 ReasoningChunkEvent（流式过程）
     */
    void publishReasoningChunk(Msg chunkMsg) {
        emitDeltas(chunkMsg);
        if (hooks != null && !hooks.isEmpty()) {
            publishPreReasoning(); // 此前未发送 Pre 时，先发送 Pre
            ReasoningChunkEvent chunkEvent =
                    new ReasoningChunkEvent(agent, "A2A", null, chunkMsg, chunkMsg);

            Mono<ReasoningChunkEvent> eventMono = Mono.just(chunkEvent);
            for (Hook hook : hooks) {
                eventMono = eventMono.flatMap(hook::onEvent);
            }
            eventMono.block();
        }
    }

    /**
     * 将流式分块的文本与思考作为回复增量发出。
     */
    private void emitDeltas(Msg chunkMsg) {
        if (eventEmitter == null) {
            return;
        }
        for (ContentBlock block : chunkMsg.getContent()) {
            if (block instanceof TextBlock text) {
                eventEmitter.emit(new TextBlockDeltaEvent(replyId, "text", text.getText()));
            } else if (block instanceof ThinkingBlock thinking) {
                eventEmitter.emit(
                        new ThinkingBlockDeltaEvent(replyId, "thinking", thinking.getThinking()));
            }
        }
    }

    /**
     * 触发 PostReasoningEvent（只触发一次），并在钩子有机会修改后返回最终推理消息。
     *
     * @param finalMsg 原始最终推理消息
     * @return 钩子修改后的推理消息；未运行钩子或未应用修改时返回 {@code finalMsg}
     */
    Msg publishPostReasoning(Msg finalMsg) {
        if (hooks != null && !hooks.isEmpty() && postReasoningFired.compareAndSet(false, true)) {
            publishPreReasoning();
            PostReasoningEvent postEvent = new PostReasoningEvent(agent, "A2A", null, finalMsg);

            Mono<PostReasoningEvent> eventMono = Mono.just(postEvent);
            for (Hook hook : hooks) {
                eventMono = eventMono.flatMap(hook::onEvent);
            }

            postEvent = eventMono.block();
            if (postEvent != null) {
                Msg modifiedMsg = postEvent.getReasoningMessage();
                return modifiedMsg != null ? modifiedMsg : finalMsg;
            }
        }
        return finalMsg;
    }
}
