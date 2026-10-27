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
package io.agentscope.core.tool;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;

/**
 * 从 ToolResultBlock 构建工具结果消息的工具类。
 *
 * 将 ToolResultBlock 转换为包含该块的 Msg，并从原始工具调用设置正确的 id 和 name。
 * @hidden
 */
public class ToolResultMessageBuilder {

    /**
     * 根据 ToolResultBlock 和原始工具调用构建工具结果消息。
     *
     * <p>【学习提示】工具调用 ID 是请求与结果的关联键，不只是日志字段。即使多次调用同名工具，
     * 各自的 ID 仍区分不同调用；这里沿用原始 ID，才能让模型和 HITL 恢复校验匹配正确结果。
     *
     * @param result 工具执行结果
     * @param originalCall 触发执行的原始工具使用块
     * @param agentName 创建消息的智能体名称
     * @return 包含工具结果的 Msg
     */
    public static Msg buildToolResultMsg(
            ToolResultBlock result, ToolUseBlock originalCall, String agentName) {
        // 从原始调用设置 id 和 name
        ToolResultBlock resultWithIdAndName =
                result.withIdAndName(originalCall.getId(), originalCall.getName());

        return Msg.builder()
                .name(agentName)
                .role(MsgRole.TOOL)
                .content(resultWithIdAndName)
                .build();
    }
}
