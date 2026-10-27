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
package io.agentscope.core.permission;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * 绑定到某个工具名称的单条权限规则。
 *
 * <p>{@code ruleContent} 由所属工具的 {@code matchRule} 方法解释。
 * 为 {@code null} 时，规则适用于该工具的所有调用（工具名称级规则）。
 *
 * @param toolName 规则针对的工具（绝不为 {@code null}）
 * @param ruleContent 可选的工具专用匹配模式（可为空）
 * @param behavior 规则匹配时的结果行为（绝不为 {@code null}）
 * @param source 规则来源（例如 {@code "userSettings"}、{@code "suggested"}）
 */
public record PermissionRule(
        @JsonProperty("tool_name") String toolName,
        @JsonProperty("rule_content") String ruleContent,
        @JsonProperty("behavior") PermissionBehavior behavior,
        @JsonProperty("source") String source) {

    @JsonCreator
    public PermissionRule {
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(behavior, "behavior must not be null");
        Objects.requireNonNull(source, "source must not be null");
    }
}
