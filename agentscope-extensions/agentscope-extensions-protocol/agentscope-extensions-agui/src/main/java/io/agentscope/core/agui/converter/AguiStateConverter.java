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
package io.agentscope.core.agui.converter;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEvent.JsonPatchOperation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * AG-UI 协议状态管理转换器。
 *
 * <p>【学习提示】增量是面向客户端旧状态的 JSON Patch，而非完整快照。这里只对双方都是 Map 的值递归；列表等其他变更按整个值替换，不计算逐元素差异。客户端必须基于匹配的旧状态按序应用补丁，否则即使补丁格式正确也可能得到错误状态。
 *
 * <p>根据 AgentScope 状态系统创建状态事件（快照与增量）。
 */
public class AguiStateConverter {

    /**
     * 根据状态映射创建 STATE_SNAPSHOT 事件。
     *
     * @param state 状态映射
     * @param threadId 线程 ID
     * @param runId 运行 ID
     * @return StateSnapshot 事件
     */
    public AguiEvent.StateSnapshot createSnapshot(
            Map<String, Object> state, String threadId, String runId) {
        return new AguiEvent.StateSnapshot(threadId, runId, state);
    }

    /**
     * 比较前后状态创建 STATE_DELTA 事件。
     *
     * <p>生成可将变更前状态转换为变更后状态的 JSON Patch 操作（RFC 6902）。
     *
     * @param before 变更前状态
     * @param after 变更后状态
     * @param threadId 线程 ID
     * @param runId 运行 ID
     * @return StateDelta 事件；无变化时为 null
     */
    public AguiEvent.StateDelta createDelta(
            Map<String, Object> before, Map<String, Object> after, String threadId, String runId) {
        List<JsonPatchOperation> operations = computeDelta(before, after, "");

        if (operations.isEmpty()) {
            return null; // 没有变化
        }

        return new AguiEvent.StateDelta(threadId, runId, operations);
    }

    /**
     * 检查两个状态是否存在差异。
     *
     * @param before 变更前状态
     * @param after 变更后状态
     * @return 存在差异时为 true
     */
    public boolean hasChanges(Map<String, Object> before, Map<String, Object> after) {
        return !computeDelta(before, after, "").isEmpty();
    }

    /**
     * 计算将变更前状态转换为变更后状态所需的 JSON Patch 操作。
     *
     * @param before 变更前状态
     * @param after 变更后状态
     * @param basePath 基础 JSON Pointer 路径
     * @return JsonPatchOperation 列表
     */
    @SuppressWarnings("unchecked")
    private List<JsonPatchOperation> computeDelta(
            Map<String, Object> before, Map<String, Object> after, String basePath) {
        List<JsonPatchOperation> operations = new ArrayList<>();

        if (before == null && after == null) {
            return operations;
        }

        if (before == null) {
            before = Map.of();
        }

        if (after == null) {
            after = Map.of();
        }

        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(before.keySet());
        allKeys.addAll(after.keySet());

        for (String key : allKeys) {
            String path = basePath + "/" + escapeJsonPointer(key);
            Object beforeValue = before.get(key);
            Object afterValue = after.get(key);

            if (!before.containsKey(key)) {
                // 新增了键
                operations.add(JsonPatchOperation.add(path, afterValue));
            } else if (!after.containsKey(key)) {
                // 移除了键
                operations.add(JsonPatchOperation.remove(path));
            } else if (!Objects.equals(beforeValue, afterValue)) {
                // 值发生变化
                if (beforeValue instanceof Map && afterValue instanceof Map) {
                    // 递归处理嵌套映射
                    operations.addAll(
                            computeDelta(
                                    (Map<String, Object>) beforeValue,
                                    (Map<String, Object>) afterValue,
                                    path));
                } else {
                    // 替换值
                    operations.add(JsonPatchOperation.replace(path, afterValue));
                }
            }
        }

        return operations;
    }

    /**
     * 转义字符串以用于 JSON Pointer（RFC 6901）。
     *
     * @param value 要转义的字符串
     * @return 转义后的字符串
     */
    private String escapeJsonPointer(String value) {
        // 按照 RFC 6901，~ 必须转义为 ~0，/ 必须转义为 ~1
        return value.replace("~", "~0").replace("/", "~1");
    }
}
