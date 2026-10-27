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

import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.skill.curator.SkillUsageRecord.State;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按技能记录使用情况和来源的旁挂遥测存储。
 *
 * <p>持久化 {@link SkillUsageRecord}，每个技能一条。默认后端将全部记录保存在 {@code <workspace>/skills/.usage.json}；使用 {@link BaseStore} 构造时，每个技能保存在 {@code ["skills", "usage"]} 下，并通过 CAS 更新。
 *
 * <p>【学习提示】计数器必须把“检查来源”和“更新计数”放在同一次后端修改中，否则并发修改会产生检查使用时间差。文件后端的进程内锁与分布式后端的 CAS 覆盖范围不同；接口统一不代表两者具有相同跨节点保证。
 *
 * <p><b>来源门控</b>：计数修改（bumpView/bumpUse/bumpPatch）静默跳过非智能体创建的技能，避免污染内置、技能中心安装或用户编写技能的遥测，对齐 hermes-agent 的 {@code skill_usage._mutate} 行为。
 */
public class SkillUsageStore {

    private static final Logger log = LoggerFactory.getLogger(SkillUsageStore.class);

    /**
     * 文件系统旁挂文件相对于工作区的默认路径。
     */
    public static final String DEFAULT_RELATIVE_PATH = "skills/.usage.json";

    private final SkillUsageBackend backend;

    public SkillUsageStore(AbstractFilesystem filesystem) {
        this(filesystem, DEFAULT_RELATIVE_PATH);
    }

    public SkillUsageStore(AbstractFilesystem filesystem, String relativePath) {
        this(new FilesystemSkillUsageBackend(filesystem, relativePath));
    }

    /**
     * 创建以分布式 {@link BaseStore} 为后端的存储，每个技能对应一个键。
     */
    public static SkillUsageStore baseStore(BaseStore store) {
        return new SkillUsageStore(new BaseStoreSkillUsageBackend(store));
    }

    SkillUsageStore(SkillUsageBackend backend) {
        this.backend = java.util.Objects.requireNonNull(backend, "backend");
    }

    // ---------------------------------------------------------------------
    // 读写
    // ---------------------------------------------------------------------

    /**
     * 加载完整旁挂映射；缺失、不可读或损坏时返回空映射。
     */
    public Map<String, SkillUsageRecord> load() {
        return backend.loadAll();
    }

    /**
     * 持久化完整旁挂映射；失败时尽力而为。
     */
    public void save(Map<String, SkillUsageRecord> data) {
        backend.replaceAll(data != null ? data : Map.of());
    }

    /**
     * 按名称读取单条记录。
     */
    public Optional<SkillUsageRecord> get(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return backend.get(name);
    }

    /**
     * 将 {@code mutator} 应用于 {@code name} 的记录；不存在时创建新的 {@link SkillUsageRecord#defaults()}。并发控制由后端负责（进程内锁或 CAS）。
     */
    private void mutate(String name, UnaryOperator<SkillUsageRecord> mutator) {
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            backend.mutate(name, mutator);
        } catch (Exception e) {
            log.debug("SkillUsageStore.mutate({}) failed: {}", name, e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // 计数递增（来源门控：跳过非智能体创建的技能）
    // ---------------------------------------------------------------------

    public void bumpView(String name) {
        bumpIfAgentTracked(
                name,
                rec ->
                        new SkillUsageRecord(
                                rec.createdBy(),
                                rec.useCount(),
                                rec.viewCount() + 1,
                                rec.patchCount(),
                                rec.lastUsedAt(),
                                Instant.now(),
                                rec.lastPatchedAt(),
                                rec.createdAt(),
                                rec.state(),
                                rec.pinned(),
                                rec.archivedAt(),
                                rec.promotedAt(),
                                rec.promotedBy(),
                                rec.sourceSessionId(),
                                rec.environments()));
    }

    public void bumpUse(String name) {
        bumpIfAgentTracked(
                name,
                rec ->
                        new SkillUsageRecord(
                                rec.createdBy(),
                                rec.useCount() + 1,
                                rec.viewCount(),
                                rec.patchCount(),
                                Instant.now(),
                                rec.lastViewedAt(),
                                rec.lastPatchedAt(),
                                rec.createdAt(),
                                rec.state(),
                                rec.pinned(),
                                rec.archivedAt(),
                                rec.promotedAt(),
                                rec.promotedBy(),
                                rec.sourceSessionId(),
                                rec.environments()));
    }

    public void bumpPatch(String name) {
        bumpIfAgentTracked(
                name,
                rec ->
                        new SkillUsageRecord(
                                rec.createdBy(),
                                rec.useCount(),
                                rec.viewCount(),
                                rec.patchCount() + 1,
                                rec.lastUsedAt(),
                                rec.lastViewedAt(),
                                Instant.now(),
                                rec.createdAt(),
                                rec.state(),
                                rec.pinned(),
                                rec.archivedAt(),
                                rec.promotedAt(),
                                rec.promotedBy(),
                                rec.sourceSessionId(),
                                rec.environments()));
    }

    /**
     * 仅当 {@code name} 的记录存在且 {@code createdBy} 非 null（智能体已显式跟踪该技能）时应用 {@code mutator}。跳过未知或外部技能，使旁挂文件聚焦智能体编写的过程记忆。
     *
     * <p>来源检查在后端修改函数内执行，使 CAS/锁覆盖整个读-改-写，避免单独 get 与 mutate 之间的检查使用时间差（TOCTOU）。
     */
    private void bumpIfAgentTracked(String name, UnaryOperator<SkillUsageRecord> mutator) {
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            backend.mutate(
                    name,
                    rec -> {
                        if (rec.createdBy() == null) {
                            return rec; // 同一引用意味着后端不操作。
                        }
                        return mutator.apply(rec);
                    });
        } catch (Exception e) {
            log.debug("SkillUsageStore.bumpIfAgentTracked({}) failed: {}", name, e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // 来源/生命周期设置器（始终允许）
    // ---------------------------------------------------------------------

    /**
     * 标记新创建的智能体草稿（由 {@code SkillManageTool.create} 调用）。
     */
    public void markAgentDraft(String name, String sessionId) {
        mutate(
                name,
                rec ->
                        new SkillUsageRecord(
                                "agent-draft",
                                rec.useCount(),
                                rec.viewCount(),
                                rec.patchCount(),
                                rec.lastUsedAt(),
                                rec.lastViewedAt(),
                                rec.lastPatchedAt(),
                                rec.createdAt() != null ? rec.createdAt() : Instant.now(),
                                State.DRAFT,
                                rec.pinned(),
                                rec.archivedAt(),
                                rec.promotedAt(),
                                rec.promotedBy(),
                                sessionId,
                                List.of("draft")));
    }

    /**
     * 将技能标记为已完成晋级的智能体创建技能。由 {@code SkillManageTool} 在 {@code autoPromote=true}（不暂存）时使用，也供晋级门控流程使用。
     */
    public void markAgentCreated(String name, String reviewerId, List<String> environments) {
        mutate(
                name,
                rec ->
                        new SkillUsageRecord(
                                "agent",
                                rec.useCount(),
                                rec.viewCount(),
                                rec.patchCount(),
                                rec.lastUsedAt(),
                                rec.lastViewedAt(),
                                rec.lastPatchedAt(),
                                rec.createdAt() != null ? rec.createdAt() : Instant.now(),
                                State.ACTIVE,
                                rec.pinned(),
                                rec.archivedAt(),
                                Instant.now(),
                                reviewerId,
                                rec.sourceSessionId(),
                                environments != null ? List.copyOf(environments) : List.of()));
    }

    public void setState(String name, State newState) {
        if (newState == null) {
            return;
        }
        mutate(
                name,
                rec ->
                        new SkillUsageRecord(
                                rec.createdBy(),
                                rec.useCount(),
                                rec.viewCount(),
                                rec.patchCount(),
                                rec.lastUsedAt(),
                                rec.lastViewedAt(),
                                rec.lastPatchedAt(),
                                rec.createdAt(),
                                newState,
                                rec.pinned(),
                                newState == State.ARCHIVED ? Instant.now() : null,
                                rec.promotedAt(),
                                rec.promotedBy(),
                                rec.sourceSessionId(),
                                rec.environments()));
    }

    public void setPinned(String name, boolean pinned) {
        mutate(
                name,
                rec ->
                        new SkillUsageRecord(
                                rec.createdBy(),
                                rec.useCount(),
                                rec.viewCount(),
                                rec.patchCount(),
                                rec.lastUsedAt(),
                                rec.lastViewedAt(),
                                rec.lastPatchedAt(),
                                rec.createdAt(),
                                rec.state(),
                                pinned,
                                rec.archivedAt(),
                                rec.promotedAt(),
                                rec.promotedBy(),
                                rec.sourceSessionId(),
                                rec.environments()));
    }

    /**
     * 完全移除记录；技能归档或永久删除时调用。
     */
    public void forget(String name) {
        mutate(name, rec -> null);
    }

    // ---------------------------------------------------------------------
    // 读取视图
    // ---------------------------------------------------------------------

    /**
     * 全部 {@code createdBy} 非 null 的记录（即智能体编写的草稿或正式技能）。
     */
    public List<Map.Entry<String, SkillUsageRecord>> agentCreatedReport() {
        Map<String, SkillUsageRecord> all = load();
        List<Map.Entry<String, SkillUsageRecord>> out = new ArrayList<>();
        for (Map.Entry<String, SkillUsageRecord> e : all.entrySet()) {
            if (e.getValue() != null && e.getValue().createdBy() != null) {
                out.add(Map.entry(e.getKey(), e.getValue()));
            }
        }
        return Collections.unmodifiableList(out);
    }
}
