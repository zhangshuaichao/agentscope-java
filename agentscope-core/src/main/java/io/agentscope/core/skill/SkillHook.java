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
package io.agentscope.core.skill;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import reactor.core.publisher.Mono;

/**
 * 在 {@link PreCallEvent} 上通过 {@link PreCallEvent#appendSystemContent(String)} 将技能目录提示注入统一系统消息。
 *
 * <p>使用优先级 {@link #SKILL_HOOK_PRIORITY}，因此在典型 {@code HarnessAgent} 配置中，此钩子在 {@code SubagentsHook}（80）之后、{@code WorkspaceContextHook}（900）之前执行，追加顺序为：基础提示 → 子智能体 → 技能 → 工作区上下文。
 *
 * <p>技能提示追加到临时系统消息中，不会存入智能体的持久 {@code Memory}。
 *
 * @deprecated 自 2.0.0 起弃用。技能包和钩子系统已移除；请在应用代码中或通过 {@code MiddlewareBase} 管理 Markdown 技能目录。
 */
@Deprecated(since = "2.0.0")
public class SkillHook implements Hook {

    /**
     * 在默认 harness 钩子链中，于子智能体提示注入之后、工作区上下文注入之前运行。
     */
    public static final int SKILL_HOOK_PRIORITY = 85;

    private final SkillBox skillBox;

    public SkillHook(SkillBox skillBox) {
        this.skillBox = skillBox;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreCallEvent preCallEvent) {
            String skillPrompt = skillBox.getSkillPrompt();
            if (skillPrompt != null && !skillPrompt.isEmpty()) {
                preCallEvent.appendSystemContent(skillPrompt);
            }
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return SKILL_HOOK_PRIORITY;
    }
}
