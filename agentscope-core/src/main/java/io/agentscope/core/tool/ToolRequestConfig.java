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
package io.agentscope.core.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 不可变的逐调用工具请求配置。与共享无状态 {@link Toolkit} 组合而非复制，生成一次调用可见的工具范围，从不修改或复制共享工具集。
 *
 * <p>由外部工具注入层（当前为 AG-UI 适配器）产生；{@link #NONE} 表示原样使用共享工具集。{@link #externalTools()} 为 {@link SchemaOnlyTool} 实例：本框架的外部工具按定义仅包含模式，没有本地实现。{@code SchemaOnlyTool} 不携带 {@code groupManager} 或注册表反向引用，可安全地按调用构造并保存于不可变配置。具体类型 {@code Map<String, SchemaOnlyTool>} 而非 {@code Map<String, AgentTool>}，将“外部工具必为仅模式工具”确立为编译期不变量。
 *
 * <p>契约：无论 {@code mergeMode} 为何，{@code externalTools} 始终优先于后端；{@code mergeMode} 只控制后端可见性（{@link ToolMergeMode#EXTERNAL_ONLY} 隐藏后端而不修改注册表）。因此 {@code AGENT_ONLY} 只能与空工具映射配对（即 {@link #NONE}）；非空 {@code externalTools} 与 {@code AGENT_ONLY} 的组合会被拒绝。
 */
public record ToolRequestConfig(
        Map<String, SchemaOnlyTool> externalTools, // 名称 -> 外部工具（仅模式），覆盖
        // 后端工具
        ToolMergeMode mergeMode // AGENT_ONLY 意味着 NONE；否则为 MERGE_EXTERNAL_PRIORITY 或
        // EXTERNAL_ONLY
        ) {

    /**
     * 表示原样使用共享工具集的哨兵值。
     */
    public static final ToolRequestConfig NONE =
            new ToolRequestConfig(Map.of(), ToolMergeMode.AGENT_ONLY);

    public ToolRequestConfig {
        if (externalTools == null || externalTools.isEmpty()) {
            externalTools = Map.of();
        } else {
            // 保留插入顺序（调用方使用有序 LinkedHashMap，且模式生成
            // 对顺序敏感），同时使映射不可变；Map.copyOf() 会
            // 丢失顺序。立即验证每个条目，使此值类型能像 Map.copyOf 一样对 null
            // 快速失败，而不是将错误推迟到模式生成时。
            Map<String, SchemaOnlyTool> copy = new LinkedHashMap<>();
            externalTools.forEach(
                    (name, tool) ->
                            copy.put(
                                    Objects.requireNonNull(
                                            name, "external tool name cannot be null"),
                                    Objects.requireNonNull(tool, "external tool cannot be null")));
            externalTools = Collections.unmodifiableMap(copy);
        }
        mergeMode = mergeMode != null ? mergeMode : ToolMergeMode.MERGE_EXTERNAL_PRIORITY;
        if (mergeMode == ToolMergeMode.AGENT_ONLY && !externalTools.isEmpty()) {
            throw new IllegalArgumentException("AGENT_ONLY cannot include external tools");
        }
        externalTools.forEach(
                (name, tool) -> {
                    if (!name.equals(tool.getName())) {
                        throw new IllegalArgumentException(
                                "External tool key must match its schema name");
                    }
                });
    }

    /**
     * 解析本请求配置下 {@code name} 可见的工具：外部工具优先；{@link ToolMergeMode#EXTERNAL_ONLY} 模式下隐藏后端；否则查询给定后端注册表。这是“外部工具 → 隐藏后端 → 注册表”策略的唯一实现，由 {@link Toolkit#getTool(String, ToolRequestConfig)} 和执行器共享，避免逻辑偏离。
     */
    AgentTool resolveTool(String name, ToolRegistry registry) {
        AgentTool external = externalTools.get(name);
        if (external != null) {
            return external;
        }
        if (hidesBackend()) {
            return null;
        }
        return registry.getTool(name);
    }

    /**
     * 此配置是否完全隐藏后端注册表（请求级 {@link ToolMergeMode#EXTERNAL_ONLY}）。由 {@link #resolveTool(String, ToolRegistry)} 和 {@link Toolkit#getToolSchemas(java.util.Collection, ToolRequestConfig)} 共享，保证策略一致。
     */
    boolean hidesBackend() {
        return mergeMode == ToolMergeMode.EXTERNAL_ONLY;
    }

    /**
     * {@code name} 是否被外部工具遮蔽；无论合并模式为何，外部工具始终覆盖后端。由 {@link #resolveTool(String, ToolRegistry)}、执行器可用性门禁和 {@link Toolkit#getToolSchemas(java.util.Collection, ToolRequestConfig)} 共享。
     */
    boolean overrides(String name) {
        return externalTools.containsKey(name);
    }
}
