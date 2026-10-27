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
package io.agentscope.core.memory;

import static io.agentscope.core.memory.LongTermMemoryTools.wrap;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * 自动管理记忆的静态长期记忆钩子。
 *
 * <p>【学习提示】此类是旧接口兼容实现，不代表当前 Harness 记忆架构。
 * 下方上游说明沿用 PreReasoningEvent／系统消息的旧描述；当前 onEvent 实际处理
 * PreCallEvent，并把检索结果作为 USER 消息追加在输入末尾。请结合实现阅读。
 *
 * <p>此钩子实现长期记忆的 STATIC_CONTROL 模式，由框架自动处理
 * 记忆检索和记录，无须智能体主动参与。
 * 钩子执行以下操作：
 * <ol>
 *   <li>推理前检索相关记忆（PreReasoningEvent）</li>
 *   <li>将检索记忆作为系统消息注入上下文</li>
 *   <li>智能体回复后将对话记录到长期记忆（PostCallEvent）</li>
 * </ol>
 *
 * <p><b>使用时机：</b>
 * <ul>
 *   <li>STATIC_CONTROL 模式：框架自动管理记忆</li>
 *   <li>BOTH 模式：与智能体控制工具结合使用</li>
 * </ul>
 *
 * <p><b>使用示例：</b>
 * <pre>{@code
 * LongTermMemory memory = Mem0LongTermMemory.builder()
 *     .agentName("Assistant")
 *     .userName("user_123")
 *     .build();
 *
 * StaticLongTermMemoryHook hook = new StaticLongTermMemoryHook(memory, agentMemory);
 *
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(model)
 *     .hook(hook)  // Hook is automatically registered in STATIC_CONTROL/BOTH mode
 *     .build();
 * }</pre>
 *
 * <p><b>优先级：</b>此钩子具有较高优先级（50），确保记忆检索在事件链中
 * 较早发生，先于其他处理。
 *
 * @see LongTermMemory
 * @see LongTermMemoryMode
 * @deprecated 自 2.0.0 起弃用。长期记忆与 {@code Hook} 系统已移除；如有需要，
 *     请基于 {@link io.agentscope.core.middleware.MiddlewareBase} 重新实现。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public class StaticLongTermMemoryHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(StaticLongTermMemoryHook.class);
    // 长期记忆记录专用的异步调度器：
    // - 最多 1 个并发工作线程
    // - 最多 3 个排队任务（饱和时拒绝新任务）
    // 避免在全局 boundedElastic 调度器上无界排队。
    private static final Scheduler ASYNC_RECORD_SCHEDULER =
            Schedulers.newBoundedElastic(1, 3, "long-term-memory-record");

    private final LongTermMemory longTermMemory;
    private final Memory memory;
    private final boolean asyncRecord;

    /**
     * 创建采用同步记录方式的 StaticLongTermMemoryHook。
     *
     * @param longTermMemory 用于持久化存储的长期记忆实例
     * @param memory 用于访问对话历史的智能体记忆
     * @throws IllegalArgumentException 当 longTermMemory 或 memory 为 null 时抛出
     */
    public StaticLongTermMemoryHook(LongTermMemory longTermMemory, Memory memory) {
        this(longTermMemory, memory, false);
    }

    /**
     * 创建新的 StaticLongTermMemoryHook。
     *
     * @param longTermMemory 用于持久化存储的长期记忆实例
     * @param memory 用于访问对话历史的智能体记忆
     * @param asyncRecord 是否异步记录记忆（发出后不等待）
     * @throws IllegalArgumentException 当 longTermMemory 或 memory 为 null 时抛出
     */
    public StaticLongTermMemoryHook(
            LongTermMemory longTermMemory, Memory memory, boolean asyncRecord) {
        if (longTermMemory == null) {
            throw new IllegalArgumentException("Long-term memory cannot be null");
        }
        if (memory == null) {
            throw new IllegalArgumentException("Memory cannot be null");
        }
        this.longTermMemory = longTermMemory;
        this.memory = memory;
        this.asyncRecord = asyncRecord;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreCallEvent preCallEvent) {
            @SuppressWarnings("unchecked")
            Mono<T> result = (Mono<T>) handlePreCall(preCallEvent);
            return result;
        } else if (event instanceof PostCallEvent postCallEvent) {
            @SuppressWarnings("unchecked")
            Mono<T> result = (Mono<T>) handlePostCall(postCallEvent);
            return result;
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        // 使用较高优先级，以便在钩子链中尽早执行
        return 50;
    }

    /**
     * 通过检索并注入相关记忆来处理 PreCallEvent。
     *
     * <p>检索与用户查询相关的记忆，并将其作为用户消息
     * 追加到消息列表末尾。记忆用
     * {@code <long_term_memory>} 标签包装，以便识别。
     *
     * @param event PreCallEvent 事件
     * @return 包含可能已修改事件的 Mono
     */
    private Mono<PreCallEvent> handlePreCall(PreCallEvent event) {
        List<Msg> inputMessages = event.getInputMessages();
        if (inputMessages == null || inputMessages.isEmpty()) {
            return Mono.just(event);
        }
        // 提取最后一条用户消息作为查询
        int queryMsgIndex = extractLastUserMessageIndex(inputMessages);
        if (queryMsgIndex < 0) {
            return Mono.just(event);
        }

        // 检索相关记忆
        return longTermMemory
                .retrieve(inputMessages.get(queryMsgIndex))
                .filter(memoryText -> memoryText != null && !memoryText.isEmpty())
                .flatMap(
                        memoryText -> {
                            // 用标签包装记忆内容
                            String wrappedMemory = wrap(memoryText);

                            // 创建包含检索记忆的用户消息
                            Msg memoryMsg =
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .name("long_term_memory")
                                            .content(
                                                    TextBlock.builder().text(wrappedMemory).build())
                                            .build();

                            // 将记忆消息注入末尾
                            List<Msg> enhancedMessages = new ArrayList<>(inputMessages);
                            enhancedMessages.add(memoryMsg);
                            event.setInputMessages(enhancedMessages);

                            return Mono.just(event);
                        })
                .defaultIfEmpty(event)
                .onErrorResume(
                        error -> {
                            // 记录错误，但不中断流程
                            log.warn(
                                    "Failed to retrieve from long-term memory: {}",
                                    error.getMessage());
                            return Mono.just(event);
                        });
    }

    /**
     * 通过将对话记录到长期记忆来处理 PostCallEvent。
     *
     * <p>将智能体记忆中的所有消息记录到长期存储。这样，
     * 长期记忆后端（例如 Mem0）可以从完整对话上下文中
     * 提取值得记住的信息。
     *
     * <p>启用 {@code asyncRecord} 时，以发出后不等待的方式记录，
     * 不会阻塞智能体响应。异步记录
     * 使用有界调度器（1 个工作线程，队列容量 3）。饱和时丢弃新的记录
     * 任务，并写入日志。
     *
     * <p><b>权衡：</b>此异步路径有意将记录操作与
     * 主事件链解耦。此模式不保留返回的订阅，因此
     * 此类不会显式取消正在执行的记录任务。
     * 否则，会先完成记录，再返回事件。
     *
     * @param event PostCallEvent 事件
     * @return 包含未修改事件的 Mono
     */
    private Mono<PostCallEvent> handlePostCall(PostCallEvent event) {
        // 从记忆获取所有消息
        List<Msg> allMessages = memory.getMessages();
        if (allMessages == null || allMessages.isEmpty()) {
            return Mono.just(event);
        }

        // 记录到长期记忆
        if (asyncRecord) {
            // 发出后不等待：调度到专用有界调度器，使智能体响应
            // 不被阻塞，同时限制积压增长。
            return Mono.deferContextual(
                    ctxView -> {
                        longTermMemory
                                .record(allMessages)
                                .subscribeOn(ASYNC_RECORD_SCHEDULER)
                                .contextWrite(context -> context.putAll(ctxView))
                                .onErrorResume(
                                        error -> {
                                            log.warn(
                                                    "Failed to asynchronously record to long-term"
                                                            + " memory: {}",
                                                    error.getMessage());
                                            return Mono.empty();
                                        })
                                .subscribe();
                        return Mono.just(event);
                    });
        } else {
            return longTermMemory
                    .record(allMessages)
                    .thenReturn(event)
                    .onErrorResume(
                            error -> {
                                // 记录错误，但不中断流程
                                log.warn(
                                        "Failed to record to long-term memory: {}",
                                        error.getMessage());
                                return Mono.just(event);
                            });
        }
    }

    /**
     * 从消息列表提取最后一条用户消息。
     *
     * <p>从后向前扫描消息列表，查找最近的用户消息；
     * 它通常是记忆检索应使用的当前查询。
     *
     * @param messages 消息列表
     * @return 最后一条用户消息；未找到时为 null
     */
    private int extractLastUserMessageIndex(List<Msg> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.getRole() == MsgRole.USER) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 获取此钩子使用的长期记忆实例。
     *
     * @return 长期记忆实例
     */
    public LongTermMemory getLongTermMemory() {
        return longTermMemory;
    }

    /**
     * 获取此钩子使用的记忆实例。
     *
     * @return 记忆实例
     */
    public Memory getMemory() {
        return memory;
    }
}
