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
package io.agentscope.extensions.redis.state;

import java.util.List;

/**
 * 基于 Redis 的 {@link io.agentscope.core.state.AgentStateStore} 实现共享的乐观并发辅助逻辑。
 */
public final class RedisStateVersionSupport {

    /**
     * 单个状态值的单调版本计数器对应的 Redis 键后缀。
     */
    public static final String VERSION_SUFFIX = ":ver";

    /**
     * 在 {@link #SAVE_SCRIPT} 内表示无条件覆盖的 Lua {@code ARGV[2]} 哨兵值。
     */
    public static final String UNCONDITIONAL = "-1";

    /**
     * 原子比较并设置载荷，或无条件覆盖并递增版本。
     *
     * <p>KEYS 依次为载荷键、版本键、会话键集合；ARGV 依次为 JSON 载荷、预期版本
     * （{@link #UNCONDITIONAL} 表示无条件递增）、会话集合中的状态键成员。
     * 成功返回新版本，冲突返回 {@code -1}。
     */
    public static final String SAVE_SCRIPT =
            """
            local function current_version()
              if redis.call('EXISTS', KEYS[2]) == 1 then
                return tonumber(redis.call('GET', KEYS[2]))
              end
              if redis.call('EXISTS', KEYS[1]) == 1 then
                return 0
              end
              return 0
            end

            local expected = tonumber(ARGV[2])
            if expected == -1 then
              local newVersion = 1
              if redis.call('EXISTS', KEYS[2]) == 1 then
                newVersion = tonumber(redis.call('GET', KEYS[2])) + 1
              end
              redis.call('SET', KEYS[1], ARGV[1])
              redis.call('SET', KEYS[2], newVersion)
              redis.call('SADD', KEYS[3], ARGV[3])
              return newVersion
            end

            local current = current_version()
            if current ~= expected then
              return -1
            end

            local newVersion = expected + 1
            redis.call('SET', KEYS[1], ARGV[1])
            redis.call('SET', KEYS[2], newVersion)
            redis.call('SADD', KEYS[3], ARGV[3])
            return newVersion
            """;

    private RedisStateVersionSupport() {}

    public static String versionKey(String payloadKey) {
        return payloadKey + VERSION_SUFFIX;
    }

    public static long parseVersion(String payload, String versionValue) {
        if (payload == null) {
            return 0L;
        }
        if (versionValue == null || versionValue.isBlank()) {
            return 0L;
        }
        return Long.parseLong(versionValue);
    }

    public static List<String> saveScriptKeys(
            String payloadKey, String versionKey, String keysKey) {
        return List.of(payloadKey, versionKey, keysKey);
    }

    public static List<String> saveScriptArgs(
            String payload, long expectedVersion, String keyMember) {
        return List.of(payload, Long.toString(expectedVersion), keyMember);
    }

    public static List<String> unconditionalSaveArgs(String payload, String keyMember) {
        return List.of(payload, UNCONDITIONAL, keyMember);
    }
}
