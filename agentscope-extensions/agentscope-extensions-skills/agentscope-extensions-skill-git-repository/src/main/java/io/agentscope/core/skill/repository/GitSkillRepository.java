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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 Git 的只读 AgentSkillRepository 实现。
 *
 * <p>将远端仓库克隆到本地目录，直接通过文件系统读取技能。
 * {@link #getSkill(String)}、{@link #getAllSkillNames()}、{@link #getAllSkills()}、
 * {@link #skillExists(String)} 等读取操作会触发同步。
 * <p>每次读取先轻量检查远端引用，远端 HEAD 变化时才拉取，以较低网络开销保持更新。
 * <p>构造时传入 {@code autoSync=false} 可禁用自动同步；此时读取前应调用 {@link #sync()} 初始化或刷新。
 *
 * <p><b>功能：</b>只读仓库访问、读取时自动同步、HTTPS/SSH URL、分支选择、Git 进度日志及临时目录清理。
 *
 * <p><b>认证：</b>上游说明预期使用系统 Git 配置：HTTPS 的 ~/.gitconfig 或凭据助手，
 * SSH 的 ~/.ssh/ 密钥与 ssh-agent。此类实际调用 JGit，未显式注入凭据提供器或 SSH 传输配置；
 * 因此不能仅凭这段说明保证当前运行环境自动继承命令行 Git 的全部认证行为，应验证所用 JGit 传输配置。
 *
 * <p><b>使用示例：</b>
 * <pre>{@code
 * // 使用默认分支的公开仓库
 * GitSkillRepository repo = new GitSkillRepository(
 *     "https://github.com/agentscope/skills.git"
 * );
 * AgentSkill skill = repo.getSkill("calculator");
 * repo.close();
 *
 * // 指定分支
 * GitSkillRepository repo = new GitSkillRepository(
 *     "https://github.com/agentscope/skills.git",
 *     "develop"
 * );
 *
 * // 私有仓库（需要系统 Git 凭据）
 * GitSkillRepository repo = new GitSkillRepository(
 *     "git@github.com:myorg/private-skills.git"
 * );
 * }</pre>
 *
 * <p><b>临时目录：</b>调用 {@link Files#createTempDirectory(String,
 * java.nio.file.attribute.FileAttribute[])} 创建；JVM 关闭钩子或 {@link #close()} 尝试清理。
 *
 * <p>【学习提示】“只读”表示技能仓库接口不写回远端，并不表示读取无副作用：同步会访问网络、更新本地文件。
 * skillsRoot 的 normalize/startsWith 检查是词法路径约束，不等于解析符号链接后的沙箱隔离；
 * 远端技能内容仍是需要审查的数据，不会因来自 Git 自动获得执行授权。
 *
 * @see FileSystemSkillRepository
 * @see AgentSkillRepository
 */
public class GitSkillRepository implements AgentSkillRepository {

    private static final Logger logger = LoggerFactory.getLogger(GitSkillRepository.class);

    private final String remoteUrl;
    private final String branch;
    private final Path localPath;
    private final String source;
    private final boolean autoSync;
    private final String skillsRoot;
    private final boolean tempDirectory;
    private final Thread shutdownHook;
    private Path skillsPath;
    private volatile String lastRemoteRef;

    /**
     * 使用指定远端及相应配置创建 GitSkillRepository。
     * @param remoteUrl Git 仓库 URL（HTTPS 或 SSH）
     * @throws IllegalArgumentException remoteUrl 为 null 或空值时抛出
     * @throws RuntimeException 创建临时目录失败时抛出
     */
    public GitSkillRepository(String remoteUrl) {
        this(remoteUrl, null, null, null, true);
    }

    /**
     * 使用指定远端及相应配置创建 GitSkillRepository。
     * @param remoteUrl Git 仓库 URL（HTTPS 或 SSH）
     * @param localPath 本地克隆路径，null 表示使用临时目录
     * @throws IllegalArgumentException remoteUrl 为 null 或空值时抛出
     * @throws RuntimeException 创建临时目录失败时抛出
     */
    public GitSkillRepository(String remoteUrl, Path localPath) {
        this(remoteUrl, null, localPath, null, true);
    }

    /**
     * 使用指定远端及相应配置创建 GitSkillRepository。
     * @param remoteUrl Git 仓库 URL（HTTPS 或 SSH）
     * @param branch 克隆分支，null 表示远端默认分支
     * @throws IllegalArgumentException remoteUrl 为 null 或空值时抛出
     * @throws RuntimeException 创建临时目录失败时抛出
     */
    public GitSkillRepository(String remoteUrl, String branch) {
        this(remoteUrl, branch, null, null, true);
    }

    /**
     * 使用指定远端及相应配置创建 GitSkillRepository。
     * @param remoteUrl Git 仓库 URL（HTTPS 或 SSH）
     * @param localPath 本地克隆路径，null 表示使用临时目录
     * @param source 自定义来源标识符，null 表示默认值
     * @throws IllegalArgumentException remoteUrl 为 null 或空值时抛出
     * @throws RuntimeException 创建临时目录失败时抛出
     */
    public GitSkillRepository(String remoteUrl, Path localPath, String source) {
        this(remoteUrl, null, localPath, source, true);
    }

    /**
     * 使用指定远端及相应配置创建 GitSkillRepository。
     * @param remoteUrl Git 仓库 URL（HTTPS 或 SSH）
     * @param branch 克隆分支，null 表示远端默认分支
     * @param localPath 本地克隆路径，null 表示使用临时目录
     * @throws IllegalArgumentException remoteUrl 为 null 或空值时抛出
     * @throws RuntimeException 创建临时目录失败时抛出
     */
    public GitSkillRepository(String remoteUrl, String branch, Path localPath) {
        this(remoteUrl, branch, localPath, null, true);
    }

    /**
     * 使用指定远端及相应配置创建 GitSkillRepository。
     * @param remoteUrl Git 仓库 URL（HTTPS 或 SSH）
     * @param autoSync 是否在读取时自动同步
     * @throws IllegalArgumentException remoteUrl 为 null 或空值时抛出
     * @throws RuntimeException 创建临时目录失败时抛出
     */
    public GitSkillRepository(String remoteUrl, boolean autoSync) {
        this(remoteUrl, null, null, null, autoSync);
    }

    /**
     * 使用指定远端及相应配置创建 GitSkillRepository。
     * @param remoteUrl Git 仓库 URL（HTTPS 或 SSH）
     * @param branch 克隆分支，null 表示远端默认分支
     * @param localPath 本地克隆路径，null 表示使用临时目录
     * @param source 自定义来源标识符，null 表示默认值
     * @throws IllegalArgumentException remoteUrl 为 null 或空值时抛出
     * @throws RuntimeException 创建临时目录失败时抛出
     */
    public GitSkillRepository(String remoteUrl, String branch, Path localPath, String source) {
        this(remoteUrl, branch, localPath, source, true);
    }

    /**
     * 使用指定远端及相应配置创建 GitSkillRepository。
     * @param remoteUrl Git 仓库 URL（HTTPS 或 SSH）
     * @param branch 克隆分支，null 表示远端默认分支
     * @param localPath 本地克隆路径，null 表示使用临时目录
     * @param source 自定义来源标识符，null 表示默认值
     * @param autoSync 是否在读取时自动同步
     * @throws IllegalArgumentException remoteUrl 为 null 或空值时抛出
     * @throws RuntimeException 创建临时目录失败时抛出
     */
    public GitSkillRepository(
            String remoteUrl, String branch, Path localPath, String source, boolean autoSync) {
        this(remoteUrl, branch, localPath, source, autoSync, null);
    }

    /**
     * 通过明确的仓库内技能根目录创建 GitSkillRepository。
     * <p>{@code skillsRoot} 指定含有各技能文件夹的仓库内目录，每个文件夹包含 {@code SKILL.md}。
     * 非 null 值按相对仓库根目录的路径处理，拒绝绝对路径和 {@code ..} 路径段。
     * 为 null 时采用旧约定：存在 {@code <repo>/skills/} 就使用它，否则使用仓库根目录。
     * @param remoteUrl Git 仓库 URL（HTTPS 或 SSH）
     * @param branch 克隆分支，null 表示远端默认分支
     * @param localPath 本地克隆路径，null 表示临时目录
     * @param source 自定义来源标识符，null 表示默认值
     * @param autoSync 是否在读取时自动同步
     * @param skillsRoot 可选的仓库内技能目录
     * @throws IllegalArgumentException {@code remoteUrl} 为空白，或 {@code skillsRoot} 为绝对路径、包含 {@code ..} 段或逃逸仓库根目录时抛出
     * @throws RuntimeException 创建临时目录失败时抛出
     */
    public GitSkillRepository(
            String remoteUrl,
            String branch,
            Path localPath,
            String source,
            boolean autoSync,
            String skillsRoot) {
        if (remoteUrl == null || remoteUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("Remote URL cannot be null or empty");
        }
        this.remoteUrl = remoteUrl.trim();
        this.branch = branch;
        this.source = source;
        this.autoSync = autoSync;
        this.skillsRoot = normalizeSkillsRoot(skillsRoot);

        if (localPath != null) {
            // 用户指定的路径不是临时目录，不会自动删除。
            this.localPath = localPath;
            this.tempDirectory = false;
            this.shutdownHook = null;
            logger.info("Using user-specified directory for Git repository: {}", localPath);
        } else {
            // 使用 JDK 标准 API 创建临时目录。
            try {
                this.localPath = Files.createTempDirectory("agentscope-git-skills-");
                this.tempDirectory = true;
                logger.info("Created temporary directory for Git repository: {}", this.localPath);

                this.shutdownHook =
                        SkillFileSystemHelper.registerTempDirectoryCleanup(this.localPath);
            } catch (IOException e) {
                throw new RuntimeException(
                        "Failed to create temporary directory for Git repository", e);
            }
        }
    }

    @Override
    public AgentSkill getSkill(String name) {
        ensureAutoSynced();
        return SkillFileSystemHelper.loadSkill(skillsPath, name, getSource());
    }

    @Override
    public List<String> getAllSkillNames() {
        ensureAutoSynced();
        return SkillFileSystemHelper.getAllSkillNames(skillsPath);
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        ensureAutoSynced();
        return SkillFileSystemHelper.getAllSkills(skillsPath, getSource());
    }

    @Override
    public boolean skillExists(String skillName) {
        ensureAutoSynced();
        return SkillFileSystemHelper.skillExists(skillsPath, skillName);
    }

    /**
     * 手动触发仓库同步。
     */
    public void sync() {
        ensureRepositorySynced();
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        logger.warn("GitSkillRepository is read-only, save operation ignored");
        return false;
    }

    @Override
    public boolean delete(String skillName) {
        logger.warn("GitSkillRepository is read-only, delete operation ignored");
        return false;
    }

    @Override
    public void setWriteable(boolean writeable) {
        logger.warn("GitSkillRepository is read-only, set writeable operation ignored");
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    @Override
    /**
     * 手动清理本地临时仓库目录。
     * <p>此操作可选，JVM 正常终止时关闭钩子也会尝试删除临时目录；需要提前释放磁盘空间时可调用。
     */
    public void close() {
        if (!tempDirectory) {
            return;
        }

        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                logger.debug("JVM is shutting down, cannot remove shutdown hook", e);
            }
        }

        try {
            SkillFileSystemHelper.deleteDirectory(localPath);
        } catch (IOException e) {
            logger.warn("Failed to cleanup local repository directory: {}", localPath, e);
        }
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("git", remoteUrl, false);
    }

    @Override
    public String getSource() {
        return source != null ? source : buildDefaultSource();
    }

    private String buildDefaultSource() {
        String repoIdentifier = extractRepositoryIdentifier(remoteUrl);
        // 以连字符替代冒号，避免 Windows 路径问题。
        String source =
                branch != null ? "git-" + repoIdentifier + "@" + branch : "git-" + repoIdentifier;
        // 将分支名中可能出现的 Windows 保留字符替换为连字符。
        // 保留正斜杠，因为来源标识符允许 owner/repo 形式；
        // 这里构造的是来源标识符，不应将其直接视为任意平台可用的文件名。
        return source.replaceAll("[\\\\:*?\"<>|]", "-");
    }

    /**
     * 从 Git URL 提取简洁仓库标识符。
     * <p>示例：
     * <ul><li>https://github.com/owner/repo.git → owner/repo</li>
     * <li>git@github.com:owner/repo.git → owner/repo</li>
     * <li>https://gitlab.com/group/subgroup/project.git → group/subgroup/project</li></ul>
     * @param url Git 仓库 URL
     * @return 简洁仓库标识符
     */
    private String extractRepositoryIdentifier(String url) {
        // 存在 .git 后缀时移除。
        String normalized = url.endsWith(".git") ? url.substring(0, url.length() - 4) : url;

        // 处理 SSH 格式：git@host:owner/repo。
        if (normalized.contains("@") && normalized.contains(":")) {
            int colonIndex = normalized.lastIndexOf(':');
            return normalized.substring(colonIndex + 1);
        }

        // 处理 HTTPS 格式：https://host/owner/repo。
        // 也支持本地测试使用的 file:// 协议。
        if (normalized.contains("://")) {
            int hostEndIndex = normalized.indexOf('/', normalized.indexOf("://") + 3);
            if (hostEndIndex != -1 && hostEndIndex + 1 < normalized.length()) {
                return normalized.substring(hostEndIndex + 1);
            }
        }

        // 回退为最后一个斜杠之后的部分。
        int lastSlash = normalized.lastIndexOf('/');
        return lastSlash != -1 ? normalized.substring(lastSlash + 1) : normalized;
    }

    /**
     * 验证用户提供的仓库内技能根目录。
     * <p>拒绝绝对路径和 {@code ".."} 路径段，限制配置的词法路径范围。
     * 空白输入视为未指定，返回 null，保留旧版自动检测行为；符号链接边界需额外考虑。
     */
    private static String normalizeSkillsRoot(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        Path p = Path.of(trimmed);
        if (p.isAbsolute()) {
            throw new IllegalArgumentException(
                    "skillsRoot must be a path relative to the repository root, got: " + raw);
        }
        for (Path segment : p) {
            if (segment.toString().equals("..")) {
                throw new IllegalArgumentException(
                        "skillsRoot must not contain '..' segments, got: " + raw);
            }
        }
        return trimmed;
    }

    /**
     * 读取技能前确保 Git 仓库完成克隆或拉取同步。
     * @throws IllegalStateException 本地路径存在但不是有效 Git 仓库时抛出
     * @throws RuntimeException 克隆或拉取失败时抛出
     */
    private synchronized void ensureRepositorySynced() {
        try {
            boolean isEmpty = false;
            try {
                isEmpty = !Files.exists(localPath) || isEmptyDirectory(localPath);
            } catch (IOException e) {
                throw new RuntimeException(
                        "Unable to access local temporary directory: "
                                + localPath
                                + "\nError details: "
                                + e.getMessage(),
                        e);
            }

            if (isEmpty) {
                // 目录不存在或为空时克隆。
                cloneRepository();
            } else if (isGitRepository(localPath)) {
                // 已是 Git 仓库时，仅在远端引用变化后拉取更新。
                if (hasRemoteUpdates()) {
                    pullRepository();
                } else {
                    logger.debug("Remote repository has no updates, skipping pull");
                }
            } else {
                // 不是 Git 仓库则报错。
                throw new IllegalStateException(
                        "Local path exists but is not a valid Git repository: "
                                + localPath
                                + "\n"
                                + "Possible causes:\n"
                                + "  1. Previous clone operation was not completed\n"
                                + "  2. Directory was manually modified\n"
                                + "Solution: Manually delete the directory and retry, or call"
                                + " close() method to cleanup");
            }

            // 确保技能路径已初始化。
            if (skillsPath == null) {
                if (skillsRoot != null) {
                    Path explicit = localPath.resolve(skillsRoot).normalize();
                    if (!explicit.startsWith(localPath.toAbsolutePath().normalize())
                            && !explicit.startsWith(localPath.normalize())) {
                        throw new IllegalStateException(
                                "Configured skillsRoot escapes the repository: " + skillsRoot);
                    }
                    if (!Files.isDirectory(explicit)) {
                        throw new IllegalStateException(
                                "Configured skillsRoot does not exist in repository: "
                                        + skillsRoot
                                        + " (resolved to "
                                        + explicit
                                        + ")");
                    }
                    skillsPath = explicit;
                    logger.info("Using configured skillsRoot subdirectory: {}", skillsPath);
                } else {
                    // 约定：若存在 skills 子目录，则技能位于其中。
                    Path skillsSubDir = localPath.resolve("skills");
                    if (Files.exists(skillsSubDir) && Files.isDirectory(skillsSubDir)) {
                        skillsPath = skillsSubDir;
                        logger.info("Found skills subdirectory, using: {}", skillsPath);
                    } else {
                        skillsPath = localPath;
                        logger.info(
                                "No skills subdirectory found, using repository root: {}",
                                skillsPath);
                    }
                }

                logger.info(
                        "Initialized skills path for: {} (skills path: {})", remoteUrl, skillsPath);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to sync Git repository: " + remoteUrl, e);
        }
    }

    private void ensureAutoSynced() {
        if (autoSync) {
            ensureRepositorySynced();
            return;
        }
        if (skillsPath == null) {
            throw new IllegalStateException(
                    "Repository is not synchronized. Call sync() before reading skills.");
        }
    }

    /**
     * 检查目录是否为空。
     * @param path 待检查目录路径
     * @return 空目录为 true，否则为 false
     * @throws IOException 发生 I/O 错误时抛出
     */
    private boolean isEmptyDirectory(Path path) throws IOException {
        try (Stream<Path> entries = Files.list(path)) {
            return !entries.findFirst().isPresent();
        }
    }

    /**
     * 检查路径是否为 Git 仓库。
     * @param path 待检查路径
     * @return 包含 .git 目录时为 true，否则为 false
     */
    private boolean isGitRepository(Path path) {
        return Files.exists(path.resolve(".git"));
    }

    /**
     * 将远端 Git 仓库克隆到本地路径。
     * @throws RuntimeException 克隆失败时抛出，包含详细错误信息
     */
    private void cloneRepository() {
        logger.info("Cloning Git repository: {} to {}", remoteUrl, localPath);

        try {
            CloneCommand cloneCommand =
                    Git.cloneRepository()
                            .setURI(remoteUrl)
                            .setDirectory(localPath.toFile())
                            .setProgressMonitor(new LoggingProgressMonitor());

            if (branch != null) {
                cloneCommand.setBranch(branch);
                logger.info("Using branch: {}", branch);
            }

            try (Git git = cloneCommand.call()) {
                logger.info("Successfully cloned repository: {}", remoteUrl);
                ObjectId head = git.getRepository().resolve("HEAD");
                lastRemoteRef = head != null ? head.getName() : null;
            }

        } catch (TransportException e) {
            String errorMsg = e.getMessage().toLowerCase();

            if (errorMsg.contains("auth") || errorMsg.contains("authentication")) {
                throw new RuntimeException(
                        "Repository authentication failed: "
                                + remoteUrl
                                + "\n\n"
                                + "Hint: Please ensure Git credentials are configured in your"
                                + " system:\n"
                                + "  - HTTPS: Run 'git config --global credential.helper store' or"
                                + " use GitHub CLI\n"
                                + "  - SSH: Ensure SSH keys are added to ssh-agent and remote"
                                + " repository\n\n"
                                + "Verification: Run 'git clone "
                                + remoteUrl
                                + "' in terminal to test authentication",
                        e);
            } else if (errorMsg.contains("not found")
                    || errorMsg.contains("repository not found")) {
                throw new RuntimeException(
                        "Remote repository does not exist or is inaccessible: "
                                + remoteUrl
                                + "\n\n"
                                + "Please check:\n"
                                + "  1. URL is correct\n"
                                + "  2. Repository exists\n"
                                + "  3. You have access permission",
                        e);
            } else {
                throw new RuntimeException(
                        "Unable to connect to remote repository: "
                                + remoteUrl
                                + "\n"
                                + "Please check network connection and URL correctness\n"
                                + "Error details: "
                                + e.getMessage(),
                        e);
            }

        } catch (InvalidRemoteException e) {
            if (branch != null) {
                throw new RuntimeException(
                        "Specified branch does not exist: "
                                + branch
                                + "\n"
                                + "Repository: "
                                + remoteUrl
                                + "\n\n"
                                + "Solutions:\n"
                                + "  1. Check if branch name is correct\n"
                                + "  2. Use default branch (do not specify branch parameter)",
                        e);
            } else {
                throw new RuntimeException(
                        "Invalid remote repository address: "
                                + remoteUrl
                                + "\n"
                                + "Please check URL format",
                        e);
            }

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to clone repository: "
                            + remoteUrl
                            + "\n"
                            + "Error details: "
                            + e.getMessage(),
                    e);
        }
    }

    /**
     * 从远端 Git 仓库拉取更新。
     * @throws RuntimeException 拉取失败时抛出
     */
    private void pullRepository() {
        logger.debug("Pulling updates from Git repository: {}", remoteUrl);

        try (Git git = Git.open(localPath.toFile())) {
            git.pull().setProgressMonitor(new LoggingProgressMonitor()).call();

            logger.info("Successfully pulled updates from: {}", remoteUrl);
            lastRemoteRef = resolveLocalHead();

        } catch (TransportException e) {
            throw new RuntimeException(
                    "Unable to pull updates from remote repository: "
                            + remoteUrl
                            + "\n"
                            + "Please check network connection\n"
                            + "Error details: "
                            + e.getMessage(),
                    e);

        } catch (IOException e) {
            throw new RuntimeException(
                    "Unable to open local Git repository: "
                            + localPath
                            + "\n"
                            + "Local repository may be corrupted, suggest deleting local cache\n"
                            + "Error details: "
                            + e.getMessage(),
                    e);

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to pull updates: "
                            + remoteUrl
                            + "\n"
                            + "Local repository may have issues, suggest deleting local cache: "
                            + localPath
                            + "\n"
                            + "Error details: "
                            + e.getMessage(),
                    e);
        }
    }

    private boolean hasRemoteUpdates() {
        String remoteRef = resolveRemoteHead();
        if (remoteRef == null) {
            return true;
        }
        if (lastRemoteRef == null) {
            lastRemoteRef = resolveLocalHead();
        }
        return !Objects.equals(remoteRef, lastRemoteRef);
    }

    private String resolveRemoteHead() {
        try {
            LsRemoteCommand command = Git.lsRemoteRepository().setRemote(remoteUrl).setHeads(true);
            Iterable<Ref> refs = command.call();
            Optional<Ref> target =
                    StreamSupport.stream(refs.spliterator(), false)
                            .filter(this::matchesBranch)
                            .findFirst();
            if (target.isEmpty()) {
                return null;
            }
            ObjectId id = target.get().getObjectId();
            return id != null ? id.getName() : null;
        } catch (Exception e) {
            logger.debug("Failed to resolve remote head, will perform pull", e);
            return null;
        }
    }

    private boolean matchesBranch(Ref ref) {
        if (branch == null || branch.isBlank()) {
            return true;
        }
        String expected = "refs/heads/" + branch;
        return expected.equals(ref.getName());
    }

    private String resolveLocalHead() {
        try (Git git = Git.open(localPath.toFile())) {
            ObjectId head = git.getRepository().resolve("HEAD");
            return head != null ? head.getName() : null;
        } catch (Exception e) {
            logger.debug("Failed to resolve local head", e);
            return null;
        }
    }

    /**
     * 将 Git 操作进度写入日志的自定义监视器。
     */
    private static class LoggingProgressMonitor implements ProgressMonitor {
        private String currentTask;
        private int totalWork;
        private int completed;

        @Override
        public void start(int totalTasks) {
            logger.debug("Git operation started with {} tasks", totalTasks);
        }

        @Override
        public void beginTask(String title, int totalWork) {
            this.currentTask = title;
            this.totalWork = totalWork;
            this.completed = 0;
            logger.debug("Git task started: {}", title);
        }

        @Override
        public void update(int completed) {
            this.completed += completed;
            if (totalWork > 0 && logger.isDebugEnabled()) {
                int percentage = (this.completed * 100) / totalWork;
                logger.debug(
                        "Git progress [{}]: {}/{} ({}%)",
                        currentTask, this.completed, totalWork, percentage);
            }
        }

        @Override
        public void endTask() {
            logger.debug("Git task completed: {}", currentTask);
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void showDuration(boolean enabled) {
            // 本实现不使用此回调。
        }
    }
}
