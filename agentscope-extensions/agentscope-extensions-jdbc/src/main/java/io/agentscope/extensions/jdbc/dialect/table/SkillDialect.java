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
package io.agentscope.extensions.jdbc.dialect.table;

import io.agentscope.extensions.jdbc.dialect.BoundSql;
import java.util.List;

/**
 * 技能表的表领域方言接口。
 *
 * <p>每技能一行：查找列（{@code namespace}、{@code name}）、内容、来源，
 * 以及 {@code metadata_json} 中的元数据树。结构对应已弃用的技能 mysql/postgresql 模块；
 * {@code metadata_json} 必须存在，缺少它的旧表会验证失败并给出参考 DDL。
 *
 * <p>命名空间列划分名称：{@code UNIQUE(namespace, name)} 允许相同技能名在每个命名空间各出现一次。
 * 每条语句只针对一个命名空间；该列为 {@code NOT NULL DEFAULT 'default'}，
 * 因此每行都有明确命名空间，不涉及数据库专用 NULL 比较语义。
 * 每行的该列只写一次，没有语句更新它，技能不会通过这里生成的 SQL 移动命名空间。
 *
 * <p>方法名以 {@code skill} 为前缀；全部业务 SQL 为 ANSI 标准，专用实现只覆盖建表 DDL。
 *
 * @author shanhongyu
 */
public interface SkillDialect {

    /**
     * 基础表名（不带前缀）。
     */
    default String skillTableName() {
        return "skills";
    }

    /**
     * 创建技能表的一条或多条 DDL 语句，必须幂等。
     */
    List<String> skillCreateTableDdls();

    /**
     * 按命名空间内名称查询一个技能，返回列包含 {@code metadata_json}。
     */
    default BoundSql skillSelectByName(String namespace, String name) {
        return new BoundSql(
                "SELECT id, name, description, skill_content, source, metadata_json FROM "
                        + skillTableName()
                        + " WHERE namespace = ? AND name = ?",
                namespace,
                name);
    }

    /**
     * 按名称排序查询某命名空间全部技能，返回列包含 {@code metadata_json}。
     */
    default BoundSql skillSelectAll(String namespace) {
        return new BoundSql(
                "SELECT id, name, description, skill_content, source, metadata_json FROM "
                        + skillTableName()
                        + " WHERE namespace = ? ORDER BY name",
                namespace);
    }

    /**
     * 按名称排序查询某命名空间全部技能名，返回列：name。
     */
    default BoundSql skillSelectAllNames(String namespace) {
        return new BoundSql(
                "SELECT name FROM " + skillTableName() + " WHERE namespace = ? ORDER BY name",
                namespace);
    }

    /**
     * 探测命名空间内某技能名是否存在；{@code (namespace, name)} 唯一，
     * 因此无需 {@code LIMIT}。
     */
    default BoundSql skillExists(String namespace, String skillName) {
        return new BoundSql(
                "SELECT 1 FROM " + skillTableName() + " WHERE namespace = ? AND name = ?",
                namespace,
                skillName);
    }

    /**
     * 按命名空间内名称查询一个技能 ID，返回列：id。
     */
    default BoundSql skillSelectIdByName(String namespace, String skillName) {
        return new BoundSql(
                "SELECT id FROM " + skillTableName() + " WHERE namespace = ? AND name = ?",
                namespace,
                skillName);
    }

    /**
     * 插入一行技能；调用方使用 {@link java.sql.Statement#RETURN_GENERATED_KEYS}
     * 准备语句，以读取自增 ID。
     */
    default BoundSql skillInsert(
            String namespace,
            String name,
            String description,
            String skillContent,
            String source,
            String metadataJson) {
        return new BoundSql(
                "INSERT INTO "
                        + skillTableName()
                        + " (namespace, name, description, skill_content, source, metadata_json)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                namespace,
                name,
                description,
                skillContent,
                source,
                metadataJson);
    }

    /**
     * 按命名空间内名称删除一个技能。
     */
    default BoundSql skillDeleteByName(String namespace, String skillName) {
        return new BoundSql(
                "DELETE FROM " + skillTableName() + " WHERE namespace = ? AND name = ?",
                namespace,
                skillName);
    }

    /**
     * 删除某命名空间的全部技能行。
     */
    default BoundSql skillDeleteAll(String namespace) {
        return new BoundSql("DELETE FROM " + skillTableName() + " WHERE namespace = ?", namespace);
    }
}
