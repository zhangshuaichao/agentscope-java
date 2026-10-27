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
package io.agentscope.harness.agent.skill.curator;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 技能自学习循环的仅追加审计日志。每次 {@code skill_manage} 写入、晋级决策和 curator 转换都以一行 JSON 写入 {@code workspace/skills/.audit/YYYY-MM-DD.jsonl}。
 *
 * <p>有意与其他代码路径并发发出的 OTel/trace span 分离。磁盘 JSONL 是恢复和取证副本：OTel 收集器不可用时仍可保留，并让运维无需搭建指标栈即可查询记录。{@code agent.queryAudit(predicate)} 读取日文件中的条目。
 *
 */
public class SkillAuditLog {

    private static final Logger log = LoggerFactory.getLogger(SkillAuditLog.class);

    public static final String AUDIT_DIR = "skills/.audit";

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static final DateTimeFormatter DAY_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final AbstractFilesystem filesystem;
    private final WorkspaceManager workspaceManager;
    private final ReentrantLock lock = new ReentrantLock();

    public SkillAuditLog(AbstractFilesystem filesystem, WorkspaceManager workspaceManager) {
        this.filesystem = filesystem;
        this.workspaceManager = workspaceManager;
    }

    /**
     * 单条审计记录。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(
            Instant ts,
            String actor,
            String op,
            String target,
            String decision,
            String scanVerdict,
            String beforeHash,
            String afterHash,
            Map<String, String> ctx,
            Map<String, Object> extra) {}

    /**
     * 追加新条目。尽力而为：失败只记录日志，不向外抛出。
     */
    public void append(Entry entry) {
        if (entry == null) {
            return;
        }
        Entry stamped =
                entry.ts() != null
                        ? entry
                        : new Entry(
                                Instant.now(),
                                entry.actor(),
                                entry.op(),
                                entry.target(),
                                entry.decision(),
                                entry.scanVerdict(),
                                entry.beforeHash(),
                                entry.afterHash(),
                                entry.ctx(),
                                entry.extra());
        String day = DAY_FMT.format(stamped.ts());
        String path = AUDIT_DIR + "/" + day + ".jsonl";
        lock.lock();
        try {
            String json = JSON.writeValueAsString(stamped);
            String content = json + "\n";
            // 使用 WorkspaceManager 的追加辅助方法，保持标准写入路径。
            if (workspaceManager != null) {
                workspaceManager.appendUtf8WorkspaceRelative(RuntimeContext.empty(), path, content);
            } else if (filesystem != null) {
                // 没有 WorkspaceManager 时回退为读取已有内容，再上传拼接结果。
                String existing = "";
                try {
                    var rr = filesystem.read(RuntimeContext.empty(), path, 0, 0);
                    if (rr.isSuccess()
                            && rr.fileData() != null
                            && rr.fileData().content() != null) {
                        existing = rr.fileData().content();
                    }
                } catch (Exception ignored) {
                }
                filesystem.uploadFiles(
                        RuntimeContext.empty(),
                        List.of(
                                Map.entry(
                                        path,
                                        (existing + content).getBytes(StandardCharsets.UTF_8))));
            }
        } catch (Exception e) {
            log.warn("SkillAuditLog.append() failed: {}", e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 读取给定日期的审计日志，将每行解析为 {@link Entry}，再按谓词过滤。{@code dayUtc} 为 {@code null} 时查询今天。
     */
    public List<Entry> query(String dayUtc, Predicate<Entry> filter) {
        if (filesystem == null) {
            return List.of();
        }
        if (dayUtc == null || dayUtc.isBlank()) {
            dayUtc = DAY_FMT.format(Instant.now());
        }
        String path = AUDIT_DIR + "/" + dayUtc + ".jsonl";
        try {
            var rr = filesystem.read(RuntimeContext.empty(), path, 0, 0);
            if (!rr.isSuccess() || rr.fileData() == null || rr.fileData().content() == null) {
                return List.of();
            }
            List<Entry> out = new ArrayList<>();
            for (String line : rr.fileData().content().split("\n")) {
                if (line.isBlank()) continue;
                try {
                    Entry e = JSON.readValue(line, Entry.class);
                    if (filter == null || filter.test(e)) {
                        out.add(e);
                    }
                } catch (Exception parseError) {
                    // 跳过格式错误的行，继续处理。
                    log.debug("audit line parse error: {}", parseError.getMessage());
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("query() failed for {}: {}", path, e.getMessage());
            return List.of();
        }
    }

    /**
     * 常见 {@code skill_manage} 审计条目格式的便捷构建方法。
     */
    public static Entry manageEntry(
            String actor, String name, String action, String draftOrMain, String verdict) {
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("target_dir", draftOrMain);
        return new Entry(
                Instant.now(), actor, "manage", name, action, verdict, null, null, ctx, null);
    }

    /**
     * {@code promote} 审计条目的便捷构建方法。
     */
    public static Entry promoteEntry(
            String reviewer,
            String name,
            String decision,
            String scanVerdict,
            List<String> environments) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (environments != null) {
            extra.put("environments", environments);
        }
        return new Entry(
                Instant.now(),
                reviewer,
                "promote",
                name,
                decision,
                scanVerdict,
                null,
                null,
                null,
                extra);
    }
}
