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
package io.agentscope.harness.agent.filesystem;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按路径前缀把文件操作路由到不同的 {@link AbstractFilesystem} 存储。
 *
 * <p>路径按路由前缀匹配，最长前缀优先；未匹配路径交给默认存储。
 *
 * <p>Composite 有意仅实现 {@link AbstractFilesystem}，作为统一的非沙箱视图，
 * 将本地工作区和远程存储路径组合在一起。此模式有意不支持 Shell 执行：
 * 跨存储路由 Shell 命令的含义不明确，而主要使用场景（按用户／会话隔离的分布式记忆）
 * 也不需要此能力。如需 Shell 执行，请直接使用沙箱文件系统
 * （{@link AbstractSandboxFilesystem}）或 {@link LocalFilesystemWithShell}。
 *
 * <p>示例：
 *
 * <pre>{@code
 * CompositeFilesystem fs = new CompositeFilesystem(
 *     localFs,
 *     Map.of("/memories/", storeFs, "/cache/", inMemoryFs)
 * );
 * fs.read(RuntimeContext.empty(), "/memories/notes.md", 0, 100);  // → storeFs.read(...)
 * fs.read(RuntimeContext.empty(), "/src/Main.java", 0, 100);      // → localFs.read(...)
 * }</pre>
 */
public class CompositeFilesystem implements AbstractFilesystem {

    @Override
    public io.agentscope.core.session.AtomicSessionStorage sessionStorage(RuntimeContext rc) {
        return routeForPath("agents/").backend().sessionStorage(rc);
    }

    private final AbstractFilesystem defaultBackend;
    private final List<RouteEntry> sortedRoutes;

    /**
     * 创建带默认后端和前缀路由的组合文件系统。
     *
     * @param defaultBackend 未匹配任何路由时使用的后端
     * @param routes 路径前缀到存储的映射；前缀必须以 {@code "/"} 开头，
     *     且应以 {@code "/"} 结尾（例如 {@code "/memories/"}）
     */
    public CompositeFilesystem(
            AbstractFilesystem defaultBackend, Map<String, AbstractFilesystem> routes) {
        if (defaultBackend == null) {
            throw new IllegalArgumentException("defaultBackend must not be null");
        }
        this.defaultBackend = defaultBackend;

        List<RouteEntry> entries = new ArrayList<>();
        if (routes != null) {
            for (Map.Entry<String, AbstractFilesystem> e : routes.entrySet()) {
                entries.add(new RouteEntry(e.getKey(), e.getValue()));
            }
        }
        entries.sort(Comparator.comparingInt((RouteEntry e) -> e.prefix().length()).reversed());
        this.sortedRoutes = List.copyOf(entries);
    }

    // ==================== 路由 ====================

    private record RouteEntry(String prefix, AbstractFilesystem backend) {}

    private record RouteResult(
            AbstractFilesystem backend, String backendPath, String routePrefix) {}

    /**
     * 返回处理 {@code path} 的后端文件系统：最长前缀匹配的路由后端，
     * 没有匹配路由时使用默认后端。
     */
    public AbstractFilesystem filesystemFor(String path) {
        return routeForPath(path).backend();
    }

    private RouteResult routeForPath(String path) {
        // 匹配前去除两侧路径的前导斜杠进行规范化，因此调用方可以传入
        // "/skills/foo"（{@link AbstractFilesystem} 契约）或 "skills/foo"
        // （{@code WorkspaceManager.writeUtf8WorkspaceRelative} 使用的约定），并路由到
        // 同一个存储。原始 {@code entry.prefix()} 保留在
        // {@link RouteResult} 中，使路径重映射结果保持前缀的存储形式。
        String matchPath = stripLeadingSlash(path);
        for (RouteEntry entry : sortedRoutes) {
            String canonicalPrefix = stripLeadingSlash(entry.prefix());
            String prefixNoSlash =
                    canonicalPrefix.endsWith("/")
                            ? canonicalPrefix.substring(0, canonicalPrefix.length() - 1)
                            : canonicalPrefix;
            if (matchPath.equals(prefixNoSlash)) {
                if (canonicalPrefix.endsWith("/")) {
                    return new RouteResult(entry.backend(), "/", entry.prefix());
                }
                String backendPath = path.startsWith("/") ? path : "/" + path;
                return new RouteResult(entry.backend(), backendPath, entry.prefix());
            }
            String normalizedPrefix =
                    canonicalPrefix.endsWith("/") ? canonicalPrefix : canonicalPrefix + "/";
            if (matchPath.startsWith(normalizedPrefix)) {
                String suffix = matchPath.substring(normalizedPrefix.length());
                String backendPath = suffix.isEmpty() ? "/" : "/" + suffix;
                return new RouteResult(entry.backend(), backendPath, entry.prefix());
            }
        }
        return new RouteResult(defaultBackend, path, null);
    }

    private static String stripLeadingSlash(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
        }
        int i = 0;
        while (i < s.length() && s.charAt(i) == '/') {
            i++;
        }
        return s.substring(i);
    }

    // ==================== 路径重映射辅助方法 ====================

    private static String prependRoute(String routePrefix, String backendPath) {
        // 精确文件路由：后端只保存一个逻辑键，因此外部可见路径
        // 就是路由前缀本身；再追加后端的 "/<file>" 会得到
        // 重复路径 "AGENTS.md/AGENTS.md"。
        if (!routePrefix.endsWith("/")) {
            return routePrefix;
        }
        String base = routePrefix.substring(0, routePrefix.length() - 1);
        return base + backendPath;
    }

    private static FileInfo remapFileInfo(FileInfo fi, String routePrefix) {
        return new FileInfo(
                prependRoute(routePrefix, fi.path()), fi.isDirectory(), fi.size(), fi.modifiedAt());
    }

    private static GrepMatch remapGrepMatch(GrepMatch m, String routePrefix) {
        return new GrepMatch(prependRoute(routePrefix, m.path()), m.line(), m.text());
    }

    private static String stripRouteFromPattern(String pattern, String routePrefix) {
        String barePattern = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        String barePrefix = routePrefix.replaceAll("^/|/$", "") + "/";
        if (barePattern.startsWith(barePrefix)) {
            return barePattern.substring(barePrefix.length());
        }
        return pattern;
    }

    // ==================== AbstractFilesystem 实现 ====================

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        RouteResult route = routeForPath(path);

        if (route.routePrefix() != null) {
            LsResult result = route.backend().ls(runtimeContext, route.backendPath());
            if (!result.isSuccess()) {
                return result;
            }
            List<FileInfo> remapped = new ArrayList<>();
            for (FileInfo fi : result.entries()) {
                remapped.add(remapFileInfo(fi, route.routePrefix()));
            }
            return LsResult.success(remapped);
        }

        if ("/".equals(path) || ".".equals(path)) {
            List<FileInfo> results = new ArrayList<>();
            LsResult defaultResult = defaultBackend.ls(runtimeContext, "/");
            if (defaultResult.isSuccess() && defaultResult.entries() != null) {
                results.addAll(defaultResult.entries());
            }
            for (RouteEntry entry : sortedRoutes) {
                if (entry.prefix().endsWith("/")) {
                    results.add(FileInfo.ofDir(entry.prefix(), ""));
                    continue;
                }
                // 精确文件路由：仅当后端存在该文件时才显示文件条目。
                String exactPath = "/" + entry.prefix();
                if (entry.backend().exists(runtimeContext, exactPath)) {
                    results.add(FileInfo.ofFile(entry.prefix(), 0L, ""));
                }
            }
            results.sort(Comparator.comparing(FileInfo::path));
            return LsResult.success(results);
        }

        return defaultBackend.ls(runtimeContext, path);
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        RouteResult route = routeForPath(filePath);
        return route.backend().read(runtimeContext, route.backendPath(), offset, limit);
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        RouteResult route = routeForPath(filePath);
        WriteResult result = route.backend().write(runtimeContext, route.backendPath(), content);
        if (result.isSuccess() && route.routePrefix() != null) {
            return WriteResult.ok(filePath);
        }
        return result;
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        RouteResult route = routeForPath(filePath);
        EditResult result =
                route.backend()
                        .edit(
                                runtimeContext,
                                route.backendPath(),
                                oldString,
                                newString,
                                replaceAll);
        if (result.isSuccess() && route.routePrefix() != null) {
            return EditResult.ok(filePath, result.occurrences());
        }
        return result;
    }

    @Override
    public GrepResult grep(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        if (path != null) {
            RouteResult route = routeForPath(path);
            if (route.routePrefix() != null) {
                GrepResult result =
                        route.backend().grep(runtimeContext, pattern, route.backendPath(), glob);
                if (!result.isSuccess()) {
                    return result;
                }
                List<GrepMatch> remapped = new ArrayList<>();
                for (GrepMatch m : result.matches()) {
                    remapped.add(remapGrepMatch(m, route.routePrefix()));
                }
                return GrepResult.success(remapped);
            }
        }

        if (path == null || "/".equals(path) || ".".equals(path)) {
            List<GrepMatch> allMatches = new ArrayList<>();
            GrepResult defaultResult = defaultBackend.grep(runtimeContext, pattern, path, glob);
            if (!defaultResult.isSuccess()) {
                return defaultResult;
            }
            if (defaultResult.matches() != null) {
                allMatches.addAll(defaultResult.matches());
            }
            for (RouteEntry entry : sortedRoutes) {
                if (!entry.prefix().endsWith("/")) {
                    // 精确文件路由：将 grep 限定到唯一可寻址文件，避免
                    // 扫描同一后端命名空间中的无关键。
                    String exactPath = "/" + entry.prefix();
                    if (!entry.backend().exists(runtimeContext, exactPath)) {
                        continue;
                    }
                    GrepResult routeResult =
                            entry.backend().grep(runtimeContext, pattern, exactPath, glob);
                    if (!routeResult.isSuccess()) {
                        return routeResult;
                    }
                    if (routeResult.matches() != null) {
                        for (GrepMatch m : routeResult.matches()) {
                            allMatches.add(remapGrepMatch(m, entry.prefix()));
                        }
                    }
                    continue;
                }
                GrepResult routeResult = entry.backend().grep(runtimeContext, pattern, "/", glob);
                if (!routeResult.isSuccess()) {
                    return routeResult;
                }
                if (routeResult.matches() != null) {
                    for (GrepMatch m : routeResult.matches()) {
                        allMatches.add(remapGrepMatch(m, entry.prefix()));
                    }
                }
            }
            return GrepResult.success(allMatches);
        }

        return defaultBackend.grep(runtimeContext, pattern, path, glob);
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        RouteResult route = routeForPath(path);

        if (route.routePrefix() != null) {
            GlobResult result = route.backend().glob(runtimeContext, pattern, route.backendPath());
            if (!result.isSuccess()) {
                return result;
            }
            List<FileInfo> remapped = new ArrayList<>();
            for (FileInfo fi : result.matches()) {
                remapped.add(remapFileInfo(fi, route.routePrefix()));
            }
            return GlobResult.success(remapped);
        }

        // 非根路径未匹配路由时，只委托给默认后端。
        // 只有根级递归 glob 才需要扫描路由。
        if (path != null && !"/".equals(path) && !".".equals(path)) {
            return defaultBackend.glob(runtimeContext, pattern, path);
        }

        List<FileInfo> results = new ArrayList<>();
        GlobResult defaultResult = defaultBackend.glob(runtimeContext, pattern, path);
        if (defaultResult.isSuccess() && defaultResult.matches() != null) {
            results.addAll(defaultResult.matches());
        }
        String effectivePattern = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        PathMatcher fileMatcher =
                FileSystems.getDefault().getPathMatcher("glob:" + effectivePattern);
        // Java NIO 的 PathMatcher 对 `**/*` 等模式要求至少有一个分隔符，因此
        // 根级文件（如 AGENTS.md、MEMORY.md、tools.json，注册为前缀中
        // 没有分隔符的精确文件路由）不能仅靠递归匹配器匹配。
        // 沿用 {@link LocalFilesystem#glob} 的做法：移除
        // 前导 `**/` 构建“直接”匹配器，使深度为 1 的条目也能匹配。原本没有
        // 递归前缀的模式保持不变。
        String directExpr;
        if (effectivePattern.startsWith("**/")) {
            directExpr = effectivePattern.substring(3);
        } else if (effectivePattern.equals("**")) {
            directExpr = "*";
        } else {
            directExpr = effectivePattern;
        }
        PathMatcher directFileMatcher =
                FileSystems.getDefault().getPathMatcher("glob:" + directExpr);
        for (RouteEntry entry : sortedRoutes) {
            if (!entry.prefix().endsWith("/")) {
                // 精确文件路由：同时检查存在性和 glob 模式匹配，确保
                // glob("*.json", "/") 不会返回 AGENTS.md 等不匹配文件。
                Path entryPath = Path.of(entry.prefix());
                if (!fileMatcher.matches(entryPath) && !directFileMatcher.matches(entryPath)) {
                    continue;
                }
                String exactPath = "/" + entry.prefix();
                if (entry.backend().exists(runtimeContext, exactPath)) {
                    results.add(FileInfo.ofFile(entry.prefix(), 0L, ""));
                }
                continue;
            }
            String routePattern = stripRouteFromPattern(pattern, entry.prefix());
            GlobResult routeResult = entry.backend().glob(runtimeContext, routePattern, "/");
            if (routeResult.isSuccess() && routeResult.matches() != null) {
                for (FileInfo fi : routeResult.matches()) {
                    results.add(remapFileInfo(fi, entry.prefix()));
                }
            }
        }
        results.sort(Comparator.comparing(FileInfo::path));
        return GlobResult.success(results);
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        FileUploadResponse[] results = new FileUploadResponse[files.size()];
        Map<AbstractFilesystem, List<IndexedFile>> batches = new HashMap<>();

        for (int i = 0; i < files.size(); i++) {
            Map.Entry<String, byte[]> file = files.get(i);
            RouteResult route = routeForPath(file.getKey());
            batches.computeIfAbsent(route.backend(), k -> new ArrayList<>())
                    .add(new IndexedFile(i, file.getKey(), route.backendPath(), file.getValue()));
        }

        for (Map.Entry<AbstractFilesystem, List<IndexedFile>> batch : batches.entrySet()) {
            List<Map.Entry<String, byte[]>> batchFiles = new ArrayList<>();
            for (IndexedFile f : batch.getValue()) {
                batchFiles.add(Map.entry(f.backendPath(), f.content()));
            }
            List<FileUploadResponse> responses =
                    batch.getKey().uploadFiles(runtimeContext, batchFiles);
            List<IndexedFile> indexed = batch.getValue();
            for (int i = 0; i < responses.size() && i < indexed.size(); i++) {
                FileUploadResponse backendResp = responses.get(i);
                String originalPath = indexed.get(i).originalPath();
                results[indexed.get(i).originalIndex()] =
                        backendResp.isSuccess()
                                ? FileUploadResponse.success(originalPath)
                                : FileUploadResponse.fail(originalPath, backendResp.error());
            }
        }

        return List.of(results);
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        FileDownloadResponse[] results = new FileDownloadResponse[paths.size()];
        Map<AbstractFilesystem, List<int[]>> batches = new HashMap<>();
        Map<AbstractFilesystem, List<String>> batchPaths = new HashMap<>();

        for (int i = 0; i < paths.size(); i++) {
            RouteResult route = routeForPath(paths.get(i));
            batches.computeIfAbsent(route.backend(), k -> new ArrayList<>()).add(new int[] {i});
            batchPaths
                    .computeIfAbsent(route.backend(), k -> new ArrayList<>())
                    .add(route.backendPath());
        }

        for (Map.Entry<AbstractFilesystem, List<String>> batch : batchPaths.entrySet()) {
            List<FileDownloadResponse> responses =
                    batch.getKey().downloadFiles(runtimeContext, batch.getValue());
            List<int[]> indices = batches.get(batch.getKey());
            for (int i = 0; i < responses.size() && i < indices.size(); i++) {
                FileDownloadResponse resp = responses.get(i);
                int origIdx = indices.get(i)[0];
                results[origIdx] =
                        resp.error() != null
                                ? FileDownloadResponse.fail(paths.get(origIdx), resp.error())
                                : FileDownloadResponse.success(paths.get(origIdx), resp.content());
            }
        }

        return List.of(results);
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        AbstractFilesystem.validatePath(path);
        RouteResult route = routeForPath(path);
        WriteResult result = route.backend().delete(runtimeContext, route.backendPath());
        if (result.isSuccess() && route.routePrefix() != null) {
            return WriteResult.ok(path);
        }
        return result;
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        AbstractFilesystem.validatePath(fromPath);
        AbstractFilesystem.validatePath(toPath);
        RouteResult srcRoute = routeForPath(fromPath);
        RouteResult dstRoute = routeForPath(toPath);

        if (srcRoute.backend() == dstRoute.backend()) {
            WriteResult result =
                    srcRoute.backend()
                            .move(runtimeContext, srcRoute.backendPath(), dstRoute.backendPath());
            if (result.isSuccess()) {
                return WriteResult.ok(toPath);
            }
            return result;
        }

        // 跨后端移动：读取 → 写入 → 删除；并非跨存储原子事务。
        var readResult = srcRoute.backend().read(runtimeContext, srcRoute.backendPath(), 0, 0);
        if (!readResult.isSuccess() || readResult.fileData() == null) {
            return WriteResult.fail("Cannot read source for cross-backend move: " + fromPath);
        }
        String content = readResult.fileData().content();
        if (content == null) {
            content = "";
        }
        WriteResult writeResult =
                dstRoute.backend().write(runtimeContext, dstRoute.backendPath(), content);
        if (!writeResult.isSuccess()) {
            return WriteResult.fail(
                    "Cross-backend move write failed for '" + toPath + "': " + writeResult.error());
        }
        srcRoute.backend().delete(runtimeContext, srcRoute.backendPath());
        return WriteResult.ok(toPath);
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        RouteResult route = routeForPath(path);
        return route.backend().exists(runtimeContext, route.backendPath());
    }

    /**
     * 返回默认存储。
     */
    public AbstractFilesystem getDefaultBackend() {
        return defaultBackend;
    }

    private record IndexedFile(
            int originalIndex, String originalPath, String backendPath, byte[] content) {}
}
