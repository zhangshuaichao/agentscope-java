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
package io.agentscope.spring.boot.admin.metrics;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.spring.boot.admin.registry.AgentResolver;
import reactor.core.publisher.Mono;

/**
 * 观察 {@link PostCallEvent} 并将每次调用词元用量转交 {@link MetricsRecorder} 的旧版 {@link Hook}。
 *
 * <p>此钩子只观察而不修改：不改变智能体最终消息，始终原样返回事件，适合通过 {@link io.agentscope.core.agent.AgentBase#addSystemHook(Hook)} 全局注册。
 *
 * <p><b>时序注意：</b>{@code addSystemHook} 仅作用于注册后构造的智能体（参见 {@code AgentBase} 构造方法）。admin starter 接入钩子之前已实例化的智能体不会贡献用量统计。
 */
@SuppressWarnings("deprecation")
public final class MetricsHook implements Hook {

    private final MetricsRecorder recorder;

    public MetricsHook(MetricsRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public int priority() {
        // 后运行：只读取，不应抢在验证／安全钩子之前。
        return 900;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostCallEvent post) {
            recordSafe(post);
        }
        return Mono.just(event);
    }

    private void recordSafe(PostCallEvent post) {
        try {
            Agent agent = post.getAgent();
            String agentName = agent == null ? null : agent.getName();
            String modelName = null;
            ReActAgent react = AgentResolver.unwrapReActAgent(agent);
            if (react != null && react.getModel() != null) {
                modelName = react.getModel().getModelName();
            }
            Msg msg = post.getFinalMessage();
            ChatUsage usage = msg == null ? null : msg.getUsage();
            if (usage == null) {
                // 缺失用量信息时仍计入调用次数。
                recorder.record(agentName, modelName, 0, 0);
                return;
            }
            recorder.record(agentName, modelName, usage.getInputTokens(), usage.getOutputTokens());
        } catch (RuntimeException ignored) {
            // 绝不让指标钩子破坏智能体执行。
        }
    }
}
