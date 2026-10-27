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

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.dialect.BoundSql;
import io.agentscope.extensions.jdbc.dialect.table.SkillDialect;
import io.agentscope.extensions.jdbc.dialect.table.SkillResourcesDialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 由 {@link AbstractJdbcDialect} 支持、与数据库种类无关的技能仓库。
 *
 * <p>实现 {@link AgentSkillRepository}，不内联业务 SQL；语句由方言的技能表领域通过
 * {@link BoundSql} 提供。行为移植自已弃用的技能 mysql/postgresql 仓库。
 * 表结构由构建器统一验证：缺少 {@code metadata_json} 等声明列的表会在检查阶段失败，并附参考 DDL。
 *
 * <p>技能按命名空间划分（{@code UNIQUE(namespace, name)}），实现每个
 * {@link AgentSkillRepository} 的作用域隔离契约：实例在构造时绑定一个命名空间，
 * 无参数方法只针对该作用域。感知命名空间的方法是此类额外提供的便利能力，
 * 用于需要通过同一实例访问多个命名空间的工具。
 *
 * <p>命名空间是此仓库每项操作的首要维度：每个方法先解析到恰好一个明确命名空间，
 * 包括 {@code clearAllSkills()}，它仅清空绑定命名空间，绝不清空其他命名空间。
 * 跨命名空间清空有意不属于仓库操作。
 *
 * <p>技能命名空间只写一次：这里的写入路径都是插入或删除，
 * 没有语句更新 {@code namespace} 列，因此技能不会通过此类迁移命名空间。
 *
 * <p>示例：
 * <pre>{@code
 * AbstractJdbcDialect dialect = AbstractJdbcDialect.from(dataSource)
 *         .enableSkillTables(true)
 *         .build();
 * AgentSkillRepository repo = new JdbcAgentSkillRepository(dataSource, dialect);
 * repo.save(List.of(skill), false);
 * }</pre>
 *
 * @author shanhongyu
 */
public class JdbcAgentSkillRepository implements AgentSkillRepository {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcAgentSkillRepository.class);

    /**
     * {@link #getRepositoryInfo()} 报告的仓库类型。
     */
    private static final String REPOSITORY_TYPE = "jdbc";

    /**
     * 不带命名空间的构造方法所绑定的命名空间，与 DDL 列默认值一致。
     */
    private static final String DEFAULT_NAMESPACE = "default";

    /**
     * 技能名最大长度，与旧仓库一致。
     */
    private static final int MAX_SKILL_NAME_LENGTH = 255;

    /**
     * 资源路径最大长度，与旧仓库一致。
     */
    private static final int MAX_RESOURCE_PATH_LENGTH = 500;

    /**
     * Windows 盘符前缀，例如 {@code C:}。有意也匹配 {@code C:foo} 等盘符相对形式，
     * 它们在 Windows 上可能解析到技能目录之外；代价是拒绝 {@code a:b.txt} 这样的 POSIX 文件名，
     * 但技能中不预期使用此类名称。
     */
    private static final Pattern DRIVE_LETTER_PREFIX = Pattern.compile("^[A-Za-z]:.*");

    /**
     * SQLite 的 {@code SQLITE_CONSTRAINT}；xerial 以 null SQLState 报告它。
     */
    private static final int SQLITE_CONSTRAINT = 19;

    /**
     * 完全不报告 SQLState 且未被 {@link #SQLITE_CONSTRAINT} 覆盖的驱动重复键错误码：
     * Informix 和 GBase 8s 的 {@code -239}（唯一索引重复值）及 {@code -268}（唯一约束违反）。
     * 值来自数据库文档；与 {@link #isUniqueViolation} 的其他条目不同，上游未取得这些驱动做实测验证。
     */
    private static final Set<Integer> VENDOR_UNIQUE_VIOLATION_CODES = Set.of(-239, -268);

    /**
     * 明确表示唯一约束违反的 SQLState：{@code 23505}（SQL:2003 错误码，
     * 用于 PostgreSQL 及其分支、H2、HSQLDB、Derby、DB2）。有意不包含泛化的 {@code
     * 23500}：未找到明确用它报告重复键的驱动，且泛化状态覆盖其他约束违反；
     * 这些数据库的重复键经 {@code 23000} 进入 {@link #DUPLICATE_KEY_VENDOR_CODES}。
     * 如将来出现此类驱动，应扩展其 class-23 状态的门控，而不是重新接纳泛化状态。
     */
    private static final Set<String> UNIQUE_SQL_STATES = Set.of("23505");

    /**
     * 通用完整性 SQLState {@code 23000} 下的数据库重复键错误码，
     * 因为仅凭状态无法区分重复键和其他约束违反：MySQL／MariaDB／TiDB／OceanBase 的
     * {@code 1062}、MySQL 5.5 时代的 {@code 1022}、Oracle {@code ORA-00001} = 1，
     * 以及 SQL Server {@code 2601}／{@code 2627}。
     */
    private static final Set<Integer> DUPLICATE_KEY_VENDOR_CODES =
            Set.of(1062, 1022, 1, 2601, 2627);

    /**
     * 保存技能表的数据源；此仓库绝不关闭它。
     */
    private final DataSource dataSource;

    /**
     * 提供技能表 SQL 的技能表领域接口。
     */
    private final SkillDialect skillDialect;

    /**
     * 提供资源表 SQL 的技能资源表领域接口。
     */
    private final SkillResourcesDialect skillResourcesDialect;

    /**
     * 构造时绑定的命名空间；每个无参数方法都针对它。
     */
    private final String namespace;

    /**
     * 是否允许写入，通过 {@link #setWriteable(boolean)} 切换。
     */
    private volatile boolean writeable;

    /**
     * 在已经由 {@code AbstractJdbcDialect.from(dataSource).enableSkillTables(true).build()}
     * 创建和验证的表上创建仓库；与本模块其他组件一样，构造方法不操作表结构。
     *
     * @param dataSource JDBC 数据源
     * @param dialect 提供技能表方言的已组装方言
     */
    public JdbcAgentSkillRepository(DataSource dataSource, AbstractJdbcDialect dialect) {
        this(dataSource, dialect, DEFAULT_NAMESPACE, true);
    }

    /**
     * 创建带可写标志的仓库。
     *
     * @param dataSource JDBC 数据源
     * @param dialect 提供技能表方言的已组装方言
     * @param writeable 是否允许写入
     * @throws IllegalArgumentException {@code dataSource} 或 {@code dialect} 为 null 时抛出
     * @throws IllegalStateException 构建时未启用技能表组时抛出
     */
    public JdbcAgentSkillRepository(
            DataSource dataSource, AbstractJdbcDialect dialect, boolean writeable) {
        this(dataSource, dialect, DEFAULT_NAMESPACE, writeable);
    }

    /**
     * 创建绑定单个命名空间的仓库；实例恰好针对该作用域
     * （见 {@link AgentSkillRepository} 的隔离契约）。
     * 感知命名空间的方法额外允许同一实例访问其他作用域。
     *
     * @param dataSource JDBC 数据源
     * @param dialect 提供技能表方言的已组装方言
     * @param namespace 此实例绑定的命名空间
     * @param writeable 是否允许写入
     * @throws IllegalArgumentException {@code dataSource} 或 {@code dialect} 为 null，
     *     或 {@code namespace} 无效时抛出
     * @throws IllegalStateException 构建时未启用技能表组时抛出
     */
    public JdbcAgentSkillRepository(
            DataSource dataSource,
            AbstractJdbcDialect dialect,
            String namespace,
            boolean writeable) {
        this.dataSource = requireNonNull(dataSource, "dataSource");
        requireNonNull(dialect, "dialect");
        validateNamespace(namespace);
        // 仅下方表组启用检查需要聚合方言；CRUD 通过两个表领域接口执行，
        // 类似 JdbcStore 使用 StoreDialect。
        this.skillDialect = dialect;
        this.skillResourcesDialect = dialect;
        this.namespace = namespace;
        this.writeable = writeable;
        requireSkillTablesEnabled(dialect);
        LOG.info(
                "JdbcAgentSkillRepository initialized: skills table '{}', resources table"
                        + " '{}', namespace '{}'",
                skillDialect.skillTableName(),
                skillResourcesDialect.skillResourcesTableName(),
                namespace);
    }

    /**
     * 构造时绑定的命名空间，每个无参数方法都针对它。
     *
     * @return 已绑定命名空间（不为 null）
     */
    public String getNamespace() {
        return namespace;
    }

    @Override
    public AgentSkill getSkill(String name) {
        return getSkill(namespace, name);
    }

    @Override
    public List<String> getAllSkillNames() {
        return getAllSkillNames(namespace);
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        return getAllSkills(namespace);
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        return save(namespace, skills, force);
    }

    @Override
    public boolean delete(String skillName) {
        return delete(namespace, skillName);
    }

    @Override
    public boolean skillExists(String skillName) {
        return skillExists(namespace, skillName);
    }

    public AgentSkill getSkill(String namespace, String name) {
        validateNamespace(namespace);
        validateSkillName(name);
        try (Connection conn = dataSource.getConnection()) {
            LoadedSkillRecord row =
                    query(
                            conn,
                            skillDialect.skillSelectByName(namespace, name),
                            rs -> {
                                if (!rs.next()) {
                                    throw new IllegalArgumentException(
                                            "Skill not found in namespace '"
                                                    + namespace
                                                    + "': "
                                                    + name);
                                }
                                return LoadedSkillRecord.fromResultSet(rs);
                            });
            Map<String, String> resources = loadResourcesBySkillId(conn, row.id);
            return buildSkill(
                    name,
                    row.description,
                    row.skillContent,
                    row.source,
                    row.metadataJson,
                    resources);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load skill: " + name, e);
        }
    }

    /**
     * 列出已存名称，跳过不满足与 {@link #buildSkill} 相同读取侧验证的行，并记录警告。
     * 此类名称本来就无法加载或删除，因为 {@code getSkill} 和 {@code delete} 会先验证。
     */
    public List<String> getAllSkillNames(String namespace) {
        validateNamespace(namespace);
        try (Connection conn = dataSource.getConnection()) {
            List<String> stored = new ArrayList<>();
            forEachRow(
                    conn,
                    skillDialect.skillSelectAllNames(namespace),
                    rs -> stored.add(rs.getString("name")));
            return filterUnloadableNames(stored);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list skill names", e);
        }
    }

    /**
     * 丢弃不满足读取侧验证的名称，逐名称警告并汇总警告；
     * 必须让调用方看得出列表只是部分结果。
     */
    private static List<String> filterUnloadableNames(List<String> stored) {
        List<String> names = new ArrayList<>(stored.size());
        for (String name : stored) {
            try {
                validateSkillName(name);
            } catch (IllegalArgumentException e) {
                LOG.warn("Skipping unloadable skill name '{}': {}", name, e.getMessage());
                continue;
            }
            names.add(name);
        }
        if (names.size() < stored.size()) {
            LOG.warn(
                    "Skill name listing is partial: {} names omitted",
                    stored.size() - names.size());
        }
        return names;
    }

    /**
     * 加载某命名空间全部目录及每个技能的完整资源，峰值内存为该命名空间资源字节量的 O(n)。
     * 在另行跟踪的流式变体实现前，大型目录应通过 {@link #getSkill(String, String)} 逐个访问。
     */
    public List<AgentSkill> getAllSkills(String namespace) {
        validateNamespace(namespace);
        try (Connection conn = dataSource.getConnection()) {
            Map<Long, LoadedSkillRecord> records = loadSkillRecords(conn, namespace);
            stitchResources(conn, namespace, records);
            return buildAll(records);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load all skills", e);
        }
    }

    /**
     * 按名称排序加载某命名空间的技能行。
     * 随后单独附加资源时，用 {@link LinkedHashMap} 保持该顺序。
     */
    private Map<Long, LoadedSkillRecord> loadSkillRecords(Connection conn, String namespace)
            throws SQLException {
        Map<Long, LoadedSkillRecord> records = new LinkedHashMap<>();
        forEachRow(
                conn,
                skillDialect.skillSelectAll(namespace),
                rs -> {
                    LoadedSkillRecord record = LoadedSkillRecord.fromResultSet(rs);
                    records.put(record.id, record);
                });
        return records;
    }

    /**
     * 将命名空间的资源行附加到各技能；孤儿行记日志并跳过。
     */
    private void stitchResources(
            Connection conn, String namespace, Map<Long, LoadedSkillRecord> records)
            throws SQLException {
        forEachRow(
                conn,
                skillResourcesDialect.skillResourcesSelectAll(namespace),
                rs -> {
                    long skillId = rs.getLong("id");
                    LoadedSkillRecord record = records.get(skillId);
                    if (record != null) {
                        record.resources.put(
                                rs.getString("resource_path"), rs.getString("resource_content"));
                    } else {
                        LOG.warn("Found orphaned resource for non-existent id: {}", skillId);
                    }
                });
    }

    /**
     * 构建技能，跳过模型拒绝的行并记录警告。
     */
    private List<AgentSkill> buildAll(Map<Long, LoadedSkillRecord> records) {
        List<AgentSkill> skills = new ArrayList<>(records.size());
        int omitted = 0;
        for (LoadedSkillRecord record : records.values()) {
            try {
                skills.add(
                        buildSkill(
                                record.name,
                                record.description,
                                record.skillContent,
                                record.source,
                                record.metadataJson,
                                record.resources));
            } catch (Exception e) {
                omitted++;
                LOG.warn("Failed to build skill '{}': {}", record.name, e.getMessage(), e);
            }
        }
        if (omitted > 0) {
            LOG.warn(
                    "Skill catalog listing is partial: {} of {} skills omitted",
                    omitted,
                    records.size());
        }
        return skills;
    }

    public boolean save(String namespace, List<AgentSkill> skills, boolean force) {
        validateNamespace(namespace);
        if (skills == null || skills.isEmpty()) {
            return false;
        }
        if (!writeable) {
            LOG.warn("Cannot save skills: repository is read-only");
            return false;
        }

        try (Connection conn = dataSource.getConnection()) {
            validateForSave(skills);
            if (!force) {
                requireNoConflicts(conn, namespace, skills);
            }

            return runInTransaction(
                    conn,
                    c -> {
                        for (AgentSkill skill : skills) {
                            // force=false 时有意跳过前置删除：若并发保存
                            // 在预检查和本次插入之间写入同名技能，
                            // 必须由 UNIQUE 约束使插入失败，
                            // 而不能静默覆盖。
                            if (force && skillExistsInternal(c, namespace, skill.getName())) {
                                deleteSkillInternal(c, namespace, skill.getName());
                                LOG.debug(
                                        "Deleted existing skill for overwrite: {}",
                                        skill.getName());
                            }
                            long skillId = insertSkill(c, namespace, skill, force);
                            insertResources(c, namespace, skillId, skill.getResources());
                            LOG.info(
                                    "Successfully saved skill: {} (id={})",
                                    skill.getName(),
                                    skillId);
                        }
                        return true;
                    });
        } catch (SQLException e) {
            LOG.error("Failed to save skills", e);
            throw new RuntimeException("Failed to save skills", e);
        }
    }

    public boolean delete(String namespace, String skillName) {
        validateNamespace(namespace);
        if (!writeable) {
            LOG.warn("Cannot delete skill: repository is read-only");
            return false;
        }
        validateSkillName(skillName);

        try (Connection conn = dataSource.getConnection()) {
            if (!skillExistsInternal(conn, namespace, skillName)) {
                LOG.warn("Skill does not exist: {}", skillName);
                return false;
            }
            runInTransaction(
                    conn,
                    c -> {
                        deleteSkillInternal(c, namespace, skillName);
                        return true;
                    });
            LOG.info("Successfully deleted skill: {}", skillName);
            return true;
        } catch (SQLException e) {
            LOG.error("Failed to delete skill: {}", skillName, e);
            throw new RuntimeException("Failed to delete skill: " + skillName, e);
        }
    }

    public boolean skillExists(String namespace, String skillName) {
        validateNamespace(namespace);
        if (skillName == null || skillName.isEmpty()) {
            return false;
        }
        try (Connection conn = dataSource.getConnection()) {
            return skillExistsInternal(conn, namespace, skillName);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check skill existence: " + skillName, e);
        }
    }

    /**
     * 报告类型、位置及可写性；位置携带绑定命名空间
     * （{@code <skill table>@<namespace>}），使同表不同作用域可区分。
     */
    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo(
                REPOSITORY_TYPE, skillDialect.skillTableName() + "@" + namespace, writeable);
    }

    /**
     * 仓库身份为 {@code jdbc_<skill table>@<namespace>}，绑定命名空间是其一部分；
     * 形式遵循 Nacos 仓库的 {@code nacos@<namespaceId>} 约定。
     * 同表两个作用域不能共享身份：MarketplaceStager 将来源解析为文件系统路径段
     * （{@code .skills-cache/<source>/} 子树），其路径穿越安全依赖于构造时命名空间验证
     * 拒绝路径分隔符及 {@code .}／{@code ..}。
     */
    @Override
    public String getSource() {
        return REPOSITORY_TYPE + "_" + skillDialect.skillTableName() + "@" + namespace;
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
        // DataSource 由外部管理，这里无需释放。
        LOG.debug("JdbcAgentSkillRepository closed");
    }

    /**
     * 删除构造时绑定命名空间中的全部技能及资源。
     *
     * @return 删除的技能行数
     */
    public int clearAllSkills() {
        return clearAllSkills(getNamespace());
    }

    /**
     * 删除指定命名空间中的全部技能及资源，不影响其他命名空间。
     *
     * @return 删除的技能行数
     */
    public int clearAllSkills(String namespace) {
        validateNamespace(namespace);
        if (!writeable) {
            LOG.warn("Cannot clear skills: repository is read-only");
            return 0;
        }
        try (Connection conn = dataSource.getConnection()) {
            int deleted =
                    runInTransaction(
                            conn,
                            c -> {
                                // 两条带作用域的语句；资源表携带所属技能的
                                // 命名空间，因此无需连接查询。
                                executeUpdate(
                                        c,
                                        skillResourcesDialect.skillResourcesDeleteAll(namespace));
                                return executeUpdate(c, skillDialect.skillDeleteAll(namespace));
                            });
            LOG.info("Cleared namespace '{}': {} skills deleted", namespace, deleted);
            return deleted;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear skills", e);
        }
    }

    // ------------------------------------------------------------------
    // 配置检查
    // ------------------------------------------------------------------

    /**
     * 对启用基础表但未启用技能表的配置立即报错：禁用技能组的方言没有处理技能表结构，
     * 若不检查，技能路径会在首次使用时失败。
     *
     * @throws IllegalStateException 构建时未启用技能表组时抛出
     */
    private static void requireSkillTablesEnabled(AbstractJdbcDialect dialect) {
        if (!dialect.isSkillTablesEnabled()) {
            throw new IllegalStateException(
                    "The skill tables group ("
                            + dialect.skillTableName()
                            + " / "
                            + dialect.skillResourcesTableName()
                            + ") is not enabled on this dialect. Enable it with"
                            + " enableSkillTables(true) on AbstractJdbcDialect.from(dataSource)"
                            + " before building the dialect.");
        }
    }

    // ------------------------------------------------------------------
    // 保存路径辅助方法
    // ------------------------------------------------------------------

    /**
     * 在任何事务开始前验证全部技能名、资源路径及资源内容。
     * 其他 NOT NULL 列无需检查：{@code AgentSkill} 构造方法保证它们非 null，
     * 资源值是唯一可能把 null 传给驱动的字段。
     *
     * @throws IllegalArgumentException 技能为 null、名称／资源路径无效，或资源内容为 null 时抛出
     *     （所有数据库 DDL 中该列均为 NOT NULL）
     */
    private static void validateForSave(List<AgentSkill> skills) {
        for (AgentSkill skill : skills) {
            if (skill == null) {
                throw new IllegalArgumentException("Skills to save must not contain null elements");
            }
            validateSkillName(skill.getName());
            Map<String, String> resources = skill.getResources();
            if (resources != null) {
                for (Map.Entry<String, String> entry : resources.entrySet()) {
                    validateResourcePath(entry.getKey());
                    if (entry.getValue() == null) {
                        throw new IllegalArgumentException(
                                "Resource content must not be null: " + entry.getKey());
                    }
                }
            }
        }
    }

    /**
     * 在 {@code force=false} 时执行快速失败预检查，在任何写入前列出全部冲突名称。
     * 它只是提前提示：最终保证是事务内插入上的 {@code (namespace, name)} UNIQUE 约束
     * （见 {@link #insertSkill}），由它封住预检查与保存之间的并发写入窗口。
     *
     * @throws IllegalStateException 列出所有冲突技能名
     */
    private void requireNoConflicts(Connection conn, String namespace, List<AgentSkill> skills)
            throws SQLException {
        List<String> existing = new ArrayList<>();
        for (AgentSkill skill : skills) {
            if (skillExistsInternal(conn, namespace, skill.getName())) {
                existing.add(skill.getName());
            }
        }
        if (!existing.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot save skills: the following skills already exist and force=false: "
                            + String.join(", ", existing)
                            + ". Use force=true to overwrite existing skills.");
        }
    }

    /**
     * 插入一个技能行并返回生成的 ID。{@code force=false} 时不做前置删除，
     * 因此 {@code (namespace, name)} UNIQUE 约束是权威冲突检查：
     * 预检查与本次插入之间出现的同名记录，产生与预检查相同的 {@link IllegalStateException}，
     * 而不是被静默覆盖。
     *
     * @throws SQLException 插入或获取键失败时抛出
     * @throws IllegalStateException {@code force=false} 且名称已存在时抛出
     */
    private long insertSkill(Connection conn, String namespace, AgentSkill skill, boolean force)
            throws SQLException {
        BoundSql bound =
                skillDialect.skillInsert(
                        namespace,
                        skill.getName(),
                        skill.getDescription(),
                        skill.getSkillContent(),
                        skill.getSource(),
                        serializeMetadata(skill.getMetadata()));
        try (PreparedStatement stmt =
                conn.prepareStatement(bound.sql(), Statement.RETURN_GENERATED_KEYS)) {
            bindParams(stmt, bound.params());
            stmt.executeUpdate();
            try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    return generatedKeys.getLong(1);
                }
                throw new SQLException("Failed to get generated id for skill: " + skill.getName());
            }
        } catch (SQLException e) {
            if (!force && isUniqueViolation(e)) {
                throw new IllegalStateException(
                        "Cannot save skills: the following skills already exist and"
                                + " force=false: "
                                + skill.getName()
                                + ". Use force=true to overwrite existing skills.",
                        e);
            }
            throw e;
        }
    }

    /**
     * 在一次往返中批量插入技能资源。每行由方言以 {@link BoundSql} 提供，
     * 语句与参数一起传递；此类不承担位置占位符契约。
     * 全部行共享同一语句结构，用第一行准备预编译语句。
     *
     * @throws SQLException 任一行插入失败时抛出
     */
    private void insertResources(
            Connection conn, String namespace, long skillId, Map<String, String> resources)
            throws SQLException {
        if (resources == null || resources.isEmpty()) {
            LOG.debug("No resources to insert for id: {}", skillId);
            return;
        }
        List<String> paths = new ArrayList<>(resources.keySet());
        String firstPath = paths.get(0);
        String insertSql =
                skillResourcesDialect
                        .skillResourcesInsert(
                                namespace, skillId, firstPath, resources.get(firstPath))
                        .sql();
        try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
            for (String path : paths) {
                BoundSql row =
                        skillResourcesDialect.skillResourcesInsert(
                                namespace, skillId, path, resources.get(path));
                bindParams(stmt, row.params());
                stmt.addBatch();
            }
            int[] results = stmt.executeBatch();
            int insertedCount = 0;
            for (int i = 0; i < results.length; i++) {
                if (results[i] > 0 || results[i] == Statement.SUCCESS_NO_INFO) {
                    insertedCount++;
                } else {
                    // 符合规范的驱动会为每条命令返回一个结果；这里的检查防止
                    // 异常驱动使日志语句触发 IndexOutOfBounds。
                    String path = i < paths.size() ? paths.get(i) : "<unknown>";
                    LOG.error(
                            "Failed to insert resource '{}' for id '{}': batch result {}",
                            path,
                            skillId,
                            results[i]);
                }
            }
            if (insertedCount != resources.size()) {
                throw new SQLException(
                        "Failed to insert all resources for id '"
                                + skillId
                                + "'. Expected: "
                                + resources.size()
                                + ", Inserted: "
                                + insertedCount);
            }
        }
    }

    // ------------------------------------------------------------------
    // 删除路径辅助方法
    // ------------------------------------------------------------------

    /**
     * 在一个命名空间内删除技能及其资源。为可移植性显式删除资源：
     * SQLite 仅在启用 {@code PRAGMA foreign_keys} 时执行级联外键，
     * 因此级联保留为第二道防线。
     */
    private void deleteSkillInternal(Connection conn, String namespace, String skillName)
            throws SQLException {
        Long skillId =
                query(
                        conn,
                        skillDialect.skillSelectIdByName(namespace, skillName),
                        rs -> rs.next() ? rs.getLong("id") : null);
        if (skillId != null) {
            executeUpdate(conn, skillResourcesDialect.skillResourcesDeleteBySkillId(skillId));
        }
        executeUpdate(conn, skillDialect.skillDeleteByName(namespace, skillName));
    }

    /**
     * 执行更新类型的 {@link BoundSql}，返回受影响行数。
     */
    private int executeUpdate(Connection conn, BoundSql bound) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(bound.sql())) {
            bindParams(ps, bound.params());
            return ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    // 读取路径辅助方法
    // ------------------------------------------------------------------

    /**
     * 按路径索引加载一个技能的资源。
     */
    private Map<String, String> loadResourcesBySkillId(Connection conn, long skillId)
            throws SQLException {
        Map<String, String> resources = new HashMap<>();
        forEachRow(
                conn,
                skillResourcesDialect.skillResourcesSelectBySkillId(skillId),
                rs ->
                        resources.put(
                                rs.getString("resource_path"), rs.getString("resource_content")));
        return resources;
    }

    /**
     * 在已打开连接上探测存在性。
     */
    private boolean skillExistsInternal(Connection conn, String namespace, String skillName)
            throws SQLException {
        return query(conn, skillDialect.skillExists(namespace, skillName), ResultSet::next);
    }

    /**
     * 从行数据构建 {@link AgentSkill}，有完整元数据时恢复它，否则回退到旧核心列。
     *
     * <p>读取时再次验证：旧表写入时未检查路径，而使用方会将这些值解析到磁盘。
     * {@code getSkill} 直接暴露拒绝；{@code getAllSkills} 跳过该行，
     * {@code getAllSkillNames} 省略该名称，后二者均记录警告，与不可构建行的处理一致。
     *
     * @throws IllegalArgumentException 行名称或资源路径不满足与保存路径相同的验证时抛出
     */
    private AgentSkill buildSkill(
            String name,
            String description,
            String skillContent,
            String source,
            String metadataJson,
            Map<String, String> resources) {
        validateSkillName(name);
        if (resources != null) {
            for (String path : resources.keySet()) {
                validateResourcePath(path);
            }
        }
        return new AgentSkill(
                deserializeMetadata(metadataJson, name, description),
                skillContent,
                resources,
                source);
    }

    /**
     * 存在 {@code metadata_json} 时先反序列化，再使用权威 SQL 列覆盖名称和描述。
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
                LOG.warn(
                        "Failed to deserialize metadata_json for skill '{}', falling back to"
                                + " core metadata",
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

    // ------------------------------------------------------------------
    // 验证与 JDBC 工具方法
    // ------------------------------------------------------------------

    /**
     * 验证命名空间：长度 1–64（DDL 列宽），字符属于 {@code
     * [A-Za-z0-9._-]}，排除路径相对名称 {@code .} 和 {@code ..}。
     *
     * @throws IllegalArgumentException 为 null／空、超长、等于 "."／".." 或含允许集合之外字符时抛出
     */
    private static void validateNamespace(String namespace) {
        if (namespace == null || namespace.isEmpty()) {
            throw new IllegalArgumentException("Namespace cannot be null or empty");
        }
        if (".".equals(namespace) || "..".equals(namespace)) {
            throw new IllegalArgumentException("Namespace must not be '.' or '..': " + namespace);
        }
        if (namespace.length() > 64) {
            throw new IllegalArgumentException("Namespace cannot exceed 64 characters");
        }
        for (int i = 0; i < namespace.length(); i++) {
            if (!isNamespaceCharacter(namespace.charAt(i))) {
                throw new IllegalArgumentException(
                        "Namespace contains illegal character '"
                                + namespace.charAt(i)
                                + "'; allowed charset is [A-Za-z0-9._-]: "
                                + namespace);
            }
        }
    }

    private static boolean isNamespaceCharacter(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '.'
                || c == '_'
                || c == '-';
    }

    /**
     * 验证技能名称。
     *
     * @throws IllegalArgumentException 为 null／空、超长，或含路径分隔符／穿越序列时抛出
     */
    private static void validateSkillName(String skillName) {
        if (skillName == null || skillName.trim().isEmpty()) {
            throw new IllegalArgumentException("Skill name cannot be null or empty");
        }
        if (skillName.length() > MAX_SKILL_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Skill name cannot exceed " + MAX_SKILL_NAME_LENGTH + " characters");
        }
        if (skillName.contains("..") || skillName.contains("/") || skillName.contains("\\")) {
            throw new IllegalArgumentException("Skill name cannot contain path separators or '..'");
        }
    }

    /**
     * 验证资源路径。除旧的 null／空／长度检查外，还拒绝绝对路径和 {@code ..} 片段：
     * 技能内容可能来自不可信来源，路径之后会解析到磁盘，此类路径会逃出技能目录。
     * {@code docs/readme.md} 等普通相对子路径仍有效。
     *
     * @throws IllegalArgumentException 为 null／空、超长、绝对路径或存在路径穿越时抛出
     */
    private static void validateResourcePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Resource path cannot be null or empty");
        }
        if (path.length() > MAX_RESOURCE_PATH_LENGTH) {
            throw new IllegalArgumentException(
                    "Resource path cannot exceed " + MAX_RESOURCE_PATH_LENGTH + " characters");
        }
        if (isAbsoluteOrTraversing(path)) {
            throw new IllegalArgumentException(
                    "Resource path must be relative and must not contain '..': " + path);
        }
    }

    /**
     * 路径是否为绝对路径（POSIX、Windows 盘符或 UNC），或包含 {@code ..} 片段。
     */
    private static boolean isAbsoluteOrTraversing(String path) {
        if (path.startsWith("/")
                || path.startsWith("\\")
                || DRIVE_LETTER_PREFIX.matcher(path).matches()) {
            return true;
        }
        for (String segment : path.split("[/\\\\]")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 通过共用的准备／绑定／执行流程运行查询；处理器持有游标，
     * 使单行读取和全量扫描共用一条代码路径。
     */
    private static <T> T query(Connection conn, BoundSql bound, ResultSetProcessor<T> processor)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(bound.sql())) {
            bindParams(ps, bound.params());
            try (ResultSet rs = ps.executeQuery()) {
                return processor.process(rs);
            }
        }
    }

    /**
     * 通过 {@link #query} 将查询的每一行交给 {@code handler}。
     */
    private static void forEachRow(Connection conn, BoundSql bound, RowHandler handler)
            throws SQLException {
        query(
                conn,
                bound,
                rs -> {
                    while (rs.next()) {
                        handler.handle(rs);
                    }
                    return null;
                });
    }

    /**
     * 在单个事务中运行 {@code work}：保存并恢复自动提交模式，
     * 成功时提交，捕获到 SQLException 或 RuntimeException 时先回滚，再重新抛出原异常。
     */
    private static <T> T runInTransaction(Connection conn, TransactionalWork<T> work)
            throws SQLException {
        boolean originalAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            T result = work.execute(conn);
            conn.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly(conn, e);
            throw e;
        } finally {
            restoreAutoCommit(conn, originalAutoCommit);
        }
    }

    /**
     * 按占位符顺序绑定 {@link BoundSql} 参数。
     */
    private static void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    /**
     * 异常是否报告重复键／唯一约束违反，即 {@code force=false} 插入的权威冲突信号。
     * 只匹配明确重复信号，其他约束错误保留驱动自己的 {@link SQLException}，
     * 不提供可能造成破坏的“使用 force=true”建议。
     *
     * <p>按以下顺序判断信号：
     *
     * <ol>
     *   <li>SQLState 属于 {@link #UNIQUE_SQL_STATES}，即可移植的唯一约束违反码。
     *       上游所针对的 pgjdbc 及其分支不抛 JDBC 4 子类型（参见其问题 #963），
     *       Firebird、SQL Server、达梦和 H2 1.x 的相关路径也按状态判断；
     *       H2 2.x、HSQLDB、Derby 和 DB2 的子类型同样可携带 23505。
     *       pgjdbc 返回错误码 0，因此错误码不能替代状态。
     *   <li>SQLState {@code 23000} 加 {@link #DUPLICATE_KEY_VENDOR_CODES} 中的错误码；
     *       MySQL 系、Oracle 和 SQL Server 使用此泛化完整性状态，须由专用错误码进一步决定。
     *   <li>SQLite（xerial）：null 状态下错误 19 汇总全部 {@code SQLITE_CONSTRAINT_*}，
     *       由 {@link #isSqliteUniqueViolation} 缩小范围。
     *   <li>不报告 SQLState 的驱动使用专用码 {@link #VENDOR_UNIQUE_VIOLATION_CODES}。
     * </ol>
     *
     * <p>有意不单凭 {@link SQLIntegrityConstraintViolationException} 子类型匹配：
     * MySQL Connector/J 对 NOT NULL（1048）和外键（1451／1452）违反也抛出它，
     * 这些正是本方法要避免的误报；class-23 同类状态 23502、23503、23514 也不匹配。
     * 无法识别的重复错误以普通 {@code SQLException} 使保存失败，而不误报冲突。
     *
     * <p>同样不匹配 MySQL {@code ER_DUP_ENTRY_AUTOINCREMENT_CASE}（1569）：
     * 它报告 class 23 之外的 SQLState {@code HY000}，但需要显式自增值，技能插入从不提供。
     * ClickHouse 不强制主键唯一性，因此没有这里可匹配的重复键错误。
     *
     * @param e 技能插入抛出的异常
     * @return 失败是重复键冲突而非其他错误时为 true
     */
    static boolean isUniqueViolation(SQLException e) {
        String state = e.getSQLState();
        // SQLState 确实可能为 null（SQLite 就如此报告），而 Set.of 不接受 null 查询。
        if (state != null && UNIQUE_SQL_STATES.contains(state)) {
            return true;
        }
        if ("23000".equals(state) && DUPLICATE_KEY_VENDOR_CODES.contains(e.getErrorCode())) {
            return true;
        }
        if (e.getErrorCode() == SQLITE_CONSTRAINT
                && e.getClass().getName().startsWith("org.sqlite.")) {
            return isSqliteUniqueViolation(e);
        }
        return VENDOR_UNIQUE_VIOLATION_CODES.contains(e.getErrorCode());
    }

    /**
     * 通过驱动的 {@code getResultCode()}，将 SQLite 泛化错误 19 缩小为唯一约束违反。
     * 使用反射避免把驱动放入编译类路径。比较枚举 {@code name()} 而非 {@code toString()}，
     * 后者渲染完整消息，不会等于裸常量（sqlite-jdbc 3.47.1.0）。反射失败时回退到泛化信号。
     */
    private static boolean isSqliteUniqueViolation(SQLException e) {
        try {
            Object resultCode = e.getClass().getMethod("getResultCode").invoke(e);
            return resultCode instanceof Enum<?>
                    && "SQLITE_CONSTRAINT_UNIQUE".equals(((Enum<?>) resultCode).name());
        } catch (ReflectiveOperationException reflectionFailure) {
            return true;
        }
    }

    /**
     * 执行回滚；回滚失败以 suppressed 附加，避免遮盖触发回滚的原始错误。
     */
    private static void rollbackQuietly(Connection conn, Exception failure) {
        try {
            conn.rollback();
        } catch (SQLException rollbackException) {
            failure.addSuppressed(rollbackException);
        }
    }

    /**
     * 将连接自动提交模式恢复为事务前状态；吞掉恢复失败，避免遮盖原始错误。
     */
    private static void restoreAutoCommit(Connection conn, boolean originalAutoCommit) {
        try {
            if (conn.getAutoCommit() != originalAutoCommit) {
                conn.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException e) {
            LOG.warn("Failed to restore auto-commit mode on connection", e);
        }
    }

    /**
     * 拒绝 null 参数，消息中携带参数名称。
     */
    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    /**
     * 持有游标的结果集回调，供 {@code query} 的单行或全量读取使用。
     */
    @FunctionalInterface
    private interface ResultSetProcessor<T> {
        T process(ResultSet rs) throws SQLException;
    }

    /**
     * {@code forEachRow} 的逐行回调。
     */
    @FunctionalInterface
    private interface RowHandler {
        void handle(ResultSet rs) throws SQLException;
    }

    /**
     * {@code runInTransaction} 的事务工作单元。
     */
    @FunctionalInterface
    private interface TransactionalWork<T> {
        T execute(Connection conn) throws SQLException;
    }

    /**
     * 可变容器，用于拼接从不同结果集读取的技能与资源。
     */
    private static final class LoadedSkillRecord {

        private final long id;
        private final String name;
        private final String description;
        private final String skillContent;
        private final String source;
        private final String metadataJson;
        private final Map<String, String> resources = new HashMap<>();

        /**
         * 保存单个技能行，直到资源附加完成。
         */
        private LoadedSkillRecord(
                long id,
                String name,
                String description,
                String skillContent,
                String source,
                String metadataJson) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.skillContent = skillContent;
            this.source = source;
            this.metadataJson = metadataJson;
        }

        /**
         * 将共用技能查询列（id 到 metadata_json）映射为一条记录。
         */
        private static LoadedSkillRecord fromResultSet(ResultSet rs) throws SQLException {
            return new LoadedSkillRecord(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getString("skill_content"),
                    rs.getString("source"),
                    rs.getString("metadata_json"));
        }
    }
}
