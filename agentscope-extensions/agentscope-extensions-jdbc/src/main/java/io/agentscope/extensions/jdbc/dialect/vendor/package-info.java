/**
 * 数据库专用方言实现，每种数据库一个类。
 *
 * <p>每个类继承 {@link io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect}，
 * 只覆盖 SQL 与 ANSI 默认值不同的方法：建表 DDL、UPSERT 语法、表存在性检查及可选锁策略。
 * 各数据库相同的业务 SQL 继承表领域接口默认实现。
 */
package io.agentscope.extensions.jdbc.dialect.vendor;
