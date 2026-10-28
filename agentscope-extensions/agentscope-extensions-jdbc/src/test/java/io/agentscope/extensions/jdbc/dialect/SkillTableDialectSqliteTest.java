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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.jdbc.dialect.vendor.SqliteDialect;
import io.agentscope.extensions.jdbc.skill.JdbcAgentSkillRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/**
 * 技能表组的 SQLite 执行测试。此方言生成键需要 {@code INTEGER PRIMARY KEY AUTOINCREMENT}，级联外键仅在开启 {@code PRAGMA foreign_keys} 时生效。验证 DDL 执行、模式校验，以及仓库显式删除资源使行为与支持级联的厂商一致。
 *
 * <p>通过 {@link TempDir} 使用文件数据库，使独立连接可看到相同模式。
 *
 * @author shanhongyu
 */
@DisplayName("Skill table dialects (SQLite)")
class SkillTableDialectSqliteTest {

    @TempDir Path tempDir;

    @Test
    @DisplayName("skill DDLs execute, validate, and return generated keys")
    void ddlExecutesAndReturnsGeneratedKeys() throws Exception {
        DataSource ds = createDataSource("skill_ddl");
        SqliteDialect dialect = new SqliteDialect();

        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement()) {
            for (String ddl : dialect.skillCreateTableDdls()) {
                stmt.execute(ddl);
            }
            for (String ddl : dialect.skillResourcesCreateTableDdls()) {
                stmt.execute(ddl);
            }
            assertDoesNotThrow(
                    () ->
                            TableSchemaValidator.validate(
                                    conn,
                                    dialect.skillTableName(),
                                    dialect.skillCreateTableDdls()));

            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "INSERT INTO agentscope_skills (name, description, skill_content,"
                                    + " source) VALUES ('s1', 'd', 'c', 'test')",
                            Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    assertTrue(keys.next(), "SQLite AUTOINCREMENT must return a generated key");
                    assertTrue(keys.getLong(1) > 0);
                }
            }
        }
    }

    @Test
    @DisplayName("repository round-trip on SQLite deletes resources explicitly with the skill")
    void repositoryRoundTripDeletesResources() throws Exception {
        DataSource ds = createDataSource("skill_repo");
        AbstractJdbcDialect dialect =
                AbstractJdbcDialect.from(ds)
                        .enableBaseTables(false)
                        .enableSkillTables(true)
                        .build();
        JdbcAgentSkillRepository repo = new JdbcAgentSkillRepository(ds, dialect);

        var skill =
                new io.agentscope.core.skill.AgentSkill(
                        Map.of("name", "sqlite-skill", "description", "d"),
                        "content",
                        Map.of("docs/readme.md", "hello"),
                        "test");
        assertTrue(repo.save(List.of(skill), false));
        assertEquals("hello", repo.getSkill("sqlite-skill").getResource("docs/readme.md"));

        // SQLite 默认关闭外键，此断言检查仓库自行删除资源行，
        // 而不是依赖 ON DELETE CASCADE。
        assertTrue(repo.delete("sqlite-skill"));
        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs =
                        stmt.executeQuery("SELECT COUNT(*) FROM agentscope_skill_resources")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "resources must be gone with the skill");
        }
    }

    @Test
    @DisplayName("namespaces isolate the same skill name, resources included, on SQLite")
    void namespacesIsolateOnSqlite() throws Exception {
        DataSource ds = createDataSource("skill_ns");
        AbstractJdbcDialect dialect =
                AbstractJdbcDialect.from(ds)
                        .enableBaseTables(false)
                        .enableSkillTables(true)
                        .build();
        JdbcAgentSkillRepository repo = new JdbcAgentSkillRepository(ds, dialect);
        var teamA =
                new io.agentscope.core.skill.AgentSkill(
                        Map.of("name", "shared", "description", "team-a"),
                        "content-a",
                        Map.of("docs/a.md", "a"),
                        "test");
        var teamB =
                new io.agentscope.core.skill.AgentSkill(
                        Map.of("name", "shared", "description", "team-b"),
                        "content-b",
                        Map.of("docs/b.md", "b"),
                        "test");

        assertTrue(repo.save("team-a", List.of(teamA), false));
        assertTrue(repo.save("team-b", List.of(teamB), false), "the composite key admits both");
        assertEquals("content-a", repo.getSkill("team-a", "shared").getSkillContent());
        assertEquals("content-b", repo.getSkill("team-b", "shared").getSkillContent());

        // 资源按技能 ID 删除，即使这里不执行级联外键，
        // 其他命名空间也应保留自身资源行。
        assertTrue(repo.delete("team-a", "shared"));
        assertEquals("b", repo.getSkill("team-b", "shared").getResource("docs/b.md"));
        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs =
                        stmt.executeQuery("SELECT COUNT(*) FROM agentscope_skill_resources")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "only team-b's resource row must remain");
        }
    }

    /** 文件型 SQLite DataSource，每个测试一个数据库文件。 */
    private DataSource createDataSource(String name) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve(name + ".db").toAbsolutePath());
        return ds;
    }
}
