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
package io.agentscope.builder.web.catalog;

import java.util.List;
import java.util.Optional;

/**
 * 控制平面中智能体<em>定义</em>文件（技能、可选工作区资产、暂存资源）的持久化接口，
 * 独立于会话 Hands 的 {@code AbstractFilesystem}。
 * <p>结构化配置（{@code tools[]} / {@code mcpServers[]} / 技能引用）仍保存在数据库版本快照中；
 * 此存储保存文件内容，使任意控制平面副本都能构建 {@code HarnessAgent}，不依赖特定本地磁盘。
 */
public interface DefinitionStore {

    /** 在智能体定义命名空间下写入或替换 UTF-8 文本文件。 */
    void putText(String ownerId, String agentId, String relativePath, String content);

    /** 读取 UTF-8 文本文件，不存在时返回空。 */
    Optional<String> getText(String ownerId, String agentId, String relativePath);

    /** 删除单个文件键。忽略不存在的键。 */
    void delete(String ownerId, String agentId, String relativePath);

    /**
     * 删除相对路径等于 {@code prefix} 或以 {@code
     * prefix + "/"} 开头的所有文件。
     */
    void deletePrefix(String ownerId, String agentId, String prefix);

    /** 列出 {@code prefix} 下的相对路径（包括嵌套文件）。{@code prefix} 为空白时列出整个智能体定义树。 */
    List<String> list(String ownerId, String agentId, String prefix);
}
