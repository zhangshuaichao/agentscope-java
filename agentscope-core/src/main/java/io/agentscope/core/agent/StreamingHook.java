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
package io.agentscope.core.agent;

import io.agentscope.core.hook.ActingChunkEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PostSummaryEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.hook.SummaryChunkEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

/**
 * 流式事件的内部钩子实现。
 *
 * <p>拦截钩子回调，向 FluxSink 发出 {@link Event} 实例；负责事件过滤及分块模式处理。
 *
 * @deprecated 自 2.0.0 起弃用，由 {@link io.agentscope.core.middleware.MiddlewareBase} 系统替代。
 * 细粒度事件流请使用 {@link io.agentscope.core.ReActAgent#streamEvents(java.util.List)}。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
@SuppressWarnings("deprecation")
public class StreamingHook implements Hook {

    private final FluxSink<Event> sink;
    private final StreamOptions options;

    // 跟踪之前的内容，供增量模式使用
    private final Map<String, List<ContentBlock>> previousContent = new HashMap<>();

    /**
     * 创建新的流式钩子。
     *
     * @param sink 接收事件的 FluxSink
     * @param options 流式配置选项
     */
    public StreamingHook(FluxSink<Event> sink, StreamOptions options) {
        this.sink = sink;
        this.options = options;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostReasoningEvent) {
            PostReasoningEvent e = (PostReasoningEvent) event;
            // 流式处理完成后调用 postReasoning
            // 这是最后一条／完整消息
            if (options.shouldStream(EventType.REASONING)
                    && options.shouldIncludeReasoningEmission(false)) {
                emitEvent(EventType.REASONING, e.getReasoningMessage(), true);
            }
            return Mono.just(event);
        } else if (event instanceof ReasoningChunkEvent) {
            ReasoningChunkEvent e = (ReasoningChunkEvent) event;
            // 这是中间片段
            if (options.shouldStream(EventType.REASONING)
                    && options.shouldIncludeReasoningEmission(true)) {
                // 根据 StreamOptions 使用增量或累积内容
                Msg msgToEmit =
                        options.isIncremental() ? e.getIncrementalChunk() : e.getAccumulated();
                emitEvent(EventType.REASONING, msgToEmit, false);
            }
            return Mono.just(event);
        } else if (event instanceof PostActingEvent) {
            PostActingEvent e = (PostActingEvent) event;
            // 工具执行完成
            if (options.shouldStream(EventType.TOOL_RESULT)) {
                Msg toolMsg = createToolMessage(e.getToolResult());
                emitEvent(EventType.TOOL_RESULT, toolMsg, true);
            }
            return Mono.just(event);
        } else if (event instanceof ActingChunkEvent) {
            ActingChunkEvent e = (ActingChunkEvent) event;
            // 工具中间片段
            if (options.shouldStream(EventType.TOOL_RESULT) && options.isIncludeActingChunk()) {
                Msg toolMsg = createToolMessage(e.getChunk());
                emitEvent(EventType.TOOL_RESULT, toolMsg, false);
            }
            return Mono.just(event);
        } else if (event instanceof PostSummaryEvent) {
            PostSummaryEvent e = (PostSummaryEvent) event;
            // 摘要生成完成
            if (options.shouldStream(EventType.SUMMARY)
                    && options.shouldIncludeSummaryEmission(false)) {
                emitEvent(EventType.SUMMARY, e.getSummaryMessage(), true);
            }
            return Mono.just(event);
        } else if (event instanceof SummaryChunkEvent) {
            SummaryChunkEvent e = (SummaryChunkEvent) event;
            // 摘要中间片段
            if (options.shouldStream(EventType.SUMMARY)
                    && options.shouldIncludeSummaryEmission(true)) {
                // 根据 StreamOptions 使用增量或累积内容
                Msg msgToEmit =
                        options.isIncremental() ? e.getIncrementalChunk() : e.getAccumulated();
                emitEvent(EventType.SUMMARY, msgToEmit, false);
            }
            return Mono.just(event);
        }
        return Mono.just(event);
    }

    // ========== 辅助方法 ==========

    /**
     * 根据工具结果块创建工具消息。
     *
     * @param toolResultBlock 工具结果或片段
     * @return 角色为 TOOL 且包含结果的消息
     */
    private Msg createToolMessage(ToolResultBlock toolResultBlock) {
        return Msg.builder()
                .name("system")
                .role(MsgRole.TOOL)
                .content(List.of(toolResultBlock))
                .build();
    }

    /**
     * 向 sink 发出事件。
     *
     * @param type 事件类型
     * @param msg 消息
     * @param isLast 是否为流中的最后一条／完整消息
     */
    private void emitEvent(EventType type, Msg msg, boolean isLast) {
        Msg processedMsg = msg;

        // 在增量模式下计算差异（如将来需要）
        // 当前直接使用 ReasoningChunkEvent 的增量片段

        // 创建并发出事件
        Event event = new Event(type, processedMsg, isLast);
        sink.next(event);

        // 更新跟踪信息
        if (!isLast) {
            previousContent.put(msg.getId(), new ArrayList<>(msg.getContent()));
        } else {
            previousContent.remove(msg.getId());
        }
    }
}
