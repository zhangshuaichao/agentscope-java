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
package io.agentscope.claw2.web.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 本地目录公开的智能体定义 API 表示。
 *
 * <p>agentscope-claw 是单用户应用。两种智能体并存并采用相同工作区布局：
 *
 * <ul>
 *   <li><b>内置：</b>定义在 {@code ${clawHome}/agentscope.json}，始终存在，不能经 API 删除。
 *   <li><b>自定义：</b>用户经 UI 定义并持久化到 {@code ${clawHome}/agents.json}。
 * </ul>
 *
 * <p>{@code builtin} 标记告知前端条目类型，便于隐藏内置智能体的删除操作。
 *
 * @param id 智能体标识，在目录内唯一
 * @param name 可读显示名称
 * @param description 智能体用途的可选简述
 * @param sysPrompt 系统提示词
 * @param model 可选模型 ID 覆盖值
 * @param maxIters 最大推理迭代次数（null 使用运行时默认值）
 * @param tools 智能体可访问的内置工具名称列表（null 表示未知）
 * @param toolsAllow 显式工具允许列表（null 表示未配置）
 * @param toolsDeny 显式工具拒绝列表（null 表示未配置）
 * @param identityName 显示名称覆盖值（null 使用智能体名称）
 * @param identityEmoji 表情符号简写（null 表示未设置）
 * @param groupChatMentionPatterns 群聊提及匹配模式（null 表示未配置）
 * @param groupChatRequireMention 群聊是否必须提及智能体
 * @param sandboxMode 为未来逐智能体沙箱预留（默认 {@code "off"}）
 * @param sandboxScope 为未来逐智能体沙箱预留（默认 {@code "agent"}）
 * @param skillsAllow 显式技能允许列表（null 表示未配置）
 * @param skillsDeny 显式技能拒绝列表（null 表示未配置）
 * @param createdAt 创建时间戳（epoch 毫秒）；内置智能体为 {@code 0}
 * @param updatedAt 最后更新时间戳（epoch 毫秒）
 * @param workspacePath 可选工作区位置覆盖值；绝对路径原样使用，相对路径相对于 clawHome 解析，空白时采用 {@code ${clawHome}/agents/{id}/workspace} 默认布局
 * @param builtin 来自内置 {@code agentscope.json} 时为 {@code true}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentDefinition(
        String id,
        String name,
        String description,
        String sysPrompt,
        String model,
        Integer maxIters,
        List<String> tools,
        List<String> toolsAllow,
        List<String> toolsDeny,
        String identityName,
        String identityEmoji,
        List<String> groupChatMentionPatterns,
        Boolean groupChatRequireMention,
        String sandboxMode,
        String sandboxScope,
        List<String> skillsAllow,
        List<String> skillsDeny,
        long createdAt,
        long updatedAt,
        String workspacePath,
        boolean builtin) {}
