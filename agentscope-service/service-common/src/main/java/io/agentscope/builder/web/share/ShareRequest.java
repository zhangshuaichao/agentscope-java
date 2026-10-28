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
package io.agentscope.builder.web.share;

/**
 * 为非智能体托管资源（环境 / 记忆存储 / 密钥库）授予 {@link ResourceAccessService} 共享权限的请求体。
 * @param granteeType {@link AgentShareGrant#GRANTEE_USER} 或 {@link
 *     AgentShareGrant#GRANTEE_WORKSPACE}
 * @param granteeId 目标用户 ID；工作区授权时使用 {@link AgentShareGrant#WORKSPACE_ID}
 * @param tier {@code CLONE}、{@code RUN}、{@code EDIT} 之一（见 {@link AgentAclService.Tier}）
 */
public record ShareRequest(String granteeType, String granteeId, String tier) {}
