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
package io.agentscope.extensions.jdbc.skill;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/**
 * SQLite 冲突分类测试：全部 {@code SQLITE_CONSTRAINT_*} 均报告错误 19 且 SQLState 为 null，因此 {@link JdbcAgentSkillRepository#isUniqueViolation} 必须通过驱动 {@code getResultCode()} 细分。使用真实驱动异常，因为普通 {@code SQLException} 不进入此分支，旧细分逻辑曾静默漏匹配。通过 {@link TempDir} 使用文件数据库，使不同连接共享模式。
 *
 * @author shanhongyu
 */
@DisplayName("JdbcAgentSkillRepository (SQLite)")
class JdbcAgentSkillRepositorySqliteTest {

    @TempDir Path tempDir;

    @Test
    @DisplayName("a duplicate save surfaces the force=false conflict and rolls back")
    void duplicateSaveSurfacesConflictAndRollsBack() {
        DataSource ds = createDataSource("skill_repo_conflict");
        JdbcAgentSkillRepository repo = new JdbcAgentSkillRepository(ds, skillDialect(ds));

        IllegalStateException conflict =
                assertThrows(
                        IllegalStateException.class,
                        () -> repo.save(List.of(sampleSkill("dup"), sampleSkill("dup")), false));

        assertTrue(conflict.getMessage().contains("force=false"), conflict.getMessage());
        assertTrue(
                conflict.getCause() instanceof SQLException,
                "the driver's error must stay chained as the cause");
        assertFalse(repo.skillExists("dup"), "the first row must be rolled back");
    }

    @Test
    @DisplayName("real driver errors classify: SQLITE_CONSTRAINT_UNIQUE matches, NOT NULL not")
    void realDriverErrorsClassify() throws Exception {
        DataSource ds = createDataSource("skill_classifier");
        skillDialect(ds); // build 创建原始插入语句所针对的表
        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement()) {
            String insert =
                    "INSERT INTO agentscope_skills (name, description, skill_content, source)"
                            + " VALUES ";
            String row = "('n', 'd', 'c', 'test')";
            stmt.execute(insert + row);
            SQLException duplicate =
                    assertThrows(SQLException.class, () -> stmt.execute(insert + row));
            assertTrue(
                    JdbcAgentSkillRepository.isUniqueViolation(duplicate),
                    "SQLITE_CONSTRAINT_UNIQUE must classify as a duplicate: " + duplicate);
            SQLException notNull =
                    assertThrows(
                            SQLException.class,
                            () -> stmt.execute(insert + "('n2', NULL, 'c', 'test')"));
            assertFalse(
                    JdbcAgentSkillRepository.isUniqueViolation(notNull),
                    "SQLITE_CONSTRAINT_NOTNULL must not classify as a duplicate: " + notNull);
        }
    }

    /** 启用技能表组组装方言，作为唯一模式创建入口。 */
    private static AbstractJdbcDialect skillDialect(DataSource ds) {
        return AbstractJdbcDialect.from(ds).enableBaseTables(false).enableSkillTables(true).build();
    }

    /** 最小技能，冲突路径无需资源。 */
    private static AgentSkill sampleSkill(String name) {
        return new AgentSkill(Map.of("name", name, "description", "d"), "c", Map.of(), "test");
    }

    /** 文件型 SQLite DataSource，每个测试一个数据库文件。 */
    private DataSource createDataSource(String name) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve(name + ".db").toAbsolutePath());
        return ds;
    }
}
