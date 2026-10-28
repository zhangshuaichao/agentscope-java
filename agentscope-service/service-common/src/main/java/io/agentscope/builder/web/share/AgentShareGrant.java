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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 智能体上的单条共享授权。
 * <p>包含两个维度：
 * <ul>
 *   <li>授予谁：指定用户（{@code granteeType=USER, granteeId=<userId>}）或本部署的所有已登录用户
 *       （{@code granteeType=WORKSPACE, granteeId="*"}）。
 *   <li>授予什么：权限级别（{@code CLONE}、{@code RUN} 或 {@code EDIT}）。级别具有包含关系：
 *       {@code EDIT > RUN > CLONE}，因此 {@code RUN} 授权包含 {@code CLONE}。
 * </ul>
 * <p>授权保存在智能体定义上（见
 * {@link io.agentscope.builder.web.catalog.AgentDefinition#shares()}），由
 * {@link AgentAclService} 判定。{@code createdAt}/{@code createdBy} 字段用于活动日志，绝不参与权限决定。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentShareGrant(
        String granteeType, String granteeId, String tier, long createdAt, String createdBy) {

    public static final String GRANTEE_USER = "USER";
    public static final String GRANTEE_WORKSPACE = "WORKSPACE";
    public static final String WORKSPACE_ID = "*";

    public static final String TIER_CLONE = "CLONE";
    public static final String TIER_RUN = "RUN";
    public static final String TIER_EDIT = "EDIT";
}
