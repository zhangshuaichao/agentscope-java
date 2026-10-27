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

import io.agentscope.core.tool.ToolBase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * 按配置的权限规则评估工具执行请求。
 *
 * <p>评估顺序：
 *
 * <ol>
 *   <li>工具级拒绝规则（最高优先级）。
 *   <li>工具级询问规则。
 *   <li>工具专用检查（不受绕过模式影响）：EXPLORE／ACCEPT_EDITS 的只读处理、
 *       危险路径检查，以及工具自身 {@link ToolBase#checkPermissions} 返回的结果。
 *   <li>工具级允许规则。
 *   <li>{@link PermissionMode#BYPASS} 回退。
 *   <li>默认 ASK（在 {@link PermissionMode#DONT_ASK} 下转为 DENY）。
 * </ol>
 *
 * <p>引擎在构造时，将传入 {@link PermissionContextState} 中的规则快照复制到
 * 自己的可变规则表；不会修改原始上下文。运行时可用 {@link #addRule} 扩展规则集。
 */
public final class PermissionEngine {

    private final PermissionContextState context;
    private final Map<String, List<PermissionRule>> allowRules;
    private final Map<String, List<PermissionRule>> denyRules;
    private final Map<String, List<PermissionRule>> askRules;

    /**
     * 使用给定上下文的规则和模式初始化引擎。
     *
     * @param context 提供模式、工作目录和初始规则的权限上下文
     */
    public PermissionEngine(PermissionContextState context) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.allowRules = copyMutable(context.getAllowRules());
        this.denyRules = copyMutable(context.getDenyRules());
        this.askRules = copyMutable(context.getAskRules());
    }

    private static Map<String, List<PermissionRule>> copyMutable(
            Map<String, List<PermissionRule>> source) {
        Map<String, List<PermissionRule>> out = new HashMap<>();
        if (source != null) {
            for (Map.Entry<String, List<PermissionRule>> e : source.entrySet()) {
                out.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
        }
        return out;
    }

    /**
     * 返回底层上下文（模式与工作目录从中读取）。
     *
     * @return 构造此引擎时使用的不可变上下文
     */
    public PermissionContextState getContext() {
        return context;
    }

    /**
     * 向引擎内部规则集添加规则。
     *
     * <p>根据 {@link PermissionRule#behavior()} 分流：ALLOW／DENY／ASK 规则
     * 分别追加到引擎的允许／拒绝／询问规则表；PASSTHROUGH 规则被忽略。
     *
     * @param rule 要添加的规则；不可为 null
     */
    public void addRule(PermissionRule rule) {
        Objects.requireNonNull(rule, "rule must not be null");
        switch (rule.behavior()) {
            case ALLOW ->
                    allowRules.computeIfAbsent(rule.toolName(), k -> new ArrayList<>()).add(rule);
            case DENY ->
                    denyRules.computeIfAbsent(rule.toolName(), k -> new ArrayList<>()).add(rule);
            case ASK -> askRules.computeIfAbsent(rule.toolName(), k -> new ArrayList<>()).add(rule);
            case PASSTHROUGH -> {
                // 不存储 PASSTHROUGH 规则；它表示“交由引擎决定”。
            }
        }
    }

    /** 引擎当前允许规则表的只读视图。 */
    public Map<String, List<PermissionRule>> getAllowRules() {
        return unmodifiableSnapshot(allowRules);
    }

    /** 引擎当前拒绝规则表的只读视图。 */
    public Map<String, List<PermissionRule>> getDenyRules() {
        return unmodifiableSnapshot(denyRules);
    }

    /** 引擎当前询问规则表的只读视图。 */
    public Map<String, List<PermissionRule>> getAskRules() {
        return unmodifiableSnapshot(askRules);
    }

    private static Map<String, List<PermissionRule>> unmodifiableSnapshot(
            Map<String, List<PermissionRule>> source) {
        Map<String, List<PermissionRule>> snapshot = new HashMap<>();
        for (Map.Entry<String, List<PermissionRule>> e : source.entrySet()) {
            snapshot.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * 为指定工具调用解析权限决定。
     *
     * @param tool 被调用的工具
     * @param toolInput 工具将接收的输入映射
     * @return 发出最终 {@link PermissionDecision} 的 Mono
     */
    public Mono<PermissionDecision> checkPermission(ToolBase tool, Map<String, Object> toolInput) {
        Objects.requireNonNull(tool, "tool must not be null");
        Map<String, Object> input = toolInput == null ? Map.of() : toolInput;

        // 【学习提示】这里是有序短路判定，不是把所有规则结果做投票。
        // 显式拒绝、显式询问先返回，所以后面的允许规则或 BYPASS 无法覆盖它们。
        // 1. 拒绝规则（最高优先级）
        PermissionDecision denyDecision = checkDenyRules(tool, input);
        if (denyDecision != null) {
            return Mono.just(denyDecision);
        }

        // 2. 询问规则
        PermissionDecision askDecision = checkAskRules(tool, input);
        if (askDecision != null) {
            return Mono.just(askDecision.withSuggestedRules(tool.generateSuggestions(input)));
        }

        // 3. 工具专用检查（不受绕过模式影响）
        return toolCheckPermissions(tool, input)
                .flatMap(
                        toolDecision -> {
                            if (toolDecision.getBehavior() == PermissionBehavior.DENY) {
                                return Mono.just(toolDecision);
                            }
                            if (toolDecision.getBehavior() == PermissionBehavior.ALLOW) {
                                return Mono.just(toolDecision);
                            }
                            if (toolDecision.getBehavior() == PermissionBehavior.ASK
                                    && toolDecision.getDecisionReason() != null
                                    && toolDecision
                                            .getDecisionReason()
                                            .toLowerCase(Locale.ROOT)
                                            .contains("safety")) {
                                return Mono.just(
                                        toolDecision.withSuggestedRules(
                                                tool.generateSuggestions(input)));
                            }
                            return continueAfterToolCheck(tool, input);
                        })
                // 【学习提示】PASSTHROUGH 被转为空序列；上游未发出决定时走 switchIfEmpty。
                // defer 避免在组装流水线时提前执行后续规则检查。
                .switchIfEmpty(Mono.defer(() -> continueAfterToolCheck(tool, input)));
    }

    private Mono<PermissionDecision> continueAfterToolCheck(
            ToolBase tool, Map<String, Object> input) {
        // 4. 允许规则
        PermissionDecision allowDecision = checkAllowRules(tool, input);
        if (allowDecision != null) {
            return Mono.just(allowDecision);
        }

        // 5. BYPASS 回退
        if (context.getMode() == PermissionMode.BYPASS) {
            return Mono.just(
                    PermissionDecision.builder()
                            .behavior(PermissionBehavior.ALLOW)
                            .message("Permission granted for " + tool.getName() + " (bypass mode)")
                            .decisionReason("Bypass mode allows all operations")
                            .build());
        }

        // 6. 默认决定（ASK；在 DONT_ASK 下为 DENY）
        return Mono.just(
                defaultDecisionAsk(tool.getName())
                        .withSuggestedRules(tool.generateSuggestions(input)));
    }

    /**
     * 运行工具专用权限流水线。
     *
     * <p>先处理 EXPLORE／ACCEPT_EDITS 的只读行为，再调用工具自身的
     * {@link ToolBase#checkPermissions}。工具返回 PASSTHROUGH 时发出空序列。
     */
    private Mono<PermissionDecision> toolCheckPermissions(
            ToolBase tool, Map<String, Object> input) {
        if (context.getMode() == PermissionMode.EXPLORE
                || context.getMode() == PermissionMode.ACCEPT_EDITS) {
            PermissionDecision modeDecision = checkExploreMode(tool);
            if (modeDecision != null) {
                return Mono.just(modeDecision);
            }
        }
        return tool.checkPermissions(input, context)
                .flatMap(
                        decision -> {
                            if (decision.getBehavior() == PermissionBehavior.PASSTHROUGH) {
                                return Mono.empty();
                            }
                            return Mono.just(decision);
                        });
    }

    private PermissionDecision checkExploreMode(ToolBase tool) {
        if (context.getMode() == PermissionMode.EXPLORE) {
            if (tool.isReadOnly()) {
                return PermissionDecision.builder()
                        .behavior(PermissionBehavior.ALLOW)
                        .message(
                                "Permission granted for "
                                        + tool.getName()
                                        + " (explore mode - read-only tool)")
                        .decisionReason("Explore mode allows read-only operations")
                        .build();
            }
            return PermissionDecision.builder()
                    .behavior(PermissionBehavior.DENY)
                    .message(
                            "Permission denied for "
                                    + tool.getName()
                                    + " (explore mode is read-only)")
                    .decisionReason("Explore mode does not allow modifications")
                    .build();
        }
        if (context.getMode() == PermissionMode.ACCEPT_EDITS) {
            if (tool.isReadOnly()) {
                return PermissionDecision.builder()
                        .behavior(PermissionBehavior.ALLOW)
                        .message(
                                "Permission granted for "
                                        + tool.getName()
                                        + " (accept edits mode - read-only tool)")
                        .decisionReason("Accept edits mode allows read-only operations")
                        .build();
            }
        }
        return null;
    }

    private PermissionDecision checkDenyRules(ToolBase tool, Map<String, Object> input) {
        for (PermissionRule rule : rulesFor(denyRules, tool.getName())) {
            if (ruleMatches(tool, rule, input)) {
                return PermissionDecision.builder()
                        .behavior(PermissionBehavior.DENY)
                        .message("Permission to use " + tool.getName() + " has been denied")
                        .decisionReason("Rule: " + rule.ruleContent())
                        .build();
            }
        }
        return null;
    }

    private PermissionDecision checkAskRules(ToolBase tool, Map<String, Object> input) {
        for (PermissionRule rule : rulesFor(askRules, tool.getName())) {
            if (ruleMatches(tool, rule, input)) {
                return PermissionDecision.builder()
                        .behavior(PermissionBehavior.ASK)
                        .message("Permission required for " + tool.getName())
                        .decisionReason("Rule: " + rule.ruleContent())
                        .build();
            }
        }
        return null;
    }

    private PermissionDecision checkAllowRules(ToolBase tool, Map<String, Object> input) {
        for (PermissionRule rule : rulesFor(allowRules, tool.getName())) {
            if (ruleMatches(tool, rule, input)) {
                return PermissionDecision.builder()
                        .behavior(PermissionBehavior.ALLOW)
                        .message("Permission granted for " + tool.getName())
                        .updatedInput(input)
                        .build();
            }
        }
        return null;
    }

    private static List<PermissionRule> rulesFor(
            Map<String, List<PermissionRule>> table, String toolName) {
        List<PermissionRule> rules = table.get(toolName);
        return rules == null ? List.of() : rules;
    }

    private boolean ruleMatches(ToolBase tool, PermissionRule rule, Map<String, Object> input) {
        String content = rule.ruleContent();
        if (content == null || content.isEmpty()) {
            return true;
        }
        return tool.matchRule(content, input);
    }

    private PermissionDecision defaultDecisionAsk(String toolName) {
        if (context.getMode() == PermissionMode.DONT_ASK) {
            return PermissionDecision.builder()
                    .behavior(PermissionBehavior.DENY)
                    .message(
                            "Permission denied for "
                                    + toolName
                                    + " (dont_ask mode - user not available)")
                    .decisionReason("User is not available to answer permission prompts")
                    .build();
        }
        return PermissionDecision.builder()
                .behavior(PermissionBehavior.ASK)
                .message("Permission required for " + toolName)
                .decisionReason("Mode: " + context.getMode().name().toLowerCase(Locale.ROOT))
                .build();
    }
}
