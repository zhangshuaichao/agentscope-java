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
package io.agentscope.core.rag.store;

import com.pgvector.PGvector;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.VectorStoreException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import io.agentscope.core.util.JsonUtils;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * PostgreSQL pgvector 向量存储实现。
 *
 * <p>基于带 pgvector 扩展的 PostgreSQL 存储与搜索向量，实现 VDBStoreBase 统一操作接口。
 *
 * <p>使用表保存向量、文档 ID、分块 ID、内容及 JSONB 自定义载荷等元数据。
 *
 * <p>使用 pgvector JDBC 驱动（com.pgvector:pgvector），依赖示例：
 * <pre>{@code
 * <dependency>
 *     <groupId>org.postgresql</groupId>
 *     <artifactId>postgresql</artifactId>
 *     <version>42.7.4</version>
 * </dependency>
 * <dependency>
 *     <groupId>com.pgvector</groupId>
 *     <artifactId>pgvector</artifactId>
 *     <version>0.1.6</version>
 * </dependency>
 * }</pre>
 *
 * <p>前提：
 * <ul>
 * <li>安装 pgvector 扩展的 PostgreSQL 11+。</li>
 * <li>执行 CREATE EXTENSION IF NOT EXISTS vector;。</li>
 * </ul>
 *
 * <p>用法示例：
 * <pre>{@code
 * // Using builder with full configuration (recommended)
 * try (PgVectorStore store = PgVectorStore.builder()
 *         .jdbcUrl("jdbc:postgresql://localhost:5432/mydb")
 *         .username("postgres")
 *         .password("password")
 *         .schema("my_schema")  // optional, defaults to "public"
 *         .tableName("embeddings")
 *         .dimensions(1024)
 *         .distanceType(PgVectorStore.DistanceType.COSINE)
 *         .build()) {
 *     store.add(documents).block();
 *     List<Document> results = store.search(searchDto).block();
 * }
 *
 * // Using static factory method (simpler, uses defaults)
 * try (PgVectorStore store = PgVectorStore.create(
 *         "jdbc:postgresql://localhost:5432/mydb",
 *         "postgres", "password", "embeddings", 1024)) {
 *     store.add(documents).block();
 *     List<Document> results = store.search(searchDto).block();
 * }
 * }</pre>
 *
 * <p>JDBC URL 应遵循 PostgreSQL 格式：
 * <ul>
 * <li>jdbc:postgresql://host:port/database。</li>
 * <li>jdbc:postgresql://localhost:5432/vectordb。</li>
 * </ul>
 */
public class PgVectorStore implements VDBStoreBase, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStore.class);

    // 表的列名。
    private static final String COL_ID = "id";
    private static final String COL_VECTOR = "embedding";
    private static final String COL_DOC_ID = "doc_id";
    private static final String COL_CHUNK_ID = "chunk_id";
    private static final String COL_CONTENT = "content";
    private static final String COL_PAYLOAD = "payload";

    private static final String DEFAULT_SCHEMA = "public";

    /**
     * 数据库标识符（schema 和表名）校验模式。
     * 仅允许字母、数字和下划线，且必须以字母或下划线开头，防止恶意标识符造成 SQL 注入。
     */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /**
     * PostgreSQL 标识符长度限制（63 字节）。
     */
    private static final int MAX_IDENTIFIER_LENGTH = 63;

    private final String jdbcUrl;
    private final String schema;
    private final String tableName;
    private final int dimensions;
    private final DistanceType distanceType;
    private final Connection connection;
    private volatile boolean closed = false;

    /**
     * pgvector 支持的距离类型。
     */
    public enum DistanceType {
        /**
         * L2（欧氏）距离，使用 {@code <->} 操作符。
         */
        L2("vector_l2_ops", "<->"),

        /**
         * 内积，使用 {@code <#>} 操作符（归一化向量的内积越大越相似）。
         */
        INNER_PRODUCT("vector_ip_ops", "<#>"),

        /**
         * 余弦距离，使用 {@code <=>} 操作符。
         */
        COSINE("vector_cosine_ops", "<=>");

        private final String indexOps;
        private final String operator;

        DistanceType(String indexOps, String operator) {
            this.indexOps = indexOps;
            this.operator = operator;
        }

        /**
         * 获取此距离类型的索引操作符类名。
         *
         * <p>用于创建 pgvector HNSW 索引，例如 {@code CREATE INDEX ... USING hnsw (embedding vector_cosine_ops)}。
         *
         * @return 索引操作符类名，如 vector_cosine_ops、vector_l2_ops
         */
        public String getIndexOps() {
            return indexOps;
        }

        /**
         * 获取此距离类型的 SQL 操作符。
         *
         * <p>用于 ORDER BY 按相似度排序，例如 {@code ORDER BY embedding <=> query_vector}。
         *
         * @return SQL 操作符，如余弦的 {@code <=>}、L2 的 {@code <->}
         */
        public String getOperator() {
            return operator;
        }
    }

    /**
     * 按构建器配置创建 PgVectorStore。
     *
     * @param builder 构建器实例
     * @throws VectorStoreException 当客户端初始化或建表失败时
     */
    private PgVectorStore(Builder builder) throws VectorStoreException {
        this.jdbcUrl = builder.jdbcUrl;
        this.schema = builder.schema != null ? builder.schema : DEFAULT_SCHEMA;
        this.tableName = builder.tableName;
        this.dimensions = builder.dimensions;
        this.distanceType = builder.distanceType;

        Connection tempConnection = null;

        try {
            // 构建连接属性。
            Properties props = new Properties();
            if (builder.username != null) {
                props.setProperty("user", builder.username);
            }
            if (builder.password != null) {
                props.setProperty("password", builder.password);
            }
            if (builder.connectionTimeoutMs > 0) {
                props.setProperty(
                        "connectTimeout", String.valueOf(builder.connectionTimeoutMs / 1000));
            }

            // 创建连接。
            tempConnection = DriverManager.getConnection(jdbcUrl, props);

            // 注册 pgvector 类型。
            PGvector.addVectorType(tempConnection);

            log.debug(
                    "Initialized PostgreSQL connection: url={}, schema={}, table={}",
                    jdbcUrl,
                    schema,
                    tableName);

            // 确保表存在。
            ensureTable(tempConnection);

            // 仅在初始化成功后赋值给 final 字段。
            this.connection = tempConnection;

            log.debug("PgVectorStore initialized successfully for table: {}", tableName);
        } catch (Exception e) {
            // 初始化失败时清理资源。
            try {
                if (tempConnection != null) {
                    tempConnection.close();
                }
            } catch (Exception cleanupException) {
                log.warn("Error closing connection after initialization failure", cleanupException);
            }
            throw new VectorStoreException("Failed to initialize PgVectorStore", e);
        }
    }

    /**
     * 使用最小配置创建 PgVectorStore。
     *
     * @param jdbcUrl PostgreSQL JDBC URL，如 jdbc:postgresql://localhost:5432/mydb
     * @param username 数据库用户名
     * @param password 数据库密码
     * @param tableName 使用的表名
     * @param dimensions 待存储向量维度
     * @return 新的 PgVectorStore 实例
     * @throws VectorStoreException 当初始化失败时
     */
    public static PgVectorStore create(
            String jdbcUrl, String username, String password, String tableName, int dimensions)
            throws VectorStoreException {
        return builder()
                .jdbcUrl(jdbcUrl)
                .username(username)
                .password(password)
                .tableName(tableName)
                .dimensions(dimensions)
                .build();
    }

    @Override
    public Mono<Void> add(List<Document> documents) {
        if (documents == null) {
            return Mono.error(new IllegalArgumentException("Documents list cannot be null"));
        }
        if (documents.isEmpty()) {
            return Mono.empty();
        }

        // 校验全部文档。
        for (Document document : documents) {
            if (document == null) {
                return Mono.error(new IllegalArgumentException("Document cannot be null"));
            }
            if (document.getEmbedding() == null) {
                return Mono.error(new IllegalArgumentException("Document must have embedding set"));
            }
            if (document.getEmbedding().length != dimensions) {
                return Mono.error(
                        new VectorStoreException(
                                String.format(
                                        "Embedding dimension mismatch: expected %d, got %d",
                                        dimensions, document.getEmbedding().length)));
            }
        }

        return Mono.fromCallable(
                        () -> {
                            try {
                                ensureNotClosed();
                                addDocumentsToPostgres(documents);
                                return null;
                            } catch (Exception e) {
                                throw new VectorStoreException(
                                        "Failed to add documents to PostgreSQL", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        e ->
                                e instanceof VectorStoreException
                                        ? e
                                        : new VectorStoreException(
                                                "Failed to add documents to PostgreSQL", e))
                .then();
    }

    @Override
    public Mono<List<Document>> search(SearchDocumentDto searchDocumentDto) {
        double[] queryEmbedding = searchDocumentDto.getQueryEmbedding();
        int limit = searchDocumentDto.getLimit();
        Double scoreThreshold = searchDocumentDto.getScoreThreshold();

        if (queryEmbedding == null) {
            return Mono.error(new IllegalArgumentException("Query embedding cannot be null"));
        }
        if (queryEmbedding.length != dimensions) {
            return Mono.error(
                    new VectorStoreException(
                            String.format(
                                    "Query embedding dimension mismatch: expected %d, got %d",
                                    dimensions, queryEmbedding.length)));
        }
        if (limit <= 0) {
            return Mono.error(new IllegalArgumentException("Limit must be positive"));
        }

        return Mono.fromCallable(
                        () -> {
                            try {
                                ensureNotClosed();
                                return searchDocumentsInPostgres(
                                        queryEmbedding, limit, scoreThreshold);
                            } catch (Exception e) {
                                throw new VectorStoreException(
                                        "Failed to search documents in PostgreSQL", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        e ->
                                e instanceof VectorStoreException
                                        ? e
                                        : new VectorStoreException(
                                                "Failed to search documents in PostgreSQL", e));
    }

    @Override
    public Mono<Boolean> delete(String docId) {
        if (docId == null || docId.trim().isEmpty()) {
            return Mono.error(new IllegalArgumentException("Document ID cannot be null or empty"));
        }

        return Mono.fromCallable(
                        () -> {
                            try {
                                ensureNotClosed();
                                return deleteDocumentFromPostgres(docId);
                            } catch (Exception e) {
                                throw new VectorStoreException(
                                        "Failed to delete document from PostgreSQL", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        e ->
                                e instanceof VectorStoreException
                                        ? e
                                        : new VectorStoreException(
                                                "Failed to delete document from PostgreSQL", e));
    }

    /**
     * 获取底层 JDBC 连接以执行高级操作。
     *
     * <p>供超出 VDBStoreBase 接口的操作使用，例如：
     * <ul>
     * <li>自定义索引配置。</li>
     * <li>高级过滤。</li>
     * <li>表管理。</li>
     * <li>批量操作。</li>
     * </ul>
     *
     * @return Connection 实例
     * @throws VectorStoreException 当存储已关闭时
     */
    public Connection getConnection() throws VectorStoreException {
        ensureNotClosed();
        return connection;
    }

    /**
     * 操作前确保存储未关闭。
     *
     * @throws VectorStoreException 当存储已关闭时
     */
    private void ensureNotClosed() throws VectorStoreException {
        if (closed) {
            throw new VectorStoreException("PgVectorStore has been closed");
        }
    }

    /**
     * 获取含 schema 的完全限定表名。
     *
     * <p>安全拼接 schema 与表名；两者已在构造时通过 {@link #validateIdentifier(String, String)} 校验，只含字母、数字和下划线，防止此处 SQL 注入。
     *
     * @return 完全限定表名 schema.table
     */
    private String getFullTableName() {
        return schema + "." + tableName;
    }

    /**
     * 确保表存在，必要时创建。
     *
     * @param conn 使用的连接
     * @throws VectorStoreException 当建表失败时
     */
    private void ensureTable(Connection conn) throws VectorStoreException {
        try {
            // 确保 vector 扩展可用。
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
            }

            // 确保 schema 存在，不存在时创建。
            if (!DEFAULT_SCHEMA.equalsIgnoreCase(schema)) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
                }
                log.debug("Ensured schema '{}' exists", schema);
            }

            // 检查指定 schema 中表是否存在。
            boolean tableExists = false;
            try (ResultSet rs =
                    conn.getMetaData()
                            .getTables(
                                    null,
                                    schema.toLowerCase(),
                                    tableName.toLowerCase(),
                                    new String[] {"TABLE"})) {
                tableExists = rs.next();
            }

            if (tableExists) {
                log.debug("Table '{}.{}' already exists", schema, tableName);
                return;
            }

            // 创建表。
            createTable(conn);
            log.debug("Created table '{}.{}' with dimensions {}", schema, tableName, dimensions);
        } catch (Exception e) {
            throw new VectorStoreException(
                    "Failed to ensure table exists: " + getFullTableName(), e);
        }
    }

    /**
     * 按指定维度和 schema 创建表。
     *
     * @param conn 使用的连接
     * @throws SQLException 当建表失败时
     */
    private void createTable(Connection conn) throws SQLException {
        String fullTableName = getFullTableName();

        // 创建含 vector 列的表。
        String createTableSql =
                String.format(
                        "CREATE TABLE IF NOT EXISTS %s ("
                                + "%s VARCHAR(64) PRIMARY KEY, "
                                + "%s vector(%d), "
                                + "%s VARCHAR(256), "
                                + "%s VARCHAR(256), "
                                + "%s TEXT, "
                                + "%s JSONB"
                                + ")",
                        fullTableName,
                        COL_ID,
                        COL_VECTOR,
                        dimensions,
                        COL_DOC_ID,
                        COL_CHUNK_ID,
                        COL_CONTENT,
                        COL_PAYLOAD);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSql);
        }

        // 为 doc_id 创建索引，加快过滤。
        // 索引名使用 schema_table 格式以避免冲突。
        String indexPrefix = schema + "_" + tableName;
        String createDocIdIndexSql =
                String.format(
                        "CREATE INDEX IF NOT EXISTS idx_%s_doc_id ON %s (%s)",
                        indexPrefix, fullTableName, COL_DOC_ID);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createDocIdIndexSql);
        }

        // 为向量列创建 HNSW 索引，加快相似度搜索。
        String createVectorIndexSql =
                String.format(
                        "CREATE INDEX IF NOT EXISTS idx_%s_vector ON %s USING hnsw (%s %s)",
                        indexPrefix, fullTableName, COL_VECTOR, distanceType.getIndexOps());

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createVectorIndexSql);
        }
    }

    /**
     * 向 PostgreSQL 添加多条文档。
     *
     * <p>使用 upsert（INSERT ... ON CONFLICT DO UPDATE）处理重复 ID。
     *
     * @param documents 待存储文档，必须已设置嵌入
     * @throws SQLException 当操作失败时
     */
    private void addDocumentsToPostgres(List<Document> documents) throws SQLException {
        String fullTableName = getFullTableName();
        String upsertSql =
                String.format(
                        "INSERT INTO %s (%s, %s, %s, %s, %s, %s) VALUES (?, ?, ?, ?, ?, ?::jsonb) "
                                + "ON CONFLICT (%s) DO UPDATE SET "
                                + "%s = EXCLUDED.%s, "
                                + "%s = EXCLUDED.%s, "
                                + "%s = EXCLUDED.%s, "
                                + "%s = EXCLUDED.%s, "
                                + "%s = EXCLUDED.%s",
                        fullTableName,
                        COL_ID,
                        COL_VECTOR,
                        COL_DOC_ID,
                        COL_CHUNK_ID,
                        COL_CONTENT,
                        COL_PAYLOAD,
                        COL_ID,
                        COL_VECTOR,
                        COL_VECTOR,
                        COL_DOC_ID,
                        COL_DOC_ID,
                        COL_CHUNK_ID,
                        COL_CHUNK_ID,
                        COL_CONTENT,
                        COL_CONTENT,
                        COL_PAYLOAD,
                        COL_PAYLOAD);

        try (PreparedStatement stmt = connection.prepareStatement(upsertSql)) {
            for (Document document : documents) {
                // 设置 ID。
                stmt.setString(1, document.getId());

                // 将 double[] 转换为 PGvector。
                double[] embedding = document.getEmbedding();
                float[] floatArray = new float[embedding.length];
                for (int i = 0; i < embedding.length; i++) {
                    floatArray[i] = (float) embedding[i];
                }
                stmt.setObject(2, new PGvector(floatArray));

                // 设置元数据字段。
                DocumentMetadata metadata = document.getMetadata();
                stmt.setString(3, metadata.getDocId());
                stmt.setString(4, metadata.getChunkId());

                // 将内容序列化为 JSON 字符串。
                String contentJson;
                try {
                    contentJson = JsonUtils.getJsonCodec().toJson(metadata.getContent());
                } catch (Exception e) {
                    log.warn("Failed to serialize content, using text representation", e);
                    contentJson = metadata.getContentText();
                }
                stmt.setString(5, contentJson);

                // 将自定义载荷序列化为 JSON。
                Map<String, Object> customPayload = metadata.getPayload();
                String payloadJson =
                        customPayload != null && !customPayload.isEmpty()
                                ? JsonUtils.getJsonCodec().toJson(customPayload)
                                : "{}";
                stmt.setString(6, payloadJson);

                stmt.addBatch();
            }

            int[] results = stmt.executeBatch();
            log.debug(
                    "Inserted/Updated {} documents into table '{}'", results.length, fullTableName);
        }
    }

    /**
     * 在 PostgreSQL 搜索相似文档。
     *
     * @param queryEmbedding 查询向量
     * @param limit 最大结果数
     * @param scoreThreshold 可选最低分数阈值
     * @return 已设置分数的文档列表
     * @throws SQLException 当操作失败时
     */
    private List<Document> searchDocumentsInPostgres(
            double[] queryEmbedding, int limit, Double scoreThreshold) throws SQLException {

        // 将 double[] 转换为 PGvector。
        float[] floatArray = new float[queryEmbedding.length];
        for (int i = 0; i < queryEmbedding.length; i++) {
            floatArray[i] = (float) queryEmbedding[i];
        }
        PGvector queryVector = new PGvector(floatArray);

        // 构建查询：余弦与 L2 距离越小越相似。
        // 内积越大越相似，但 pgvector 使用负内积排序。
        String distanceExpr = String.format("%s %s ?", COL_VECTOR, distanceType.getOperator());

        String searchSql =
                String.format(
                        "SELECT %s, %s, %s, %s, %s, %s, (%s) as distance "
                                + "FROM %s "
                                + "ORDER BY distance ASC "
                                + "LIMIT ?",
                        COL_ID,
                        COL_VECTOR,
                        COL_DOC_ID,
                        COL_CHUNK_ID,
                        COL_CONTENT,
                        COL_PAYLOAD,
                        distanceExpr,
                        getFullTableName());

        List<Document> results = new ArrayList<>();

        try (PreparedStatement stmt = connection.prepareStatement(searchSql)) {
            stmt.setObject(1, queryVector);
            stmt.setInt(2, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    try {
                        // 读取距离并转换为相似度分数。
                        double distance = rs.getDouble("distance");

                        // 根据距离类型转换相似度分数。
                        double score = convertDistanceToScore(distance);

                        // 应用已指定的分数阈值。
                        if (scoreThreshold != null && score < scoreThreshold) {
                            continue;
                        }

                        // 从结果重建文档。
                        Document document = reconstructDocumentFromResult(rs, score);
                        if (document != null) {
                            results.add(document);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to reconstruct document from search result", e);
                    }
                }
            }
        }

        return results;
    }

    /**
     * 根据距离类型转换相似度分数。
     *
     * <p>【学习提示】统一接口要求分数越高越相似，但不同度量不具有相同数值标尺。余弦距离取 1-distance，L2 取 1/(1+distance)，负内积取反；同一个阈值不能未经校准就跨度量或跨嵌入模型复用。
     *
     * @param distance pgvector 返回的距离
     * @return 相似度分数，越高越相似
     */
    private double convertDistanceToScore(double distance) {
        return switch (distanceType) {
            case COSINE ->
                    // 余弦距离为 1 - cosine_similarity，因此 score = 1 - distance。
                    1.0 - distance;
            case L2 ->
                    // L2 距离使用 1 / (1 + distance) 转为相似度。
                    1.0 / (1.0 + distance);
            case INNER_PRODUCT ->
                    // pgvector 返回负内积，取反恢复内积。
                    -distance;
        };
    }

    /**
     * 从 PostgreSQL ResultSet 重建 Document。
     *
     * @param rs 包含文档数据的结果集
     * @param score 相似度分数
     * @return 重建文档；重建失败时为 null
     */
    private Document reconstructDocumentFromResult(ResultSet rs, double score) {
        try {
            // 提取元数据字段。
            String docId = rs.getString(COL_DOC_ID);
            String chunkId = rs.getString(COL_CHUNK_ID);
            String contentJson = rs.getString(COL_CONTENT);

            // 反序列化内容。
            ContentBlock content;
            try {
                content = JsonUtils.getJsonCodec().fromJson(contentJson, ContentBlock.class);
            } catch (Exception e) {
                log.debug("Failed to deserialize ContentBlock, creating TextBlock from content", e);
                content = TextBlock.builder().text(contentJson).build();
            }

            // 从 payload 字段反序列化自定义载荷。
            Map<String, Object> customPayload = new HashMap<>();
            String payloadJson = rs.getString(COL_PAYLOAD);
            if (payloadJson != null && !payloadJson.isEmpty() && !"{}".equals(payloadJson)) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> deserializedPayload =
                            JsonUtils.getJsonCodec().fromJson(payloadJson, Map.class);
                    customPayload = deserializedPayload;
                } catch (Exception e) {
                    log.warn("Failed to deserialize payload, using empty map", e);
                }
            }

            // 创建元数据及带载荷的文档。
            DocumentMetadata metadata =
                    new DocumentMetadata(content, docId, chunkId, customPayload);
            Document document = new Document(metadata);
            document.setScore(score);

            // 需要时从 vector 列提取嵌入。
            try {
                PGvector vectorObj = (PGvector) rs.getObject(COL_VECTOR);
                if (vectorObj != null) {
                    float[] floatVector = vectorObj.toArray();
                    double[] embedding = new double[floatVector.length];
                    for (int i = 0; i < floatVector.length; i++) {
                        embedding[i] = floatVector[i];
                    }
                    document.setEmbedding(embedding);
                }
            } catch (Exception e) {
                log.debug("Failed to extract embedding from result", e);
            }

            return document;
        } catch (Exception e) {
            log.error("Failed to reconstruct document from search result", e);
            return null;
        }
    }

    /**
     * 按文档 ID 从 PostgreSQL 删除文档。
     *
     * @param docId 待删除文档 ID
     * @return 删除成功时为 true
     * @throws SQLException 当操作失败时
     */
    private boolean deleteDocumentFromPostgres(String docId) throws SQLException {
        String fullTableName = getFullTableName();
        String deleteSql = String.format("DELETE FROM %s WHERE %s = ?", fullTableName, COL_DOC_ID);

        try (PreparedStatement stmt = connection.prepareStatement(deleteSql)) {
            stmt.setString(1, docId);
            int deletedCount = stmt.executeUpdate();
            log.debug("Deleted {} documents from table '{}'", deletedCount, fullTableName);
            return deletedCount > 0;
        }
    }

    /**
     * 获取 JDBC URL。
     *
     * @return JDBC URL
     */
    public String getJdbcUrl() {
        return jdbcUrl;
    }

    /**
     * 获取 schema 名称。
     *
     * @return schema 名称
     */
    public String getSchema() {
        return schema;
    }

    /**
     * 获取表名。
     *
     * @return 表名
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * 获取向量维度。
     *
     * @return 维度
     */
    public int getDimensions() {
        return dimensions;
    }

    /**
     * 获取相似度搜索使用的距离类型。
     *
     * @return 距离类型
     */
    public DistanceType getDistanceType() {
        return distanceType;
    }

    /**
     * 关闭 PostgreSQL 连接并释放资源。
     *
     * <p>关闭 JDBC 连接，释放数据库资源；操作幂等，可安全重复调用。
     *
     * <p>关闭后存储操作会抛出 VectorStoreException。建议用 try-with-resources 自动管理：
     * <pre>{@code
     * try (PgVectorStore store = PgVectorStore.builder()
     *         .jdbcUrl("jdbc:postgresql://localhost:5432/mydb")
     *         .tableName("embeddings")
     *         .dimensions(1024)
     *         .build()) {
     *     store.add(documents).block();
     *     // Store is automatically closed here
     * }
     * }</pre>
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }

        synchronized (this) {
            if (closed) {
                return;
            }

            closed = true;

            try {
                if (connection != null && !connection.isClosed()) {
                    log.debug("Closing PostgreSQL connection for table: {}", getFullTableName());
                    connection.close();
                }
            } catch (Exception e) {
                log.warn("Error closing PostgreSQL connection", e);
            }

            log.debug("PgVectorStore closed for table: {}", getFullTableName());
        }
    }

    /**
     * 检查存储是否已关闭。
     *
     * @return 已关闭时为 true，否则为 false
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * 通过流式配置创建 PgVectorStore 的构建器。
     *
     * <p>用法示例：
     * <pre>{@code
     * PgVectorStore store = PgVectorStore.builder()
     *     .jdbcUrl("jdbc:postgresql://localhost:5432/mydb")
     *     .username("postgres")
     *     .password("password")
     *     .schema("my_schema")
     *     .tableName("embeddings")
     *     .dimensions(1024)
     *     .distanceType(PgVectorStore.DistanceType.COSINE)
     *     .connectionTimeoutMs(30000)
     *     .build();
     * }</pre>
     */
    public static class Builder {
        private String jdbcUrl;
        private String username;
        private String password;
        private String schema;
        private String tableName;
        private int dimensions;
        private DistanceType distanceType = DistanceType.COSINE;
        private long connectionTimeoutMs = 30000L;

        private Builder() {}

        /**
         * 设置 PostgreSQL JDBC URL。
         *
         * @param jdbcUrl JDBC URL，如 jdbc:postgresql://localhost:5432/mydb
         * @return 当前构建器
         */
        public Builder jdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
            return this;
        }

        /**
         * 设置数据库用户名。
         *
         * @param username 用户名
         * @return 当前构建器
         */
        public Builder username(String username) {
            this.username = username;
            return this;
        }

        /**
         * 设置数据库密码。
         *
         * @param password 密码
         * @return 当前构建器
         */
        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /**
         * 设置数据库 schema。
         *
         * <p>默认为 public，不存在时自动创建。
         *
         * @param schema schema 名称，如 my_schema
         * @return 当前构建器
         */
        public Builder schema(String schema) {
            this.schema = schema;
            return this;
        }

        /**
         * 设置表名。
         *
         * @param tableName 使用的表名
         * @return 当前构建器
         */
        public Builder tableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        /**
         * 设置向量维度。
         *
         * @param dimensions 待存储向量维度
         * @return 当前构建器
         */
        public Builder dimensions(int dimensions) {
            this.dimensions = dimensions;
            return this;
        }

        /**
         * 设置向量相似度搜索的距离类型。
         *
         * <p>默认 COSINE，其他选项为 L2（欧氏）和 INNER_PRODUCT。
         *
         * @param distanceType 距离类型
         * @return 当前构建器
         */
        public Builder distanceType(DistanceType distanceType) {
            this.distanceType = distanceType;
            return this;
        }

        /**
         * 设置连接超时（毫秒）。
         *
         * <p>默认 30000（30 秒），0 或负数禁用超时。
         *
         * @param connectionTimeoutMs 连接超时毫秒数
         * @return 当前构建器
         */
        public Builder connectionTimeoutMs(long connectionTimeoutMs) {
            this.connectionTimeoutMs = connectionTimeoutMs;
            return this;
        }

        /**
         * 构建 PgVectorStore。
         *
         * <p>构造时立即建立数据库连接。连接或建表失败时抛出 VectorStoreException。
         *
         * @return 新的 PgVectorStore 实例
         * @throws IllegalArgumentException 当必填参数无效时
         * @throws VectorStoreException 当连接初始化或建表失败时
         */
        public PgVectorStore build() throws VectorStoreException {
            if (jdbcUrl == null || jdbcUrl.trim().isEmpty()) {
                throw new IllegalArgumentException("JDBC URL cannot be null or empty");
            }
            if (tableName == null || tableName.trim().isEmpty()) {
                throw new IllegalArgumentException("Table name cannot be null or empty");
            }
            if (dimensions <= 0) {
                throw new IllegalArgumentException("Dimensions must be positive");
            }
            if (distanceType == null) {
                throw new IllegalArgumentException("Distance type cannot be null");
            }

            // 校验标识符，防止 SQL 注入。
            validateIdentifier(tableName, "Table name");
            if (schema != null && !schema.isEmpty()) {
                validateIdentifier(schema, "Schema name");
            }

            return new PgVectorStore(this);
        }
    }

    /**
     * 创建 PgVectorStore 构建器。
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 校验数据库标识符（schema 或表名），防止 SQL 注入。
     *
     * <p>确保只含字母、数字和下划线，并以字母或下划线开头。schema 和表名不能用预编译语句参数化，因此此校验非常重要。
     *
     * @param identifier 待校验标识符
     * @param identifierType 错误消息使用的标识符类型描述
     * @throws IllegalArgumentException 当标识符无效或含不安全字符时
     */
    private static void validateIdentifier(String identifier, String identifierType) {
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException(identifierType + " cannot be null or empty");
        }
        if (identifier.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(
                    identifierType + " cannot exceed " + MAX_IDENTIFIER_LENGTH + " characters");
        }
        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    identifierType
                            + " contains invalid characters. Only alphanumeric characters and"
                            + " underscores are allowed, and it must start with a letter or"
                            + " underscore. Invalid value: "
                            + identifier);
        }
    }
}
