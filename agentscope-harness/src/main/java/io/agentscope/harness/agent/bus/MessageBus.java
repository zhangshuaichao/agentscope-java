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
package io.agentscope.harness.agent.bus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 用于跨会话和进程协调工作的实时消息传输接口。
 *
 * <p>暴露三种消费模式：
 * <ul><li><b>消费队列</b>（模式 A）：单消费者，读取即确认；返回记录即移出存储。</li>
 * <li><b>重放日志</b>（模式 C）：多消费者，由外部限制大小；读者各自维护游标，记录保留到裁剪或超出 maxLen。</li>
 * <li><b>瞬时广播</b>（模式 D）：即发即忘的发布订阅；仅当前订阅者收到载荷，不保留历史。</li></ul>
 *
 * <p>领域辅助方法 {@link #inboxPush}、{@link #inboxDrain}、{@link #enqueueWakeup}
 * 通过固定键命名约定将高层概念映射到这些基础操作。
 *
 * <p>实现：<ul><li>{@code WorkspaceMessageBus}：无需外部依赖的工作区实现；
 * 共享与并发保证取决于后端，发布订阅退化为轮询。</li></ul>
 */
public interface MessageBus extends AutoCloseable {

    // ---- 模式 A：消费队列（单消费者，读取即确认）----

    /**
     * 向指定键的消费队列追加载荷。
     * @param key 队列标识符，命名约定由调用方决定
     * @param payload 待入队的可 JSON 序列化字典
     * @return 传输层记录 ID
     */
    Mono<String> queuePush(String key, Map<String, Object> payload);

    /**
     * 从指定队列取出至多 {@code maxCount} 条记录；返回的记录被移除。
     * 未取出的旧记录仍留在队列中，下一次调用可继续读取，并非只会返回新到达记录。
     * 接口用于单消费者；具体实现的原子性需单独确认。
     * @param key 队列标识符
     * @param maxCount 返回记录的上限
     * @return 按到达顺序排列的记录；空队列返回空列表
     */
    Mono<List<BusEntry>> queueDrain(String key, int maxCount);

    /**
     * 删除指定队列及其全部记录；操作幂等。
     * @param key 队列标识符
     */
    Mono<Void> queueDelete(String key);

    /**
     * 检查指定队列是否有记录，不消费记录。
     * @param key 队列标识符
     * @return 至少有一条记录时为 true
     */
    Mono<Boolean> queuePeek(String key);

    // ---- 领域辅助方法：收件箱 ----

    /**
     * 检查会话收件箱是否有待处理消息，不消费消息。
     * @param sessionId 待检查会话
     * @return 至少有一条消息时为 true
     */
    default Mono<Boolean> inboxHasMessages(String sessionId) {
        return queuePeek("agentscope:inbox:" + sessionId);
    }

    // ---- 模式 C：重放日志（多消费者，由外部限制大小）----

    /**
     * 向指定重放日志追加载荷。读者各自维护游标；记录保留到裁剪或达到 {@code maxLen} 限制。
     * @param key 日志标识符
     * @param payload 待追加的可 JSON 序列化字典
     * @param maxLen 近似记录数量上限，超出时裁剪旧记录；{@code 0} 或负数表示不限制
     * @return 传输层记录 ID，可用于后续读取游标
     */
    Mono<String> logAppend(String key, Map<String, Object> payload, int maxLen);

    /**
     * 读取严格位于 {@code since} 之后的至多 {@code maxCount} 条日志。
     * 读取不删除记录，同一记录可供任意数量读者读取。
     * @param key 日志标识符
     * @param since 游标，只返回 ID 更新的记录；{@code null} 表示从头开始
     * @param maxCount 返回记录的上限
     * @return 追加顺序的记录；没有比 {@code since} 更新的记录时返回空列表
     */
    Mono<List<BusEntry>> logRead(String key, String since, int maxCount);

    /**
     * 删除指定日志及其全部记录；操作幂等。
     * @param key 日志标识符
     */
    Mono<Void> logTrim(String key);

    // ---- 模式 D：瞬时广播（即发即忘）----

    /**
     * 向广播频道发布载荷，仅当前订阅者收到。
     * @param key 频道标识符
     * @param payload 可 JSON 序列化字典
     */
    Mono<Void> publish(String key, Map<String, Object> payload);

    /**
     * 订阅广播频道，产生订阅建立之后发布的载荷。
     * 调用方负责订阅生命周期，取消 Flux 即结束订阅。
     * @param key 频道标识符
     * @return 载荷流
     */
    Flux<Map<String, Object>> subscribe(String key);

    // ---- 领域辅助方法：收件箱 ----

    /**
     * 将消息推入指定会话的收件箱队列。
     * @param sessionId 接收会话
     * @param msg 可 JSON 序列化载荷，通常为序列化后的 HintBlock
     */
    default Mono<Void> inboxPush(String sessionId, Map<String, Object> msg) {
        return queuePush("agentscope:inbox:" + sessionId, msg).then();
    }

    /**
     * 取出并移除会话待处理收件箱消息。
     * @param sessionId 待消费的会话
     * @param maxCount 最多消费的记录数量
     * @return 按到达顺序排列的记录
     */
    default Mono<List<BusEntry>> inboxDrain(String sessionId, int maxCount) {
        return queueDrain("agentscope:inbox:" + sessionId, maxCount);
    }

    // ---- 领域辅助方法：唤醒 ----

    /**
     * 将唤醒请求入队，并通知调度器。
     * @param userId 所属用户 ID，用于多用户隔离
     * @param sessionId 待唤醒会话
     * @param agentId 拥有该会话的智能体
     */
    default Mono<Void> enqueueWakeup(String userId, String sessionId, String agentId) {
        return queuePush(
                        "agentscope:wakeups",
                        Map.of(
                                "userId", userId != null ? userId : "",
                                "sessionId", sessionId,
                                "agentId", agentId))
                .then(publish("agentscope:wakeup_signal", Map.of()));
    }

    /**
     * 不带 userId 的便捷重载，以空 userId 委派给三参数形式。
     */
    default Mono<Void> enqueueWakeup(String sessionId, String agentId) {
        return enqueueWakeup("", sessionId, agentId);
    }

    /**
     * 订阅共享唤醒信号频道。
     * @return 信号载荷流，每个信号表示应立即消费唤醒队列
     */
    default Flux<Map<String, Object>> subscribeWakeup() {
        return subscribe("agentscope:wakeup_signal");
    }

    // ---- 领域辅助方法：会话事件 ----

    String SESSION_EVENTS_KEY_PREFIX = "agentscope:session:events:";
    int SESSION_REPLAY_MAX_LEN = 1000;

    /**
     * 将会话事件写入重放日志并实时广播。晚加入者从日志重放，已连接者通过发布订阅接收。
     * @param sessionId 事件所属会话
     * @param event 可 JSON 序列化的事件载荷
     * @return 重放日志记录 ID
     */
    default Mono<String> sessionPublishEvent(String sessionId, Map<String, Object> event) {
        String key = SESSION_EVENTS_KEY_PREFIX + sessionId;
        return logAppend(key, event, SESSION_REPLAY_MAX_LEN)
                .flatMap(
                        entryId -> {
                            Map<String, Object> live = new HashMap<>(event);
                            live.put("_entry_id", entryId);
                            return publish(key, live).thenReturn(entryId);
                        });
    }

    /**
     * 读取会话重放日志，用于追赶历史或重连。
     * @param sessionId 待读取会话
     * @param since 游标，返回严格晚于此 ID 的记录；{@code null} 表示读取全部
     * @param maxCount 返回事件上限
     * @return 按追加顺序排列的记录
     */
    default Mono<List<BusEntry>> sessionReadEvents(String sessionId, String since, int maxCount) {
        return logRead(SESSION_EVENTS_KEY_PREFIX + sessionId, since, maxCount);
    }

    /**
     * 实时订阅会话发布的事件，只产生订阅建立之后交付的载荷。
     * @param sessionId 待订阅会话
     * @return 事件载荷流
     */
    default Flux<Map<String, Object>> sessionSubscribeEvents(String sessionId) {
        return subscribe(SESSION_EVENTS_KEY_PREFIX + sessionId);
    }

    /**
     * 清理会话事件重放日志。在一次会话运行结束后调用，避免晚加入者重放上次运行的旧事件。
     * @param sessionId 待清理会话
     */
    default Mono<Void> sessionTrimEvents(String sessionId) {
        return logTrim(SESSION_EVENTS_KEY_PREFIX + sessionId);
    }

    /**
     * 释放底层传输资源，默认不执行操作。
     */
    @Override
    default void close() {}
}
