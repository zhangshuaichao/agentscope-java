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
package io.agentscope.dataagent.web.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.web.auth.UserStore;
import io.agentscope.dataagent.web.catalog.UserAgentDefinitionStore;
import io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 每智能体的仅追加活动日志，以 JSONL 保存于其自身命名空间工作区。
 * <p>布局（相对于智能体命名空间根；第一行为当前日志，第二行为轮转分块，按名称最新优先）：
 * <pre>
 *   activity.jsonl              # the live log
 *   activity-{timestampMs}.jsonl # rotated chunks (most recent first by name)
 * </pre>
 * <p>写入经过工作区同一个 {@link AbstractFilesystem}，文件位于逐所有者/智能体沙箱中，继承部署隔离保证。
 * 因抽象文件系统不提供流式追加原语，追加使用读-改-写；逐智能体进程内锁保证单节点并发追加一致。
 * {@code WorkspaceCopier} 排除 {@code activity*.jsonl}，因此克隆以全新审计记录开始。
 * <p>{@code activity.jsonl} 超过 {@link #ROTATION_THRESHOLD_BYTES} 后，下一次写入先轮转为
 * {@code activity-{ts}.jsonl} 再创建新日志。因文件系统没有 {@code rename}/{@code move}，
 * 轮转通过复制加删除实现。
 * <p>读取按最新优先返回；当前日志之后，按字典顺序合并轮转分块（最新轮转在前）。
 * <p>【学习提示】这是尽力记录的应用活动轨迹，不是保证不可丢失的安全审计账本；
 * 进程内锁和复制删除也不提供跨副本事务原子性。
 */
@Service
public class AgentActivityStore {

    private static final Logger log = LoggerFactory.getLogger(AgentActivityStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String LIVE_FILE = "activity.jsonl";
    static final String ROTATED_PREFIX = "activity-";
    static final String ROTATED_SUFFIX = ".jsonl";

    /** 当前日志超过此大小时执行轮转。 */
    static final int ROTATION_THRESHOLD_BYTES = 1024 * 1024; // 1 MiB

    /** 单次读取响应的硬上限，避免日志暴增拖垮界面。 */
    static final int MAX_RETURN = 500;

    private final WorkspaceManagerFactory workspaceFactory;
    private final UserStore userStore;
    private final UserAgentDefinitionStore agentStore;

    /** 每个 (ownerId, agentId) 一把锁，保证进程内追加一致。 */
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public AgentActivityStore(
            WorkspaceManagerFactory workspaceFactory,
            UserStore userStore,
            UserAgentDefinitionStore agentStore) {
        this.workspaceFactory = workspaceFactory;
        this.userStore = userStore;
        this.agentStore = agentStore;
    }

    /** 向 {@code (ownerId, agentId)} 的日志追加事件。采用尽力策略：持久化错误仅记录不传播，避免审计日志故障破坏用户调用。 */
    public void record(String ownerId, String agentId, ActorRef actor, String action) {
        record(ownerId, agentId, actor, action, null, null);
    }

    /** 追加带可选目标与元数据的事件，失败语义见 {@link #record(String, String, ActorRef, String)}。 */
    public void record(
            String ownerId,
            String agentId,
            ActorRef actor,
            String action,
            String target,
            Map<String, Object> metadata) {
        if (ownerId == null || agentId == null || action == null) {
            return;
        }
        ActivityEvent event =
                new ActivityEvent(
                        newId(),
                        System.currentTimeMillis(),
                        actor != null ? actor.userId() : null,
                        actor != null ? actor.username() : null,
                        action,
                        target,
                        metadata == null || metadata.isEmpty() ? null : Map.copyOf(metadata));
        ReentrantLock lock = lockFor(ownerId, agentId);
        lock.lock();
        try {
            appendLocked(ownerId, agentId, event);
        } catch (RuntimeException ex) {
            log.warn("Activity log write failed for {}/{}: {}", ownerId, agentId, ex.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /** 返回 {@code (ownerId, agentId)} 最近 {@code limit} 条事件，可过滤为严格晚于 {@code sinceMs} 的事件。最新在前。 */
    public List<ActivityEvent> list(String ownerId, String agentId, Long sinceMs, int limit) {
        int cap = Math.min(limit > 0 ? limit : 50, MAX_RETURN);
        ReentrantLock lock = lockFor(ownerId, agentId);
        lock.lock();
        try {
            return readNewestFirst(ownerId, agentId, sinceMs, cap);
        } catch (RuntimeException ex) {
            log.warn("Activity log read failed for {}/{}: {}", ownerId, agentId, ex.getMessage());
            return List.of();
        } finally {
            lock.unlock();
        }
    }

    /** 一次性解析用户显示名，容忍未知 ID（返回 ID 本身）。 */
    public ActorRef actor(String userId) {
        if (userId == null) return new ActorRef(null, null);
        Optional<UserStore.UserRecord> rec = userStore.findById(userId);
        return new ActorRef(userId, rec.map(UserStore.UserRecord::username).orElse(userId));
    }

    // -----------------------------------------------------------------
    // 追加路径
    // -----------------------------------------------------------------

    private void appendLocked(String ownerId, String agentId, ActivityEvent event) {
        AbstractFilesystem fs = scopedFs(ownerId, agentId);
        String line;
        try {
            line = MAPPER.writeValueAsString(event) + "\n";
        } catch (Exception e) {
            throw new RuntimeException("activity event serialization failed", e);
        }
        String existing = readUtf8(fs, LIVE_FILE).orElse("");
        if (existing.length() >= ROTATION_THRESHOLD_BYTES) {
            rotateLocked(fs);
            existing = "";
        }
        String combined = existing + line;
        fs.uploadFiles(
                null,
                List.of(
                        new AbstractMap.SimpleEntry<>(
                                LIVE_FILE, combined.getBytes(StandardCharsets.UTF_8))));
    }

    private void rotateLocked(AbstractFilesystem fs) {
        Optional<String> body = readUtf8(fs, LIVE_FILE);
        if (body.isEmpty() || body.get().isEmpty()) {
            return;
        }
        String name = ROTATED_PREFIX + System.currentTimeMillis() + ROTATED_SUFFIX;
        fs.uploadFiles(
                null,
                List.of(
                        new AbstractMap.SimpleEntry<>(
                                name, body.get().getBytes(StandardCharsets.UTF_8))));
        // 下一次写入将覆盖当前文件；显式删除可在后续追加延迟时
        // 保持读取一致。
        try {
            fs.delete(null, "/" + LIVE_FILE);
        } catch (RuntimeException ignored) {
            // 容忍失败，下一次 uploadFiles 将覆盖
        }
    }

    // -----------------------------------------------------------------
    // 读取路径
    // -----------------------------------------------------------------

    private List<ActivityEvent> readNewestFirst(
            String ownerId, String agentId, Long sinceMs, int cap) {
        AbstractFilesystem fs = scopedFs(ownerId, agentId);

        List<String> files = new ArrayList<>();
        files.add(LIVE_FILE);

        GlobResult glob = fs.glob(null, ROTATED_PREFIX + "*" + ROTATED_SUFFIX, null);
        if (glob != null && glob.isSuccess() && glob.matches() != null) {
            List<String> rotated = new ArrayList<>();
            for (FileInfo info : glob.matches()) {
                if (info.isDirectory()) continue;
                String absPath = info.path();
                int slash = absPath.lastIndexOf('/');
                String name = slash >= 0 ? absPath.substring(slash + 1) : absPath;
                if (name.startsWith(ROTATED_PREFIX) && name.endsWith(ROTATED_SUFFIX)) {
                    rotated.add(name);
                }
            }
            // 最新轮转在前。
            rotated.sort(Comparator.reverseOrder());
            files.addAll(rotated);
        }

        List<ActivityEvent> out = new ArrayList<>();
        for (String name : files) {
            Optional<String> body = readUtf8(fs, name);
            if (body.isEmpty()) continue;
            List<ActivityEvent> parsed = parseLines(body.get());
            // 最新记录位于文件末尾；逆序迭代以保持最新优先。
            for (int i = parsed.size() - 1; i >= 0; i--) {
                ActivityEvent ev = parsed.get(i);
                if (sinceMs != null && ev.timestampMs() <= sinceMs) {
                    continue;
                }
                out.add(ev);
                if (out.size() >= cap) {
                    return out;
                }
            }
        }
        return out;
    }

    private static List<ActivityEvent> parseLines(String body) {
        List<ActivityEvent> events = new ArrayList<>();
        if (body == null || body.isEmpty()) return events;
        for (String line : body.split("\n")) {
            if (line.isBlank()) continue;
            try {
                events.add(MAPPER.readValue(line, ActivityEvent.class));
            } catch (Exception ex) {
                // 容忍损坏的行
            }
        }
        return events;
    }

    // -----------------------------------------------------------------
    // 辅助方法
    // -----------------------------------------------------------------

    private AbstractFilesystem scopedFs(String ownerId, String agentId) {
        String workspacePath =
                agentStore
                        .findById(ownerId, agentId)
                        .map(UserAgentDefinitionStore.StoredEntry::workspacePath)
                        .orElse(null);
        return workspaceFactory.userDataFs(ownerId, agentId, workspacePath);
    }

    private static Optional<String> readUtf8(AbstractFilesystem fs, String path) {
        ReadResult r = fs.read(null, path, 0, Integer.MAX_VALUE);
        if (r == null || !r.isSuccess() || r.fileData() == null) return Optional.empty();
        String content = r.fileData().content();
        return Optional.of(content == null ? "" : content);
    }

    private ReentrantLock lockFor(String ownerId, String agentId) {
        return locks.computeIfAbsent(ownerId + "/" + agentId, k -> new ReentrantLock());
    }

    private static String newId() {
        byte[] buf = new byte[12];
        java.util.concurrent.ThreadLocalRandom.current().nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    /** 触发事件的操作者身份元组，两个字段都可为 {@code null}。 */
    public record ActorRef(String userId, String username) {}
}
