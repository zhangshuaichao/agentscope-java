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
package io.agentscope.core.agui.converter;

import static io.agentscope.core.agui.AguiInterruptConstants.INTERRUPT_KIND_PERMISSION_CONFIRM;
import static io.agentscope.core.agui.AguiInterruptConstants.METADATA_AGENTSCOPE_INTERRUPT_KIND;
import static io.agentscope.core.agui.AguiInterruptConstants.METADATA_TOOL_CONTENT;
import static io.agentscope.core.agui.AguiInterruptConstants.METADATA_TOOL_INPUT;
import static io.agentscope.core.agui.AguiInterruptConstants.METADATA_TOOL_NAME;
import static io.agentscope.core.agui.AguiInterruptConstants.TOOL_CALL_INTERRUPT_REASON;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiFunctionCall;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.AguiResume;
import io.agentscope.core.agui.model.AguiToolCall;
import io.agentscope.core.agui.model.AudioInputContent;
import io.agentscope.core.agui.model.DocumentInputContent;
import io.agentscope.core.agui.model.ImageInputContent;
import io.agentscope.core.agui.model.InputContent;
import io.agentscope.core.agui.model.InputContentDataSource;
import io.agentscope.core.agui.model.InputContentSource;
import io.agentscope.core.agui.model.InputContentUrlSource;
import io.agentscope.core.agui.model.MessageContent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.model.TextInputContent;
import io.agentscope.core.agui.model.VideoInputContent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AG-UI 消息与 AgentScope 消息之间的转换器。
 *
 * <p>负责 AG-UI 协议消息格式与 AgentScope 内部消息格式之间的双向转换。
 */
public class AguiMessageConverter {

    /**
     * AG-UI 恢复载荷键：用户批准了工具调用。
     */
    private static final String RESUME_PAYLOAD_APPROVED = "approved";

    /**
     * AG-UI 恢复载荷键：完整替换的工具参数。
     */
    private static final String RESUME_PAYLOAD_EDITED_ARGS = "editedArgs";

    /**
     * AG-UI 恢复载荷键：拒绝工具调用时提供的可选原因。
     */
    private static final String RESUME_PAYLOAD_REASON = "reason";

    /**
     * 创建 AguiMessageConverter。
     */
    public AguiMessageConverter() {}

    /**
     * 将 AgentScope 消息转换为 AG-UI 消息。
     *
     * @param msg 要转换的 AgentScope 消息
     * @return 转换后的 AG-UI 消息
     * @deprecated 改用 {@link #toAguiMessages(Msg)}。返回列表的方法保留混合推理内容，并展开多个工具结果而不抛异常。
     */
    @Deprecated
    public AguiMessage toAguiMessage(Msg msg) {
        List<AguiMessage> messages = toAguiMessages(msg);
        if (messages.size() != 1) {
            throw new IllegalArgumentException(
                    "A message that converts to multiple AG-UI messages must be converted with"
                            + " toAguiMessages(Msg)");
        }
        return messages.get(0);
    }

    /**
     * 将 AgentScope 消息转换为一个或多个 AG-UI 消息。
     *
     * <p>混合 {@link ThinkingBlock} 与其他内容的消息被拆为推理消息和其余内容。含多个工具结果的 TOOL 消息按结果逐个展开为 AG-UI 工具消息，保留每个结果的 {@code toolCallId}、内容与错误状态。
     *
     * <p>每个生成消息获得格式为 {@code agui::sourceId-UUID} 的唯一 ID。已带此前缀的 ID 会复用，重复转换不会再次追加标记。
     *
     * @param msg 要转换的 AgentScope 消息
     * @return 转换后的 AG-UI 消息，永不为空
     */
    public List<AguiMessage> toAguiMessages(Msg msg) {
        if (msg.getRole() == MsgRole.TOOL) {
            List<ToolResultBlock> toolResults = msg.getContentBlocks(ToolResultBlock.class);
            if (toolResults.size() > 1) {
                List<AguiMessage> messages = new ArrayList<>();
                for (ToolResultBlock toolResult : toolResults) {
                    messages.add(toolResultMessage(msg, toolResult));
                }
                return List.copyOf(messages);
            }
        }
        return splitReasoningMessage(msg, toSingleAguiMessage(msg));
    }

    /**
     * 转换最多携带一个工具结果的 AgentScope 消息。
     *
     * @param msg 要转换的 AgentScope 消息
     * @return 转换后的 AG-UI 消息
     */
    private AguiMessage toSingleAguiMessage(Msg msg) {
        String role = convertRole(msg.getRole());
        StringBuilder content = new StringBuilder();
        List<AguiToolCall> toolCalls = new ArrayList<>();
        String toolCallId = null;
        String error = null;
        boolean toolMessage = msg.getRole() == MsgRole.TOOL;
        String reasoningContent = thinkingContent(msg);

        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock tb) {
                appendContent(content, tb.getText());
            } else if (block instanceof ToolUseBlock tub) {
                toolCalls.add(toAguiToolCall(tub));
            } else if (block instanceof ToolResultBlock trb) {
                toolCallId = trb.getId();
                if (toolMessage && trb.getState() == ToolResultState.ERROR) {
                    error = toolResultText(trb);
                } else {
                    for (ContentBlock output : trb.getOutput()) {
                        if (output instanceof TextBlock tb) {
                            appendContent(content, tb.getText());
                        }
                    }
                }
            }
        }

        if (reasoningContent.length() > 0
                && content.length() == 0
                && toolCalls.isEmpty()
                && toolCallId == null) {
            return AguiMessage.reasoningMessage(
                    generatedMessageId(msg), reasoningContent.toString());
        }

        // 协议将工具消息的 `content` 列为必填字段，不接受 null
        String contentText = content.toString();
        MessageContent wireContent =
                contentText.isEmpty() && !toolMessage ? null : new MessageContent.Text(contentText);

        return new AguiMessage(
                generatedMessageId(msg),
                role,
                wireContent,
                toolCalls.isEmpty() ? null : toolCalls,
                toolCallId,
                error);
    }

    /**
     * 将 AgentScope 消息列表转换为 AG-UI 消息。
     *
     * <p>每个生成消息获得格式为 {@code agui::sourceId-UUID} 的唯一 ID。已带此前缀的 ID 会复用，重复转换不会再次追加标记。
     *
     * <p>混合推理与其他内容或包含多个工具结果的消息会展开，因此输出列表可能比输入列表长。
     *
     * @param msgs 要转换的 AgentScope 消息
     * @return 转换后的 AG-UI 消息
     */
    public List<AguiMessage> toAguiMessageList(List<Msg> msgs) {
        return msgs.stream()
                .flatMap(msg -> toAguiMessages(msg).stream())
                .collect(Collectors.toList());
    }

    /**
     * 将 AG-UI 消息转换为 AgentScope 消息。
     *
     * @param aguiMessage 要转换的 AG-UI 消息
     * @return 转换后的 AgentScope 消息
     */
    public Msg toMsg(AguiMessage aguiMessage) {
        MsgRole role = convertRole(aguiMessage.getRole());
        List<ContentBlock> blocks = new ArrayList<>();

        // 处理内容：纯文本或结构化块
        MessageContent content = aguiMessage.getContent();
        if (content instanceof MessageContent.Text text) {
            if (aguiMessage.isReasoningMessage()) {
                addThinkingBlock(blocks, text.value());
            } else {
                addTextBlock(blocks, text.value(), aguiMessage);
            }
        } else if (content instanceof MessageContent.Blocks blocksContent) {
            if (!aguiMessage.isUserMessage()) {
                throw new IllegalArgumentException(
                        "Structured content blocks are only supported for AG-UI user messages");
            }
            for (InputContent input : blocksContent.parts()) {
                blocks.add(toContentBlock(input));
            }
        } else if (aguiMessage.isToolMessage() && aguiMessage.getToolCallId() != null) {
            // 无内容的工具消息（例如前端工具未返回内容）仍需
            // 生成 ToolResultBlock，以便下游完成待处理工具调用。
            addTextBlock(blocks, "", aguiMessage);
        }

        // 若存在工具调用则添加（适用于助手消息）
        if (aguiMessage.hasToolCalls()) {
            for (AguiToolCall tc : aguiMessage.getToolCalls()) {
                blocks.add(toToolUseBlock(tc));
            }
        }

        return Msg.builder().id(aguiMessage.getId()).role(role).content(blocks).build();
    }

    /**
     * 将 AG-UI 消息列表转换为 AgentScope 消息。
     *
     * @param aguiMessages 要转换的 AG-UI 消息
     * @return 转换后的 AgentScope 消息
     */
    public List<Msg> toMsgList(List<AguiMessage> aguiMessages) {
        return aguiMessages.stream().map(this::toMsg).collect(Collectors.toList());
    }

    /**
     * 将 AG-UI 运行输入转换为 AgentScope 消息，包括官方恢复条目。
     *
     * @param input AG-UI 运行输入
     * @return 转换后的 AgentScope 消息
     */
    public List<Msg> toMsgList(RunAgentInput input) {
        return toMsgList(input, Map.of());
    }

    /**
     * 将 AG-UI 运行输入转换为 AgentScope 消息；可用时通过已知原始中断解析恢复条目。
     *
     * <p>部分客户端（尤其是 CopilotKit {@code useInterrupt.resolve()}）为同一 {@code toolCallId} 同时发送 {@code role:"tool"} 消息和匹配的 {@code resume[]} 条目。会跳过与已有 {@link ToolResultBlock} ID 重复的普通工具恢复，避免 {@code ReActAgent} 抛出 {@code Duplicate tool result ID}。权限确认恢复仍产生 USER {@link ConfirmResult} 消息，绝不因此跳过。
     *
     * @param input AG-UI 运行输入
     * @param resumeInterrupts 中断 ID 到原始中断的映射
     * @return 转换后的 AgentScope 消息
     */
    public List<Msg> toMsgList(
            RunAgentInput input, Map<String, AguiEvent.Interrupt> resumeInterrupts) {
        Objects.requireNonNull(input, "input cannot be null");
        List<Msg> msgs = new ArrayList<>(toMsgList(input.getMessages()));
        Set<String> existingToolResultIds = collectToolResultIds(msgs);
        Map<String, AguiEvent.Interrupt> interrupts =
                resumeInterrupts != null ? resumeInterrupts : Map.of();
        for (AguiResume resume : input.getResume()) {
            AguiEvent.Interrupt interrupt = interrupts.get(resume.getInterruptId());
            String toolCallId = resolveToolCallId(resume.getInterruptId(), interrupt);
            if (toolCallId == null || toolCallId.isBlank()) {
                continue;
            }
            if (isPermissionConfirmInterrupt(interrupt)) {
                msgs.add(toConfirmResultMsg(resume, toolCallId, interrupt));
            } else if (!existingToolResultIds.contains(toolCallId)) {
                msgs.add(toToolResultMsg(resume, toolCallId));
                existingToolResultIds.add(toolCallId);
            }
        }
        return List.copyOf(msgs);
    }

    /**
     * 将 AG-UI 角色字符串转换为 AgentScope MsgRole。
     *
     * @param role AG-UI 角色字符串
     * @return 对应 MsgRole
     */
    private MsgRole convertRole(String role) {
        return switch (role.toLowerCase()) {
            case "user" -> MsgRole.USER;
            case "assistant" -> MsgRole.ASSISTANT;
            case "system" -> MsgRole.SYSTEM;
            case "tool" -> MsgRole.TOOL;
            case "reasoning" -> MsgRole.ASSISTANT;
            default -> MsgRole.USER;
        };
    }

    /**
     * 将 AgentScope MsgRole 转换为 AG-UI 角色字符串。
     *
     * @param role AgentScope MsgRole
     * @return 对应角色字符串
     */
    private String convertRole(MsgRole role) {
        return switch (role) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
            case TOOL -> "tool";
        };
    }

    /**
     * 为给定文本添加文本内容块；工具消息则包装为 ToolResultBlock。
     *
     * @param blocks 目标块列表
     * @param text 文本内容
     * @param aguiMessage 源消息（提供角色/工具调用 ID 上下文）
     */
    private void addTextBlock(List<ContentBlock> blocks, String text, AguiMessage aguiMessage) {
        if (aguiMessage.isToolMessage() && aguiMessage.getToolCallId() != null) {
            String error = aguiMessage.getError();
            if (error != null && !error.isBlank()) {
                blocks.add(
                        ToolResultBlock.builder()
                                .id(aguiMessage.getToolCallId())
                                .output(
                                        TextBlock.builder()
                                                .text(
                                                        text == null || text.isEmpty()
                                                                ? error
                                                                : error + "\n" + text)
                                                .build())
                                .state(ToolResultState.ERROR)
                                .build());
                return;
            }
            // 工具结果必须始终携带 ToolResultBlock，即使前端
            // 返回空内容。
            String resultText = text != null ? text : "";
            blocks.add(
                    ToolResultBlock.builder()
                            .id(aguiMessage.getToolCallId())
                            .output(TextBlock.builder().text(resultText).build())
                            .state(ToolResultState.SUCCESS)
                            .build());
            return;
        }
        if (text == null || text.isEmpty()) {
            return;
        }
        blocks.add(TextBlock.builder().text(text).build());
    }

    private void addThinkingBlock(List<ContentBlock> blocks, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        blocks.add(ThinkingBlock.builder().thinking(text).build());
    }

    private static void appendContent(StringBuilder target, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (target.length() > 0) {
            target.append("\n");
        }
        target.append(value);
    }

    /**
     * 将工具结果的文本块连接为单个字符串。
     */
    private static String toolResultText(ToolResultBlock trb) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock output : trb.getOutput()) {
            if (output instanceof TextBlock tb) {
                appendContent(text, tb.getText());
            }
        }
        return text.toString();
    }

    /**
     * 根据单个 AgentScope 工具结果构建一条 AG-UI 工具消息。
     */
    private AguiMessage toolResultMessage(Msg msg, ToolResultBlock result) {
        String id = generatedMessageId(msg);
        String text = toolResultText(result);
        boolean error = result.getState() == ToolResultState.ERROR;
        MessageContent content =
                error ? new MessageContent.Text("") : new MessageContent.Text(text);
        return new AguiMessage(
                id, convertRole(msg.getRole()), content, null, result.getId(), error ? text : null);
    }

    private List<AguiMessage> splitReasoningMessage(Msg msg, AguiMessage standardMessage) {
        String reasoningContent = thinkingContent(msg);
        if (reasoningContent.isEmpty() || standardMessage.isReasoningMessage()) {
            return List.of(standardMessage);
        }
        return List.of(
                AguiMessage.reasoningMessage(generatedMessageId(msg), reasoningContent),
                standardMessage);
    }

    private static String generatedMessageId(Msg msg) {
        String id = msg.getId();
        return id.startsWith("agui::") ? id : "agui::" + id + "-" + UUID.randomUUID();
    }

    private static String thinkingContent(Msg msg) {
        StringBuilder reasoningContent = new StringBuilder();
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof ThinkingBlock tb) {
                appendContent(reasoningContent, tb.getThinking());
            }
        }
        return reasoningContent.toString();
    }

    /**
     * 将 AG-UI {@link InputContent} 转换为 AgentScope {@link ContentBlock}。
     *
     * <p>对密封的 {@link InputContent} 层次结构使用 instanceof 模式匹配。增加新 InputContent 子类型时，末尾的 {@code throw} 提供运行时兜底。Java 21+ 可升级为穷尽式 switch 模式匹配，以在编译期强制检查。
     *
     * @param input AG-UI 输入内容片段
     * @return 转换后的内容块
     */
    private ContentBlock toContentBlock(InputContent input) {
        if (input instanceof TextInputContent text) {
            return TextBlock.builder().text(text.text()).build();
        }
        if (input instanceof ImageInputContent image) {
            return ImageBlock.builder().source(toSource(image.source())).build();
        }
        if (input instanceof AudioInputContent audio) {
            return AudioBlock.builder().source(toSource(audio.source())).build();
        }
        if (input instanceof VideoInputContent video) {
            return VideoBlock.builder().source(toSource(video.source())).build();
        }
        if (input instanceof DocumentInputContent) {
            throw new IllegalStateException(
                    "Unsupported AG-UI input content type 'document': document input is not"
                            + " supported yet");
        }
        throw new IllegalStateException(
                "Unhandled InputContent type: "
                        + (input == null ? "null" : input.getClass().getSimpleName()));
    }

    /**
     * 将 AG-UI 协议 {@link InputContentSource} 转换为 AgentScope {@link Source}。
     *
     * <p>映射：
     * <ul>
     * <li>{@link InputContentUrlSource} (type:"url", value) &rarr; {@link URLSource} (url)</li>
     * <li>{@link InputContentDataSource} (type:"data", value) &rarr; {@link Base64Source} (data)</li>
     * </ul>
     *
     * @param inputSource AG-UI 协议来源
     * @return AgentScope 内部来源
     */
    private Source toSource(InputContentSource inputSource) {
        if (inputSource instanceof InputContentUrlSource url) {
            return new URLSource(url.value(), url.mimeType());
        }
        if (inputSource instanceof InputContentDataSource data) {
            return new Base64Source(data.mimeType(), data.value());
        }
        throw new IllegalStateException("Unhandled InputContentSource type: " + inputSource);
    }

    /**
     * 将 AG-UI 工具调用转换为 AgentScope ToolUseBlock。
     *
     * @param tc AG-UI 工具调用
     * @return 转换后的 ToolUseBlock
     */
    private ToolUseBlock toToolUseBlock(AguiToolCall tc) {
        Map<String, Object> input = parseJsonArguments(tc.getFunction().getArguments());
        return ToolUseBlock.builder()
                .id(tc.getId())
                .name(tc.getFunction().getName())
                .input(input)
                .build();
    }

    /**
     * 将 AgentScope ToolUseBlock 转换为 AG-UI 工具调用。
     *
     * @param tub AgentScope ToolUseBlock
     * @return 转换后的 AG-UI 工具调用
     */
    private AguiToolCall toAguiToolCall(ToolUseBlock tub) {
        String arguments = serializeArguments(tub.getInput());
        AguiFunctionCall function = new AguiFunctionCall(tub.getName(), arguments);
        return new AguiToolCall(tub.getId(), function);
    }

    /**
     * 将 JSON 参数字符串解析为 Map。
     *
     * @param arguments JSON 参数字符串
     * @return 解析后的 Map
     */
    private Map<String, Object> parseJsonArguments(String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        try {
            return JsonUtils.getJsonCodec()
                    .fromJson(arguments, new TypeReference<Map<String, Object>>() {});
        } catch (JsonException e) {
            return Map.of();
        }
    }

    private static Set<String> collectToolResultIds(List<Msg> msgs) {
        Set<String> ids = new HashSet<>();
        for (Msg msg : msgs) {
            if (msg.getContent() == null) {
                continue;
            }
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ToolResultBlock toolResult
                        && toolResult.getId() != null
                        && !toolResult.getId().isBlank()) {
                    ids.add(toolResult.getId());
                }
            }
        }
        return ids;
    }

    private Msg toToolResultMsg(AguiResume resume, String toolCallId) {
        ToolResultState state =
                resume.isCancelled() ? ToolResultState.INTERRUPTED : ToolResultState.SUCCESS;
        ToolResultBlock result =
                ToolResultBlock.builder()
                        .id(toolCallId)
                        .output(TextBlock.builder().text(resumeContent(resume)).build())
                        .metadata(
                                Map.of(
                                        "agui.interruptId", resume.getInterruptId(),
                                        "agui.resumeStatus", resume.getStatus()))
                        .state(state)
                        .build();
        return Msg.builder()
                .id("agui-resume-" + resume.getInterruptId())
                .role(MsgRole.TOOL)
                .content(result)
                .build();
    }

    /**
     * 为权限模式工具确认恢复构建携带 {@link ConfirmResult} 的 {@link Msg}。
     *
     * <p>结果 Msg 的角色为 USER，元数据在 {@link Msg#METADATA_CONFIRM_RESULTS} 下包含单元素 {@code List<ConfirmResult>}。{@link ConfirmResult} 内的 {@code ToolUseBlock} 根据原始中断元数据重建，保证其 {@code content} 字段（工具参数的 JSON 对象字符串）非 null，避免 {@code ReActAgent.applyConfirmResults} 中的 {@code "argument content is null"} 问题。
     *
     * @param resume AG-UI 恢复条目
     * @param toolCallId 已解析的工具调用 ID
     * @param interrupt 包含工具元数据的原始中断
     * @return 携带确认结果的 USER 角色 Msg
     */
    private Msg toConfirmResultMsg(
            AguiResume resume, String toolCallId, AguiEvent.Interrupt interrupt) {
        boolean approved = isApproved(resume);
        Map<String, Object> metadata = interrupt.metadata();

        String toolName = metadata != null ? stringValue(metadata.get(METADATA_TOOL_NAME)) : null;
        Map<String, Object> toolInput = null;
        String toolContent = null;
        if (metadata != null) {
            Object inputObj = metadata.get(METADATA_TOOL_INPUT);
            if (inputObj instanceof Map) {
                toolInput = toStringObjectMap(inputObj, METADATA_TOOL_INPUT);
            }
            toolContent = stringValue(metadata.get(METADATA_TOOL_CONTENT));
        }
        Map<String, Object> editedArgs = editedArgs(resume);
        if (editedArgs != null) {
            toolInput = editedArgs;
            toolContent = serializeArguments(editedArgs);
        }

        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id(toolCallId)
                        .name(toolName)
                        .input(toolInput)
                        .content(toolContent)
                        .build();

        ConfirmResult confirmResult =
                new ConfirmResult(approved, toolUseBlock, null, reason(resume));
        return Msg.builder()
                .id("agui-confirm-" + resume.getInterruptId())
                .role(MsgRole.USER)
                .textContent(approved ? "approved" : "denied")
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, List.of(confirmResult)))
                .build();
    }

    /**
     * 判断用户是否批准工具。
     *
     * <p>状态为 {@code cancelled} 时拒绝工具。否则，只有显式布尔字段 {@code approved: true} 才表示批准。
     */
    private static boolean isApproved(AguiResume resume) {
        if (resume.isCancelled()) {
            return false;
        }
        Object payload = resume.getPayload();
        if (payload instanceof Map<?, ?> map) {
            return Boolean.TRUE.equals(map.get(RESUME_PAYLOAD_APPROVED));
        }
        return false;
    }

    private static Map<String, Object> editedArgs(AguiResume resume) {
        Object payload = resume.getPayload();
        if (!(payload instanceof Map<?, ?> map) || !map.containsKey(RESUME_PAYLOAD_EDITED_ARGS)) {
            return null;
        }
        return toStringObjectMap(map.get(RESUME_PAYLOAD_EDITED_ARGS), RESUME_PAYLOAD_EDITED_ARGS);
    }

    private static Map<String, Object> toStringObjectMap(Object value, String fieldName) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(fieldName + " must be a JSON object when present");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(
                        fieldName + " must contain only string property names");
            }
            result.put(key, entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    private static String reason(AguiResume resume) {
        Object payload = resume.getPayload();
        if (!(payload instanceof Map<?, ?> map)) {
            return null;
        }
        String reason = stringValue(map.get(RESUME_PAYLOAD_REASON));
        return reason == null || reason.isBlank() ? null : reason;
    }

    private static String stringValue(Object value) {
        return value instanceof String s ? s : null;
    }

    private static boolean isPermissionConfirmInterrupt(AguiEvent.Interrupt interrupt) {
        if (interrupt == null || !TOOL_CALL_INTERRUPT_REASON.equals(interrupt.reason())) {
            return false;
        }
        Map<String, Object> metadata = interrupt.metadata();
        return metadata != null
                && INTERRUPT_KIND_PERMISSION_CONFIRM.equals(
                        metadata.get(METADATA_AGENTSCOPE_INTERRUPT_KIND));
    }

    private String resumeContent(AguiResume resume) {
        if (resume.isCancelled()) {
            return "Interrupt cancelled by user";
        }
        Object payload = resume.getPayload();
        if (payload == null) {
            return "";
        }
        if (payload instanceof String text) {
            return text;
        }
        try {
            return JsonUtils.getJsonCodec().toJson(payload);
        } catch (JsonException e) {
            return String.valueOf(payload);
        }
    }

    private String resolveToolCallId(String interruptId, AguiEvent.Interrupt interrupt) {
        if (interrupt != null
                && interrupt.toolCallId() != null
                && !interrupt.toolCallId().isBlank()) {
            return interrupt.toolCallId();
        }
        if (interrupt != null && !TOOL_CALL_INTERRUPT_REASON.equals(interrupt.reason())) {
            return null;
        }
        if (interruptId == null || interruptId.isBlank()) {
            return null;
        }
        int separator = interruptId.lastIndexOf(':');
        if (separator >= 0 && separator < interruptId.length() - 1) {
            return interruptId.substring(separator + 1);
        }
        return interruptId;
    }

    /**
     * 将参数 Map 序列化为 JSON 字符串。
     *
     * @param arguments 参数 Map
     * @return JSON 字符串
     */
    private String serializeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            return JsonUtils.getJsonCodec().toJson(arguments);
        } catch (JsonException e) {
            return "{}";
        }
    }
}
