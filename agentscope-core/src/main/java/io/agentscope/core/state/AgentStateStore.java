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
package io.agentscope.core.state;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * AgentScope 智能体状态的持久化存储接口。
 *
 * <p>{@code AgentStateStore} 为 {@link State} 对象提供保存、加载、删除和列举操作，
 * 以 {@code (userId, sessionId)} 二元组寻址，使智能体、记忆、工具集等
 * 有状态组件能够跨应用运行或用户交互保存和恢复。
 *
 * <p>槽位寻址刻意保持简单：
 * <ul>
 *   <li>{@code sessionId} — 不可为 null 或空白，标识一次对话／会话。
 *   <li>{@code userId} — 可为空。{@code null} 表示匿名／单租户调用方
 *       （例如命令行使用和测试）。实现将所有匿名会话归入同一命名空间。
 * </ul>
 *
 * <p>实现自行决定如何将二元组组合成存储键（文件路径、Redis 键前缀或 SQL 列）。
 * 调用方不得在调用前手工拼接二者。
 *
 * <p>【学习提示】用户与会话是两个独立维度：同名 sessionId 在不同用户下应进入不同槽位。
 * 命名空间用于数据寻址与隔离，不代替应用层的用户身份认证和访问授权。
 *
 * <p>用法示例：
 *
 * <pre>{@code
 * AgentStateStore store = new JsonFileAgentStateStore(Path.of("state"));
 *
 * // 保存匿名会话状态
 * store.save(null, "session-1", "agent_state", state);
 *
 * // 保存用户作用域内的状态
 * store.save("alice", "session-1", "agent_state", state);
 *
 * // 加载状态
 * Optional<AgentState> loaded =
 *         store.get("alice", "session-1", "agent_state", AgentState.class);
 *
 * // 列出用户的所有会话，null 表示匿名会话
 * Set<String> mySessions = store.listSessionIds("alice");
 * }</pre>
 */
public interface AgentStateStore {

    /**
     * 不支持乐观并发的后端所用的哨兵版本。CAS 写入发生冲突、且没有任何写入时，
     * {@link #saveIfVersion} 也返回此值。
     */
    long UNVERSIONED = -1L;

    /**
     * 保存单个状态值（完整替换）。
     *
     * <p>保存单个状态对象，替换同一键已有的值。
     *
     * @param userId 可为空的用户标识符；{@code null} 表示匿名
     * @param sessionId 会话标识符；不可为 null 或空白
     * @param key 状态键（例如 {@code "agent_state"}、{@code "toolkit_activeGroups"}）
     * @param value 要保存的状态值
     */
    void save(String userId, String sessionId, String key, State value);

    /**
     * 此后端是否通过 {@link #getVersioned} 和 {@link #saveIfVersion} 支持乐观并发。
     *
     * <p>默认为 {@code false}：{@link #saveIfVersion} 退化为无条件 {@link #save}。
     */
    default boolean supportsVersioning() {
        return false;
    }

    /**
     * 加载单个状态值及其存储版本。
     *
     * <p>默认实现包装 {@link #get}，并报告 {@link #UNVERSIONED}。
     *
     * @param userId 可为空的用户标识符
     * @param sessionId 会话标识符；不可为 null 或空白
     * @param key 状态键
     * @param type 期望的状态类型
     * @param <T> 状态类型
     * @return 一个 {@link VersionedState}；不存在时 {@code value} 为 {@code null}。
     *     支持版本控制的后端在键不存在时报告 {@code version == 0}。
     */
    default <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        return new VersionedState<>(get(userId, sessionId, key, type).orElse(null), UNVERSIONED);
    }

    /**
     * 对单个状态值执行比较并交换（CAS）写入。
     *
     * <p>【学习提示】读取时记住版本，写入时提交该版本；若期间有其他写入者更新了状态，
     * 比较会失败。是否支持这种保护必须检查 supportsVersioning()，不能仅看接口方法存在。
     *
     * <ul>
     *   <li>{@code expectedVersion == 0} — 不存在时创建。如果键已存在，
     *       但存储版本仍为 {@code 0}（例如通过模式迁移回填的行），
     *       CAS 条件仍满足，并将值写入版本 {@code 1}。仅当键存在且版本非零时失败。
     *   <li>{@code expectedVersion == }{@link #UNVERSIONED} — 无条件覆盖
     *       （等同于 {@link #save}）。
     *   <li>其他情况 — 仅在存储版本等于 {@code expectedVersion} 时写入。
     * </ul>
     *
     * <p>成功时返回新版本；冲突时返回 {@link #UNVERSIONED} 且不写入。
     * 默认实现总是调用 {@link #save} 并返回 {@link
     * #UNVERSIONED}，为不支持版本控制的后端保留旧版最后写入者获胜的行为。
     *
     * @param userId 可为空的用户标识符
     * @param sessionId 会话标识符；不可为 null 或空白
     * @param key 状态键
     * @param value 新状态值
     * @param expectedVersion 调用方观察到的版本
     * @return 成功时为新版本；冲突或后端不支持版本控制时为 {@link #UNVERSIONED}
     */
    default long saveIfVersion(
            String userId, String sessionId, String key, State value, long expectedVersion) {
        save(userId, sessionId, key, value);
        return UNVERSIONED;
    }

    /**
     * 保存状态值列表。
     *
     * <p>不同实现可以采用不同的存储策略：
     * <ul>
     *   <li>{@link JsonFileAgentStateStore}：增量追加，只写入新增元素
     *   <li>{@link InMemoryAgentStateStore}：完整替换，替换整个列表
     * </ul>
     *
     * <p>调用方应始终传入完整列表，由实现决定存储策略。
     *
     * @param userId 可为空的用户标识符
     * @param sessionId 会话标识符；不可为 null 或空白
     * @param key 状态键（例如 {@code "memory_messages"}）
     * @param values 完整的状态值列表
     */
    void save(String userId, String sessionId, String key, List<? extends State> values);

    /**
     * 获取单个状态值。
     *
     * @param userId 可为空的用户标识符
     * @param sessionId 会话标识符；不可为 null 或空白
     * @param key 状态键
     * @param type 期望的状态类型
     * @param <T> 状态类型
     * @return 状态值；未找到时为空
     */
    <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type);

    /**
     * 获取状态值列表。
     *
     * @param userId 可为空的用户标识符
     * @param sessionId 会话标识符；不可为 null 或空白
     * @param key 状态键
     * @param itemType 期望的元素类型
     * @param <T> 元素类型
     * @return 状态值列表；未找到时为空列表
     */
    <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType);

    /**
     * 检查会话是否存在。
     *
     * @param userId 可为空的用户标识符
     * @param sessionId 会话标识符；不可为 null 或空白
     * @return 会话存在任何已持久化状态时返回 true
     */
    boolean exists(String userId, String sessionId);

    /**
     * 删除会话及其全部数据。
     *
     * @param userId 可为空的用户标识符
     * @param sessionId 会话标识符；不可为 null 或空白
     */
    void delete(String userId, String sessionId);

    /**
     * 删除会话内的单个状态条目。
     *
     * @param userId 可为空的用户标识符
     * @param sessionId 会话标识符；不可为 null 或空白
     * @param key 要删除的状态键
     */
    default void delete(String userId, String sessionId, String key) {
        // 默认不执行操作；支持按键删除的实现应重写此方法
    }

    /**
     * 列出指定用户命名空间下可见的会话标识符。
     *
     * <p>使用 {@code userId == null} 列出匿名会话；传入具体用户则仅列出该用户的会话。
     * 本接口不提供一次调用跨用户列举的能力；这属于独立的管理职责
     * （admin starter 通过遍历已知用户实现）。
     *
     * @param userId 可为空的用户标识符
     * @return 存储在 {@code userId} 下的会话标识符集合
     */
    Set<String> listSessionIds(String userId);

    /** 清理此存储使用的资源。需要清理资源的实现应重写此方法。 */
    default void close() {
        // 默认实现不执行任何操作
    }
}
