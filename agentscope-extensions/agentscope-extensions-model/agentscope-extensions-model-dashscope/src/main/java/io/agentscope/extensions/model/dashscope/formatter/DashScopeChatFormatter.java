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
package io.agentscope.extensions.model.dashscope.formatter;

import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.dashscope.dto.DashScopeContentPart;
import io.agentscope.extensions.model.dashscope.dto.DashScopeInput;
import io.agentscope.extensions.model.dashscope.dto.DashScopeMessage;
import io.agentscope.extensions.model.dashscope.dto.DashScopeParameters;
import io.agentscope.extensions.model.dashscope.dto.DashScopeRequest;
import io.agentscope.extensions.model.dashscope.dto.DashScopeResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DashScope Conversation/Generation API 的格式化器。
 * 在 AgentScope Msg 与 DashScope DTO 之间转换。
 *
 * <p>同时处理文本和多模态消息，支持 Generation API 与 MultiModalConversation API。
 */
public class DashScopeChatFormatter
        extends AbstractBaseFormatter<DashScopeMessage, DashScopeResponse, DashScopeRequest> {

    private static final Logger log = LoggerFactory.getLogger(DashScopeChatFormatter.class);

    private static final Map<String, String> EPHEMERAL_CACHE_CONTROL = Map.of("type", "ephemeral");
    private static final int MAX_CACHE_MARKERS = 4;

    private final DashScopeMessageConverter messageConverter;
    private final DashScopeResponseParser responseParser;
    private final DashScopeToolsHelper toolsHelper;

    public DashScopeChatFormatter() {
        this.messageConverter = new DashScopeMessageConverter(this::convertToolResultToString);
        this.responseParser = new DashScopeResponseParser();
        this.toolsHelper = new DashScopeToolsHelper();
    }

    @Override
    protected List<DashScopeMessage> doFormat(List<Msg> msgs) {
        List<DashScopeMessage> result = new ArrayList<>();
        for (Msg msg : msgs) {
            boolean hasMedia = hasMediaContent(msg);
            DashScopeMessage dsMsg = messageConverter.convertToMessage(msg, hasMedia);
            if (dsMsg != null) {
                result.add(dsMsg);
            }
        }
        return result;
    }

    @Override
    protected List<DashScopeMessage> doFormat(List<Msg> msgs, GenerateOptions options) {
        List<DashScopeMessage> result = new ArrayList<>();
        List<Boolean> cacheDirectives = new ArrayList<>();
        for (Msg msg : msgs) {
            DashScopeMessage dsMsg = messageConverter.convertToMessage(msg, hasMediaContent(msg));
            if (dsMsg != null) {
                result.add(dsMsg);
                cacheDirectives.add(cacheControlDirective(msg));
            }
        }
        applyAutomaticCacheControl(result, cacheDirectives, options);
        return result;
    }

    @Override
    public ChatResponse parseResponse(DashScopeResponse result, Instant startTime) {
        return responseParser.parseResponse(result, startTime);
    }

    @Override
    public void applyOptions(
            DashScopeRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        DashScopeParameters params = request.getParameters();
        if (params == null) {
            params = DashScopeParameters.builder().build();
            request.setParameters(params);
        }
        toolsHelper.applyOptions(params, options, defaultOptions);
    }

    @Override
    public void applyTools(DashScopeRequest request, List<ToolSchema> tools) {
        DashScopeParameters params = request.getParameters();
        if (params == null) {
            params = DashScopeParameters.builder().build();
            request.setParameters(params);
        }
        params.setTools(toolsHelper.convertTools(tools));
    }

    /**
     * 将工具选择配置应用到 DashScopeRequest。
     *
     * @param request DashScope 请求
     * @param toolChoice 工具选择配置
     */
    @Override
    public void applyToolChoice(DashScopeRequest request, ToolChoice toolChoice) {
        DashScopeParameters params = request.getParameters();
        if (params == null) {
            params = DashScopeParameters.builder().build();
            request.setParameters(params);
        }
        toolsHelper.applyToolChoice(params, toolChoice);
    }

    /**
     * 将 AgentScope Msg 格式化为 DashScope 多模态消息，供需要 MultiModalConversation API 的视觉模型使用。
     *
     * @param messages 待转换的 AgentScope 消息
     * @return 包含多模态内容的 DashScopeMessage 列表
     */
    public List<DashScopeMessage> formatMultiModal(List<Msg> messages) {
        return formatMultiModal(messages, null);
    }

    /**
     * 使用请求级生成选项将 AgentScope Msg 格式化为 DashScope 多模态消息。
     *
     * @param messages 待转换的 AgentScope 消息
     * @param options 请求级生成选项，可为 {@code null}
     * @return 包含多模态内容的 DashScopeMessage 列表
     */
    public List<DashScopeMessage> formatMultiModal(List<Msg> messages, GenerateOptions options) {
        List<DashScopeMessage> result = new ArrayList<>();
        List<Boolean> cacheDirectives = new ArrayList<>();
        for (Msg msg : messages) {
            DashScopeMessage message = messageConverter.convertToMessage(msg, true);
            result.add(message);
            cacheDirectives.add(cacheControlDirective(msg));
        }
        applyAutomaticCacheControl(result, cacheDirectives, options);
        return result;
    }

    /**
     * 为 API 调用构建完整 DashScopeRequest。
     *
     * @param model 模型名称
     * @param messages 格式化后的 DashScope 消息
     * @param stream 是否启用流式
     * @return 可用于 API 调用的完整 DashScopeRequest
     */
    public DashScopeRequest buildRequest(
            String model, List<DashScopeMessage> messages, boolean stream) {
        DashScopeParameters params =
                DashScopeParameters.builder().incrementalOutput(stream).build();

        return DashScopeRequest.builder()
                .model(model)
                .input(DashScopeInput.builder().messages(messages).build())
                .parameters(params)
                .build();
    }

    /**
     * 使用完整配置构建 DashScopeRequest。
     *
     * @param model 模型名称
     * @param messages 格式化后的 DashScope 消息
     * @param stream 是否启用流式
     * @param options 生成选项
     * @param defaultOptions 默认生成选项
     * @param tools 工具模式
     * @param toolChoice 工具选择配置
     * @return 可用于 API 调用的完整 DashScopeRequest
     */
    public DashScopeRequest buildRequest(
            String model,
            List<DashScopeMessage> messages,
            boolean stream,
            GenerateOptions options,
            GenerateOptions defaultOptions,
            List<ToolSchema> tools,
            ToolChoice toolChoice) {

        DashScopeRequest request = buildRequest(model, messages, stream);

        applyOptions(request, options, defaultOptions);
        applyTools(request, tools);
        applyToolChoice(request, toolChoice);

        return request;
    }

    static void setCacheControlOnContent(DashScopeMessage message) {
        DashScopeContentPart lastPart = getOrCreateLastContentPart(message);
        if (lastPart == null) {
            throw new IllegalStateException(
                    "Cannot place cache_control on a message without a content part");
        }
        if (lastPart.getCacheControl() == null) {
            lastPart.setCacheControl(EPHEMERAL_CACHE_CONTROL);
        }
    }

    private static DashScopeContentPart getOrCreateLastContentPart(DashScopeMessage message) {
        Object content = message.getContent();
        if (content instanceof String text) {
            DashScopeContentPart textPart = DashScopeContentPart.text(text);
            message.setContent(List.of(textPart));
            return textPart;
        } else if (content instanceof List<?> parts) {
            for (int i = parts.size() - 1; i >= 0; i--) {
                if (parts.get(i) instanceof DashScopeContentPart part) {
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
            List<DashScopeMessage> messages, List<Boolean> directives, GenerateOptions options) {
        if (messages == null
                || messages.isEmpty()
                || options == null
                || !Boolean.TRUE.equals(options.getCacheControl())) {
            return;
        }
        if (messages.size() != directives.size()) {
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
        if (shouldAutoCache(directives.get(lastIndex))) {
            candidates.add(lastIndex);
        }
        for (int i = 0; i < messages.size(); i++) {
            if ("system".equals(messages.get(i).getRole()) && shouldAutoCache(directives.get(i))) {
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

    private static int countCacheMarkers(List<DashScopeMessage> messages) {
        int count = 0;
        for (DashScopeMessage message : messages) {
            Object content = message.getContent();
            if (content instanceof List<?> parts) {
                for (Object part : parts) {
                    if (part instanceof DashScopeContentPart contentPart
                            && contentPart.getCacheControl() != null) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
