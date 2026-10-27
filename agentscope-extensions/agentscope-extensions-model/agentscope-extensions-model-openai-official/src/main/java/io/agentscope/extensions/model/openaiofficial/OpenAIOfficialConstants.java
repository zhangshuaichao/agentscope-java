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
package io.agentscope.extensions.model.openaiofficial;

import java.util.Set;

/**
 * OpenAI official 模块的共享常量。
 *
 * <p>定义提供者 ID、{@code additionalBodyParams} 允许列表键集合，以及
 * 元数据命名空间键（{@code openai.*}）。所有内部组件引用这些常量，
 * 确保写入元数据键与历史重放读取使用完全相同的字符串。
 */
final class OpenAIOfficialConstants {

    private OpenAIOfficialConstants() {}

    /**
     * ModelException、SPI 注册及模型 ID 前缀中使用的提供者 ID。
     */
    static final String PROVIDER_ID = "openai-official";

    // ── additionalBodyParams 允许列表 ───────────────────────────

    /**
     * {@code GenerateOptions.additionalBodyParams} 中允许的键集合。
     * 不在允许列表中的键会在请求映射时触发快速失败。
     */
    static final Set<String> ADDITIONAL_BODY_PARAMS_WHITELIST =
            Set.of(
                    "max_tool_calls",
                    "prompt_cache_key",
                    "prompt_cache_options",
                    "previous_response_id",
                    "service_tier",
                    "safety_identifier",
                    "store",
                    "reasoning.summary",
                    "reasoning.context",
                    "reasoning.mode");

    // ── 元数据命名空间键 ─────────────────────────

    // 响应级
    static final String MD_RESPONSE_ID = "openai.response.id";
    static final String MD_RESPONSE_STATUS = "openai.response.status";
    static final String MD_RESPONSE_CREATED_AT = "openai.response.created_at";
    static final String MD_RESPONSE_COMPLETED_AT = "openai.response.completed_at";
    static final String MD_RESPONSE_INCOMPLETE_REASON = "openai.response.incomplete_reason";
    static final String MD_RESPONSE_ERROR = "openai.response.error";
    static final String MD_RESPONSE_CITATIONS = "openai.response.citations";

    // 推理级（内部状态，用于历史重放）
    static final String MD_REASONING_ENCRYPTED_CONTENT = "openai.reasoning.encrypted_content";
    static final String MD_REASONING_TEXT = "openai.reasoning.text";

    // 函数调用级（内部状态，用于历史重放）
    static final String MD_FUNCTION_CALL_NAMESPACE = "openai.functionCall.namespace";
}
