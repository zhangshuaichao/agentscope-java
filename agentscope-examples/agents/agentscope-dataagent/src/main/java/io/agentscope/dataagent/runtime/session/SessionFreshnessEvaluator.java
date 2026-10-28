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
package io.agentscope.dataagent.runtime.session;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 根据最后活动时间和 {@link SessionResetPolicy} 评估会话是否仍有效，
 * 对应 OpenClaw 的 {@code evaluateSessionFreshness}。
 * <p>两个独立的过期条件：
 * <ul>
 *   <li><b>每日重置</b>：{@code updatedAt} 早于系统默认时区中最近一次
 *       {@code policy.dailyResetHour()} 每日边界时过期。</li>
 *   <li><b>空闲超时</b>：{@code now} 超过 {@code updatedAt + idleMinutes * 60_000} 时过期。</li>
 * </ul>
 * <p>任一条件触发即认为会话过期（{@code fresh = false}）。
 */
public final class SessionFreshnessEvaluator {

    private SessionFreshnessEvaluator() {}

    /**
     * 评估会话有效性。
     * @param updatedAtMs 会话最后活动时间（纪元毫秒）
     * @param nowMs 当前时间（纪元毫秒）
     * @param policy 要应用的重置策略
     * @return 有效性评估结果
     */
    public static SessionFreshness evaluate(
            long updatedAtMs, long nowMs, SessionResetPolicy policy) {
        if (policy.mode() == SessionResetPolicy.ResetMode.NEVER) {
            return new SessionFreshness(true, null, null);
        }

        Long dailyResetAtMs = null;
        boolean staleDaily = false;

        if (policy.mode() == SessionResetPolicy.ResetMode.DAILY
                || policy.mode() == SessionResetPolicy.ResetMode.BOTH) {
            dailyResetAtMs = resolveDailyResetAtMs(nowMs, policy.dailyResetHour());
            staleDaily = updatedAtMs < dailyResetAtMs;
        }

        Long idleExpiresAtMs = null;
        boolean staleIdle = false;

        if (policy.mode() == SessionResetPolicy.ResetMode.IDLE
                || policy.mode() == SessionResetPolicy.ResetMode.BOTH) {
            if (policy.idleMinutes() > 0) {
                idleExpiresAtMs = updatedAtMs + (long) policy.idleMinutes() * 60_000L;
                staleIdle = nowMs > idleExpiresAtMs;
            }
        }

        boolean fresh = !(staleDaily || staleIdle);
        return new SessionFreshness(fresh, dailyResetAtMs, idleExpiresAtMs);
    }

    /** 计算给定小时最近一次每日重置边界。当前时间早于今天边界时，使用昨天的边界。 */
    static long resolveDailyResetAtMs(long nowMs, int resetHour) {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = Instant.ofEpochMilli(nowMs).atZone(zone);
        LocalDate today = now.toLocalDate();
        ZonedDateTime todayBoundary = today.atTime(resetHour, 0).atZone(zone);
        if (now.isBefore(todayBoundary)) {
            todayBoundary = todayBoundary.minusDays(1);
        }
        return todayBoundary.toInstant().toEpochMilli();
    }
}
