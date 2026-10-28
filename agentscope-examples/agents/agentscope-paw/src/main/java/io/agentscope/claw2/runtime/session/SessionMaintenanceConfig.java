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
 * 会话存储维护配置，控制自动清理过期会话与限制条目总数。
 *
 * @param enabled 是否在创建／更新会话时自动执行维护
 * @param pruneAfterMs 移除此时长内未更新的会话，0 表示禁用
 * @param maxEntries 存储中会话总数上限，0 表示不限
 */
public record SessionMaintenanceConfig(boolean enabled, long pruneAfterMs, int maxEntries) {

    /**
     * 默认禁用维护。
     */
    public static SessionMaintenanceConfig disabled() {
        return new SessionMaintenanceConfig(false, 0, 0);
    }

    /**
     * @param pruneAfterMs 清理早于此毫秒数的会话，例如 7 天为 604_800_000
     * @param maxEntries 最大保留会话数，先淘汰最旧项；0 表示不限
     */
    public static SessionMaintenanceConfig enabled(long pruneAfterMs, int maxEntries) {
        return new SessionMaintenanceConfig(true, pruneAfterMs, maxEntries);
    }
}
