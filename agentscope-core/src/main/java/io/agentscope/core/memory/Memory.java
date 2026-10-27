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
import java.util.List;

/**
 * 用于存储和管理对话历史的记忆组件接口。
 *
 * <p>不同记忆实现可以采用内存、数据库或窗口等存储策略。
 * {@link #saveTo}／{@link #loadFrom} 允许调用方为旧版 v1 会话
 * 通过 {@link AgentStateStore} 持久化消息缓冲区；新代码应
 * 改用 {@link io.agentscope.core.state.AgentState}。
 *
 * @deprecated 自 2.0.0 起弃用。对话上下文现保存在
 *     {@link io.agentscope.core.state.AgentState#getContext()}。保留此接口作为只写镜像，
 *     以与 1.0.x 用户代码保持源码兼容。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public interface Memory {

    /**
     * 以 {@code memory_messages} 会话键保存消息缓冲区。
     */
    void saveTo(AgentStateStore stateStore, String userId, String sessionId);

    /**
     * 加载先前保存的消息缓冲区（不存在时不执行操作）。
     */
    void loadFrom(AgentStateStore stateStore, String userId, String sessionId);

    /**
     * 向记忆添加消息。
     *
     * @param message 要存入记忆的消息
     */
    void addMessage(Msg message);

    /**
     * 获取记忆中存储的所有消息。
     *
     * @return 所有消息的列表（可为空，但绝不为 null）
     */
    List<Msg> getMessages();

    /**
     * 删除指定索引处的消息。
     *
     * <p>索引越界（负数或 >= size）时，此操作应直接不执行，
     * 而不是抛出异常；这样即使存在并发修改，也能安全清理。
     *
     * @param index 要删除消息的索引（从 0 开始）
     */
    void deleteMessage(int index);

    /**
     * 清除记忆中的所有消息。
     *
     * <p>此操作移除所有已存储的对话历史。请谨慎使用，
     * 除非状态已持久化，否则通常不可撤销。
     */
    void clear();
}
