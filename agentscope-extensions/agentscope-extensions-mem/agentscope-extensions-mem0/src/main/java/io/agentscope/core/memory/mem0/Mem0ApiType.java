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
package io.agentscope.core.memory.mem0;

/**
 * Mem0 API 部署类型枚举。
 *
 * <p>两类部署使用不同端点与认证方式：
 * <ul><li><b>PLATFORM</b>：官方云服务，端点 /v1/memories/ 与 /v2/memories/search/，
 * 使用 {@code Authorization: Token <apiKey>} 请求头。</li>
 * <li><b>SELF_HOSTED</b>：自托管服务，端点 /memories 与 /search，
 * 使用 {@code X-API-Key: <apiKey>} 请求头。</li></ul>
 */
public enum Mem0ApiType {
    /** 平台 Mem0，默认值，使用官方云服务。 */
    PLATFORM,

    /** 自托管 Mem0，使用用户部署的服务。 */
    SELF_HOSTED;

    /**
     * 将字符串解析为 Mem0ApiType，不区分大小写。
     *
     * @param value 待解析字符串
     * @return 对应类型，null、空或无效时返回 PLATFORM
     */
    public static Mem0ApiType fromString(String value) {
        if (value == null || value.isEmpty()) {
            return PLATFORM;
        }
        String normalized = value.toLowerCase().replace("_", "-");
        if ("self-hosted".equals(normalized) || "selfhosted".equals(normalized)) {
            return SELF_HOSTED;
        }
        return PLATFORM; // 默认使用 PLATFORM。
    }
}
