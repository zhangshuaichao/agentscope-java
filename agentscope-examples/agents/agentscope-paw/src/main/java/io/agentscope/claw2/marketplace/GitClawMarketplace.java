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
package io.agentscope.claw2.marketplace;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.GitSkillRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 Git 的市场。将克隆、拉取和文件遍历委托给 {@code agentscope-extensions-skill-git-repository} 模块的 {@link GitSkillRepository}，避免 claw 重复实现 JGit 基础逻辑。
 *
 * <p>遵循底层仓库约定：技能位于 {@code <repo>/skills/<name>/} 下；若不存在 {@code skills/} 目录，则直接使用仓库根目录。每个技能含 {@code SKILL.md} 及可选附属文件。
 */
public class GitClawMarketplace implements ClawMarketplace {

    private static final Logger logger = LoggerFactory.getLogger(GitClawMarketplace.class);

    private final String id;
    private final String remoteUrl;
    private final String branch;
    private final Path localPath;
    private final String skillsRoot;
    private final GitSkillRepository repo;

    /**
     * @param id 用户选择的稳定市场 ID
     * @param remoteUrl 上游 Git 仓库的 HTTPS 或 SSH URL
     * @param branch 可选分支（null 表示远端默认分支）
     * @param localPath 可选本地克隆目标；为 null 时底层仓库创建临时目录，并注册 JVM 关闭钩子清理
     * @param skillsRoot 仓库内包含技能目录的可选子目录；为 null 或空白时采用旧约定：存在 {@code skills/} 时使用它，否则使用仓库根目录。必须为仓库相对路径且不含 {@code ..} 段；违规由底层 {@link GitSkillRepository} 报告。
     */
    public GitClawMarketplace(
            String id, String remoteUrl, String branch, Path localPath, String skillsRoot) {
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
        this.skillsRoot = (skillsRoot == null || skillsRoot.isBlank()) ? null : skillsRoot.trim();
        // 来源字符串会写入已安装技能的 _install.meta.json；固定为市场 ID，
        // 方便用户追溯工作区中的技能来自哪个市场。
        this.repo =
                new GitSkillRepository(
                        this.remoteUrl,
                        this.branch,
                        this.localPath,
                        "git:" + id,
                        true,
                        this.skillsRoot);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String type() {
        return "git";
    }

    @Override
    // 【学习提示】这里直接返回配置的 remoteUrl，没有去除 URL 内嵌凭据；配置时应避免把秘密写入可展示 URL。
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
