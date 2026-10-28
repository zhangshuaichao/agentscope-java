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
import java.util.List;
import java.util.Map;

/** 托管智能体会话的 API 表示。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ManagedSessionDto(
        String id,
        String ownerId,
        String agentId,
        String agentOwnerId,
        Integer agentVersion,
        String agentRefType,
        String agentOverridesJson,
        String environmentId,
        /** 此会话对应对话的稳定身份；普通聊天界面 / API 会话为 {@code null}。团队成员会话由控制平面分配，使用 {@code team|{namespace}/{team}|{member}} 键。 */
        String externalKey,
        List<String> memoryStoreIds,
        List<String> vaultIds,
        List<Map<String, Object>> resources,
        String status,
        Map<String, Object> stopReason,
        long createdAt,
        long updatedAt,
        Long archivedAt) {}
