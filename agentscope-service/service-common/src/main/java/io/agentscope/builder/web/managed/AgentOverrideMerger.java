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
package io.agentscope.builder.web.managed;

import java.util.LinkedHashMap;
import java.util.Map;

/** 会话范围智能体覆盖配置的共享合并辅助类。 */
public final class AgentOverrideMerger {

    private AgentOverrideMerger() {}

    /** 返回将 {@code patch} 应用于 {@code current} 后的新映射（可安全处理 null）。 */
    public static Map<String, Object> merge(
            Map<String, Object> current, Map<String, Object> patch) {
        Map<String, Object> result =
                current == null ? new LinkedHashMap<>() : new LinkedHashMap<>(current);
        if (patch != null) {
            result.putAll(patch);
        }
        return result;
    }
}
