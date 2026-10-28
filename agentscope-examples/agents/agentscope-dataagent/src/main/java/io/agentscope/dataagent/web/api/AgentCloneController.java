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
package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.web.audit.ActivityEvent;
import io.agentscope.dataagent.web.audit.AgentActivityStore;
import io.agentscope.dataagent.web.catalog.AgentCatalogService;
import io.agentscope.dataagent.web.catalog.AgentCatalogService.StoredEntryAndDefinition;
import io.agentscope.dataagent.web.catalog.AgentDefinition;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.share.AgentAclService.Tier;
import io.agentscope.dataagent.web.util.WorkspaceCopier;
import io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 克隆端点：为至少拥有 CLONE 权限的调用者创建其私有智能体副本。
 * <p>副本复制配置和工作区文件，但不带原来的共享授权、会话及渠道绑定（设计计划第 5 节）。
 * 文件复制经 {@code AbstractFilesystem} 抽象层执行；当前使用按 {@code (userId,
 * agentId)} 隔离的 Docker 沙箱视图，具体行为仍取决于底层实现。
 * <p>第一版不支持克隆全局智能体（返回 409）：全局配置位于 {@code agentscope.json}，
 * 不属于用户命名空间，其工作区布局尚未接入按用户使用的 {@link WorkspaceManagerFactory}。
 */
@RestController
@RequestMapping("/api/agents/{id}/clone")
public class AgentCloneController {

    private static final Logger log = LoggerFactory.getLogger(AgentCloneController.class);

    private final AgentCatalogService catalog;
    private final AgentAccessGuard guard;
    private final WorkspaceManagerFactory workspaceFactory;
    private final AgentActivityStore activity;

    public AgentCloneController(
            AgentCatalogService catalog,
            AgentAccessGuard guard,
            WorkspaceManagerFactory workspaceFactory,
            AgentActivityStore activity) {
        this.catalog = catalog;
        this.guard = guard;
        this.workspaceFactory = workspaceFactory;
        this.activity = activity;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<AgentDefinition> clone(
            @PathVariable("id") String sourceAgentId,
            @RequestBody(required = false) CloneRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    AgentDefinition src = guard.require(userId, sourceAgentId, Tier.CLONE);
                    if (!AgentDefinition.SCOPE_USER.equals(src.scope()) || src.ownerId() == null) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Cloning global agents is not supported yet; "
                                        + "edit agentscope.json or fork the project instead.");
                    }
                    String srcOwnerId = src.ownerId();
                    String newId = req != null ? req.newAgentId() : null;
                    String newName = req != null ? req.name() : null;

                    StoredEntryAndDefinition out =
                            catalog.prepareClone(srcOwnerId, sourceAgentId, userId, newId, newName);

                    int copied =
                            WorkspaceCopier.copy(
                                    workspaceFactory,
                                    srcOwnerId,
                                    sourceAgentId,
                                    src.workspacePath(),
                                    userId,
                                    out.entry().id(),
                                    out.entry().workspacePath());
                    log.info(
                            "Clone {}/{} -> {}/{}: {} files copied",
                            srcOwnerId,
                            sourceAgentId,
                            userId,
                            out.entry().id(),
                            copied);

                    AgentActivityStore.ActorRef actor = activity.actor(userId);
                    activity.record(
                            userId,
                            out.entry().id(),
                            actor,
                            ActivityEvent.Action.CLONE_TO,
                            srcOwnerId + "/" + sourceAgentId,
                            Map.of("files", copied));
                    activity.record(
                            srcOwnerId,
                            sourceAgentId,
                            actor,
                            ActivityEvent.Action.CLONE_FROM,
                            userId + "/" + out.entry().id(),
                            Map.of("files", copied));
                    return out.definition();
                });
    }

    public record CloneRequest(String newAgentId, String name) {}
}
