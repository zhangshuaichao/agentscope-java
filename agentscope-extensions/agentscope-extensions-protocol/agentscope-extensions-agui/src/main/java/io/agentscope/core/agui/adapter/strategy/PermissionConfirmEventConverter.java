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
package io.agentscope.core.agui.adapter.strategy;

import static io.agentscope.core.agui.AguiInterruptConstants.INTERRUPT_KIND_PERMISSION_CONFIRM;
import static io.agentscope.core.agui.AguiInterruptConstants.METADATA_AGENTSCOPE_INTERRUPT_KIND;
import static io.agentscope.core.agui.AguiInterruptConstants.METADATA_REPLY_ID;
import static io.agentscope.core.agui.AguiInterruptConstants.METADATA_TOOL_CONTENT;
import static io.agentscope.core.agui.AguiInterruptConstants.METADATA_TOOL_INPUT;
import static io.agentscope.core.agui.AguiInterruptConstants.METADATA_TOOL_NAME;
import static io.agentscope.core.agui.AguiInterruptConstants.TOOL_CALL_INTERRUPT_REASON;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将权限模式 HITL {@link RequireUserConfirmEvent} 转换为 AG-UI 中断结果。
 *
 * <p>【学习提示】模型提出工具调用不代表获得执行授权。确认恢复先由核心匹配待处理调用，再通过 UserConfirmResultEvent 接纳旧 toolCallId；接纳仅允许关联结果，不会重新发送调用或绕过权限检查。resolved 表示已回答，显式拒绝同样是已回答，不应误判为批准。
 *
 * <p>智能体以 {@code PermissionMode.DEFAULT} 运行时，权限引擎对非只读工具返回 {@code ASK}，{@code ReActAgent} 发出 {@link RequireUserConfirmEvent}（同时发出 {@code RequestStopEvent(PERMISSION_ASKING)}），而不是执行工具。这与 {@code AgentLifecycleEventConverter} 处理的工具挂起路径不同，后者受 {@code GenerateReason.TOOL_SUSPENDED} 条件控制。
 *
 * <p>每个待处理 {@link ToolUseBlock} 对应一个加入流上下文的 {@link AguiEvent.Interrupt}；{@code AgentLifecycleEventConverter} 在 {@code AgentEndEvent} 时将其取出并写入 {@code RUN_FINISHED} 中断结果。中断 ID 复用 {@code replyId:toolCallId} 格式，使恢复路径能够还原回复/工具调用身份。
 *
 * <p>中断元数据携带 {@code toolContent}——工具参数的有效 JSON 对象字符串——使恢复路径重建的 {@link ToolUseBlock} 具有非 null 的 {@code content}。这是因为 {@code ReActAgent.applyConfirmResults} 完全替换已保存的 {@code ToolUseBlock}，工具输入校验直接读取 {@code content} 而不会回退到 {@code input}；null 内容会以 {@code argument "content" is null} 导致恢复失败。
 *
 * <p>{@link UserConfirmResultEvent} 静默接纳已验证的工具调用 ID，使新运行无需重放前一轮工具调用事件即可交付结果。核心在对待处理工具调用验证确认后才发出此事件。
 */
final class PermissionConfirmEventConverter implements AgentEventConverter {

    private static final Map<String, Object> CONFIRM_RESPONSE_SCHEMA =
            Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of(
                            "approved",
                            Map.of("type", "boolean"),
                            "editedArgs",
                            Map.of(
                                    "type",
                                    "object",
                                    "description",
                                    "Full replacement of the tool args. Not merged."),
                            "reason",
                            Map.of(
                                    "type",
                                    "string",
                                    "description",
                                    "Optional explanation supplied when the tool call is denied.")),
                    "required",
                    List.of("approved"));

    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        return Set.of(RequireUserConfirmEvent.class, UserConfirmResultEvent.class);
    }

    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
        if (event instanceof UserConfirmResultEvent resultEvent) {
            for (ConfirmResult result : resultEvent.getConfirmResults()) {
                if (result.getToolCall() != null) {
                    context.adoptToolCall(result.getToolCall().getId());
                }
            }
            return;
        }
        RequireUserConfirmEvent confirmEvent = (RequireUserConfirmEvent) event;
        String replyId = confirmEvent.getReplyId();

        List<ToolUseBlock> toolCalls = confirmEvent.getToolCalls();
        for (ToolUseBlock toolUse : toolCalls) {
            // toolUse 不会为 null，参见 RequireUserConfirmEvent 构造器
            if (isBlank(toolUse.getId())) {
                throw new IllegalStateException(
                        "RequireUserConfirmEvent contains a tool call without a stable id");
            }
            context.addInterrupt(buildInterrupt(replyId, toolUse));
        }
    }

    private static AguiEvent.Interrupt buildInterrupt(String replyId, ToolUseBlock toolUse) {
        String toolCallId = toolUse.getId();
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (!isBlank(toolUse.getName())) {
            metadata.put(METADATA_TOOL_NAME, toolUse.getName());
        }
        if (toolUse.getInput() != null && !toolUse.getInput().isEmpty()) {
            metadata.put(METADATA_TOOL_INPUT, toolUse.getInput());
        }
        metadata.put(METADATA_TOOL_CONTENT, JsonUtils.resolveToolCallArgsJson(toolUse));
        metadata.put(METADATA_AGENTSCOPE_INTERRUPT_KIND, INTERRUPT_KIND_PERMISSION_CONFIRM);
        if (!isBlank(replyId)) {
            metadata.put(METADATA_REPLY_ID, replyId);
        }
        return new AguiEvent.Interrupt(
                interruptId(replyId, toolCallId),
                TOOL_CALL_INTERRUPT_REASON,
                confirmMessage(toolUse),
                toolCallId,
                CONFIRM_RESPONSE_SCHEMA,
                null,
                Map.copyOf(metadata));
    }

    private static String confirmMessage(ToolUseBlock toolUse) {
        String name = isBlank(toolUse.getName()) ? "tool" : toolUse.getName();
        return "Tool '" + name + "' requires user confirmation before execution";
    }

    private static String interruptId(String replyId, String toolCallId) {
        if (!isBlank(replyId)) {
            return replyId + ":" + toolCallId;
        }
        return toolCallId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
