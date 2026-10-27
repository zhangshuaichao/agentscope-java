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
package io.agentscope.extensions.model.openai.formatter;

import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.dto.OpenAIContentPart;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.dto.OpenAIResponse;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI Chat Completion HTTP API 的基础格式化器，为单智能体与多智能体格式化器提供公共能力。
 *
 * <p>子类必须实现：
 * <ul>
 * <li>{@link #doFormat(List)}：将消息转换为 OpenAI 格式。</li>
 * <li>{@link #applyOptions(OpenAIRequest, GenerateOptions, GenerateOptions)}：应用生成选项。</li>
 * <li>{@link #applyTools(OpenAIRequest, List)}：应用工具模式。</li>
 * <li>{@link #applyToolChoice(OpenAIRequest, ToolChoice)}：应用工具选择配置。</li>
 * </ul>
 */
public abstract class OpenAIBaseFormatter
        extends AbstractBaseFormatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> {

    private static final Logger log = LoggerFactory.getLogger(OpenAIBaseFormatter.class);

    private static final Map<String, String> EPHEMERAL_CACHE_CONTROL = Map.of("type", "ephemeral");
    private static final int MAX_CACHE_MARKERS = 4;

    protected final OpenAIMessageConverter messageConverter;
    protected final OpenAIResponseParser responseParser;

    protected OpenAIBaseFormatter() {
        this.messageConverter =
                new OpenAIMessageConverter(
                        this::extractTextContent, this::convertToolResultToString);
        this.responseParser = new OpenAIResponseParser();
    }

    @Override
    public ChatResponse parseResponse(OpenAIResponse response, Instant startTime) {
        return responseParser.parseResponse(response, startTime);
    }

    /**
     * 将生成选项应用到请求。子类实现提供商专用的选项处理。
     *
     * @param request OpenAI 请求 DTO
     * @param options 要应用的生成选项
     * @param defaultOptions options 为 null 时使用的默认选项
     */
    @Override
    public abstract void applyOptions(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions);

    /**
     * 将工具模式应用到请求。子类实现提供商专用的工具处理。
     *
     * @param request OpenAI 请求 DTO
     * @param tools 要应用的工具模式列表，可为 null 或空
     */
    @Override
    public abstract void applyTools(OpenAIRequest request, List<ToolSchema> tools);

    /**
     * 将工具选择配置应用到请求。子类实现提供商专用的工具选择处理。
     *
     * @param request OpenAI 请求 DTO
     * @param toolChoice 工具选择配置，null 表示 auto
     */
    @Override
    public abstract void applyToolChoice(OpenAIRequest request, ToolChoice toolChoice);

    /**
     * 结合提供商上下文应用工具模式。
     * 默认实现委托给 {@link #applyTools(OpenAIRequest, List)}。
     *
     * @param request OpenAI 请求 DTO
     * @param tools 要应用的工具模式
     * @param baseUrl API 基础 URL，默认忽略
     * @param modelName 模型名称，默认忽略
     */
    @Override
    public void applyTools(
            OpenAIRequest request, List<ToolSchema> tools, String baseUrl, String modelName) {
        applyTools(request, tools);
    }

    /**
     * 结合提供商上下文应用工具选择。
     * 默认实现委托给 {@link #applyToolChoice(OpenAIRequest, ToolChoice)}。
     *
     * @param request OpenAI 请求 DTO
     * @param toolChoice 工具选择配置
     * @param baseUrl API 基础 URL，默认忽略
     * @param modelName 模型名称，默认忽略
     */
    @Override
    public void applyToolChoice(
            OpenAIRequest request, ToolChoice toolChoice, String baseUrl, String modelName) {
        applyToolChoice(request, toolChoice);
    }

    /**
     * 构建基础 OpenAIRequest。
     *
     * @param model 模型名称
     * @param messages 已格式化的 OpenAI 消息
     * @param stream 是否启用流式输出
     * @return 基础 OpenAIRequest
     */
    public OpenAIRequest buildRequest(String model, List<OpenAIMessage> messages, boolean stream) {
        return OpenAIRequest.builder().model(model).messages(messages).stream(stream).build();
    }

    /**
     * 构建带完整配置的 OpenAIRequest。
     * 此方法提供便捷用法；通用代码更推荐使用标准 Formatter 接口，手动实例化请求并调用各 apply 方法。
     *
     * @param model 模型名称
     * @param messages 已格式化的 OpenAI 消息
     * @param stream 是否启用流式输出
     * @param options 生成选项
     * @param defaultOptions 默认生成选项
     * @param tools 工具模式
     * @param toolChoice 工具选择配置
     * @return 可直接调用 API 的完整 OpenAIRequest
     */
    public OpenAIRequest buildRequest(
            String model,
            List<OpenAIMessage> messages,
            boolean stream,
            GenerateOptions options,
            GenerateOptions defaultOptions,
            List<ToolSchema> tools,
            ToolChoice toolChoice) {

        OpenAIRequest request =
                OpenAIRequest.builder().model(model).messages(messages).stream(stream).build();

        applyOptions(request, options, defaultOptions);
        applyTools(request, tools);

        if (toolChoice != null) {
            applyToolChoice(request, toolChoice);
        }

        return request;
    }

    static void setCacheControlOnContent(OpenAIMessage message) {
        OpenAIContentPart lastPart = getOrCreateLastContentPart(message);
        if (lastPart == null) {
            throw new IllegalStateException(
                    "Cannot place cache_control on a message without a content part");
        }
        if (lastPart.getCacheControl() == null) {
            lastPart.setCacheControl(EPHEMERAL_CACHE_CONTROL);
        }
    }

    private static OpenAIContentPart getOrCreateLastContentPart(OpenAIMessage message) {
        Object content = message.getContent();
        if (content instanceof String text) {
            OpenAIContentPart textPart = OpenAIContentPart.text(text);
            message.setContent(List.of(textPart));
            return textPart;
        } else if (content instanceof List<?> parts) {
            for (int i = parts.size() - 1; i >= 0; i--) {
                if (parts.get(i) instanceof OpenAIContentPart part) {
                    return part;
                }
            }
        }
        return null;
    }

    static Boolean cacheControlDirective(Msg msg) {
        if (msg == null || msg.getMetadata() == null) {
            return null;
        }
        Object directive = msg.getMetadata().get(MessageMetadataKeys.CACHE_CONTROL);
        return directive instanceof Boolean value ? value : null;
    }

    static void applyAutomaticCacheControl(
            List<OpenAIMessage> messages, List<Boolean> cacheDirectives, GenerateOptions options) {
        if (messages == null
                || messages.isEmpty()
                || options == null
                || !Boolean.TRUE.equals(options.getCacheControl())) {
            return;
        }
        if (messages.size() != cacheDirectives.size()) {
            throw new IllegalStateException(
                    "Cache-control directives do not match formatted messages");
        }

        int markerCount = countCacheMarkers(messages);
        if (markerCount > MAX_CACHE_MARKERS) {
            log.warn(
                    "Request contains {} explicit cache_control markers; provider uses the last {};"
                            + " skipping automatic markers",
                    markerCount,
                    MAX_CACHE_MARKERS);
            return;
        }

        LinkedHashSet<Integer> candidates = new LinkedHashSet<>();
        int lastIndex = messages.size() - 1;
        if (shouldAutoCache(cacheDirectives.get(lastIndex))) {
            candidates.add(lastIndex);
        }
        for (int i = 0; i < messages.size(); i++) {
            if ("system".equals(messages.get(i).getRole())
                    && shouldAutoCache(cacheDirectives.get(i))) {
                candidates.add(i);
            }
        }

        for (Integer index : candidates) {
            if (markerCount >= MAX_CACHE_MARKERS) {
                break;
            }
            setCacheControlOnContent(messages.get(index));
            markerCount++;
        }
    }

    private static boolean shouldAutoCache(Boolean directive) {
        return directive == null;
    }

    private static int countCacheMarkers(List<OpenAIMessage> messages) {
        int count = 0;
        for (OpenAIMessage message : messages) {
            Object content = message.getContent();
            if (content instanceof List<?> parts) {
                for (Object part : parts) {
                    if (part instanceof OpenAIContentPart contentPart
                            && contentPart.getCacheControl() != null) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
