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
import java.io.InputStream;
import java.util.List;

/**
 * 快照表的表领域方言接口。
 *
 * <p>按 {@code snapshot_id} 存储工作区二进制 tar 归档。
 *
 * <p>方法名以 {@code snapshot} 为前缀，避免聚合类实现多个表领域接口时名称冲突。
 *
 * @author shanhongyu
 */
public interface SnapshotDialect {

    /**
     * 基础表名（不带前缀）。
     */
    default String snapshotTableName() {
        return "snapshots";
    }

    // ------------------------------------------------------------------
    // 抽象定义：每种数据库都必须覆盖
    // ------------------------------------------------------------------

    /**
     * 创建快照表的一条或多条 DDL 语句，必须幂等。
     */
    List<String> snapshotCreateTableDdls();

    /**
     * 用于上传快照的幂等 UPSERT。
     *
     * <p>客户端通过 {@link java.sql.PreparedStatement#setBinaryStream(int, InputStream)}
     * 将 {@code data} 参数绑定为二进制流，因此应用无需先将完整归档载入堆内存，
     * 即可通过流接口向数据库传输快照。
     */
    BoundSql snapshotUpsert(String snapshotId, InputStream data);

    // ------------------------------------------------------------------
    // 默认实现：ANSI 基线
    // ------------------------------------------------------------------

    /**
     * 单快照查询，返回列：data。
     */
    default BoundSql snapshotSelect(String snapshotId) {
        return new BoundSql(
                "SELECT data FROM " + snapshotTableName() + " WHERE snapshot_id = ?", snapshotId);
    }

    /**
     * 探测快照是否存在。
     */
    default BoundSql snapshotExists(String snapshotId) {
        return new BoundSql(
                "SELECT 1 FROM " + snapshotTableName() + " WHERE snapshot_id = ? LIMIT 1",
                snapshotId);
    }
}
