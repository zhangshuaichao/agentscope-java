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

/**
 * JDBC 方言层：表领域接口（table）、聚合抽象类和数据库专用实现（vendor）。
 *
 * <p>此包是组装层。{@link io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect}
 * 是实现所有表领域接口及 {@link io.agentscope.extensions.jdbc.dialect.SandboxLockStrategy}
 * 契约的聚合抽象类，保存统一表前缀及逐表覆盖。
 * {@link io.agentscope.extensions.jdbc.dialect.BoundSql} 是业务 SQL 的统一返回类型
 * （SQL 加绑定参数）。{@link io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialectBuilder}
 * 负责基于 SPI 的自动检测及可选建表。
 *
 * <pre>
 * (1) dialect.table: one interface per table (abstract DDL + ANSI business SQL defaults), methods prefixed with table short name
 *
 *   +--------------------+   +------------------------+   +--------------------+
 *   | StoreDialect       |   | SessionStateDialect    |   | SnapshotDialect    |
 *   | DDL (abstract)     |   | DDL (abstract)         |   | DDL (abstract)     |
 *   | ANSI SQL (default) |   | ANSI SQL (default)     |   | ANSI SQL (default) |
 *   | base name "store"  |   | base name "sessions"   |   | base name "snaps"  |
 *   +--------------------+   +------------------------+   +--------------------+
 *   +--------------------+   +----------------------------+
 *   | SkillDialect       |   | SkillResourcesDialect      |     base table group: store/sessions/snapshots
 *   | base name "skills" |   | base name "skill_resources"|     skill table group: skills/skill_resources
 *   +--------------------+   +----------------------------+
 *            |                          |                          |
 *            v                          v                          v             &lt;- implements (aggregate implements all table-domain interfaces + SandboxLockStrategy)
 *   +--------+--------------------------+--------------------------+-----------+
 *   | AbstractJdbcDialect  aggregate abstract class                                |
 *   | Implements all table-domain interfaces + SandboxLockStrategy                |
 *   | Holds tablePrefix + per-table overrides + table-group flags; from(DataSource) returns builder |
 *   | build() detects DB -> binds DataSource -> assembles names/groups -> creates or validates tables |
 *   | createTableDdls() collects the enabled groups' DDL; tryEnter() default = table-based lock    |
 *   | Final name resolution: override > prefix + base                               |
 *   +--------+--------------------------+--------------------------+-----------+
 *            |                          |                          |
 *            v                          v                          v             &lt;- extends (one class per database — ALL differences in one file)
 *
 * (2) dialect.vendor: override only DDL + divergent SQL + lock (if native), inherit ANSI defaults
 *
 *   +----------------------+   +----------------------+   +------------------+   +------------------+
 *   | MysqlDialect         |   | PostgresDialect      |   | H2Dialect        |   | SqliteDialect    |
 *   | ON DUPLICATE KEY     |   | ON CONFLICT          |   | MERGE INTO       |   | ON CONFLICT      |
 *   | LONGTEXT/LONGBLOB    |   | TEXT/BYTEA           |   | CLOB/BLOB        |   | TEXT/BLOB        |
 *   | tryEnter: GET_LOCK   |   | tryEnter: inherited  |   | tryEnter: inherited | | tryEnter: inherited |
 *   +----------------------+   +----------------------+   +------------------+   +------------------+
 *
 * (3) Adding a database with native locks = override tryEnter() in the vendor class (no separate strategy class)
 * </pre>
 *
 * <h2>新增表</h2>
 * <ol>
 *   <li>在 {@code table} 中创建表领域接口，方法名以表短名为前缀。</li>
 *   <li>加入聚合类的 {@code implements}，并添加名称覆盖字段、final 解析器、
 *       构建器方法及 {@code createTableDdls()} 中受开关控制的语句。</li>
 *   <li>在各数据库专用类中覆盖抽象 DDL。</li>
 * </ol>
 *
 * <h2>新增表组</h2>
 * <ol>
 *   <li>对组内每张表执行“新增表”步骤。</li>
 *   <li>添加一个 {@code enableXxxTables(boolean)} 构建器开关（设置器语义，默认关闭），
 *       并用它控制 {@code createTableDdls()} 中该组的语句。</li>
 * </ol>
 *
 * <h2>新增数据库</h2>
 * <ol>
 *   <li>创建继承 {@link io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect} 的专用类。</li>
 *   <li>覆盖 DDL、存在差异的 SQL 和 {@code supports()}；
 *       仅数据库具备原生建议锁时覆盖 {@code tryEnter()}。</li>
 *   <li>在 {@code META-INF/services/io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect} 注册此类。</li>
 * </ol>
 *
 * <p>“覆盖 DDL”意味着实现<em>每个</em>表领域的抽象建表方法。
 * 框架升级新增表领域时，聚合类增加抽象方法，仓库外方言类在实现前无法编译。
 * 这是刻意设计：不能让无法创建某领域表的方言通过隐藏缺口的默认实现静默组装。
 * 仓库内专用类在同一次变更中更新，因此只有第三方方言需要此编译迁移步骤。
 *
 * <p>运行时，按旧制品编译的第三方方言不会自动重编译；只有实际调用缺失抽象方法时，
 * JVM 才抛出 {@link AbstractMethodError}。技能 DDL 的调用位于 {@code createTableDdls()}
 * 的技能组开关内，因此未迁移方言仍可服务基础表，直到有人启用技能组；
 * 此时技能 DDL 方法上的 {@code AbstractMethodError} 提示应重新编译并实现技能领域。
 */
package io.agentscope.extensions.jdbc.dialect;
