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
package io.agentscope.claw2.runtime.session;

/**
 * 控制会话何时被视为过期并切换到新会话的策略。
 *
 * @param mode 重置模式：NEVER（默认）、DAILY、IDLE 或 BOTH
 * @param dailyResetHour 每日重置边界小时数（0—23），仅 DAILY 或 BOTH 使用，默认 4
 * @param idleMinutes 会话被视为过期前的空闲分钟数，仅 IDLE 或 BOTH 使用，0 表示禁用
 */
public record SessionResetPolicy(ResetMode mode, int dailyResetHour, int idleMinutes) {

    public enum ResetMode {
        NEVER,
        DAILY,
        IDLE,
        BOTH
    }

    public SessionResetPolicy {
        if (dailyResetHour < 0 || dailyResetHour > 23) {
            throw new IllegalArgumentException(
                    "dailyResetHour must be 0-23, got " + dailyResetHour);
        }
        if (idleMinutes < 0) {
            throw new IllegalArgumentException("idleMinutes must be >= 0, got " + idleMinutes);
        }
    }

    /**
     * 默认策略：会话永不过期。
     */
    public static SessionResetPolicy never() {
        return new SessionResetPolicy(ResetMode.NEVER, 4, 0);
    }

    public static SessionResetPolicy daily(int hour) {
        return new SessionResetPolicy(ResetMode.DAILY, hour, 0);
    }

    public static SessionResetPolicy idle(int minutes) {
        return new SessionResetPolicy(ResetMode.IDLE, 4, minutes);
    }

    public static SessionResetPolicy both(int dailyHour, int idleMinutes) {
        return new SessionResetPolicy(ResetMode.BOTH, dailyHour, idleMinutes);
    }
}
