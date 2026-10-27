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
package io.agentscope.core.memory;

/**
 * 定义长期记忆与智能体行为的集成方式。
 *
 * <p>此枚举控制记忆管理由框架自动处理、
 * 由智能体通过工具调用主动处理，或两者兼用。模式影响：
 * <ul>
 *   <li>何时记录和检索记忆
 *   <li>是否在智能体工具集中注册记忆管理工具
 *   <li>智能体对自身记忆的控制程度
 * </ul>
 *
 * <p><b>选择合适模式：</b>
 * <ul>
 *   <li><b>AGENT_CONTROL：</b>希望智能体自主决定记忆操作时使用，
 *       适合能判断信息重要性的高级智能体。</li>
 *   <li><b>STATIC_CONTROL：</b>希望框架自动管理记忆而无须智能体参与时使用，
 *       适合简单智能体，或要求全面记录记忆的场景。</li>
 *   <li><b>BOTH：</b>建议的默认模式。结合自动后台记忆与智能体
 *       控制，兼顾两种方式的优势。</li>
 * </ul>
 *
 * <p><b>使用示例：</b>
 * <pre>{@code
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(model)
 *     .longTermMemory(memory)
 *     .longTermMemoryMode(LongTermMemoryMode.BOTH)  // 推荐
 *     .build();
 * }</pre>
 *
 * @see LongTermMemory
 * @see io.agentscope.core.ReActAgent
 * @deprecated 自 2.0.0 起弃用。旧长期记忆功能已移除。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public enum LongTermMemoryMode {

    /**
     * 智能体通过工具调用主动控制记忆。
     */
    AGENT_CONTROL,

    /**
     * 框架自动管理记忆，无须智能体参与。
     */
    STATIC_CONTROL,

    /**
     * 结合智能体控制与框架自动管理。
     */
    BOTH
}
