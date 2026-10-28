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

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * 执行环境模板的 API 表示。
 * <p>{@code type} 可为 {@link EnvironmentTypes#TYPE_LOCAL}（宿主文件系统）、{@link
 * EnvironmentTypes#TYPE_SANDBOX}（等效于云环境的 Docker Hands）、{@link
 * EnvironmentTypes#TYPE_REMOTE}（分布式 KV 文件系统，无 shell），或 {@link
 * EnvironmentTypes#TYPE_SELF_HOSTED}（通过 {@code externalSandbox} 使用工作进程拥有的 Hands）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EnvironmentDto(
        String id,
        String name,
        String type,
        Map<String, Object> config,
        String ownerId,
        Long archivedAt,
        long createdAt,
        long updatedAt,
        String environmentKey) {}
