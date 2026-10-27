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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * 每个技能的运行遥测及企业暂存审计字段。作为单个 JSON 对象持久化到工作区旁挂文件 {@code skills/.usage.json}（每个技能一条记录，以技能名称为键）。
 *
 * <p>字段命名有意对齐 hermes-agent 的 {@code tools/skill_usage.py} 旁挂文件格式（通过 {@link JsonProperty} 使用 snake_case），使用户在两个生态间复制技能时可以共享遥测文件。
 *
 * <p>{@code createdBy} 来源值：
 * <ul>
 * <li>{@code null}：用户编写、技能中心安装或原有技能（不参与 curator 自动管理或灰度门控）。</li>
 * <li>{@code "agent-draft"}：智能体通过 {@code skill_manage} 创建，但尚未晋级。</li>
 * <li>{@code "agent"}：智能体创建且已晋级到正式技能根目录的技能。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkillUsageRecord(
        @JsonProperty("created_by") String createdBy,
        @JsonProperty("use_count") long useCount,
        @JsonProperty("view_count") long viewCount,
        @JsonProperty("patch_count") long patchCount,
        @JsonProperty("last_used_at") Instant lastUsedAt,
        @JsonProperty("last_viewed_at") Instant lastViewedAt,
        @JsonProperty("last_patched_at") Instant lastPatchedAt,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("state") State state,
        @JsonProperty("pinned") boolean pinned,
        @JsonProperty("archived_at") Instant archivedAt,
        @JsonProperty("promoted_at") Instant promotedAt,
        @JsonProperty("promoted_by") String promotedBy,
        @JsonProperty("source_session_id") String sourceSessionId,
        @JsonProperty("environments") List<String> environments) {

    public enum State {
        @JsonProperty("draft")
        DRAFT,
        @JsonProperty("active")
        ACTIVE,
        @JsonProperty("stale")
        STALE,
        @JsonProperty("archived")
        ARCHIVED
    }

    /**
     * 新纳入跟踪的技能（刚出现在磁盘上）的默认记录。
     */
    public static SkillUsageRecord defaults() {
        return new SkillUsageRecord(
                null,
                0L,
                0L,
                0L,
                null,
                null,
                null,
                Instant.now(),
                State.ACTIVE,
                false,
                null,
                null,
                null,
                null,
                List.of());
    }

    /**
     * 新创建的智能体草稿的默认记录。
     */
    public static SkillUsageRecord newAgentDraft(String sessionId) {
        return new SkillUsageRecord(
                "agent-draft",
                0L,
                0L,
                0L,
                null,
                null,
                null,
                Instant.now(),
                State.DRAFT,
                false,
                null,
                null,
                null,
                sessionId,
                List.of("draft"));
    }

    /**
     * 支持宽容解码的 Jackson 构造方法（缺失字段默认为 null/0/false）。
     */
    @JsonCreator
    public static SkillUsageRecord ofJson(
            @JsonProperty("created_by") String createdBy,
            @JsonProperty("use_count") Long useCount,
            @JsonProperty("view_count") Long viewCount,
            @JsonProperty("patch_count") Long patchCount,
            @JsonProperty("last_used_at") Instant lastUsedAt,
            @JsonProperty("last_viewed_at") Instant lastViewedAt,
            @JsonProperty("last_patched_at") Instant lastPatchedAt,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("state") State state,
            @JsonProperty("pinned") Boolean pinned,
            @JsonProperty("archived_at") Instant archivedAt,
            @JsonProperty("promoted_at") Instant promotedAt,
            @JsonProperty("promoted_by") String promotedBy,
            @JsonProperty("source_session_id") String sourceSessionId,
            @JsonProperty("environments") List<String> environments) {
        return new SkillUsageRecord(
                createdBy,
                useCount == null ? 0L : useCount,
                viewCount == null ? 0L : viewCount,
                patchCount == null ? 0L : patchCount,
                lastUsedAt,
                lastViewedAt,
                lastPatchedAt,
                createdAt == null ? Instant.now() : createdAt,
                state == null ? State.ACTIVE : state,
                pinned != null && pinned,
                archivedAt,
                promotedAt,
                promotedBy,
                sourceSessionId,
                environments == null ? List.of() : environments);
    }

    /**
     * {@code last_used_at}、{@code last_viewed_at}、{@code last_patched_at} 中最新的时间。
     */
    @JsonIgnore
    public Instant latestActivityAt() {
        Instant latest = null;
        for (Instant t : new Instant[] {lastUsedAt, lastViewedAt, lastPatchedAt}) {
            if (t != null && (latest == null || t.isAfter(latest))) {
                latest = t;
            }
        }
        return latest;
    }

    /**
     * {@code use_count}、{@code view_count}、{@code patch_count} 之和。
     */
    @JsonIgnore
    public long activityCount() {
        return useCount + viewCount + patchCount;
    }

    /**
     * 此技能是否符合 curator 自动管理或灰度可见性过滤的条件。
     */
    @JsonIgnore
    public boolean isAgentCreated() {
        return "agent".equals(createdBy);
    }
}
