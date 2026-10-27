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
package io.agentscope.core.hook;

/**
 * 表示全部钩子事件类型的枚举。
 *
 * <p>这些事件在智能体执行的不同阶段触发，
 * 可通过实现 {@link Hook} 接口拦截。
 *
 * @see Hook
 * @see HookEvent
 * @deprecated 自 2.0.0 起弃用。请改用 {@link io.agentscope.core.middleware.MiddlewareBase}。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public enum HookEventType {
    /** 智能体开始处理之前 */
    PRE_CALL,

    /** 智能体完成处理之后 */
    POST_CALL,

    /** LLM 推理之前 */
    PRE_REASONING,

    /** LLM 推理完成之后 */
    POST_REASONING,

    /** LLM 推理流式输出期间 */
    REASONING_CHUNK,

    /** 工具执行之前 */
    PRE_ACTING,

    /** 工具执行完成之后 */
    POST_ACTING,

    /** 工具执行流式输出期间 */
    ACTING_CHUNK,

    /** 摘要生成之前（达到最大迭代次数时） */
    PRE_SUMMARY,

    /** 摘要生成完成之后 */
    POST_SUMMARY,

    /** 摘要流式输出期间 */
    SUMMARY_CHUNK,

    /** 发生错误时 */
    ERROR
}
