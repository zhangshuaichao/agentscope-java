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

import io.agentscope.builder.web.auth.UserStore;
import io.agentscope.builder.web.auth.UserStore.UserRecord;
import io.agentscope.builder.web.catalog.AgentDefinition;
import io.agentscope.builder.web.catalog.UserAgentDefinitionStore;
import io.agentscope.builder.web.managed.AgentVersionSnapshot;
import io.agentscope.builder.web.managed.service.AgentVersionService;
import io.agentscope.builder.web.persistence.jpa.AgentVersionEntity;
import io.agentscope.builder.web.persistence.jpa.AgentVersionEntityRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * 纯 JPA 的 {@link AgentVisibilityResolver}：共享数据库上的读取视图，供不拥有文件目录的平面
 * （数据平面、调度器）使用。
 * <p>声明为普通类而非 {@code @Component}，避免控制平面已有权威目录解析器时产生两个同类型 Bean。
 * 非控制平面在自身配置中将其声明为 {@code @Bean}。
 * <p>可见性沿用控制平面目录语义，但不依赖任何文件状态：
 * <ul>
 *   <li><b>全局智能体</b>通过 {@link
 *       AgentVersionService#GLOBAL_OWNER} 下是否存在版本行识别。控制平面启动时将这些行具体化
 *       （{@code ManagedAgentsMigrationRunner → AgentCatalogService#ensureGlobalVersions}），
 *       因而可交给数据平面运行的智能体应已具备版本行：流量到达此处前，控制平面先创建会话。
 *   <li><b>自有智能体</b>从调用方的 {@link UserAgentDefinitionStore} 命名空间解析。
 *   <li><b>被共享智能体</b>通过扫描其他所有者的命名空间解析，接受第一个被
 *       {@link AgentAclService#tierFor} 授予任一级别的条目（USER 或 WORKSPACE 受让方）。
 * </ul>
 * <p>权限级别强制检查仍在 {@link AgentAccessGuard}；此类仅判断可见性并返回定义。
 */
@Transactional(readOnly = true)
public class JpaAgentVisibilityResolver implements AgentVisibilityResolver {

    private final AgentVersionEntityRepository versionRepository;
    private final AgentVersionService versionService;
    private final UserAgentDefinitionStore store;
    private final UserStore userStore;
    private final AgentAclService aclService;

    public JpaAgentVisibilityResolver(
            AgentVersionEntityRepository versionRepository,
            AgentVersionService versionService,
            UserAgentDefinitionStore store,
            UserStore userStore,
            AgentAclService aclService) {
        this.versionRepository = versionRepository;
        this.versionService = versionService;
        this.store = store;
        this.userStore = userStore;
        this.aclService = aclService;
    }

    @Override
    public Optional<AgentDefinition> findVisible(String userId, String agentId) {
        if (agentId == null) {
            return Optional.empty();
        }
        // 全局智能体优先解决 ID 冲突，与控制平面的 listVisible 顺序一致。
        Optional<AgentDefinition> global = findGlobal(agentId);
        if (global.isPresent()) {
            return global;
        }
        // 其次使用调用方自有副本。
        Optional<UserAgentDefinitionStore.StoredEntry> own = store.findById(userId, agentId);
        if (own.isPresent()) {
            return Optional.of(own.get().toDefinition(userId));
        }
        // 最后检查其他所有者的定义，仅通过 ACL 授权接受。
        for (UserRecord owner : userStore.listAll()) {
            if (owner.userId().equals(userId)) {
                continue;
            }
            Optional<UserAgentDefinitionStore.StoredEntry> e =
                    store.findById(owner.userId(), agentId);
            if (e.isEmpty()) {
                continue;
            }
            AgentDefinition def = e.get().toDefinition(owner.userId());
            if (aclService.tierFor(userId, def) != null) {
                return Optional.of(def);
            }
        }
        return Optional.empty();
    }

    /** 从全局智能体最新的已具体化版本快照重建定义。没有全局版本行时返回 {@link Optional#empty()}（控制平面不知道该智能体，或从未针对此数据库启动过）。 */
    private Optional<AgentDefinition> findGlobal(String agentId) {
        List<AgentVersionEntity> versions =
                versionRepository.findByOwnerIdAndAgentIdOrderByVersionAsc(
                        AgentVersionService.GLOBAL_OWNER, agentId);
        if (versions.isEmpty()) {
            return Optional.empty();
        }
        AgentVersionEntity head = versions.get(versions.size() - 1);
        AgentVersionSnapshot s = versionService.fromJson(head.getSnapshotJson());
        return Optional.of(
                new AgentDefinition(
                        agentId,
                        s.name() != null ? s.name() : agentId,
                        s.description(),
                        null, // 不暴露系统提示词，与控制平面目录保持一致
                        s.model(),
                        s.maxIters(),
                        s.tools(),
                        s.mcpServers(),
                        s.skills(),
                        s.multiagent(),
                        s.identityName(),
                        s.identityEmoji(),
                        s.groupChatMentionPatterns(),
                        s.groupChatRequireMention(),
                        AgentDefinition.SCOPE_GLOBAL,
                        null, // ownerId — 全局智能体没有所有者
                        head.getCreatedAt(),
                        head.getCreatedAt(),
                        null, // shares — 全局智能体不单独共享
                        AgentDefinition.RUN_AS_INVOKER,
                        null, // forkOf
                        null, // workspacePath — 在运行时侧而非目录侧解析
                        s.sandboxMode(),
                        s.sandboxScope(),
                        head.getVersion(),
                        null, // archivedAt
                        null, // 元数据
                        null)); // tierForCurrentUser — 由调用方填充
    }
}
