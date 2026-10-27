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
package io.agentscope.harness.agent.filesystem.remote;

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
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.agentscope.harness.agent.filesystem.util.FilesystemUtils;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 由 {@link BaseStore} 支撑的 {@link AbstractFilesystem}（持久化、可跨线程使用）。
 *
 * <p>文件通过命名空间组织，并跨线程／会话持久保存。命名空间可以是静态的（构造时固定），也可以是动态的（每次操作通过 {@link NamespaceFactory} 解析）。
 *
 * <p>动态命名空间示例：
 *
 * <pre>{@code
 * RemoteFilesystem fs = new RemoteFilesystem(store,
 *     () -> List.of("sessions", sessionIdSupplier.get(), "filesystem"));
 * }</pre>
 */
public class RemoteFilesystem implements AbstractFilesystem {

    private final BaseStore store;
    private final NamespaceFactory namespaceFactory;

    /**
     * 可选的尽力而为本地索引。非空时，{@code ls}、{@code glob}、{@code exists}
     * 和 {@code grep} 先查索引，只有索引没有匹配条目时才回退到远程存储。
     */
    private WorkspaceIndex index;

    /**
     * 创建使用 {@link NamespaceFactory} 的 RemoteFilesystem；每次操作都会调用该工厂，允许根据运行时上下文改变命名空间。
     *
     * @param store 用于持久化的存储
     * @param namespaceFactory 每次操作返回命名空间元组的工厂
     */
    public RemoteFilesystem(BaseStore store, NamespaceFactory namespaceFactory) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        if (namespaceFactory == null) {
            throw new IllegalArgumentException("namespaceFactory must not be null");
        }
        this.store = store;
        this.namespaceFactory = namespaceFactory;
    }

    /**
     * 创建使用固定命名空间的 RemoteFilesystem。
     *
     * @param store 用于持久化的存储
     * @param namespace 用于组织文件的命名空间元组
     */
    public RemoteFilesystem(BaseStore store, List<String> namespace) {
        this(store, toFactory(namespace));
    }

    /**
     * 创建使用默认 "filesystem" 命名空间的 RemoteFilesystem。
     *
     * @param store 用于持久化的存储
     */
    public RemoteFilesystem(BaseStore store) {
        this(store, List.of("filesystem"));
    }

    /**
     * 附加尽力而为的 {@link WorkspaceIndex}，以加速列表、glob、存在性检查和 grep 操作。
     * 索引可以为 {@code null}（禁用），也可能过期。
     *
     * <p>各操作的回退语义不同：
     *
     * <ul>
     *   <li>{@code ls}、{@code glob}、{@code exists}：索引没有匹配前缀时，回退到完整远程存储扫描；上游以此处理本节点索引过期的情况。
     *   <li>{@code grep}：索引非 {@code null} 时，先从索引枚举候选项。如果索引路径没有产生匹配，则回退到完整远程存储扫描，避免遗漏本节点尚未获知的其他节点写入。每个候选项的内容始终从远程存储这一权威来源获取。
     * </ul>
     *
     * <p>【学习提示】这里的回退条件是“没有匹配”，而非“索引覆盖完整”。索引已有部分命中时不会自动补扫全部远程文件，因此不能把尽力而为索引误认为强一致的完整视图。
     *
     * @param index 工作区索引；{@code null} 禁用基于索引的快速路径
     * @return 当前实例，支持链式调用
     */
    public RemoteFilesystem withIndex(WorkspaceIndex index) {
        this.index = index;
        return this;
    }

    private static NamespaceFactory toFactory(List<String> namespace) {
        if (namespace == null || namespace.isEmpty()) {
            throw new IllegalArgumentException("namespace must not be empty");
        }
        List<String> frozen = List.copyOf(namespace);
        return rc -> frozen;
    }

    @Override
    public io.agentscope.core.session.AtomicSessionStorage sessionStorage(RuntimeContext rc) {
        return new io.agentscope.harness.agent.session.StoreSessionStorage(store, getNamespace(rc));
    }

    /**
     * 此后端是否支持跨节点的文件比较并交换更新。
     */
    public boolean supportsAtomicUpdates() {
        return store.supportsAtomicSessionStorage();
    }

    /**
     * 为宿主侧任务维护注册路由元数据。注册表使用独立的存储命名空间，绝不是智能体文件系统工具可见的文件。
     * 每个解析出的作用域都有自己的键，因此并发注册不会覆盖同一共享列表。
     */
    public void registerTaskScope(RuntimeContext rc, String agentId) {
        List<String> namespace = getNamespace(rc);
        if (namespace.equals(getNamespace(RuntimeContext.empty()))) {
            return;
        }
        String key = scopeKey(namespace);
        store.put(
                taskScopeNamespace(agentId),
                key,
                Map.of(
                        "namespace",
                        List.copyOf(namespace),
                        "userId",
                        rc.getUserId() == null ? "" : rc.getUserId(),
                        "sessionId",
                        rc.getSessionId() == null ? "" : rc.getSessionId()));
    }

    /**
     * 返回用于可信任务维护的已注册上下文，包括默认上下文。
     * 这不会改变普通文件系统操作所使用的命名空间。
     */
    public List<RuntimeContext> taskMaintenanceContexts(String agentId) {
        List<RuntimeContext> contexts = new ArrayList<>();
        contexts.add(RuntimeContext.empty());
        final int pageSize = 64;
        for (int offset = 0; ; offset += pageSize) {
            List<StoreItem> page = store.search(taskScopeNamespace(agentId), pageSize, offset);
            for (StoreItem item : page) {
                Map<String, Object> value = item.value();
                RuntimeContext rc =
                        RuntimeContext.builder()
                                .userId((String) value.get("userId"))
                                .sessionId((String) value.get("sessionId"))
                                .build();
                // 忽略来自不兼容命名空间工厂或配置的条目。
                if (getNamespace(rc).equals(value.get("namespace"))) {
                    contexts.add(rc);
                }
            }
            if (page.size() < pageSize) {
                return contexts;
            }
        }
    }

    private List<String> taskScopeNamespace(String agentId) {
        return List.of(
                "harness", "task-scopes", scopeKey(getNamespace(RuntimeContext.empty())), agentId);
    }

    private static String scopeKey(List<String> namespace) {
        return namespace.stream()
                .map(
                        segment ->
                                Base64.getUrlEncoder()
                                        .withoutPadding()
                                        .encodeToString(segment.getBytes(StandardCharsets.UTF_8)))
                .collect(java.util.stream.Collectors.joining("."));
    }

    private List<String> getNamespace(RuntimeContext rc) {
        List<String> ns = namespaceFactory.getNamespace(rc);
        if (ns == null || ns.isEmpty()) {
            throw new IllegalStateException("NamespaceFactory returned null or empty namespace");
        }
        return ns;
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        String normalizedPath = path.endsWith("/") ? path : path + "/";

        // 快速路径：索引包含此前缀的条目
        if (index != null && index.hasPrefix(normalizedPath)) {
            List<String> indexPaths = index.listByPrefix(normalizedPath);
            List<FileInfo> infos = new ArrayList<>();
            Set<String> subdirs = new LinkedHashSet<>();
            for (String p : indexPaths) {
                String relative = p.substring(normalizedPath.length());
                if (relative.contains("/")) {
                    String subdirName = relative.substring(0, relative.indexOf('/'));
                    subdirs.add(normalizedPath + subdirName + "/");
                } else {
                    infos.add(FileInfo.ofFile(p, 0, ""));
                }
            }
            for (String subdir : subdirs) {
                infos.add(FileInfo.ofDir(subdir, ""));
            }
            infos.sort(Comparator.comparing(FileInfo::path));
            return LsResult.success(infos);
        }

        // 回退：完整扫描远程存储
        List<StoreItem> items = searchAllItems(runtimeContext);
        List<FileInfo> infos = new ArrayList<>();
        Set<String> subdirs = new LinkedHashSet<>();

        for (StoreItem item : items) {
            if (!item.key().startsWith(normalizedPath)) {
                continue;
            }

            String relative = item.key().substring(normalizedPath.length());

            if (relative.contains("/")) {
                String subdirName = relative.substring(0, relative.indexOf('/'));
                subdirs.add(normalizedPath + subdirName + "/");
                continue;
            }

            FileData fd = convertItemToFileData(item);
            if (fd == null) {
                continue;
            }
            int size = fd.content() != null ? fd.content().length() : 0;
            infos.add(
                    FileInfo.ofFile(
                            item.key(), size, fd.modifiedAt() != null ? fd.modifiedAt() : ""));
        }

        for (String subdir : subdirs) {
            infos.add(FileInfo.ofDir(subdir, ""));
        }

        infos.sort(Comparator.comparing(FileInfo::path));
        return LsResult.success(infos);
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        StoreItem item = store.get(getNamespace(runtimeContext), filePath);
        if (item == null) {
            return ReadResult.fail("File '" + filePath + "' not found");
        }

        FileData fileData = convertItemToFileData(item);
        if (fileData == null) {
            return ReadResult.fail("Invalid file data for '" + filePath + "'");
        }

        if (!"text".equals(FilesystemUtils.getFileType(filePath))) {
            return ReadResult.success(fileData);
        }

        String content = fileData.content();
        if (content == null || content.isBlank()) {
            return ReadResult.success(fileData);
        }

        String[] lines = content.split("\n", -1);
        int startIdx = Math.max(0, offset);
        int endIdx = limit > 0 ? Math.min(startIdx + limit, lines.length) : lines.length;

        if (startIdx >= lines.length) {
            return ReadResult.fail(
                    "Line offset " + offset + " exceeds file length (" + lines.length + " lines)");
        }

        StringBuilder sb = new StringBuilder();
        for (int i = startIdx; i < endIdx; i++) {
            if (i > startIdx) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }

        return ReadResult.success(
                new FileData(
                        sb.toString(),
                        fileData.encoding(),
                        fileData.createdAt(),
                        fileData.modifiedAt()));
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        List<String> ns = getNamespace(runtimeContext);
        FileData fileData = FileData.create(content);
        // CAS 不存在时创建：在跨节点并发写入下保持原子性。
        boolean written = store.putIfVersion(ns, filePath, fileDataToStoreValue(fileData), 0L);
        if (!written) {
            return WriteResult.fail(
                    "Cannot write to "
                            + filePath
                            + " because it already exists. Read and then make an edit,"
                            + " or write to a new path.");
        }
        return WriteResult.ok(filePath);
    }

    /**
     * 并发写入时 {@link #edit} 的最大 CAS 重试次数。
     */
    static final int EDIT_MAX_RETRIES = 5;

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

        List<String> ns = getNamespace(runtimeContext);
        // 有界 CAS 重试循环：每次尝试重新读取当前版本，
        // 版本不匹配时重试。EDIT_MAX_RETRIES 次 CAS 尝试均失败后返回冲突
        // 错误，而不是无限循环。
        for (int attempt = 0; attempt < EDIT_MAX_RETRIES; attempt++) {
            StoreItem item = store.get(ns, filePath);
            if (item == null) {
                return EditResult.fail("Error: File '" + filePath + "' not found");
            }

            FileData fileData = convertItemToFileData(item);
            if (fileData == null) {
                return EditResult.fail("Error: Invalid file data");
            }

            String content = fileData.content() != null ? fileData.content() : "";
            FilesystemUtils.ReplacementResult result =
                    FilesystemUtils.stringReplacement(content, oldString, newString, replaceAll);

            if (!result.isSuccess()) {
                return EditResult.fail(result.error());
            }

            String newContent = result.content();
            int occurrences = result.occurrences();

            FileData updated = fileData.withContent(newContent);
            boolean ok =
                    store.putIfVersion(ns, filePath, fileDataToStoreValue(updated), item.version());
            if (ok) {
                return EditResult.ok(filePath, occurrences);
            }
            // 版本不匹配：其他写入者抢先更新，重新读取并重试。
        }
        return EditResult.fail(
                "Edit conflict on '"
                        + filePath
                        + "' after "
                        + EDIT_MAX_RETRIES
                        + " retries. Another writer is concurrently modifying this file.");
    }

    @Override
    public GrepResult grep(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        String normalizedPath = normalizePath(path);

        PathMatcher globMatcher = null;
        if (glob != null && !glob.isBlank()) {
            globMatcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        }

        // 快速路径：使用索引枚举 {@code normalizedPath} 下的候选路径，
        // 再从远程存储获取每个文件内容并逐行扫描。
        // 索引仅缩小候选集合；内容始终从权威存储读取，
        // 不会读取本地磁盘，因此本地副本过期或缺失不会掩盖匹配。
        if (index != null) {
            List<String> candidates =
                    "/".equals(normalizedPath)
                            ? index.listByPrefix("")
                            : index.listByPrefix(normalizedPath);
            List<GrepMatch> matches = new ArrayList<>();
            for (String key : candidates) {
                if (!matchesPathPrefix(key, normalizedPath)) {
                    continue;
                }
                if (globMatcher != null) {
                    String fileName =
                            key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
                    if (!globMatcher.matches(java.nio.file.Path.of(fileName))) {
                        continue;
                    }
                }
                StoreItem item = store.get(getNamespace(runtimeContext), key);
                if (item == null) {
                    continue;
                }
                FileData fd = convertItemToFileData(item);
                if (fd == null || fd.content() == null) {
                    continue;
                }
                String[] lines = fd.content().split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (lines[i].contains(pattern)) {
                        matches.add(new GrepMatch(key, i + 1, lines[i]));
                    }
                }
            }
            // 索引产生了匹配则返回；否则继续扫描权威存储：
            // 在多节点部署中，本节点索引可能还不知道
            // 其他节点写入的文件，不能悄然漏掉这些文件。
            if (!matches.isEmpty()) {
                return GrepResult.success(matches);
            }
        }

        // 回退：完整扫描远程存储
        List<StoreItem> items = searchAllItems(runtimeContext);
        List<GrepMatch> matches = new ArrayList<>();
        for (StoreItem item : items) {
            String key = item.key();

            if (!matchesPathPrefix(key, normalizedPath)) {
                continue;
            }

            if (globMatcher != null) {
                String fileName = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
                if (!globMatcher.matches(java.nio.file.Path.of(fileName))) {
                    continue;
                }
            }

            FileData fd = convertItemToFileData(item);
            if (fd == null || fd.content() == null) {
                continue;
            }

            String[] lines = fd.content().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(pattern)) {
                    matches.add(new GrepMatch(key, i + 1, lines[i]));
                }
            }
        }

        return GrepResult.success(matches);
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        String normalizedPath = normalizePath(path);
        String effectivePattern = pattern.startsWith("/") ? pattern.substring(1) : pattern;

        PathMatcher matcher =
                FileSystems.getDefault()
                        .getPathMatcher(
                                "glob:"
                                        + (effectivePattern.startsWith("**")
                                                ? effectivePattern
                                                : "**/" + effectivePattern));
        // Java NIO 对以 "**/" 开头的模式要求存在路径分隔符，因此
        // 递归匹配器不会匹配直接位于搜索根目录的文件。移除递归前缀，
        // 构建第二个匹配器以覆盖根目录文件。
        String directPattern;
        if (effectivePattern.startsWith("**/")) {
            directPattern = effectivePattern.substring(3);
        } else if (effectivePattern.equals("**")) {
            directPattern = "*";
        } else {
            directPattern = effectivePattern;
        }
        PathMatcher directMatcher =
                FileSystems.getDefault().getPathMatcher("glob:" + directPattern);

        // 快速路径：索引包含此前缀的条目
        if (index != null && index.hasPrefix(normalizedPath)) {
            List<String> candidates = index.listByPrefix(normalizedPath);
            List<FileInfo> results = new ArrayList<>();
            for (String key : candidates) {
                if (!matchesPathPrefix(key, normalizedPath)) {
                    continue;
                }
                String relativePath;
                if ("/".equals(normalizedPath)) {
                    relativePath = key.startsWith("/") ? key.substring(1) : key;
                } else {
                    relativePath = key.substring(normalizedPath.length() + 1);
                }
                if (matcher.matches(Path.of(relativePath))
                        || directMatcher.matches(Path.of(relativePath))) {
                    results.add(FileInfo.ofFile(key, 0, ""));
                }
            }
            results.sort(Comparator.comparing(FileInfo::path));
            return GlobResult.success(results);
        }

        // 回退：完整扫描远程存储
        List<StoreItem> items = searchAllItems(runtimeContext);
        List<FileInfo> results = new ArrayList<>();
        for (StoreItem item : items) {
            String key = item.key();
            if (!matchesPathPrefix(key, normalizedPath)) {
                continue;
            }

            String relativePath;
            if ("/".equals(normalizedPath)) {
                relativePath = key.startsWith("/") ? key.substring(1) : key;
            } else {
                relativePath = key.substring(normalizedPath.length() + 1);
            }

            if (matcher.matches(java.nio.file.Path.of(relativePath))
                    || directMatcher.matches(java.nio.file.Path.of(relativePath))) {
                FileData fd = convertItemToFileData(item);
                int size = (fd != null && fd.content() != null) ? fd.content().length() : 0;
                String modifiedAt = (fd != null && fd.modifiedAt() != null) ? fd.modifiedAt() : "";
                results.add(FileInfo.ofFile(key, size, modifiedAt));
            }
        }

        results.sort(Comparator.comparing(FileInfo::path));
        return GlobResult.success(results);
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        List<String> ns = getNamespace(runtimeContext);
        List<FileUploadResponse> responses = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : files) {
            String filePath = entry.getKey();
            byte[] content = entry.getValue();

            String contentStr;
            String encoding;
            try {
                contentStr = FilesystemUtils.decodeUtf8Strict(content);
                encoding = "utf-8";
            } catch (CharacterCodingException e) {
                contentStr = Base64.getEncoder().encodeToString(content);
                encoding = "base64";
            }

            FileData fileData = FileData.create(contentStr, encoding);
            // 后写入者胜出：uploadFiles 是快照推送 API（会话镜像、审计日志
            // 轮转、WorkspaceManager.writeUtf8WorkspaceRelative）。需要
            // 不存在时才创建语义的调用方应使用 {@link #write}，该方法为
            // 工具侧写入路径保留 CAS 不存在时创建语义。
            store.put(ns, filePath, fileDataToStoreValue(fileData));
            responses.add(FileUploadResponse.success(filePath));
        }
        return responses;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        List<String> ns = getNamespace(runtimeContext);
        List<FileDownloadResponse> responses = new ArrayList<>();
        for (String filePath : paths) {
            StoreItem item = store.get(ns, filePath);
            if (item == null) {
                responses.add(FileDownloadResponse.fail(filePath, "file_not_found"));
                continue;
            }

            FileData fd = convertItemToFileData(item);
            if (fd == null || fd.content() == null) {
                responses.add(FileDownloadResponse.fail(filePath, "invalid file data"));
                continue;
            }

            byte[] contentBytes;
            if ("base64".equals(fd.encoding())) {
                contentBytes = Base64.getMimeDecoder().decode(fd.content());
            } else {
                contentBytes = fd.content().getBytes(StandardCharsets.UTF_8);
            }
            responses.add(FileDownloadResponse.success(filePath, contentBytes));
        }
        return responses;
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = normalizePath(path);

        // 快速路径：优先查询索引
        if (index != null) {
            if (index.exists(normalized)) {
                return true;
            }
            if (index.hasPrefix(normalized + "/")) {
                return true;
            }
            // 索引未命中：继续查询远程存储（可能尚未建立索引）
        }

        List<String> ns = getNamespace(runtimeContext);
        if (store.get(ns, normalized) != null) {
            return true;
        }
        // 还检查是否存在任意子项（类似目录）
        List<StoreItem> items = searchAllItems(runtimeContext);
        for (StoreItem item : items) {
            if (item.key().startsWith(normalized + "/")) {
                return true;
            }
        }
        return false;
    }

    // 删除或移动时也更新索引，避免残留条目
    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        AbstractFilesystem.validatePath(path);
        List<String> ns = getNamespace(runtimeContext);
        List<StoreItem> items = searchAllItems(runtimeContext);
        String normalizedPath = normalizePath(path);
        for (StoreItem item : items) {
            if (item.key().equals(normalizedPath) || item.key().startsWith(normalizedPath + "/")) {
                store.delete(ns, item.key());
                if (index != null) {
                    index.remove(item.key());
                }
            }
        }
        // 幂等：未找到也视为成功
        return WriteResult.ok(path);
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        AbstractFilesystem.validatePath(fromPath);
        AbstractFilesystem.validatePath(toPath);
        List<String> ns = getNamespace(runtimeContext);
        List<StoreItem> items = searchAllItems(runtimeContext);
        String normFrom = normalizePath(fromPath);
        String normTo = normalizePath(toPath);
        boolean found = false;
        for (StoreItem item : items) {
            String key = item.key();
            if (key.equals(normFrom) || key.startsWith(normFrom + "/")) {
                String newKey = normTo + key.substring(normFrom.length());
                store.put(ns, newKey, item.value());
                store.delete(ns, key);
                if (index != null) {
                    index.rename(key, newKey);
                }
                found = true;
            }
        }
        if (!found) {
            return WriteResult.fail("Source does not exist: " + fromPath);
        }
        return WriteResult.ok(toPath);
    }

    // ==================== 内部辅助方法 ====================

    private List<StoreItem> searchAllItems(RuntimeContext runtimeContext) {
        List<String> ns = getNamespace(runtimeContext);
        List<StoreItem> all = new ArrayList<>();
        int offset = 0;
        int pageSize = 100;
        while (true) {
            List<StoreItem> page = store.search(ns, pageSize, offset);
            if (page.isEmpty()) {
                break;
            }
            for (StoreItem item : page) {
                all.add(normalizeItemKey(item));
            }
            if (page.size() < pageSize) {
                break;
            }
            offset += pageSize;
        }
        return all;
    }

    private static StoreItem normalizeItemKey(StoreItem item) {
        String key = item.key();
        if (key != null && !key.startsWith("/")) {
            return new StoreItem("/" + key, item.value(), item.version());
        }
        return item;
    }

    private static FileData convertItemToFileData(StoreItem item) {
        if (item == null || item.value() == null) {
            return null;
        }
        Map<String, Object> value = item.value();
        Object contentObj = value.get("content");
        if (contentObj == null) {
            return null;
        }

        String content;
        if (contentObj instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append('\n');
                }
                sb.append(list.get(i));
            }
            content = sb.toString();
        } else if (contentObj instanceof String s) {
            content = s;
        } else {
            return null;
        }

        String encoding =
                value.containsKey("encoding") ? String.valueOf(value.get("encoding")) : "utf-8";
        String createdAt =
                value.containsKey("created_at") ? String.valueOf(value.get("created_at")) : null;
        String modifiedAt =
                value.containsKey("modified_at") ? String.valueOf(value.get("modified_at")) : null;

        return new FileData(content, encoding, createdAt, modifiedAt);
    }

    private static Map<String, Object> fileDataToStoreValue(FileData fd) {
        Map<String, Object> result = new HashMap<>();
        result.put("content", fd.content());
        result.put("encoding", fd.encoding());
        if (fd.createdAt() != null) {
            result.put("created_at", fd.createdAt());
        }
        if (fd.modifiedAt() != null) {
            result.put("modified_at", fd.modifiedAt());
        }
        return result;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        if (!"/".equals(normalized) && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean matchesPathPrefix(String key, String normalizedPath) {
        if ("/".equals(normalizedPath)) {
            return key.startsWith("/");
        }
        return key.equals(normalizedPath) || key.startsWith(normalizedPath + "/");
    }
}
