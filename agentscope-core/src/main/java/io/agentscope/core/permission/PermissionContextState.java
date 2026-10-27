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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 权限评估上下文：模式、工作目录和三张规则表。
 *
 * <p>规则表以工具名称为键，每个值是为该工具注册的有序规则列表。
 * 引擎先评估 {@code denyRules}，再评估 {@code askRules}、工具自检，
 * 最后评估 {@code allowRules}；完整顺序见 {@code PermissionEngine} 的 Javadoc。
 */
@JsonPropertyOrder({"mode", "working_directories", "allow_rules", "deny_rules", "ask_rules"})
public final class PermissionContextState {

    private final PermissionMode mode;
    private final Map<String, AdditionalWorkingDirectory> workingDirectories;
    private final Map<String, List<PermissionRule>> allowRules;
    private final Map<String, List<PermissionRule>> denyRules;
    private final Map<String, List<PermissionRule>> askRules;

    private PermissionContextState(Builder builder) {
        this.mode = builder.mode == null ? PermissionMode.DEFAULT : builder.mode;
        this.workingDirectories =
                Collections.unmodifiableMap(new LinkedHashMap<>(builder.workingDirectories));
        this.allowRules = freeze(builder.allowRules);
        this.denyRules = freeze(builder.denyRules);
        this.askRules = freeze(builder.askRules);
    }

    @JsonCreator
    static PermissionContextState fromJson(
            @JsonProperty("mode") PermissionMode mode,
            @JsonProperty("working_directories")
                    Map<String, AdditionalWorkingDirectory> workingDirectories,
            @JsonProperty("allow_rules") Map<String, List<PermissionRule>> allowRules,
            @JsonProperty("deny_rules") Map<String, List<PermissionRule>> denyRules,
            @JsonProperty("ask_rules") Map<String, List<PermissionRule>> askRules) {
        Builder b = builder();
        if (mode != null) {
            b.mode(mode);
        }
        if (workingDirectories != null) {
            workingDirectories.forEach(b::addWorkingDirectory);
        }
        copyInto(allowRules, b::addAllowRule);
        copyInto(denyRules, b::addDenyRule);
        copyInto(askRules, b::addAskRule);
        return b.build();
    }

    private static void copyInto(Map<String, List<PermissionRule>> source, RuleAdder adder) {
        if (source == null) {
            return;
        }
        source.forEach(
                (tool, rules) -> {
                    if (rules == null) {
                        return;
                    }
                    rules.forEach(rule -> adder.add(tool, rule));
                });
    }

    private static Map<String, List<PermissionRule>> freeze(
            Map<String, List<PermissionRule>> source) {
        Map<String, List<PermissionRule>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(copy);
    }

    @JsonProperty("mode")
    public PermissionMode getMode() {
        return mode;
    }

    /**
     * 此上下文是否属于“简单”上下文，即直接由 {@link Builder#build()} 构建，
     * 没有进一步定制。ReAct 智能体据此决定启用完整权限引擎
     * （规则、模式、工具自检），还是回退到 2.0 之前的轻量路径，
     * 该路径仅由工具自身的 {@code checkPermissions} 自检把关。
     */
    @JsonIgnore
    public boolean isTrivial() {
        return mode == PermissionMode.DEFAULT
                && workingDirectories.isEmpty()
                && allowRules.isEmpty()
                && denyRules.isEmpty()
                && askRules.isEmpty();
    }

    @JsonProperty("working_directories")
    public Map<String, AdditionalWorkingDirectory> getWorkingDirectories() {
        return workingDirectories;
    }

    @JsonProperty("allow_rules")
    public Map<String, List<PermissionRule>> getAllowRules() {
        return allowRules;
    }

    @JsonProperty("deny_rules")
    public Map<String, List<PermissionRule>> getDenyRules() {
        return denyRules;
    }

    @JsonProperty("ask_rules")
    public Map<String, List<PermissionRule>> getAskRules() {
        return askRules;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 返回替换了 {@link PermissionMode} 的上下文副本，保留所有工作目录和规则。
     * 用于运行时切换评估模式（例如将会话切换到 {@link PermissionMode#BYPASS}），
     * 同时不丢失已配置的规则。
     *
     * @param newMode 返回的上下文应使用的模式
     * @return 除模式外与当前对象相同的新上下文（模式未改变时返回 {@code this}）
     */
    public PermissionContextState withMode(PermissionMode newMode) {
        Objects.requireNonNull(newMode, "newMode must not be null");
        if (newMode == this.mode) {
            return this;
        }
        Builder b = builder().mode(newMode);
        workingDirectories.forEach(b::addWorkingDirectory);
        copyInto(allowRules, b::addAllowRule);
        copyInto(denyRules, b::addDenyRule);
        copyInto(askRules, b::addAskRule);
        return b.build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PermissionContextState other)) {
            return false;
        }
        return mode == other.mode
                && Objects.equals(workingDirectories, other.workingDirectories)
                && Objects.equals(allowRules, other.allowRules)
                && Objects.equals(denyRules, other.denyRules)
                && Objects.equals(askRules, other.askRules);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, workingDirectories, allowRules, denyRules, askRules);
    }

    @Override
    public String toString() {
        return "PermissionContextState{mode="
                + mode
                + ", workingDirectories="
                + workingDirectories
                + ", allowRules="
                + allowRules
                + ", denyRules="
                + denyRules
                + ", askRules="
                + askRules
                + '}';
    }

    @FunctionalInterface
    private interface RuleAdder {
        void add(String toolName, PermissionRule rule);
    }

    public static final class Builder {
        private PermissionMode mode = PermissionMode.DEFAULT;
        private final Map<String, AdditionalWorkingDirectory> workingDirectories =
                new LinkedHashMap<>();
        private final Map<String, List<PermissionRule>> allowRules = new LinkedHashMap<>();
        private final Map<String, List<PermissionRule>> denyRules = new LinkedHashMap<>();
        private final Map<String, List<PermissionRule>> askRules = new LinkedHashMap<>();

        private Builder() {}

        public Builder mode(PermissionMode mode) {
            this.mode = Objects.requireNonNull(mode, "mode must not be null");
            return this;
        }

        public Builder addWorkingDirectory(String key, AdditionalWorkingDirectory directory) {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(directory, "directory must not be null");
            this.workingDirectories.put(key, directory);
            return this;
        }

        public Builder addAllowRule(String toolName, PermissionRule rule) {
            appendRule(allowRules, toolName, rule);
            return this;
        }

        public Builder addDenyRule(String toolName, PermissionRule rule) {
            appendRule(denyRules, toolName, rule);
            return this;
        }

        public Builder addAskRule(String toolName, PermissionRule rule) {
            appendRule(askRules, toolName, rule);
            return this;
        }

        private static void appendRule(
                Map<String, List<PermissionRule>> table, String toolName, PermissionRule rule) {
            Objects.requireNonNull(toolName, "toolName must not be null");
            Objects.requireNonNull(rule, "rule must not be null");
            table.computeIfAbsent(toolName, key -> new ArrayList<>()).add(rule);
        }

        public PermissionContextState build() {
            return new PermissionContextState(this);
        }
    }
}
