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
package io.agentscope.harness.agent.skill;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.repository.RuntimeContextSkillRepository;
import io.agentscope.core.skill.util.SkillUtil;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * 基于 {@link AbstractFilesystem} 的 {@link AgentSkillRepository}，通过 {@link LazyResourceCapable} 懒加载资源。取代旧的 {@code FilesystemBackedSkillRepository} 与 {@code WritableFilesystemSkillRepository} 组合：可写性现在通过构造参数配置，而不是子类。
 *
 * <p>通过在 {@code skillsRelativeDir} 下 glob 匹配 {@code SKILL.md} 发现技能。注册时仅读取 SKILL.md 文本；引用文档、脚本、素材等资源通过 {@link #resourcesFor(String, RuntimeContext)} 按需读取，在 {@code load_skill_through_path} 未命中内存映射时由 harness 技能运行时使用。
 *
 * <p>每次文件系统调用都传入显式提供的 {@link RuntimeContext}，因此透明遵循按用户命名空间隔离和沙箱路由。无上下文操作使用默认命名空间，不会从其他活动智能体调用中推断用户。
 *
 * <p>删除为非破坏性操作：技能目录移动到 {@code .archive/<name>-<ts>/} 而不是直接移除，符合 curator 的“不删除”不变量。
 */
@SuppressWarnings("deprecation")
public class WorkspaceSkillRepository
        implements RuntimeContextSkillRepository, LazyResourceCapable {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSkillRepository.class);

    private static final String SKILL_FILE = "SKILL.md";
    private static final String DEFAULT_SOURCE = "workspace";
    private static final String ARCHIVE_PREFIX = ".archive";

    private final AbstractFilesystem filesystem;
    private final String skillsRelativeDir;

    private final String source;

    private volatile boolean writable;

    /**
     * 创建只读仓库，限定在默认命名空间；除非显式提供请求上下文，无上下文操作使用 {@link RuntimeContext#empty()}。
     */
    public WorkspaceSkillRepository(AbstractFilesystem filesystem, String skillsRelativeDir) {
        this(filesystem, skillsRelativeDir, null, false);
    }

    /**
     * 创建可写仓库（与旧 {@code WritableFilesystemSkillRepository} 的默认行为一致），限定在默认命名空间。
     */
    public WorkspaceSkillRepository(
            AbstractFilesystem filesystem, String skillsRelativeDir, String source) {
        this(filesystem, skillsRelativeDir, source, true);
    }

    /**
     * 创建显式指定来源和可写性的仓库，限定在默认命名空间。
     */
    public WorkspaceSkillRepository(
            AbstractFilesystem filesystem,
            String skillsRelativeDir,
            String source,
            boolean writable) {
        this.filesystem = Objects.requireNonNull(filesystem, "filesystem");
        this.skillsRelativeDir = Objects.requireNonNull(skillsRelativeDir, "skillsRelativeDir");
        this.source = (source == null || source.isBlank()) ? DEFAULT_SOURCE : source;
        this.writable = writable;
    }

    // =========================================================================
    // 读取路径（AgentSkillRepository）
    // =========================================================================

    @Override
    public AgentSkill getSkill(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (AgentSkill skill : getAllSkills()) {
            if (name.equals(skill.getName())) {
                return skill;
            }
        }
        return null;
    }

    @Override
    public List<String> getAllSkillNames() {
        List<AgentSkill> skills = getAllSkills();
        List<String> names = new ArrayList<>(skills.size());
        for (AgentSkill skill : skills) {
            names.add(skill.getName());
        }
        return names;
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        return getAllSkills(RuntimeContext.empty());
    }

    /**
     * 使用调用方的请求级上下文列出技能。
     */
    @Override
    public List<AgentSkill> getAllSkills(RuntimeContext context) {
        RuntimeContext ctx = context != null ? context : RuntimeContext.empty();
        GlobResult glob;
        try {
            glob = filesystem.glob(ctx, SKILL_FILE, skillsRelativeDir);
        } catch (Exception e) {
            log.debug("Filesystem glob for skills failed: {}", e.getMessage());
            return Collections.emptyList();
        }
        if (!glob.isSuccess() || glob.matches() == null || glob.matches().isEmpty()) {
            return Collections.emptyList();
        }

        List<AgentSkill> skills = new ArrayList<>(glob.matches().size());
        for (FileInfo fi : glob.matches()) {
            String path = fi.path();
            if (path == null || path.isBlank()) {
                continue;
            }
            if (hasMetadataAncestor(path, skillsRelativeDir)) {
                continue;
            }
            try {
                ReadResult rr = filesystem.read(ctx, path, 0, 0);
                if (!rr.isSuccess() || rr.fileData() == null || rr.fileData().content() == null) {
                    continue;
                }
                // 有意将资源保留为 null；harness 技能运行时
                // 通过 resourcesFor(name, ctx) 懒加载资源，支持逐调用切换命名空间，
                // 无需预加载全部内容。
                skills.add(SkillUtil.createFrom(rr.fileData().content(), null, source));
            } catch (Exception e) {
                log.warn("Failed to load skill from '{}': {}", path, e.getMessage());
            }
        }
        return skills;
    }

    @Override
    public boolean skillExists(String skillName) {
        return getSkill(skillName) != null;
    }

    /**
     * {@link #skillExists(String)} 的请求上下文版本；{@code null} 使用默认命名空间。
     */
    public boolean skillExists(String skillName, RuntimeContext context) {
        return getSkill(skillName, context != null ? context : RuntimeContext.empty()) != null;
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("filesystem", skillsRelativeDir, writable);
    }

    @Override
    public String getSource() {
        return source;
    }

    @Override
    public boolean isWriteable() {
        return writable;
    }

    @Override
    public void setWriteable(boolean writeable) {
        this.writable = writeable;
    }

    // =========================================================================
    // 懒加载资源（LazyResourceCapable）
    // =========================================================================

    @Override
    public SkillResources resourcesFor(String skillName, RuntimeContext ctx) {
        if (skillName == null || skillName.isBlank()) {
            return SkillResources.empty();
        }
        RuntimeContext effectiveCtx = ctx != null ? ctx : RuntimeContext.empty();
        return new FilesystemSkillResources(filesystem, skillDirRelative(skillName), effectiveCtx);
    }

    // =========================================================================
    // 写入路径（保存/删除）
    // =========================================================================

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        return save(skills, force, null);
    }

    /**
     * {@link #save(List, boolean)} 的请求上下文版本；{@code null} 使用默认命名空间。
     */
    public boolean save(List<AgentSkill> skills, boolean force, RuntimeContext context) {
        if (!writable) {
            log.warn("WorkspaceSkillRepository is currently read-only; save() ignored");
            return false;
        }
        if (skills == null || skills.isEmpty()) {
            return false;
        }
        RuntimeContext ctx = context != null ? context : RuntimeContext.empty();
        boolean allOk = true;
        for (AgentSkill skill : skills) {
            if (skill == null || skill.getName() == null || skill.getName().isBlank()) {
                allOk = false;
                continue;
            }
            if (!force && getSkill(skill.getName(), ctx) != null) {
                log.debug("Skill '{}' already exists; skipping (force=false)", skill.getName());
                allOk = false;
                continue;
            }
            try {
                writeSkill(skill, ctx);
            } catch (Exception e) {
                log.warn("Failed to save skill '{}': {}", skill.getName(), e.getMessage());
                allOk = false;
            }
        }
        return allOk;
    }

    @Override
    public boolean delete(String skillName) {
        return delete(skillName, null);
    }

    /**
     * {@link #delete(String)} 的请求上下文版本；{@code null} 使用默认命名空间。
     */
    public boolean delete(String skillName, RuntimeContext context) {
        if (!writable) {
            log.warn("WorkspaceSkillRepository is currently read-only; delete() ignored");
            return false;
        }
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        RuntimeContext ctx = context != null ? context : RuntimeContext.empty();
        AgentSkill existing = getSkill(skillName, ctx);
        if (existing == null) {
            return false;
        }
        String src = skillDirRelative(skillName);
        String archiveDest = archiveDestRelative(skillName);
        try {
            WriteResult moveResult = filesystem.move(ctx, src, archiveDest);
            if (!moveResult.isSuccess()) {
                log.warn(
                        "Failed to archive skill '{}' from {} to {}: {}",
                        skillName,
                        src,
                        archiveDest,
                        moveResult.error());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Exception archiving skill '{}': {}", skillName, e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // 子文件操作（供 SkillManageTool 的 write_file / remove_file / patch 使用）
    // =========================================================================

    /**
     * 读取 {@code <skillsRelativeDir>/<skillName>/<relPath>} 下的单个原始文件。
     * 文件不存在或读取失败时返回 {@code null}。
     */
    public String readSkillFile(String skillName, String relPath) {
        return readSkillFile(skillName, relPath, null);
    }

    /**
     * {@link #readSkillFile(String, String)} 的请求上下文版本；{@code null} 使用默认命名空间。
     */
    public String readSkillFile(String skillName, String relPath, RuntimeContext context) {
        if (skillName == null || skillName.isBlank() || relPath == null || relPath.isBlank()) {
            return null;
        }
        String path = skillDirRelative(skillName) + "/" + relPath;
        try {
            ReadResult rr =
                    filesystem.read(context != null ? context : RuntimeContext.empty(), path, 0, 0);
            if (rr.isSuccess() && rr.fileData() != null) {
                return rr.fileData().content();
            }
        } catch (Exception e) {
            log.debug("readSkillFile({}, {}) failed: {}", skillName, relPath, e.getMessage());
        }
        return null;
    }

    /**
     * 写入或覆盖 {@code <skillsRelativeDir>/<skillName>/<relPath>} 下的单个原始文件。
     * 调用方负责校验 {@code relPath}（允许的子目录、路径穿越、大小限制）。成功时返回 {@code true}。
     */
    public boolean writeSkillFile(String skillName, String relPath, String content) {
        return writeSkillFile(skillName, relPath, content, null);
    }

    /**
     * {@link #writeSkillFile(String, String, String)} 的请求上下文版本；{@code null} 使用默认命名空间。
     */
    public boolean writeSkillFile(
            String skillName, String relPath, String content, RuntimeContext context) {
        if (!writable) {
            return false;
        }
        if (skillName == null
                || skillName.isBlank()
                || relPath == null
                || relPath.isBlank()
                || content == null) {
            return false;
        }
        String path = skillDirRelative(skillName) + "/" + relPath;
        try {
            filesystem.uploadFiles(
                    context != null ? context : RuntimeContext.empty(),
                    List.of(
                            new AbstractMap.SimpleImmutableEntry<>(
                                    path, content.getBytes(StandardCharsets.UTF_8))));
            return true;
        } catch (Exception e) {
            log.warn("writeSkillFile({}, {}) failed: {}", skillName, relPath, e.getMessage());
            return false;
        }
    }

    /**
     * 删除 {@code <skillsRelativeDir>/<skillName>/<relPath>} 下的单个原始文件。
     * 操作幂等：文件不存在也视为成功。
     */
    public boolean deleteSkillFile(String skillName, String relPath) {
        return deleteSkillFile(skillName, relPath, null);
    }

    /**
     * {@link #deleteSkillFile(String, String)} 的请求上下文版本；{@code null} 使用默认命名空间。
     */
    public boolean deleteSkillFile(String skillName, String relPath, RuntimeContext context) {
        if (!writable) {
            return false;
        }
        if (skillName == null || skillName.isBlank() || relPath == null || relPath.isBlank()) {
            return false;
        }
        String path = skillDirRelative(skillName) + "/" + relPath;
        try {
            WriteResult r =
                    filesystem.delete(context != null ? context : RuntimeContext.empty(), path);
            return r.isSuccess();
        } catch (Exception e) {
            log.warn("deleteSkillFile({}, {}) failed: {}", skillName, relPath, e.getMessage());
            return false;
        }
    }

    /**
     * 返回相对技能根路径，供调用方构建子路径。
     */
    public String resolveSkillRoot(String skillName) {
        return skillDirRelative(skillName);
    }

    // =========================================================================
    // 访问器（供 harness 技能运行时及辅助代码使用）
    // =========================================================================

    public AbstractFilesystem filesystem() {
        return filesystem;
    }

    public String skillsRelativeDir() {
        return skillsRelativeDir;
    }

    // =========================================================================
    // 内部实现
    // =========================================================================

    private void writeSkill(AgentSkill skill, RuntimeContext ctx) {
        String skillMd = toMarkdown(skill);
        String skillDir = skillDirRelative(skill.getName());
        String skillMdPath = skillDir + "/" + SKILL_FILE;

        List<Map.Entry<String, byte[]>> uploads = new ArrayList<>();
        uploads.add(
                new AbstractMap.SimpleImmutableEntry<>(
                        skillMdPath, skillMd.getBytes(StandardCharsets.UTF_8)));

        Map<String, String> resources = skill.getResources();
        if (resources != null) {
            for (Map.Entry<String, String> entry : resources.entrySet()) {
                String relPath = entry.getKey();
                String content = entry.getValue();
                if (relPath == null || relPath.isBlank() || content == null) {
                    continue;
                }
                if (relPath.startsWith("/") || relPath.contains("..")) {
                    log.warn(
                            "Skipping resource with unsafe path '{}' in skill '{}'",
                            relPath,
                            skill.getName());
                    continue;
                }
                String targetPath = skillDir + "/" + relPath;
                uploads.add(
                        new AbstractMap.SimpleImmutableEntry<>(
                                targetPath, content.getBytes(StandardCharsets.UTF_8)));
            }
        }

        filesystem.uploadFiles(ctx, uploads);
    }

    private String skillDirRelative(String name) {
        return skillsRelativeDir + "/" + name;
    }

    private String archiveDestRelative(String name) {
        String ts =
                DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                        .withZone(ZoneOffset.UTC)
                        .format(Instant.now());
        return skillsRelativeDir + "/" + ARCHIVE_PREFIX + "/" + name + "-" + ts;
    }

    /**
     * 将技能重新组装成带 YAML frontmatter 的 Markdown 文档，是 {@code MarkdownSkillParser} 的逆操作，兼容 {@link AgentSkill} 携带的各类标量、列表和映射元数据。
     */
    static String toMarkdown(AgentSkill skill) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(options);

        LinkedHashMap<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("name", skill.getName());
        ordered.put("description", skill.getDescription());
        Map<String, Object> meta = skill.getMetadata();
        if (meta != null) {
            for (Map.Entry<String, Object> e : meta.entrySet()) {
                String k = e.getKey();
                if ("name".equals(k) || "description".equals(k)) {
                    continue;
                }
                ordered.put(k, e.getValue());
            }
        }

        String fm = yaml.dump(ordered).trim();
        String body = skill.getSkillContent() != null ? skill.getSkillContent() : "";
        return "---\n" + fm + "\n---\n" + body;
    }

    /**
     * 路径表示位于 {@code base} 直属元数据子树（例如 {@code _drafts/}、{@code .archive/}、{@code .audit/}、{@code .backups/}）中的技能时，返回 {@code true}。匹配 {@code "/<base>/<x>/"} 模式，其中 {@code <x>} 以 {@code _} 或 {@code .} 开头。
     *
     * <p>特殊处理 {@code base == "."} 和 {@code base == ""}：检查路径首段是否以 {@code _} 或 {@code .} 开头，使工作区根目录扫描也能过滤 {@code .skills-cache/} 等元数据树。
     *
     * <p>不依赖后端：匹配的是子串，不要求路径相对于搜索根，因此同时适用于绝对路径（LocalFilesystem 默认）、虚拟路径和命名空间路径。
     */
    static boolean hasMetadataAncestor(String path, String base) {
        if (path == null || base == null) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        String b = base.replace('\\', '/');

        // base 为 "." 或 "" 时，忽略前导斜杠后的首段若以
        // '_' 或 '.' 开头，就属于元数据，避免标记 '/./' 无法匹配。
        if (b.isEmpty() || ".".equals(b)) {
            String trimmed = normalized.startsWith("/") ? normalized.substring(1) : normalized;
            int slash = trimmed.indexOf('/');
            if (slash <= 0) {
                return false;
            }
            char first = trimmed.charAt(0);
            return first == '_' || first == '.';
        }

        if (b.startsWith("/")) {
            b = b.substring(1);
        }
        if (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        String marker = "/" + b + "/";
        int idx = normalized.indexOf(marker);
        if (idx < 0) {
            if (normalized.startsWith(b + "/")) {
                int afterBase = b.length() + 1;
                if (afterBase >= normalized.length()) {
                    return false;
                }
                char first = normalized.charAt(afterBase);
                return first == '_' || first == '.';
            }
            return false;
        }
        int afterBase = idx + marker.length();
        if (afterBase >= normalized.length()) {
            return false;
        }
        char first = normalized.charAt(afterBase);
        return first == '_' || first == '.';
    }

    // =========================================================================
    // 懒加载资源访问器实现
    // =========================================================================

    private static final class FilesystemSkillResources implements SkillResources {

        private final AbstractFilesystem fs;
        private final String skillDir;
        private final RuntimeContext capturedCtx;

        FilesystemSkillResources(AbstractFilesystem fs, String skillDir, RuntimeContext ctx) {
            this.fs = fs;
            this.skillDir = skillDir;
            this.capturedCtx = ctx;
        }

        @Override
        public Optional<String> read(String relativePath) {
            String safe = sanitize(relativePath);
            if (safe == null) {
                return Optional.empty();
            }
            try {
                ReadResult r = fs.read(capturedCtx, skillDir + "/" + safe, 0, 0);
                if (r.isSuccess() && r.fileData() != null && r.fileData().content() != null) {
                    return Optional.of(r.fileData().content());
                }
            } catch (Exception e) {
                log.debug("SkillResources.read({}) failed: {}", relativePath, e.getMessage());
            }
            return Optional.empty();
        }

        @Override
        public Optional<byte[]> readBinary(String relativePath) {
            String safe = sanitize(relativePath);
            if (safe == null) {
                return Optional.empty();
            }
            String full = skillDir + "/" + safe;
            try {
                List<FileDownloadResponse> resps = fs.downloadFiles(capturedCtx, List.of(full));
                if (resps == null || resps.isEmpty()) {
                    return Optional.empty();
                }
                FileDownloadResponse resp = resps.get(0);
                if (resp != null && resp.isSuccess() && resp.content() != null) {
                    return Optional.of(resp.content());
                }
            } catch (Exception e) {
                log.debug("SkillResources.readBinary({}) failed: {}", relativePath, e.getMessage());
            }
            return Optional.empty();
        }

        @Override
        public List<String> list() {
            try {
                GlobResult g = fs.glob(capturedCtx, "**/*", skillDir);
                if (!g.isSuccess() || g.matches() == null || g.matches().isEmpty()) {
                    return Collections.emptyList();
                }
                List<String> rels = new ArrayList<>(g.matches().size());
                for (FileInfo fi : g.matches()) {
                    if (fi == null || fi.path() == null || fi.isDirectory()) {
                        continue;
                    }
                    String rel = relativeTo(fi.path(), skillDir);
                    if (rel == null || rel.isBlank() || SKILL_FILE.equals(rel)) {
                        continue;
                    }
                    rels.add(rel);
                }
                Collections.sort(rels);
                return rels;
            } catch (Exception e) {
                log.debug("SkillResources.list() failed: {}", e.getMessage());
                return Collections.emptyList();
            }
        }

        private static String sanitize(String relativePath) {
            if (relativePath == null || relativePath.isBlank()) {
                return null;
            }
            String s = relativePath.replace('\\', '/');
            if (s.startsWith("/") || s.contains("..")) {
                return null;
            }
            return s;
        }

        /**
         * 从 {@code path} 中移除 {@code skillDir} 前缀，通过匹配目录标记的最后一次出现，统一处理绝对、虚拟和命名空间存储。
         */
        private static String relativeTo(String path, String skillDir) {
            String normPath = path.replace('\\', '/');
            String normDir = skillDir.replace('\\', '/');
            if (normDir.startsWith("/")) {
                normDir = normDir.substring(1);
            }
            if (normDir.endsWith("/")) {
                normDir = normDir.substring(0, normDir.length() - 1);
            }
            String marker = "/" + normDir + "/";
            int idx = normPath.lastIndexOf(marker);
            if (idx >= 0) {
                return normPath.substring(idx + marker.length());
            }
            String prefix = normDir + "/";
            if (normPath.startsWith(prefix)) {
                return normPath.substring(prefix.length());
            }
            return normPath;
        }
    }
}
