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
package io.agentscope.extensions.controlplane.model;

import io.agentscope.controlplane.proto.InventoryReport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实例级运行时资源清单：子智能体、工作区和健康状态。连接后立即上报，之后按一级上报节奏更新。
 */
public record Inventory(
        List<SubagentInfo> subagents, List<WorkspaceInfo> workspaces, InstanceHealth health) {

    public static Inventory healthOnly(int activeSessions) {
        return new Inventory(List.of(), List.of(), new InstanceHealth(true, "", activeSessions));
    }

    public InventoryReport toProto() {
        InventoryReport.Builder b = InventoryReport.newBuilder();
        for (SubagentInfo s : subagents) {
            b.addSubagents(
                    io.agentscope.controlplane.proto.SubagentInfo.newBuilder()
                            .setName(nullToEmpty(s.name()))
                            .setDescription(nullToEmpty(s.description()))
                            .addAllTools(s.tools() == null ? List.of() : s.tools())
                            .setWorkspaceMode(nullToEmpty(s.workspaceMode()))
                            .setUrl(nullToEmpty(s.url()))
                            .setInvokeCount(s.invokeCount())
                            .setLastInvokedAt(s.lastInvokedAt())
                            .build());
        }
        for (WorkspaceInfo w : workspaces) {
            b.addWorkspaces(
                    io.agentscope.controlplane.proto.WorkspaceInfo.newBuilder()
                            .setPath(nullToEmpty(w.path()))
                            .setMode(nullToEmpty(w.mode()))
                            .setSizeBytes(w.sizeBytes())
                            .setOwnerRef(nullToEmpty(w.ownerRef()))
                            .build());
        }
        if (health != null) {
            b.setHealth(
                    io.agentscope.controlplane.proto.InstanceHealth.newBuilder()
                            .setHealthy(health.healthy())
                            .setReason(nullToEmpty(health.reason()))
                            .setActiveSessions(health.activeSessions())
                            .build());
        }
        return b.build();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
 * 可由此实例访问的子智能体。
 */
    public record SubagentInfo(
            String name,
            String description,
            List<String> tools,
            String workspaceMode,
            String url,
            long invokeCount,
            long lastInvokedAt) {

        public Map<String, Object> toJsonMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", nullToEmpty(name));
            if (description != null && !description.isEmpty()) {
                out.put("description", description);
            }
            if (tools != null && !tools.isEmpty()) {
                out.put("tools", tools);
            }
            if (workspaceMode != null && !workspaceMode.isEmpty()) {
                out.put("workspaceMode", workspaceMode);
            }
            if (url != null && !url.isEmpty()) {
                out.put("url", url);
            }
            if (invokeCount > 0) {
                out.put("invokeCount", invokeCount);
            }
            if (lastInvokedAt > 0) {
                out.put("lastInvokedAt", lastInvokedAt);
            }
            return out;
        }
    }

    /**
 * 此实例挂载的工作区。
 */
    public record WorkspaceInfo(String path, String mode, long sizeBytes, String ownerRef) {

        public Map<String, Object> toJsonMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", nullToEmpty(path));
            out.put("mode", nullToEmpty(mode));
            if (sizeBytes > 0) {
                out.put("sizeBytes", sizeBytes);
            }
            if (ownerRef != null && !ownerRef.isEmpty()) {
                out.put("ownerRef", ownerRef);
            }
            return out;
        }
    }

    /**
 * 此数据平面实例的存活状态。
 */
    public record InstanceHealth(boolean healthy, String reason, int activeSessions) {}
}
