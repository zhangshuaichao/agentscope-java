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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 更新 {@link SkillUsageStore} 的计数：当智能体调用 SkillBox 注册的技能加载器
 * （例如 {@code load_skill_through_path}）时增加计数。来源筛选在存储内部执行，
 * 只记录标记为 {@code created_by="agent"} 或 {@code "agent-draft"} 的技能。
 *
 * <p>计数更新采用尽力而为策略，在进入 {@code onActing} 时立即执行，早于实际工具调用返回。
 * 其语义是“模型在本轮决定调用此技能”，而不是“调用已经成功”。
 * 这与 hermes-agent 的遥测结构一致，计算成本较低。
 */
public class SkillUsageMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(SkillUsageMiddleware.class);

    /** 调用后计为一次技能“查看”的工具名称。 */
    private static final Set<String> VIEW_TOOL_NAMES =
            Set.of("load_skill_through_path", "read_skill");

    /**
     * 调用后计为一次技能“使用”的工具名称。当前包含显式 {@code use_skill}，
     * 匹配此名称的调用会触发 {@code bumpUse}。
     */
    private static final Set<String> USE_TOOL_NAMES = Set.of("use_skill");

    private final SkillUsageStore usageStore;

    public SkillUsageMiddleware(SkillUsageStore usageStore) {
        this.usageStore = java.util.Objects.requireNonNull(usageStore, "usageStore");
    }

    /** 仅声明所需扩展点；子类若重写更多钩子，必须扩展此集合。 */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_ACTING);
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        if (input != null && input.toolCalls() != null) {
            for (ToolUseBlock call : input.toolCalls()) {
                trackInvocation(call);
            }
        }
        return next.apply(input);
    }

    /**
     * 如果 {@code call} 指向 SkillBox 加载／使用工具，则提取 {@code skillId} 参数
     * （或回退等价字段），并更新存储中对应的计数。吞掉更新失败，
     * 确保遥测绝不会破坏智能体循环。
     */
    private void trackInvocation(ToolUseBlock call) {
        if (call == null || call.getName() == null) {
            return;
        }
        String toolName = call.getName();
        boolean isView = VIEW_TOOL_NAMES.contains(toolName);
        boolean isUse = USE_TOOL_NAMES.contains(toolName);
        if (!isView && !isUse) {
            return;
        }
        String skillName = extractSkillName(call);
        if (skillName == null || skillName.isBlank()) {
            return;
        }
        try {
            if (isView) {
                usageStore.bumpView(skillName);
            } else {
                usageStore.bumpUse(skillName);
            }
        } catch (Exception e) {
            log.debug(
                    "SkillUsageMiddleware bump for tool={} skill={} failed: {}",
                    toolName,
                    skillName,
                    e.getMessage());
        }
    }

    /**
     * 从工具调用块提取技能标识符。{@code skillId} 是 SkillBox 加载器的标准键；
     * 也接受 {@code skill_id} 和 {@code name} 作为回退，以兼容未来可能的重命名。
     */
    private static String extractSkillName(ToolUseBlock call) {
        Map<String, Object> input = call.getInput();
        if (input == null) {
            return null;
        }
        for (String key : new String[] {"skillId", "skill_id", "name"}) {
            Object v = input.get(key);
            if (v != null) {
                String s = v.toString().trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }

    // 为保留的空 Mono 辅助方法抑制未使用警告。
    @SuppressWarnings("unused")
    private static Mono<Void> noop() {
        return Mono.empty();
    }
}
