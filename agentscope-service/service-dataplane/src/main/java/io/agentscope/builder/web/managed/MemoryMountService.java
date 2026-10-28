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
package io.agentscope.builder.web.managed;

import io.agentscope.builder.web.managed.service.MemoryStoreService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 将会话绑定的记忆存储解析为 {@link MemoryStoreFilesystem} 路由，挂载到 Harness 文件系统的 {@code memory-stores/{storeName}/} 下（参见 {@link MemoryStoreFilesystem#routePrefix} 与 {@link EnvironmentSpecFactory#applyMemoryStoreRoutes}）。
 * <p>通过 {@link MemoryStoreService} 访问记忆文档，不进行宿主磁盘物化，因此并发轮次与缓存 {@link io.agentscope.harness.agent.HarnessAgent} 实例中的挂载无需显式回写即可保持同步。
 */
@Service
public class MemoryMountService {

    private static final Logger log = LoggerFactory.getLogger(MemoryMountService.class);

    /**
     * 记忆存储路由挂载的根路径段。
     */
    public static final String MEMORY_ROOT = "memory-stores";

    private final MemoryStoreService memoryStoreService;

    public MemoryMountService(MemoryStoreService memoryStoreService) {
        this.memoryStoreService = memoryStoreService;
    }

    /**
     * 加载每个请求记忆存储 ID 的元数据，返回适合注入系统提示词的挂载描述。解析失败（已删除、无权限）的存储记录警告后跳过。
     */
    public List<MountInfo> resolveMounts(String ownerId, List<String> memoryStoreIds) {
        List<MountInfo> mounts = new ArrayList<>();
        if (memoryStoreIds == null || memoryStoreIds.isEmpty()) {
            return mounts;
        }
        for (String storeId : memoryStoreIds) {
            try {
                MemoryStoreDto store = memoryStoreService.getStore(ownerId, storeId);
                String safeName = sanitize(store.name());
                mounts.add(new MountInfo(storeId, store.name(), MEMORY_ROOT + "/" + safeName));
            } catch (Exception ex) {
                log.warn("Failed to resolve memory store {}: {}", storeId, ex.getMessage());
            }
        }
        return mounts;
    }

    /**
     * 为每个可解析记忆存储 ID 构建一个 {@link MemoryStoreFilesystem}，供 {@link EnvironmentSpecFactory#applyMemoryStoreRoutes} 挂载。默认均为 {@code read_write}；可通过 {@link #createFilesystems(String, List, Map)} 将部分存储设为 {@code read_only}。
     */
    public List<MemoryStoreFilesystem> createFilesystems(
            String ownerId, List<String> memoryStoreIds) {
        return createFilesystems(ownerId, memoryStoreIds, Map.of());
    }

    /**
     * 为每个可解析记忆存储 ID 构建一个 {@link MemoryStoreFilesystem}，从 {@code accessByStoreId} 应用各存储访问模式（{@code read_only} | {@code read_write}，默认 {@code read_write}）。该配置通常来自会话环境，例如 {@code config.memoryAccess = {storeId: "read_only"}}。
     */
    public List<MemoryStoreFilesystem> createFilesystems(
            String ownerId, List<String> memoryStoreIds, Map<String, String> accessByStoreId) {
        List<MemoryStoreFilesystem> filesystems = new ArrayList<>();
        if (memoryStoreIds == null || memoryStoreIds.isEmpty()) {
            return filesystems;
        }
        Map<String, String> access = accessByStoreId != null ? accessByStoreId : Map.of();
        for (String storeId : memoryStoreIds) {
            try {
                MemoryStoreDto store = memoryStoreService.getStore(ownerId, storeId);
                filesystems.add(
                        new MemoryStoreFilesystem(
                                memoryStoreService,
                                ownerId,
                                storeId,
                                store.name(),
                                access.get(storeId)));
            } catch (Exception ex) {
                log.warn("Failed to mount memory store {}: {}", storeId, ex.getMessage());
            }
        }
        return filesystems;
    }

    /**
     * 构建描述已挂载记忆存储目录的系统提示词附录。
     */
    public String promptAppendix(List<MountInfo> mounts) {
        if (mounts == null || mounts.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Mounted Memory Stores\n");
        sb.append(
                "These stores contain shared knowledge. Read documents on demand using the memory"
                    + " tools. Each mount's access mode is listed below. Keep private working notes"
                    + " in this session's workspace.\n");
        for (MountInfo mount : mounts) {
            sb.append("- `")
                    .append(mount.relativePath())
                    .append("/` (also conceptually `/mnt/memory/")
                    .append(sanitize(mount.storeName()))
                    .append("/`) — store \"")
                    .append(mount.storeName())
                    .append("\", storeId=")
                    .append(mount.storeId())
                    .append(", access=")
                    .append(mount.accessMode())
                    .append("\n");
        }
        return sb.toString();
    }

    public List<MemoryStoreFilesystem> createResolvedFilesystems(
            io.agentscope.builder.control.ControlPlaneClient client,
            String sessionId,
            String ownerId,
            List<Map<String, Object>> mounts,
            Map<String, String> access) {
        List<MemoryStoreFilesystem> result = new ArrayList<>();
        java.util.Set<String> routes = new java.util.HashSet<>();
        if (mounts == null) return result;
        for (Map<String, Object> mount : mounts) {
            String id = (String) mount.get("storeId");
            String name = (String) mount.get("name");
            if (id == null || name == null)
                throw new IllegalArgumentException("Invalid memory mount");
            if (!routes.add(MemoryStoreFilesystem.routePrefix(name)))
                throw new IllegalArgumentException("Memory mount names collide: " + name);
            result.add(
                    new MemoryStoreFilesystem(
                            client.memoryDocuments(sessionId, id),
                            ownerId,
                            id,
                            name,
                            access.get(id)));
        }
        return result;
    }

    /**
     * @deprecated 不再物化到宿主磁盘；记忆存储通过 {@link #createFilesystems} 实时挂载。仅为迁移中的调用方保持编译兼容，委托给 {@link #resolveMounts}。
     */
    @Deprecated
    public List<MountInfo> materialize(
            String ownerId, Path workspace, List<String> memoryStoreIds) {
        return resolveMounts(ownerId, memoryStoreIds);
    }

    /**
     * @deprecated 回写是空操作：{@link MemoryStoreFilesystem} 在每次工具调用中直接写入 {@link MemoryStoreService}，轮次结束后没有待协调内容。
     */
    @Deprecated
    public void writeback(String ownerId, Path workspace, List<String> memoryStoreIds) {
        // 空操作：MemoryStoreFilesystem 立即直写。
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "store";
        }
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * 单个已挂载记忆存储的描述。
     */
    public record MountInfo(
            String storeId, String storeName, String relativePath, String accessMode) {
        public MountInfo(String storeId, String storeName, String relativePath) {
            this(storeId, storeName, relativePath, "read_write");
        }
    }
}
