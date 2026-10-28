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

import io.agentscope.builder.web.catalog.AgentDefinition;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 供控制器使用的便捷门禁：按 ID 解析智能体（全局、自有、被共享），并验证调用方至少拥有要求的权限级别。将 {@code findVisible + can} 模式从各控制器提取出来，同时保持调用处显式可见（不使用 AOP 隐式机制）。<p>可见性解析委托给平面本地的 {@link AgentVisibilityResolver}（控制平面使用目录服务，数据平面使用 JPA 读取视图），因此两个平面无需跨平面 Maven 依赖即可复用此门禁。 */
@Service
public class AgentAccessGuard {

    private final AgentVisibilityResolver visibilityResolver;
    private final AgentAclService acl;

    public AgentAccessGuard(AgentVisibilityResolver visibilityResolver, AgentAclService acl) {
        this.visibilityResolver = visibilityResolver;
        this.acl = acl;
    }

    /**
     * 解析智能体，仅当 {@code userId} 至少拥有 {@code required} 权限时返回。
     * @throws ResponseStatusException 智能体对用户不可见时为 404；可见但权限不足时为 403
     */
    public AgentDefinition require(String userId, String agentId, Tier required) {
        AgentDefinition def =
                visibilityResolver
                        .findVisible(userId, agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));
        if (!acl.can(userId, def, required)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Required tier " + required.name() + " on agent " + agentId);
        }
        return def;
    }

    /** 获取智能体而不强制权限级别（不可见时返回 404），用于只读描述符。 */
    public AgentDefinition load(String userId, String agentId) {
        return visibilityResolver
                .findVisible(userId, agentId)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Agent not found: " + agentId));
    }
}
