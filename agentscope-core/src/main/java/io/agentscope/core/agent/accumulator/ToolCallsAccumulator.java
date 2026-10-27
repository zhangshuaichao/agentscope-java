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
package io.agentscope.core.agent.accumulator;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用于累积流式工具调用片段的工具调用累积器。
 *
 * <p>支持多个并行工具调用，负责：
 * <ul>
 * <li>累积工具名称和 ID</li>
 * <li>增量合并参数</li>
 * <li>累积并解析原始 JSON 内容</li>
 * <li>处理占位名称（例如 "__fragment__"）</li>
 * </ul>
 * @hidden
 */
public class ToolCallsAccumulator implements ContentAccumulator<ToolUseBlock> {

    private static final Logger LOG = LoggerFactory.getLogger(ToolCallsAccumulator.class);

    // 用于支持多个并行工具调用的映射
    // 键：工具标识符（ID、名称或索引）
    private final Map<String, ToolCallBuilder> builders = new LinkedHashMap<>();
    private int nextIndex = 0;

    // 跟踪最近的工具调用键，以处理不含 ID 的流式片段
    // 模型返回占位名称且 ID 为空的片段时需要此信息
    private String lastToolCallKey = null;

    /**
     * 单个工具调用的构建器。
     */
    private static class ToolCallBuilder {
        String toolId;
        String name;
        Map<String, Object> args = new HashMap<>();
        StringBuilder rawContent = new StringBuilder();
        Map<String, Object> metadata = new HashMap<>();
        // 最近一次 rawContent JSON 解析失败的异常名称；解析
        // 成功时清除，仅用于在最终构建时报告一次失败
        String parseFailure;
        boolean parseWarned;
        ToolCallState state;

        void merge(ToolUseBlock block) {
            // 存在 ID 时更新
            if (this.toolId == null && block.getId() != null && !block.getId().isEmpty()) {
                this.toolId = block.getId();
            }

            // 更新名称（忽略占位名称）
            if (block.getName() != null
                    && !block.getName().isEmpty()
                    && !isPlaceholder(block.getName())) {
                this.name = block.getName();
            }

            // 合并参数，跳过 null 值，以免早期流片段中的部分／空映射
            // 抹掉先前已累积的非空参数。
            if (block.getInput() != null) {
                for (Map.Entry<String, Object> entry : block.getInput().entrySet()) {
                    if (entry.getValue() != null) {
                        this.args.put(entry.getKey(), entry.getValue());
                    } else if (!this.args.containsKey(entry.getKey())) {
                        this.args.put(entry.getKey(), null);
                    }
                }
            }

            // 累积原始内容（用于解析完整 JSON）
            if (block.getContent() != null) {
                this.rawContent.append(block.getContent());
            }

            // 合并元数据（例如 Gemini 3 Pro 的 thoughtSignature）
            if (block.getMetadata() != null && !block.getMetadata().isEmpty()) {
                this.metadata.putAll(block.getMetadata());
            }

            this.state = block.getState();
        }

        ToolUseBlock build() {
            return build(false);
        }

        ToolUseBlock build(boolean truncated) {
            Map<String, Object> finalArgs = new HashMap<>(args);
            String rawContentStr = this.rawContent.toString();

            // 始终尝试解析完整累积的原始 JSON。早期流片段可能
            // 看起来是完整对象（{...}），但仍包含 null／不完整值；
            // 最终原始内容是补全缺失键或 null 键的权威来源。
            if (!rawContentStr.isEmpty()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed =
                            JsonUtils.getJsonCodec().fromJson(rawContentStr, Map.class);
                    parseFailure = null;
                    if (parsed != null) {
                        for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                            if (entry.getValue() == null) {
                                continue;
                            }
                            Object existing = finalArgs.get(entry.getKey());
                            if (existing == null || !finalArgs.containsKey(entry.getKey())) {
                                finalArgs.put(entry.getKey(), entry.getValue());
                            }
                        }
                    }
                } catch (Exception e) {
                    // 流片段仍在到达时，不完整的 JSON 前缀属于正常情况，
                    // 因此 build() 此时不发出日志；若原始内容最终仍不是合法 JSON，
                    // buildAllToolCalls() 会在最终构建时报告一次失败。
                    // 仅保留异常名称：异常消息可能引用原始参数，
                    // 其中可能含有凭据或其他敏感数据。
                    parseFailure = e.getClass().getSimpleName();
                }
            }

            // 将 rawContent 用作内容前，始终验证它是合法的 JSON 对象，
            // 防止持久化格式错误的 JSON 片段
            // （例如流在参数中途被中断）。原始内容非空但
            // 无效，意味着参数被截断或损坏；
            // 该事实通过元数据标记保留，使行动阶段可以
            // 拒绝执行，而不是悄悄使用 {} 执行调用。
            String contentStr;
            Map<String, Object> finalMetadata = metadata;
            boolean malformed;
            if (rawContentStr.isEmpty()) {
                contentStr = "{}";
                // 没有完整块或任何已解析参数时，输出达到上限而停止
                // 不能证明一个空的工具调用起始片段就是完整的无参调用。
                malformed = truncated && finalArgs.isEmpty();
            } else if (JsonUtils.isValidJsonObject(rawContentStr)) {
                contentStr = rawContentStr;
                malformed = false;
            } else {
                contentStr = "{}";
                malformed = true;
            }
            if (malformed) {
                finalMetadata = new HashMap<>(metadata);
                finalMetadata.put(ToolUseBlock.METADATA_MALFORMED_ARGUMENTS, true);
            }

            return ToolUseBlock.builder()
                    .id(toolId != null ? toolId : generateId())
                    .name(name)
                    .input(finalArgs)
                    .content(contentStr)
                    .metadata(finalMetadata.isEmpty() ? null : finalMetadata)
                    .state(state)
                    .build();
        }

        /**
         * 最终累积参数被标记为格式错误时，最多发出一条脱敏警告。
         *
         * <p>仅在最终构建边界（{@link #buildAllToolCalls(boolean)}）调用，不在每片段构建时调用，
         * 因为流中途出现不完整 JSON 前缀是正常情况。
         * 警告省略原始参数（可能包含凭据、个人身份信息或用户文档），也不携带堆栈跟踪。
         */
        private void warnIfMalformedOnce(ToolUseBlock block, boolean truncated) {
            if (!block.hasMalformedArguments() || parseWarned) {
                return;
            }
            parseWarned = true;
            String cause =
                    parseFailure != null
                            ? parseFailure
                            : (truncated ? "TruncatedEmptyArguments" : "NotAJsonObject");
            LOG.warn(
                    "Tool call '{}' (id '{}') has malformed arguments after the stream ended"
                            + " (raw length: {}); the call will be rejected before execution."
                            + " Cause: {}",
                    name != null && !name.isEmpty() ? name : "<unnamed>",
                    block.getId(),
                    rawContent.length(),
                    cause);
        }

        private boolean isPlaceholder(String name) {
            // 常见占位名称
            return "__fragment__".equals(name)
                    || "__pending__".equals(name)
                    || (name != null && name.startsWith("__"));
        }

        private String generateId() {
            return "tool_call_" + System.currentTimeMillis();
        }
    }

    /**
     * @hidden
     */
    @Override
    public void add(ToolUseBlock block) {
        if (block == null) {
            return;
        }

        // 确定此块属于哪个工具调用
        String key = determineKey(block);

        // 获取或创建对应构建器
        ToolCallBuilder builder = builders.computeIfAbsent(key, k -> new ToolCallBuilder());

        // 合并此块
        builder.merge(block);
    }

    /**
     * 确定工具调用的键（用于区分多个并行调用）。
     *
     * <p>优先顺序：
     * <ol>
     * <li>存在非空工具 ID 时使用它</li>
     * <li>存在非占位、非空工具名称时使用它</li>
     * <li>如果是片段（占位名称或空的续传名称），且存在最近工具调用键，则复用它</li>
     * <li>否则，对无任何标识符的片段使用索引</li>
     * </ol>
     */
    private String determineKey(ToolUseBlock block) {
        // 1. 优先使用非空工具 ID
        if (block.getId() != null && !block.getId().isEmpty()) {
            String key = block.getId();
            // 不是占位名称时记住此键
            if (block.getName() != null && !isPlaceholder(block.getName())) {
                lastToolCallKey = key;
            }
            return key;
        }

        // 2. 使用工具名称（非占位名称）
        if (block.getName() != null
                && !block.getName().isEmpty()
                && !isPlaceholder(block.getName())) {
            String key = "name:" + block.getName();
            lastToolCallKey = key;
            return key;
        }

        // 3. 如果这是片段（占位名称或空的续传名称），且存在
        // 最近使用的键，则复用它
        boolean emptyContinuationName = block.getName() != null && block.getName().isEmpty();
        if ((isPlaceholder(block.getName()) || emptyContinuationName) && lastToolCallKey != null) {
            return lastToolCallKey;
        }

        // 4. 使用索引（用于无任何标识符的片段）
        String key = "index:" + nextIndex++;
        lastToolCallKey = key;
        return key;
    }

    private boolean isPlaceholder(String name) {
        return name != null && name.startsWith("__");
    }

    /**
     * @hidden
     */
    @Override
    public boolean hasContent() {
        return !builders.isEmpty();
    }

    /**
     * @hidden
     */
    @Override
    public ContentBlock buildAggregated() {
        List<ToolUseBlock> toolCalls = buildAllToolCalls();

        // 只有一个工具调用时直接返回
        // 有多个调用时返回最后一个（也可以返回特殊的多调用块）
        if (toolCalls.isEmpty()) {
            return null;
        }

        return toolCalls.get(toolCalls.size() - 1);
    }

    /**
     * 构建全部已累积工具调用。
     *
     * @hidden
     * @return 工具调用列表
     */
    public List<ToolUseBlock> buildAllToolCalls() {
        return buildAllToolCalls(false);
    }

    /**
     * 构建调用；当输出因达到 token 上限而停止时，拒绝含义不明的空起始片段。
     *
     * <p>这是最终构建边界：格式错误的调用保留用于拒绝执行的上游标记，并发出一条脱敏诊断警告。
     */
    public List<ToolUseBlock> buildAllToolCalls(boolean truncated) {
        return builders.values().stream()
                .map(
                        builder -> {
                            ToolUseBlock block = builder.build(truncated);
                            builder.warnIfMalformedOnce(block, truncated);
                            return block;
                        })
                .collect(Collectors.toList());
    }

    /**
     * 按 ID 获取已累积工具调用。
     *
     * <p>ID 为 null／空，或找不到对应构建器时，回退到 lastToolCallKey。
     *
     * @param id 要查找的工具调用 ID
     * @return 已累积的 ToolUseBlock；未找到时为 null
     */
    public ToolUseBlock getAccumulatedToolCall(String id) {
        if (id != null && !id.isEmpty()) {
            // 首先尝试直接按 ID 查找
            ToolCallBuilder builder = builders.get(id);
            if (builder != null) {
                return builder.build();
            }
        }

        // ID 为空或未找到时回退到 lastToolCallKey
        if (lastToolCallKey != null) {
            ToolCallBuilder builder = builders.get(lastToolCallKey);
            if (builder != null) {
                return builder.build();
            }
        }

        return null;
    }

    /**
     * 获取全部已累积工具调用。
     *
     * <p>这是 {@link #buildAllToolCalls()} 的别名，用于保持 API 一致性。
     *
     * @return 全部已累积 ToolUseBlock 的列表
     */
    public List<ToolUseBlock> getAllAccumulatedToolCalls() {
        return buildAllToolCalls();
    }

    /**
     * 获取当前（最近）正在累积的工具调用 ID。
     *
     * <p>用于给片段补上正确的工具调用 ID，使用户能够正确连接流式片段。
     *
     * @return 当前工具调用 ID；没有正在累积的调用时为 null
     */
    public String getCurrentToolCallId() {
        if (lastToolCallKey == null) {
            return null;
        }

        ToolCallBuilder builder = builders.get(lastToolCallKey);
        if (builder == null) {
            return null;
        }

        return builder.toolId;
    }

    /**
     * @hidden
     */
    @Override
    public void reset() {
        builders.clear();
        nextIndex = 0;
        lastToolCallKey = null;
    }
}
