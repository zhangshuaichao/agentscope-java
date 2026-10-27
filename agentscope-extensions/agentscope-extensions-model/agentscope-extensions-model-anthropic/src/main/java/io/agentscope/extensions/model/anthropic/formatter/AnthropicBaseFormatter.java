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
package io.agentscope.extensions.model.anthropic.formatter;

import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.anthropic.tool.AnthropicServerTool;
import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic API 的抽象基础格式化器，共享 Anthropic 专用协议处理逻辑。
 *
 * <p>负责：
 * <ul><li>提取并应用系统消息，Anthropic 要求通过 system 参数传入。</li>
 * <li>利用 GenerateOptions 配置工具选择。</li></ul>
 */
public abstract class AnthropicBaseFormatter
        extends AbstractBaseFormatter<MessageParam, Object, MessageCreateParams.Builder> {

    protected final AnthropicMessageConverter messageConverter;

    /** 生成选项的线程局部存储，从 applyOptions 传递给 applyTools。 */
    private final ThreadLocal<GenerateOptions> currentOptions = new ThreadLocal<>();

    protected AnthropicBaseFormatter() {
        this.messageConverter = new AnthropicMessageConverter(this::convertToolResultToString);
    }

    /**
     * 将生成选项应用到 Anthropic 请求参数。
     *
     * @param paramsBuilder Anthropic 请求参数构建器
     * @param options 要应用的生成选项
     * @param defaultOptions options 为 null 时使用的默认选项
     */
    @Override
    public void applyOptions(
            MessageCreateParams.Builder paramsBuilder,
            GenerateOptions options,
            GenerateOptions defaultOptions) {
        // 保存包含模型级默认值的有效选项，供 applyTools 使用。
        currentOptions.set(GenerateOptions.mergeOptions(options, defaultOptions));

        // 应用其他选项。
        AnthropicToolsHelper.applyOptions(paramsBuilder, options, defaultOptions);
    }

    /**
     * 将工具模式应用到 Anthropic 请求参数，使用 applyOptions 保存的选项来配置工具选择。
     *
     * @param paramsBuilder Anthropic 请求参数构建器
     * @param tools 要应用的工具模式列表，可为 null 或空
     */
    @Override
    public void applyTools(MessageCreateParams.Builder paramsBuilder, List<ToolSchema> tools) {
        applyTools(paramsBuilder, tools, List.of());
    }

    /**
     * 将客户端工具与 Anthropic 内置服务端工具作为统一工具集应用。
     *
     * <p>【学习提示】选项通过线程局部变量从 applyOptions 传入，二者需要在同一线程内按序执行；
     * finally 清理可避免线程池复用时把本次配置带入下一次请求。
     *
     * @param paramsBuilder Anthropic 请求参数
     * @param tools 客户端工具模式
     * @param serverTools Anthropic 服务端工具
     */
    public void applyTools(
            MessageCreateParams.Builder paramsBuilder,
            List<ToolSchema> tools,
            List<AnthropicServerTool> serverTools) {
        GenerateOptions options = currentOptions.get();

        try {
            AnthropicToolsHelper.applyTools(paramsBuilder, tools, serverTools, options, cacheTtl);
        } finally {
            // 即使工具校验失败，也清理线程局部存储。
            currentOptions.remove();
        }
    }

    /**
     * 存在系统消息时提取并应用。Anthropic API 要求通过 system 参数设置系统消息，而不是放入普通消息列表。
     *
     * <p>由 Model 调用，从列表提取首条系统消息并应用到 system 参数。
     *
     * @param paramsBuilder Anthropic 请求参数构建器
     * @param messages 全部消息，可能包含系统消息
     */
    public void applySystemMessage(MessageCreateParams.Builder paramsBuilder, List<Msg> messages) {
        applySystemMessage(paramsBuilder, messages, false);
    }

    /**
     * 存在系统消息时提取并应用，可选地标记为提示词缓存。
     *
     * <p>{@code cacheControlEnabled} 为 true 时，系统提示词以文本块发送，携带
     * <code>cache_control: {"type": "ephemeral"}</code>，使 Anthropic 可缓存较长系统提示词。
     *
     * @param paramsBuilder Anthropic 请求参数构建器
     * @param messages 全部消息，可能包含系统消息
     * @param cacheControlEnabled 是否启用提示词缓存
     */
    public void applySystemMessage(
            MessageCreateParams.Builder paramsBuilder,
            List<Msg> messages,
            boolean cacheControlEnabled) {
        String systemMessage = messageConverter.extractSystemMessage(messages);
        if (systemMessage == null || systemMessage.isEmpty()) {
            return;
        }

        if (cacheControlEnabled) {
            paramsBuilder.systemOfTextBlockParams(
                    List.of(
                            TextBlockParam.builder()
                                    .text(systemMessage)
                                    .cacheControl(buildCacheControl(this.cacheTtl))
                                    .build()));
        } else {
            paramsBuilder.system(systemMessage);
        }
    }

    /** 提示词缓存共享的 ephemeral 缓存控制标记。 */
    static final CacheControlEphemeral EPHEMERAL_CACHE_CONTROL =
            CacheControlEphemeral.builder().build();

    /** 临时缓存控制的 TTL，在模型构建时设置一次。 */
    private String cacheTtl;

    /** 配置提示词缓存标记的 TTL，例如 {@code "1h"}。 */
    public void cacheTtl(String cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    /**
     * 构建应用已配置 TTL 的 {@link CacheControlEphemeral}。
     * 未设置 TTL 时返回共享的默认 ephemeral 标记。
     */
    static CacheControlEphemeral buildCacheControl(String cacheTtl) {
        if (cacheTtl == null || cacheTtl.isEmpty()) {
            return EPHEMERAL_CACHE_CONTROL;
        }
        return CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.of(cacheTtl)).build();
    }

    /**
     * 向已格式化消息应用自动缓存控制策略。
     *
     * <p>向最后一条消息的最后一个可缓存内容块添加
     * <code>cache_control: {"type": "ephemeral"}</code>。
     * Anthropic 缓存从开头直到标记块（含该块）的全部内容，因此可跨轮次缓存完整对话前缀。
     *
     * @param formattedMessages 已格式化的 Anthropic 消息
     * @return 已应用缓存控制的消息
     */
    public List<MessageParam> applyCacheControl(List<MessageParam> formattedMessages) {
        if (formattedMessages == null || formattedMessages.isEmpty()) {
            return formattedMessages;
        }

        int lastIdx = formattedMessages.size() - 1;
        MessageParam last = formattedMessages.get(lastIdx);
        CacheControlEphemeral cacheControl = buildCacheControl(this.cacheTtl);
        MessageParam marked = markLastCacheableBlock(last, cacheControl);
        if (marked == last) {
            return formattedMessages;
        }

        List<MessageParam> result = new ArrayList<>(formattedMessages);
        result.set(lastIdx, marked);
        return result;
    }

    /**
     * 返回消息副本，使最后一个可缓存内容块携带 ephemeral cache_control。
     * 字符串内容转换为单个文本块，以便附加标记。没有可标记内容时返回原始实例。
     */
    private static MessageParam markLastCacheableBlock(
            MessageParam message, CacheControlEphemeral cacheControl) {
        MessageParam.Content content = message.content();

        if (content.isString()) {
            TextBlockParam text =
                    TextBlockParam.builder()
                            .text(content.asString())
                            .cacheControl(cacheControl)
                            .build();
            return message.toBuilder()
                    .content(
                            MessageParam.Content.ofBlockParams(
                                    List.of(ContentBlockParam.ofText(text))))
                    .build();
        }

        if (content.isBlockParams()) {
            List<ContentBlockParam> blocks = new ArrayList<>(content.asBlockParams());
            for (int i = blocks.size() - 1; i >= 0; i--) {
                ContentBlockParam marked = withCacheControl(blocks.get(i), cacheControl);
                if (marked == null) {
                    continue;
                }
                blocks.set(i, marked);
                return message.toBuilder()
                        .content(MessageParam.Content.ofBlockParams(blocks))
                        .build();
            }
        }

        return message;
    }

    /**
     * 返回附带 ephemeral cache_control 的内容块副本；
     * 类型不支持 cache_control 时返回 {@code null}，例如思考块。
     */
    private static ContentBlockParam withCacheControl(
            ContentBlockParam block, CacheControlEphemeral cacheControl) {
        if (block.isText()) {
            return ContentBlockParam.ofText(
                    block.asText().toBuilder().cacheControl(cacheControl).build());
        }
        if (block.isImage()) {
            return ContentBlockParam.ofImage(
                    block.asImage().toBuilder().cacheControl(cacheControl).build());
        }
        if (block.isToolUse()) {
            return ContentBlockParam.ofToolUse(
                    block.asToolUse().toBuilder().cacheControl(cacheControl).build());
        }
        if (block.isToolResult()) {
            return ContentBlockParam.ofToolResult(
                    block.asToolResult().toBuilder().cacheControl(cacheControl).build());
        }
        return null;
    }
}
