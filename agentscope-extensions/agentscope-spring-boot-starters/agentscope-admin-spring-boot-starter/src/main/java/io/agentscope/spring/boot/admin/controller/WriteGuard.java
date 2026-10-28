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
package io.agentscope.spring.boot.admin.controller;

import io.agentscope.spring.boot.admin.properties.AdminProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 写操作的前置门控。
 *
 * <p>请求必须拒绝时抛出 {@link ResponseStatusException}。设计为每个写控制器方法的第一条语句，集中失败边界。
 *
 * <p>【学习提示】先检查 writeEnabled，再校验非空的已配置令牌；令牌配置为空时不进行令牌验证。启用写能力并不自动获得完整认证机制，外层仍需访问控制。
 */
final class WriteGuard {

    private final AdminProperties properties;

    WriteGuard(AdminProperties properties) {
        this.properties = properties;
    }

    /**
     * @param suppliedToken {@code X-Agentscope-Admin-Token} 请求头值，可为 null
     */
    void check(String suppliedToken) {
        if (!properties.isWriteEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "agentscope.admin.write-enabled is false; admin write operations are disabled");
        }
        String expected = properties.getWriteToken();
        if (!expected.isEmpty() && !expected.equals(suppliedToken)) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "missing or invalid X-Agentscope-Admin-Token header");
        }
    }
}
