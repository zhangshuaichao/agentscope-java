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
package io.agentscope.harness.agent.skill.runtime;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将非工作区技能资源（第 1 层/第 2 层/市场）物化到 {@code <wsRoot>/.skills-cache/<source-ns>/<skill-name>/}，使：
 * <ul>
 * <li>启用 Shell 的 HarnessAgent（沙箱或本地带 Shell）能通过绝对路径执行暂存脚本。
 * <li>沙箱投影（在 {@code DEFAULT_WORKSPACE_PROJECTION_ROOTS} 中包含 {@code .skills-cache}）能在启动时将暂存内容同步进沙箱。
 * </ul>
 *
 * <p>暂存器不保留跨调用状态：每次调用重新构建 {@code .skills-cache} 下应保留目录的允许名单，仅物化 SHA-256 已变化的文件，并清理不在名单中的孤立目录。
 *
 * <p>【学习提示】scope 解决不同身份的目录隔离，宽限期缓解共享范围内并发调用的清理冲突，两者用途不同。时间戳不是持有者租约，也不是文件内容快照；若调用超过宽限期或同范围内发生重写，不能仅靠这些机制推导出绝对并发隔离。
 *
 * <p><b>缓存是共享的。</b>一个暂存器实例服务同一工作区根目录上的所有并发调用，共享卷上的其他副本也写入同一目录树。因此，一个调用可见技能构成的允许名单无法说明其他进行中调用的需求：按用户划分技能可见性时，调用 B 的“孤立目录”可能是调用 A 正使用的 {@code filesRoot}。孤立目录回收因此受 {@link #DEFAULT_ORPHAN_GRACE} 限制：只有目录在整个宽限期内未被触碰才会删除，每次处理都会刷新保留目录的时间戳，遍历也容忍条目在过程中被删除。
 *
 * <p>工作区原生技能（由 {@link WorkspaceSkillRepository} 提供）不暂存：它们已在 {@code <wsRoot>/skills/} 下（或由沙箱文件系统懒加载），常规 {@code skills} 根投影已覆盖它们。
 */
@SuppressWarnings("deprecation")
public final class MarketplaceStager {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceStager.class);

    public static final String CACHE_DIR = ".skills-cache";
    public static final String GLOBAL_NAMESPACE = "_global";

    /**
     * 孤立目录必须持续未被触碰多久才允许删除。此窗口应长于任何仍可能通过 Shell 执行暂存脚本的调用，因为暂存路径在系统提示中交给模型后，可能很晚才被使用。保留旧目录只花费少量空间，删除活动目录却会破坏其他用户调用，因此默认值倾向于更长。
     */
    public static final Duration DEFAULT_ORPHAN_GRACE = Duration.ofMinutes(30);

    /**
     * 调用方未提供隔离身份时使用的路径段。
     */
    public static final String SHARED_SCOPE = "_shared";

    private final Path workspaceRoot;
    private final Duration orphanGrace;

    public MarketplaceStager(Path workspaceRoot) {
        this(workspaceRoot, DEFAULT_ORPHAN_GRACE);
    }

    /**
     * 供调用方及测试指定非默认孤立目录宽限期的重载。
     */
    public MarketplaceStager(Path workspaceRoot, Duration orphanGrace) {
        this.workspaceRoot = workspaceRoot;
        this.orphanGrace =
                orphanGrace != null && !orphanGrace.isNegative()
                        ? orphanGrace
                        : DEFAULT_ORPHAN_GRACE;
    }

    /**
     * 暂存全部符合条件的输入，并返回从 {@code skill.name} 到解析后的 {@link StageResult} 的映射。来源仓库为 {@link WorkspaceSkillRepository} 的输入返回 {@link StageResult.WorkspaceNative}，因为工作区树已包含它们，无需暂存。
     *
     * <p>每次调用重建暂存目录允许名单；{@code .skills-cache/<source-ns>/} 下不在名单中的现有目录将进入孤立回收流程，满足宽限期后才删除，避免市场仓库不再发布的技能永久残留。
     *
     * @param visible 按组合顺序排列的技能与仓库对（上游已按名称去重，只保留胜出者）
     * @param sourceNs 仓库身份到解析后来源命名空间的映射（通过 {@code _idx} 后缀处理 {@code getSource()} 冲突）
     * @return 以 {@code skill.name} 为键的有序映射（保留插入顺序）
     */
    public Map<String, StageResult> stage(
            List<RepoBound> visible, Map<AgentSkillRepository, String> sourceNs) {
        // null 表示“没有可用身份”，不同于某个身份的拼写
        // 恰好与共享桶名称相同。
        return stage(visible, sourceNs, null);
    }

    /**
     * 暂存到本次调用的隔离范围。GC 可能删除的每个目录都位于 {@code .skills-cache/<scope>/} 下；只有共享 scope 的调用才共享此子树，因此单次调用可见技能构成的允许名单限定在该范围内，而不像扁平布局那样涉及其他范围。
     *
     * @param scope 单次调用隔离键（通常为 {@code userId} 或 {@code sessionId}）；空白值归入 {@value #SHARED_SCOPE}
     */
    public Map<String, StageResult> stage(
            List<RepoBound> visible, Map<AgentSkillRepository, String> sourceNs, String scope) {
        Map<String, StageResult> roots = new HashMap<>(visible.size());
        if (workspaceRoot == null) {
            // 无宿主工作区（少见，例如仅 classpath 的构建）时，跳过暂存，
            // 对相关技能报告没有 filesRoot；Shell 模式渲染
            // 会正常省略它们。
            for (RepoBound bound : visible) {
                if (bound.repo() instanceof WorkspaceSkillRepository) {
                    roots.put(bound.skill().getName(), new StageResult.WorkspaceNative());
                } else {
                    roots.put(bound.skill().getName(), StageResult.NONE);
                }
            }
            return roots;
        }

        Path scopeRoot = workspaceRoot.resolve(CACHE_DIR).resolve(scopeSegment(scope));
        Set<Path> retained = new HashSet<>();

        stageAll(visible, sourceNs, scopeRoot, retained, roots);

        try {
            garbageCollectOrphans(scopeRoot, retained);
        } catch (Exception e) {
            // 缓存清理尽力而为，绝不能让智能体调用失败，
            // 与单技能物化采用相同回退原则。
            log.warn("Orphan GC under {} skipped: {}", scopeRoot, e.getMessage());
        }
        return roots;
    }

    /**
     * 空白 scope 归入同一个共享路径段，使 GC 始终只面对一个子树。
     */
    private static final int MAX_SCOPE_SEGMENT = 64;

    /**
     * 将调用方身份映射为单个路径段，并避免仅清理字符带来的碰撞。仅做清理不够：{@code alice@corp.com} 和 {@code alice#corp.com} 都会变成 {@code alice_corp.com}，两个身份共享子树正是 scope 要避免的问题。对并非天然独立且文件系统安全的路径段，保留可读前缀，并使用原始身份摘要消歧。
     */
    private static String scopeSegment(String scope) {
        if (scope == null || scope.isBlank()) {
            return SHARED_SCOPE;
        }
        String safe = scope.replaceAll("[^A-Za-z0-9._-]", "_");
        boolean lossless =
                safe.equals(scope)
                        && !safe.equals(SHARED_SCOPE)
                        && safe.length() <= MAX_SCOPE_SEGMENT
                        // Windows 不接受路径分量末尾的点或空格。
                        && !safe.endsWith(".");
        if (lossless) {
            return safe;
        }
        String digest = sha256(scope.getBytes(StandardCharsets.UTF_8)).substring(0, 12);
        int keep = Math.min(safe.length(), MAX_SCOPE_SEGMENT - digest.length() - 1);
        return safe.substring(0, Math.max(keep, 0)) + "-" + digest;
    }

    /**
     * 将全部符合条件的输入物化到本次调用的 scope 子树下。
     */
    private void stageAll(
            List<RepoBound> visible,
            Map<AgentSkillRepository, String> sourceNs,
            Path scopeRoot,
            Set<Path> retained,
            Map<String, StageResult> roots) {
        for (RepoBound bound : visible) {
            AgentSkill skill = bound.skill();
            String name = skill.getName();
            if (name == null || name.isBlank()) {
                continue;
            }

            if (bound.repo() instanceof WorkspaceSkillRepository) {
                // 工作区原生技能：skills/<name>/ 已在正确路径上。
                roots.put(name, new StageResult.WorkspaceNative());
                continue;
            }

            String ns = sourceNs.get(bound.repo());
            if (ns == null || ns.isBlank()) {
                ns = bound.repo().getSource();
                if (ns == null || ns.isBlank()) {
                    ns = GLOBAL_NAMESPACE;
                }
            }

            Path stagedDir = scopeRoot.resolve(ns).resolve(name);
            try {
                if (!materializeIfChanged(stagedDir, skill.getResources())) {
                    // 未物化任何内容，符合懒加载仓库的输出：
                    // 它没有发布内存资源。此时若报告 Cached，
                    // 就会为始终为空的目录渲染 <files-root>，
                    // 因此报告 NONE，让 Shell 模式渲染省略此技能。
                    log.debug("Skill '{}' (source-ns={}) staged no resources", name, ns);
                    roots.put(name, StageResult.NONE);
                    continue;
                }
                // 在 GC 之前标记为活动状态，避免并发调用或
                // 共享卷的其他副本将其视为孤立目录。
                touch(stagedDir);
                retained.add(stagedDir);
                roots.put(
                        name, new StageResult.Cached(scopeRoot.getFileName().toString(), ns, name));
            } catch (Exception e) {
                log.warn("Failed to stage skill '{}' (source-ns={}): {}", name, ns, e.getMessage());
                roots.put(name, StageResult.NONE);
            }
        }
    }

    /**
     * 供不关心返回值的调用方使用的便捷方法。
     */
    public void invalidateAll() {
        if (workspaceRoot == null) {
            return;
        }
        Path cacheRoot = workspaceRoot.resolve(CACHE_DIR);
        if (!Files.isDirectory(cacheRoot)) {
            return;
        }
        try {
            deleteRecursively(cacheRoot);
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to clear {}: {}", cacheRoot, e.getMessage());
        }
    }

    // =========================================================================
    // 内部实现
    // =========================================================================

    private boolean materializeIfChanged(Path stagedDir, Map<String, String> resources)
            throws IOException {
        if (resources == null || resources.isEmpty()) {
            // 没有内容可写时不留下目录，让调用方能够区分
            // 已暂存技能和内容不可用的技能。
            return false;
        }
        // 暂存目录由 writeIfChanged 在首个通过下方过滤的条目处延迟创建。
        // 若在此提前创建，会为所有条目均被拒绝的映射生成目录，
        // 违背上述约定。
        // 记录预期文件，之后移除 stagedDir 下的多余文件。
        Set<Path> expected = new HashSet<>();
        for (Map.Entry<String, String> e : resources.entrySet()) {
            String rel = e.getKey();
            String content = e.getValue();
            if (rel == null || rel.isBlank() || content == null) {
                continue;
            }
            if (rel.startsWith("/") || rel.contains("..")) {
                log.debug("Skipping unsafe resource path '{}' during stage", rel);
                continue;
            }
            Path target = stagedDir.resolve(rel).normalize();
            if (!target.startsWith(stagedDir)) {
                log.debug("Skipping out-of-tree resource path '{}' during stage", rel);
                continue;
            }
            byte[] bytes = decode(content);
            writeIfChanged(target, bytes);
            expected.add(target);
        }
        // 移除暂存目录中已不再对应此技能已发布资源的旧文件，
        // 使每个技能的暂存操作幂等并自行清理。
        removeUnexpected(stagedDir, expected);
        return !expected.isEmpty();
    }

    private void writeIfChanged(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            byte[] existing = Files.readAllBytes(target);
            if (sha256(existing).equals(sha256(bytes))) {
                return;
            }
        }
        Files.write(target, bytes);
        // 启发式恢复执行位：摄入路径将文件转换为字符串，丢弃了
        // POSIX 模式，因此从 shebang 或已知脚本扩展名重新推导 +x。这不是真正的
        // 模式保留；纯静态技能素材（.json/.md/.txt）仍保持 644。
        maybeMarkExecutable(target, bytes);
    }

    /**
     * 脚本检测启发式：字节 0/1 处存在 shebang，或使用已知脚本后缀。匹配时在 POSIX 文件系统添加执行权限；非 POSIX（Windows）静默不操作。
     */
    private static void maybeMarkExecutable(Path target, byte[] bytes) {
        if (!shouldBeExecutable(target, bytes)) {
            return;
        }
        try {
            Set<PosixFilePermission> current = new HashSet<>(Files.getPosixFilePermissions(target));
            // 只为已有读取权限的用户类别增加执行位，类似 `chmod +x`：
            // 640 文件变为 750，而不是 751。
            EnumSet<PosixFilePermission> toAdd = EnumSet.noneOf(PosixFilePermission.class);
            if (current.contains(PosixFilePermission.OWNER_READ)) {
                toAdd.add(PosixFilePermission.OWNER_EXECUTE);
            }
            if (current.contains(PosixFilePermission.GROUP_READ)) {
                toAdd.add(PosixFilePermission.GROUP_EXECUTE);
            }
            if (current.contains(PosixFilePermission.OTHERS_READ)) {
                toAdd.add(PosixFilePermission.OTHERS_EXECUTE);
            }
            if (toAdd.isEmpty()) {
                return;
            }
            current.addAll(toAdd);
            Files.setPosixFilePermissions(target, current);
        } catch (UnsupportedOperationException e) {
            // POSIX 权限不可用（Windows 默认文件系统），有意不操作。
        } catch (IOException e) {
            log.debug("Failed to set exec bit on {}: {}", target, e.getMessage());
        }
    }

    /**
     * 已知解释器/脚本后缀；采取保守策略，仅标记明显的脚本。
     */
    private static final Set<String> SCRIPT_SUFFIXES =
            Set.of(".sh", ".bash", ".zsh", ".ksh", ".py", ".rb", ".pl", ".js", ".mjs");

    /**
     * 包级可见，便于不启动 {@link #stage} 就直接测试启发式规则。
     */
    static boolean shouldBeExecutable(Path target, byte[] bytes) {
        // 1. shebang 检测：不依赖文件名的最强信号。
        if (bytes != null && bytes.length >= 2 && bytes[0] == '#' && bytes[1] == '!') {
            return true;
        }
        // 2. 文件名后缀。
        String fileName = target.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return SCRIPT_SUFFIXES.contains(fileName.substring(dot));
    }

    private void removeUnexpected(Path stagedDir, Set<Path> expected) {
        try (var stream = Files.walk(stagedDir)) {
            List<Path> toDelete = new ArrayList<>();
            stream.filter(Files::isRegularFile)
                    .filter(p -> !expected.contains(p.normalize()))
                    .forEach(toDelete::add);
            for (Path p : toDelete) {
                deleteQuietly(p);
            }
            // 清理移除文件后留下的空子目录。
            try (var dirStream = Files.walk(stagedDir)) {
                List<Path> dirs = new ArrayList<>();
                dirStream
                        .filter(Files::isDirectory)
                        .filter(p -> !p.equals(stagedDir))
                        .forEach(dirs::add);
                // 从叶子到根遍历，确保能够删除。
                dirs.sort((a, b) -> b.getNameCount() - a.getNameCount());
                for (Path d : dirs) {
                    try (var probe = Files.list(d)) {
                        if (probe.findAny().isEmpty()) {
                            deleteQuietly(d);
                        }
                    }
                }
            }
        } catch (IOException | UncheckedIOException e) {
            // Files.walk / Files.list 会将迭代中的 I/O 错误（条目被并发调用删除）
            // 包装为 UncheckedIOException，它不是 IOException。
            log.debug("Cleanup of {} failed: {}", stagedDir, e.getMessage());
        }
    }

    private void garbageCollectOrphans(Path cacheRoot, Set<Path> retained) {
        if (!Files.isDirectory(cacheRoot)) {
            return;
        }
        // `retained` 只反映一次调用的可见技能，而缓存是共享的；仅删除
        // 整个宽限期内没有任何调用暂存过的条目。
        Instant cutoff = Instant.now().minus(orphanGrace);
        // 两层布局：<source-ns>/<skill-name>/
        try (var nsStream = Files.list(cacheRoot)) {
            List<Path> nsDirs = new ArrayList<>();
            nsStream.filter(Files::isDirectory).forEach(nsDirs::add);
            for (Path nsDir : nsDirs) {
                try (var skillStream = Files.list(nsDir)) {
                    List<Path> skillDirs = new ArrayList<>();
                    skillStream.filter(Files::isDirectory).forEach(skillDirs::add);
                    for (Path skillDir : skillDirs) {
                        if (retained.contains(skillDir) || !isStale(skillDir, cutoff)) {
                            continue;
                        }
                        deleteRecursively(skillDir);
                    }
                }
                // 清理空命名空间目录。
                try (var probe = Files.list(nsDir)) {
                    if (probe.findAny().isEmpty()) {
                        deleteQuietly(nsDir);
                    }
                }
            }
        } catch (IOException | UncheckedIOException e) {
            log.debug("Orphan GC under {} failed: {}", cacheRoot, e.getMessage());
        }
    }

    /**
     * 刷新 GC 读取的修改时间，降低活动目录被误判为孤立目录的风险。
     */
    private static void touch(Path dir) {
        try {
            Files.setLastModifiedTime(dir, FileTime.from(Instant.now()));
        } catch (IOException | RuntimeException e) {
            log.debug("Failed to touch {}: {}", dir, e.getMessage());
        }
    }

    /**
     * 修改时间不可读就无法证明条目已失效，因此保留它。
     */
    private static boolean isStale(Path dir, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(dir).toInstant().isBefore(cutoff);
        } catch (IOException | RuntimeException e) {
            log.debug("Cannot read mtime of {}; keeping it: {}", dir, e.getMessage());
            return false;
        }
    }

    /**
     * 删除一个条目，容忍它已被并发调用删除或替换。
     */
    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException | RuntimeException e) {
            log.debug("Failed to delete {}: {}", p, e.getMessage());
        }
    }

    /**
     * 容忍遍历过程中目录树变化的递归删除。使用 {@link Files#walkFileTree} 而不是 {@link Files#walk}：后者的懒加载流一遇到条目消失就以 {@link UncheckedIOException} 中止整个遍历；访问器则通过 {@code visitFileFailed} 报告，允许我们继续。
     */
    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        deleteQuietly(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        // 已消失或不可读，无需继续移除。
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                        deleteQuietly(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    private static byte[] decode(String content) {
        if (content.startsWith("base64:")) {
            return Base64.getDecoder().decode(content.substring("base64:".length()));
        }
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(bytes);
            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 解析每个仓库的 {@code source} 命名空间。若多个仓库报告相同 {@code getSource()}，第二个及之后的仓库获得 {@code _<idx>} 后缀，并记录警告。第 1 层宿主仓库来源字符串为空时使用 {@link #GLOBAL_NAMESPACE}。
     */
    public static Map<AgentSkillRepository, String> resolveSourceNamespaces(
            List<AgentSkillRepository> repos) {
        Map<AgentSkillRepository, String> ns = new IdentityHashMap<>();
        if (repos == null || repos.isEmpty()) {
            return ns;
        }
        Map<String, Integer> count = new HashMap<>();
        for (AgentSkillRepository repo : repos) {
            String src = effectiveSource(repo);
            count.merge(src, 1, Integer::sum);
        }
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < repos.size(); i++) {
            AgentSkillRepository repo = repos.get(i);
            String src = effectiveSource(repo);
            int total = count.getOrDefault(src, 1);
            if (total == 1) {
                ns.put(repo, src);
            } else {
                int idx = seen.merge(src, 1, Integer::sum);
                String resolved = src + "_" + idx;
                ns.put(repo, resolved);
                if (idx > 1 || total > 1) {
                    log.warn(
                            "Skill repository source '{}' is used by {} repositories;"
                                    + " disambiguating as '{}' for repo at index {} ({})",
                            src,
                            total,
                            resolved,
                            i,
                            repo.getClass().getSimpleName());
                }
            }
        }
        return ns;
    }

    private static String effectiveSource(AgentSkillRepository repo) {
        String s = repo.getSource();
        if (s == null || s.isBlank()) {
            return GLOBAL_NAMESPACE;
        }
        // WorkspaceSkillRepository 不占用来源命名空间槽位，但仍记录其索引，
        // 让调用方可以统一传递映射。
        return s;
    }

    /**
     * 将技能与产生它的仓库配对。
     */
    public record RepoBound(AgentSkill skill, AgentSkillRepository repo) {}

    /**
     * 暂存单个技能的结果。
     */
    public sealed interface StageResult {
        StageResult NONE = new None();

        /**
         * 未执行暂存，技能来源没有 Shell 可达的表示。
         *
         * <p>仓库未发布任何可暂存资源时也返回此值。本轮既未暂存也未保留该技能内容，因此早先调用写入的目录会成为孤立回收候选。{@code NONE} 不表示“之前缓存的文件仍然存在”。
         */
        record None() implements StageResult {}

        /**
         * 技能来自 {@link WorkspaceSkillRepository}（已在 workspace/skills/ 中）。
         */
        record WorkspaceNative() implements StageResult {}

        /**
         * 技能暂存于 {@code .skills-cache/<sourceNs>/<skillName>/} 下。
         */
        record Cached(String scopeSegment, String sourceNamespace, String skillName)
                implements StageResult {}
    }
}
