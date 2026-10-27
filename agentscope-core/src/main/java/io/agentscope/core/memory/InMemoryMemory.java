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

import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentStateStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 支持状态持久化的内存式 Memory 实现。
 *
 * 此实现使用线程安全集合在内存中存储消息，
 * 并提供状态序列化／反序列化以管理会话。
 *
 * @deprecated 自 2.0.0 起弃用。对话上下文位于
 *     {@link io.agentscope.core.state.AgentState#getContext()}；保留此类是为了
 *     与 1.0.x 用户代码保持源码兼容。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public class InMemoryMemory implements Memory {

    private final List<Msg> messages = new CopyOnWriteArrayList<>();

    /** 存储键前缀。 */
    private static final String KEY_PREFIX = "memory";

    /**
     * InMemoryMemory 的构造器。
     */
    public InMemoryMemory() {}

    // ==================== StateModule 实现 ====================

    /**
     * 将记忆状态保存到会话。
     *
     * <p>向会话传递完整消息列表，包括列表为空的情况。
     * 增量存储由 AgentStateStore 实现负责（例如 JsonFileAgentStateStore 根据文件行数
     * 只追加新条目）。
     *
     * @param stateStore 要保存到的状态存储
     * @param userId 用户标识符
     * @param sessionId 会话标识符
     */
    @Override
    public void saveTo(AgentStateStore stateStore, String userId, String sessionId) {
        // 始终保存，即使为空也要保存，确保清空后的状态被持久化
        stateStore.save(userId, sessionId, KEY_PREFIX + "_messages", new ArrayList<>(messages));
    }

    /**
     * 从存储加载记忆状态。
     *
     * @param stateStore 要从中加载的状态存储
     * @param userId 可为空的用户标识符
     * @param sessionId 会话标识符
     */
    @Override
    public void loadFrom(AgentStateStore stateStore, String userId, String sessionId) {
        List<Msg> loaded =
                stateStore.getList(userId, sessionId, KEY_PREFIX + "_messages", Msg.class);
        messages.clear();
        messages.addAll(loaded);
    }

    // ==================== Memory 接口实现 ====================

    /**
     * 向内存消息列表添加消息。
     *
     * <p>由于使用 CopyOnWriteArrayList，此方法是线程安全的。
     *
     * @param message 要添加到记忆的消息
     */
    @Override
    public void addMessage(Msg message) {
        messages.add(message);
    }

    /**
     * 获取记忆中所有非 null 消息。
     *
     * <p>此方法过滤 null 条目并返回新的列表副本，支持
     * 线程安全的并发读取。
     *
     * @return 包含所有非 null 消息的新列表
     */
    @Override
    public List<Msg> getMessages() {
        return messages.stream().filter(Objects::nonNull).collect(Collectors.toList());
    }

    /**
     * 删除指定索引处的消息。
     *
     * <p>由于使用 CopyOnWriteArrayList，此方法是线程安全的。索引
     * 越界时不执行操作（不抛出异常）。
     *
     * @param index 要删除消息的索引（从 0 开始）
     */
    @Override
    public void deleteMessage(int index) {
        if (index >= 0 && index < messages.size()) {
            messages.remove(index);
        }
    }

    /**
     * 清除记忆中的所有消息。
     *
     * <p>由于使用 CopyOnWriteArrayList，此方法是线程安全的。
     */
    @Override
    public void clear() {
        messages.clear();
    }
}
