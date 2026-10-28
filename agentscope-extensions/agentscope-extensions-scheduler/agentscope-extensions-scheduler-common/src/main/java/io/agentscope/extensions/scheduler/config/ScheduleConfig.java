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
package io.agentscope.extensions.scheduler.config;

import io.agentscope.extensions.scheduler.AgentScheduler;
import java.util.Objects;

/**
 * 智能体任务的调度配置，包含模式及时间参数。
 * <p>NONE：默认不自动调度，仅手动执行；CRON：按表达式调度；FIXED_RATE：开始时间按固定间隔；FIXED_DELAY：上次完成到下次开始之间固定延迟。
 * <p>使用示例（下方原示例数值保留；fixedRate/fixedDelay/initialDelay 实际单位为毫秒，不是示例旧文字所称的分钟）：
 * <pre>{@code
 * // 基于 Cron 的调度
 * ScheduleConfig config1 = ScheduleConfig.builder()
 *     .cron("0 0 8 * * ?")
 *     .zoneId("Asia/Shanghai")
 *     .build();
 *
 * // 固定频率调度（下方数值 5L 的实际单位为毫秒）
 * ScheduleConfig config2 = ScheduleConfig.builder()
 *     .fixedRate(5L)
 *     .initialDelay(10L)
 *     .build();
 *
 * // 固定延迟调度（下方数值 5L 表示完成后延迟 5 毫秒）
 * ScheduleConfig config3 = ScheduleConfig.builder()
 *     .fixedDelay(5L)
 *     .build();
 * }</pre>
 * @see ScheduleMode
 * @see AgentScheduler
 */
public class ScheduleConfig {

    private final ScheduleMode scheduleMode;
    private final String cronExpression;
    private final Long fixedRate;
    private final Long fixedDelay;
    private final Long initialDelay;
    private final String zoneId;

    private ScheduleConfig(Builder builder) {
        this.scheduleMode = builder.scheduleMode;
        this.cronExpression = builder.cronExpression;
        this.fixedRate = builder.fixedRate;
        this.fixedDelay = builder.fixedDelay;
        this.initialDelay = builder.initialDelay;
        this.zoneId = builder.zoneId;
        validate();
    }

    /**
     * 按调度模式校验配置。
     */
    private void validate() {
        Objects.requireNonNull(scheduleMode, "Schedule mode must not be null");
        switch (scheduleMode) {
            case NONE:
                // NONE 模式无需校验
                break;
            case CRON:
                if (cronExpression == null || cronExpression.trim().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Cron expression must not be null or empty for CRON mode");
                }
                break;
            case FIXED_RATE:
                if (fixedRate == null || fixedRate <= 0) {
                    throw new IllegalArgumentException(
                            "Fixed rate must be a positive value for FIXED_RATE mode");
                }
                break;
            case FIXED_DELAY:
                if (fixedDelay == null || fixedDelay <= 0) {
                    throw new IllegalArgumentException(
                            "Fixed delay must be a positive value for FIXED_DELAY mode");
                }
                break;
        }
    }

    /**
     * 创建构建器。
     * @return 新 ScheduleConfig.Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 获取调度模式。
     * @return 调度模式
     */
    public ScheduleMode getScheduleMode() {
        return scheduleMode;
    }

    /**
     * 获取 Cron 表达式，仅适用于 CRON。
     * @return Cron 表达式，非 CRON 调度时为 null
     */
    public String getCronExpression() {
        return cronExpression;
    }

    /**
     * 获取固定频率间隔，单位毫秒，仅适用于 FIXED_RATE。
     * @return 固定频率间隔毫秒数，非该模式时为 null
     */
    public Long getFixedRate() {
        return fixedRate;
    }

    /**
     * 获取固定延迟，单位毫秒，仅适用于 FIXED_DELAY。
     * @return 固定延迟毫秒数，非该模式时为 null
     */
    public Long getFixedDelay() {
        return fixedDelay;
    }

    /**
     * 获取首次执行前的初始延迟。
     * @return 初始延迟毫秒数，未配置时为 null
     */
    public Long getInitialDelay() {
        return initialDelay;
    }

    /**
     * 获取 Cron 表达式计算的时区 ID，例如 "Asia/Shanghai"、"America/New_York"、"UTC"。
     * @return 时区 ID 字符串，为 null 时使用系统默认
     */
    public String getZoneId() {
        return zoneId;
    }

    /**
     * 创建 ScheduleConfig 的构建器。
     */
    public static class Builder {
        private ScheduleMode scheduleMode = ScheduleMode.NONE; // 默认使用 NONE
        private String cronExpression;
        private Long fixedRate;
        private Long fixedDelay;
        private Long initialDelay;
        private String zoneId;

        private Builder() {}

        /**
         * 配置 Cron 调度。下方保留上游字段示意图；实际表达式语法须服从所选后端，Quartz 的星期字段不能直接按图中 0-6 解释。
         * <pre>
         * ┌───────────── second (0-59)
         * │ ┌───────────── minute (0-59)
         * │ │ ┌───────────── hour (0-23)
         * │ │ │ ┌───────────── day of month (1-31)
         * │ │ │ │ ┌───────────── month (1-12 or JAN-DEC)
         * │ │ │ │ │ ┌───────────── day of week (0-6 or SUN-SAT)
         * │ │ │ │ │ │
         * * * * * * *
         * </pre>
         * <p>示例："0 0 8 * * ?" 每天 8 点；"0 0/30 * * * ?" 每 30 分钟；"0 0 9 * * MON-FRI" 工作日 9 点。
         * @param expression Cron 表达式
         * @return 当前构建器
         */
        public Builder cron(String expression) {
            this.scheduleMode = ScheduleMode.CRON;
            this.cronExpression = expression;
            return this;
        }

        /**
         * 配置固定频率调度。若上次仍在运行，下一次可能在其结束后立即开始，取决于实现的并发策略。
         * <p>例如间隔 5 分钟，目标启动时间为 T、T+5min、T+10min，不取决于单次执行耗时。
         * @param rate 启动间隔，单位毫秒
         * @return 当前构建器
         */
        public Builder fixedRate(Long rate) {
            this.scheduleMode = ScheduleMode.FIXED_RATE;
            this.fixedRate = rate;
            return this;
        }

        /**
         * 配置固定延迟调度：上次完成到下次开始之间等待固定时长。
         * <p>例如延迟 5 分钟且执行耗时 2 分钟，则下次在上次启动 7 分钟后开始。
         * @param delay 完成到下次执行的延迟，单位毫秒
         * @return 当前构建器
         */
        public Builder fixedDelay(Long delay) {
            this.scheduleMode = ScheduleMode.FIXED_DELAY;
            this.fixedDelay = delay;
            return this;
        }

        /**
         * 设置首次执行前的初始延迟。配置可用于调度类型，具体应用方式由后端触发器实现决定。
         * @param delay 初始延迟毫秒数
         * @return 当前构建器
         */
        public Builder initialDelay(Long delay) {
            this.initialDelay = delay;
            return this;
        }

        /**
         * 设置 Cron 表达式计算的时区 ID，仅适用于 CRON，未指定时使用系统默认。
         * <p>例如 "Asia/Shanghai"、"America/New_York"、"UTC"、"Europe/London"。
         * @param zoneIdStr 时区 ID 字符串，例如 "Asia/Shanghai"
         * @return 当前构建器
         */
        public Builder zoneId(String zoneIdStr) {
            this.zoneId = zoneIdStr;
            return this;
        }

        /**
         * 构建 ScheduleConfig。
         * @return 新实例
         * @throws IllegalArgumentException 配置无效时
         */
        public ScheduleConfig build() {
            return new ScheduleConfig(this);
        }
    }
}
