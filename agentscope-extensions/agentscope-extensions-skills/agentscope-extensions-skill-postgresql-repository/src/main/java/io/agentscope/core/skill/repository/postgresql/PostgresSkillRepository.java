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
package io.agentscope.core.skill.repository.postgresql;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.util.JsonUtils;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 PostgreSQL 数据库的 AgentSkillRepository 实现。
 * <p>技能表保存核心查询字段 {@code name}、{@code description}、技能正文和来源，
 * 可选 {@code metadata_json} 保存完整元数据树；资源表保存 {@code id}、{@code resource_path}、{@code resource_content}。
 * <p>{@code createIfNotExist=true} 时新建表的结构：
 * <pre>
 * CREATE TABLE IF NOT EXISTS "agentscope"."agentscope_skills" (
 *     id BIGSERIAL PRIMARY KEY,
 *     name VARCHAR(255) NOT NULL UNIQUE,
 *     description TEXT NOT NULL,
 *     skill_content TEXT NOT NULL,
 *     source VARCHAR(255) NOT NULL,
 *     metadata_json TEXT NULL,
 *     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
 * );
 *
 * CREATE TABLE IF NOT EXISTS "agentscope"."agentscope_skill_resources" (
 *     id BIGINT NOT NULL,
 *     resource_path VARCHAR(500) NOT NULL,
 *     resource_content TEXT NOT NULL,
 *     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *     PRIMARY KEY (id, resource_path),
 *     FOREIGN KEY (id) REFERENCES "agentscope"."agentscope_skills"(id) ON DELETE CASCADE
 * );
 * </pre>
 * <p>兼容行为：新表带 {@code metadata_json}，现有表不会自动执行 {@code ALTER TABLE} 迁移。
 * 存在 {@code metadata_json} 时完整保存与恢复元数据；缺少 {@code metadata_json} 时回退旧结构，仅往返保存 {@code name} 与 {@code description}。
 * <p>支持 {@code createIfNotExist=true} 自动创建模式及表、运行时结构检测、技能及资源增删改查、参数化查询、事务，由 JDBC URL 选择数据库并配置模式。
 * <p>【学习提示】值通过占位符绑定，而模式及表标识符需单独白名单验证，二者不能互相替代。
 * 建表开关不是迁移器；元数据列能力在构造时缓存，运行中外部改表后需重建实例才能重新探测。
 * <p>示例：
 * <pre>{@code
 * // 使用带默认模式名和表名的简单构造函数
 * DataSource dataSource = createDataSource();
 * PostgresSkillRepository repo = new PostgresSkillRepository(dataSource, true, true);
 *
 * // 使用 Builder 自定义配置
 * PostgresSkillRepository repo = PostgresSkillRepository.builder(dataSource)
 *         .schemaName("my_schema")
 *         .skillsTableName("my_skills")
 *         .resourcesTableName("my_resources")
 *         .createIfNotExist(true)
 *         .writeable(true)
 *         .build();
 *
 * // 保存技能
 * AgentSkill skill = new AgentSkill("my-skill", "Description", "Content", resources);
 * repo.save(List.of(skill), false);
 *
 * // 获取技能
 * AgentSkill loaded = repo.getSkill("my-skill");
 * }</pre>
 * @deprecated 已由 {@code io.agentscope.extensions.jdbc.skill.JdbcAgentSkillRepository} 取代，
 * 位于 {@code agentscope-extensions-jdbc}，统一支持各 JDBC 方言。连接装配、结构升级和配置映射参见 JDBC 扩展文档的旧模块迁移章节。
 */
@Deprecated
public class PostgresSkillRepository implements AgentSkillRepository {

    private static final Logger logger = LoggerFactory.getLogger(PostgresSkillRepository.class);

    /**
     * 技能存储默认模式名。
     */
    private static final String DEFAULT_SCHEMA_NAME = "agentscope";

    /**
     * 技能默认表名。
     */
    private static final String DEFAULT_SKILLS_TABLE_NAME = "agentscope_skills";

    /**
     * 技能资源默认表名。
     */
    private static final String DEFAULT_RESOURCES_TABLE_NAME = "agentscope_skill_resources";

    /**
     * 验证模式及表名的模式。生成 SQL 会用双引号包裹标识符，同时遵循其他仓库采用的保守字母数字／下划线约定。
     */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /**
     * PostgreSQL 标识符长度上限。
     */
    private static final int MAX_IDENTIFIER_LENGTH = 63;

    /**
     * 单次 JDBC 批处理发送的资源行数上限。
     */
    private static final int RESOURCE_BATCH_SIZE = 1000;

    private static final Set<String> SUPPORTED_METADATA_JSON_COLUMN_TYPES =
            Set.of("text", "character varying", "character");

    /**
     * 技能名长度上限。
     */
    private static final int MAX_SKILL_NAME_LENGTH = 255;

    /**
     * 资源路径长度上限。
     */
    private static final int MAX_RESOURCE_PATH_LENGTH = 500;

    private final DataSource dataSource;
    private final String schemaName;
    private final String skillsTableName;
    private final String resourcesTableName;
    private final boolean metadataJsonColumnSupported;
    private volatile boolean writeable;

    @FunctionalInterface
    private interface SqlOperation {
        void execute() throws SQLException;
    }

    /**
     * 使用默认模式及表名创建 PostgresSkillRepository。
     * <p>默认模式为 {@code agentscope}，表名为 {@code agentscope_skills} 与 {@code agentscope_skill_resources}。
     * @param dataSource 提供数据库连接的数据源
     * @param createIfNotExist true 时为新部署自动建模式及表，false 时要求现有结构；不会为已有表自动添加 {@code metadata_json}
     * @param writeable 是否支持写操作
     * @throws IllegalArgumentException dataSource 为 null 时抛出
     * @throws IllegalStateException createIfNotExist 为 false 且模式及表不存在时抛出
     */
    public PostgresSkillRepository(
            DataSource dataSource, boolean createIfNotExist, boolean writeable) {
        this(
                dataSource,
                DEFAULT_SCHEMA_NAME,
                DEFAULT_SKILLS_TABLE_NAME,
                DEFAULT_RESOURCES_TABLE_NAME,
                createIfNotExist,
                writeable);
    }

    /**
     * 使用自定义模式及表名与选项创建 PostgresSkillRepository。
     * <p>{@code createIfNotExist} 为 true 时自动创建不存在的模式及表；false 且模式及表不存在时抛出 {@link IllegalStateException}。
     * 已有表按现状验证，不自动迁移添加 {@code metadata_json}。
     * <p>此构造器私有，请通过 {@link #builder(DataSource)} 配置。
     * @param dataSource 提供连接的数据源
     * @param schemaName 自定义模式名，null 或空值使用默认值
     * @param skillsTableName 自定义技能表名，null 或空值使用默认值
     * @param resourcesTableName 自定义资源表名，null 或空值使用默认值
     * @param createIfNotExist 是否为新部署自动建模式及表；不为已有表自动添加 {@code metadata_json}
     * @param writeable 是否支持写操作
     * @throws IllegalArgumentException dataSource 为 null 或标识符无效时抛出
     * @throws IllegalStateException createIfNotExist 为 false 且模式及表不存在时抛出
     */
    private PostgresSkillRepository(
            DataSource dataSource,
            String schemaName,
            String skillsTableName,
            String resourcesTableName,
            boolean createIfNotExist,
            boolean writeable) {
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource cannot be null");
        }

        this.dataSource = dataSource;
        this.writeable = writeable;

        // 空值使用默认配置，再执行验证。
        this.schemaName =
                (schemaName == null || schemaName.trim().isEmpty())
                        ? DEFAULT_SCHEMA_NAME
                        : schemaName.trim();
        this.skillsTableName =
                (skillsTableName == null || skillsTableName.trim().isEmpty())
                        ? DEFAULT_SKILLS_TABLE_NAME
                        : skillsTableName.trim();
        this.resourcesTableName =
                (resourcesTableName == null || resourcesTableName.trim().isEmpty())
                        ? DEFAULT_RESOURCES_TABLE_NAME
                        : resourcesTableName.trim();

        // 验证标识符，防止 SQL 注入。
        validateIdentifier(this.schemaName, "Schema name");
        validateIdentifier(this.skillsTableName, "Skills table name");
        validateIdentifier(this.resourcesTableName, "Resources table name");

        if (createIfNotExist) {
            // 不存在时创建模式及表。
            createSchemaIfNotExist();
            createTablesIfNotExist();
        } else {
            // 验证模式及表存在。
            verifySchemaExists();
            verifyTablesExist();
        }

        this.metadataJsonColumnSupported = detectMetadataJsonColumnSupport();

        logger.info(
                "PostgresSkillRepository initialized with schema: {}, skills table: {},"
                        + " resources table: {}",
                this.schemaName,
                this.skillsTableName,
                this.resourcesTableName);
    }

    /**
     * 不存在时创建模式。
     * <p>PostgreSQL 数据库由 JDBC URL 选择，本实现只在该数据库内创建模式，不尝试创建数据库。
     */
    private void createSchemaIfNotExist() {
        String createSchemaSql = "CREATE SCHEMA IF NOT EXISTS " + quoteIdentifier(schemaName);

        try (Connection conn = dataSource.getConnection()) {
            executeInWriteTransaction(
                    conn,
                    () -> {
                        try (PreparedStatement stmt = conn.prepareStatement(createSchemaSql)) {
                            stmt.execute();
                        }
                    });
            logger.debug("Schema created or already exists: {}", schemaName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create schema: " + schemaName, e);
        }
    }

    /**
     * 不存在时创建技能表与资源表。
     * <p>新技能表包含可选 {@code metadata_json} 列，无需改变旧查询字段即可保存完整元数据。
     */
    private void createTablesIfNotExist() {
        // 创建技能表，以 id 为主键、name 为唯一键。
        String createSkillsTableSql =
                "CREATE TABLE IF NOT EXISTS "
                        + getFullTableName(skillsTableName)
                        + " (id BIGSERIAL PRIMARY KEY,"
                        + " name VARCHAR(255) NOT NULL UNIQUE, description TEXT NOT NULL,"
                        + " skill_content TEXT NOT NULL, source VARCHAR(255) NOT NULL,"
                        + " metadata_json TEXT NULL,"
                        + " created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP"
                        + " DEFAULT CURRENT_TIMESTAMP)";

        // 创建资源表，以 id 作为外键。
        String createResourcesTableSql =
                "CREATE TABLE IF NOT EXISTS "
                        + getFullTableName(resourcesTableName)
                        + " (id BIGINT NOT NULL, resource_path VARCHAR(500) NOT NULL,"
                        + " resource_content TEXT NOT NULL, created_at TIMESTAMP DEFAULT"
                        + " CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + " PRIMARY KEY (id, resource_path),"
                        + " FOREIGN KEY (id) REFERENCES "
                        + getFullTableName(skillsTableName)
                        + "(id) ON DELETE CASCADE)";

        try (Connection conn = dataSource.getConnection()) {
            executeInWriteTransaction(
                    conn,
                    () -> {
                        try (PreparedStatement stmt = conn.prepareStatement(createSkillsTableSql)) {
                            stmt.execute();
                            logger.debug(
                                    "Skills table created or already exists: {}", skillsTableName);
                        }

                        try (PreparedStatement stmt =
                                conn.prepareStatement(createResourcesTableSql)) {
                            stmt.execute();
                            logger.debug(
                                    "Resources table created or already exists: {}",
                                    resourcesTableName);
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tables", e);
        }
    }

    /**
     * 验证模式存在。
     * @throws IllegalStateException 模式不存在时抛出
     */
    private void verifySchemaExists() {
        String checkSql =
                "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(checkSql)) {
            stmt.setString(1, schemaName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "Schema does not exist: "
                                    + schemaName
                                    + ". Use PostgresSkillRepository(dataSource, true, writeable)"
                                    + " or builder(dataSource).createIfNotExist(true).build() to"
                                    + " auto-create.");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check schema existence: " + schemaName, e);
        }
    }

    /**
     * 验证所需表存在。
     * @throws IllegalStateException 任一表不存在时抛出
     */
    private void verifyTablesExist() {
        verifyTableExists(skillsTableName);
        verifyTableExists(resourcesTableName);
    }

    /**
     * 验证指定表存在。
     * @param tableName 待检查表名
     * @throws IllegalStateException 表不存在时抛出
     */
    private void verifyTableExists(String tableName) {
        String checkSql =
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(checkSql)) {
            stmt.setString(1, schemaName);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "Table does not exist: "
                                    + schemaName
                                    + "."
                                    + tableName
                                    + ". Use PostgresSkillRepository(dataSource, true, writeable)"
                                    + " or builder(dataSource).createIfNotExist(true).build() to"
                                    + " auto-create.");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check table existence: " + tableName, e);
        }
    }

    /**
     * 获取带模式前缀的完整表名。
     * @param tableName 表名
     * @return 双引号转义的完整名称（"schema"."table"）
     */
    private String getFullTableName(String tableName) {
        return quoteIdentifier(schemaName) + "." + quoteIdentifier(tableName);
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + identifier + "\"";
    }

    /**
     * 检测当前技能表是否支持可选的 {@code metadata_json} 列。
     * <p>构造时缓存此能力；有此列时完整往返元数据，否则走旧版兼容路径。
     */
    private boolean detectMetadataJsonColumnSupport() {
        String checkSql =
                "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ?"
                        + " AND TABLE_NAME = ? AND COLUMN_NAME = ? LIMIT 1";

        try {
            Connection conn = dataSource.getConnection();
            try {
                PreparedStatement stmt = conn.prepareStatement(checkSql);
                try {
                    stmt.setString(1, schemaName);
                    stmt.setString(2, skillsTableName);
                    stmt.setString(3, "metadata_json");
                    ResultSet rs = stmt.executeQuery();
                    try {
                        if (!rs.next()) {
                            return false;
                        }
                        String dataType = rs.getString("DATA_TYPE");
                        if (isSupportedMetadataJsonColumnType(dataType)) {
                            return true;
                        }
                        logger.warn(
                                "metadata_json column on {}.{} has unsupported type '{}'; extended"
                                        + " metadata will not be persisted",
                                schemaName,
                                skillsTableName,
                                dataType);
                        return false;
                    } finally {
                        rs.close();
                    }
                } finally {
                    stmt.close();
                }
            } finally {
                conn.close();
            }
        } catch (SQLException e) {
            logger.warn(
                    "Failed to detect metadata_json column support, falling back to legacy schema",
                    e);
            return false;
        }
    }

    private boolean isSupportedMetadataJsonColumnType(String dataType) {
        if (dataType == null) {
            return false;
        }
        return SUPPORTED_METADATA_JSON_COLUMN_TYPES.contains(dataType.toLowerCase(Locale.ROOT));
    }

    @Override
    public AgentSkill getSkill(String name) {
        validateSkillName(name);

        String selectSkillSql =
                "SELECT id, name, description, skill_content, source"
                        + (metadataJsonColumnSupported ? ", metadata_json" : "")
                        + " FROM "
                        + getFullTableName(skillsTableName)
                        + " WHERE name = ?";

        String selectResourcesSql =
                "SELECT resource_path, resource_content FROM "
                        + getFullTableName(resourcesTableName)
                        + " WHERE id = ?";

        try (Connection conn = dataSource.getConnection()) {
            // 加载技能元数据。
            long skillId;
            String description;
            String skillContent;
            String source;
            String metadataJson = null;

            try (PreparedStatement stmt = conn.prepareStatement(selectSkillSql)) {
                stmt.setString(1, name);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("Skill not found: " + name);
                    }
                    skillId = rs.getLong("id");
                    description = rs.getString("description");
                    skillContent = rs.getString("skill_content");
                    source = rs.getString("source");
                    if (metadataJsonColumnSupported) {
                        metadataJson = rs.getString("metadata_json");
                    }
                }
            }

            // 用 skillId 加载技能资源。
            Map<String, String> resources = new HashMap<>();
            try (PreparedStatement stmt = conn.prepareStatement(selectResourcesSql)) {
                stmt.setLong(1, skillId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String path = rs.getString("resource_path");
                        String content = rs.getString("resource_content");
                        resources.put(path, content);
                    }
                }
            }

            return buildSkill(name, description, skillContent, source, metadataJson, resources);

        } catch (SQLException e) {
            throw new RuntimeException("Failed to load skill: " + name, e);
        }
    }

    @Override
    public List<String> getAllSkillNames() {
        String selectSql =
                "SELECT name FROM " + getFullTableName(skillsTableName) + " ORDER BY name";

        List<String> skillNames = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(selectSql);
                ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                skillNames.add(rs.getString("name"));
            }

        } catch (SQLException e) {
            throw new RuntimeException("Failed to list skill names", e);
        }

        return skillNames;
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        String selectAllSkillsSql =
                "SELECT id, name, description, skill_content, source"
                        + (metadataJsonColumnSupported ? ", metadata_json" : "")
                        + " FROM "
                        + getFullTableName(skillsTableName)
                        + " ORDER BY name";

        String selectAllResourcesSql =
                "SELECT id, resource_path, resource_content FROM "
                        + getFullTableName(resourcesTableName);

        try (Connection conn = dataSource.getConnection()) {
            // 一次查询加载全部技能，以 id 关联资源。
            Map<Long, LoadedSkillRecord> skillRecords = new HashMap<>();

            try (PreparedStatement stmt = conn.prepareStatement(selectAllSkillsSql);
                    ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long skillId = rs.getLong("id");
                    String name = rs.getString("name");
                    String description = rs.getString("description");
                    String skillContent = rs.getString("skill_content");
                    String source = rs.getString("source");
                    String metadataJson =
                            metadataJsonColumnSupported ? rs.getString("metadata_json") : null;

                    skillRecords.put(
                            skillId,
                            new LoadedSkillRecord(
                                    name, description, skillContent, source, metadataJson));
                }
            }

            // 一次查询加载全部资源，并按 id 关联技能。
            try (PreparedStatement stmt = conn.prepareStatement(selectAllResourcesSql);
                    ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long skillId = rs.getLong("id");
                    String resourcePath = rs.getString("resource_path");
                    String resourceContent = rs.getString("resource_content");

                    LoadedSkillRecord record = skillRecords.get(skillId);
                    if (record != null) {
                        record.resources.put(resourcePath, resourceContent);
                    } else {
                        logger.warn("Found orphaned resource for non-existent id: {}", skillId);
                    }
                }
            }

            // 构建全部技能。
            List<AgentSkill> skills = new ArrayList<>(skillRecords.size());
            for (LoadedSkillRecord record : skillRecords.values()) {
                try {
                    skills.add(
                            buildSkill(
                                    record.name,
                                    record.description,
                                    record.skillContent,
                                    record.source,
                                    record.metadataJson,
                                    record.resources));
                } catch (IllegalArgumentException e) {
                    logger.warn("Failed to build skill: {}", e.getMessage(), e);
                }
            }

            return skills;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to load all skills", e);
        }
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        if (skills == null || skills.isEmpty()) {
            return false;
        }

        if (!writeable) {
            logger.warn("Cannot save skills: repository is read-only");
            return false;
        }

        try (Connection conn = dataSource.getConnection()) {
            // 事务前预检全部技能名和资源路径。
            for (AgentSkill skill : skills) {
                validateSkillName(skill.getName());

                // 在事务前验证资源路径，避免不必要的回滚。
                Map<String, String> resources = skill.getResources();
                if (resources != null && !resources.isEmpty()) {
                    for (String path : resources.keySet()) {
                        validateResourcePath(path);
                    }
                }
            }

            // force=false 时，在开始事务前检查所有技能是否存在；
            // 后续并发竞争仍由数据库约束和事务处理。
            if (!force) {
                List<String> existingSkills = new ArrayList<>();
                for (AgentSkill skill : skills) {
                    if (skillExistsInternal(conn, skill.getName())) {
                        existingSkills.add(skill.getName());
                    }
                }
                if (!existingSkills.isEmpty()) {
                    String conflictingSkills = String.join(", ", existingSkills);
                    throw new IllegalStateException(
                            "Cannot save skills: the following skills already exist and"
                                    + " force=false: "
                                    + conflictingSkills
                                    + ". Use force=true to overwrite existing skills.");
                }
            }

            executeInWriteTransaction(
                    conn,
                    () -> {
                        for (AgentSkill skill : skills) {
                            String skillName = skill.getName();

                            // 检查技能是否存在，用于 force=true。
                            boolean exists = skillExistsInternal(conn, skillName);

                            if (exists) {
                                // 删除原技能及其资源。
                                deleteSkillInternal(conn, skillName);
                                logger.debug("Deleted existing skill for overwrite: {}", skillName);
                            }

                            // 插入技能并获取生成的 id。
                            long skillId = insertSkill(conn, skill);

                            // 使用 skillId 插入资源。
                            insertResources(conn, skillId, skill.getResources());

                            logger.info("Successfully saved skill: {} (id={})", skillName, skillId);
                        }
                    });
            return true;

        } catch (SQLException e) {
            logger.error("Failed to save skills", e);
            throw new RuntimeException("Failed to save skills", e);
        }
    }

    /**
     * 向数据库插入技能并返回生成的 id。
     * @param conn 数据库连接
     * @param skill 待插入技能
     * @return 生成的 id
     * @throws SQLException 插入失败时抛出
     */
    private long insertSkill(Connection conn, AgentSkill skill) throws SQLException {
        String insertSql =
                "INSERT INTO "
                        + getFullTableName(skillsTableName)
                        + (metadataJsonColumnSupported
                                ? " (name, description, skill_content, source, metadata_json)"
                                        + " VALUES (?, ?, ?, ?, ?)"
                                : " (name, description, skill_content, source) VALUES (?, ?, ?,"
                                        + " ?)");

        try (PreparedStatement stmt =
                conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, skill.getName());
            stmt.setString(2, skill.getDescription());
            stmt.setString(3, skill.getSkillContent());
            stmt.setString(4, skill.getSource());
            if (metadataJsonColumnSupported) {
                stmt.setString(5, serializeMetadata(skill.getMetadata()));
            } else if (hasExtendedMetadata(skill.getMetadata())) {
                logger.warn(
                        "metadata_json column not found in {}.{}; extended metadata for skill '{}'"
                                + " will not be persisted",
                        schemaName,
                        skillsTableName,
                        skill.getName());
            }
            stmt.executeUpdate();

            try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    return generatedKeys.getLong(1);
                } else {
                    throw new SQLException(
                            "Failed to get generated id for skill: " + skill.getName());
                }
            }
        }
    }

    /**
     * 批量插入技能资源。
     * <p>通过 JDBC batch 减少逐条提交开销；实际网络往返次数取决于驱动和批处理配置，不保证单次往返。
     * @param conn 数据库连接
     * @param skillId 资源所属技能 id
     * @param resources 待插入资源
     * @throws SQLException 插入失败时抛出
     */
    private void insertResources(Connection conn, long skillId, Map<String, String> resources)
            throws SQLException {
        if (resources == null || resources.isEmpty()) {
            logger.debug("No resources to insert for id: {}", skillId);
            return;
        }

        // 资源路径已在 save() 开始事务前验证。

        String insertSql =
                "INSERT INTO "
                        + getFullTableName(resourcesTableName)
                        + " (id, resource_path, resource_content) VALUES (?, ?, ?)";

        // 分块批处理，避免超过 PostgreSQL 绑定参数数量限制。
        try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
            int insertedCount = 0;
            List<String> batchPaths = new ArrayList<>(RESOURCE_BATCH_SIZE);
            for (Map.Entry<String, String> entry : resources.entrySet()) {
                String path = entry.getKey();
                String content = entry.getValue();

                stmt.setLong(1, skillId);
                stmt.setString(2, path);
                stmt.setString(3, content);

                stmt.addBatch();
                batchPaths.add(path);
                if (batchPaths.size() == RESOURCE_BATCH_SIZE) {
                    insertedCount += executeResourceBatch(stmt, batchPaths, skillId);
                    batchPaths.clear();
                }
            }
            if (!batchPaths.isEmpty()) {
                insertedCount += executeResourceBatch(stmt, batchPaths, skillId);
            }

            logger.debug(
                    "Batch inserted {} resources for id '{}' (total: {})",
                    insertedCount,
                    skillId,
                    resources.size());
        }
    }

    private int executeResourceBatch(PreparedStatement stmt, List<String> batchPaths, long skillId)
            throws SQLException {
        int[] results = stmt.executeBatch();
        try {
            if (results.length != batchPaths.size()) {
                throw new SQLException(
                        "Resource batch for id '"
                                + skillId
                                + "' returned "
                                + results.length
                                + " results for "
                                + batchPaths.size()
                                + " resources");
            }

            int insertedCount = 0;
            List<String> failedResources = new ArrayList<>();
            for (int i = 0; i < results.length; i++) {
                if (results[i] > 0 || results[i] == Statement.SUCCESS_NO_INFO) {
                    insertedCount++;
                } else {
                    String path = batchPaths.get(i);
                    failedResources.add("index " + i + " path '" + path + "' result " + results[i]);
                    logger.error(
                            "Failed to insert resource for id '{}' at batch index {} path '{}'"
                                    + " with result {}",
                            skillId,
                            i,
                            path,
                            results[i]);
                }
            }
            if (!failedResources.isEmpty()) {
                throw new SQLException(
                        "Failed to insert resources for id '"
                                + skillId
                                + "': "
                                + String.join(", ", failedResources));
            }
            return insertedCount;
        } finally {
            stmt.clearBatch();
        }
    }

    @Override
    public boolean delete(String skillName) {
        if (!writeable) {
            logger.warn("Cannot delete skill: repository is read-only");
            return false;
        }

        validateSkillName(skillName);

        try {
            Connection conn = dataSource.getConnection();
            try {
                if (!skillExistsInternal(conn, skillName)) {
                    logger.warn("Skill does not exist: {}", skillName);
                    return false;
                }

                executeInWriteTransaction(conn, () -> deleteSkillInternal(conn, skillName));
                logger.info("Successfully deleted skill: {}", skillName);
                return true;
            } finally {
                conn.close();
            }

        } catch (SQLException e) {
            logger.error("Failed to delete skill: {}", skillName, e);
            throw new RuntimeException("Failed to delete skill: " + skillName, e);
        }
    }

    /**
     * 从数据库删除技能及其资源。
     * <p>按技能名删除会触发 ON DELETE CASCADE，自动删除资源。
     * @param conn 数据库连接
     * @param skillName 待删除技能名
     * @throws SQLException 删除失败时抛出
     */
    private void deleteSkillInternal(Connection conn, String skillName) throws SQLException {
        // 按名称删除技能，资源通过 ON DELETE CASCADE 删除。
        String deleteSkillSql =
                "DELETE FROM " + getFullTableName(skillsTableName) + " WHERE name = ?";

        try (PreparedStatement stmt = conn.prepareStatement(deleteSkillSql)) {
            stmt.setString(1, skillName);
            stmt.executeUpdate();
        }
    }

    @Override
    public boolean skillExists(String skillName) {
        if (skillName == null || skillName.isEmpty()) {
            return false;
        }

        try (Connection conn = dataSource.getConnection()) {
            return skillExistsInternal(conn, skillName);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check skill existence: " + skillName, e);
        }
    }

    /**
     * 使用现有连接检查技能是否存在。
     * @param conn 数据库连接
     * @param skillName 待检查技能名
     * @return 存在时为 true
     * @throws SQLException 查询失败时抛出
     */
    private boolean skillExistsInternal(Connection conn, String skillName) throws SQLException {
        String checkSql =
                "SELECT 1 FROM " + getFullTableName(skillsTableName) + " WHERE name = ? LIMIT 1";

        try (PreparedStatement stmt = conn.prepareStatement(checkSql)) {
            stmt.setString(1, skillName);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo(
                "postgresql", schemaName + "." + skillsTableName, writeable);
    }

    @Override
    public String getSource() {
        return "postgresql_" + schemaName + "_" + skillsTableName;
    }

    @Override
    public void setWriteable(boolean writeable) {
        this.writeable = writeable;
    }

    @Override
    public boolean isWriteable() {
        return writeable;
    }

    @Override
    public void close() {
        // DataSource 由外部管理，这里不关闭它。
        logger.debug("PostgresSkillRepository closed");
    }

    /**
     * 获取技能存储模式名。
     * @return 模式名
     */
    public String getSchemaName() {
        return schemaName;
    }

    /**
     * 获取技能表名。
     * @return 技能表名
     */
    public String getSkillsTableName() {
        return skillsTableName;
    }

    /**
     * 获取资源表名。
     * @return 资源表名
     */
    public String getResourcesTableName() {
        return resourcesTableName;
    }

    /**
     * 获取提供数据库连接的数据源。
     * @return DataSource 实例
     */
    public DataSource getDataSource() {
        return dataSource;
    }

    /**
     * 暴露当前技能表是否支持 {@code metadata_json}，包级可见供测试使用。
     */
    boolean isMetadataJsonColumnSupported() {
        return metadataJsonColumnSupported;
    }

    /**
     * 从 SQL 行构建 {@link AgentSkill}：可用时恢复完整元数据，否则回退旧核心字段。
     */
    private AgentSkill buildSkill(
            String name,
            String description,
            String skillContent,
            String source,
            String metadataJson,
            Map<String, String> resources) {
        Map<String, Object> metadata = deserializeMetadata(metadataJson, name, description);
        return new AgentSkill(metadata, skillContent, resources, source);
    }

    /**
     * 存在 {@code metadata_json} 时反序列化，再用权威 SQL 列覆盖 {@code name} 和 {@code description}。
     */
    private Map<String, Object> deserializeMetadata(
            String metadataJson, String name, String description) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        if (metadataJson != null && !metadataJson.isBlank()) {
            try {
                Map<String, Object> parsed =
                        JsonUtils.getJsonCodec()
                                .fromJson(
                                        metadataJson, new TypeReference<Map<String, Object>>() {});
                if (parsed != null) {
                    metadata.putAll(parsed);
                }
            } catch (RuntimeException e) {
                logger.warn(
                        "Failed to deserialize metadata_json for skill '{}', falling back to core"
                                + " metadata",
                        name,
                        e);
            }
        }
        metadata.put("name", name);
        metadata.put("description", description);
        return metadata;
    }

    /**
     * 将完整技能元数据树序列化到 {@code metadata_json}。
     */
    private String serializeMetadata(Map<String, Object> metadata) {
        return JsonUtils.getJsonCodec().toJson(metadata);
    }

    /**
     * 检查元数据是否含旧核心列之外的字段，仅用于向旧结构写入时发出降级警告。
     */
    private boolean hasExtendedMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        return metadata.keySet().stream()
                .anyMatch(key -> !"name".equals(key) && !"description".equals(key));
    }

    /**
     * 从独立结果集拼装技能及资源时使用的临时容器。
     */
    private static final class LoadedSkillRecord {
        private final String name;
        private final String description;
        private final String skillContent;
        private final String source;
        private final String metadataJson;
        private final Map<String, String> resources = new HashMap<>();

        private LoadedSkillRecord(
                String name,
                String description,
                String skillContent,
                String source,
                String metadataJson) {
            this.name = name;
            this.description = description;
            this.skillContent = skillContent;
            this.source = source;
            this.metadataJson = metadataJson;
        }
    }

    /**
     * 清除当前配置技能表中的全部技能，用于测试或清理。
     * <p>删除技能时通过 ON DELETE CASCADE 自动删除资源。
     * @return 删除的技能数量
     */
    public int clearAllSkills() {
        if (!writeable) {
            logger.warn("Cannot clear skills: repository is read-only");
            return 0;
        }

        // 资源通过 ON DELETE CASCADE 自动删除。
        String deleteSkillsSql = "DELETE FROM " + getFullTableName(skillsTableName);

        try (Connection conn = dataSource.getConnection()) {
            int[] deleted = new int[1];
            executeInWriteTransaction(
                    conn,
                    () -> {
                        // 删除全部技能，资源由级联删除。
                        try (PreparedStatement stmt = conn.prepareStatement(deleteSkillsSql)) {
                            deleted[0] = stmt.executeUpdate();
                        }
                    });
            logger.info("Cleared all skills, {} skills deleted", deleted[0]);
            return deleted[0];

        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear skills", e);
        }
    }

    /**
     * 安全恢复连接的自动提交模式。
     * <p>捕获并记录恢复时的 SQLException，避免 finally 中的新异常掩盖原始异常。
     * @param conn 待恢复自动提交的连接
     */
    private void restoreAutoCommit(Connection conn, boolean originalAutoCommit) {
        try {
            if (conn.getAutoCommit() != originalAutoCommit) {
                conn.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException e) {
            logger.warn("Failed to restore auto-commit mode on connection", e);
        }
    }

    private void executeInWriteTransaction(Connection conn, SqlOperation operation)
            throws SQLException {
        boolean originalAutoCommit = conn.getAutoCommit();
        if (originalAutoCommit) {
            conn.setAutoCommit(false);
        }

        try {
            operation.execute();
            conn.commit();
        } catch (SQLException | RuntimeException e) {
            try {
                conn.rollback();
            } catch (SQLException rollbackException) {
                e.addSuppressed(rollbackException);
            }
            throw e;
        } finally {
            restoreAutoCommit(conn, originalAutoCommit);
        }
    }

    /**
     * 验证技能名。
     * @param skillName 待验证技能名
     * @throws IllegalArgumentException 技能名无效时抛出
     */
    private void validateSkillName(String skillName) {
        if (skillName == null || skillName.trim().isEmpty()) {
            throw new IllegalArgumentException("Skill name cannot be null or empty");
        }
        if (skillName.length() > MAX_SKILL_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Skill name cannot exceed " + MAX_SKILL_NAME_LENGTH + " characters");
        }
        // 检查路径遍历尝试。
        if (skillName.contains("..") || skillName.contains("/") || skillName.contains("\\")) {
            throw new IllegalArgumentException("Skill name cannot contain path separators or '..'");
        }
    }

    /**
     * 验证资源路径。
     * @param path 待验证资源路径
     * @throws IllegalArgumentException 路径无效时抛出
     */
    private void validateResourcePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Resource path cannot be null or empty");
        }
        if (path.length() > MAX_RESOURCE_PATH_LENGTH) {
            throw new IllegalArgumentException(
                    "Resource path cannot exceed " + MAX_RESOURCE_PATH_LENGTH + " characters");
        }
    }

    /**
     * 验证模式或表标识符，防止 SQL 注入。
     * <p>只允许字母、数字、下划线，且必须以字母或下划线开头。模式及表名不能作为预处理语句参数绑定，因此需单独验证。
     * @param identifier 待验证的模式名或表名
     * @param identifierType 标识符类型说明，用于错误消息
     * @throws IllegalArgumentException 标识符无效或包含不安全字符时抛出
     */
    private void validateIdentifier(String identifier, String identifierType) {
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

    /**
     * 创建 PostgresSkillRepository 构建器。
     * <p>示例：
     * <pre>{@code
     * PostgresSkillRepository repo = PostgresSkillRepository.builder(dataSource)
     *         .schemaName("my_schema")
     *         .skillsTableName("my_skills")
     *         .resourcesTableName("my_resources")
     *         .createIfNotExist(true)
     *         .writeable(true)
     *         .build();
     * }</pre>
     * @param dataSource 必需的数据源
     * @return 新构建器实例
     * @throws IllegalArgumentException dataSource 为 null 时抛出
     */
    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }

    /**
     * 通过流式 API 配置模式及表名和行为选项，创建 PostgresSkillRepository。
     * @deprecated 参见 {@link PostgresSkillRepository}，迁移到 {@code agentscope-extensions-jdbc} 的 {@code JdbcAgentSkillRepository}。
     */
    @Deprecated
    public static class Builder {

        private final DataSource dataSource;
        private String schemaName = DEFAULT_SCHEMA_NAME;
        private String skillsTableName = DEFAULT_SKILLS_TABLE_NAME;
        private String resourcesTableName = DEFAULT_RESOURCES_TABLE_NAME;
        private boolean createIfNotExist = true;
        private boolean writeable = true;

        /**
         * 用必需数据源创建构建器。
         * @param dataSource 提供连接的数据源
         * @throws IllegalArgumentException dataSource 为 null 时抛出
         */
        private Builder(DataSource dataSource) {
            if (dataSource == null) {
                throw new IllegalArgumentException("DataSource cannot be null");
            }
            this.dataSource = dataSource;
        }

        /**
         * 设置技能模式名。
         * @param schemaName 模式名，默认 "agentscope"
         * @return 当前构建器，可链式调用
         */
        public Builder schemaName(String schemaName) {
            this.schemaName = schemaName;
            return this;
        }

        /**
         * 设置技能表名。
         * @param skillsTableName 技能表名，默认 "agentscope_skills"
         * @return 当前构建器，可链式调用
         */
        public Builder skillsTableName(String skillsTableName) {
            this.skillsTableName = skillsTableName;
            return this;
        }

        /**
         * 设置资源表名。
         * @param resourcesTableName 资源表名，默认 "agentscope_skill_resources"
         * @return 当前构建器，可链式调用
         */
        public Builder resourcesTableName(String resourcesTableName) {
            this.resourcesTableName = resourcesTableName;
            return this;
        }

        /**
         * 设置模式及表不存在时是否创建。
         * @param createIfNotExist true 自动创建，false 要求已有结构；默认 true
         * @return 当前构建器，可链式调用
         */
        public Builder createIfNotExist(boolean createIfNotExist) {
            this.createIfNotExist = createIfNotExist;
            return this;
        }

        /**
         * 设置是否支持写操作。
         * @param writeable true 允许写入，false 为只读；默认 true
         * @return 当前构建器，可链式调用
         */
        public Builder writeable(boolean writeable) {
            this.writeable = writeable;
            return this;
        }

        /**
         * 构建 PostgresSkillRepository。
         * @return 新建的 PostgresSkillRepository
         * @throws IllegalArgumentException 标识符无效时抛出
         * @throws IllegalStateException createIfNotExist 为 false 且模式及表不存在时抛出
         */
        public PostgresSkillRepository build() {
            return new PostgresSkillRepository(
                    dataSource,
                    schemaName,
                    skillsTableName,
                    resourcesTableName,
                    createIfNotExist,
                    writeable);
        }
    }
}
