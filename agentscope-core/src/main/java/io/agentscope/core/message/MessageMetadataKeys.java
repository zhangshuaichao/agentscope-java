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
package io.agentscope.core.message;

/**
 * 常用消息元数据键的常量。
 *
 * <p>定义框架统一使用的标准元数据键，保证一致性并避免魔法字符串。
 */
public final class MessageMetadataKeys {

    private MessageMetadataKeys() {
        // 禁止实例化
    }

    /**
     * 用于跳过多智能体格式化器历史合并的元数据键。
     *
     * <p>使用多智能体格式化器时，该标记为 {@code true} 的消息保持独立，不会合并到对话历史中。
     *
     * <p><b>类型：</b> Boolean
     * <p><b>示例：</b>
     * <pre>{@code
     * Map<String, Object> metadata = new HashMap<>();
     * metadata.put(MessageMetadataKeys.BYPASS_MULTIAGENT_HISTORY_MERGE, true);
     * Msg msg = Msg.builder()
     *     .role(MsgRole.USER)
     *     .content(TextBlock.builder().text("Important reminder").build())
     *     .metadata(metadata)
     *     .build();
     * }</pre>
     */
    public static final String BYPASS_MULTIAGENT_HISTORY_MERGE = "_bypass_multiagent_history_merge";

    /**
     * 用于标记结构化输出提醒消息的元数据键。
     *
     * <p>ReActAgent 内部用此标记识别临时提醒消息，引导模型使用 generate_response 工具生成结构化输出。
     *
     * <p><b>类型：</b> Boolean
     * <p><b>仅供内部使用</b>
     */
    public static final String STRUCTURED_OUTPUT_REMINDER = "_structured_output_reminder";

    /**
     * 结构化输出提醒类型的元数据键。
     *
     * <p>保存创建提醒消息时使用的 {@link io.agentscope.core.model.StructuredOutputReminder} 模式（例如 TOOL_CHOICE、PROMPT），以便钩子应用模式对应的行为。
     *
     * <p><b>类型：</b> String（StructuredOutputReminder 枚举名称）
     * <p><b>仅供内部使用</b>
     */
    public static final String STRUCTURED_OUTPUT_REMINDER_TYPE = "_structured_output_reminder_type";

    /**
     * 用于标记达到最大迭代次数后摘要生成失败的元数据键。
     *
     * <p><b>类型：</b> Boolean
     * <p><b>仅供内部使用</b>
     */
    public static final String SUMMARY_FAILED = "_summary_failed";

    /**
     * 对话用量统计的元数据键。
     *
     * <p>包含模型生成期间累积的 token 用量信息：输入、输出、缓存、缓存创建、推理、工具调用提示 token，以及耗时。用于跟踪 token 消耗，估算成本和监控使用情况。
     *
     * <p><b>类型：</b> {@link io.agentscope.core.model.ChatUsage}
     * <p><b>示例：</b>
     * <pre>{@code
     * Msg msg = agent.call(userMsg).block();
     * ChatUsage usage = msg.getChatUsage();
     * if (usage != null) {
     *     System.out.println("Input tokens: " + usage.getInputTokens());
     *     System.out.println("Output tokens: " + usage.getOutputTokens());
     *     System.out.println("Cached tokens: " + usage.getCachedTokens());
     *     System.out.println("Cache creation tokens: " + usage.getCacheCreationTokens());
     *     System.out.println("Reasoning tokens: " + usage.getReasoningTokens());
     *     System.out.println("Tool-use prompt tokens: " + usage.getToolUsePromptTokens());
     *     System.out.println("Total tokens: " + usage.getTotalTokens());
     * }
     * }</pre>
     */
    public static final String CHAT_USAGE = "_chat_usage";

    /**
     * 标记模型因达到输出 token 上限而停止生成的元数据键。
     */
    public static final String OUTPUT_LIMIT_REACHED = "_output_limit_reached";

    /**
     * 结构化输出数据的元数据键。
     *
     * <p>包含 generate_response 工具生成的结构化响应数据。此键将结构化输出与其他元数据隔离，以避免冲突。
     *
     * <p><b>类型：</b> {@code Map<String, Object>} 或用户定义的 POJO
     * <p><b>示例：</b>
     * <pre>{@code
     * Msg msg = agent.call(userMsg, WeatherResponse.class).block();
     * WeatherResponse response = msg.getStructuredData(WeatherResponse.class);
     * }</pre>
     */
    public static final String STRUCTURED_OUTPUT = "_structured_output";

    /**
     * 显式标记消息启用或禁用提示缓存的元数据键。
     *
     * <p>为 {@code true} 时，格式化器在该消息的最后一个内容块添加 <code>cache_control:
     * {"type": "ephemeral"}</code>。为 {@code false} 时，该消息被明确排除在缓存之外：不输出
     * <code>cache_control</code>，通过 {@link io.agentscope.core.model.GenerateOptions#getCacheControl()}
     * 配置的自动缓存控制策略也会跳过它。
     *
     * <p>显式标记（{@code true} 或 {@code false}）优先于自动策略，不会被覆盖。
     *
     * <p><b>类型：</b> Boolean
     * <p><b>示例：</b>
     * <pre>{@code
     * Map<String, Object> metadata = new HashMap<>();
     * metadata.put(MessageMetadataKeys.CACHE_CONTROL, true);
     * Msg msg = Msg.builder()
     *     .role(MsgRole.USER)
     *     .textContent("Important context to cache...")
     *     .metadata(metadata)
     *     .build();
     * }</pre>
     */
    public static final String CACHE_CONTROL = "_cache_control";

    /**
     * 标记由 {@code returnDirect} 工具路径合成的助手消息的元数据键（将工具结果作为本轮最终答复）。
     *
     * <p><b>类型：</b> Boolean
     */
    public static final String TOOL_RETURN_DIRECT = "_tool_return_direct";
}
