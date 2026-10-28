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
package io.agentscope.extensions.agentprotocol;

import java.util.List;
import java.util.Set;

/** 协议端点所提供的持久化任务记录的工作区分区，以及调用方提供上下文属性的约定。 */
public final class AgentProtocolConstants {

    private AgentProtocolConstants() {}

    /** 用于 {@code agents/&lt;id&gt;/tasks/} 路径的合成父智能体 ID。 */
    public static final String PROTOCOL_AGENT_ID = "_agentscope_protocol";

    /** 以任务 ID 映射保存所有协议任务的单个会话桶文件。 */
    public static final String PROTOCOL_SESSION_ID = "_tasks";

    /** 提交的 {@code context} 中承载调用方自定义属性的字段。嵌套保存可将这些属性与协议自身的上下文字段（{@code user_id}、{@code parent_session_id}、{@code stream}、{@code detail}、{@code deny_rules}）区分开。 */
    public static final String CONTEXT_ATTRIBUTES_FIELD = "attributes";

    /**
     * 用于在 {@link io.agentscope.core.agent.RuntimeContext} 中以不可修改的 {@code Map<String, Object>} 保存完整 {@code context.attributes} 映射的键。
     *
     * <p>属性集中放在这一命名空间键下，避免调用方控制的名称覆盖框架键。工具和中间件通过 {@code ctx.get(
     * AgentProtocolConstants.RUNTIME_CONTEXT_ATTRIBUTES_KEY)} 读取；若要将某个属性暴露为独立顶层键，可使用 {@link RuntimeContextCustomizer#flatten(String...)}。
     */
    public static final String RUNTIME_CONTEXT_ATTRIBUTES_KEY = "agentprotocol.context.attributes";

    /** 框架读取的精确运行时上下文键；属性展开操作不得覆盖。 */
    private static final Set<String> RESERVED_KEYS = Set.of("agentId", "outboundAddress");

    /** 框架拥有的运行时上下文键前缀；属性展开操作不得覆盖。 */
    private static final List<String> RESERVED_PREFIXES =
            List.of("agentscope.", "agui.", "io.agentscope.");

    /** 判断 {@code key} 是否由框架自身读取，因而不宜被调用方的值覆盖。例如，{@code agentId} 决定异步工具唤醒的路由，而 {@code outboundAddress} 携带网关回复地址。 */
    public static boolean isReservedRuntimeContextKey(String key) {
        if (key == null || key.isBlank()) {
            return true;
        }
        if (RESERVED_KEYS.contains(key)) {
            return true;
        }
        for (String prefix : RESERVED_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
