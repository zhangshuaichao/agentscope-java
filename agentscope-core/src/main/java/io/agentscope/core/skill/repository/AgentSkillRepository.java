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

import io.agentscope.core.skill.AgentSkill;
import java.util.List;

/**
 * AgentSkill 持久化操作的仓库接口。
 *
 * <p>遵循仓库模式和依赖倒置原则，使文件系统、数据库、远程 API 等存储可以相互替换。
 *
 * <h2>作用域隔离：每个实现必须满足的要求</h2>
 *
 * <p>开发者应能方便地、通过构造方式为不同智能体或用户提供独立技能仓库。
 * 技能名称由人选择，不同作用域中同名是正常情况，而非冲突。
 * 每个仓库实例只定位一个作用域，所有实现必须保证：
 * <ul>
 *   <li>不同作用域的同名技能可以共存，不得施加全局唯一约束；
 *   <li>读取、覆盖（包括 {@code force}）和删除都留在实例自身作用域内，不触及其他作用域的同名技能；
 *   <li>对于实例无法定位的作用域，明确拒绝，不能静默重定向到另一个作用域。
 * </ul>
 *
 * <p>作用域的表达方式由后端原生机制决定，这里有意不统一标准。
 * 多数存储已有天然定位标识，预期用法是为每个标识构造一个仓库：
 * <ul>
 *   <li>文件类仓库（FileSystem、Classpath）：配置的基础目录或资源路径，一个路径对应一个作用域；
 *   <li>Workspace：运行上下文选定的用户目录；
 *   <li>Nacos：原生 namespace ID；
 *   <li>Git：仓库、分支或仓库内技能根目录；
 *   <li>关系数据库：{@code JdbcAgentSkillRepository} 在构造时为实例绑定命名空间，由显式的 {@code namespace} 列支持。
 * </ul>
 *
 * <p>需要同时使用多个作用域的应用，应为每个作用域注册一个仓库并组合使用
 * （例如在智能体构建器中链式调用 {@code skillRepository(...)}），而不是用单个仓库跨作用域查询。
 *
 * <p>使用示例：
 * <pre>{@code
 * AgentSkillRepository repo = new GitSkillRepository("https://github.com/org/skills.git");
 * AgentSkill skill = repo.getSkill("calculate");
 * }</pre>
 */
// 【学习提示】一个实例对应一个作用域是接口契约，不是此接口自动施加的安全机制。
// 后端实现仍需确保查询和写入都带正确命名空间，应用还需管理调用者的授权。
public interface AgentSkillRepository extends AutoCloseable {

    /**
     * 按名称获取技能。
     *
     * @param name 技能名称
     * @return 与名称匹配的技能
     * @throws IllegalArgumentException 名称或版本无效时抛出
     */
    AgentSkill getSkill(String name);

    /**
     * 列出仓库中所有可用技能 ID。
     *
     * <p>每个技能 ID 遵循 {@code name_version_source} 格式。
     *
     * @return 全部技能 ID 列表（不可为 null，可以为空）
     */
    List<String> getAllSkillNames();

    /**
     * 获取仓库中的全部技能。
     *
     * @return 全部技能列表（不可为 null，可以为空）
     */
    List<AgentSkill> getAllSkills();

    /**
     * 在仓库中保存或更新技能。
     *
     * <p>存在同名技能时更新，否则创建新技能。
     * <p>技能列表为空时返回 false。
     *
     * @param skills 要保存的技能
     * @param force 技能已存在时是否强制保存
     * @return 保存成功时为 {@code true}，否则为 {@code false}
     */
    boolean save(List<AgentSkill> skills, boolean force);

    /**
     * 按技能名称删除技能。
     *
     * @param skillName 技能名称（不可为 null）
     * @return 删除成功时为 {@code true}，未找到技能时为 {@code false}
     */
    boolean delete(String skillName);

    /**
     * 检查仓库中是否存在技能。
     *
     * @param skillName 技能名称（不可为 null）
     * @return 技能存在时为 {@code true}，否则为 {@code false}
     */
    boolean skillExists(String skillName);

    /**
     * 获取仓库元数据。
     *
     * <p>信息包括仓库类型、位置及其他元数据。
     *
     * @return 仓库信息（不可为 null）
     */
    AgentSkillRepositoryInfo getRepositoryInfo();

    /**
     * 获取仓库来源标识符。
     *
     * <p>来源遵循 {@code repositoryType_location} 格式。
     *
     * @return 来源标识符（不可为 null）
     */
    String getSource();

    /**
     * 设置此仓库的可写标记。
     *
     * @param writeable 仓库是否支持写操作
     */
    void setWriteable(boolean writeable);

    /**
     * 检查仓库是否支持写操作。
     *
     * @return 可写时为 {@code true}，否则为 {@code false}
     */
    boolean isWriteable();

    /**
     * 清理仓库使用的资源。
     *
     * <p>实现如果需要释放网络连接、文件句柄或缓存等资源，应重写此方法。
     */
    @Override
    default void close() {
        // 默认实现不执行任何操作
    }
}
