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
package io.agentscope.extensions.jdbc.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.state.State;
import io.agentscope.extensions.jdbc.JdbcDistributedStore;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.skill.JdbcAgentSkillRepository;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL Testcontainers 集成测试：以真实数据库验证四个组件，特别是 ON CONFLICT UPSERT 与 BYTEA 处理。
 *
 * <p>需要 Docker，运行命令：
 * {@code mvn -pl agentscope-extensions/agentscope-extensions-jdbc -am verify -Pintegration}
 *
 * @author shanhongyu
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.DisplayName.class)
class PostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withDatabaseName("agentscope");

    record TestState(String value) implements State {}

    @Test
    @DisplayName("01: AbstractJdbcDialect.from detects PostgreSQL")
    void dialectDetection() {
        DataSource ds = createDataSource();
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(ds).build();
        assertTrue(dialect.getClass().getSimpleName().contains("Postgres"));
    }

    @Test
    @DisplayName("02: state store CRUD with ON CONFLICT")
    void stateStoreCrud() {
        var store = JdbcDistributedStore.create(createDataSource()).agentStateStore();
        store.save("user1", "s1", "key", new TestState("value"));

        Optional<TestState> result = store.get("user1", "s1", "key", TestState.class);
        assertTrue(result.isPresent());
        assertEquals("value", result.get().value());
    }

    @Test
    @DisplayName("03: KV store CAS with ON CONFLICT")
    void kvStoreCas() {
        var store = JdbcDistributedStore.create(createDataSource()).baseStore();
        assertTrue(store.putIfVersion(List.of("ns"), "k", Map.of("v", 1), 0L));
        StoreItem item = store.get(List.of("ns"), "k");
        assertNotNull(item);
        assertEquals(1L, item.version());
    }

    @Test
    @DisplayName("04: snapshot BYTEA upload/download")
    void snapshotBytea() throws Exception {
        var ds = createDataSource();
        var spec =
                new io.agentscope.extensions.jdbc.snapshot.JdbcSnapshotSpec(
                        ds, AbstractJdbcDialect.from(ds).build());
        RemoteSnapshotClient client = spec.getClient();

        byte[] data = "postgres bytea content".getBytes();
        client.upload("pg-snap", new ByteArrayInputStream(data));
        try (var downloaded = client.download("pg-snap")) {
            assertEquals(data.length, downloaded.readAllBytes().length);
        }
    }

    @Test
    @DisplayName("05: sandbox lock uses PostgreSQL advisory lock")
    void advisoryLock() throws Exception {
        var guard = JdbcDistributedStore.create(createDataSource()).sandboxExecutionGuard();
        // 长锁名覆盖 SHA-256 到 64 位咨询锁键的派生。
        String longValue = "agent-" + "x".repeat(120);
        var key = SandboxIsolationKey.resolve(IsolationScope.GLOBAL, null, longValue).orElseThrow();

        SandboxLease lease = guard.tryEnter(key);
        assertNotNull(lease);
        lease.close();
    }

    @Test
    @DisplayName("06: build() blocks when a legacy table lacks the version column")
    void buildBlocksOnLegacyTableMissingColumn() throws Exception {
        DataSource ds = createDataSource();
        // 先建当前模式，再将 sessions 表还原为旧 postgresql 扩展
        // 创建的结构：同名但缺 version 列。
        AbstractJdbcDialect.from(ds).build();
        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE agentscope_sessions DROP COLUMN version");
        }

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class, () -> AbstractJdbcDialect.from(ds).build());

        assertTrue(exception.getMessage().contains("version"), exception.getMessage());
        assertTrue(exception.getMessage().contains("agentscope_sessions"), exception.getMessage());
    }

    @Test
    @DisplayName("07: skill repository round-trip on the skill table group")
    void skillRepositoryRoundTrip() {
        DataSource ds = createDataSource();
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(ds).enableSkillTables(true).build();
        var repo = new JdbcAgentSkillRepository(ds, dialect);

        var skill =
                new AgentSkill(
                        Map.of(
                                "name", "pg-skill",
                                "description", "integration skill",
                                "homepage", "https://example.com"),
                        "content",
                        Map.of("readme.md", "hello"),
                        "integration");
        assertTrue(repo.save(List.of(skill), false));

        AgentSkill loaded = repo.getSkill("pg-skill");
        assertEquals("https://example.com", loaded.getMetadataValue("homepage"));
        assertEquals("hello", loaded.getResource("readme.md"));
        assertTrue(repo.delete("pg-skill"));
        assertFalse(repo.skillExists("pg-skill"));
    }

    @Test
    @DisplayName("08: namespaced skills coexist and stay isolated on PostgreSQL")
    void namespacedSkillIsolation() {
        DataSource ds = createDataSource();
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(ds).enableSkillTables(true).build();
        var repo = new JdbcAgentSkillRepository(ds, dialect);

        var teamA =
                new AgentSkill(
                        Map.of("name", "pg-ns-skill", "description", "team-a"),
                        "content-a",
                        Map.of("docs/a.md", "a"),
                        "integration");
        var teamB =
                new AgentSkill(
                        Map.of("name", "pg-ns-skill", "description", "team-b"),
                        "content-b",
                        Map.of("docs/b.md", "b"),
                        "integration");
        assertTrue(repo.save("team-a", List.of(teamA), false));
        assertTrue(
                repo.save("team-b", List.of(teamB), false),
                "UNIQUE(namespace, name) must admit the same name in another namespace");
        assertEquals("content-a", repo.getSkill("team-a", "pg-ns-skill").getSkillContent());
        assertEquals("content-b", repo.getSkill("team-b", "pg-ns-skill").getSkillContent());

        // 在真实 PostgreSQL 上检查命名空间限定的删除与清空。
        assertTrue(repo.delete("team-a", "pg-ns-skill"));
        assertEquals("b", repo.getSkill("team-b", "pg-ns-skill").getResource("docs/b.md"));
        assertEquals(1, repo.clearAllSkills("team-b"));
        assertTrue(repo.getAllSkillNames("team-b").isEmpty());
    }

    /**
     * Testcontainers PostgreSQL 数据库的数据源。
     *
     * @return 可用的 DataSource
     */
    private DataSource createDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }
}
