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
package io.agentscope.harness.coding.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 每次 LLM 推理步骤前，将排队消息注入系统提示词的中间件。
 * <ol>
 * <li>从分发器设置的 {@link #CURRENT_THREAD_ID} 读取当前线程 ID</li>
 * <li>检查 {@code SqliteBaseStore} 的 {@code ["queue", thread_id]} 命名空间</li>
 * <li>通过 {@link #onSystemPrompt} 将本批排队消息追加到系统提示词</li>
 * <li>逐条删除本批已读取消息，未读取条目留待后续</li>
 * </ol>
 *
 * <p>调用方（webhook 处理器 / CLI 分发器）必须在调用 {@code gateway.run()} 前设置 {@link #CURRENT_THREAD_ID}，之后移除：
 * <pre>{@code
 * MessageQueueMiddleware.CURRENT_THREAD_ID.set(threadId);
 * try {
 *     gateway.run(ctx, messages, address).block();
 * } finally {
 *     MessageQueueMiddleware.CURRENT_THREAD_ID.remove();
 * }
 * }</pre>
 *
 * <p>【学习提示】ThreadLocal 绑定的是 Java 线程，不会自动跨 Reactor 调度切换传播。队列按最多 100 条读取并逐条删除，不是事务性领取；中途失败回退原提示词时，已删除消息不会自动恢复。生产化需另外评估并发领取、重试和外部文本注入系统提示的信任边界。
 */
public class MessageQueueMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(MessageQueueMiddleware.class);

    /**
     * 保存当前进行中智能体运行线程 ID 的 ThreadLocal。分发器或 webhook 处理器须在调用 {@code gateway.run()} 前设置。
     */
    public static final ThreadLocal<String> CURRENT_THREAD_ID = new ThreadLocal<>();

    private final BaseStore store;

    public MessageQueueMiddleware(BaseStore store) {
        this.store = store;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        String threadId = CURRENT_THREAD_ID.get();
        if (threadId == null || threadId.isBlank()) {
            return Mono.just(currentPrompt);
        }
        return Mono.fromCallable(() -> drainQueue(currentPrompt, threadId))
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "[message-queue-middleware] Error draining queue for thread"
                                            + " {}: {}",
                                    threadId,
                                    e.getMessage());
                            return Mono.just(currentPrompt);
                        });
    }

    private String drainQueue(String currentPrompt, String threadId) {
        List<String> ns = List.of("queue", threadId);
        List<StoreItem> items = store.search(ns, 100, 0);
        if (items.isEmpty()) {
            return currentPrompt;
        }
        StringBuilder injected = new StringBuilder(currentPrompt != null ? currentPrompt : "");
        injected.append("\n\n[Message Queue — injected before this reasoning step]\n");
        for (StoreItem item : items) {
            Object payload = item.value().get("payload");
            if (payload != null) {
                injected.append("- ").append(payload).append("\n");
            }
            store.delete(ns, item.key());
        }
        log.debug(
                "[message-queue-middleware] Injected {} queued messages for thread {}",
                items.size(),
                threadId);
        return injected.toString();
    }
}
