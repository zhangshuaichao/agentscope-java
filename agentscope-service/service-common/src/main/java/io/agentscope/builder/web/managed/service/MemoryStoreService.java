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
package io.agentscope.builder.web.managed.service;

import io.agentscope.builder.web.managed.MemoryDto;
import io.agentscope.builder.web.managed.MemoryStoreDto;
import io.agentscope.builder.web.managed.MemoryVersionDto;
import io.agentscope.builder.web.managed.ResourceShareDto;
import io.agentscope.builder.web.persistence.jpa.MemoryEntity;
import io.agentscope.builder.web.persistence.jpa.MemoryEntityRepository;
import io.agentscope.builder.web.persistence.jpa.MemoryStoreEntity;
import io.agentscope.builder.web.persistence.jpa.MemoryStoreEntityRepository;
import io.agentscope.builder.web.persistence.jpa.MemoryVersionEntity;
import io.agentscope.builder.web.persistence.jpa.MemoryVersionEntityRepository;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import io.agentscope.builder.web.share.ResourceAccessService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 记忆存储及带版本记忆文档的增删改查。 */
@Service
@Transactional
public class MemoryStoreService {

    /** {@link ResourceAccessService} 共享授权使用的资源类型键。 */
    public static final String RESOURCE_TYPE = "memory_store";

    /** 创建记忆存储的请求体。 */
    public record CreateMemoryStoreRequest(String name, String description) {}

    /** 写入记忆文档的请求体。 */
    public record PutMemoryRequest(String content) {}

    private final MemoryStoreEntityRepository storeRepository;
    private final MemoryEntityRepository memoryRepository;
    private final MemoryVersionEntityRepository versionRepository;
    private final ResourceAccessService resourceAccessService;

    public MemoryStoreService(
            MemoryStoreEntityRepository storeRepository,
            MemoryEntityRepository memoryRepository,
            MemoryVersionEntityRepository versionRepository,
            ResourceAccessService resourceAccessService) {
        this.storeRepository = storeRepository;
        this.memoryRepository = memoryRepository;
        this.versionRepository = versionRepository;
        this.resourceAccessService = resourceAccessService;
    }

    /** 列出用户拥有的记忆存储。 */
    @Transactional(readOnly = true)
    public List<MemoryStoreDto> listStores(String ownerId) {
        return storeRepository.findByOwnerIdOrderByCreatedAtAsc(ownerId).stream()
                .map(this::toStoreDto)
                .toList();
    }

    /** 调用方拥有或获共享授权（至少 RUN）的单个记忆存储。 */
    @Transactional(readOnly = true)
    public MemoryStoreDto getStore(String ownerId, String storeId) {
        return toStoreDto(requireAccess(ownerId, storeId, Tier.RUN));
    }

    /** 创建记忆存储。 */
    public MemoryStoreDto createStore(String ownerId, CreateMemoryStoreRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (storeRepository.existsByOwnerIdAndName(ownerId, request.name())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Memory store name already exists: " + request.name());
        }
        long now = System.currentTimeMillis();
        MemoryStoreEntity entity = new MemoryStoreEntity();
        entity.setStoreId(ManagedJsonHelper.randomId("mst_"));
        entity.setOwnerId(ownerId);
        entity.setName(request.name());
        entity.setDescription(request.description());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toStoreDto(storeRepository.save(entity));
    }

    /** 删除记忆存储及其包含的所有记忆。 */
    public void deleteStore(String ownerId, String storeId) {
        requireAccess(ownerId, storeId, Tier.EDIT);
        for (MemoryEntity memory : memoryRepository.findByStoreIdOrderByPathAsc(storeId)) {
            versionRepository.deleteByMemoryId(memory.getMemoryId());
        }
        memoryRepository.deleteByStoreId(storeId);
        storeRepository.findByStoreId(storeId).ifPresent(storeRepository::delete);
    }

    /** 列出存储中的所有记忆。 */
    @Transactional(readOnly = true)
    public List<MemoryDto> listMemories(String ownerId, String storeId) {
        requireAccess(ownerId, storeId, Tier.RUN);
        return memoryRepository.findByStoreIdOrderByPathAsc(storeId).stream()
                .map(this::toMemoryDto)
                .toList();
    }

    /** 按路径返回记忆文档。 */
    @Transactional(readOnly = true)
    public MemoryDto getMemory(String ownerId, String storeId, String path) {
        requireAccess(ownerId, storeId, Tier.RUN);
        return memoryRepository
                .findByStoreIdAndPath(storeId, path)
                .map(this::toMemoryDto)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Memory not found: " + path));
    }

    /** 创建或更新记忆文档，并递增头版本。 */
    public MemoryDto putMemory(
            String ownerId, String storeId, String path, PutMemoryRequest request) {
        requireAccess(ownerId, storeId, Tier.EDIT);
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
        }
        long now = System.currentTimeMillis();
        MemoryEntity memory =
                memoryRepository
                        .findByStoreIdAndPath(storeId, path)
                        .orElseGet(
                                () -> {
                                    MemoryEntity fresh = new MemoryEntity();
                                    fresh.setMemoryId(ManagedJsonHelper.randomId("mem_"));
                                    fresh.setStoreId(storeId);
                                    fresh.setPath(path);
                                    fresh.setHeadVersion(0);
                                    fresh.setCreatedAt(now);
                                    return fresh;
                                });
        int nextVersion = memory.getHeadVersion() + 1;
        memory.setContent(request.content());
        memory.setHeadVersion(nextVersion);
        memory.setUpdatedAt(now);
        MemoryEntity saved = memoryRepository.save(memory);

        MemoryVersionEntity version = new MemoryVersionEntity();
        version.setMemoryId(saved.getMemoryId());
        version.setVersion(nextVersion);
        version.setContent(saved.getContent());
        version.setCreatedAt(now);
        versionRepository.save(version);

        MemoryStoreEntity store = storeRepository.findByStoreId(storeId).orElseThrow();
        store.setUpdatedAt(now);
        storeRepository.save(store);
        return toMemoryDto(saved);
    }

    /** 删除记忆文档及其版本历史。 */
    public void deleteMemory(String ownerId, String storeId, String path) {
        requireAccess(ownerId, storeId, Tier.EDIT);
        MemoryEntity memory =
                memoryRepository
                        .findByStoreIdAndPath(storeId, path)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Memory not found: " + path));
        versionRepository.deleteByMemoryId(memory.getMemoryId());
        memoryRepository.delete(memory);
    }

    /** 列出记忆文档的所有版本。 */
    @Transactional(readOnly = true)
    public List<MemoryVersionDto> listVersions(String ownerId, String storeId, String path) {
        MemoryEntity memory = requireMemory(ownerId, storeId, path);
        return versionRepository.findByMemoryIdOrderByVersionAsc(memory.getMemoryId()).stream()
                .map(this::toVersionDto)
                .toList();
    }

    /** 列出记忆存储上的共享授权。仅限所有者/EDIT 级别。 */
    @Transactional(readOnly = true)
    public List<ResourceShareDto> listShares(String ownerId, String storeId) {
        requireAccess(ownerId, storeId, Tier.EDIT);
        return resourceAccessService.listShares(RESOURCE_TYPE, storeId);
    }

    /** 将记忆存储共享给用户或整个工作区。仅限所有者/EDIT 级别。 */
    public ResourceShareDto share(
            String ownerId, String storeId, String granteeType, String granteeId, Tier tier) {
        MemoryStoreEntity store = requireAccess(ownerId, storeId, Tier.EDIT);
        return resourceAccessService.share(
                RESOURCE_TYPE, storeId, store.getOwnerId(), granteeType, granteeId, tier, ownerId);
    }

    /** 撤销记忆存储上的共享授权。仅限所有者/EDIT 级别。 */
    public void unshare(String ownerId, String storeId, String shareId) {
        requireAccess(ownerId, storeId, Tier.EDIT);
        resourceAccessService.unshare(RESOURCE_TYPE, storeId, shareId);
    }

    /** 解析存储并验证 {@code callerId} 至少具有 {@code required} 权限。 */
    private MemoryStoreEntity requireAccess(String callerId, String storeId, Tier required) {
        MemoryStoreEntity store =
                storeRepository
                        .findByStoreId(storeId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Memory store not found: " + storeId));
        resourceAccessService.require(
                callerId, store.getOwnerId(), RESOURCE_TYPE, storeId, required);
        return store;
    }

    private MemoryEntity requireMemory(String ownerId, String storeId, String path) {
        requireAccess(ownerId, storeId, Tier.RUN);
        return memoryRepository
                .findByStoreIdAndPath(storeId, path)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Memory not found: " + path));
    }

    private MemoryStoreDto toStoreDto(MemoryStoreEntity entity) {
        return new MemoryStoreDto(
                entity.getStoreId(),
                entity.getOwnerId(),
                entity.getName(),
                entity.getDescription(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private MemoryDto toMemoryDto(MemoryEntity entity) {
        return new MemoryDto(
                entity.getMemoryId(),
                entity.getStoreId(),
                entity.getPath(),
                entity.getContent(),
                entity.getHeadVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private MemoryVersionDto toVersionDto(MemoryVersionEntity entity) {
        return new MemoryVersionDto(
                entity.getMemoryId(),
                entity.getVersion(),
                entity.getContent(),
                entity.getCreatedAt());
    }
}
