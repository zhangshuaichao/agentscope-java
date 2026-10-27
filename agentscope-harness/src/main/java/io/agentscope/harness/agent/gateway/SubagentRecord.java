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
package io.agentscope.harness.agent.gateway;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 面向恢复、可持久化的已公开子智能体描述。
 * 与持有活跃 {@code Agent} 引用的内存 {@code ExposedSession} 不同，
 * {@code SubagentRecord} 只保存任意节点重建和路由子智能体所需的<em>配方</em>：
 * 类型（{@code agentId}）、自己的对话会话（{@code sessionId}）及生命周期元数据。
 *
 * <p>{@code agentId} 交给智能体工厂重建实例；调用时传入 {@code sessionId}，
 * 使重建后的智能体从（分布式）{@code AgentStateStore} 加载正确对话历史。
 *
 * @param subagentId 用户可见的直接寻址句柄
 * @param agentId 子智能体类型标识（用于重建智能体）
 * @param sessionId 子智能体自己的会话 ID（用于加载对话状态）
 * @param userId 发起用户 ID；未知时为 {@code null}
 * @param parentSessionId 公开此子智能体的父会话；可为 {@code null}
 * @param createdAt 子智能体公开时间
 * @param expiresAt 可选过期时刻；{@code null} 表示无 TTL
 */
public record SubagentRecord(
        String subagentId,
        String agentId,
        String sessionId,
        String userId,
        String parentSessionId,
        Instant createdAt,
        Instant expiresAt) {

    /**
     * 此记录的 TTL 相对于 {@code now} 是否已经到期。
     */
    public boolean isExpired(Instant now) {
        return expiresAt != null && now != null && now.isAfter(expiresAt);
    }

    /**
     * 将记录序列化为扁平字符串／数字映射，供 {@code BaseStore} 持久化。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("subagentId", subagentId);
        m.put("agentId", agentId);
        m.put("sessionId", sessionId);
        if (userId != null) {
            m.put("userId", userId);
        }
        if (parentSessionId != null) {
            m.put("parentSessionId", parentSessionId);
        }
        if (createdAt != null) {
            m.put("createdAt", createdAt.toEpochMilli());
        }
        if (expiresAt != null) {
            m.put("expiresAt", expiresAt.toEpochMilli());
        }
        return m;
    }

    /**
     * 从持久化映射重建记录，容忍缺失可选字段。
     */
    public static SubagentRecord fromMap(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        return new SubagentRecord(
                asString(m.get("subagentId")),
                asString(m.get("agentId")),
                asString(m.get("sessionId")),
                asString(m.get("userId")),
                asString(m.get("parentSessionId")),
                asInstant(m.get("createdAt")),
                asInstant(m.get("expiresAt")));
    }

    private static String asString(Object v) {
        return v != null ? v.toString() : null;
    }

    private static Instant asInstant(Object v) {
        if (v instanceof Number n) {
            return Instant.ofEpochMilli(n.longValue());
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(s.trim()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
