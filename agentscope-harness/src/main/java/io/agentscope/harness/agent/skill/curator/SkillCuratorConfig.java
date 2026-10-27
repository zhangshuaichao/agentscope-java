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

/**
 * {@link SkillCurator} 的配置项。默认采用设计方案中的保守企业配置：7 天周期、30/90 天过期/归档阈值，LLM 汇总阶段仅试运行。
 */
public final class SkillCuratorConfig {

    public enum UmbrellaPassMode {
        /**
         * 仅第一阶段：执行 active/stale/archive 自动转换。
         */
        DISABLED,
        /**
         * 运行汇总阶段但不调用 skill_manage，仅输出报告（当前为无 LLM 的占位实现）。
         */
        DRY_RUN_ONLY
    }

    private final boolean enabled;
    private final int intervalHours;
    private final int staleAfterDays;
    private final int archiveAfterDays;
    private final UmbrellaPassMode umbrellaPassMode;

    private SkillCuratorConfig(Builder b) {
        this.enabled = b.enabled;
        this.intervalHours = b.intervalHours;
        this.staleAfterDays = b.staleAfterDays;
        this.archiveAfterDays = b.archiveAfterDays;
        this.umbrellaPassMode = b.umbrellaPassMode;
    }

    public boolean enabled() {
        return enabled;
    }

    public int intervalHours() {
        return intervalHours;
    }

    public int staleAfterDays() {
        return staleAfterDays;
    }

    public int archiveAfterDays() {
        return archiveAfterDays;
    }

    public UmbrellaPassMode umbrellaPassMode() {
        return umbrellaPassMode;
    }

    public static SkillCuratorConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean enabled = true;
        private int intervalHours = 24 * 7; // 7 天
        private int staleAfterDays = 30;
        private int archiveAfterDays = 90;
        private UmbrellaPassMode umbrellaPassMode = UmbrellaPassMode.DRY_RUN_ONLY;

        private Builder() {}

        public Builder enabled(boolean v) {
            this.enabled = v;
            return this;
        }

        public Builder intervalHours(int v) {
            this.intervalHours = Math.max(1, v);
            return this;
        }

        public Builder staleAfterDays(int v) {
            this.staleAfterDays = Math.max(1, v);
            return this;
        }

        public Builder archiveAfterDays(int v) {
            this.archiveAfterDays = Math.max(this.staleAfterDays, v);
            return this;
        }

        public Builder umbrellaPassMode(UmbrellaPassMode mode) {
            this.umbrellaPassMode = mode != null ? mode : UmbrellaPassMode.DRY_RUN_ONLY;
            return this;
        }

        public SkillCuratorConfig build() {
            return new SkillCuratorConfig(this);
        }
    }
}
