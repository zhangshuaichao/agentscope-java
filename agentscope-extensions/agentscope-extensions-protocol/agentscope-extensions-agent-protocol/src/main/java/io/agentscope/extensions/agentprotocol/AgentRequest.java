/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.agentprotocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 供 {@link AgentFactory} 为一轮任务选择或构造智能体的完整信息。
 *
 * @param taskId 任务标识，也用作智能体会话 ID
 * @param agentId 来自 {@code POST /tasks} 的请求智能体 ID
 * @param input 用户输入；恢复运行时为空，改由确认决策承载输入
 * @param userId 从 {@code context.user_id} 解析
 * @param parentSessionId 从 {@code context.parent_session_id} 解析
 * @param resume 是否在恢复此前等待工具确认的任务
 * @param context 原样收到的提交 {@code context} 映射，包含全部自定义键
 */
public record AgentRequest(
        String taskId,
        String agentId,
        String input,
        String userId,
        String parentSessionId,
        boolean resume,
        Map<String, Object> context) {

    public AgentRequest {
        // JSON 载荷可能包含 null 值，因此这里不能使用 Map.copyOf。
        context =
                context == null || context.isEmpty()
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(context));
    }

    /** 返回 {@code context} 中 {@code key} 对应的原始值；不存在时返回 {@code null}。 */
    public Object contextValue(String key) {
        return key == null ? null : context.get(key);
    }

    /** 将 {@code context} 中 {@code key} 对应的值作为字符串返回；不存在时返回 {@code null}。 */
    public String contextString(String key) {
        Object v = contextValue(key);
        return v == null ? null : String.valueOf(v);
    }

    /** 调用方在 {@code context.attributes} 中定义的属性；未提供时返回空映射。这些值会传入智能体的 {@link io.agentscope.core.agent.RuntimeContext}；外层 {@link #context()} 还包含协议自身的字段。 */
    public Map<String, Object> attributes() {
        Object raw = context.get(AgentProtocolConstants.CONTEXT_ATTRIBUTES_FIELD);
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** 将 {@code context.attributes} 中 {@code key} 对应的值作为字符串返回；不存在时返回 {@code null}。 */
    public String attributeString(String key) {
        Object v = key == null ? null : attributes().get(key);
        return v == null ? null : String.valueOf(v);
    }
}
