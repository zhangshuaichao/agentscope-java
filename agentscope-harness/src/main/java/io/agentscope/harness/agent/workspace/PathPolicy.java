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
package io.agentscope.harness.agent.workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 不可变的主机目录允许列表，用于限定 {@link LocalFsMode#ROOTED} 接受的绝对路径。
 * 每个根目录都保存为规范化的绝对路径，以便低成本检查子路径关系。
 *
 * <p>Claude Code 风格的双层本地文件系统使用
 * {@code PathPolicy.of(project, workspace,
 * additionalRoots)} 限定智能体可访问范围，
 * 但不强制将其放入单个硬沙箱。
 *
 * <p>空策略（{@link #empty()}）拒绝所有绝对路径；仅在调用方有意退回只允许相对路径访问时使用。
 */
public final class PathPolicy {

    private static final PathPolicy EMPTY = new PathPolicy(List.of());

    private final List<Path> roots;

    private PathPolicy(List<Path> roots) {
        this.roots = List.copyOf(roots);
    }

    /** 返回不允许任何绝对路径的策略。 */
    public static PathPolicy empty() {
        return EMPTY;
    }

    /** 从一个或多个根目录构建策略。跳过 {@code null} 条目，其余根目录转换为规范化绝对路径。 */
    public static PathPolicy of(Path first, Path... rest) {
        List<Path> all = new ArrayList<>();
        addNormalized(all, first);
        if (rest != null) {
            for (Path r : rest) {
                addNormalized(all, r);
            }
        }
        return all.isEmpty() ? EMPTY : new PathPolicy(all);
    }

    /** 从根目录集合构建策略，跳过 {@code null} 条目。 */
    public static PathPolicy of(Collection<Path> roots) {
        if (roots == null || roots.isEmpty()) {
            return EMPTY;
        }
        List<Path> all = new ArrayList<>();
        for (Path r : roots) {
            addNormalized(all, r);
        }
        return all.isEmpty() ? EMPTY : new PathPolicy(all);
    }

    private static void addNormalized(List<Path> out, Path p) {
        if (p == null) {
            return;
        }
        out.add(p.toAbsolutePath().normalize());
    }

    /** 按插入顺序返回配置的根目录。 */
    public List<Path> roots() {
        return roots;
    }

    /** 未配置根目录时为 {@code true}。 */
    public boolean isEmpty() {
        return roots.isEmpty();
    }

    /**
     * 返回 {@code true} 表示 {@code candidate} 等于某个已配置根目录或位于其下。
     * 调用方必须传入绝对路径；相对路径返回 {@code false}。
     */
    public boolean isAllowed(Path candidate) {
        if (candidate == null || !candidate.isAbsolute()) {
            return false;
        }
        Path normalized = candidate.normalize();
        for (Path root : roots) {
            if (normalized.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PathPolicy other && Objects.equals(roots, other.roots);
    }

    @Override
    public int hashCode() {
        return roots.hashCode();
    }

    @Override
    public String toString() {
        return "PathPolicy" + roots;
    }
}
