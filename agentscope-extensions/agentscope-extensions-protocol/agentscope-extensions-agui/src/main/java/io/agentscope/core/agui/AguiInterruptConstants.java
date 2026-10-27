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
package io.agentscope.core.agui;

/**
 * AG-UI 中断原因和 AgentScope 中断元数据的常量。
 */
public final class AguiInterruptConstants {

    private AguiInterruptConstants() {}

    /**
     * 工具关联决策的 AG-UI 中断原因。
     */
    public static final String TOOL_CALL_INTERRUPT_REASON = "tool_call";

    /**
     * 结构化输入请求的 AG-UI 中断原因。
     */
    public static final String INPUT_REQUIRED_INTERRUPT_REASON = "input_required";

    /**
     * 中断元数据键：AgentScope 专用中断种类。
     */
    public static final String METADATA_AGENTSCOPE_INTERRUPT_KIND = "agentscope.interruptKind";

    /**
     * 权限模式工具确认的中断元数据值。
     */
    public static final String INTERRUPT_KIND_PERMISSION_CONFIRM = "permission_confirm";

    /**
     * 中断元数据键：工具名称。
     */
    public static final String METADATA_TOOL_NAME = "toolName";

    /**
     * 中断元数据键：已解析的工具参数。
     */
    public static final String METADATA_TOOL_INPUT = "toolInput";

    /**
     * 中断元数据键：序列化为 JSON 对象字符串的工具参数。
     */
    public static final String METADATA_TOOL_CONTENT = "toolContent";

    /**
     * 中断元数据键：发起请求的回复 ID。
     */
    public static final String METADATA_REPLY_ID = "replyId";
}
