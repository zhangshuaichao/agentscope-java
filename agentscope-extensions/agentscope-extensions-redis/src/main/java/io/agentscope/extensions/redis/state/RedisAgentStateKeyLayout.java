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

import java.util.Optional;

/**
 * 构建智能体会话状态的 Redis 键。
 *
 * <p>V0 是原始布局，会话键使用普通的 {@code userId/sessionId} 段。
 * V1 面向 Redis Cluster，将版本化状态键中的同一段放入 Redis 哈希标签：{@code {userId/sessionId}}。
 * 标签内容等于 V0 会话段，让状态键、版本键与会话标记落入同一哈希槽，以便 Lua CAS 操作。
 */
final class RedisAgentStateKeyLayout {

    static final String KEYS_SUFFIX = ":_keys";

    private static final String LIST_SUFFIX = ":list";

    private static final String ANON_USER = "__anon__";

    private final String keyPrefix;

    private final String slotId;

    private RedisAgentStateKeyLayout(String keyPrefix, String slotId) {
        this.keyPrefix = keyPrefix;
        this.slotId = slotId;
    }

    /**
     * 用 Redis 哈希标签包裹规范化会话段，构建 V1 布局。
     * <p>示例：{@code agentscope:session:{user/session}:state}。
     * @param keyPrefix 状态存储配置的 Redis 键前缀
     * @param userId 原始用户 ID，可为 null
     * @param sessionId 必填会话 ID
     * @return 支持集群同槽操作的 V1 键布局
     */
    static RedisAgentStateKeyLayout v1(String keyPrefix, String userId, String sessionId) {
        validateV1KeyPrefix(keyPrefix);
        validateV1UserId(normalizeUser(userId));
        validateV1SessionId(sessionId);
        return new RedisAgentStateKeyLayout(
                keyPrefix, "{" + sessionSegment(userId, sessionId) + "}");
    }

    /**
     * 使用原始未包裹哈希标签的会话段构建 V0 布局。
     * <p>示例：{@code agentscope:session:user/session:state}。
     * @param keyPrefix 状态存储配置的 Redis 键前缀
     * @param userId 原始用户 ID，可为 null
     * @param sessionId 必填会话 ID
     * @return 原始 V0 键布局
     */
    static RedisAgentStateKeyLayout v0(String keyPrefix, String userId, String sessionId) {
        return new RedisAgentStateKeyLayout(keyPrefix, sessionSegment(userId, sessionId));
    }

    /**
     * 将缺省用户 ID 规范化为原 Redis 状态布局使用的同一哨兵值。
     * @param userId 原始用户 ID，可为 null
     * @return 规范化用户段
     */
    static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANON_USER : userId;
    }

    /**
     * 构建扫描用户 V1 会话标记键的模式。
     * @param keyPrefix 状态存储配置的 Redis 键前缀
     * @param userSegment 规范化用户段
     * @return V1 会话标记键的 Redis 扫描模式
     */
    static String v1KeysPattern(String keyPrefix, String userSegment) {
        return escapeRedisGlobLiteral(keyPrefix)
                + "{"
                + escapeRedisGlobLiteral(userSegment)
                + "/*}"
                + KEYS_SUFFIX;
    }

    /**
     * 构建扫描用户 V0 会话标记键的模式。
     * @param keyPrefix 状态存储配置的 Redis 键前缀
     * @param userSegment 规范化用户段
     * @return V0 会话标记键的 Redis 扫描模式
     */
    static String v0KeysPattern(String keyPrefix, String userSegment) {
        return escapeRedisGlobLiteral(keyPrefix)
                + escapeRedisGlobLiteral(userSegment)
                + "/*"
                + KEYS_SUFFIX;
    }

    /**
     * 验证 V1 前缀不会改变会话段外添加的 Redis 哈希标签。
     * @param keyPrefix 状态存储配置的 Redis 键前缀
     * @throws IllegalArgumentException 前缀包含哈希标签花括号时抛出
     */
    static void validateV1KeyPrefix(String keyPrefix) {
        rejectCharacters(keyPrefix, "keyPrefix", false);
    }

    /**
     * 验证规范化 V1 用户 ID 在 {@code {userId/sessionId}} 中无歧义。
     * @param userId 规范化用户 ID
     * @throws IllegalArgumentException ID 含花括号或路径分隔符时抛出
     */
    static void validateV1UserId(String userId) {
        rejectCharacters(userId, "userId", true);
    }

    private static void validateV1SessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        rejectCharacters(sessionId, "sessionId", true);
    }

    private static void rejectCharacters(String value, String name, boolean rejectSlash) {
        if (value.indexOf('{') >= 0
                || value.indexOf('}') >= 0
                || (rejectSlash && value.indexOf('/') >= 0)) {
            throw new IllegalArgumentException(
                    name + ": " + value + " contains a character that is not supported by V1");
        }
    }

    /**
     * 转义 Redis glob 元字符，使 {@code SCAN MATCH} 按字面匹配此值。
     * <p>仅转换临时扫描模式，不修改调用方原值或为其保存的 Redis 键。
     * @param value 要嵌入 Redis glob 模式的字面键段
     * @return 用于 glob 字面匹配的编码后键段
     */
    private static String escapeRedisGlobLiteral(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '\\'
                    || character == '*'
                    || character == '?'
                    || character == '['
                    || character == ']') {
                escaped.append('\\');
            }
            escaped.append(character);
        }
        return escaped.toString();
    }

    /**
     * 解析 V0 会话标记键，匹配用户段时返回会话 ID。
     * @param keysKey Redis 会话标记键
     * @param keyPrefix 状态存储配置的 Redis 键前缀
     * @param userSegment 规范化用户段
     * @return 解析出的会话 ID；不匹配 V0 布局时为空
     */
    static Optional<String> parseV0SessionIdFromKeysKey(
            String keysKey, String keyPrefix, String userSegment) {
        String userPrefix = keyPrefix + userSegment + "/";
        if (!keysKey.startsWith(userPrefix) || !keysKey.endsWith(KEYS_SUFFIX)) {
            return Optional.empty();
        }
        return Optional.of(
                keysKey.substring(userPrefix.length(), keysKey.length() - KEYS_SUFFIX.length()));
    }

    /**
     * 解析 V1 会话标记键，返回不含 Redis 哈希标签花括号的会话 ID。
     * @param keysKey Redis 会话标记键
     * @param keyPrefix 状态存储配置的 Redis 键前缀
     * @param userSegment 规范化用户段
     * @return 解析出的会话 ID；不匹配 V1 布局时为空
     */
    static Optional<String> parseV1SessionIdFromKeysKey(
            String keysKey, String keyPrefix, String userSegment) {
        String userPrefix = keyPrefix + "{" + userSegment + "/";
        String suffix = "}" + KEYS_SUFFIX;
        if (!keysKey.startsWith(userPrefix) || !keysKey.endsWith(suffix)) {
            return Optional.empty();
        }
        return Optional.of(
                keysKey.substring(userPrefix.length(), keysKey.length() - suffix.length()));
    }

    /**
     * 构建当前布局中单值状态的 Redis 字符串键。
     * @param key 会话内的状态键
     * @return 单值状态的 Redis 键
     */
    String getStateKey(String key) {
        return keyPrefix + slotId + ":" + key;
    }

    /**
     * 构建当前布局中列表状态的 Redis 列表键。
     * @param key 会话内的状态键
     * @return 列表状态的 Redis 键
     */
    String getListKey(String key) {
        return getStateKey(key) + LIST_SUFFIX;
    }

    /**
     * 构建跟踪列表状态键所用的会话标记成员。
     * @param key 会话内的状态键
     * @return 列表状态键的标记成员
     */
    String getListTrackKey(String key) {
        return key + LIST_SUFFIX;
    }

    /**
     * 判断会话标记成员是否表示列表状态键。
     * @param trackedKey 会话标记集合中的成员
     * @return 表示列表状态时为 true
     */
    static boolean isListTrackKey(String trackedKey) {
        return trackedKey.endsWith(LIST_SUFFIX);
    }

    /**
     * 返回列表标记成员代表的状态键名。
     * @param trackedKey 会话标记集合中的列表成员
     * @return 去除列表标记后缀的状态键名
     */
    static String baseKeyFromListTrackKey(String trackedKey) {
        return trackedKey.substring(0, trackedKey.length() - LIST_SUFFIX.length());
    }

    /**
     * 构建跟踪当前会话布局中所有状态键的 Redis 集合键。
     * @return 会话标记集合的 Redis 键
     */
    String getKeysKey() {
        return keyPrefix + slotId + KEYS_SUFFIX;
    }

    /**
     * 返回当前布局使用的规范化会话段。
     * @return 当前键布局的槽标识段
     */
    String slotId() {
        return slotId;
    }

    /**
     * 构建 V0 与 V1 共享的规范化 {@code userId/sessionId} 段。
     * @param userId 原始用户 ID，可为 null
     * @param sessionId 必填会话 ID
     * @return 规范化会话段
     */
    private static String sessionSegment(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return normalizeUser(userId) + "/" + sessionId;
    }
}
