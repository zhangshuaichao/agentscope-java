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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.coordination.LocalPeriodicGate;
import io.agentscope.harness.agent.coordination.PeriodicGate;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 后台技能维护编排器。生命周期参照 hermes-agent 的 {@code agent/curator.py}：
 * <ul>
 * <li>{@code applyAutomaticTransitions}：遍历智能体跟踪的技能，根据旁挂文件中最近活动时间执行 {@code ACTIVE → STALE → ARCHIVED} 转换。跳过固定（pinned）技能及 DRAFT 技能。</li>
 * <li>umbrella 汇总阶段：生成整合候选的 Markdown 报告；在默认 {@code DRY_RUN_ONLY} 模式中不调用 {@code skill_manage}，仅写报告。目前尚未实现实际整合（真正调用 {@code skill_manage}）。</li>
 * </ul>
 *
 * <p>【学习提示】这里要区分已实现行为与规划：自动状态转换会实际归档目录，但汇总阶段目前只是按名称前缀生成报告，并未调用 LLM 或执行技能合并。DRY_RUN_ONLY 约束汇总阶段，不代表整个维护运行没有文件系统副作用。
 *
 * <p>严格不变量：
 * <ul>
 * <li>仅处理 {@code createdBy != null} 的旁挂记录（智能体编写的技能），自动转换进一步限定为已晋级的技能。</li>
 * <li>固定技能跳过全部自动转换。</li>
 * <li>绝不直接删除，仅归档。</li>
 * <li>{@code DRAFT} 状态归晋级门控管理，而不是 curator。</li>
 * </ul>
 */
public class SkillCurator {

    private static final Logger log = LoggerFactory.getLogger(SkillCurator.class);

    public static final String STATE_PATH = "skills/.curator_state.json";
    public static final String REPORTS_DIR = "skills/.curator_reports";

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final AbstractFilesystem filesystem;
    private final SkillUsageStore usageStore;
    private final WorkspaceSkillRepository mainRepo;
    private final SkillCuratorConfig config;
    private final PeriodicGate periodicGate;

    public SkillCurator(
            AbstractFilesystem filesystem,
            SkillUsageStore usageStore,
            WorkspaceSkillRepository mainRepo,
            SkillCuratorConfig config) {
        this(filesystem, usageStore, mainRepo, config, new LocalPeriodicGate());
    }

    public SkillCurator(
            AbstractFilesystem filesystem,
            SkillUsageStore usageStore,
            WorkspaceSkillRepository mainRepo,
            SkillCuratorConfig config,
            PeriodicGate periodicGate) {
        this.filesystem = java.util.Objects.requireNonNull(filesystem, "filesystem");
        this.usageStore = java.util.Objects.requireNonNull(usageStore, "usageStore");
        this.mainRepo = java.util.Objects.requireNonNull(mainRepo, "mainRepo");
        this.config = config != null ? config : SkillCuratorConfig.defaults();
        this.periodicGate = periodicGate != null ? periodicGate : new LocalPeriodicGate();
    }

    public SkillCuratorConfig config() {
        return config;
    }

    // ---------------------------------------------------------------------
    // 状态旁挂文件
    // ---------------------------------------------------------------------

    public SkillCuratorState loadState() {
        try {
            var rr = filesystem.read(RuntimeContext.empty(), STATE_PATH, 0, 0);
            if (rr.isSuccess() && rr.fileData() != null && rr.fileData().content() != null) {
                String body = rr.fileData().content();
                if (!body.isBlank()) {
                    return JSON.readValue(body, SkillCuratorState.class);
                }
            }
        } catch (Exception e) {
            log.debug("loadState() failed: {}", e.getMessage());
        }
        return SkillCuratorState.defaults();
    }

    public void saveState(SkillCuratorState state) {
        try {
            String json = JSON.writeValueAsString(state);
            filesystem.uploadFiles(
                    RuntimeContext.empty(),
                    List.of(
                            new AbstractMap.SimpleImmutableEntry<>(
                                    STATE_PATH, json.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception e) {
            log.warn("saveState() failed: {}", e.getMessage());
        }
    }

    public boolean isPaused() {
        return loadState().paused();
    }

    public void setPaused(boolean paused) {
        SkillCuratorState s = loadState();
        saveState(
                new SkillCuratorState(
                        s.lastRunAt(),
                        s.runCount(),
                        paused,
                        s.lastRunSummary(),
                        s.lastRunDurationSeconds(),
                        s.lastReportPath()));
    }

    /**
     * 时间间隔与周期门控。仅在启用、未暂停、{@code last_run_at} 早于 {@code intervalHours} 且注入的 {@link PeriodicGate} 成功取得执行权时，返回 true。首次运行只初始化 {@code last_run_at} 而不触发，避免新部署尚未经过一个周期就执行维护。
     */
    public boolean shouldRunNow(Instant now) {
        if (!config.enabled() || isPaused()) {
            return false;
        }
        if (now == null) {
            now = Instant.now();
        }
        SkillCuratorState s = loadState();
        if (s.lastRunAt() == null) {
            // 初始化时间戳并延后执行。
            saveState(
                    new SkillCuratorState(
                            now,
                            s.runCount(),
                            s.paused(),
                            "deferred first run — curator seeded",
                            null,
                            null));
            return false;
        }
        // 持久化间隔检查跨 JVM 重启仍有效；随后由 PeriodicGate
        // 在副本间（StoreBacked）或进程内（Local）去重。
        if (Duration.between(s.lastRunAt(), now).toHours() < config.intervalHours()) {
            return false;
        }
        return periodicGate.tryClaim("skill-curator", Duration.ofHours(config.intervalHours()));
    }

    // ---------------------------------------------------------------------
    // 自动状态转换
    // ---------------------------------------------------------------------

    public record TransitionCounts(int checked, int markedStale, int archived, int reactivated) {}

    public TransitionCounts applyAutomaticTransitions(Instant now) {
        if (now == null) {
            now = Instant.now();
        }
        Instant staleCutoff = now.minus(config.staleAfterDays(), ChronoUnit.DAYS);
        Instant archiveCutoff = now.minus(config.archiveAfterDays(), ChronoUnit.DAYS);

        int checked = 0;
        int markedStale = 0;
        int archived = 0;
        int reactivated = 0;

        for (var entry : usageStore.agentCreatedReport()) {
            String name = entry.getKey();
            SkillUsageRecord rec = entry.getValue();
            if (rec == null) {
                continue;
            }
            // 仅自动管理已晋级（createdBy=="agent"）的技能，草稿归
            // 晋级门控负责。
            if (!"agent".equals(rec.createdBy()) || rec.pinned()) {
                continue;
            }
            checked++;

            Instant anchor = rec.latestActivityAt();
            if (anchor == null) {
                anchor = rec.createdAt() != null ? rec.createdAt() : now;
            }
            SkillUsageRecord.State current = rec.state();

            if (anchor.isBefore(archiveCutoff) && current != SkillUsageRecord.State.ARCHIVED) {
                // 移动到归档（mainRepo.delete 以非破坏性方式归档）。
                try {
                    mainRepo.delete(name);
                } catch (Exception e) {
                    log.debug("archive {} failed: {}", name, e.getMessage());
                }
                usageStore.setState(name, SkillUsageRecord.State.ARCHIVED);
                archived++;
            } else if (anchor.isBefore(staleCutoff) && current == SkillUsageRecord.State.ACTIVE) {
                usageStore.setState(name, SkillUsageRecord.State.STALE);
                markedStale++;
            } else if (anchor.isAfter(staleCutoff) && current == SkillUsageRecord.State.STALE) {
                usageStore.setState(name, SkillUsageRecord.State.ACTIVE);
                reactivated++;
            }
        }
        return new TransitionCounts(checked, markedStale, archived, reactivated);
    }

    // ---------------------------------------------------------------------
    // umbrella 汇总阶段（仅试运行报告）
    // ---------------------------------------------------------------------

    /**
     * 生成可读的 Markdown 报告，按共同名称前缀对全部智能体创建的技能分组。这是 LLM 驱动汇总整合阶段的占位实现；提示与 LLM 调用需要辅助模型，留待未来版本。{@code DRY_RUN_ONLY} 模式下写入 {@code skills/.curator_reports/&lt;ts&gt;/REPORT.md}；{@code DISABLED} 模式下不写入并返回 {@code null}。
     *
     * @return 相对于工作区的报告路径；禁用时为 {@code null}
     */
    public String runUmbrellaDryRunReport(Instant now) {
        if (config.umbrellaPassMode() == SkillCuratorConfig.UmbrellaPassMode.DISABLED) {
            return null;
        }
        if (now == null) {
            now = Instant.now();
        }
        String ts =
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .withZone(java.time.ZoneOffset.UTC)
                        .format(now);
        String reportPath = REPORTS_DIR + "/" + ts + "/REPORT.md";

        // 按首个短横线分隔片段聚类技能（简单启发式，对齐 hermes
        // 汇总提示中的 PREFIX CLUSTERS 指引）。
        Map<String, java.util.List<String>> clusters = new LinkedHashMap<>();
        for (var entry : usageStore.agentCreatedReport()) {
            SkillUsageRecord rec = entry.getValue();
            if (!"agent".equals(rec.createdBy()) || rec.pinned()) {
                continue;
            }
            String name = entry.getKey();
            String prefix = name.split("[-_.]", 2)[0];
            clusters.computeIfAbsent(prefix, k -> new java.util.ArrayList<>()).add(name);
        }

        StringBuilder report = new StringBuilder();
        report.append("# Skill Curator Dry-Run Report\n\n");
        report.append("Generated: ").append(now).append("\n");
        report.append("Mode: ").append(config.umbrellaPassMode()).append("\n\n");

        int multiMemberClusters = 0;
        report.append("## Prefix clusters\n\n");
        for (var c : clusters.entrySet()) {
            if (c.getValue().size() < 2) {
                continue;
            }
            multiMemberClusters++;
            report.append("- **")
                    .append(c.getKey())
                    .append("-*** (")
                    .append(c.getValue().size())
                    .append(" members): ")
                    .append(String.join(", ", c.getValue()))
                    .append("\n");
        }
        if (multiMemberClusters == 0) {
            report.append("(no clusters of 2+ skills found)\n");
        }
        report.append("\n## Structured summary (required)\n\n```yaml\n");
        report.append("consolidations: []\n");
        report.append("prunings: []\n");
        report.append("```\n");

        try {
            filesystem.uploadFiles(
                    RuntimeContext.empty(),
                    List.of(
                            new AbstractMap.SimpleImmutableEntry<>(
                                    reportPath,
                                    report.toString().getBytes(StandardCharsets.UTF_8))));
        } catch (Exception e) {
            log.warn("Failed to write curator report: {}", e.getMessage());
            return null;
        }
        return reportPath;
    }

    // ---------------------------------------------------------------------
    // 组合入口
    // ---------------------------------------------------------------------

    public record CuratorRunReport(
            TransitionCounts transitions,
            String dryRunReportPath,
            Instant ranAt,
            long durationMs) {}

    /**
     * 执行完整 curator 阶段：第一阶段状态转换，加第二阶段试运行报告。
     */
    public CuratorRunReport runOnce(Instant now) {
        long startNanos = System.nanoTime();
        Instant ranAt = now != null ? now : Instant.now();
        TransitionCounts counts = applyAutomaticTransitions(ranAt);
        String reportPath = runUmbrellaDryRunReport(ranAt);
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;

        // 持久化运行状态。
        SkillCuratorState s = loadState();
        String summary =
                String.format(
                        "checked=%d markedStale=%d archived=%d reactivated=%d",
                        counts.checked(),
                        counts.markedStale(),
                        counts.archived(),
                        counts.reactivated());
        saveState(
                new SkillCuratorState(
                        ranAt,
                        s.runCount() + 1,
                        s.paused(),
                        summary,
                        durationMs / 1000,
                        reportPath));

        return new CuratorRunReport(counts, reportPath, ranAt, durationMs);
    }
}
