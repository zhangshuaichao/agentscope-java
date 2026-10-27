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
package io.agentscope.harness.agent.skill.curator;

import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * {@link SkillUsageStore} 的持久化后端。可使用工作区文件（单进程）或分布式 {@code BaseStore}（跨节点 CAS）。
 */
interface SkillUsageBackend {

    /**
     * 加载全部技能使用记录，以技能名称为键。
     */
    Map<String, SkillUsageRecord> loadAll();

    /**
     * 按技能名称读取单条记录。
     */
    Optional<SkillUsageRecord> get(String name);

    /**
     * 将 {@code mutator} 应用于 {@code name} 对应的记录；不存在时创建新的 {@link SkillUsageRecord#defaults()} 记录。
     *
     * <p>约定：
     * <ul>
     * <li>返回与输入相同的实例表示不操作，不写入。
     * <li>返回 {@code null} 表示删除已有记录。
     * <li>返回不同实例表示创建或替换记录。
     * </ul>
     */
    void mutate(String name, UnaryOperator<SkillUsageRecord> mutator);

    /**
     * 原子替换整个使用记录映射（多键存储中尽力而为）。
     */
    void replaceAll(Map<String, SkillUsageRecord> data);
}
