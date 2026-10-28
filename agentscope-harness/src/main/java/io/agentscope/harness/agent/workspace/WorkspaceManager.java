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
package io.agentscope.harness.agent.workspace;

import static io.agentscope.harness.agent.workspace.WorkspaceConstants.AGENTS_DIR;
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.AGENTS_MD;
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.KNOWLEDGE_DIR;
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.KNOWLEDGE_MD;
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.MEMORY_DIR;
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.MEMORY_MD;
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.SESSIONS_DIR;
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.SKILLS_DIR;
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.TASKS_DIR;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 采用双层读取架构的无状态工作区内容访问器。
 *
 * <p><strong>读取路径：</strong>每次读取 AGENTS.md、MEMORY.md、知识等内容时，先查询
 * {@link AbstractFilesystem}；若返回非空内容则采用它（文件系统覆盖层优先），
 * 否则回退到本地工作区磁盘。文件系统层通过 {@link NamespaceFactory} 透明应用用户/会话作用域。
 *
 * <p><strong>写入路径：</strong>记忆、会话等写入统一经由 {@link AbstractFilesystem} 抽象。
 * 未配置文件系统层时，相关方法直接使用本地工作区。
 *
 * <p><strong>列表：</strong>记忆文件、知识文件和会话日志等列表合并文件系统层与本地磁盘的结果，
 * 按相对路径去重。
 *
 * <p>预期布局：
 * <pre>
 * workspace/
 * ├── AGENTS.md
 * ├── MEMORY.md
 * ├── memory/YYYY-MM-DD.md
 * ├── skills/&lt;skill-name&gt;/SKILL.md
 * ├── knowledge/KNOWLEDGE.md
 * ├── knowledge/*
 * ├── subagents/&lt;id&gt;.md                     (subagent declarations)
 * ├── agents/&lt;agentId&gt;/workspace/           (isolated subagent runtime root, auto-created)
 * ├── agents/&lt;agentId&gt;/sessions/sessions.json
 * └── .agentscope-runtime/ — private native session journal (local backend)
 * </pre>
 *
 * <p>目录图中文说明：AGENTS.md 是工作约定，MEMORY.md 与按日 memory 文件承载记忆；skills 保存技能，knowledge 保存知识。subagents 下是子智能体声明；agents/agentId/workspace 是自动建立的隔离运行根，sessions/sessions.json 是会话索引；.agentscope-runtime 是本地后端的私有原生会话日志目录。
 *
 * <p>【学习提示】双层读取的回退规则不等于覆盖文件一定存在；“非空才覆盖”也意味着空内容可能触发本地回退。
 * 阅读隔离问题时，应同时查看运行时命名空间、路由后端和本地回退路径。
 */
public class WorkspaceManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);
    private static final ObjectMapper TASK_RECORD_JSON =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final TypeReference<Map<String, TaskRecord>> TASK_MAP_TYPE =
            new TypeReference<>() {};

    /**
     * 工作区相对文件的逐路径锁，用于避免并发读—改—写竞争。
     * 以工作区相对路径为键，例如 {@code agents/X/tasks/Y.json}、
     * {@code agents/X/sessions/sessions.json}、{@code memory/YYYY-MM-DD.md}。
     *
     * <p>这只是进程内锁。普通工作区文件的读—改—写跨副本采用最后写入者获胜语义，
     * 不能因 {@code WorkspaceManager} 持有路径锁就推断存在服务端 CAS。
     * 真正的乐观并发能力位于 {@code BaseStore#putIfVersion} 和
     * {@code AgentStateStore#saveIfVersion}；具体任务记录的远程路由还需查看其后端变更路径。
     */
    private final Map<String, ReentrantLock> pathLocks = new ConcurrentHashMap<>();

    private final Path workspace;
    private final AbstractFilesystem filesystem;

    /** 尽力而为的本地文件索引；SQLite 不可用时可能为 {@code null}。 */
    private final WorkspaceIndex index;

    private final NamespaceFactory namespaceFactory;

    /**
     * {@code true} 表示本管理器自行分配了 {@link #index}，所用构造器为 {@code (workspace,
     * filesystem)}，
     * 并负责关闭索引。外部传入索引时，例如由 {@link io.agentscope.harness.agent.HarnessAgent} 的构建器提供，
     * 其生命周期由外部拥有者管理，此处 {@link #close()} 不执行关闭。
     */
    private final boolean ownsIndex;

    public WorkspaceManager(Path workspace) {
        this(workspace, null, null, null, false);
    }

    public WorkspaceManager(Path workspace, AbstractFilesystem filesystem) {
        this(workspace, filesystem, WorkspaceIndex.open(workspace), null, true);
    }

    public WorkspaceManager(Path workspace, AbstractFilesystem filesystem, WorkspaceIndex index) {
        this(workspace, filesystem, index, null, false);
    }

    public WorkspaceManager(
            Path workspace,
            AbstractFilesystem filesystem,
            WorkspaceIndex index,
            NamespaceFactory namespaceFactory) {
        this(workspace, filesystem, index, namespaceFactory, false);
    }

    private WorkspaceManager(
            Path workspace,
            AbstractFilesystem filesystem,
            WorkspaceIndex index,
            NamespaceFactory namespaceFactory,
            boolean ownsIndex) {
        this.workspace = workspace;
        this.filesystem = filesystem;
        this.index = index;
        this.namespaceFactory = namespaceFactory;
        this.ownsIndex = ownsIndex;
    }

    /**
     * 本管理器拥有索引时，释放基于 SQLite 的 {@link WorkspaceIndex}。
     *
     * <p>在 Windows 上使用 {@code @TempDir} 的测试需要此操作：JDBC 驱动持有
     * {@code .index/workspace.db} 的文件句柄，句柄未关闭时 Windows 拒绝删除临时目录。
     */
    @Override
    public void close() {
        if (ownsIndex && index != null) {
            index.close();
        }
    }

    public NamespaceFactory getNamespaceFactory() {
        return namespaceFactory;
    }

    /** 返回尽力而为的工作区索引；不可用时可能为 {@code null}。 */
    public WorkspaceIndex getIndex() {
        return index;
    }

    public AbstractFilesystem getFilesystem() {
        return filesystem;
    }

    /**
     * 验证工作区存在且关键文件齐备，对缺失项记录警告。
     * 在 HarnessAgent 构建时调用一次。
     */
    public void validate() {
        if (!Files.isDirectory(workspace)) {
            log.warn(
                    "Workspace directory does not exist: {}. "
                            + "Please create it and add AGENTS.md.",
                    workspace.toAbsolutePath());
            return;
        }
        boolean agentsMdExists = Files.isRegularFile(workspace.resolve(AGENTS_MD));
        if (!agentsMdExists && filesystem != null) {
            try {
                agentsMdExists = filesystem.exists(RuntimeContext.empty(), AGENTS_MD);
            } catch (Exception e) {
                log.debug(
                        "Filesystem not available at build time, skipping exists check: {}",
                        e.getMessage());
            }
        }
        if (!agentsMdExists) {
            log.warn(
                    "AGENTS.md not found in workspace: {}. "
                            + "AGENTS.md defines persona and local conventions for the agent.",
                    workspace.toAbsolutePath());
        }
    }

    public Path getWorkspace() {
        return workspace;
    }

    /**
     * 解析运行时用户数据的工作区相对路径，并应用命名空间前缀。
     * 用于包含用户级数据的路径，例如会话、任务和记忆。
     *
     * <p>将运行时上下文传给 {@link NamespaceFactory}，让每次调用的用户/会话身份决定命名空间，
     * 而不是依赖共享可变引用。
     */
    public Path resolveRuntimeDataPath(RuntimeContext rc, String relativePath) {
        if (namespaceFactory == null) {
            return workspace.resolve(relativePath);
        }
        List<String> ns = namespaceFactory.getNamespace(rc != null ? rc : RuntimeContext.empty());
        if (ns == null || ns.isEmpty()) {
            return workspace.resolve(relativePath);
        }
        return workspace.resolve(String.join("/", ns)).resolve(relativePath);
    }

    /** 读取 AGENTS.md 内容，未找到时返回空字符串。 */
    public String readAgentsMd(RuntimeContext rc) {
        return readWithOverride(rc, AGENTS_MD);
    }

    /** 从知识目录读取 KNOWLEDGE.md 内容。 */
    public String readKnowledgeMd(RuntimeContext rc) {
        return readWithOverride(rc, KNOWLEDGE_DIR + "/" + KNOWLEDGE_MD);
    }

    /** 读取 MEMORY.md 内容：优先文件系统覆盖层，回退到本地。 */
    public String readMemoryMd(RuntimeContext rc) {
        return readWithOverride(rc, MEMORY_MD);
    }

    /**
     * 采用双层模式读取工作区内的 UTF-8 文件：
     * 先查文件系统，再回退到本地磁盘。
     */
    public String readManagedWorkspaceFileUtf8(RuntimeContext rc, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return "";
        }
        String normalized = normalizeRelativePath(relativePath);
        if (normalized.isEmpty()) {
            return "";
        }
        Path resolved = workspace.resolve(normalized).normalize();
        if (!resolved.startsWith(workspace)) {
            return "";
        }
        return readWithOverride(rc, normalized);
    }

    public Path getMemoryDir(RuntimeContext rc) {
        return resolveRuntimeDataPath(rc, MEMORY_DIR);
    }

    public Path getSkillsDir() {
        return workspace.resolve(SKILLS_DIR);
    }

    public Path getKnowledgeDir() {
        return workspace.resolve(KNOWLEDGE_DIR);
    }

    /** 列出知识目录树下的全部文件，合并文件系统与本地磁盘结果。 */
    public List<Path> listKnowledgeFiles(RuntimeContext rc) {
        Set<String> relativePaths = new LinkedHashSet<>();

        if (filesystem != null) {
            GlobResult glob = filesystem.glob(rc, "*", KNOWLEDGE_DIR);
            if (glob.isSuccess() && glob.matches() != null) {
                for (FileInfo fi : glob.matches()) {
                    if (fi.path() != null && !fi.path().isBlank()) {
                        relativePaths.add(normalizeRelativePath(fi.path().trim()));
                    }
                }
            }
        }

        Path dir = getKnowledgeDir();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                        .forEach(
                                p -> {
                                    String rel =
                                            workspace
                                                    .relativize(p.normalize())
                                                    .toString()
                                                    .replace('\\', '/');
                                    relativePaths.add(rel);
                                });
            } catch (IOException e) {
                log.warn("Failed to list knowledge files: {}", e.getMessage());
            }
        }

        List<Path> result = new ArrayList<>();
        for (String rel : relativePaths) {
            result.add(workspace.resolve(rel));
        }
        return result;
    }

    public Path getSessionDir(RuntimeContext rc, String agentId) {
        return resolveRuntimeDataPath(rc, AGENTS_DIR + "/" + agentId + "/" + SESSIONS_DIR);
    }

    /**
     * 返回旧版会话文件路径（.json），不创建目录。
     *
     * @deprecated 原生会话历史通过 SessionLogStore 存储；导入旧状态文件时应显式迁移。
     */
    @Deprecated
    public Path resolveSessionFile(RuntimeContext rc, String agentId, String sessionId) {
        return getSessionDir(rc, agentId).resolve(sessionId + ".json");
    }

    /**
     * 向工作区相对文件追加 UTF-8 文本，必要时创建父目录。
     * 写入通过 {@link AbstractFilesystem}；未配置该层时回退本地文件。
     *
     * <p>逐路径 {@link ReentrantLock} 串行化并发调用方，使读、合并、写循环在本进程内原子执行。
     * 跨副本追加为最后写入者获胜；此方法不执行 CAS。
     */
    public void appendUtf8WorkspaceRelative(
            RuntimeContext rc, String relativePath, String content) {
        if (relativePath == null || content == null) {
            return;
        }
        String normalized = requireSafeRelativePath(relativePath);
        if (normalized.isEmpty()) {
            return;
        }
        ReentrantLock lock = pathLocks.computeIfAbsent(normalized, k -> new ReentrantLock());
        lock.lock();
        try {
            if (filesystem == null) {
                appendLocalFile(normalized, content);
                return;
            }
            ReadResult rr = filesystem.read(rc, normalized, 0, 0);
            String existing = "";
            if (rr.isSuccess() && rr.fileData() != null && rr.fileData().content() != null) {
                existing = rr.fileData().content();
            }
            String merged = existing + content;
            filesystem.uploadFiles(
                    rc, List.of(Map.entry(normalized, merged.getBytes(StandardCharsets.UTF_8))));
        } finally {
            lock.unlock();
        }
    }

    // ==================== 任务记录方法 ====================

    /**
     * 服务任务记录路径的文件系统层为调用级沙箱代理时返回 {@code true}。
     * 任务记录是跨调用的编排元数据：{@code WorkspaceTaskRepository} 的心跳与孤儿任务清理器
     * 从智能体调用之外的调度线程维护它们，此时 {@code SandboxBackedFilesystem} 不持有存活沙箱，
     * 任何操作都会抛错。因此任务记录 I/O 直接路由到主机工作区，与未配置文件系统层时的位置相同，
     * 使任务存活状态记账在调用间仍可运行。仍尊重显式前缀路由，例如为 {@code agents/} 挂载的持久存储。
     */
    private boolean taskRecordsRouteToLiveSandbox(String relPath) {
        AbstractFilesystem fs = this.filesystem;
        if (fs instanceof RoutedSandboxFilesystem routed) {
            fs = routed.backendFor(relPath);
        }
        return fs instanceof SandboxBackedFilesystem;
    }

    /**
     * 插入或更新 {@link TaskRecord}，存放于 {@code agents/<agentId>/tasks/<sessionId>.json}。
     *
     * <p>读取现有映射，以 {@code taskId} 为键合并或插入记录，再写回更新后的映射。
     * 本地路径使用逐文件 {@link ReentrantLock}，防止多个任务共用同一会话文件时发生读—改—写竞争；
     * 远程任务存储路径通过后端变更接口处理并发。
     */
    public void writeTaskRecord(
            RuntimeContext rc, String agentId, String sessionId, TaskRecord record) {
        if (agentId == null
                || agentId.isBlank()
                || sessionId == null
                || sessionId.isBlank()
                || record == null
                || record.getTaskId() == null) {
            return;
        }
        String rel = taskRecordPath(agentId, sessionId);
        registerTaskScope(rc, agentId);
        mutateTaskMap(
                rc,
                rel,
                map -> {
                    TaskRecord current = map.get(record.getTaskId());
                    // 每次 CAS 冲突后都重新判断：延迟到达的心跳不得复活
                    // 已被其他节点完成、取消或判定为
                    // 孤儿状态的任务。
                    if (current != null
                            && current.getStatus() != null
                            && current.getStatus().isTerminal()
                            && current.getStatus() != record.getStatus()) {
                        return false;
                    }
                    if (current != null
                            && current.isCancelRequested()
                            && !record.isCancelRequested()
                            && (record.getStatus() == null || !record.getStatus().isTerminal())) {
                        return false;
                    }
                    record.touch();
                    map.put(record.getTaskId(), record);
                    return true;
                });
    }

    private RemoteFilesystem taskRemoteFilesystem(String rel) {
        AbstractFilesystem fs = filesystem;
        while (fs != null) {
            if (fs instanceof CompositeFilesystem composite) {
                fs = composite.filesystemFor(rel);
            } else if (fs instanceof RoutedSandboxFilesystem routed) {
                fs = routed.backendFor(rel);
            } else if (fs instanceof OverlayFilesystem overlay) {
                fs = overlay.upper();
            } else {
                return fs instanceof RemoteFilesystem remote ? remote : null;
            }
        }
        return null;
    }

    private void registerTaskScope(RuntimeContext rc, String agentId) {
        RemoteFilesystem remote = taskRemoteFilesystem(taskRecordPath(agentId, "_"));
        if (remote != null) {
            try {
                remote.registerTaskScope(rc, agentId);
            } catch (Exception e) {
                // 保留尽力持久化行为；后续读写会重试注册。
                log.warn(
                        "Failed to register task maintenance scope for agent {}: {}",
                        agentId,
                        e.getMessage());
            }
        }
    }

    /** 任务记录及维护其原始存储作用域所需的路由上下文。 */
    public record ScopedTaskRecord(RuntimeContext context, TaskRecord record) {}

    /**
     * 跨已注册作用域列出任务记录，用于主机侧维护。普通任务读取仍限制在调用方作用域内。
     * 包含旧文件，因为停机时间可能超过常规近期文件窗口，而非终态记录仍需要孤儿恢复处理。
     */
    public Collection<ScopedTaskRecord> listTaskRecordsForMaintenance(String agentId) {
        RemoteFilesystem remote = taskRemoteFilesystem(taskRecordPath(agentId, "_"));
        List<RuntimeContext> contexts =
                remote == null
                        ? List.of(RuntimeContext.empty())
                        : remote.taskMaintenanceContexts(agentId);
        List<ScopedTaskRecord> records = new ArrayList<>();
        for (RuntimeContext context : contexts) {
            for (TaskRecord record : listAllTaskRecords(context, agentId, null)) {
                records.add(new ScopedTaskRecord(context, record));
            }
        }
        return records;
    }

    /**
     * 仅当当前持久化记录仍然过期时，才将本地任务标为孤儿任务。
     * 远程 CAS 重试在写入前重新检查心跳与终态。
     */
    public boolean failOrphanedTask(
            RuntimeContext rc,
            String agentId,
            String sessionId,
            String taskId,
            Instant threshold,
            String error) {
        return mutateTaskMap(
                rc,
                taskRecordPath(agentId, sessionId),
                map -> {
                    TaskRecord current = map.get(taskId);
                    if (current == null
                            || current.getStatus() == null
                            || current.getStatus().isTerminal()
                            || current.isAgentProtocolTransport()
                            || current.getLastUpdatedAt() == null
                            || current.getLastUpdatedAt().isAfter(threshold)) {
                        return false;
                    }
                    current.setStatus(TaskStatus.FAILED);
                    current.setErrorMessage(error);
                    current.touch();
                    return true;
                });
    }

    private boolean mutateTaskMap(
            RuntimeContext rc, String rel, Function<Map<String, TaskRecord>, Boolean> mutation) {
        ReentrantLock lock = pathLocks.computeIfAbsent(rel, k -> new ReentrantLock());
        lock.lock();
        try {
            RemoteFilesystem remote = taskRemoteFilesystem(rel);
            boolean atomic = remote != null && remote.supportsAtomicUpdates();
            for (int attempt = 0; attempt < 5; attempt++) {
                if (!atomic) {
                    Map<String, TaskRecord> map = readTaskMap(rc, rel);
                    if (!mutation.apply(map)) {
                        return false;
                    }
                    persistTaskMap(rc, rel, map);
                    return true;
                }
                // 通过路由读取，保留前缀重映射与叠加层语义。
                ReadResult read = filesystem.read(rc, rel, 0, 0);
                String json =
                        read.isSuccess() && read.fileData() != null
                                ? read.fileData().content()
                                : null;
                Map<String, TaskRecord> map =
                        json == null || json.isBlank()
                                ? new LinkedHashMap<>()
                                : TASK_RECORD_JSON.readValue(json, TASK_MAP_TYPE);
                if (map == null) {
                    map = new LinkedHashMap<>();
                }
                if (!mutation.apply(map)) {
                    return false;
                }
                String updated =
                        TASK_RECORD_JSON.writerWithDefaultPrettyPrinter().writeValueAsString(map);
                boolean written =
                        json == null
                                ? filesystem.write(rc, rel, updated).isSuccess()
                                : filesystem.edit(rc, rel, json, updated, false).isSuccess();
                if (written) {
                    if (index != null) {
                        index.upsert(rel, updated.getBytes(StandardCharsets.UTF_8).length, null);
                    }
                    return true;
                }
            }
            log.warn("Task record update conflicted repeatedly for {}", rel);
        } catch (IOException e) {
            log.error(
                    "Failed to parse or update task record store {}, aborting write to avoid data"
                            + " loss.",
                    rel,
                    e);
        } finally {
            lock.unlock();
        }
        return false;
    }

    /** 按任务 ID 读取单个 {@link TaskRecord}，未找到时返回 {@link Optional#empty()}。 */
    public Optional<TaskRecord> readTaskRecord(
            RuntimeContext rc, String agentId, String sessionId, String taskId) {
        if (agentId == null
                || agentId.isBlank()
                || sessionId == null
                || sessionId.isBlank()
                || taskId == null
                || taskId.isBlank()) {
            return Optional.empty();
        }
        registerTaskScope(rc, agentId);
        String rel = taskRecordPath(agentId, sessionId);
        Map<String, TaskRecord> map = readTaskMapLocked(rc, rel);
        return Optional.ofNullable(map.get(taskId));
    }

    /** 按插入顺序返回给定智能体与会话的全部 {@link TaskRecord}。 */
    public Collection<TaskRecord> listTaskRecords(
            RuntimeContext rc, String agentId, String sessionId) {
        if (agentId == null || agentId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return Collections.emptyList();
        }
        registerTaskScope(rc, agentId);
        String rel = taskRecordPath(agentId, sessionId);
        return List.copyOf(readTaskMapLocked(rc, rel).values());
    }

    /**
     * 返回给定智能体的 {@link TaskRecord}，覆盖在 {@code recentWindow} 内活跃的<em>所有</em>会话，
     * 不保证顺序。
     *
     * <p>合并本地磁盘与文件系统层的任务 JSON 文件。已知最后修改时间
     * （磁盘 mtime 或 {@link FileInfo#modifiedAt()}）且早于 {@code recentWindow} 的文件会跳过。
     * 维护流程不传截止时间，因为旧文件可能包含长期停机期间执行器已消失的非终态任务。
     *
     * @param agentId 父智能体标识
     * @param recentWindow 只考虑此时长内修改的文件，已知更旧的文件会跳过；{@code null} 包含所有保留文件
     */
    public Collection<TaskRecord> listAllTaskRecords(
            RuntimeContext rc, String agentId, Duration recentWindow) {
        if (agentId == null || agentId.isBlank()) {
            return Collections.emptyList();
        }
        Instant cutoff = recentWindow == null ? null : Instant.now().minus(recentWindow);
        String tasksRelDir = AGENTS_DIR + "/" + agentId + "/" + TASKS_DIR;

        // 工作区相对路径 → Optional<Instant> 最后修改时间（空值表示 mtime 未知）。
        Map<String, Optional<Instant>> relPaths = new LinkedHashMap<>();

        if (filesystem != null && !taskRecordsRouteToLiveSandbox(tasksRelDir)) {
            GlobResult glob = filesystem.glob(rc, "*.json", tasksRelDir);
            if (glob.isSuccess() && glob.matches() != null) {
                for (FileInfo fi : glob.matches()) {
                    if (fi.path() == null || fi.path().isBlank()) {
                        continue;
                    }
                    String rel = normalizeRelativePath(fi.path().trim());
                    Instant mtime = parseInstantQuiet(fi.modifiedAt());
                    relPaths.put(rel, Optional.ofNullable(mtime));
                }
            }
        }

        Path tasksDir = resolveRuntimeDataPath(rc, AGENTS_DIR + "/" + agentId + "/" + TASKS_DIR);
        if (Files.isDirectory(tasksDir)) {
            try (Stream<Path> stream = Files.list(tasksDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .forEach(
                                p -> {
                                    String rel = tasksRelDir + "/" + p.getFileName();
                                    if (!relPaths.containsKey(rel)) {
                                        relPaths.put(rel, Optional.ofNullable(diskMtime(p)));
                                    }
                                });
            } catch (IOException e) {
                log.warn("Failed to list task files for agent {}: {}", agentId, e.getMessage());
            }
        }

        List<TaskRecord> all = new ArrayList<>();
        for (Map.Entry<String, Optional<Instant>> entry : relPaths.entrySet()) {
            Optional<Instant> mtime = entry.getValue();
            // 仅在 mtime 已知且明确早于截止时间时跳过。
            if (cutoff != null && mtime.isPresent() && mtime.get().isBefore(cutoff)) {
                continue;
            }
            all.addAll(readTaskMapLocked(rc, entry.getKey()).values());
        }
        return all;
    }

    private static Instant parseInstantQuiet(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    private Instant diskMtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (IOException e) {
            return null;
        }
    }

    private String taskRecordPath(String agentId, String sessionId) {
        return AGENTS_DIR + "/" + agentId + "/" + TASKS_DIR + "/" + sessionId + ".json";
    }

    /**
     * 先获取逐文件锁，再委托 {@link #readTaskMap(RuntimeContext, String)}，
     * 使读取与 {@link #writeTaskRecord} 中的读—改—写周期互斥。
     * 这避免并发写入者的非原子文件更新（截断后写入）被读取为不完整 JSON。
     */
    private Map<String, TaskRecord> readTaskMapLocked(RuntimeContext rc, String rel) {
        ReentrantLock lock = pathLocks.computeIfAbsent(rel, k -> new ReentrantLock());
        lock.lock();
        try {
            try {
                return readTaskMap(rc, rel);
            } catch (IOException e) {
                // 明确暴露损坏问题，但不修改或重新初始化底层文件。
                log.error(
                        "Failed to parse task record store {}, returning empty in-memory view.",
                        rel,
                        e);
                return Collections.emptyMap();
            }
        } finally {
            lock.unlock();
        }
    }

    private Map<String, TaskRecord> readTaskMap(RuntimeContext rc, String rel) throws IOException {
        String json =
                taskRecordsRouteToLiveSandbox(rel)
                        // 执行与非路由分支相同的规范化和验证，防止恶意
                        // sessionId 通过主机磁盘路径读取工作区之外的内容。
                        ? readFileQuietly(workspace.resolve(requireSafeRelativePath(rel)))
                        : readWritableWorkspaceRelativeUtf8(rc, rel);
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        Map<String, TaskRecord> map = TASK_RECORD_JSON.readValue(json, TASK_MAP_TYPE);
        return map != null ? new LinkedHashMap<>(map) : new LinkedHashMap<>();
    }

    private void persistTaskMap(RuntimeContext rc, String rel, Map<String, TaskRecord> map) {
        try {
            String serialized =
                    TASK_RECORD_JSON.writerWithDefaultPrettyPrinter().writeValueAsString(map);
            if (taskRecordsRouteToLiveSandbox(rel)) {
                writeLocalFile(rel, serialized);
                return;
            }
            writeUtf8WorkspaceRelative(rc, rel, serialized);
        } catch (IOException e) {
            log.warn("Failed to write task record store {}: {}", rel, e.getMessage());
        }
    }

    private String readWritableWorkspaceRelativeUtf8(RuntimeContext rc, String relativePath) {
        String normalized = requireSafeRelativePath(relativePath);
        if (normalized.isEmpty()) return "";
        return readWithOverride(rc, normalized);
    }

    /** 覆盖工作区相对 UTF-8 文件；配置了文件系统时，写入均经由该层。 */
    public void writeUtf8WorkspaceRelative(RuntimeContext rc, String relativePath, String content) {
        if (relativePath == null || content == null) {
            return;
        }
        String normalized = requireSafeRelativePath(relativePath);
        if (normalized.isEmpty()) {
            return;
        }
        if (filesystem == null) {
            writeLocalFile(normalized, content);
            return;
        }
        filesystem.uploadFiles(
                rc, List.of(Map.entry(normalized, content.getBytes(StandardCharsets.UTF_8))));
        // 尽力在索引中记录上传大小，因为没有可用于 stat 的本地文件。
        if (index != null) {
            index.upsert(normalized, content.getBytes(StandardCharsets.UTF_8).length, null);
        }
    }

    // ==================== 技能自主学习辅助方法 ====================

    /**
     * 为 {@link #writeDraftSkillFile} 和 {@link #moveSkill} 使用的启发式规则提供检测：
     * 返回 {@code true} 表示叠加文件系统的下层为非本地 KV/远程后端，例如 {@code RemoteFilesystem}。
     * 这类存储的草稿应放到下层，使外部审批系统能跨副本读取。
     */
    private static boolean isRemoteLowerLayer(AbstractFilesystem fs) {
        if (!(fs instanceof OverlayFilesystem overlay)) {
            return false;
        }
        AbstractFilesystem lower = overlay.lower();
        if (lower == null) {
            return false;
        }
        String name = lower.getClass().getSimpleName();
        // 非本地 KV 及 KV 叠加实现跨副本公开其内容。
        return name.contains("Remote") || name.contains("Kv") || name.contains("KV");
    }

    /**
     * 在 {@code skills/_drafts/} 下或调用方传入的草稿目录下写入单个文件。
     * 底层文件系统为 {@link OverlayFilesystem} 且下层是 {@code RemoteFilesystem} 时，
     * 草稿直接写入下层，使其他副本与外部审批系统可见；否则使用标准上层写入路径。
     *
     * @param rc 运行时上下文，直接传给文件系统
     * @param relativePath 工作区相对路径，必须包含草稿前缀
     * @param content UTF-8 内容
     */
    public void writeDraftSkillFile(RuntimeContext rc, String relativePath, String content) {
        if (relativePath == null || content == null) {
            return;
        }
        String normalized = requireSafeRelativePath(relativePath);
        if (normalized.isEmpty()) {
            return;
        }
        if (filesystem instanceof OverlayFilesystem overlay && isRemoteLowerLayer(filesystem)) {
            // 跨副本草稿：直接写入共享的下层后端。
            overlay.lower()
                    .uploadFiles(
                            rc,
                            List.of(
                                    Map.entry(
                                            normalized, content.getBytes(StandardCharsets.UTF_8))));
            return;
        }
        // 默认路径与 writeUtf8WorkspaceRelative 相同。
        writeUtf8WorkspaceRelative(rc, relativePath, content);
    }

    /**
     * 移动工作区内的目录，用于将 {@code skills/_drafts/<x>} 晋升到 {@code skills/<x>}。
     * 尽力执行：底层文件系统提供原生 {@code move} 时使用它；成功返回 {@code true}。
     */
    public boolean moveSkill(RuntimeContext rc, String fromRelative, String toRelative) {
        if (fromRelative == null || toRelative == null || filesystem == null) {
            return false;
        }
        String src = requireSafeRelativePath(fromRelative);
        String dst = requireSafeRelativePath(toRelative);
        if (src.isEmpty() || dst.isEmpty()) {
            return false;
        }
        try {
            // 叠加层加远程下层的配置中，源草稿可能位于下层，
            // 目标也应位于下层以保持跨副本可见；叠加文件系统
            // 通过自身的 move 契约透明处理此情况。
            WriteResult r = filesystem.move(rc, src, dst);
            return r.isSuccess();
        } catch (Exception e) {
            log.warn("moveSkill {} → {} failed: {}", src, dst, e.getMessage());
            return false;
        }
    }

    // ==================== 双层读写辅助方法 ====================

    /**
     * 双层读取：先查文件系统（通过 {@link
     * NamespaceFactory} 应用命名空间），再回退本地磁盘。
     */
    private String readWithOverride(RuntimeContext rc, String relativePath) {
        String fsContent = readTextThroughFilesystem(rc, relativePath);
        if (!fsContent.isEmpty()) {
            return fsContent;
        }
        return readFileQuietly(workspace.resolve(relativePath));
    }

    private String readFileQuietly(Path path) {
        if (!Files.isRegularFile(path)) {
            return "";
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", path, e.getMessage());
            return "";
        }
    }

    private String readTextThroughFilesystem(RuntimeContext rc, String filePath) {
        if (filesystem == null) {
            return "";
        }
        ReadResult r = filesystem.read(rc, filePath, 0, 0);
        if (!r.isSuccess() || r.fileData() == null) {
            return "";
        }
        String c = r.fileData().content();
        return c != null ? c : "";
    }

    private void appendLocalFile(String relativePath, String content) {
        Path local = workspace.resolve(relativePath).normalize();
        if (!local.startsWith(workspace)) {
            log.warn("Refusing to write outside workspace: {}", relativePath);
            return;
        }
        try {
            if (local.getParent() != null) {
                Files.createDirectories(local.getParent());
            }
            Files.writeString(
                    local,
                    content,
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            if (index != null) {
                index.upsertFromLocalFile(relativePath, local);
            }
        } catch (IOException e) {
            log.warn("Failed to append {}: {}", local, e.getMessage());
        }
    }

    /**
     * 原子覆盖本地磁盘上的工作区相对 UTF-8 文件。
     *
     * <p>先将内容写入同级临时文件，再通过 {@link StandardCopyOption#ATOMIC_MOVE} 重命名覆盖目标。
     * 这是尽力保证：底层文件系统不支持原子重命名时回退到普通移动。
     * 这样可避免在支持原子替换的路径中让并发读取者观察到部分写入的文件。
     */
    private void writeLocalFile(String relativePath, String content) {
        Path local = workspace.resolve(relativePath).normalize();
        if (!local.startsWith(workspace)) {
            log.warn("Refusing to write outside workspace: {}", relativePath);
            return;
        }
        Path temp = local.resolveSibling(local.getFileName() + ".tmp." + UUID.randomUUID());
        try {
            if (local.getParent() != null) {
                Files.createDirectories(local.getParent());
            }
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temp,
                        local,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temp, local, StandardCopyOption.REPLACE_EXISTING);
            }
            if (index != null) {
                index.upsertFromLocalFile(relativePath, local);
            }
        } catch (IOException e) {
            log.warn("Failed to write {}: {}", local, e.getMessage());
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
        }
    }

    static String normalizeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return "";
        }
        String s = relativePath.replace('\\', '/').stripLeading();
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }

    private static String requireSafeRelativePath(String relativePath) {
        String normalized = normalizeRelativePath(relativePath);
        if (!normalized.isEmpty()) {
            AbstractFilesystem.validatePath(normalized);
        }
        return normalized;
    }

    /**
     * 返回全部记忆文件的工作区相对路径，包括 {@code MEMORY.md} 和 {@code
     * memory/*.md}。合并 {@link AbstractFilesystem} 层与本地磁盘结果，按相对路径去重。
     */
    public List<String> listMemoryFilePaths(RuntimeContext rc) {
        Set<String> paths = new LinkedHashSet<>();

        if (filesystem != null) {
            ReadResult memMd = filesystem.read(rc, MEMORY_MD, 0, 1);
            if (memMd.isSuccess()) {
                paths.add(MEMORY_MD);
            }
            GlobResult glob = filesystem.glob(rc, "*.md", MEMORY_DIR);
            if (glob.isSuccess() && glob.matches() != null) {
                for (FileInfo fi : glob.matches()) {
                    if (fi.path() != null && !fi.path().isBlank()) {
                        String rel = normalizeRelativePath(fi.path().trim());
                        if (!rel.isEmpty()) {
                            paths.add(rel);
                        }
                    }
                }
            }
        }

        if (Files.isRegularFile(resolveRuntimeDataPath(rc, MEMORY_MD))) {
            paths.add(MEMORY_MD);
        }
        Path memDir = getMemoryDir(rc);
        if (Files.isDirectory(memDir)) {
            try (Stream<Path> walk = Files.list(memDir)) {
                walk.filter(p -> p.toString().endsWith(".md"))
                        .filter(Files::isRegularFile)
                        .forEach(p -> paths.add(MEMORY_DIR + "/" + p.getFileName()));
            } catch (IOException e) {
                log.warn("Failed to list memory dir: {}", e.getMessage());
            }
        }
        return new ArrayList<>(paths);
    }

    /** 用于建立索引的工作区相对路径。 */
    public String toWorkspaceRelativeString(Path absoluteUnderWorkspace) {
        return workspace
                .relativize(absoluteUnderWorkspace.normalize())
                .toString()
                .replace('\\', '/');
    }
}
