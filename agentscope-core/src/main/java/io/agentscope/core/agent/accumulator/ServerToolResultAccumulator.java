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
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 累积提供方在一轮推理期间返回的服务端工具结果。
 *
 * <p>服务端工具结果以 {@code tool_use_id} 为键，确保最终助手消息中每个结果紧邻其匹配的工具调用。
 * 后来的结果可以替换仍在运行的部分结果，使提供方能在最终结果到达前流式输出预览。
 * 没有匹配调用的结果按到达顺序保留并记录日志，而非静默丢失。
 *
 * @hidden
 */
final class ServerToolResultAccumulator {

    private static final Logger log = LoggerFactory.getLogger(ServerToolResultAccumulator.class);

    private final Map<String, ToolResultBlock> resultsById = new LinkedHashMap<>();
    private final List<ToolResultBlock> resultsWithoutId = new ArrayList<>();

    /**
     * 添加服务端工具结果。
     *
     * <p>重复 ID 保留首个终态结果；运行中的结果可被后续运行中或终态结果替换。
     * 忽略非服务端结果，因为本地工具结果在行动阶段产生，不属于推理输出。
     *
     * @hidden
     * @param result 提供方返回的结果块
     */
    void add(ToolResultBlock result) {
        if (result == null || !result.isServerTool()) {
            return;
        }

        String id = result.getId();
        if (id == null || id.isBlank()) {
            resultsWithoutId.add(result);
            return;
        }
        ToolResultBlock existing = resultsById.get(id);
        if (existing == null || existing.getState() == ToolResultState.RUNNING) {
            resultsById.put(id, result);
        }
    }

    /**
     * 返回是否已累积任何服务端工具结果。
     *
     * @hidden
     * @return 至少有一个结果时为 true
     */
    boolean hasContent() {
        return !resultsById.isEmpty() || !resultsWithoutId.isEmpty();
    }

    /**
     * 按到达顺序构建全部已累积结果。
     *
     * @hidden
     * @return 服务端工具结果的不可变列表
     */
    List<ToolResultBlock> buildAllToolResults() {
        List<ToolResultBlock> results = new ArrayList<>(resultsById.values());
        results.addAll(resultsWithoutId);
        return List.copyOf(results);
    }

    /**
     * 将每个匹配结果紧接在对应工具调用之后。未匹配结果追加在工具调用之后，
     * 使数据保持可见，同时提供诊断信息。
     *
     * @hidden
     * @param toolCalls 同一推理轮次中累积的工具调用
     * @return 已插入匹配服务端工具结果的工具调用列表
     */
    List<ContentBlock> placeAfterToolCalls(List<ToolUseBlock> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.copyOf(buildAllToolResults());
        }

        Map<String, ToolResultBlock> unmatched = new LinkedHashMap<>(resultsById);
        List<ContentBlock> blocks = new ArrayList<>();
        for (ToolUseBlock toolCall : toolCalls) {
            blocks.add(toolCall);
            ToolResultBlock result = unmatched.remove(toolCall.getId());
            if (result != null) {
                blocks.add(result);
            }
        }

        if (!unmatched.isEmpty() || !resultsWithoutId.isEmpty()) {
            log.warn(
                    "{} server tool result(s) have no matching tool call",
                    unmatched.size() + resultsWithoutId.size());
        }
        blocks.addAll(unmatched.values());
        blocks.addAll(resultsWithoutId);
        return blocks;
    }

    /**
     * 清空已累积的服务端工具结果。
     *
     * @hidden
     */
    void reset() {
        resultsById.clear();
        resultsWithoutId.clear();
    }
}
