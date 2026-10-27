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
package io.agentscope.harness.agent.memory.session;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 判断会话是否仍然有效，或应当重置。
 *
 * <p>设计参考 agentscope-claw 的 SessionFreshnessEvaluator，支持两种重置策略：
 * <ul>
 * <li><b>每日重置</b>：每天到达配置的小时后，AgentStateStore 重置会话。</li>
 * <li><b>空闲超时</b>：无活动时间超过阈值后，AgentStateStore 重置会话。</li>
 * </ul>
 */
public class SessionFreshnessEvaluator {

    private final int dailyResetHour;
    private final Duration idleTimeout;
    private final ZoneId timezone;

    /**
     * 使用默认设置创建会话有效性评估器：
     * 每天凌晨 4 点重置，空闲超时为 2 小时，使用系统时区。
     */
    public SessionFreshnessEvaluator() {
        this(4, Duration.ofHours(2), ZoneId.systemDefault());
    }

    public SessionFreshnessEvaluator(int dailyResetHour, Duration idleTimeout, ZoneId timezone) {
        this.dailyResetHour = dailyResetHour;
        this.idleTimeout = idleTimeout;
        this.timezone = timezone;
    }

    /**
     * 判断会话是否应视为过期并重置。
     *
     * @param lastActivityAt 会话最后一次活动的时间戳
     * @return 应重置会话时返回 true
     */
    public boolean isStale(Instant lastActivityAt) {
        if (lastActivityAt == null) {
            return true;
        }

        Instant now = Instant.now();

        if (idleTimeout != null
                && Duration.between(lastActivityAt, now).compareTo(idleTimeout) > 0) {
            return true;
        }

        if (dailyResetHour >= 0) {
            ZonedDateTime lastActivity = lastActivityAt.atZone(timezone);
            ZonedDateTime nowZoned = now.atZone(timezone);

            LocalTime resetTime = LocalTime.of(dailyResetHour, 0);
            ZonedDateTime todayReset = nowZoned.toLocalDate().atTime(resetTime).atZone(timezone);

            if (nowZoned.isAfter(todayReset) && lastActivity.isBefore(todayReset)) {
                return true;
            }
        }

        return false;
    }

    public int getDailyResetHour() {
        return dailyResetHour;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }
}
