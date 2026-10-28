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
package io.agentscope.extensions.agentprotocol;

import io.agentscope.harness.agent.subagent.protocol.RemoteAgentEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** 用于 Agent Protocol SSE 流传输的逐任务事件总线。通过重放缓冲让晚到的订阅者或重连客户端从 {@code fromSeq} 开始补收事件。 */
public final class AgentProtocolTaskEventBus implements AgentProtocolEventBus {

    private static final Logger log = LoggerFactory.getLogger(AgentProtocolTaskEventBus.class);

    private final int replayBufferSize;
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    public AgentProtocolTaskEventBus() {
        this(256);
    }

    public AgentProtocolTaskEventBus(int replayBufferSize) {
        this.replayBufferSize = Math.max(16, replayBufferSize);
    }

    /** 发布事件并分配单调递增的 {@code seq}。 */
    @Override
    public RemoteAgentEvent publish(String taskId, RemoteAgentEvent event) {
        Channel ch = channels.computeIfAbsent(taskId, id -> new Channel(replayBufferSize));
        // 【学习提示】原子计数保证分配不同序号，却不保证并发发布按序送达；tryEmitNext 失败只记录日志，
        // 不会重试，所以该进程内缓冲不是可靠消息队列或持久化事件日志。
        long seq = ch.seq.incrementAndGet();
        event.setSeq(seq);
        event.setTaskId(taskId);
        Sinks.EmitResult result = ch.sink.tryEmitNext(event);
        if (result.isFailure()) {
            log.debug("Failed to emit event seq={} for task {}: {}", seq, taskId, result);
        }
        return event;
    }

    /**
     * 订阅 {@code taskId} 的事件，可跳过满足 {@code seq <=
     * fromSeq} 的事件。
     */
    @Override
    public Flux<RemoteAgentEvent> subscribe(String taskId, long fromSeq) {
        Channel ch = channels.computeIfAbsent(taskId, id -> new Channel(replayBufferSize));
        Flux<RemoteAgentEvent> flux = ch.sink.asFlux();
        if (fromSeq > 0) {
            return flux.filter(e -> e.getSeq() > fromSeq);
        }
        return flux;
    }

    /** 发布终态事件后，完成并移除通道。 */
    @Override
    public void complete(String taskId) {
        // 【学习提示】移除通道也移除了新订阅者寻找旧缓冲的入口；终结后再次订阅会创建新通道，
        // 无法据 fromSeq 恢复已移除的历史。进程重启同样不会保留这些缓冲和序号。
        Channel ch = channels.remove(taskId);
        if (ch != null) {
            ch.sink.tryEmitComplete();
        }
    }

    private static final class Channel {
        private final Sinks.Many<RemoteAgentEvent> sink;
        private final AtomicLong seq = new AtomicLong();

        Channel(int replayBufferSize) {
            this.sink = Sinks.many().replay().limit(replayBufferSize);
        }
    }
}
