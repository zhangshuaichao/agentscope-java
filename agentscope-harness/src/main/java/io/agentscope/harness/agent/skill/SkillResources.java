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
package io.agentscope.harness.agent.skill;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 单个技能的懒加载资源访问器。
 *
 * <p>将“有哪些资源”与“如何获取内容”解耦，使基于 {@code AbstractFilesystem} 的仓库能按需响应 {@code load_skill_through_path}，无需在注册时把所有技能的每个字节预加载到内存。
 *
 * <p>全部路径都相对于技能根目录（例如 {@code "references/guide.md"}、{@code "scripts/run.py"}）。实现会拒绝绝对路径及路径穿越序列（{@code ".."}）。
 */
public interface SkillResources {

    /**
     * 按 UTF-8 读取文本资源。
     *
     * @param relativePath 相对于技能根目录的路径
     * @return 内容；资源不存在或读取失败时为空
     */
    Optional<String> read(String relativePath);

    /**
     * 读取二进制资源。
     *
     * @param relativePath 相对于技能根目录的路径
     * @return 内容；资源不存在或读取失败时为空
     */
    Optional<byte[]> readBinary(String relativePath);

    /**
     * 列出此访问器可提供的全部相对资源路径。
     *
     * <p>{@code SkillLoadTool} 在请求路径不存在时，用它构建友好的“可用资源”列表。
     *
     * @return 不可修改的相对路径列表，不会为 {@code null}
     */
    List<String> list();

    /**
     * 返回不提供任何资源的空操作访问器。
     *
     * @return 共享的空实例
     */
    static SkillResources empty() {
        return EmptySkillResources.INSTANCE;
    }
}

final class EmptySkillResources implements SkillResources {

    static final EmptySkillResources INSTANCE = new EmptySkillResources();

    private EmptySkillResources() {}

    @Override
    public Optional<String> read(String relativePath) {
        return Optional.empty();
    }

    @Override
    public Optional<byte[]> readBinary(String relativePath) {
        return Optional.empty();
    }

    @Override
    public List<String> list() {
        return Collections.emptyList();
    }
}
