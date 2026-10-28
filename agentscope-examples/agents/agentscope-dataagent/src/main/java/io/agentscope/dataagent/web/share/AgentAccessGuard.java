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
package io.agentscope.dataagent.web.share;

import io.agentscope.dataagent.web.catalog.AgentCatalogService;
import io.agentscope.dataagent.web.catalog.AgentDefinition;
import io.agentscope.dataagent.web.share.AgentAclService.Tier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 供控制器使用的便捷门禁：按 ID 解析智能体（全局、自有、被共享），并验证调用方至少拥有要求的权限级别。提取各控制器中的 {@code findVisible + can} 模式，同时保持调用处显式可见（不使用 AOP 隐式机制）。 */
@Service
public class AgentAccessGuard {

    private final AgentCatalogService catalog;
    private final AgentAclService acl;

    public AgentAccessGuard(AgentCatalogService catalog, AgentAclService acl) {
        this.catalog = catalog;
        this.acl = acl;
    }

    /**
     * 解析智能体，仅当 {@code userId} 至少拥有 {@code required} 权限时返回。
     * @throws ResponseStatusException 智能体对用户不可见时为 404；可见但权限不足时为 403
     */
    public AgentDefinition require(String userId, String agentId, Tier required) {
        AgentDefinition def =
                catalog.findVisible(userId, agentId)
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
        return catalog.findVisible(userId, agentId)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Agent not found: " + agentId));
    }
}
