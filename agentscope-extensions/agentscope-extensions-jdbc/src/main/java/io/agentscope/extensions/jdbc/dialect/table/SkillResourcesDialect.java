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
 * 技能资源表的表领域方言接口。
 *
 * <p>每技能资源一行，以 {@code (id, resource_path)} 为键，外键指向技能表并使用
 * {@code ON DELETE CASCADE}。资源行携带所属技能的 {@code namespace}，
 * 在同一事务中写入，使批量读取和删除可按命名空间限定而无需连接技能表。
 * 与技能行相同，此列只写一次，没有语句更新它；两行的值总是一起写入，
 * 通过此接口不会出现不一致。此表必须在它引用的技能表之后创建。
 *
 * <p>方法名以 {@code skillResources} 为前缀；全部业务 SQL 为 ANSI 标准，专用实现只覆盖建表 DDL。
 *
 * @author shanhongyu
 */
public interface SkillResourcesDialect {

    /**
     * 基础表名（不带前缀）。
     */
    default String skillResourcesTableName() {
        return "skill_resources";
    }

    // ------------------------------------------------------------------
    // 抽象定义：每种数据库都必须覆盖
    // ------------------------------------------------------------------

    /**
     * 创建技能资源表的一条或多条 DDL 语句，必须幂等。
     */
    List<String> skillResourcesCreateTableDdls();

    // ------------------------------------------------------------------
    // 默认实现：ANSI 基线
    // ------------------------------------------------------------------

    /**
     * 以 {@link BoundSql} 返回单条资源 INSERT：语句与参数一起组装，
     * 列和占位符顺序由方言统一负责，调用方不再面对裸模板按位置绑定。
     * 调用方将多行加入同一预编译语句批次，使单个技能的资源在一次往返中插入；
     * 同一技能的各行必须具有相同语句结构。
     */
    default BoundSql skillResourcesInsert(
            String namespace, long skillId, String resourcePath, String resourceContent) {
        return new BoundSql(
                "INSERT INTO "
                        + skillResourcesTableName()
                        + " (namespace, id, resource_path, resource_content)"
                        + " VALUES (?, ?, ?, ?)",
                namespace,
                skillId,
                resourcePath,
                resourceContent);
    }

    /**
     * 查询某命名空间全部资源行，返回列：id、resource_path、resource_content。
     */
    default BoundSql skillResourcesSelectAll(String namespace) {
        return new BoundSql(
                "SELECT id, resource_path, resource_content FROM "
                        + skillResourcesTableName()
                        + " WHERE namespace = ?",
                namespace);
    }

    /**
     * 删除某命名空间全部资源行，与 {@link SkillDialect#skillDeleteAll(String)} 配套。
     */
    default BoundSql skillResourcesDeleteAll(String namespace) {
        return new BoundSql(
                "DELETE FROM " + skillResourcesTableName() + " WHERE namespace = ?", namespace);
    }

    /**
     * 查询一个技能的资源，返回列：resource_path、resource_content。
     */
    default BoundSql skillResourcesSelectBySkillId(long skillId) {
        return new BoundSql(
                "SELECT resource_path, resource_content FROM "
                        + skillResourcesTableName()
                        + " WHERE id = ?",
                skillId);
    }

    /**
     * 删除一个技能的资源行。
     *
     * <p>外键 {@code ON DELETE CASCADE} 也会删除资源，但 SQLite 只有在调用方逐连接启用
     * {@code PRAGMA foreign_keys} 后才执行外键约束，因此仓库先显式删除资源，
     * 再删除所属技能行；级联作为第二道防线保留。
     */
    default BoundSql skillResourcesDeleteBySkillId(long skillId) {
        return new BoundSql("DELETE FROM " + skillResourcesTableName() + " WHERE id = ?", skillId);
    }
}
