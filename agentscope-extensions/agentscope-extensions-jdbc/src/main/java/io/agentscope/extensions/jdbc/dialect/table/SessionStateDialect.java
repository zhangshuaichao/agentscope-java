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
package io.agentscope.extensions.jdbc.dialect.table;

import io.agentscope.extensions.jdbc.dialect.BoundSql;
import java.util.List;

/**
 * 会话状态表的表领域方言接口。
 *
 * <p>按 {@code (session_id, state_key, item_index)} 存储智能体状态。
 * 单值状态使用 {@code item_index = 0}；列表状态每元素一行。
 *
 * <p>方法名以 {@code sessionState} 为前缀，避免聚合类实现多个表领域接口时名称冲突。
 *
 * <p>辅助索引：频繁使用的 {@code WHERE session_id = ?} 及
 * {@code session_id LIKE 'prefix%'} 查询需要 {@code session_id} 索引。
 * 支持内联索引的数据库（MySQL）在 {@code CREATE TABLE} 中写入
 * {@code INDEX idx_session (session_id)}；其他数据库在
 * {@link #sessionStateCreateTableDdls()} 列表中追加幂等
 * {@code CREATE INDEX IF NOT EXISTS} 作为第二条语句。
 *
 * @author shanhongyu
 */
public interface SessionStateDialect {

    /**
     * 基础表名（不带前缀）。
     */
    default String sessionStateTableName() {
        return "sessions";
    }

    // ------------------------------------------------------------------
    // 抽象定义：每种数据库都必须覆盖
    // ------------------------------------------------------------------

    /**
     * 创建会话表的一条或多条 DDL 语句，必须幂等。
     */
    List<String> sessionStateCreateTableDdls();

    /**
     * 对单值状态执行 UPSERT，冲突时更新 state_data。
     */
    BoundSql sessionStateUpsert(String sessionId, String stateKey, int itemIndex, String stateData);

    // ------------------------------------------------------------------
    // 默认实现：ANSI 基线
    // ------------------------------------------------------------------

    /**
     * 列表元素的 INSERT，每元素一行。
     */
    default BoundSql sessionStateInsert(
            String sessionId, String stateKey, int itemIndex, String stateData) {
        return new BoundSql(
                "INSERT INTO "
                        + sessionStateTableName()
                        + " (session_id, state_key, item_index, state_data) VALUES (?, ?, ?, ?)",
                sessionId,
                stateKey,
                itemIndex,
                stateData);
    }

    /**
     * 以 {@code version = 1} 插入单值状态。
     * {@code saveIfVersion} 在 {@code expectedVersion == 0} 时使用（不存在才创建）；
     * 主键冲突时调用方回退到 {@link #sessionStateUpdateIfVersion}，使用
     * {@code expectedVersion = 0}，使回填版本为 0 的行仍可满足 CAS；
     * 非零版本不匹配时映射为 {@link io.agentscope.core.state.AgentStateStore#UNVERSIONED}。
     */
    default BoundSql sessionStateInsertIfAbsent(
            String sessionId, String stateKey, int itemIndex, String stateData) {
        return new BoundSql(
                "INSERT INTO "
                        + sessionStateTableName()
                        + " (session_id, state_key, item_index, state_data, version)"
                        + " VALUES (?, ?, ?, ?, 1)",
                sessionId,
                stateKey,
                itemIndex,
                stateData);
    }

    /**
     * 以 {@code version == expectedVersion} 为条件的比较并交换 UPDATE。
     */
    default BoundSql sessionStateUpdateIfVersion(
            String sessionId,
            String stateKey,
            int itemIndex,
            String stateData,
            long expectedVersion) {
        return new BoundSql(
                "UPDATE "
                        + sessionStateTableName()
                        + " SET state_data = ?, version = ? WHERE session_id = ? AND state_key = ?"
                        + " AND item_index = ? AND version = ?",
                stateData,
                expectedVersion + 1L,
                sessionId,
                stateKey,
                itemIndex,
                expectedVersion);
    }

    /**
     * 按 item_index 查询单值状态，返回列：state_data。
     */
    default BoundSql sessionStateSelect(String sessionId, String stateKey, int itemIndex) {
        return new BoundSql(
                "SELECT state_data FROM "
                        + sessionStateTableName()
                        + " WHERE session_id = ? AND state_key = ? AND item_index = ?",
                sessionId,
                stateKey,
                itemIndex);
    }

    /**
     * 带乐观并发版本的单值状态查询，返回列：state_data、version。
     */
    default BoundSql sessionStateSelectVersioned(String sessionId, String stateKey, int itemIndex) {
        return new BoundSql(
                "SELECT state_data, version FROM "
                        + sessionStateTableName()
                        + " WHERE session_id = ? AND state_key = ? AND item_index = ?",
                sessionId,
                stateKey,
                itemIndex);
    }

    /**
     * 有序列表查询，返回列：state_data。
     */
    default BoundSql sessionStateSelectList(String sessionId, String stateKey) {
        return new BoundSql(
                "SELECT state_data FROM "
                        + sessionStateTableName()
                        + " WHERE session_id = ? AND state_key = ? ORDER BY item_index",
                sessionId,
                stateKey);
    }

    /**
     * 删除某状态键的全部元素。
     */
    default BoundSql sessionStateDeleteByKey(String sessionId, String stateKey) {
        return new BoundSql(
                "DELETE FROM "
                        + sessionStateTableName()
                        + " WHERE session_id = ? AND state_key = ?",
                sessionId,
                stateKey);
    }

    /**
     * 删除整个会话。
     */
    default BoundSql sessionStateDeleteSession(String sessionId) {
        return new BoundSql(
                "DELETE FROM " + sessionStateTableName() + " WHERE session_id = ?", sessionId);
    }

    /**
     * 用于变更检测的 MAX(item_index)，无行时返回 NULL。
     */
    default BoundSql sessionStateSelectMaxIndex(String sessionId, String stateKey) {
        return new BoundSql(
                "SELECT MAX(item_index) FROM "
                        + sessionStateTableName()
                        + " WHERE session_id = ? AND state_key = ?",
                sessionId,
                stateKey);
    }

    /**
     * 探测会话是否存在。
     */
    default BoundSql sessionStateExists(String sessionId) {
        return new BoundSql(
                "SELECT 1 FROM " + sessionStateTableName() + " WHERE session_id = ? LIMIT 1",
                sessionId);
    }

    /**
     * 列出匹配前缀模式的不重复会话 ID。
     */
    default BoundSql sessionStateListSessionIds(String prefixPattern) {
        return new BoundSql(
                "SELECT DISTINCT session_id FROM "
                        + sessionStateTableName()
                        + " WHERE session_id LIKE ? ESCAPE '"
                        + sessionStateLikeEscapeChar()
                        + "' ORDER BY session_id",
                prefixPattern);
    }

    /**
     * {@link #sessionStateListSessionIds} 中 LIKE 模式的转义字符。
     */
    default char sessionStateLikeEscapeChar() {
        return '!';
    }
}
