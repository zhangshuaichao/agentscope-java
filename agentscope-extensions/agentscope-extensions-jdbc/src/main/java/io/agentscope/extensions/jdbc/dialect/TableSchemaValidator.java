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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 组装阶段的表结构验证器：比较方言 {@code CREATE TABLE} DDL 声明的列与数据库实际列。
 *
 * <p>探测语句 {@code SELECT * FROM <table> WHERE 1=0} 与数据库无关，只需 DML 权限。
 * 仅按列名比较且忽略大小写（H2 会将未引用标识符保存为大写）；
 * 额外列和类型差异不视为错误，它们由用户自己的迁移负责。
 * 预期列来自 {@link CreateTableDdlParser}；DDL 无法解析时记录 info 日志并跳过验证，
 * 因为验证是辅助保护，不是阻止扩展的强制门槛。
 *
 * <p>失败消息一次报告完整信息：表名、缺失列、实际列集合、参考 DDL，
 * 并说明框架不会修改既有表。
 *
 * @author shanhongyu
 */
public final class TableSchemaValidator {

    private static final Logger LOG = LoggerFactory.getLogger(TableSchemaValidator.class);

    /**
     * 表示“未定义表”的 SQLSTATE 值；数据库差异见 {@link #isTableMissing}。
     */
    private static final Set<String> TABLE_MISSING_SQL_STATES =
            Set.of("42S02", "42P01", "42704", "42X05", "S0002");

    /**
     * 表示“未定义表”的数据库错误码，用于 SQLSTATE 缺失或不标准的情况；
     * 数据库差异见 {@link #isTableMissing}。
     */
    private static final Set<Integer> TABLE_MISSING_ERROR_CODES =
            Set.of(1146, 942, 208, -204, -206, -5501, -2106, 42102, 42104);

    private TableSchemaValidator() {}

    /**
     * 合法 SQL 标识符模式。表名通过拼接进入 SQL（标识符无法绑定），
     * 因此它是防护边界；{@link AbstractJdbcDialectBuilder} 也使用此模式。
     */
    static final Pattern VALID_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * 在已有连接上验证一张表的列；构建器为全部已启用表复用组装连接。
     *
     * @param connection 已打开的连接；此方法不关闭它
     * @param tableName 要验证的表
     * @param createTableDdls 此表的方言 DDL 语句；第一条可解析 {@code CREATE TABLE} 提供预期列
     * @throws IllegalArgumentException {@code tableName} 不是普通标识符时抛出
     * @throws IllegalStateException 缺表或缺少预期列时抛出
     */
    public static void validate(
            Connection connection, String tableName, List<String> createTableDdls) {
        Objects.requireNonNull(connection, "connection");
        requireValidTableName(tableName);
        Objects.requireNonNull(createTableDdls, "createTableDdls");
        Set<String> expected = expectedColumns(createTableDdls);
        if (expected.isEmpty()) {
            LOG.info(
                    "Skipping schema validation for '{}': no parseable CREATE TABLE DDL —"
                            + " likely an unrecognized third-party DDL shape",
                    tableName);
            return;
        }
        Set<String> actual = actualColumns(connection, tableName, createTableDdls);

        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Table '"
                            + tableName
                            + "' is missing column(s) "
                            + missing
                            + " (actual columns: "
                            + actual
                            + "). The table already exists, so CREATE TABLE IF NOT EXISTS"
                            + " cannot add the missing columns — typically a legacy table"
                            + " adopted under the same name. The framework never alters"
                            + " existing tables; add the missing columns manually (e.g."
                            + " ALTER TABLE ... ADD COLUMN) based on the reference DDL:\n"
                            + referenceDdl(createTableDdls));
        }
    }

    /**
     * 在表名拼接到探测 SQL 之前验证它。
     *
     * @param tableName 调用方提供的表名
     * @throws IllegalArgumentException 名称为 null 或不是普通标识符时抛出
     */
    private static void requireValidTableName(String tableName) {
        if (tableName == null || !VALID_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException(
                    "tableName must match [A-Za-z_][A-Za-z0-9_]*, got: " + tableName);
        }
    }

    /**
     * 列表中第一条可解析 {@code CREATE TABLE} 的列集合。
     *
     * @param createTableDdls 此表的方言 DDL 语句
     * @return 小写预期列名；没有可解析 DDL 时为空
     */
    private static Set<String> expectedColumns(List<String> createTableDdls) {
        for (String ddl : createTableDdls) {
            Set<String> columns = CreateTableDdlParser.parseColumns(ddl);
            if (!columns.isEmpty()) {
                return columns;
            }
        }
        return Set.of();
    }

    /**
     * 从探测结果的 {@code ResultSetMetaData} 获取表的实际列，转为小写。
     *
     * @param connection 已打开的连接
     * @param tableName 要探测的表
     * @param createTableDdls 仅用于错误路径中的参考 DDL
     * @return 小写实际列名
     */
    private static Set<String> actualColumns(
            Connection connection, String tableName, List<String> createTableDdls) {
        String probe = "SELECT * FROM " + tableName + " WHERE 1=0";
        try (Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery(probe)) {
            ResultSetMetaData metaData = rs.getMetaData();
            Set<String> actual = new TreeSet<>();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                String label = metaData.getColumnLabel(i);
                if (label != null) {
                    actual.add(label.toLowerCase(Locale.ROOT));
                }
            }
            return actual;
        } catch (SQLException e) {
            if (isTableMissing(e)) {
                throw new IllegalStateException(
                        "Table '"
                                + tableName
                                + "' does not exist. The framework only creates tables"
                                + " through AbstractJdbcDialect.from(dataSource).build()"
                                + " and never alters existing schemas — create the table"
                                + " manually using this reference DDL:\n"
                                + referenceDdl(createTableDdls));
            }
            throw new IllegalStateException(
                    "Failed to read columns of table '"
                            + tableName
                            + "' during schema"
                            + " validation",
                    e);
        }
    }

    /**
     * 将 DDL 列表连接为可直接复制的参考文本。
     *
     * @param createTableDdls 此表的方言 DDL 语句
     * @return 以分号和换行连接的语句
     */
    private static String referenceDdl(List<String> createTableDdls) {
        return String.join(";\n", createTableDdls);
    }

    /**
     * 检测表不存在的错误；每行对应一个数据库（上游依据各数据库官方错误参考核对错误码）：
     *
     * <ul>
     *   <li>MySQL — SQLState 42S02，错误码 1146（ER_NO_SUCH_TABLE）</li>
     *   <li>MariaDB — MySQL 系：42S02／1146</li>
     *   <li>TiDB — 兼容 MySQL：42S02／1146</li>
     *   <li>OceanBase（MySQL 模式）— 兼容 MySQL：42S02／1146</li>
     *   <li>OceanBase（Oracle 模式）— 对应 Oracle：942</li>
     *   <li>PostgreSQL — SQLState 42P01（undefined_table）；驱动报告 errorCode 0</li>
     *   <li>KingbaseES（人大金仓）— PostgreSQL 系：42P01</li>
     *   <li>openGauss／GaussDB — PostgreSQL 系：42P01</li>
     *   <li>PolarDB（兼容 PostgreSQL）— PostgreSQL 系：42P01</li>
     *   <li>Oracle — 错误码 942（ORA-00942）；SQLState 不标准</li>
     *   <li>SQL Server — SQLState S0002，错误码 208（无效对象名）</li>
     *   <li>DB2（LUW／zOS／IBM i）— SQLState 42704，SQLCODE -204（未定义名称）</li>
     *   <li>H2 — 错误码 42104（2.x）／42102（1.x）；SQLState 不标准</li>
     *   <li>HSQLDB — 错误码 -5501（用户缺少权限或对象不存在）</li>
     *   <li>Derby／JavaDB — SQLState 42X05</li>
     *   <li>Firebird（3.0+）— SQLState 42S02</li>
     *   <li>DM（达梦）— 错误码 -2106（无效的表或视图名）</li>
     *   <li>Informix／GBase 8s — 错误码 -206（数据库中不存在指定表）</li>
     *   <li>SQLite（xerial）— SQLState 为 null，消息 {@code "no such table: <name>"}</li>
     * </ul>
     *
     * <p>有意不使用 {@code instanceof SQLSyntaxErrorException}：它覆盖整个 SQLState 42 类，
     * 包括不能误报为缺表的语法和权限错误；而 pgjdbc 本来就不抛出 JDBC 4 子类型。
     *
     * @param e 探测抛出的异常
     * @return 错误表示表不存在时为 true
     */
    private static boolean isTableMissing(SQLException e) {
        if (TABLE_MISSING_SQL_STATES.contains(e.getSQLState())) {
            return true;
        }
        if (TABLE_MISSING_ERROR_CODES.contains(e.getErrorCode())) {
            return true;
        }
        return e.getSQLState() == null
                && e.getMessage() != null
                && e.getMessage().toLowerCase(Locale.ROOT).contains("no such table");
    }

    /**
     * 从 {@code CREATE TABLE} DDL 提取列名集合。DDL 是预期列的唯一事实来源，
     * 无需手工维护可能与之漂移的列名列表。
     *
     * <p>有意保持小巧：只需要列名，几十行词法分析足以覆盖仓库内各数据库形式。
     * 在括号深度为零的逗号处分割列列表（{@code DECIMAL(10,2)} 内的逗号保持不变）；
     * 每段的首个标识符就是列名，约束关键字开头的段则表示表级约束。
     * 引用标识符（{@code "col"}、{@code `col`}、{@code [col]}）去掉定界符。
     * 无法识别为 {@code CREATE TABLE} 或有任意无法识别的段时，返回空集合并跳过验证；
     * 验证是辅助保护，不能阻止无法解析的第三方 DDL。
     *
     * <p>有意设计为嵌套类：验证器是唯一生产调用方，目前没有通用化需求；
     * 使用包级可见而非 private，以便单元测试直接调用。
     */
    static final class CreateTableDdlParser {

        /**
         * 标识表级约束而非列定义的段首关键字。
         */
        private static final Set<String> CONSTRAINT_KEYWORDS =
                Set.of(
                        "PRIMARY",
                        "FOREIGN",
                        "UNIQUE",
                        "KEY",
                        "INDEX",
                        "CONSTRAINT",
                        "CHECK",
                        "EXCLUDE",
                        "FULLTEXT",
                        "SPATIAL");

        /**
         * {@code CREATE [TEMPORARY|TEMP|GLOBAL|LOCAL|UNLOGGED] TABLE ...} 前缀。
         * {@code CREATE} 与 {@code TABLE} 之间的修饰符可选，以支持使用它们的第三方方言。
         */
        private static final Pattern CREATE_TABLE_PREFIX =
                Pattern.compile(
                        "\\s*CREATE\\s+((TEMPORARY|TEMP|GLOBAL|LOCAL|UNLOGGED)\\s+)*TABLE\\b",
                        Pattern.CASE_INSENSITIVE);

        /**
         * 未引用标识符：字母或下划线开头，后续为单词字符。
         */
        private static final Pattern LEADING_IDENTIFIER =
                Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

        private CreateTableDdlParser() {}

        /**
         * 解析 {@code CREATE TABLE} DDL 声明的列名。
         *
         * @param ddl 要解析的 DDL 语句
         * @return 小写列名；语句不是可识别的 {@code CREATE TABLE} 或无法安全解析时为空，
         *     调用方此时跳过验证
         */
        static Set<String> parseColumns(String ddl) {
            if (ddl == null || ddl.isBlank()) {
                return Set.of();
            }
            String sql = stripComments(ddl);
            if (!CREATE_TABLE_PREFIX.matcher(sql).find()) {
                return Set.of();
            }
            int open = indexOfTopLevelParen(sql);
            if (open < 0) {
                return Set.of();
            }
            int close = indexOfMatchingParen(sql, open);
            if (close < 0) {
                return Set.of();
            }

            Set<String> columns = new LinkedHashSet<>();
            for (String segment : splitTopLevelCommas(sql, open + 1, close)) {
                String trimmed = segment.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                Identifier name = leadingIdentifier(trimmed);
                if (name == null) {
                    // 无法识别某段，说明此 DDL 形式超出当前规则。
                    // 拒绝猜测，避免报告错误的列集合。
                    return Set.of();
                }
                if (name.quoted()
                        || !CONSTRAINT_KEYWORDS.contains(name.text().toUpperCase(Locale.ROOT))) {
                    columns.add(name.text().toLowerCase(Locale.ROOT));
                }
            }
            return Set.copyOf(columns);
        }

        /**
         * 段首标识符，可能是带定界符的标识符或普通单词。
         *
         * @param segment 去除首尾空白后的列或约束段
         * @return 标识符；段首不属于上述形式时为 null
         */
        private static Identifier leadingIdentifier(String segment) {
            char first = segment.charAt(0);
            if (first == '"' || first == '`' || first == '[') {
                char closing = first == '[' ? ']' : first;
                int end = segment.indexOf(closing, 1);
                if (end <= 1) {
                    return null;
                }
                return new Identifier(segment.substring(1, end), true);
            }
            Matcher matcher = LEADING_IDENTIFIER.matcher(segment);
            if (!matcher.find() || matcher.start() != 0) {
                return null;
            }
            return new Identifier(matcher.group(), false);
        }

        /**
         * 一段的首个标识符；{@code quoted} 表示带定界符。
         */
        private record Identifier(String text, boolean quoted) {}

        // ------------------------------------------------------------------
        // 扫描辅助方法：感知引号与注释
        // ------------------------------------------------------------------

        /**
         * 移除 {@code --} 行注释及 {@code /* *}\/ 块注释，保留引号内容。
         *
         * @param sql 原始 DDL
         * @return 将注释替换为空白后的 DDL
         */
        private static String stripComments(String sql) {
            StringBuilder sb = new StringBuilder(sql.length());
            int i = 0;
            char quote = 0;
            while (i < sql.length()) {
                char c = sql.charAt(i);
                if (quote != 0) {
                    sb.append(c);
                    if (c == quote) {
                        quote = 0;
                    }
                    i++;
                } else if (c == '\'' || c == '"' || c == '`') {
                    quote = c;
                    sb.append(c);
                    i++;
                } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                    while (i < sql.length() && sql.charAt(i) != '\n') {
                        i++;
                    }
                } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                    i += 2;
                    while (i + 1 < sql.length()
                            && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                        i++;
                    }
                    i = Math.min(i + 2, sql.length());
                    sb.append(' ');
                } else {
                    sb.append(c);
                    i++;
                }
            }
            return sb.toString();
        }

        /**
         * 引号和字符串字面量之外的第一个 {@code (}，即列列表起始位置。
         *
         * @param sql 已去除注释的 DDL
         * @return 左括号索引；不存在时为 -1
         */
        private static int indexOfTopLevelParen(String sql) {
            char quote = 0;
            for (int i = 0; i < sql.length(); i++) {
                char c = sql.charAt(i);
                if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    }
                } else if (c == '\'' || c == '"' || c == '`') {
                    quote = c;
                } else if (c == '(') {
                    return i;
                }
            }
            return -1;
        }

        /**
         * 与 {@code open} 位置的 {@code (} 匹配的 {@code )} 索引；未闭合时为 -1。
         *
         * @param sql 已去除注释的 DDL
         * @param open 左括号索引
         * @return 匹配的右括号索引；没有时为 -1
         */
        private static int indexOfMatchingParen(String sql, int open) {
            int depth = 0;
            char quote = 0;
            for (int i = open; i < sql.length(); i++) {
                char c = sql.charAt(i);
                if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    }
                } else if (c == '\'' || c == '"' || c == '`') {
                    quote = c;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')' && --depth == 0) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * 在引号外、括号深度为零的逗号处分割 {@code sql[from, to)}，
         * 保留 {@code DECIMAL(10,2)} 等类型内部的嵌套逗号。
         *
         * @param sql 已去除注释的 DDL
         * @param from 左括号后一个位置的索引
         * @param to 右括号索引
         * @return 按顺序排列的原始片段
         */
        private static List<String> splitTopLevelCommas(String sql, int from, int to) {
            List<String> segments = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            int depth = 0;
            char quote = 0;
            for (int i = from; i < to; i++) {
                char c = sql.charAt(i);
                if (quote != 0) {
                    current.append(c);
                    if (c == quote) {
                        quote = 0;
                    }
                } else if (c == '\'' || c == '"' || c == '`') {
                    quote = c;
                    current.append(c);
                } else if (c == '(') {
                    depth++;
                    current.append(c);
                } else if (c == ')') {
                    depth--;
                    current.append(c);
                } else if (c == ',' && depth == 0) {
                    segments.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
            segments.add(current.toString());
            return segments;
        }
    }
}
