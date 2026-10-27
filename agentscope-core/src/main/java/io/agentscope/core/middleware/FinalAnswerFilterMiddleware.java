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
package io.agentscope.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 仅暴露 ReAct 事件流中最后一轮推理产生的文本。
 *
 * <p>文本事件先缓冲，直到当前模型调用完成。如果模型在该轮产生工具调用，
 * 则抑制已缓冲及后续的文本事件；否则，在对应的 {@link ModelCallEndEvent}
 * 之前发出缓冲的文本事件。
 *
 * <p>此中间件需要显式启用，不会改变 {@code ReActAgent} 的默认行为。
 * 只有观察到工具调用后，才能确定某轮属于中间轮次，因此当前轮的文本
 * 要等到模型调用完成或检测到工具调用之后，才会决定是否发出。
 */
public class FinalAnswerFilterMiddleware implements MiddlewareBase {

    /** 仅声明所需的拦截点；子类若重写更多钩子，必须扩展此集合。 */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_REASONING);
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        // 【学习提示】defer 为每次订阅单独创建 RoundState，避免不同订阅共享缓冲区。
        // concatMap 保持事件处理顺序；doFinally 在完成、出错或取消时清理本轮状态。
        return Flux.defer(
                () -> {
                    RoundState state = new RoundState();
                    return next.apply(input)
                            .concatMap(state::handle)
                            .doFinally(signal -> state.clear());
                });
    }

    private static boolean isTextBlockEvent(AgentEvent event) {
        return event instanceof TextBlockStartEvent
                || event instanceof TextBlockDeltaEvent
                || event instanceof TextBlockEndEvent;
    }

    private static final class RoundState {
        private final List<AgentEvent> bufferedTextEvents = new ArrayList<>();
        private String replyId;
        private boolean toolCallSeen;

        private Flux<AgentEvent> handle(AgentEvent event) {
            if (event instanceof ModelCallStartEvent start) {
                replyId = start.getReplyId();
                toolCallSeen = false;
                bufferedTextEvents.clear();
                return Flux.just(event);
            }

            if (isTextBlockEvent(event)) {
                if (isCurrentReply(event) && !toolCallSeen) {
                    bufferedTextEvents.add(event);
                    return Flux.empty();
                }
                if (isCurrentReply(event)) {
                    return Flux.empty();
                }
                return Flux.just(event);
            }

            if (event instanceof ToolCallStartEvent toolCall
                    && Objects.equals(replyId, toolCall.getReplyId())) {
                toolCallSeen = true;
                bufferedTextEvents.clear();
                return Flux.just(event);
            }

            if (event instanceof ModelCallEndEvent end && isCurrentReply(end)) {
                if (toolCallSeen) {
                    clear();
                    return Flux.just(event);
                }

                // 【学习提示】先复制缓冲区，再清理状态；返回流使用副本，
                // 因而不会因 clear() 而丢失尚未向下游发出的最终文本。
                List<AgentEvent> finalEvents = new ArrayList<>(bufferedTextEvents);
                finalEvents.add(event);
                clear();
                return Flux.fromIterable(finalEvents);
            }

            return Flux.just(event);
        }

        private boolean isCurrentReply(AgentEvent event) {
            String eventReplyId = getReplyId(event);
            return replyId != null && Objects.equals(replyId, eventReplyId);
        }

        private static String getReplyId(AgentEvent event) {
            if (event instanceof TextBlockStartEvent textStart) {
                return textStart.getReplyId();
            }
            if (event instanceof TextBlockDeltaEvent textDelta) {
                return textDelta.getReplyId();
            }
            if (event instanceof TextBlockEndEvent textEnd) {
                return textEnd.getReplyId();
            }
            if (event instanceof ModelCallEndEvent modelEnd) {
                return modelEnd.getReplyId();
            }
            return null;
        }

        private void clear() {
            bufferedTextEvents.clear();
            replyId = null;
            toolCallSeen = false;
        }
    }
}
