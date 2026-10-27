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
package io.agentscope.core.tool.mcp;

import io.agentscope.core.agent.RuntimeContext;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * MCP（模型上下文协议）请求元数据的类型命名空间标记。
 *
 * <p>通过 {@link RuntimeContext} 将本类实例注册到 {@link RuntimeContext}，其条目会自动作为 {@link McpTool} 发出的每个 MCP {@link McpSchema.CallToolRequest} 的 {@code meta} 字段。
 *
 * <p>只提取以 {@code McpMeta.class} 类型键注册的对象，其他上下文对象（依赖注入 Bean、配置等）不会泄漏到 MCP meta。
 *
 * <p><b>使用示例：</b>
 *
 * <p>1）通过 {@code RuntimeContext}：
 * <pre>{@code
 * RuntimeContext rtCtx = RuntimeContext.builder()
 *     .put(McpMeta.class, new McpMeta(Map.of("traceId", "abc-123")))
 *     .build();
 * }</pre>
 *
 * <p>2）合并多个元数据来源，同名键以后者覆盖前者：
 * <pre>{@code
 * McpMeta base = new McpMeta(Map.of("traceId", "abc-123"));
 * McpMeta extra = new McpMeta(Map.of("callbackUrl", "https://hook.example.com"));
 * McpMeta merged = McpMeta.merge(base, extra);
 * }</pre>
 *
 * @see McpTool
 * @see RuntimeContext
 */
public record McpMeta(Map<String, Object> entries) {

    /**
     * 使用给定条目创建 McpMeta。
     *
     * @param entries 元数据条目（可为 null 或空）；映射会进行防御性复制
     */
    public McpMeta(Map<String, Object> entries) {
        this.entries =
                entries != null
                        ? Collections.unmodifiableMap(new HashMap<>(entries))
                        : Collections.emptyMap();
    }

    /**
     * 以不可修改映射返回元数据条目。
     *
     * @return 条目，永不为 null（可以为空）
     */
    @Override
    public Map<String, Object> entries() {
        return entries;
    }

    /**
     * 返回元数据是否没有条目。
     *
     * @return 为空为 true，否则为 false
     */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * 将多个 {@code McpMeta} 合并为一个。
     *
     * <p>同名键以后面的实例覆盖前面的实例。
     *
     * @param metas 待合并元数据实例（静默跳过 null）
     * @return 合并后的新 McpMeta；输入均为 null 或空时返回空实例
     */
    public static McpMeta merge(McpMeta... metas) {
        if (metas == null || metas.length == 0) {
            return new McpMeta(null);
        }
        Map<String, Object> merged = new HashMap<>();
        for (McpMeta meta : metas) {
            if (meta != null && !meta.entries.isEmpty()) {
                merged.putAll(meta.entries);
            }
        }
        return new McpMeta(merged);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        McpMeta mcpMeta = (McpMeta) o;
        return Objects.equals(entries, mcpMeta.entries);
    }

    @Override
    public String toString() {
        return "McpMeta" + entries;
    }
}
