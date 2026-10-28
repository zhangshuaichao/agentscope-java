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
package io.agentscope.extensions.jdbc.dialect;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.jdbc.H2TestSupport;
import io.agentscope.extensions.jdbc.dialect.vendor.H2Dialect;
import io.agentscope.extensions.jdbc.dialect.vendor.MysqlDialect;
import io.agentscope.extensions.jdbc.dialect.vendor.PostgresDialect;
import io.agentscope.extensions.jdbc.dialect.vendor.SqliteDialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 技能表组方言的 H2 测试：执行 DDL、检查必需列（拦截缺少 {@code metadata_json} 的旧表）、生成键插入和级联外键。后两项是旧技能 mysql/postgresql 模块依赖但基础表未覆盖的行为。构建器的缺表拦截由 {@code DialectBuilderTableGroupsTest} 覆盖。
 *
 * @author shanhongyu
 */
@DisplayName("Skill table dialects (H2)")
class SkillTableDialectH2Test {

    /** 各厂商技能 DDL 声明的列集合。 */
    private static final Set<String> SKILL_COLUMNS =
            Set.of(
                    "id",
                    "namespace",
                    "name",
                    "description",
                    "skill_content",
                    "source",
                    "metadata_json",
                    "created_at",
                    "updated_at");

    /** 各厂商技能资源 DDL 声明的列集合。 */
    private static final Set<String> SKILL_RESOURCE_COLUMNS =
            Set.of(
                    "id",
                    "namespace",
                    "resource_path",
                    "resource_content",
                    "created_at",
                    "updated_at");

    // ------------------------------------------------------------------
    // DDL 执行与校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("skill DDLs create both tables with the declared columns and validate clean")
    void freshCreateValidates() throws Exception {
        DataSource ds = H2TestSupport.createDataSource("skill_ddl_fresh");
        H2Dialect dialect = new H2Dialect();

        executeDdls(ds, dialect.skillCreateTableDdls());
        executeDdls(ds, dialect.skillResourcesCreateTableDdls());

        try (Connection conn = ds.getConnection()) {
            assertDoesNotThrow(
                    () ->
                            TableSchemaValidator.validate(
                                    conn,
                                    dialect.skillTableName(),
                                    dialect.skillCreateTableDdls()));
            assertDoesNotThrow(
                    () ->
                            TableSchemaValidator.validate(
                                    conn,
                                    dialect.skillResourcesTableName(),
                                    dialect.skillResourcesCreateTableDdls()));
        }
        assertEquals(SKILL_COLUMNS, actualColumns(ds, dialect.skillTableName()));
        assertEquals(SKILL_RESOURCE_COLUMNS, actualColumns(ds, dialect.skillResourcesTableName()));
    }

    @Test
    @DisplayName("a table missing a declared column is blocked, naming it and the reference DDL")
    void missingColumnBlocked() throws Exception {
        DataSource ds = H2TestSupport.createDataSource("skill_ddl_missing_column");
        H2Dialect dialect = new H2Dialect();
        // 旧技能模块在 metadata_json 引入前的精确结构：
        // 包含所有其他声明列。必需列策略要求在检查阶段明确提示，
        // 让用户执行 ALTER，而不是静默
        // 降级。
        execute(
                ds,
                "CREATE TABLE "
                        + dialect.skillTableName()
                        + " ("
                        + "  id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "  name VARCHAR(255) NOT NULL UNIQUE,"
                        + "  description CLOB NOT NULL,"
                        + "  skill_content CLOB NOT NULL,"
                        + "  source VARCHAR(255) NOT NULL,"
                        + "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                        + ")");

        try (Connection conn = ds.getConnection()) {
            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    TableSchemaValidator.validate(
                                            conn,
                                            dialect.skillTableName(),
                                            dialect.skillCreateTableDdls()));
            assertTrue(
                    exception.getMessage().contains("metadata_json"),
                    "message must name the missing column: " + exception.getMessage());
            assertTrue(
                    exception.getMessage().toUpperCase(Locale.ROOT).contains("CREATE TABLE"),
                    "message must carry the reference DDL: " + exception.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 自增与级联：两项移植行为
    // ------------------------------------------------------------------

    @Test
    @DisplayName("insert returns the auto-increment id and skill delete cascades to resources")
    void generatedKeysAndCascadeDelete() throws Exception {
        DataSource ds = H2TestSupport.createDataSource("skill_ddl_genkeys_cascade");
        H2Dialect dialect = new H2Dialect();
        executeDdls(ds, dialect.skillCreateTableDdls());
        executeDdls(ds, dialect.skillResourcesCreateTableDdls());

        try (Connection conn = ds.getConnection()) {
            long skillId;
            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "INSERT INTO "
                                    + dialect.skillTableName()
                                    + " (name, description, skill_content, source)"
                                    + " VALUES ('s1', 'd', 'c', 'test')",
                            Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    assertTrue(keys.next(), "generated key must be returned");
                    skillId = keys.getLong(1);
                    assertTrue(skillId > 0, "auto-increment id must be positive");
                }
            }

            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "INSERT INTO "
                                    + dialect.skillResourcesTableName()
                                    + " (id, resource_path, resource_content)"
                                    + " VALUES (?, 'docs/readme.md', 'hello')")) {
                ps.setLong(1, skillId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "DELETE FROM " + dialect.skillTableName() + " WHERE name = 's1'")) {
                ps.executeUpdate();
            }

            try (PreparedStatement ps =
                            conn.prepareStatement(
                                    "SELECT COUNT(*) FROM " + dialect.skillResourcesTableName());
                    ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(
                        0, rs.getInt(1), "ON DELETE CASCADE must remove the skill's resources");
            }
        }
    }

    // ------------------------------------------------------------------
    // 解析各厂商 DDL 结构
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every vendor's skill DDL parses to the expected column set")
    void allVendorDdlsParse() {
        for (AbstractJdbcDialect dialect :
                List.of(
                        new H2Dialect(),
                        new MysqlDialect(),
                        new PostgresDialect(),
                        new SqliteDialect())) {
            assertEquals(
                    SKILL_COLUMNS,
                    TableSchemaValidator.CreateTableDdlParser.parseColumns(
                            dialect.skillCreateTableDdls().get(0)),
                    dialect.getClass().getSimpleName() + " skill DDL must parse");
            assertEquals(
                    SKILL_RESOURCE_COLUMNS,
                    TableSchemaValidator.CreateTableDdlParser.parseColumns(
                            dialect.skillResourcesCreateTableDdls().get(0)),
                    dialect.getClass().getSimpleName() + " skill-resources DDL must parse");
        }
    }

    @Test
    @DisplayName("every vendor's skill DDL declares UNIQUE(namespace, name), not UNIQUE(name)")
    void allVendorDdlsDeclareNamespacedUnique() {
        for (AbstractJdbcDialect dialect :
                List.of(
                        new H2Dialect(),
                        new MysqlDialect(),
                        new PostgresDialect(),
                        new SqliteDialect())) {
            String ddl = normalise(dialect.skillCreateTableDdls().get(0)).toUpperCase(Locale.ROOT);
            assertTrue(
                    ddl.matches(".*UNIQUE[^,)]*\\(\\s*NAMESPACE\\s*,\\s*NAME\\s*\\).*"),
                    dialect.getClass().getSimpleName()
                            + " must declare UNIQUE(namespace, name): "
                            + ddl);
            assertFalse(
                    ddl.matches(".*NAME\\s+VARCHAR\\(255\\)\\s+NOT\\s+NULL\\s+UNIQUE.*"),
                    dialect.getClass().getSimpleName()
                            + " must not keep a global UNIQUE(name): "
                            + ddl);
        }
    }

    @Test
    @DisplayName(
            "UNIQUE(namespace, name) admits the same name in two namespaces, rejects within one")
    void namespacedUniqueConstraint() throws Exception {
        DataSource ds = H2TestSupport.createDataSource("skill_ddl_ns_unique");
        H2Dialect dialect = new H2Dialect();
        executeDdls(ds, dialect.skillCreateTableDdls());
        executeDdls(ds, dialect.skillResourcesCreateTableDdls());

        String insert =
                "INSERT INTO "
                        + dialect.skillTableName()
                        + " (namespace, name, description, skill_content, source)"
                        + " VALUES (?, 's1', 'd', 'c', 'test')";
        try (Connection conn = ds.getConnection();
                PreparedStatement nsA = conn.prepareStatement(insert);
                PreparedStatement nsB = conn.prepareStatement(insert);
                PreparedStatement dupA = conn.prepareStatement(insert)) {
            nsA.setString(1, "team-a");
            assertEquals(1, nsA.executeUpdate());
            nsB.setString(1, "team-b");
            assertEquals(1, nsB.executeUpdate(), "same name must coexist in another namespace");
            dupA.setString(1, "team-a");
            SQLException duplicate = assertThrows(SQLException.class, dupA::executeUpdate);
            assertEquals(
                    "23505",
                    duplicate.getSQLState(),
                    "the violation must be the portable unique-constraint state");
        }
    }

    @Test
    @DisplayName("a table missing the namespace column is blocked, naming it")
    void missingNamespaceColumnBlocked() throws Exception {
        DataSource ds = H2TestSupport.createDataSource("skill_ddl_missing_namespace");
        H2Dialect dialect = new H2Dialect();
        // 尚未升级部署的结构：除 namespace 外全部列，
        // 以及旧全局 UNIQUE(name)。
        execute(
                ds,
                "CREATE TABLE "
                        + dialect.skillTableName()
                        + " ("
                        + "  id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "  name VARCHAR(255) NOT NULL UNIQUE,"
                        + "  description CLOB NOT NULL,"
                        + "  skill_content CLOB NOT NULL,"
                        + "  source VARCHAR(255) NOT NULL,"
                        + "  metadata_json CLOB,"
                        + "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                        + ")");

        try (Connection conn = ds.getConnection()) {
            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    TableSchemaValidator.validate(
                                            conn,
                                            dialect.skillTableName(),
                                            dialect.skillCreateTableDdls()));
            assertTrue(
                    exception.getMessage().contains("namespace"),
                    "message must name the missing column: " + exception.getMessage());
        }
    }

    @Test
    @DisplayName("the documented upgrade (add column, rebuild index) makes namespaced saves work")
    void legacyTableUpgradePath() throws Exception {
        DataSource ds = H2TestSupport.createDataSource("skill_ddl_upgrade");
        H2Dialect dialect = new H2Dialect();
        execute(
                ds,
                "CREATE TABLE "
                        + dialect.skillTableName()
                        + " ("
                        + "  id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "  name VARCHAR(255) NOT NULL,"
                        + "  description CLOB NOT NULL,"
                        + "  skill_content CLOB NOT NULL,"
                        + "  source VARCHAR(255) NOT NULL,"
                        + "  metadata_json CLOB,"
                        + "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "  CONSTRAINT name_key UNIQUE (name)"
                        + ")");
        execute(
                ds,
                "INSERT INTO "
                        + dialect.skillTableName()
                        + " (name, description, skill_content, source)"
                        + " VALUES ('legacy', 'd', 'c', 'test')");

        // 部署说明规定的旧全局 UNIQUE(name) 迁移。
        execute(
                ds,
                "ALTER TABLE "
                        + dialect.skillTableName()
                        + " ADD COLUMN namespace VARCHAR(64) NOT NULL DEFAULT 'default'");
        execute(ds, "ALTER TABLE " + dialect.skillTableName() + " DROP CONSTRAINT name_key");
        execute(
                ds,
                "ALTER TABLE "
                        + dialect.skillTableName()
                        + " ADD CONSTRAINT uk_namespace_name UNIQUE (namespace, name)");

        try (Connection conn = ds.getConnection()) {
            assertDoesNotThrow(
                    () ->
                            TableSchemaValidator.validate(
                                    conn,
                                    dialect.skillTableName(),
                                    dialect.skillCreateTableDdls()));

            String insert =
                    "INSERT INTO "
                            + dialect.skillTableName()
                            + " (namespace, name, description, skill_content, source)"
                            + " VALUES ('team-a', 'legacy', 'd', 'c', 'test')";
            try (PreparedStatement ps = conn.prepareStatement(insert)) {
                assertEquals(
                        1,
                        ps.executeUpdate(),
                        "after the upgrade the same name must be savable in another namespace");
            }
        }
    }

    private static String normalise(String ddl) {
        return ddl.replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /** 在给定数据库执行方言的全部 DDL。 */
    private static void executeDdls(DataSource ds, List<String> ddls) throws SQLException {
        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement()) {
            for (String ddl : ddls) {
                stmt.execute(ddl);
            }
        }
    }

    /** 在给定数据库执行一条 SQL。 */
    private static void execute(DataSource ds, String sql) throws SQLException {
        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    /** 表的实际列名，转为小写。 */
    private static Set<String> actualColumns(DataSource ds, String table) throws SQLException {
        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
            ResultSetMetaData metaData = rs.getMetaData();
            Set<String> columns = new TreeSet<>();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                columns.add(metaData.getColumnLabel(i).toLowerCase(Locale.ROOT));
            }
            return columns;
        }
    }
}
