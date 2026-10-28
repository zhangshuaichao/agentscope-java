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
package io.agentscope.dataagent.web.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.dataagent.web.share.AgentShareGrant;
import java.util.List;

/**
 * 当前用户可见的智能体定义的 API 表示。
 * <p>两种范围：
 * <ul>
 *   <li>{@code global}：在项目 {@code agentscope.json} 中定义，所有用户可见。
 *       普通用户不能修改或删除，但可发起隔离对话。</li>
 *   <li>{@code user}：由特定用户定义，所有者可创建、更新、删除，也可通过共享授权开放给其他用户。</li>
 * </ul>
 * @param id 智能体标识符，在其范围内唯一
 * @param name 可读显示名
 * @param description 可选用途简述
 * @param sysPrompt 系统提示词，全局智能体可为 null 或隐藏
 * @param model 可选模型 ID 覆盖值
 * @param maxIters 最大推理迭代数，null 使用运行时默认值
 * @param tools 可访问的内置工具名称列表，null 表示未知
 * @param toolsAllow 显式工具允许列表，null 表示未配置
 * @param toolsDeny 显式工具拒绝列表，null 表示未配置
 * @param identityName 显示名覆盖值，null 使用智能体名称
 * @param identityEmoji 表情简写，null 表示未设置
 * @param groupChatMentionPatterns 群聊提及模式，null 表示未配置
 * @param groupChatRequireMention 群聊是否要求提及
 * @param skillsAllow 显式技能允许列表，null 表示未配置
 * @param skillsDeny 显式技能拒绝列表，null 表示未配置
 * @param scope {@code "global"} 或 {@code "user"}
 * @param ownerId 创建者 userId，全局智能体为 {@code null}
 * @param createdAt 创建时间戳（纪元毫秒），从配置加载的全局智能体为 {@code 0}
 * @param updatedAt 最后更新时间戳（纪元毫秒）
 * @param shares 逐智能体共享授权，null/空表示未共享
 * @param runAs 运行身份（默认 {@code "INVOKER"}；为 v2 Claws 语义预留，
 *     {@code "OWNER"} 将表示对受让方调用模拟智能体所有者）
 * @param forkOf 由克隆产生时为源 {@code agentId}，否则为 {@code null}
 * @param workspacePath 用户选择的工作区位置标签；绝对路径原样使用，相对/空白路径由工厂按配置根解析。
 *     旧文档约定为 {@code ~/.agentscope/}，空白默认使用智能体 ID。只在创建时设置，之后不可编辑。
 * @param sandboxMode 可选执行隔离后端（{@code "local"} 或 {@code "sandbox"}）；
 *     {@code null} 时运行时使用平台默认值
 * @param sandboxScope {@code sandboxMode == "sandbox"} 时的可选共享范围（{@code
 *     "SESSION"} / {@code "USER"} / {@code "AGENT"} / {@code "GLOBAL"}），映射到 {@link
 *     io.agentscope.harness.agent.IsolationScope}
 * @param tierForCurrentUser 临时字段：当前调用用户的有效级别
 *     （{@code "CLONE"}/{@code "RUN"}/{@code "EDIT"}），只在已认证读取路径填充，不持久化
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
        List<String> skillsAllow,
        List<String> skillsDeny,
        String scope,
        String ownerId,
        long createdAt,
        long updatedAt,
        List<AgentShareGrant> shares,
        String runAs,
        String forkOf,
        String workspacePath,
        String sandboxMode,
        String sandboxScope,
        String tierForCurrentUser) {

    public static final String SCOPE_GLOBAL = "global";
    public static final String SCOPE_USER = "user";

    public static final String RUN_AS_INVOKER = "INVOKER";
    public static final String RUN_AS_OWNER = "OWNER";

    /** 返回替换了 {@code tierForCurrentUser} 的副本。 */
    public AgentDefinition withTierForCurrentUser(String tier) {
        return new AgentDefinition(
                id,
                name,
                description,
                sysPrompt,
                model,
                maxIters,
                tools,
                toolsAllow,
                toolsDeny,
                identityName,
                identityEmoji,
                groupChatMentionPatterns,
                groupChatRequireMention,
                skillsAllow,
                skillsDeny,
                scope,
                ownerId,
                createdAt,
                updatedAt,
                shares,
                runAs,
                forkOf,
                workspacePath,
                sandboxMode,
                sandboxScope,
                tier);
    }
}
