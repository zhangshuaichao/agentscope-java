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

/**
 * 调度模式枚举，定义智能体执行的不同时间策略。
 * @see ScheduleConfig
 */
public enum ScheduleMode {

    /**
     * 不自动调度，默认模式。NONE 下仍可通过正常调用方法手动执行，适用于按需或手动触发。
     */
    NONE,

    /**
     * 基于 Cron 表达式的调度，支持每周一 9 点、每月首日等复杂模式。
     * <ul><li>"0 0 8 * * ?"：每天 8 点。</li><li>"0 0/30 * * * ?"：每 30 分钟。</li><li>"0 0 9 * * MON-FRI"：工作日 9 点。</li><li>"0 0 0 1 * ?"：每月首日午夜。</li></ul>
     */
    CRON,

    /**
     * 固定频率：各次执行开始时间按固定间隔安排，不取决于执行耗时。若耗时超过间隔，下一次可能在上次完成后立即开始，取决于并发策略。适合定期轮询、健康检查、时间触发。例如 5 分钟频率的目标开始时间为 T、T+5min、T+10min。
     */
    FIXED_RATE,

    /**
     * 固定延迟：上次完成后等待固定时长再开始下一次，保证最小空闲时间。适合顺序处理、限速与防止过载。例如延迟 5 分钟、执行耗时 2 分钟，则相邻开始时间相差 7 分钟。
     */
    FIXED_DELAY
}
