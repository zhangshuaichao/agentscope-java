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

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.MessageParam.Role;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 AgentScope Msg 转换为 Anthropic SDK MessageParam。
 *
 * <p>处理 system、user、assistant 和 tool 等角色的转换，支持文本、图像等多模态内容与工具调用。
 *
 * <p>重要：此适配器将首条系统消息交由独立 system 参数处理，非首条系统消息转换为用户消息。
 */
public class AnthropicMessageConverter {

    private static final Logger log = LoggerFactory.getLogger(AnthropicMessageConverter.class);
    private static final AnthropicServerToolHelper SERVER_TOOL_HELPER =
            AnthropicServerToolHelper.instance();

    private final AnthropicMediaConverter mediaConverter;
    private final Function<List<ContentBlock>, String> toolResultConverter;

    /**
     * 使用所需依赖函数创建 AnthropicMessageConverter。
     *
     * @param toolResultConverter 将工具结果块转换为字符串的函数
     */
    public AnthropicMessageConverter(Function<List<ContentBlock>, String> toolResultConverter) {
        this.mediaConverter = new AnthropicMediaConverter();
        this.toolResultConverter = toolResultConverter;
    }

    /**
     * 将 Msg 列表转换为 Anthropic MessageParam 列表，
     * 并处理工具结果必须放在独立用户消息中的特殊要求。
     *
     * @param messages 待转换的消息
     * @return 供 Anthropic API 使用的 MessageParam 列表
     */
    public List<MessageParam> convert(List<Msg> messages) {
        List<MessageParam> result = new ArrayList<>();

        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            boolean isFirstMessage = (i == 0);

            if (msg.getRole() == MsgRole.ASSISTANT && clientToolUses(msg).size() > 1) {
                SplitToolResultSequence splitResults = collectSplitToolResults(messages, i + 1);
                if (shouldSplitParallelToolCalls(msg, splitResults)) {
                    result.addAll(convertParallelToolCalls(msg, splitResults));
                    i += splitResults.consumedMessages();
                    continue;
                }
            }

            // 特殊处理客户端工具结果：为它们创建独立用户消息。
            // 服务端工具结果，例如 web_search_tool_result，
            // 则保持内联在助手消息中。
            if (msg.hasContentBlocks(ToolResultBlock.class)) {
                // 先添加非工具结果内容（若存在）。
                List<ContentBlock> nonToolBlocks = new ArrayList<>();
                List<ToolResultBlock> toolResults = new ArrayList<>();

                for (ContentBlock block : msg.getContent()) {
                    if (block instanceof ToolResultBlock tr && !tr.isServerTool()) {
                        toolResults.add(tr);
                    } else {
                        nonToolBlocks.add(block);
                    }
                }

                // 存在常规内容时添加。
                if (!nonToolBlocks.isEmpty()) {
                    MessageParam regularMsg = convertMessageContent(msg, nonToolBlocks, i == 0);
                    if (regularMsg != null) {
                        result.add(regularMsg);
                    }
                }

                // 将工具结果添加为独立用户消息。
                for (ToolResultBlock toolResult : toolResults) {
                    result.add(convertToolResult(toolResult));
                }
            } else {
                MessageParam param = convertMessageContent(msg, msg.getContent(), isFirstMessage);
                if (param != null) {
                    result.add(param);
                }
            }
        }

        return result;
    }

    /**
     * 判断当前助手消息是否应展开为 Anthropic 要求的工具调用/工具结果交替序列。
     *
     * <p>仅当以下条件全部成立时拆分：
     * <ul><li>当前角色为 {@link MsgRole#ASSISTANT}。</li><li>发现至少一条紧随其后的纯工具结果消息。</li>
     * <li>当前消息包含至少两个 {@link ToolUseBlock}。</li><li>每个工具调用 ID 存在且非空白。</li>
     * <li>收集的工具结果数等于调用数，且每个调用 ID 都有对应结果。</li></ul>
     */
    private boolean shouldSplitParallelToolCalls(Msg msg, SplitToolResultSequence splitResults) {
        if (msg.getRole() != MsgRole.ASSISTANT || splitResults.consumedMessages() == 0) {
            return false;
        }

        List<ToolUseBlock> toolUses = clientToolUses(msg);
        if (toolUses.size() <= 1) {
            return false;
        }

        List<String> toolUseIds = toolUses.stream().map(ToolUseBlock::getId).toList();
        if (toolUseIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            return false;
        }

        return splitResults.resultsById().size() == toolUseIds.size()
                && toolUseIds.stream().allMatch(id -> splitResults.resultsById().containsKey(id));
    }

    /**
     * 收集紧随助手消息的连续纯工具结果消息。
     *
     * <p>从 {@code startIndex} 开始扫描，通常是待检查助手消息的下一条。
     * 仅跨越完全由 {@link ToolResultBlock} 构成的消息；遇到没有工具结果或含其他内容的消息就停止，
     * 因为该消息必须保留在原位置，不能吸收到拆分序列中。
     */
    private SplitToolResultSequence collectSplitToolResults(List<Msg> messages, int startIndex) {
        Map<String, ToolResultBlock> byId = new LinkedHashMap<>();
        int consumed = 0;

        for (int i = startIndex; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            List<ToolResultBlock> toolResults = msg.getContentBlocks(ToolResultBlock.class);
            if (toolResults.isEmpty() || hasNonToolResultContent(msg)) {
                break;
            }

            consumed++;
            for (ToolResultBlock toolResult : toolResults) {
                if (toolResult.getId() != null) {
                    byId.putIfAbsent(toolResult.getId(), toolResult);
                }
            }
        }

        return new SplitToolResultSequence(byId, consumed);
    }

    private boolean hasNonToolResultContent(Msg msg) {
        return msg.getContent().stream().anyMatch(block -> !(block instanceof ToolResultBlock));
    }

    /**
     * 将一条含并行工具调用的助手消息展开为交替的 Anthropic 消息。
     *
     * <p>原消息中的每个 {@link ToolUseBlock} 包装为独立助手消息，紧随与之匹配的用户工具结果消息。
     * 工具调用之前的解释文本等前导助手内容只附在第一条拆分助手消息上，避免在新序列中重复。
     *
     * <p>【学习提示】这是协议消息布局转换，不重新调度或重放工具；拆分前必须以 ID 校验完整配对。
     */
    private List<MessageParam> convertParallelToolCalls(
            Msg assistantMsg, SplitToolResultSequence splitResults) {
        List<MessageParam> converted = new ArrayList<>();
        // 服务端工具块（调用与结果）留在前导内容中，只有客户端工具
        // 调用参与调用/结果交替序列。
        List<ContentBlock> leadingBlocks =
                assistantMsg.getContent().stream()
                        .filter(block -> !(block instanceof ToolUseBlock tub) || tub.isServerTool())
                        .toList();
        List<ToolUseBlock> toolUses = clientToolUses(assistantMsg);

        for (int i = 0; i < toolUses.size(); i++) {
            ToolUseBlock toolUse = toolUses.get(i);
            List<ContentBlock> assistantBlocks = new ArrayList<>();
            if (i == 0) {
                assistantBlocks.addAll(leadingBlocks);
            }
            assistantBlocks.add(toolUse);

            MessageParam assistantParam =
                    convertMessageContent(assistantMsg, assistantBlocks, false);
            if (assistantParam != null) {
                converted.add(assistantParam);
            }

            ToolResultBlock toolResult = splitResults.resultsById().get(toolUse.getId());
            converted.add(convertToolResult(toolResult));
        }

        return converted;
    }

    private record SplitToolResultSequence(
            Map<String, ToolResultBlock> resultsById, int consumedMessages) {}

    /** 由本地工具集执行的工具调用，不含 Anthropic web_search 等由提供商服务端执行的工具。 */
    private List<ToolUseBlock> clientToolUses(Msg msg) {
        return msg.getContentBlocks(ToolUseBlock.class).stream()
                .filter(toolUse -> !toolUse.isServerTool())
                .toList();
    }

    /** 将消息内容转换为 MessageParam。 */
    private MessageParam convertMessageContent(
            Msg msg, List<ContentBlock> blocks, boolean isFirstMessage) {
        Role role = convertRole(msg.getRole(), isFirstMessage);
        List<ContentBlockParam> contentBlocks = new ArrayList<>();

        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock tb) {
                contentBlocks.add(
                        ContentBlockParam.ofText(
                                TextBlockParam.builder().text(tb.getText()).build()));
            } else if (block instanceof HintBlock hb) {
                contentBlocks.add(
                        ContentBlockParam.ofText(
                                TextBlockParam.builder().text(hb.getHint()).build()));
            } else if (block instanceof ThinkingBlock thinkingBlock) {
                // Anthropic 原生支持思考内容块。
                contentBlocks.add(
                        ContentBlockParam.ofText(
                                TextBlockParam.builder()
                                        .text(thinkingBlock.getThinking())
                                        .build()));
            } else if (block instanceof ImageBlock ib) {
                try {
                    ImageBlockParam imageParam = mediaConverter.convertImageBlock(ib);
                    contentBlocks.add(ContentBlockParam.ofImage(imageParam));
                } catch (Exception e) {
                    log.warn("Failed to process ImageBlock: {}", e.getMessage());
                    contentBlocks.add(
                            ContentBlockParam.ofText(
                                    TextBlockParam.builder()
                                            .text(
                                                    "[Image - processing failed: "
                                                            + e.getMessage()
                                                            + "]")
                                            .build()));
                }
            } else if (block instanceof DataBlock db) {
                try {
                    ImageBlockParam imageParam = mediaConverter.convertDataBlock(db);
                    contentBlocks.add(ContentBlockParam.ofImage(imageParam));
                } catch (Exception e) {
                    log.warn("Failed to process DataBlock: {}", e.getMessage());
                    contentBlocks.add(
                            ContentBlockParam.ofText(
                                    TextBlockParam.builder()
                                            .text(
                                                    "[Media - processing failed: "
                                                            + e.getMessage()
                                                            + "]")
                                            .build()));
                }
            } else if (block instanceof ToolUseBlock tub) {
                if (tub.isServerTool()) {
                    // 服务端工具调用以 server_tool_use 块回传。
                    contentBlocks.add(
                            ContentBlockParam.ofServerToolUse(SERVER_TOOL_HELPER.encodeUse(tub)));
                } else {
                    contentBlocks.add(
                            ContentBlockParam.ofToolUse(
                                    ToolUseBlockParam.builder()
                                            .id(tub.getId())
                                            .name(tub.getName())
                                            .input(
                                                    JsonValue.from(
                                                            tub.getInput() != null
                                                                    ? tub.getInput()
                                                                    : Map.of()))
                                            .build()));
                }
            } else if (block instanceof ToolResultBlock trb && trb.isServerTool()) {
                // 服务端工具结果保留在助手消息内，原样回传。
                ContentBlockParam param = SERVER_TOOL_HELPER.encodeResult(trb);
                if (param != null) {
                    contentBlocks.add(param);
                }
            }
            // 客户端 ToolResultBlock 在 convert() 方法中单独处理。
        }

        if (contentBlocks.isEmpty()) {
            return null;
        }

        return MessageParam.builder()
                .role(role)
                .content(MessageParam.Content.ofBlockParams(contentBlocks))
                .build();
    }

    /** 将工具结果转换为独立用户消息。 */
    private MessageParam convertToolResult(ToolResultBlock toolResult) {
        // 将输出转换为内容块。
        List<ToolResultBlockParam.Content.Block> blocks = new ArrayList<>();

        Object output = toolResult.getOutput();
        if (output == null) {
            blocks.add(
                    ToolResultBlockParam.Content.Block.ofText(
                            TextBlockParam.builder().text((String) null).build()));
        } else if (output instanceof List) {
            // 多块输出。
            List<?> outputList = (List<?>) output;
            for (Object item : outputList) {
                if (item instanceof ContentBlock cb) {
                    if (cb instanceof TextBlock tb) {
                        blocks.add(
                                ToolResultBlockParam.Content.Block.ofText(
                                        TextBlockParam.builder().text(tb.getText()).build()));
                    } else if (cb instanceof ImageBlock ib) {
                        try {
                            ImageBlockParam imageParam = mediaConverter.convertImageBlock(ib);
                            blocks.add(ToolResultBlockParam.Content.Block.ofImage(imageParam));
                        } catch (Exception e) {
                            log.warn("Failed to process ImageBlock in tool result: {}", e);
                        }
                    } else if (cb instanceof DataBlock db) {
                        try {
                            ImageBlockParam imageParam = mediaConverter.convertDataBlock(db);
                            blocks.add(ToolResultBlockParam.Content.Block.ofImage(imageParam));
                        } catch (Exception e) {
                            log.warn("Failed to process DataBlock in tool result: {}", e);
                        }
                    }
                }
            }
        } else {
            // 字符串输出。
            String outputStr =
                    output instanceof String
                            ? (String) output
                            : (output instanceof ContentBlock
                                    ? toolResultConverter.apply(List.of((ContentBlock) output))
                                    : output.toString());
            blocks.add(
                    ToolResultBlockParam.Content.Block.ofText(
                            TextBlockParam.builder().text(outputStr).build()));
        }

        // 创建工具结果块。
        ToolResultBlockParam toolResultParam =
                ToolResultBlockParam.builder()
                        .toolUseId(toolResult.getId())
                        .content(ToolResultBlockParam.Content.ofBlocks(blocks))
                        .build();

        // 包装为用户消息。
        return MessageParam.builder()
                .role(Role.USER)
                .content(
                        MessageParam.Content.ofBlockParams(
                                List.of(ContentBlockParam.ofToolResult(toolResultParam))))
                .build();
    }

    /**
     * 将 AgentScope MsgRole 转换为 Anthropic Role。
     * 首条系统消息由 system 参数处理，其他系统消息转换为 user。
     */
    private Role convertRole(MsgRole msgRole, boolean isFirstMessage) {
        return switch (msgRole) {
            case SYSTEM -> isFirstMessage ? Role.USER : Role.USER; // Anthropic 对普通消息列表中的
            // 系统消息使用 user 角色。
            case USER -> Role.USER;
            case ASSISTANT -> Role.ASSISTANT;
            case TOOL -> Role.USER; // 工具结果始终使用用户消息。
        };
    }

    /** 首条消息为系统消息时，提取其内容。 */
    public String extractSystemMessage(List<Msg> messages) {
        if (messages.isEmpty()) {
            return null;
        }

        Msg first = messages.get(0);
        if (first.getRole() == MsgRole.SYSTEM) {
            StringBuilder sb = new StringBuilder();
            for (ContentBlock block : first.getContent()) {
                if (block instanceof TextBlock tb) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(tb.getText());
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        }

        return null;
    }
}
