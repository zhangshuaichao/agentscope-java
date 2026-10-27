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
package io.agentscope.harness.agent.filesystem.local;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.util.FilesystemUtils;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在本地磁盘上读写文件的 {@link AbstractFilesystem} 实现。
 *
 * <p>路径解析行为由构造时传入的 {@link LocalFsMode} 控制：
 *
 * <ul>
 *   <li>{@link LocalFsMode#SANDBOXED} — 路径锚定到 {@code rootDir}，阻止 {@code ..}
 *       和根目录之外的绝对路径。等同于旧配置 {@code virtualMode=true}。
 *   <li>{@link LocalFsMode#ROOTED} — 仅接受位于所配置 {@link PathPolicy} 根目录之一的绝对路径；
 *       相对路径锚定到 {@code rootDir}。
 *   <li>{@link LocalFsMode#UNRESTRICTED} — 绝对路径原样传递；相对路径锚定到 {@code rootDir}。
 *       等同于旧配置 {@code virtualMode=false}。
 * </ul>
 */
public class LocalFilesystem implements AbstractFilesystem {

    private static final Logger log = LoggerFactory.getLogger(LocalFilesystem.class);

    private static final int DEFAULT_MAX_FILE_SIZE_MB = 10;

    private final Path cwd;
    private final LocalFsMode mode;
    private final PathPolicy pathPolicy;
    private final long maxFileSizeBytes;
    private final NamespaceFactory namespaceFactory;

    /**
     * 用于 {@link #edit} 内部“读取—修改—写入”周期的逐路径锁。
     * 以规范化后的绝对路径字符串为键，确保两个调用方即使传入不同路径，
     * 解析到相同键时，就共享同一把锁。
     *
     * <p>【学习提示】锁表属于当前文件系统实例，不是跨实例或跨进程文件锁。
     * 键只做绝对化和 normalize，没有解析符号链接，因此不同别名也不保证共享锁。
     * 它保护这里的 edit 读改写流程，不代表所有文件操作都有全局事务隔离。
     */
    private final ConcurrentHashMap<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();

    /**
     * 与 {@link #LocalFilesystem(Path)} 相同，但先对字符串执行 {@link String#strip()}，
     * 再调用 {@link Path#of(String, String...) Path.of(path)}。
     * 传入 {@code null} 与传入 {@code null} 的 {@link Path} 一样，表示当前工作目录；拒绝空白字符串。
     *
     * @param rootDir 文件系统根路径字符串；{@code null} 表示进程工作目录
     */
    public LocalFilesystem(String rootDir) {
        this(rootDirFromString(rootDir), false, DEFAULT_MAX_FILE_SIZE_MB, null);
    }

    /**
     * 创建以指定目录为根的抽象文件系统。
     *
     * @param rootDir 所有操作的根目录（{@code null} 表示当前工作目录）
     */
    public LocalFilesystem(Path rootDir) {
        this(rootDir, false, DEFAULT_MAX_FILE_SIZE_MB, null);
    }

    /**
     * 使用明确配置创建抽象文件系统。
     *
     * @param rootDir 所有操作的根目录（{@code null} 表示当前工作目录）
     * @param virtualMode 为 true 时，所有路径锚定到 rootDir 并阻止路径穿越
     * @param maxFileSizeMb 搜索操作允许的最大文件大小，单位 MB
     */
    public LocalFilesystem(Path rootDir, boolean virtualMode, int maxFileSizeMb) {
        this(rootDir, virtualMode, maxFileSizeMb, null);
    }

    /**
     * 与 {@link #LocalFilesystem(Path, boolean, int)} 相同，但接收路径字符串；
     * {@code null}／空白规则参见 {@link #LocalFilesystem(String)}。
     */
    public LocalFilesystem(String rootDir, boolean virtualMode, int maxFileSizeMb) {
        this(rootDirFromString(rootDir), virtualMode, maxFileSizeMb, null);
    }

    /**
     * 旧构造方法：把 {@code virtualMode} 映射到 {@link LocalFsMode#SANDBOXED} 或
     * {@link LocalFsMode#UNRESTRICTED}，并使用空 {@link PathPolicy}。
     * 需要 {@link LocalFsMode#ROOTED} 时，优先使用下方感知模式的构造方法。
     *
     * @param rootDir 所有操作的根目录（{@code null} 表示当前工作目录）
     * @param virtualMode 为 true 时，所有路径锚定到 rootDir 并阻止路径穿越
     * @param maxFileSizeMb 搜索操作允许的最大文件大小，单位 MB
     * @param namespaceFactory 限定路径作用域的可选命名空间工厂（{@code null} 表示不使用）
     */
    public LocalFilesystem(
            Path rootDir,
            boolean virtualMode,
            int maxFileSizeMb,
            NamespaceFactory namespaceFactory) {
        this(
                rootDir,
                virtualMode ? LocalFsMode.SANDBOXED : LocalFsMode.UNRESTRICTED,
                null,
                maxFileSizeMb,
                namespaceFactory);
    }

    /**
     * 与 {@link #LocalFilesystem(Path, boolean, int, NamespaceFactory)} 相同，但接收路径字符串；
     * {@code null}／空白规则参见 {@link #LocalFilesystem(String)}。
     */
    public LocalFilesystem(
            String rootDir,
            boolean virtualMode,
            int maxFileSizeMb,
            NamespaceFactory namespaceFactory) {
        this(rootDirFromString(rootDir), virtualMode, maxFileSizeMb, namespaceFactory);
    }

    /**
     * 使用明确模式和路径策略创建文件系统。
     *
     * <p>{@code mode} 控制智能体绝对路径的验证方式；三种选项见 {@link LocalFsMode}。
     * 在 {@link LocalFsMode#ROOTED} 模式下，仅接受位于 {@code pathPolicy} 根目录之一
     * 或 {@code rootDir} 本身之下的绝对路径；在 {@link LocalFsMode#SANDBOXED} 中，
     * 每个路径都锚定到 {@code rootDir}；在 {@link LocalFsMode#UNRESTRICTED} 中，绝对路径保持原样。
     *
     * @param rootDir 相对路径的根目录（{@code null} 表示当前工作目录）
     * @param mode 路径解析策略（{@code null} 默认使用 {@link LocalFsMode#UNRESTRICTED}）
     * @param pathPolicy {@link LocalFsMode#ROOTED} 的允许列表；其他模式忽略（{@code null} 视为空）
     * @param maxFileSizeMb 搜索操作允许的最大文件大小，单位 MB
     * @param namespaceFactory 限定路径作用域的可选命名空间工厂（{@code null} 表示不使用）
     */
    public LocalFilesystem(
            Path rootDir,
            LocalFsMode mode,
            PathPolicy pathPolicy,
            int maxFileSizeMb,
            NamespaceFactory namespaceFactory) {
        this.cwd =
                rootDir != null
                        ? rootDir.toAbsolutePath().normalize()
                        : Path.of("").toAbsolutePath();
        this.mode = mode != null ? mode : LocalFsMode.UNRESTRICTED;
        this.pathPolicy = pathPolicy != null ? pathPolicy : PathPolicy.empty();
        this.maxFileSizeBytes = (long) maxFileSizeMb * 1024 * 1024;
        this.namespaceFactory = namespaceFactory;
    }

    /**
     * 将根路径字符串转换为 {@link Path}。{@code null} 仍返回 {@code null}（当前工作目录）。
     * 非 null 值在 {@link String#strip()} 后必须非空白。
     */
    static Path rootDirFromString(String rootDir) {
        if (rootDir == null) {
            return null;
        }
        String trimmed = rootDir.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("root directory path must not be blank");
        }
        return Path.of(trimmed);
    }

    /**
     * 返回此文件系统的根目录。
     */
    public Path getCwd() {
        return cwd;
    }

    /**
     * 返回当前路径解析模式。
     */
    public LocalFsMode getMode() {
        return mode;
    }

    /**
     * 返回所配置的路径策略。{@link #getMode()} 不是 {@link LocalFsMode#ROOTED} 时为空；
     * 即使策略为空，{@link LocalFsMode#ROOTED} 仍隐式接受 {@link #getCwd()} 下的路径。
     */
    public PathPolicy getPathPolicy() {
        return pathPolicy;
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        Path dirPath = resolvePath(runtimeContext, path);
        if (!Files.exists(dirPath)) {
            return LsResult.fail("Path does not exist: " + path);
        }
        if (!Files.isDirectory(dirPath)) {
            return LsResult.fail("Not a directory: " + path);
        }

        List<FileInfo> results = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dirPath)) {
            for (Path entry : ds) {
                try {
                    BasicFileAttributes attrs =
                            Files.readAttributes(entry, BasicFileAttributes.class);
                    String entryPath = resolveEntryPath(runtimeContext, entry);
                    String modifiedAt =
                            Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis()).toString();

                    if (attrs.isDirectory()) {
                        results.add(FileInfo.ofDir(entryPath + "/", modifiedAt));
                    } else {
                        results.add(FileInfo.ofFile(entryPath, attrs.size(), modifiedAt));
                    }
                } catch (IOException e) {
                    log.debug("Skipping unreadable entry: {}", entry);
                }
            }
        } catch (IOException e) {
            log.warn("ls failed for {}: {}", path, e.getMessage());
        }

        results.sort(Comparator.comparing(FileInfo::path));
        return LsResult.success(results);
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        Path resolved = resolvePath(runtimeContext, filePath);

        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            return ReadResult.fail("File '" + filePath + "' not found");
        }

        try {
            if (!"text".equals(FilesystemUtils.getFileType(filePath))) {
                byte[] raw = Files.readAllBytes(resolved);
                String encoded = Base64.getEncoder().encodeToString(raw);
                return ReadResult.success(new FileData(encoded, "base64"));
            }

            String content = Files.readString(resolved, StandardCharsets.UTF_8);

            if (content.isEmpty() || content.isBlank()) {
                return ReadResult.success(
                        new FileData(
                                "System reminder: File exists but has empty contents", "utf-8"));
            }

            String[] lines = content.split("\n", -1);
            int startIdx = Math.max(0, offset);
            int endIdx = limit > 0 ? Math.min(startIdx + limit, lines.length) : lines.length;

            if (startIdx >= lines.length) {
                return ReadResult.fail(
                        "Line offset "
                                + offset
                                + " exceeds file length ("
                                + lines.length
                                + " lines)");
            }

            StringBuilder sb = new StringBuilder();
            for (int i = startIdx; i < endIdx; i++) {
                if (i > startIdx) {
                    sb.append('\n');
                }
                sb.append(lines[i]);
            }
            return ReadResult.success(new FileData(sb.toString(), "utf-8"));

        } catch (IOException e) {
            return ReadResult.fail("Error reading file '" + filePath + "': " + e.getMessage());
        }
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        Path resolved = resolvePath(runtimeContext, filePath);

        if (Files.exists(resolved)) {
            return WriteResult.fail(
                    "Cannot write to "
                            + filePath
                            + " because it already exists. Read and then make an edit,"
                            + " or write to a new path.");
        }

        try {
            if (resolved.getParent() != null) {
                Files.createDirectories(resolved.getParent());
            }
            Files.writeString(resolved, content, StandardCharsets.UTF_8);
            return WriteResult.ok(filePath);
        } catch (IOException e) {
            return WriteResult.fail("Error writing file '" + filePath + "': " + e.getMessage());
        }
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        EditResult invalid = FilesystemUtils.validateEditArguments(oldString, newString);
        if (invalid != null) {
            return invalid;
        }

        Path resolved = resolvePath(runtimeContext, filePath);

        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            return EditResult.fail("Error: File '" + filePath + "' not found");
        }

        // 串行执行同一文件的并发编辑，防止丢失更新。
        String lockKey = resolved.toAbsolutePath().normalize().toString();
        ReentrantLock lock = fileLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        lock.lock();
        try {
            // 换行规范化统一放在 FilesystemUtils.stringReplacement 中，使每种
            // 文件系统都遵循 LF 语义；内容据此写回。
            String content = Files.readString(resolved, StandardCharsets.UTF_8);

            FilesystemUtils.ReplacementResult result =
                    FilesystemUtils.stringReplacement(content, oldString, newString, replaceAll);

            if (!result.isSuccess()) {
                return EditResult.fail(result.error());
            }

            String newContent = result.content();
            int occurrences = result.occurrences();

            Files.writeString(resolved, newContent, StandardCharsets.UTF_8);
            return EditResult.ok(filePath, occurrences);
        } catch (IOException e) {
            return EditResult.fail("Error editing file '" + filePath + "': " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public GrepResult grep(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        Path basePath;
        try {
            basePath = resolvePath(runtimeContext, path != null ? path : ".");
        } catch (SecurityException e) {
            return GrepResult.success(List.of());
        }

        if (!Files.exists(basePath)) {
            return GrepResult.success(List.of());
        }

        List<GrepMatch> matches = ripgrepSearch(runtimeContext, pattern, basePath, glob);
        if (matches == null) {
            matches = javaSearch(runtimeContext, pattern, basePath, glob);
        }
        return GrepResult.success(matches);
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        String effectivePattern = pattern;
        if (effectivePattern.startsWith("/")) {
            effectivePattern = effectivePattern.substring(1);
        }

        Path searchPath;
        if ("/".equals(path) || path == null) {
            searchPath = hasNamespace(runtimeContext) ? resolvePath(runtimeContext, ".") : cwd;
        } else {
            searchPath = resolvePath(runtimeContext, path);
        }

        if (!Files.exists(searchPath) || !Files.isDirectory(searchPath)) {
            return GlobResult.success(List.of());
        }

        String globExpr =
                effectivePattern.startsWith("**") ? effectivePattern : "**/" + effectivePattern;
        FileSystem fs = FileSystems.getDefault();
        PathMatcher matcher = fs.getPathMatcher("glob:" + globExpr);
        // Java 的 PathMatcher 对 `**/<x>` 要求至少有一个分隔符，因此深度为 1 的文件
        // 无法匹配 `**/*` 等模式。去除前导 `**/`，使直接匹配器
        // 也能匹配搜索根目录中的文件。
        String directExpr;
        if (effectivePattern.startsWith("**/")) {
            directExpr = effectivePattern.substring(3);
        } else if (effectivePattern.equals("**")) {
            directExpr = "*";
        } else {
            directExpr = effectivePattern;
        }
        PathMatcher directMatcher = fs.getPathMatcher("glob:" + directExpr);

        List<FileInfo> results = new ArrayList<>();
        try {
            Files.walkFileTree(
                    searchPath,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            Path rel = searchPath.relativize(file);
                            if (matcher.matches(rel) || directMatcher.matches(rel)) {
                                String filePath = resolveEntryPath(runtimeContext, file);
                                String modifiedAt =
                                        Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis())
                                                .toString();
                                results.add(FileInfo.ofFile(filePath, attrs.size(), modifiedAt));
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            log.warn("glob failed for {}: {}", pattern, e.getMessage());
        }

        results.sort(Comparator.comparing(FileInfo::path));
        return GlobResult.success(results);
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        List<FileUploadResponse> responses = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : files) {
            String filePath = entry.getKey();
            byte[] content = entry.getValue();
            try {
                Path resolved = resolvePath(runtimeContext, filePath);
                if (resolved.getParent() != null) {
                    Files.createDirectories(resolved.getParent());
                }
                Files.write(resolved, content);
                responses.add(FileUploadResponse.success(filePath));
            } catch (IOException e) {
                responses.add(FileUploadResponse.fail(filePath, e.getMessage()));
            } catch (SecurityException | IllegalArgumentException e) {
                responses.add(FileUploadResponse.fail(filePath, "permission_denied"));
            }
        }
        return responses;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        List<FileDownloadResponse> responses = new ArrayList<>();
        for (String filePath : paths) {
            try {
                Path resolved = resolvePath(runtimeContext, filePath);
                if (!Files.exists(resolved)) {
                    responses.add(FileDownloadResponse.fail(filePath, "file_not_found"));
                    continue;
                }
                if (Files.isDirectory(resolved)) {
                    responses.add(FileDownloadResponse.fail(filePath, "is_directory"));
                    continue;
                }
                byte[] content = Files.readAllBytes(resolved);
                responses.add(FileDownloadResponse.success(filePath, content));
            } catch (IOException e) {
                responses.add(FileDownloadResponse.fail(filePath, e.getMessage()));
            } catch (SecurityException e) {
                responses.add(FileDownloadResponse.fail(filePath, "permission_denied"));
            }
        }
        return responses;
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        AbstractFilesystem.validatePath(path);
        Path resolved = resolvePath(runtimeContext, path);
        if (!Files.exists(resolved)) {
            return WriteResult.ok(path); // 幂等操作
        }
        try {
            if (Files.isDirectory(resolved)) {
                try (Stream<Path> walk = Files.walk(resolved)) {
                    walk.sorted(Comparator.reverseOrder())
                            .forEach(
                                    p -> {
                                        try {
                                            Files.delete(p);
                                        } catch (IOException e) {
                                            log.warn("Failed to delete {}: {}", p, e.getMessage());
                                        }
                                    });
                }
            } else {
                Files.delete(resolved);
            }
            return WriteResult.ok(path);
        } catch (IOException e) {
            return WriteResult.fail("Error deleting '" + path + "': " + e.getMessage());
        }
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        AbstractFilesystem.validatePath(fromPath);
        AbstractFilesystem.validatePath(toPath);
        Path from = resolvePath(runtimeContext, fromPath);
        Path to = resolvePath(runtimeContext, toPath);
        if (!Files.exists(from)) {
            return WriteResult.fail("Source does not exist: " + fromPath);
        }
        try {
            if (to.getParent() != null) {
                Files.createDirectories(to.getParent());
            }
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            return WriteResult.ok(toPath);
        } catch (IOException e) {
            return WriteResult.fail(
                    "Error moving '" + fromPath + "' to '" + toPath + "': " + e.getMessage());
        }
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        try {
            return Files.exists(resolvePath(runtimeContext, path));
        } catch (SecurityException e) {
            return false;
        }
    }

    // ==================== 路径解析 ====================

    protected NamespaceFactory getNamespaceFactory() {
        return namespaceFactory;
    }

    @Override
    public io.agentscope.core.session.AtomicSessionStorage sessionStorage(RuntimeContext rc) {
        return new io.agentscope.harness.agent.session.LocalSessionStorage(
                resolvePath(rc, ".").resolve(".agentscope-runtime"));
    }

    protected Path resolvePath(RuntimeContext rc, String key) {
        if (key != null
                && java.util.Arrays.stream(key.replace('\\', '/').split("/"))
                        .anyMatch(".agentscope-runtime"::equalsIgnoreCase))
            throw new SecurityException("Session journal is reserved for the runtime");
        String effectiveKey = applyNamespacePrefix(rc, key);
        if (effectiveKey == null || effectiveKey.isBlank()) {
            return cwd;
        }

        return switch (mode) {
            case SANDBOXED -> resolveSandboxed(effectiveKey);
            case ROOTED -> resolveRooted(effectiveKey);
            case UNRESTRICTED -> resolveUnrestricted(effectiveKey);
        };
    }

    private Path resolveSandboxed(String effectiveKey) {
        String vpath = effectiveKey.startsWith("/") ? effectiveKey : "/" + effectiveKey;
        if (vpath.contains("..") || vpath.startsWith("~")) {
            throw new SecurityException("Path traversal not allowed");
        }
        // 去除 Windows 盘符前缀（"C:\"／"C:/"），使 Windows 绝对路径
        // 与 Unix 绝对路径一样重新锚定到沙箱根；Unix 输入不受影响。
        Path full = cwd.resolve(stripWindowsDrive(vpath.substring(1))).normalize();
        if (!full.startsWith(cwd)) {
            throw new SecurityException("Path " + full + " outside root directory: " + cwd);
        }
        return full;
    }

    private static String stripWindowsDrive(String key) {
        if (key.length() >= 3
                && Character.isLetter(key.charAt(0))
                && key.charAt(1) == ':'
                && (key.charAt(2) == '\\' || key.charAt(2) == '/')) {
            return key.substring(3);
        }
        return key;
    }

    private Path resolveRooted(String effectiveKey) {
        AbstractFilesystem.validatePath(effectiveKey);
        Path target = Path.of(effectiveKey);
        if (target.isAbsolute()) {
            Path normalized = target.normalize();
            if (normalized.startsWith(cwd) || pathPolicy.isAllowed(normalized)) {
                return normalized;
            }
            if (Files.exists(normalized)) {
                throw rootAccessDenied(normalized);
            }
            if (!effectiveKey.startsWith("/")) {
                throw rootAccessDenied(normalized);
            }
        }

        if (effectiveKey.startsWith("/")) {
            String stripped = effectiveKey.substring(1);
            if (stripped.isEmpty()) {
                return cwd;
            }
            if (stripped.startsWith("~")) {
                throw new SecurityException("Path traversal not allowed: " + effectiveKey);
            }
            Path full = cwd.resolve(stripped).normalize();
            if (!full.startsWith(cwd)) {
                throw new SecurityException("Path " + full + " outside root directory: " + cwd);
            }
            return full;
        }

        Path full = cwd.resolve(target).normalize();
        if (!full.startsWith(cwd)) {
            throw new SecurityException("Path " + full + " outside root directory: " + cwd);
        }
        return full;
    }

    private SecurityException rootAccessDenied(Path normalized) {
        return new SecurityException(
                "Absolute path "
                        + normalized
                        + " is not within an allowed root. Filesystem root: "
                        + cwd
                        + "; additional roots: "
                        + pathPolicy.roots());
    }

    private Path resolveUnrestricted(String effectiveKey) {
        Path target = Path.of(effectiveKey);
        if (target.isAbsolute()) {
            return target;
        }
        return cwd.resolve(target).normalize();
    }

    private String applyNamespacePrefix(RuntimeContext rc, String key) {
        if (namespaceFactory == null || key == null || key.isBlank()) {
            return key;
        }
        // 绝对路径指定宿主机上的特定位置，不能再添加命名空间作用域。
        // 添加命名空间前缀会把它变成相对路径，导致它在工作区根下
        // 错误解析（例如 /abs/path → ns//abs/path）。
        // Windows 绝对路径以盘符（"C:\"）或 UNC 前缀（"\\"）开头，
        // 而非 "/"，因此必须显式检查这些形式。
        if (isAbsolutePathString(key)) {
            return key;
        }
        List<String> ns = namespaceFactory.getNamespace(rc);
        if (ns == null || ns.isEmpty()) {
            return key;
        }
        String prefix = String.join("/", ns);
        return prefix + "/" + key;
    }

    /**
     * 当 {@code key} 看起来是任一支持的操作系统上的绝对路径时返回 {@code true}：
     * Unix（{@code /...}）、Windows 盘符（{@code C:\} 或 {@code C:/}），
     * 或 Windows UNC（{@code \\server\share}）。
     */
    private static boolean isAbsolutePathString(String key) {
        if (key.startsWith("/")) {
            return true;
        }
        // Windows 盘符路径："X:\" 或 "X:/"
        if (key.length() >= 3
                && Character.isLetter(key.charAt(0))
                && key.charAt(1) == ':'
                && (key.charAt(2) == '\\' || key.charAt(2) == '/')) {
            return true;
        }
        // Windows UNC 路径："\\server\share"
        return key.startsWith("\\\\");
    }

    protected String toVirtualPath(Path path) {
        return "/"
                + path.toAbsolutePath()
                        .normalize()
                        .toString()
                        .substring(cwd.toString().length())
                        .replace('\\', '/')
                        .replaceFirst("^/+", "");
    }

    private boolean hasNamespace(RuntimeContext rc) {
        if (namespaceFactory == null) {
            return false;
        }
        List<String> ns = namespaceFactory.getNamespace(rc);
        return ns != null && !ns.isEmpty();
    }

    private String resolveEntryPath(RuntimeContext rc, Path entry) {
        if (mode == LocalFsMode.SANDBOXED) {
            return toVirtualPath(entry);
        }
        if (hasNamespace(rc)) {
            return stripNamespacePrefix(rc, entry);
        }
        return toCwdRelativePath(entry);
    }

    private String toCwdRelativePath(Path path) {
        return cwd.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private String stripNamespacePrefix(RuntimeContext rc, Path absolutePath) {
        String relPath = toCwdRelativePath(absolutePath);
        String nsPrefix = String.join("/", namespaceFactory.getNamespace(rc));
        if (relPath.startsWith(nsPrefix + "/")) {
            return relPath.substring(nsPrefix.length() + 1);
        }
        return relPath;
    }

    // ==================== Grep 实现 ====================

    private List<GrepMatch> ripgrepSearch(
            RuntimeContext rc, String pattern, Path basePath, String includeGlob) {
        List<String> cmd = new ArrayList<>();
        cmd.add("rg");
        cmd.add("--json");
        cmd.add("-F");
        if (includeGlob != null && !includeGlob.isBlank()) {
            cmd.add("--glob");
            cmd.add(includeGlob);
        }
        cmd.add("--");
        cmd.add(pattern);
        cmd.add(basePath.toString());

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            List<GrepMatch> matches = new ArrayList<>();
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    GrepMatch match = parseRipgrepJsonLine(rc, line);
                    if (match != null) {
                        matches.add(match);
                    }
                }
            }

            proc.waitFor();
            return matches;

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private GrepMatch parseRipgrepJsonLine(RuntimeContext rc, String jsonLine) {
        try {
            if (!jsonLine.contains("\"type\":\"match\"")) {
                return null;
            }
            String pathText = extractJsonStringField(jsonLine, "text", "path");
            String lineNumStr = extractJsonField(jsonLine, "line_number");
            String linesText = extractJsonStringField(jsonLine, "text", "lines");

            if (pathText == null || lineNumStr == null) {
                return null;
            }
            String filePath = resolveEntryPath(rc, Path.of(pathText));
            int lineNum = Integer.parseInt(lineNumStr.trim());
            String text = linesText != null ? linesText.replaceAll("[\r\n]+$", "") : "";
            return new GrepMatch(filePath, lineNum, text);
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractJsonStringField(String json, String field, String parentField) {
        String searchKey = "\"" + parentField + "\":{";
        int parentIdx = json.indexOf(searchKey);
        if (parentIdx < 0) {
            return extractSimpleJsonString(json, field);
        }
        String sub = json.substring(parentIdx + searchKey.length());
        return extractSimpleJsonString(sub, field);
    }

    private static String extractSimpleJsonString(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) {
            return null;
        }
        start += key.length();
        int end = json.indexOf('"', start);
        if (end < 0) {
            return null;
        }
        return json.substring(start, end);
    }

    private static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\":";
        int start = json.indexOf(key);
        if (start < 0) {
            return null;
        }
        start += key.length();
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        return json.substring(start, end).trim();
    }

    private List<GrepMatch> javaSearch(
            RuntimeContext rc, String pattern, Path basePath, String includeGlob) {
        Pattern compiledPattern = Pattern.compile(Pattern.quote(pattern));
        PathMatcher globMatcher = null;
        if (includeGlob != null && !includeGlob.isBlank()) {
            globMatcher = FileSystems.getDefault().getPathMatcher("glob:" + includeGlob);
        }

        List<GrepMatch> matches = new ArrayList<>();
        Path root = Files.isDirectory(basePath) ? basePath : basePath.getParent();

        try (Stream<Path> walk = Files.walk(root)) {
            PathMatcher finalGlobMatcher = globMatcher;
            walk.filter(Files::isRegularFile)
                    .filter(
                            p -> {
                                if (finalGlobMatcher != null) {
                                    return finalGlobMatcher.matches(p.getFileName());
                                }
                                return true;
                            })
                    .filter(
                            p -> {
                                try {
                                    return Files.size(p) <= maxFileSizeBytes;
                                } catch (IOException e) {
                                    return false;
                                }
                            })
                    .forEach(
                            file -> {
                                try {
                                    List<String> lines =
                                            Files.readAllLines(file, StandardCharsets.UTF_8);
                                    for (int i = 0; i < lines.size(); i++) {
                                        if (compiledPattern.matcher(lines.get(i)).find()) {
                                            String filePath = resolveEntryPath(rc, file);
                                            matches.add(
                                                    new GrepMatch(filePath, i + 1, lines.get(i)));
                                        }
                                    }
                                } catch (IOException e) {
                                    // 跳过二进制或不可读文件
                                }
                            });
        } catch (IOException e) {
            log.warn("Java search failed: {}", e.getMessage());
        }

        return matches;
    }
}
