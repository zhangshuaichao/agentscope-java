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
package io.agentscope.builder.runtime.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 可选的 {@code session} 配置块，位于 {@code agentscope.json} ，控制会话生命周期与维护。
 *
 * <h2>示例</h2>
 *
 * <pre>{@code
 * "session": {
 *   "reset": { "dailyAt": "04:00", "idleMinutes": 360 },
 *   "maintenance": { "mode": "prune", "pruneAfter": "7d", "maxEntries": 1000 }
 * }
 * }</pre>
 *
 * <p>对应 OpenClaw 的会话级配置。运行时映射为：
 *
 * <ul>
 *   <li>{@code SessionMaintenanceConfig} 用于清理/数量限制策略
 *   <li>定时任务 ({@code SessionLifecycleScheduler}) 负责触发重置事件
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionLifecycleConfig {

    @JsonProperty("reset")
    private ResetConfig reset;

    @JsonProperty("maintenance")
    private MaintenanceConfig maintenance;

    public ResetConfig getReset() {
        return reset;
    }

    public void setReset(ResetConfig reset) {
        this.reset = reset;
    }

    public MaintenanceConfig getMaintenance() {
        return maintenance;
    }

    public void setMaintenance(MaintenanceConfig maintenance) {
        this.maintenance = maintenance;
    }

    // -----------------------------------------------------------------
    // 重置配置（自动重置触发条件）
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResetConfig {

        /** 每日自动重置所有会话的 24 小时制时间（{@code "HH:mm"}）。null 或空白表示禁用。按 JVM 默认时区解释。 */
        @JsonProperty("dailyAt")
        private String dailyAt;

        /** 会话空闲（无活动）达到此分钟数后，在下一条入站消息到达时满足自动重置条件。{@code 0} 表示禁用。 */
        @JsonProperty("idleMinutes")
        private Integer idleMinutes;

        public String getDailyAt() {
            return dailyAt;
        }

        public void setDailyAt(String dailyAt) {
            this.dailyAt = dailyAt;
        }

        public Integer getIdleMinutes() {
            return idleMinutes;
        }

        public void setIdleMinutes(Integer idleMinutes) {
            this.idleMinutes = idleMinutes;
        }
    }

    // -----------------------------------------------------------------
    // 维护配置（后台清理/数量限制）
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MaintenanceConfig {

        /** {@code "off"}（不操作）、{@code "prune"}（基于时间）或 {@code "cap"}（基于条目数）。 */
        @JsonProperty("mode")
        private String mode;

        /** 会话被视为过期并移除前的最大存活时间。支持 {@code "7d"}、{@code "24h"}、{@code "60m"} 等时长，或表示毫秒数的 long 值。 */
        @JsonProperty("pruneAfter")
        private String pruneAfter;

        /** 会话条目数量的硬上限；超限时优先淘汰最旧的条目。 */
        @JsonProperty("maxEntries")
        private Integer maxEntries;

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getPruneAfter() {
            return pruneAfter;
        }

        public void setPruneAfter(String pruneAfter) {
            this.pruneAfter = pruneAfter;
        }

        public Integer getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(Integer maxEntries) {
            this.maxEntries = maxEntries;
        }

        /** 将 {@code "7d"} 或 {@code "60m"} 等时长字符串解析为毫秒数。 */
        public long pruneAfterMs() {
            if (pruneAfter == null || pruneAfter.isBlank()) return 0L;
            String s = pruneAfter.trim().toLowerCase();
            try {
                if (s.endsWith("ms")) return Long.parseLong(s.substring(0, s.length() - 2).trim());
                if (s.endsWith("s")) {
                    return Long.parseLong(s.substring(0, s.length() - 1).trim()) * 1_000L;
                }
                if (s.endsWith("m")) {
                    return Long.parseLong(s.substring(0, s.length() - 1).trim()) * 60_000L;
                }
                if (s.endsWith("h")) {
                    return Long.parseLong(s.substring(0, s.length() - 1).trim()) * 3_600_000L;
                }
                if (s.endsWith("d")) {
                    return Long.parseLong(s.substring(0, s.length() - 1).trim()) * 86_400_000L;
                }
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
    }
}
