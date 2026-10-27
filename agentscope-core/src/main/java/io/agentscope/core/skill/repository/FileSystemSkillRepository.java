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
import io.agentscope.core.skill.util.SkillFileSystemHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于文件系统的 AgentSkillRepository 实现。
 *
 * <p>在本地目录结构中存储技能：每个技能有独立子目录，包含 SKILL.md 和可选资源文件。
 *
 * <p>目录结构：
 * <pre>{@code
 * baseDir/
 * ├── skill-name-1/
 * │   ├── SKILL.md          # Required: Entry file with YAML frontmatter
 * │   ├── references/       # Optional: Reference documentation
 * │   ├── examples/         # Optional: Example files
 * │   └── scripts/          # Optional: Script files
 * └── skill-name-2/
 *     └── SKILL.md
 * }</pre>
 *
 * <p>使用示例：
 * <pre>{@code
 * Path baseDir = Paths.get("/path/to/skills");
 * FileSystemSkillRepository repo = new FileSystemSkillRepository(baseDir);
 * AgentSkill skill = repo.getSkill("my-skill");
 * }</pre>
 */
public class FileSystemSkillRepository implements AgentSkillRepository {

    private static final Logger logger = LoggerFactory.getLogger(FileSystemSkillRepository.class);
    private static final String SKILL_FILE_NAME = "SKILL.md";

    private final Path baseDir;
    private final String source;
    private boolean writeable;

    /**
     * 为 {@code true} 时，{@link #getAllSkills()} 仅将 SKILL.md 读入内存；
     * 不预加载所引用的支持文件，使用者按需通过技能的 {@code originDir} 解析这些文件。
     * 默认为 {@code false}，以保留仍读取 {@code skill.getResources()} 的调用方的历史行为。
     */
    private final boolean lazy;

    /**
     * 缓存技能目录绝对路径到快照的映射，使重复调用 {@link #getAllSkills()} 时，
     * 跳过 mtime 与大小均未变化的 SKILL.md 的 {@code readString}。
     */
    private final Map<Path, Snapshot> skillCache = new ConcurrentHashMap<>();

    public FileSystemSkillRepository(Path baseDir) {
        this(baseDir, true, null, false);
    }

    /**
     * 使用指定基础目录创建 FileSystemSkillRepository。
     *
     * @param baseDir 包含技能子目录的基础目录（不可为 null）
     * @param writeable 仓库是否支持写操作
     * @throws IllegalArgumentException baseDir 为 null、不存在或不是目录时抛出
     */
    public FileSystemSkillRepository(Path baseDir, boolean writeable) {
        this(baseDir, writeable, null, false);
    }

    /**
     * 使用指定基础目录和来源创建 FileSystemSkillRepository。
     *
     * @param baseDir 包含技能子目录的基础目录（不可为 null）
     * @param writeable 仓库是否支持写操作
     * @param source 技能的自定义来源标识符（null 表示默认值）
     * @throws IllegalArgumentException baseDir 为 null、不存在或不是目录时抛出
     */
    public FileSystemSkillRepository(Path baseDir, boolean writeable, String source) {
        this(baseDir, writeable, source, false);
    }

    /**
     * 创建显式配置惰性模式的 FileSystemSkillRepository。
     *
     * @param baseDir 包含技能子目录的基础目录（不可为 null）
     * @param writeable 仓库是否支持写操作
     * @param source 技能的自定义来源标识符（null 表示默认值）
     * @param lazy 为 {@code true} 时不预加载支持文件；使用者必须通过技能的 {@code originDir}
     *      解析它们。大型技能库可配合 {@code load_skill_through_path} 磁盘回退机制使用
     * @throws IllegalArgumentException baseDir 为 null、不存在或不是目录时抛出
     */
    public FileSystemSkillRepository(Path baseDir, boolean writeable, String source, boolean lazy) {
        this.writeable = writeable;
        this.source = source;
        this.lazy = lazy;
        if (baseDir == null) {
            throw new IllegalArgumentException("Base directory cannot be null");
        }

        // 转换为绝对路径并规范化
        this.baseDir = baseDir.toAbsolutePath().normalize();

        // 验证目录存在
        if (!Files.exists(this.baseDir)) {
            throw new IllegalArgumentException("Base directory does not exist: " + this.baseDir);
        }

        // 验证确实为目录
        if (!Files.isDirectory(this.baseDir)) {
            throw new IllegalArgumentException(
                    "Base directory is not a directory: " + this.baseDir);
        }

        logger.info(
                "FileSystemSkillRepository initialized [baseDir={}, lazy={}]", this.baseDir, lazy);
    }

    /**
     * 此仓库是否仅读取 SKILL.md，并推迟加载支持文件。
     */
    public boolean isLazy() {
        return lazy;
    }

    @Override
    public AgentSkill getSkill(String name) {
        // 单技能查询很少出现在热点路径，因此直接调用相同缓存路径中的
        // getAllSkills()，再选择匹配项。这让 mtime 失效处理逻辑
        // 集中在同一处。
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (AgentSkill skill : getAllSkills()) {
            if (name.equals(skill.getName())) {
                return skill;
            }
        }
        // 回退路径：技能刚写入但缓存尚未发现它时，
        // 直接读取。
        // 【学习提示】此回退调用三参数 loadSkill，最终走预加载资源的重载，
        // 与上面的 getAllSkills() 惰性路径不同；不能从旧注释推断它会沿用 lazy。
        return SkillFileSystemHelper.loadSkill(baseDir, name, getSource());
    }

    @Override
    public List<String> getAllSkillNames() {
        return SkillFileSystemHelper.getAllSkillNames(baseDir);
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        List<AgentSkill> out = new ArrayList<>();
        Set<Path> seenDirs = new HashSet<>();
        try (Stream<Path> subdirs = Files.list(baseDir)) {
            for (Path dir : (Iterable<Path>) subdirs::iterator) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                Path skillFile = dir.resolve(SKILL_FILE_NAME);
                if (!Files.exists(skillFile)) {
                    continue;
                }
                Path key = dir.toAbsolutePath().normalize();
                seenDirs.add(key);
                try {
                    BasicFileAttributes attrs =
                            Files.readAttributes(skillFile, BasicFileAttributes.class);
                    long mtime = attrs.lastModifiedTime().toMillis();
                    long size = attrs.size();
                    Snapshot cached = skillCache.get(key);
                    // 【学习提示】缓存用修改时间和大小判断是否复用，并不比较完整文件内容。
                    // 阅读性能优化时，需区分“低成本变化线索”与“内容相等的严格证明”。
                    if (cached != null && cached.mtime == mtime && cached.size == size) {
                        out.add(cached.skill);
                        continue;
                    }
                    AgentSkill skill =
                            SkillFileSystemHelper.loadSkillFromDirectory(dir, getSource(), !lazy);
                    skillCache.put(key, new Snapshot(mtime, size, skill));
                    out.add(skill);
                } catch (IOException e) {
                    logger.warn("Failed to stat SKILL.md for '{}': {}", dir, e.getMessage(), e);
                } catch (Exception e) {
                    logger.warn("Failed to load skill from '{}': {}", dir, e.getMessage(), e);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to list skill directories", e);
        }
        // 驱逐两次调用之间底层目录已消失的条目，避免缓存
        // 继续保留已删除的技能。
        skillCache.keySet().retainAll(seenDirs);
        return out;
    }

    /**
     * 缓存条目，将 SKILL.md 的 mtime／大小与解析后的 AgentSkill 配对。
     */
    private record Snapshot(long mtime, long size, AgentSkill skill) {}

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        if (skills == null || skills.isEmpty()) {
            return false;
        }

        if (!writeable) {
            logger.warn("Cannot save skills: repository is read-only");
            return false;
        }

        boolean ok = SkillFileSystemHelper.saveSkills(baseDir, skills, force);
        skillCache.clear();
        return ok;
    }

    @Override
    public boolean delete(String skillName) {
        if (!writeable) {
            logger.warn("Cannot delete skill: repository is read-only");
            return false;
        }

        boolean ok = SkillFileSystemHelper.deleteSkill(baseDir, skillName);
        skillCache.clear();
        return ok;
    }

    @Override
    public boolean skillExists(String skillName) {
        return SkillFileSystemHelper.skillExists(baseDir, skillName);
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("filesystem", baseDir.toString(), writeable);
    }

    @Override
    public String getSource() {
        return source != null ? source : "filesystem-" + buildDefaultSourceSuffix();
    }

    private String buildDefaultSourceSuffix() {
        Path fileName = baseDir.getFileName();
        Path parent = baseDir.getParent();

        if (fileName == null) {
            return "unknown";
        }

        if (parent == null || parent.getFileName() == null) {
            return fileName.toString();
        }

        return parent.getFileName() + "_" + fileName;
    }

    @Override
    public void setWriteable(boolean writeable) {
        this.writeable = writeable;
    }

    @Override
    public boolean isWriteable() {
        return writeable;
    }
}
