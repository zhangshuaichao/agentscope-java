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
package io.agentscope.extensions.model.gemini.formatter;

import com.google.genai.types.CodeExecutionResult;
import com.google.genai.types.Content;
import com.google.genai.types.ExecutableCode;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.ToolCall;
import com.google.genai.types.ToolResponse;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 AgentScope Msg 对象转换为 Gemini API Content 格式的转换器。
 *
 * <p>处理核心消息转换逻辑，包括：
 * <ul>
 *   <li>文本块</li>
 *   <li>工具使用块（function_call）</li>
 *   <li>工具结果块（作为独立 Content 的 function_response）</li>
 *   <li>多模态内容（图像、音频、视频）</li>
 * </ul>
 *
 * <p><b>重要转换行为：</b>
 * <ul>
 *   <li>工具结果块转为独立的 "user" 角色 Content</li>
 *   <li>多项工具输出按每行 "- " 前缀格式化</li>
 *   <li>系统消息按 "user" 角色处理（Gemini API 要求）</li>
 * </ul>
 */
public class GeminiMessageConverter {

    private static final Logger log = LoggerFactory.getLogger(GeminiMessageConverter.class);

    private final GeminiMediaConverter mediaConverter;

    /**
     * 使用默认媒体转换器创建新的 GeminiMessageConverter。
     */
    public GeminiMessageConverter() {
        this.mediaConverter = new GeminiMediaConverter();
    }

    /**
     * 将 Msg 对象列表转换为 Gemini API Content 对象。
     *
     * @param msgs AgentScope 消息列表
     * @return Gemini Content 对象列表
     */
    public List<Content> convertMessages(List<Msg> msgs) {
        List<Content> result = new ArrayList<>();

        for (Msg msg : msgs) {
            List<Part> parts = new ArrayList<>();
            List<Part> functionResponseParts = new ArrayList<>();

            for (ContentBlock block : msg.getContent()) {
                if (block instanceof TextBlock tb) {
                    parts.add(Part.builder().text(tb.getText()).build());

                } else if (block instanceof ToolUseBlock tub) {
                    // 优先使用 content 字段（原始参数字符串），回退到 input 映射
                    Map<String, Object> args;
                    if (tub.getContent() != null && !tub.getContent().isEmpty()) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> parsed =
                                    JsonUtils.getJsonCodec().fromJson(tub.getContent(), Map.class);
                            args = parsed != null ? parsed : tub.getInput();
                        } catch (Exception e) {
                            log.warn(
                                    "Failed to parse content as JSON, falling back to input map:"
                                            + " {}",
                                    e.getMessage());
                            args = tub.getInput();
                        }
                    } else {
                        args = tub.getInput();
                    }

                    // 构建包含 FunctionCall 或 ToolCall 及可选思考签名的 Part
                    Part.Builder partBuilder = null;
                    if (isCodeExecutionToolUse(tub)) {
                        partBuilder = buildExecutableCodePart(tub);
                    } else if (tub.isServerTool()) {
                        // 为服务端（内置）工具创建 ToolCall
                        ToolCall toolCall =
                                ToolCall.builder()
                                        .id(tub.getId())
                                        .toolType(tub.getName())
                                        .args(args)
                                        .build();
                        partBuilder = Part.builder().toolCall(toolCall);
                    } else {
                        // 为本地函数调用创建 FunctionCall
                        FunctionCall functionCall =
                                FunctionCall.builder()
                                        .id(tub.getId())
                                        .name(tub.getName())
                                        .args(args)
                                        .build();
                        partBuilder = Part.builder().functionCall(functionCall);
                    }

                    // 检查元数据中的思考签名
                    Map<String, Object> metadata = tub.getMetadata();
                    if (metadata != null
                            && metadata.containsKey(ToolUseBlock.METADATA_THOUGHT_SIGNATURE)) {
                        applyThoughtSignature(
                                partBuilder,
                                metadata.get(ToolUseBlock.METADATA_THOUGHT_SIGNATURE),
                                tub.getName());
                    }

                    parts.add(partBuilder.build());

                } else if (block instanceof ToolResultBlock trb) {
                    // 服务端结果保留在 model Content 内；本地结果排队，
                    // 在当前消息之后作为独立的 user Content 输出。
                    if (isCodeExecutionResult(trb)) {
                        buildCodeExecutionResultPart(trb)
                                .ifPresent(partBuilder -> parts.add(partBuilder.build()));
                    } else if (trb.isServerTool()) {
                        restoreServerToolResponse(trb)
                                .ifPresent(
                                        toolResponse -> {
                                            Part.Builder partBuilder =
                                                    Part.builder().toolResponse(toolResponse);
                                            Map<String, Object> metadata = trb.getMetadata();
                                            if (metadata != null
                                                    && metadata.containsKey(
                                                            ToolUseBlock
                                                                    .METADATA_THOUGHT_SIGNATURE)) {
                                                applyThoughtSignature(
                                                        partBuilder,
                                                        metadata.get(
                                                                ToolUseBlock
                                                                        .METADATA_THOUGHT_SIGNATURE),
                                                        trb.getName());
                                            }
                                            parts.add(partBuilder.build());
                                        });
                    } else {
                        String textOutput = convertToolResultToString(trb.getOutput());
                        // 为本地函数调用创建 FunctionResponse，将输出
                        // 放在 "output" 键下
                        Map<String, Object> responseMap = new HashMap<>();
                        responseMap.put("output", textOutput);

                        FunctionResponse functionResponse =
                                FunctionResponse.builder()
                                        .id(trb.getId())
                                        .name(trb.getName())
                                        .response(responseMap)
                                        .build();

                        functionResponseParts.add(
                                Part.builder().functionResponse(functionResponse).build());
                    }

                } else if (block instanceof ImageBlock ib) {
                    parts.add(mediaConverter.convertToInlineDataPart(ib));

                } else if (block instanceof AudioBlock ab) {
                    parts.add(mediaConverter.convertToInlineDataPart(ab));

                } else if (block instanceof VideoBlock vb) {
                    parts.add(mediaConverter.convertToInlineDataPart(vb));

                } else if (block instanceof DataBlock db) {
                    parts.add(mediaConverter.convertToInlineDataPart(db));

                } else if (block instanceof HintBlock hb) {
                    parts.add(Part.builder().text(hb.getHint()).build());

                } else if (block instanceof ThinkingBlock) {
                    log.debug("Skipping ThinkingBlock when formatting message for Gemini API");
                    continue;

                } else {
                    log.warn(
                            "Unsupported block type: {} in the message, skipped.",
                            block.getClass().getSimpleName());
                }
            }

            // 存在 Part 时添加消息
            if (!parts.isEmpty()) {
                String role = convertRole(msg.getRole());
                Content content = Content.builder().role(role).parts(parts).build();
                result.add(content);
            }
            // 【学习提示】本地函数结果与服务端工具结果走不同协议表示；前者要合并为
            // 用户轮次中的响应 Part，后者留在模型 Content，不能只按“都是工具结果”平铺。
            if (!functionResponseParts.isEmpty()) {
                appendFunctionResponses(result, functionResponseParts);
            }
        }

        return result;
    }

    private boolean isCodeExecutionToolUse(ToolUseBlock toolUse) {
        return toolUse.getMetadata().containsKey(GeminiResponseParser.METADATA_CODE_EXECUTION);
    }

    private boolean isCodeExecutionResult(ToolResultBlock toolResult) {
        return toolResult.getMetadata().containsKey(GeminiResponseParser.METADATA_CODE_EXECUTION);
    }

    private Part.Builder buildExecutableCodePart(ToolUseBlock toolUse) {
        ExecutableCode.Builder executableCodeBuilder = ExecutableCode.builder().id(toolUse.getId());

        Object code = toolUse.getInput().get("code");
        if (code instanceof String codeText) {
            executableCodeBuilder.code(codeText);
        }

        Object language = toolUse.getInput().get("language");
        if (language instanceof String languageText && !languageText.isBlank()) {
            executableCodeBuilder.language(languageText);
        }

        return Part.builder().executableCode(executableCodeBuilder.build());
    }

    private Optional<Part.Builder> buildCodeExecutionResultPart(ToolResultBlock toolResult) {
        return restoreCodeExecutionResult(toolResult)
                .map(
                        result -> {
                            Part.Builder partBuilder = Part.builder().codeExecutionResult(result);
                            Map<String, Object> metadata = toolResult.getMetadata();
                            if (metadata != null
                                    && metadata.containsKey(
                                            ToolUseBlock.METADATA_THOUGHT_SIGNATURE)) {
                                applyThoughtSignature(
                                        partBuilder,
                                        metadata.get(ToolUseBlock.METADATA_THOUGHT_SIGNATURE),
                                        toolResult.getName());
                            }
                            return partBuilder;
                        });
    }

    private Optional<CodeExecutionResult> restoreCodeExecutionResult(ToolResultBlock toolResult) {
        Object raw =
                toolResult.getMetadata().get(GeminiResponseParser.METADATA_CODE_EXECUTION_RESULT);
        if (raw instanceof String json && !json.isBlank()) {
            try {
                return Optional.of(CodeExecutionResult.fromJson(json));
            } catch (Exception e) {
                log.warn(
                        "Failed to restore Gemini code execution result {}: {}",
                        toolResult.getId(),
                        e.getMessage());
            }
        }
        return Optional.empty();
    }

    private Optional<ToolResponse> restoreServerToolResponse(ToolResultBlock toolResult) {
        Object raw =
                toolResult.getMetadata().get(GeminiResponseParser.METADATA_SERVER_TOOL_RESPONSE);
        if (raw instanceof String json && !json.isBlank()) {
            try {
                return Optional.of(ToolResponse.fromJson(json));
            } catch (Exception e) {
                log.warn(
                        "Failed to restore Gemini server tool response {}: {}",
                        toolResult.getId(),
                        e.getMessage());
            }
        }
        return Optional.empty();
    }

    private void applyThoughtSignature(
            Part.Builder partBuilder, Object signature, String toolName) {
        if (signature instanceof byte[] bytes) {
            partBuilder.thoughtSignature(bytes);
        } else if (signature instanceof String base64 && !base64.isEmpty()) {
            try {
                partBuilder.thoughtSignature(Base64.getDecoder().decode(base64));
            } catch (IllegalArgumentException e) {
                log.warn("Skipping invalid thought signature on tool call '{}'", toolName, e);
            }
        }
    }

    /**
     * 追加本地函数响应，将连续结果消息合并为同一用户轮次。
     *
     * <p>Gemini 要求并行函数调用的所有响应，作为同一个 user Content 的各个 Part 返回。
     */
    private void appendFunctionResponses(List<Content> result, List<Part> responseParts) {
        if (!result.isEmpty()) {
            int lastIndex = result.size() - 1;
            Content lastContent = result.get(lastIndex);
            List<Part> lastParts = lastContent.parts().orElse(List.of());
            boolean lastIsFunctionResponseContent =
                    "user".equals(lastContent.role().orElse(null))
                            && !lastParts.isEmpty()
                            && lastParts.stream()
                                    .allMatch(part -> part.functionResponse().isPresent());
            if (lastIsFunctionResponseContent) {
                List<Part> mergedParts = new ArrayList<>(lastParts);
                mergedParts.addAll(responseParts);
                result.set(lastIndex, Content.builder().role("user").parts(mergedParts).build());
                return;
            }
        }
        result.add(Content.builder().role("user").parts(responseParts).build());
    }

    /**
     * 将 MsgRole 转为 Gemini API 角色字符串。
     *
     * @param role AgentScope 消息角色
     * @return Gemini API 角色（"user" 或 "model"）
     */
    private String convertRole(MsgRole role) {
        // Gemini API 中，assistant 对应 "model"，其他角色都对应 "user"
        return role == MsgRole.ASSISTANT ? "model" : "user";
    }

    /**
     * 将工具结果输出转为字符串表示。单项直接返回，多项每行使用 "- " 前缀。
     *
     * @param output 工具结果中的内容块列表
     * @return 输出的字符串表示
     */
    private String convertToolResultToString(List<ContentBlock> output) {
        if (output == null || output.isEmpty()) {
            return "";
        }

        List<String> textualOutput = new ArrayList<>();

        for (ContentBlock block : output) {
            if (block instanceof TextBlock tb) {
                textualOutput.add(tb.getText());

            } else if (block instanceof ImageBlock ib) {
                String reference = convertMediaBlockToTextReference(ib, "image");
                textualOutput.add(reference);

            } else if (block instanceof AudioBlock ab) {
                String reference = convertMediaBlockToTextReference(ab, "audio");
                textualOutput.add(reference);

            } else if (block instanceof VideoBlock vb) {
                String reference = convertMediaBlockToTextReference(vb, "video");
                textualOutput.add(reference);

            } else if (block instanceof DataBlock db) {
                String reference = convertMediaBlockToTextReference(db, "data");
                textualOutput.add(reference);
            }
            // 忽略其他块类型
        }

        // 单项：直接返回
        // 多项：每项加 "- " 前缀并以换行连接
        if (textualOutput.size() == 1) {
            return textualOutput.get(0);
        } else {
            return textualOutput.stream()
                    .map(s -> "- " + s)
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");
        }
    }

    /**
     * 将媒体块转为工具结果的文本引用。
     * 返回格式化字符串："The returned {mediaType} can be found at: {path}"。
     *
     * <p>URL 来源直接返回 URL；Base64 来源先保存到临时文件，再返回文件路径。
     *
     * @param block 媒体块（ImageBlock、AudioBlock 或 VideoBlock）
     * @param mediaType 媒体类型字符串（"image"、"audio" 或 "video"）
     * @return 媒体的文本引用
     */
    private String convertMediaBlockToTextReference(ContentBlock block, String mediaType) {
        Source source = extractSourceFromBlock(block);

        if (source instanceof URLSource urlSource) {
            // URL 类型：直接返回 URL
            return String.format(
                    "The returned %s can be found at: %s", mediaType, urlSource.getUrl());

        } else if (source instanceof Base64Source base64Source) {
            // Base64 类型：保存到临时文件并返回路径
            try {
                String filePath =
                        saveBase64DataToTempFile(
                                base64Source.getMediaType(), base64Source.getData());
                return String.format("The returned %s can be found at: %s", mediaType, filePath);
            } catch (IOException e) {
                log.error("Failed to save base64 data to temp file for {}", mediaType, e);
                return String.format("[%s - failed to save file: %s]", mediaType, e.getMessage());
            }
        }

        log.warn("Unsupported source type for {}: {}", mediaType, source.getClass().getName());
        return String.format("[%s - unsupported source type]", mediaType);
    }

    /**
     * 从媒体块提取来源。
     *
     * @param block 媒体块
     * @return 来源对象
     */
    private Source extractSourceFromBlock(ContentBlock block) {
        if (block instanceof ImageBlock ib) {
            return ib.getSource();
        } else if (block instanceof AudioBlock ab) {
            return ab.getSource();
        } else if (block instanceof VideoBlock vb) {
            return vb.getSource();
        } else if (block instanceof DataBlock db) {
            return db.getSource();
        }
        throw new IllegalArgumentException("Unsupported block type: " + block.getClass());
    }

    /**
     * 将 Base64 数据保存到临时文件。
     *
     * <p>文件扩展名从 MIME 类型提取（例如 "audio/wav" → ".wav"）。
     * 文件以前缀 "agentscope_" 创建，不会自动删除。
     *
     * @param mediaType MIME 类型（例如 "image/png"、"audio/wav"）
     * @param base64Data Base64 编码数据（不含前缀）
     * @return 临时文件绝对路径
     * @throws IOException 创建或写入文件失败时抛出
     */
    private String saveBase64DataToTempFile(String mediaType, String base64Data)
            throws IOException {
        // 从 MIME 类型提取扩展名（例如 "audio/wav" → ".wav"）
        String extension = "." + (mediaType.contains("/") ? mediaType.split("/")[1] : mediaType);

        // 创建带扩展名的临时文件
        Path tempFile = Files.createTempFile("agentscope_", extension);

        // 解码 Base64 数据
        byte[] decodedData = Base64.getDecoder().decode(base64Data);

        // 写入文件
        Files.write(tempFile, decodedData);

        log.debug("Saved base64 data to temp file: {}", tempFile);

        // 返回绝对路径
        return tempFile.toAbsolutePath().toString();
    }
}
