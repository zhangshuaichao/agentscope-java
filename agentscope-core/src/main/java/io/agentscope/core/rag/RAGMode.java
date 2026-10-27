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
package io.agentscope.core.rag;

/**
 * RAG（Retrieval-Augmented Generation，检索增强生成）模式枚举。
 *
 * <p>定义如何将知识检索集成到智能体中：
 * <ul>
 *   <li><b>GENERIC</b>: 通过钩子在每一步推理之前自动检索并注入知识</li>
 *   <li><b>AGENTIC</b>: 智能体主动决定何时通过工具检索知识</li>
 *   <li><b>NONE</b>: 未启用 RAG 功能</li>
 * </ul>
 *
 * @deprecated 自 2.0.0 起弃用。rag 包已移除；请在应用层集成检索。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public enum RAGMode {
    /**
     * Generic 模式：通过钩子在每一步推理之前自动检索并注入知识。
     *
     * <p>此模式下，系统根据用户查询自动检索相关知识，并将其注入提示词上下文。
     */
    GENERIC,

    /**
     * Agentic 模式：智能体决定何时通过工具检索知识。
     *
     * <p>此模式下，智能体拥有知识检索工具，并根据对话上下文主动决定何时使用。
     */
    AGENTIC,

    /**
     * 禁用模式：不提供 RAG 功能。
     *
     * <p>此智能体未启用知识检索。
     */
    NONE
}
