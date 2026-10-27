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
package io.agentscope.harness.agent.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地工作区的尽力而为 SQLite 索引。
 *
 * <p>跟踪已物化到本地、位于两个路径前缀下的文件：
 * {@code agents/&#42;/sessions/&#42;&#42;} 和 {@code memory/&#42;&#42;}。
 * 在远程后端工作区模式中，索引用于加速 {@code ls / glob / exists / grep}，
 * 枚举前缀下的路径时避免扫描整个存储的键。索引绝不存储文件<em>内容</em>；
 * {@code grep} 仍从远程存储读取各候选文件，以远端内容为准。
 *
 * <p><strong>一致性模型：</strong>索引尽力更新，可能落后于远程变化。远程写入仍是权威来源。
 * 索引更新失败只记录日志，不向调用方传播。
 *
 * <p><strong>线程安全：</strong>SQLite 通过自身事务机制串行化并发写入，不需要外部锁。
 *
 * <p>【学习提示】这是性能辅助索引而不是权威数据副本；不能用索引中的缺失或旧元数据，
 * 证明远程文件不存在或未发生变化。
 */
public class WorkspaceIndex implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceIndex.class);

    /** 存储在 index_state 中的模式版本；数据库模式变化时递增。 */
    private static final int SCHEMA_VERSION = 1;

    private static final String INDEX_DIR = ".index";
    private static final String INDEX_DB = "workspace.db";

    /**
     * 允许建立索引的工作区相对路径前缀。
     * 只跟踪位于这些前缀下的文件。
     */
    private static final List<String> INDEXED_PREFIXES =
            List.of(WorkspaceConstants.AGENTS_DIR + "/", WorkspaceConstants.MEMORY_DIR + "/");

    private final Connection conn;

    // -------------------------------------------------------------------------
    // 工厂
    // -------------------------------------------------------------------------

    /**
     * 为给定工作区根目录打开或创建索引。
     *
     * <p>无法初始化时返回 {@code null}；调用方应将 {@code null} 索引视为不可用，并回退到远程扫描。
     *
     * @param workspaceRoot 工作区根目录的绝对路径
     * @return 可用的 {@link WorkspaceIndex}，失败时返回 {@code null}
     */
    public static WorkspaceIndex open(Path workspaceRoot) {
        try {
            Path indexDir = workspaceRoot.resolve(INDEX_DIR);
            Files.createDirectories(indexDir);
            Path dbFile = indexDir.resolve(INDEX_DB);
            String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
            Connection c = DriverManager.getConnection(url);
            WorkspaceIndex idx = new WorkspaceIndex(c);
            idx.initSchema();
            return idx;
        } catch (Exception e) {
            log.warn("WorkspaceIndex unavailable (non-fatal): {}", e.getMessage());
            return null;
        }
    }

    private WorkspaceIndex(Connection conn) {
        this.conn = conn;
    }

    // -------------------------------------------------------------------------
    // 数据库模式
    // -------------------------------------------------------------------------

    private void initSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS files ("
                            + "path         TEXT PRIMARY KEY,"
                            + "size_bytes   INTEGER,"
                            + "modified_at  TEXT,"
                            + "content_type TEXT,"
                            + "encoding     TEXT,"
                            + "present_local INTEGER DEFAULT 1"
                            + ")");
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS index_state ("
                            + "key TEXT PRIMARY KEY,"
                            + "value TEXT"
                            + ")");
            // 记录模式版本：使用 INSERT OR IGNORE，只写入一次。
            st.executeUpdate(
                    "INSERT OR IGNORE INTO index_state(key, value) VALUES ('schema_version', '"
                            + SCHEMA_VERSION
                            + "')");
        }
    }

    // -------------------------------------------------------------------------
    // 写操作
    // -------------------------------------------------------------------------

    /**
     * 在索引中插入或更新文件条目。路径不属于索引前缀或发生任何错误时，静默跳过。
     *
     * @param path 工作区相对路径，使用正斜杠
     * @param sizeBytes 文件字节数，未知时传入 {@code -1}
     * @param modifiedAt ISO-8601 时间戳字符串；传入 {@code null} 使用当前时间
     */
    public void upsert(String path, long sizeBytes, String modifiedAt) {
        if (!isIndexable(path)) {
            return;
        }
        try {
            String ts = modifiedAt != null ? modifiedAt : Instant.now().toString();
            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "INSERT INTO files(path, size_bytes, modified_at, present_local)"
                                    + " VALUES(?,?,?,1)"
                                    + " ON CONFLICT(path) DO UPDATE SET"
                                    + "  size_bytes=excluded.size_bytes,"
                                    + "  modified_at=excluded.modified_at,"
                                    + "  present_local=1")) {
                ps.setString(1, path);
                ps.setLong(2, sizeBytes);
                ps.setString(3, ts);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            log.debug("Index upsert failed for '{}' (non-fatal): {}", path, e.getMessage());
        }
    }

    /**
     * 便捷重载：本地文件存在时从中读取文件大小。
     *
     * @param path 工作区相对路径
     * @param localFile 磁盘上的绝对路径，用于读取大小和修改时间；可以不存在
     */
    public void upsertFromLocalFile(String path, Path localFile) {
        if (!isIndexable(path)) {
            return;
        }
        try {
            long size = -1;
            String mtime = null;
            if (Files.isRegularFile(localFile)) {
                size = Files.size(localFile);
                mtime = Files.getLastModifiedTime(localFile).toInstant().toString();
            }
            upsert(path, size, mtime);
        } catch (IOException e) {
            log.debug("Index upsert (stat) failed for '{}' (non-fatal): {}", path, e.getMessage());
        }
    }

    /**
     * 从索引中移除文件条目，出错时静默跳过。
     *
     * @param path 工作区相对路径
     */
    public void remove(String path) {
        if (!isIndexable(path)) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM files WHERE path=?")) {
            ps.setString(1, path);
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("Index remove failed for '{}' (non-fatal): {}", path, e.getMessage());
        }
    }

    /** 将索引条目从 {@code fromPath} 重命名（移动）到 {@code toPath}，出错时静默跳过。 */
    public void rename(String fromPath, String toPath) {
        if (!isIndexable(fromPath) && !isIndexable(toPath)) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE files SET path=? WHERE path=?")) {
            ps.setString(1, toPath);
            ps.setString(2, fromPath);
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug(
                    "Index rename failed '{}' -> '{}' (non-fatal): {}",
                    fromPath,
                    toPath,
                    e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // 读操作
    // -------------------------------------------------------------------------

    /** 返回 {@code true} 表示索引中存在给定路径且 {@code present_local = 1}。 */
    public boolean exists(String path) {
        if (!isIndexable(path)) {
            return false;
        }
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT 1 FROM files WHERE path=? AND present_local=1 LIMIT 1")) {
            ps.setString(1, path);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            log.debug("Index exists failed for '{}' (non-fatal): {}", path, e.getMessage());
            return false;
        }
    }

    /**
     * 返回以给定前缀开头、已在本地存在的所有路径。
     *
     * @param prefix 工作区相对目录前缀，例如 {@code agents/a1/sessions/}
     * @return 匹配路径的列表，可能为空
     */
    public List<String> listByPrefix(String prefix) {
        List<String> result = new ArrayList<>();
        if (prefix == null) {
            return result;
        }
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT path FROM files WHERE path LIKE ? AND present_local=1")) {
            ps.setString(1, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
            }
        } catch (Exception e) {
            log.debug("Index listByPrefix failed for '{}' (non-fatal): {}", prefix, e.getMessage());
        }
        return result;
    }

    /**
     * 索引在给定前缀下存在任意条目时返回 true。
     * 只需判断存在性时，比 {@link #listByPrefix} 更快。
     */
    public boolean hasPrefix(String prefix) {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT 1 FROM files WHERE path LIKE ? AND present_local=1 LIMIT 1")) {
            ps.setString(1, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            log.debug("Index hasPrefix failed (non-fatal): {}", e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // 重建
    // -------------------------------------------------------------------------

    /**
     * 遍历本地工作区目录（{@code agents/&#42;/sessions} 和 {@code memory}）重建索引。
     * 替换现有条目，移除已不存在文件的过时条目。
     *
     * <p>此操作尽力执行：错误仅记录日志，不抛出。
     *
     * @param workspaceRoot 工作区根目录的绝对路径
     */
    public void rebuildFromDisk(Path workspaceRoot) {
        try {
            // 清除现有条目。
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DELETE FROM files");
            }

            for (String prefix : INDEXED_PREFIXES) {
                Path dir = workspaceRoot.resolve(prefix);
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (var stream = Files.walk(dir)) {
                    stream.filter(Files::isRegularFile)
                            .forEach(
                                    file -> {
                                        Path rel =
                                                workspaceRoot
                                                        .toAbsolutePath()
                                                        .relativize(file.toAbsolutePath());
                                        String relStr = rel.toString().replace('\\', '/');
                                        upsertFromLocalFile(relStr, file);
                                    });
                }
            }
            log.info("WorkspaceIndex rebuilt from local disk");
        } catch (Exception e) {
            log.warn("WorkspaceIndex rebuild failed (non-fatal): {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // 生命周期
    // -------------------------------------------------------------------------

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            log.debug("WorkspaceIndex close error (non-fatal): {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // 辅助方法
    // -------------------------------------------------------------------------

    /** 给定工作区相对路径位于某个索引目录前缀下时返回 {@code true}。 */
    static boolean isIndexable(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String prefix : INDEXED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
