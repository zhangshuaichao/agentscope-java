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
package io.agentscope.harness.agent.artifact;

/**
 * {@code deliver_artifact} 工具发起的一次产物交付请求。
 *
 * <p>工具从智能体文件系统下载文件字节，交给已配置的 {@link ArtifactDeliveryTarget}；
 * 后者负责存入目标位置，例如项目产物目录／产物库、WebDAV 或对象存储。
 *
 * @param filePath 智能体工作区中生成文件的路径，与其他文件工具采用相同规范化方式
 *      （相对于工作区，或在当前文件系统允许时使用绝对路径）
 * @param content 从文件系统下载的文件字节
 * @param fileName 交付后的目标文件名，默认是 {@code filePath} 的基础名称。
 *      始终是普通文件名；工具拒绝路径分隔符、{@code .} 和 {@code ..}，目标可直接解析
 * @param description 产物的简短说明，可以为 {@code null}
 * @param force 是否覆盖同名 {@code fileName} 的现有产物
 *      （默认为 {@code false}：目标拒绝交付，智能体应使用新的 {@code fileName} 重试）
 */
public record ArtifactDeliveryRequest(
        String filePath, byte[] content, String fileName, String description, boolean force) {}
