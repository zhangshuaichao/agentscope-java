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
package io.agentscope.harness.agent.memory.compaction;

import java.util.Set;

/**
 * 针对单个工具结果中过大输出的转存配置。
 *
 * <p>当工具结果文本超过 {@link #getMaxResultChars()} 时，完整输出会通过工作区文件系统抽象
 * 写入 {@link #getEvictionPath()} 下的确定性路径；上下文中的
 * {@link io.agentscope.core.message.ToolResultBlock} 则替换为紧凑占位内容，
 * 包含首尾预览以及使用 {@code readFile} 读取完整内容的指令。
 *
 * <p>此机制与对话摘要（{@link CompactionConfig}）<b>正交</b>：
 * <ul>
 * <li><b>Eviction（转存）</b>处理上下文的<em>宽度</em>：单条消息过大。</li>
 * <li><b>Compaction（压缩）</b>处理上下文的<em>深度</em>：累计消息过多。</li>
 * </ul>
 * 两者使用不同触发条件和生命周期事件，彼此独立工作。
 *
 * <ul>
 * <li>在 80,000 个字符时触发（按每 token 4 个字符估算约 20 K tokens）。</li>
 * <li>预览保留原始输出开头和末尾各 2,000 个字符。</li>
 * <li>转存路径前缀为 {@code large_tool_results}，相对于工作区。</li>
 * <li>排除文件系统读写编辑及记忆工具（结果较小或自行分页）。</li>
 * </ul>
 */
public class ToolResultEvictionConfig {

    /** 约 20 K tokens × 每 token 4 个字符：默认转存阈值。 */
    public static final int DEFAULT_MAX_RESULT_CHARS = 80_000;

    /** 转存占位内容首尾预览显示的字符数。 */
    public static final int DEFAULT_PREVIEW_CHARS = 2_000;

    /** 存放已转存结果的工作区相对路径前缀。 */
    public static final String DEFAULT_EVICTION_PATH = "large_tool_results";

    /**
     * 默认排除在转存之外的工具。
     * <ul>
     * <li>{@code read_file}：转存可能造成重复读取循环；大小由分页处理。</li>
     * <li>{@code write_file}、{@code edit_file}：只返回很短的成功消息。</li>
     * <li>搜索/列表工具有受限预览，但仍可参与转存。</li>
     * <li>{@code memory_search}、{@code memory_get}、{@code session_search}：结果较小或分页。</li>
     * </ul>
     * Shell（{@code execute}）有意不排除，因为命令输出可能很大。
     */
    public static final Set<String> DEFAULT_EXCLUDED_TOOLS =
            Set.of(
                    "read_file",
                    "write_file",
                    "edit_file",
                    "memory_search",
                    "memory_get",
                    "session_search");

    private final int maxResultChars;
    private final int previewChars;
    private final String evictionPath;
    private final Set<String> excludedToolNames;

    private ToolResultEvictionConfig(Builder builder) {
        this.maxResultChars = builder.maxResultChars;
        this.previewChars = builder.previewChars;
        this.evictionPath = builder.evictionPath;
        this.excludedToolNames = builder.excludedToolNames;
    }

    /** 创建采用全部默认值的配置。 */
    public static ToolResultEvictionConfig defaults() {
        return new Builder().build();
    }

    /** 触发转存前允许的最大文本长度（字符数）。 */
    public int getMaxResultChars() {
        return maxResultChars;
    }

    /** 首尾预览中显示的字符数。 */
    public int getPreviewChars() {
        return previewChars;
    }

    /** 写入转存文件的根路径，例如 {@code large_tool_results}。 */
    public String getEvictionPath() {
        return evictionPath;
    }

    /** 无论结果多大都不会转存的工具名称。 */
    public Set<String> getExcludedToolNames() {
        return excludedToolNames;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** {@link ToolResultEvictionConfig} 的构建器。 */
    public static class Builder {

        private int maxResultChars = DEFAULT_MAX_RESULT_CHARS;
        private int previewChars = DEFAULT_PREVIEW_CHARS;
        private String evictionPath = DEFAULT_EVICTION_PATH;
        private Set<String> excludedToolNames = DEFAULT_EXCLUDED_TOOLS;

        /** 设置触发转存的字符数阈值。 */
        public Builder maxResultChars(int maxResultChars) {
            this.maxResultChars = maxResultChars;
            return this;
        }

        /** 设置首尾预览包含的字符数。 */
        public Builder previewChars(int previewChars) {
            this.previewChars = previewChars;
            return this;
        }

        /** 设置转存文件的文件系统根路径前缀。 */
        public Builder evictionPath(String evictionPath) {
            this.evictionPath = evictionPath;
            return this;
        }

        /** 替换默认的排除工具名称集合。 */
        public Builder excludedToolNames(Set<String> excludedToolNames) {
            this.excludedToolNames = Set.copyOf(excludedToolNames);
            return this;
        }

        public ToolResultEvictionConfig build() {
            return new ToolResultEvictionConfig(this);
        }
    }
}
