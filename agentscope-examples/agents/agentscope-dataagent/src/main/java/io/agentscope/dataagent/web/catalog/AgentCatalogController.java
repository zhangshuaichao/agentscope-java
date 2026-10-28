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

import io.agentscope.dataagent.web.audit.ActivityEvent;
import io.agentscope.dataagent.web.audit.AgentActivityStore;
import io.agentscope.dataagent.web.catalog.AgentCatalogService.AgentCreateRequest;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.share.AgentAclService;
import io.agentscope.dataagent.web.share.AgentAclService.Tier;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** 智能体目录 REST 控制器。<ul><li>{@code GET /api/agents}：列出全部可见定义（全局、自有及被共享）。<li>{@code GET /api/agents/{id}}：获取单个定义。<li>{@code POST /api/agents}：创建用户自定义智能体。<li>{@code PUT /api/agents/{id}}：更新用户自定义智能体（所有者或 EDIT 授权）。<li>{@code DELETE /api/agents/{id}}：删除用户自定义智能体（所有者或 EDIT 授权）。</ul> */
@RestController
@RequestMapping("/api/agents")
public class AgentCatalogController {

    private final AgentCatalogService catalogService;
    private final AgentAclService aclService;
    private final AgentAccessGuard guard;
    private final AgentActivityStore activity;

    public AgentCatalogController(
            AgentCatalogService catalogService,
            AgentAclService aclService,
            AgentAccessGuard guard,
            AgentActivityStore activity) {
        this.catalogService = catalogService;
        this.aclService = aclService;
        this.guard = guard;
        this.activity = activity;
    }

    /** 列出已认证用户可见的全部智能体定义：先全局智能体，再用户自定义及可见的共享智能体。 */
    @GetMapping
    public Mono<List<AgentDefinition>> listAgents(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () ->
                        catalogService.listVisible(userId).stream()
                                .map(def -> withTier(userId, def))
                                .toList());
    }

    /** 获取已认证用户可见的单个智能体定义。 */
    @GetMapping("/{id}")
    public Mono<AgentDefinition> getAgent(@PathVariable String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () ->
                        withTier(
                                userId,
                                catalogService
                                        .findVisible(userId, id)
                                        .orElseThrow(
                                                () ->
                                                        new org.springframework.web.server
                                                                .ResponseStatusException(
                                                                org.springframework.http.HttpStatus
                                                                        .NOT_FOUND,
                                                                "Agent not found: " + id))));
    }

    /** 在返回前端的 {@link AgentDefinition} 上补充调用用户的有效权限级别，使界面无需重新实现 ACL 即可控制页签和操作入口。 */
    private AgentDefinition withTier(String userId, AgentDefinition def) {
        Tier t = aclService.tierFor(userId, def);
        return def.withTierForCurrentUser(t == null ? null : t.name());
    }

    /** 创建新的用户自定义智能体定义。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<AgentDefinition> createAgent(
            @RequestBody AgentCreateRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    AgentDefinition created = catalogService.createUserAgent(userId, req);
                    activity.record(
                            userId,
                            created.id(),
                            activity.actor(userId),
                            ActivityEvent.Action.CREATE);
                    return created;
                });
    }

    /** 更新用户自定义智能体定义。所有者或任意 EDIT 受让方可更新；无论谁发起，变更均持久化到所有者命名空间。 */
    @PutMapping("/{id}")
    public Mono<AgentDefinition> updateAgent(
            @PathVariable String id, @RequestBody AgentCreateRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    AgentDefinition def = guard.require(userId, id, Tier.EDIT);
                    String ownerId = def.ownerId();
                    if (ownerId == null) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Global agents cannot be edited via the catalog API");
                    }
                    AgentDefinition updated = catalogService.updateUserAgent(ownerId, id, req);
                    activity.record(
                            ownerId,
                            id,
                            activity.actor(userId),
                            ActivityEvent.Action.EDIT_SETTINGS);
                    return withTier(userId, updated);
                });
    }

    /** 删除用户自定义智能体定义。所有者或 EDIT 受让方可删除；仅移除所有者命名空间副本。 */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteAgent(@PathVariable String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(
                () -> {
                    AgentDefinition def = guard.require(userId, id, Tier.EDIT);
                    String ownerId = def.ownerId();
                    if (ownerId == null) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Global agents cannot be deleted via the catalog API");
                    }
                    catalogService.deleteUserAgent(ownerId, id);
                    // 智能体删除时会移除所属命名空间树（含 activity.jsonl）；
                    // 仍发出最后一条事件，以便工作区审计扫描在日志消失前
                    // 观察是谁触发删除。
                    activity.record(
                            ownerId, id, activity.actor(userId), ActivityEvent.Action.DELETE_AGENT);
                });
    }
}
