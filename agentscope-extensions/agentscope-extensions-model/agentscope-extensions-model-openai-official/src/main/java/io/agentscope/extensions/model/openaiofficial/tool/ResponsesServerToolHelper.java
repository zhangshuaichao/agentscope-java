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
package io.agentscope.extensions.model.openaiofficial.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.openai.core.ObjectMappers;
import com.openai.models.responses.ResponseCodeInterpreterToolCall;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseImageGenCallPartialImageEvent;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.Tool;
import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.tool.ToolValidator;
import io.agentscope.extensions.model.openaiofficial.OpenAIOfficialModelException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI Responses 提供商执行内置工具的共享映射与解码逻辑。
 *
 * <p>在 AgentScope 元数据中保留原始 Responses 项 JSON，
 * 使后续轮次无需从通用工具块重建，即可将提供商专属项原样传回。
 */
public final class ResponsesServerToolHelper {

    /**
     * 保存原始 Responses 输出项 JSON 的元数据键。
     */
    static final String MD_RESPONSES_SERVER_TOOL_ITEM = "openai.serverToolItem";

    /**
     * 保存 Code Interpreter 容器 ID 的元数据键。
     */
    static final String MD_CODE_INTERPRETER_CONTAINER_ID = "openai.codeInterpreter.containerId";

    private static final Logger log = LoggerFactory.getLogger(ResponsesServerToolHelper.class);

    private ResponsesServerToolHelper() {}

    public static boolean isSupportedServerToolItem(ResponseOutputItem item) {
        return isServerToolCall(item) || item.isToolSearchOutput();
    }

    public static boolean hasEmbeddedServerToolResult(ResponseOutputItem item) {
        return item.isWebSearchCall()
                || item.isCodeInterpreterCall()
                || item.isImageGenerationCall();
    }

    public static List<Tool> mergeTools(
            List<Tool> clientTools, List<OpenAIServerTool> serverTools) {
        List<Tool> merged = new ArrayList<>();
        if (clientTools != null) {
            merged.addAll(clientTools);
        }
        if (serverTools != null) {
            for (OpenAIServerTool serverTool : serverTools) {
                merged.add(serverTool.toTool());
            }
        }
        return List.copyOf(merged);
    }

    // 只负责 OpenAI 明确要求通过 include 请求的字段
    public static void applyIncludes(
            ResponseCreateParams.Builder builder, List<OpenAIServerTool> serverTools) {
        if (serverTools == null || serverTools.isEmpty()) {
            return;
        }

        Set<ResponseIncludable> includes = new LinkedHashSet<>();
        for (OpenAIServerTool serverTool : serverTools) {
            switch (serverTool.getType()) {
                case OpenAIServerTool.WEB_SEARCH -> {
                    includes.add(ResponseIncludable.WEB_SEARCH_CALL_RESULTS);
                    includes.add(ResponseIncludable.WEB_SEARCH_CALL_ACTION_SOURCES);
                }
                case OpenAIServerTool.CODE_INTERPRETER ->
                        includes.add(ResponseIncludable.CODE_INTERPRETER_CALL_OUTPUTS);
                default -> {}
            }
        }
        if (!includes.isEmpty()) {
            builder.include(List.copyOf(includes));
        }
    }

    public static List<ContentBlock> decodeBlocks(ResponseOutputItem item) {
        return decodeBlocks(item, null);
    }

    /**
     * 从 Responses 输出项解码服务端工具调用及结果。
     *
     * @param item 待解码的输出项
     * @param resultIdOverride 由 {@link HostedToolSearchPairing#companionCallId}
     *     解析的配对调用 ID，或 null
     * @return 按调用先、结果后的顺序排列的解码块
     */
    public static List<ContentBlock> decodeBlocks(
            ResponseOutputItem item, String resultIdOverride) {
        List<ContentBlock> blocks = new ArrayList<>();
        decodeCallBlock(item).ifPresent(blocks::add);
        decodeResultBlock(item, resultIdOverride).ifPresent(blocks::add);
        return blocks;
    }

    /**
     * 从 Responses 输出项解码服务端工具调用。
     *
     * <p>某些服务端工具将结果嵌入调用所在的同一输出项，另一些则为结果发出独立输出项。
     * 对于内嵌结果，原始 Responses 项由 {@code ToolResultBlock.metadata} 持有；
     * {@code ToolUseBlock} 仅携带服务端工具标记，避免历史重放重复发出同一项。
     */
    public static Optional<ToolUseBlock> decodeCallBlock(ResponseOutputItem item) {
        if (!isServerToolCall(item)) {
            return Optional.empty();
        }

        String type = serverToolCallType(item);
        String json = toJson(item);
        if (json == null) {
            return Optional.empty();
        }

        JsonNode root = readTree(json);
        String id = callId(root);
        if (!ToolValidator.requireNonBlank("OpenAI official", type, id)) {
            return Optional.empty();
        }

        JsonNode inputNode = inputNode(root, type);
        Map<String, Object> input = inputMap(inputNode);
        String content = content(inputNode);
        Map<String, Object> metadata =
                hasEmbeddedServerToolResult(item)
                        ? Map.of(ToolUseBlock.METADATA_SERVER_TOOL, true)
                        : Map.of(
                                ToolUseBlock.METADATA_SERVER_TOOL,
                                true,
                                MD_RESPONSES_SERVER_TOOL_ITEM,
                                json);

        return Optional.of(
                ToolUseBlock.builder()
                        .id(id)
                        .name(type)
                        .input(input)
                        .content(content)
                        .metadata(metadata)
                        .state(ToolCallState.FINISHED)
                        .build());
    }

    /**
     * 从 Responses 输出项解码服务端工具结果。
     *
     * <p>调用与结果共享同一输出项时，此方法提取内嵌结果。结果为独立输出项时，
     * 调用项在此不产生结果，而改为解码配对的结果项。原始 Responses 项仅保存在
     * {@code ToolResultBlock.metadata} 中，用于后续历史重放。
     */
    public static Optional<ToolResultBlock> decodeResultBlock(ResponseOutputItem item) {
        return decodeResultBlock(item, null);
    }

    /**
     * 从 Responses 输出项解码服务端工具结果。
     *
     * @param resultIdOverride 为托管工具搜索由
     *     {@link HostedToolSearchPairing#companionCallId} 解析的配对调用 ID，或 null
     */
    static Optional<ToolResultBlock> decodeResultBlock(
            ResponseOutputItem item, String resultIdOverride) {
        if (!isSupportedServerToolItem(item)) {
            return Optional.empty();
        }
        // 这些调用项由其独立输出项负责重放。
        if (isServerToolCall(item) && !hasEmbeddedServerToolResult(item)) {
            return Optional.empty();
        }

        String json = toJson(item);
        if (json == null) {
            return Optional.empty();
        }

        if (item.isCodeInterpreterCall()) {
            return Optional.of(decodeCodeInterpreterResult(item.asCodeInterpreterCall(), json));
        }

        JsonNode root = readTree(json);
        // 托管 tool_search 输出携带 call_id: null，且与调用项不共享 ID。
        String id = resultIdOverride != null ? resultIdOverride : callId(root);
        String type = serverToolCallType(item);
        if (!ToolValidator.requireNonBlank("OpenAI official", type, id)) {
            return Optional.empty();
        }

        JsonNode resultNode = resultNode(root, type);
        String status = root.path("status").asText(null);
        List<ContentBlock> output = outputBlocks(resultNode, type, root, status);

        return Optional.of(
                ToolResultBlock.builder()
                        .id(id)
                        .name(type)
                        .output(output)
                        .metadata(
                                Map.of(
                                        ToolResultBlock.METADATA_SERVER_TOOL,
                                        true,
                                        MD_RESPONSES_SERVER_TOOL_ITEM,
                                        json))
                        .state(resultState(status))
                        .build());
    }

    public static ToolResultBlock decodePartialImage(ResponseImageGenCallPartialImageEvent event) {
        DataBlock partialImage =
                DataBlock.builder()
                        .source(
                                Base64Source.builder()
                                        .mediaType(
                                                imageMediaType(event.outputFormat().orElse("png")))
                                        .data(event.partialImageB64())
                                        .build())
                        .id(event.itemId() + "-partial-" + event.partialImageIndex())
                        .name("partial-image-" + event.partialImageIndex())
                        .build();

        return ToolResultBlock.builder()
                .id(event.itemId())
                .name(OpenAIServerTool.IMAGE_GENERATION)
                .output(List.of(partialImage))
                .metadata(Map.of(ToolResultBlock.METADATA_SERVER_TOOL, true))
                .state(ToolResultState.RUNNING)
                .build();
    }

    private static ToolResultBlock decodeCodeInterpreterResult(
            ResponseCodeInterpreterToolCall call, String json) {
        List<ContentBlock> output = new ArrayList<>();
        List<ResponseCodeInterpreterToolCall.Output> outputs = call.outputs().orElse(List.of());
        for (int i = 0; i < outputs.size(); i++) {
            ResponseCodeInterpreterToolCall.Output result = outputs.get(i);
            if (result.isLogs()) {
                output.add(TextBlock.builder().text(result.asLogs().logs()).build());
            } else if (result.isImage()) {
                output.add(
                        DataBlock.builder()
                                .source(
                                        URLSource.builder()
                                                .url(result.asImage().url())
                                                .mimeType(
                                                        imageMediaTypeFromUrl(
                                                                result.asImage().url()))
                                                .build())
                                .id(call.id() + "-image-" + i)
                                .name("code-interpreter-image")
                                .build());
            }
        }
        if (output.isEmpty()) {
            output.add(TextBlock.builder().text(call.status().asString()).build());
        }

        return ToolResultBlock.builder()
                .id(call.id())
                .name(OpenAIServerTool.CODE_INTERPRETER)
                .output(output)
                .metadata(
                        Map.of(
                                ToolResultBlock.METADATA_SERVER_TOOL,
                                true,
                                MD_RESPONSES_SERVER_TOOL_ITEM,
                                json,
                                MD_CODE_INTERPRETER_CONTAINER_ID,
                                call.containerId()))
                .state(resultState(call.status().asString()))
                .build();
    }

    public static Optional<ResponseInputItem> restoreServerToolItem(Map<String, Object> metadata) {
        Object raw = metadata == null ? null : metadata.get(MD_RESPONSES_SERVER_TOOL_ITEM);
        if (!(raw instanceof String json) || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ObjectMappers.jsonMapper().readValue(json, ResponseInputItem.class));
        } catch (Exception e) {
            throw new OpenAIOfficialModelException(
                    "Cannot restore OpenAI server tool item from history: " + e.getMessage(),
                    e,
                    null);
        }
    }

    private static boolean isServerToolCall(ResponseOutputItem item) {
        return item.isWebSearchCall()
                || item.isCodeInterpreterCall()
                || item.isImageGenerationCall()
                || item.isToolSearchCall();
    }

    private static String serverToolCallType(ResponseOutputItem item) {
        if (item.isWebSearchCall()) {
            return OpenAIServerTool.WEB_SEARCH;
        }
        if (item.isCodeInterpreterCall()) {
            return OpenAIServerTool.CODE_INTERPRETER;
        }
        if (item.isImageGenerationCall()) {
            return OpenAIServerTool.IMAGE_GENERATION;
        }
        if (item.isToolSearchCall() || item.isToolSearchOutput()) {
            return OpenAIServerTool.TOOL_SEARCH;
        }
        throw new IllegalArgumentException(
                "This OpenAI server tool output item is not supported yet");
    }

    private static String toJson(ResponseOutputItem item) {
        Object value;
        if (item.isWebSearchCall()) {
            value = item.asWebSearchCall();
        } else if (item.isCodeInterpreterCall()) {
            value = item.asCodeInterpreterCall();
        } else if (item.isImageGenerationCall()) {
            value = item.asImageGenerationCall();
        } else if (item.isToolSearchCall()) {
            value = item.asToolSearchCall();
        } else if (item.isToolSearchOutput()) {
            value = item.asToolSearchOutput();
        } else {
            return null;
        }

        try {
            return ObjectMappers.jsonMapper().writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize OpenAI server tool item: {}", e.getMessage());
            return null;
        }
    }

    private static JsonNode readTree(String json) {
        try {
            return ObjectMappers.jsonMapper().readTree(json);
        } catch (Exception e) {
            log.warn("Failed to parse OpenAI server tool item JSON: {}", e.getMessage());
            return ObjectMappers.jsonMapper().createObjectNode();
        }
    }

    private static String callId(JsonNode root) {
        String callId = root.path("call_id").asText(null);
        return callId != null && !callId.isBlank() ? callId : root.path("id").asText(null);
    }

    private static JsonNode inputNode(JsonNode root, String type) {
        return switch (type) {
            case OpenAIServerTool.WEB_SEARCH -> root.path("action");
            case OpenAIServerTool.CODE_INTERPRETER -> root.path("code");
            case OpenAIServerTool.TOOL_SEARCH -> root.path("arguments");
            default -> ObjectMappers.jsonMapper().createObjectNode();
        };
    }

    private static JsonNode resultNode(JsonNode root, String type) {
        return switch (type) {
            case OpenAIServerTool.WEB_SEARCH ->
                    root.has("results") ? root.path("results") : root.path("action");
            case OpenAIServerTool.CODE_INTERPRETER -> root.path("outputs");
            case OpenAIServerTool.IMAGE_GENERATION -> root.path("result");
            case OpenAIServerTool.TOOL_SEARCH -> root.path("tools");
            default -> MissingNode.getInstance();
        };
    }

    private static Map<String, Object> inputMap(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return Map.of();
        }
        if (node.isObject()) {
            try {
                return ObjectMappers.jsonMapper()
                        .convertValue(
                                node,
                                new com.fasterxml.jackson.core.type.TypeReference<
                                        Map<String, Object>>() {});
            } catch (IllegalArgumentException ignored) {
                return Map.of();
            }
        }
        if (node.isArray()) {
            List<Object> values =
                    ObjectMappers.jsonMapper()
                            .convertValue(
                                    node,
                                    new com.fasterxml.jackson.core.type.TypeReference<
                                            List<Object>>() {});
            return Map.of("value", values);
        }
        return Map.of("value", node.asText());
    }

    private static String content(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "{}";
        }
        return node.toString();
    }

    private static String resultText(JsonNode node, String status) {
        if (node != null && !node.isMissingNode() && !node.isNull()) {
            return node.toString();
        }
        return status != null && !status.isBlank() ? status : "{}";
    }

    private static List<ContentBlock> outputBlocks(
            JsonNode resultNode, String type, JsonNode root, String status) {
        if (OpenAIServerTool.IMAGE_GENERATION.equals(type)
                && resultNode != null
                && !resultNode.isMissingNode()
                && !resultNode.isNull()) {
            return List.of(
                    DataBlock.builder()
                            .source(
                                    Base64Source.builder()
                                            .mediaType(imageMediaType(root))
                                            .data(resultNode.asText())
                                            .build())
                            .name(OpenAIServerTool.IMAGE_GENERATION)
                            .build());
        }
        return List.of(TextBlock.builder().text(resultText(resultNode, status)).build());
    }

    private static String imageMediaType(JsonNode root) {
        String outputFormat =
                root.path("output_format").asText(root.path("outputFormat").asText("png"));
        return imageMediaType(outputFormat);
    }

    private static String imageMediaType(String outputFormat) {
        if (outputFormat == null || outputFormat.isBlank()) {
            return "image/png";
        }
        String mediaType =
                MediaUtils.determineMediaType(
                        "image." + outputFormat.trim().toLowerCase(Locale.ROOT));
        return mediaType;
    }

    private static String imageMediaTypeFromUrl(String url) {
        String mediaType = MediaUtils.determineMediaType(url);
        return mediaType.startsWith("image/") ? mediaType : null;
    }

    private static ToolResultState resultState(String status) {
        if ("failed".equalsIgnoreCase(status)) {
            return ToolResultState.ERROR;
        }
        if ("in_progress".equalsIgnoreCase(status) || "incomplete".equalsIgnoreCase(status)) {
            return ToolResultState.RUNNING;
        }
        return ToolResultState.SUCCESS;
    }

    /**
     * 将托管 tool_search 输出项与其前面的调用项配对。
     *
     * <p>托管（服务端执行）工具搜索先发出 {@code tool_search_call}，
     * 随后发出 {@code tool_search_output}；两项均携带 {@code call_id: null}，
     * 且拥有不同的项 ID。按输出顺序将各项交给 {@link #companionCallId(ResponseOutputItem)}，
     * 使解码结果与解码调用配对。有多个待处理调用时，输出优先匹配最早尚未解决的调用。
     */
    public static final class HostedToolSearchPairing {

        private final Deque<String> unmatchedCallIds = new ArrayDeque<>();

        /**
         * 记录托管 tool_search 调用，或解析其输出对应的调用 ID。
         *
         * @param item 即将解码的输出项
         * @return 托管输出项的配对调用 ID
         */
        public String companionCallId(ResponseOutputItem item) {
            if (!item.isToolSearchCall() && !item.isToolSearchOutput()) {
                return null;
            }
            String json = toJson(item);
            if (json == null) {
                return null;
            }
            JsonNode root = readTree(json);
            if (!"server".equalsIgnoreCase(root.path("execution").asText(""))) {
                return null;
            }
            if (item.isToolSearchCall()) {
                String callId = callId(root);
                if (callId != null && !callId.isBlank()) {
                    unmatchedCallIds.offer(callId);
                }
                return null;
            }
            return unmatchedCallIds.poll();
        }
    }
}
