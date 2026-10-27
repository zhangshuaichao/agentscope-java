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
package io.agentscope.core.skill.repository;

/**
 * 技能仓库的元数据。
 *
 * <p>包含技能仓库的基本信息，例如类型、位置和写入能力。
 *
 * <p><b>仓库类型：</b>
 * <ul>
 *   <li>filesystem：本地文件系统存储
 *   <li>github：GitHub 仓库
 *   <li>mysql：MySQL 数据库
 *   <li>redis：Redis 缓存
 *   <li>自定义实现
 * </ul>
 *
 * <p><b>使用示例：</b>
 * <pre>{@code
 * AgentSkillRepositoryInfo info = new AgentSkillRepositoryInfo(
 *     "filesystem",
 *     "/path/to/skills",
 *     true
 * );
 * }</pre>
 */
public class AgentSkillRepositoryInfo {
    private final String type;
    private final String location;
    private final boolean writable;

    /**
     * 创建新的 AgentSkillRepositoryInfo 实例。
     *
     * @param type 仓库类型（不可为 null）
     * @param location 仓库位置（不可为 null）
     * @param writable 仓库是否支持写操作
     */
    public AgentSkillRepositoryInfo(String type, String location, boolean writable) {
        this.type = type;
        this.location = location;
        this.writable = writable;
    }

    /**
     * 获取仓库类型。
     *
     * @return 仓库类型（不可为 null）
     */
    public String getType() {
        return type;
    }

    /**
     * 获取仓库位置。
     *
     * @return 仓库位置（不可为 null）
     */
    public String getLocation() {
        return location;
    }

    /**
     * 检查仓库是否支持写操作。
     *
     * @return 可写时为 {@code true}，否则为 {@code false}
     */
    public boolean isWritable() {
        return writable;
    }

    /**
     * 返回此仓库信息的字符串表示。
     *
     * @return 格式为 "AgentSkillRepositoryInfo{type='...', location='...', writable=...}" 的字符串
     */
    @Override
    public String toString() {
        return String.format(
                "AgentSkillRepositoryInfo{type='%s', location='%s', writable=%s}",
                type, location, writable);
    }
}
