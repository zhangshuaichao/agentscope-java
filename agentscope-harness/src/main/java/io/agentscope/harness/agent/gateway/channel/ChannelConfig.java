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
package io.agentscope.harness.agent.gateway.channel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 每渠道路由配置：默认智能体回退、私聊会话作用域，以及由 {@link ChannelRouter} 评估的有序绑定规则。
 *
 * <p>{@link Channel} 实现用它参数化路由，避免在适配器代码中硬编码智能体 ID 或会话结构。
 *
 * @param channelId 此配置适用的逻辑渠道标识（必须匹配 {@link
 *     Channel#channelId()}）
 * @param defaultAgentId 未匹配绑定时的回退智能体 ID；null 时使用通过
 *     {@link io.agentscope.harness.agent.gateway.Gateway#bindMainAgent} 注册的全局默认智能体
 * @param dmScope 控制私聊（{@link PeerKind#DIRECT}）会话键的构造；默认 {@link
 *     DmScope#MAIN}
 * @param bindings 有序绑定规则列表；每个优先级层内按列表顺序评估
 */
public record ChannelConfig(
        String channelId, String defaultAgentId, DmScope dmScope, List<ChannelBinding> bindings) {

    public ChannelConfig {
        Objects.requireNonNull(channelId, "channelId");
        dmScope = dmScope != null ? dmScope : DmScope.defaultScope();
        bindings = bindings != null ? List.copyOf(bindings) : List.of();
    }

    /**
     * 最简配置：仅提供渠道 ID，使用全局默认智能体及 {@link DmScope#MAIN}。
     */
    public static ChannelConfig of(String channelId) {
        return new ChannelConfig(channelId, null, DmScope.MAIN, List.of());
    }

    /**
     * 明确指定默认智能体的配置（无绑定规则）。
     */
    public static ChannelConfig of(String channelId, String defaultAgentId) {
        return new ChannelConfig(channelId, defaultAgentId, DmScope.MAIN, List.of());
    }

    /**
     * 返回支持链式调用的渠道配置构建器。
     */
    public static Builder builder(String channelId) {
        return new Builder(channelId);
    }

    public static final class Builder {
        private final String channelId;
        private String defaultAgentId;
        private DmScope dmScope = DmScope.MAIN;
        private final List<ChannelBinding> bindings = new ArrayList<>();

        private Builder(String channelId) {
            this.channelId = Objects.requireNonNull(channelId, "channelId");
        }

        public Builder defaultAgentId(String defaultAgentId) {
            this.defaultAgentId = defaultAgentId;
            return this;
        }

        public Builder dmScope(DmScope dmScope) {
            this.dmScope = dmScope;
            return this;
        }

        public Builder binding(ChannelBinding binding) {
            this.bindings.add(Objects.requireNonNull(binding, "binding"));
            return this;
        }

        public Builder bindings(List<ChannelBinding> bindings) {
            if (bindings != null) {
                this.bindings.addAll(bindings);
            }
            return this;
        }

        public ChannelConfig build() {
            return new ChannelConfig(channelId, defaultAgentId, dmScope, bindings);
        }
    }
}
