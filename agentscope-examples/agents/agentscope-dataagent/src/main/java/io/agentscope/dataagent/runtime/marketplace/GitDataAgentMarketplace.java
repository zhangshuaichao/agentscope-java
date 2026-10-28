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
package io.agentscope.dataagent.runtime.marketplace;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.GitSkillRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 Git 的每用户市场。将克隆、拉取和文件遍历委托给
 * {@code agentscope-extensions-skill-git-repository} 模块的 {@link
 * GitSkillRepository}。
 * <p>约定与底层仓库一致：技能位于 {@code <repo>/skills/<name>/}
 * （不存在 {@code skills/} 目录时直接位于仓库根目录），每项包含 {@code SKILL.md} 及可选附属文件。
 * <p>生命周期：每用户实例由 {@link UserMarketplaceRegistry} 创建/关闭。
 * 克隆保存在创建时传给工厂的每用户缓存下，避免配置同一上游仓库的不同用户竞争同一工作副本。
 */
public class GitDataAgentMarketplace implements DataAgentMarketplace {

    private static final Logger logger = LoggerFactory.getLogger(GitDataAgentMarketplace.class);
    public static final String TYPE = "git";

    private final String id;
    private final String remoteUrl;
    private final String branch;
    private final Path localPath;
    private final GitSkillRepository repo;

    /**
     * @param id 用户选择的稳定市场 ID
     * @param remoteUrl 上游 Git 仓库的 HTTPS 或 SSH URL
     * @param branch 可选分支（null 表示远端默认分支）
     * @param localPath 可选本地克隆目标；为 null 时底层仓库创建临时目录，并注册 JVM 关闭 Hook 清理
     */
    public GitDataAgentMarketplace(String id, String remoteUrl, String branch, Path localPath) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new IllegalArgumentException("remoteUrl must not be blank");
        }
        this.id = id;
        this.remoteUrl = remoteUrl.trim();
        this.branch = (branch == null || branch.isBlank()) ? null : branch.trim();
        this.localPath = localPath;
        this.repo =
                new GitSkillRepository(this.remoteUrl, this.branch, this.localPath, "git:" + id);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayLocation() {
        return branch != null ? remoteUrl + " @" + branch : remoteUrl;
    }

    @Override
    public List<MarketSkillSummary> list() {
        List<AgentSkill> skills = repo.getAllSkills();
        List<MarketSkillSummary> summaries = new ArrayList<>(skills.size());
        for (AgentSkill skill : skills) {
            summaries.add(new MarketSkillSummary(skill.getName(), skill.getDescription(), null));
        }
        return summaries;
    }

    @Override
    public MarketSkillContent fetch(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        AgentSkill skill = repo.getSkill(name);
        if (skill == null) {
            return null;
        }
        return new MarketSkillContent(
                skill.getName(),
                skill.getDescription(),
                skill.getSkillContent(),
                skill.getResources());
    }

    @Override
    public void close() {
        try {
            repo.close();
        } catch (RuntimeException e) {
            logger.warn("Failed to close git marketplace {} ({})", id, remoteUrl, e);
        }
    }
}
