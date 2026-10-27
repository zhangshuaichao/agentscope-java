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

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.extensions.model.dashscope.dto.DashScopeContentPart;
import io.agentscope.extensions.model.dashscope.dto.DashScopeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 AgentScope Msg 转换为 DashScope DTO 消息类型。
 *
 * <p>为简单文本和包含图像、视频、音频的多模态消息提供统一转换。
 */
public class DashScopeMessageConverter {

    private static final Logger log = LoggerFactory.getLogger(DashScopeMessageConverter.class);

    private final DashScopeMediaConverter mediaConverter;
    private final DashScopeToolsHelper toolsHelper;
    private final Function<List<ContentBlock>, String> toolResultConverter;

    public DashScopeMessageConverter(Function<List<ContentBlock>, String> toolResultConverter) {
        this.mediaConverter = new DashScopeMediaConverter();
        this.toolsHelper = new DashScopeToolsHelper();
        this.toolResultConverter = toolResultConverter;
    }

    /**
     * 将单个 Msg 转换为 DashScopeMessage。
     *
     * <p>消息转换主入口，根据消息内容与多模态标志自动选择格式。
     *
     * @param msg 待转换消息
     * @param useMultimodalFormat 是否采用多模态内容格式（内容片段列表）
     * @return 转换后的 DashScopeMessage
     */
    public DashScopeMessage convertToMessage(Msg msg, boolean useMultimodalFormat) {
        DashScopeMessage result;
        if (useMultimodalFormat) {
            result = convertToMultimodalContent(msg);
        } else {
            result = convertToSimpleContent(msg);
        }

        // 若消息元数据手动标记了 cache_control，则应用它。
        applyCacheControlFromMetadata(msg, result);

        return result;
    }

    /**
     * 将消息转换为内容片段列表的多模态格式。
     *
     * <p>统一处理 TOOL、ASSISTANT、USER、SYSTEM 角色，以及 TextBlock、ImageBlock、VideoBlock、AudioBlock、ThinkingBlock（跳过）、ToolResultBlock 各类内容块。
     *
     * @param msg 待转换消息
     * @return 多模态格式的 DashScopeMessage
     */
    private DashScopeMessage convertToMultimodalContent(Msg msg) {
        // 特殊处理 TOOL 角色消息。
        if (msg.getRole() == MsgRole.TOOL) {
            return convertToolRoleMessage(msg);
        }

        List<DashScopeContentPart> contents = new ArrayList<>();

        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock tb) {
                contents.add(DashScopeContentPart.text(tb.getText()));
            } else if (block instanceof ImageBlock imageBlock) {
                try {
                    contents.add(mediaConverter.convertImageBlockToContentPart(imageBlock));
                } catch (Exception e) {
                    log.warn("Failed to process ImageBlock: {}", e.getMessage());
                    contents.add(
                            DashScopeContentPart.text(
                                    "[Image - processing failed: " + e.getMessage() + "]"));
                }
            } else if (block instanceof VideoBlock videoBlock) {
                try {
                    contents.add(mediaConverter.convertVideoBlockToContentPart(videoBlock));
                } catch (Exception e) {
                    log.warn("Failed to process VideoBlock: {}", e.getMessage());
                    contents.add(
                            DashScopeContentPart.text(
                                    "[Video - processing failed: " + e.getMessage() + "]"));
                }
            } else if (block instanceof AudioBlock audioBlock) {
                try {
                    contents.add(mediaConverter.convertAudioBlockToContentPart(audioBlock));
                } catch (Exception e) {
                    log.warn("Failed to process AudioBlock: {}", e.getMessage());
                    contents.add(
                            DashScopeContentPart.text(
                                    "[Audio - processing failed: " + e.getMessage() + "]"));
                }
            } else if (block instanceof DataBlock dataBlock) {
                try {
                    contents.add(mediaConverter.convertDataBlockToContentPart(dataBlock));
                } catch (Exception e) {
                    log.warn("Failed to process DataBlock: {}", e.getMessage());
                    contents.add(
                            DashScopeContentPart.text(
                                    "[Media - processing failed: " + e.getMessage() + "]"));
                }
            } else if (block instanceof HintBlock hb) {
                contents.add(DashScopeContentPart.text(hb.getHint()));
            } else if (block instanceof ThinkingBlock) {
                log.debug("Skipping ThinkingBlock when formatting for DashScope");
            } else if (block instanceof ToolResultBlock toolResult) {
                String toolResultText = toolResultConverter.apply(toolResult.getOutput());
                if (!toolResultText.isEmpty()) {
                    contents.add(DashScopeContentPart.text(toolResultText));
                }
            }
        }

        // 确保内容非空（部分 VL API 要求）。
        if (contents.isEmpty()) {
            contents.add(DashScopeContentPart.text(""));
        }

        DashScopeMessage.Builder builder =
                DashScopeMessage.builder()
                        .role(msg.getRole().name().toLowerCase())
                        .content(contents);

        // 处理 ASSISTANT 工具调用。
        if (msg.getRole() == MsgRole.ASSISTANT) {
            List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
            if (!toolBlocks.isEmpty()) {
                builder.toolCalls(toolsHelper.convertToolCalls(toolBlocks));
            }
        }

        return builder.build();
    }

    /**
     * 将 TOOL 角色消息转换为 DashScopeMessage。
     *
     * <p>此角色需要特殊处理 toolCallId 与 name 字段。
     *
     * @param msg TOOL 角色消息
     * @return 工具响应格式的 DashScopeMessage
     */
    private DashScopeMessage convertToolRoleMessage(Msg msg) {
        ToolResultBlock toolResult = msg.getFirstContentBlock(ToolResultBlock.class);
        if (toolResult != null) {
            List<DashScopeContentPart> content =
                    hasMediaContent(toolResult.getOutput())
                            ? convertContentBlocks(toolResult.getOutput())
                            : List.of(
                                    DashScopeContentPart.text(
                                            toolResultConverter.apply(toolResult.getOutput())));

            return DashScopeMessage.builder()
                    .role("tool")
                    .toolCallId(toolResult.getId())
                    .name(toolResult.getName())
                    .content(content)
                    .build();
        }

        // 回退：未找到 ToolResultBlock，使用文本内容。
        return DashScopeMessage.builder()
                .role("tool")
                .content(List.of(DashScopeContentPart.text(extractTextContent(msg))))
                .build();
    }

    /**
     * 将消息转换为简单文本格式。
     *
     * <p>无需多模态内容时使用，适当处理 TOOL 角色与 ASSISTANT 工具调用。
     *
     * @param msg 待转换消息
     * @return 简单文本内容的 DashScopeMessage
     */
    private DashScopeMessage convertToSimpleContent(Msg msg) {
        // 检查是否包含工具结果；若有，则按 TOOL 角色处理。
        ToolResultBlock toolResult = msg.getFirstContentBlock(ToolResultBlock.class);
        if (toolResult != null
                && (msg.getRole() == MsgRole.TOOL || msg.getRole() == MsgRole.SYSTEM)) {
            return DashScopeMessage.builder()
                    .role("tool")
                    .toolCallId(toolResult.getId())
                    .name(toolResult.getName())
                    .content(toolResultConverter.apply(toolResult.getOutput()))
                    .build();
        }

        DashScopeMessage.Builder builder =
                DashScopeMessage.builder().role(msg.getRole().name().toLowerCase());

        if (msg.getRole() == MsgRole.ASSISTANT) {
            List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
            if (!toolBlocks.isEmpty()) {
                // 含工具调用的助手消息。
                builder.toolCalls(toolsHelper.convertToolCalls(toolBlocks));
                String textContent = extractTextContent(msg);
                // Qwen3 等模型在思考模式下可能生成
                // reasoning_content 加 tool_calls，但 content 为 null 的助手消息。
                // DashScope API 要求必须存在 content 字段。
                builder.content(textContent.isEmpty() ? "" : textContent);
            } else {
                builder.content(extractTextContent(msg));
            }
        } else {
            builder.content(extractTextContent(msg));
        }

        return builder.build();
    }

    /**
     * 拼接全部 TextBlock，提取消息文本内容。
     *
     * @param msg 待提取文本的消息
     * @return 拼接后的文本内容
     */
    private String extractTextContent(Msg msg) {
        return msg.getContent().stream()
                .filter(block -> block instanceof TextBlock || block instanceof HintBlock)
                .map(
                        block -> {
                            if (block instanceof TextBlock tb) {
                                return tb.getText();
                            }
                            return ((HintBlock) block).getHint();
                        })
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
    }

    /**
     * 将 Msg 元数据中的 cache_control 应用到转换后的 DashScopeMessage。
     *
     * @param msg 含元数据的源消息
     * @param result 转换后的 DashScope 消息
     */
    void applyCacheControlFromMetadata(Msg msg, DashScopeMessage result) {
        if (msg.getMetadata() == null) {
            return;
        }
        Object cacheFlag = msg.getMetadata().get(MessageMetadataKeys.CACHE_CONTROL);
        if (Boolean.TRUE.equals(cacheFlag)) {
            DashScopeChatFormatter.setCacheControlOnContent(result);
        }
    }

    /**
     * 检查内容块是否包含媒体（图像、音频、视频）。
     *
     * @param blocks 待检查的内容块列表
     * @return 任意块为 ImageBlock、AudioBlock 或 VideoBlock 时为 true
     */
    private boolean hasMediaContent(List<ContentBlock> blocks) {
        if (blocks == null) {
            return false;
        }
        for (ContentBlock block : blocks) {
            if (block instanceof ImageBlock
                    || block instanceof AudioBlock
                    || block instanceof VideoBlock
                    || block instanceof DataBlock) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将内容块转换为多模态消息的 DashScope 内容片段。
     *
     * @param blocks 待转换的内容块列表
     * @return 转换后的 DashScopeContentPart 列表
     */
    private List<DashScopeContentPart> convertContentBlocks(List<ContentBlock> blocks) {
        List<DashScopeContentPart> content = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock tb) {
                content.add(DashScopeContentPart.text(tb.getText()));
            } else if (block instanceof ImageBlock ib) {
                try {
                    content.add(mediaConverter.convertImageBlockToContentPart(ib));
                } catch (Exception e) {
                    log.warn("Failed to process ImageBlock in tool result: {}", e.getMessage());
                    content.add(
                            DashScopeContentPart.text(
                                    "[Image - processing failed: " + e.getMessage() + "]"));
                }
            } else if (block instanceof AudioBlock ab) {
                try {
                    content.add(mediaConverter.convertAudioBlockToContentPart(ab));
                } catch (Exception e) {
                    log.warn("Failed to process AudioBlock in tool result: {}", e.getMessage());
                    content.add(
                            DashScopeContentPart.text(
                                    "[Audio - processing failed: " + e.getMessage() + "]"));
                }
            } else if (block instanceof VideoBlock vb) {
                try {
                    content.add(mediaConverter.convertVideoBlockToContentPart(vb));
                } catch (Exception e) {
                    log.warn("Failed to process VideoBlock in tool result: {}", e.getMessage());
                    content.add(
                            DashScopeContentPart.text(
                                    "[Video - processing failed: " + e.getMessage() + "]"));
                }
            } else if (block instanceof DataBlock db) {
                try {
                    content.add(mediaConverter.convertDataBlockToContentPart(db));
                } catch (Exception e) {
                    log.warn("Failed to process DataBlock in tool result: {}", e.getMessage());
                    content.add(
                            DashScopeContentPart.text(
                                    "[Media - processing failed: " + e.getMessage() + "]"));
                }
            }
        }
        return content;
    }
}
