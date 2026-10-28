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
package io.agentscope.extensions.sandbox.e2b;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.WorkspaceMountSupport;
import java.util.List;
import java.util.Objects;

/**
 * 将既有 E2B 卷挂载到沙箱。
 *
 * <p>卷独立于沙箱生命周期存在，创建沙箱时通过 {@code POST /sandboxes} 上的 {@code volumeMounts: [{name, path}]} 附加。卷生命周期操作（创建/删除/列举、内容读写）仍由 E2B 控制台/SDK 负责；此类仅描述创建时挂载。
 *
 * <p>两个字段与 E2B 的 {@code SandboxVolumeMount} 一一对应：{@code name} 为既有卷名，{@code path} 为沙箱内绝对挂载路径。E2B 卷可读写，平台未定义挂载路径前缀允许列表，因此仅校验 {@code name} 非空白且 {@code path} 为绝对路径。
 *
 * <p>忽略未知 JSON 属性：{@code E2bSandboxState} 上的 {@code @JsonIgnoreProperties} 不覆盖此嵌套类型，未来挂载条目新增字段不应导致整个持久状态反序列化失败。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class E2bVolumeMount {

    private String name;
    private String path;

    /**
     * 默认构造器。
     */
    public E2bVolumeMount() {}

    /**
     * 创建卷挂载。
     *
     * @param name 既有 E2B 卷名
     * @param path 沙箱内绝对挂载路径
     */
    public E2bVolumeMount(String name, String path) {
        setName(name);
        setPath(path);
    }

    /**
     * 返回既有 E2B 卷名。
     *
     * @return 卷名
     */
    public String getName() {
        return name;
    }

    public E2bVolumeMount setName(String name) {
        if (name == null || name.isBlank()) {
            throw new SandboxException.SandboxConfigurationException(
                    "E2B volume mount name must be set (name of an existing volume)");
        }
        this.name = name.strip();
        return this;
    }

    /**
     * 返回规范化的沙箱内绝对挂载路径。
     *
     * @return 绝对挂载路径（合并重复斜杠，根目录以外不带尾部斜杠）
     */
    public String getPath() {
        return path;
    }

    public E2bVolumeMount setPath(String path) {
        this.path = normalizeMountPath(path);
        return this;
    }

    /**
     * 规范化沙箱内挂载路径：去除首尾空白、将反斜杠转为斜杠、合并重复斜杠并移除尾部斜杠（根目录 {@code /} 本身除外），复用 {@link WorkspaceMountSupport#normalizeVirtualPath} 的统一规范化逻辑。
     *
     * @param path 原始挂载路径
     * @return 规范化绝对路径
     * @throws SandboxException.SandboxConfigurationException 路径非绝对路径时抛出
     */
    public static String normalizeMountPath(String path) {
        if (path == null || path.isBlank()) {
            throw new SandboxException.SandboxConfigurationException(
                    "E2B volume mount path must be set (absolute in-sandbox path, e.g. /mnt/data)");
        }
        String normalized = WorkspaceMountSupport.normalizeVirtualPath(path);
        if (!normalized.startsWith("/")) {
            throw new SandboxException.SandboxConfigurationException(
                    "E2B volume mount path must be absolute but was: " + path);
        }
        return normalized;
    }

    /**
     * 返回 {@code workspaceRoot} 是否位于给定挂载上，即等于挂载路径或严格位于其下。尾部斜杠检查防止 {@code /mnt/data-x} 错配 {@code /mnt/data} 挂载。
     *
     * @param workspaceRoot 待测工作区根目录（可为空白）
     * @param mountPath 规范化挂载路径（可为空白）
     * @return 工作区根目录位于挂载上时为 true
     */
    public static boolean coversWorkspaceRoot(String workspaceRoot, String mountPath) {
        if (workspaceRoot == null
                || workspaceRoot.isBlank()
                || mountPath == null
                || mountPath.isBlank()) {
            return false;
        }
        String root = normalizeMountPath(workspaceRoot);
        String mount = normalizeMountPath(mountPath);
        if (mount.equals("/")) {
            return true;
        }
        return root.equals(mount) || root.startsWith(mount + "/");
    }

    /**
     * 验证没有两个挂载使用同一沙箱内路径。
     *
     * @param mounts 待检查挂载（忽略 null 条目）
     * @throws SandboxException.SandboxConfigurationException 路径重复时抛出
     */
    public static void requireDistinctMountPaths(List<E2bVolumeMount> mounts) {
        if (mounts == null) {
            return;
        }
        for (int i = 0; i < mounts.size(); i++) {
            if (mounts.get(i) == null) {
                continue;
            }
            for (int j = i + 1; j < mounts.size(); j++) {
                if (mounts.get(j) != null
                        && mounts.get(i).getPath().equals(mounts.get(j).getPath())) {
                    throw new SandboxException.SandboxConfigurationException(
                            "E2B volume mounts must use distinct paths, duplicated: "
                                    + mounts.get(i).getPath());
                }
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof E2bVolumeMount other)) {
            return false;
        }
        return Objects.equals(name, other.name) && Objects.equals(path, other.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, path);
    }

    @Override
    public String toString() {
        return "E2bVolumeMount{name='" + name + "', path='" + path + "'}";
    }
}
