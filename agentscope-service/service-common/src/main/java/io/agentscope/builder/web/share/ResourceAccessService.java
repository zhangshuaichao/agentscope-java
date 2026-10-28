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

import io.agentscope.builder.web.managed.ResourceShareDto;
import io.agentscope.builder.web.managed.service.ManagedJsonHelper;
import io.agentscope.builder.web.persistence.jpa.ResourceShareEntity;
import io.agentscope.builder.web.persistence.jpa.ResourceShareEntityRepository;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 非智能体托管资源（环境、记忆存储、密钥库）的通用“所有者或获共享”访问控制。复用 {@link AgentAclService.Tier}，使应用内 ACL 决定使用相同三级词汇（{@code CLONE < RUN < EDIT}），但授权统一保存在以资源类型为键的表（{@link ResourceShareEntity}），而非每种资源单独建表。<p>规则：资源所有者始终拥有 {@code EDIT}；匹配调用方的授权（直接 {@code USER} 授权，或适用于所有已登录用户的 {@code WORKSPACE} 授权）贡献其级别，取最高适用级别。 */
@Service
public class ResourceAccessService {

    private final ResourceShareEntityRepository repository;

    public ResourceAccessService(ResourceShareEntityRepository repository) {
        this.repository = repository;
    }

    /** 返回 {@code userId} 对资源拥有的最高权限级别；无权限时返回 {@code null}。 */
    public Tier tierFor(String userId, String ownerId, String resourceType, String resourceId) {
        if (userId != null && userId.equals(ownerId)) {
            return Tier.EDIT;
        }
        return highestMatchingGrant(
                userId, repository.findByResourceTypeAndResourceId(resourceType, resourceId));
    }

    /** 当且仅当 {@code userId} 对资源至少拥有 {@code required} 权限时为 {@code true}。 */
    public boolean can(
            String userId, String ownerId, String resourceType, String resourceId, Tier required) {
        Tier held = tierFor(userId, ownerId, resourceType, resourceId);
        return held != null && held.implies(required);
    }

    /**
     * 验证 {@code userId} 对资源至少拥有 {@code required} 权限。
     * @throws ResponseStatusException 权限级别不足时为 403
     */
    public void require(
            String userId, String ownerId, String resourceType, String resourceId, Tier required) {
        if (!can(userId, ownerId, resourceType, resourceId, required)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Required tier " + required.name() + " on " + resourceType + " " + resourceId);
        }
    }

    /** 列出资源上的共享授权。 */
    public List<ResourceShareDto> listShares(String resourceType, String resourceId) {
        return repository.findByResourceTypeAndResourceId(resourceType, resourceId).stream()
                .map(ResourceAccessService::toDto)
                .toList();
    }

    /** 在 {@code (resourceType, resourceId)} 上为给定受让方创建或更新授权。对相同受让方重新共享时原地更新级别，而不重复创建行。 */
    public ResourceShareDto share(
            String resourceType,
            String resourceId,
            String ownerId,
            String granteeType,
            String granteeId,
            Tier tier,
            String createdBy) {
        validateGrantee(granteeType, granteeId);
        ResourceShareEntity entity =
                repository
                        .findByResourceTypeAndResourceIdAndGranteeTypeAndGranteeId(
                                resourceType, resourceId, granteeType, granteeId)
                        .orElseGet(
                                () -> {
                                    ResourceShareEntity fresh = new ResourceShareEntity();
                                    fresh.setShareId(ManagedJsonHelper.randomId("shr_"));
                                    fresh.setResourceType(resourceType);
                                    fresh.setResourceId(resourceId);
                                    fresh.setOwnerId(ownerId);
                                    fresh.setGranteeType(granteeType);
                                    fresh.setGranteeId(granteeId);
                                    fresh.setCreatedAt(System.currentTimeMillis());
                                    fresh.setCreatedBy(createdBy);
                                    return fresh;
                                });
        entity.setTier(tier.name());
        return toDto(repository.save(entity));
    }

    /** 移除共享授权。不存在时不做操作。 */
    public void unshare(String resourceType, String resourceId, String shareId) {
        repository
                .findByShareId(shareId)
                .filter(
                        e ->
                                resourceType.equals(e.getResourceType())
                                        && resourceId.equals(e.getResourceId()))
                .ifPresent(e -> repository.deleteByShareId(shareId));
    }

    private static void validateGrantee(String granteeType, String granteeId) {
        if (!AgentShareGrant.GRANTEE_USER.equals(granteeType)
                && !AgentShareGrant.GRANTEE_WORKSPACE.equals(granteeType)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "granteeType must be USER or WORKSPACE");
        }
        if (granteeId == null || granteeId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "granteeId is required");
        }
    }

    private static Tier highestMatchingGrant(String userId, List<ResourceShareEntity> grants) {
        if (grants == null || grants.isEmpty()) {
            return null;
        }
        Tier best = null;
        for (ResourceShareEntity g : grants) {
            if (!applies(userId, g)) {
                continue;
            }
            Tier t = parseTier(g.getTier());
            if (t == null) {
                continue;
            }
            if (best == null || t.implies(best)) {
                best = t;
            }
        }
        return best;
    }

    private static boolean applies(String userId, ResourceShareEntity g) {
        if (g.getGranteeType() == null) {
            return false;
        }
        if (AgentShareGrant.GRANTEE_WORKSPACE.equals(g.getGranteeType())) {
            return userId != null;
        }
        if (AgentShareGrant.GRANTEE_USER.equals(g.getGranteeType())) {
            return userId != null && userId.equals(g.getGranteeId());
        }
        return false;
    }

    private static Tier parseTier(String raw) {
        if (raw == null) return null;
        try {
            return Tier.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static ResourceShareDto toDto(ResourceShareEntity e) {
        return new ResourceShareDto(
                e.getShareId(),
                e.getResourceType(),
                e.getResourceId(),
                e.getGranteeType(),
                e.getGranteeId(),
                e.getTier(),
                e.getCreatedAt(),
                e.getCreatedBy());
    }
}
