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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 为工具参数添加属性的扩展模型接口。
 */
public interface ExtendedModel {

    /**
     * 获取要合并到基础模式中的附加属性。
     */
    Map<String, Object> getAdditionalProperties();

    /**
     * 获取附加必填字段。
     */
    List<String> getAdditionalRequired();

    /**
     * 将扩展模型与工具的基础模式合并。
     *
     * @param baseParameters 来自 AgentTool 的基础参数模式
     * @return 合并后的模式
     * @throws IllegalStateException 当属性冲突时
     */
    default Map<String, Object> mergeWithBaseSchema(Map<String, Object> baseParameters) {
        Map<String, Object> merged = new HashMap<>(baseParameters);

        // 获取基础属性和必填字段
        @SuppressWarnings("unchecked")
        Map<String, Object> baseProps =
                (Map<String, Object>) merged.getOrDefault("properties", new HashMap<>());
        @SuppressWarnings("unchecked")
        List<String> baseRequired = (List<String>) merged.getOrDefault("required", List.of());

        // 合并属性并检测冲突
        Map<String, Object> extendedProps = getAdditionalProperties();
        Set<String> conflicts = new HashSet<>();
        for (String key : extendedProps.keySet()) {
            if (baseProps.containsKey(key)) {
                conflicts.add(key);
            }
        }

        if (!conflicts.isEmpty()) {
            throw new IllegalStateException(
                    "Extended model has conflicting properties with base schema: " + conflicts);
        }

        Map<String, Object> mergedProps = new HashMap<>(baseProps);
        mergedProps.putAll(extendedProps);
        merged.put("properties", mergedProps);

        // 合并必填字段数组
        List<String> extendedRequired = getAdditionalRequired();
        if (!extendedRequired.isEmpty()) {
            Set<String> mergedRequired = new HashSet<>(baseRequired);
            mergedRequired.addAll(extendedRequired);
            merged.put("required", List.copyOf(mergedRequired));
        }

        return merged;
    }
}
