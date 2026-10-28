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
package io.agentscope.spring.boot.admin.endpoint;

import io.agentscope.spring.boot.admin.properties.AdminProperties;

/**
 * 对应 {@code controller.WriteGuard} 的 Actuator 写端点辅助类。
 *
 * <p>Actuator {@code @WriteOperation} 签名不暴露原始 HTTP 请求头，因此共享秘密通过名为 {@code token} 的正文参数传入，同时要求 {@code confirm} 参数为 {@code yes} 明确确认。生产环境仍强烈建议在管理端口的 {@code /actuator/**} 前配置 Spring Security。
 */
final class WriteGuardSupport {

    private WriteGuardSupport() {}

    static void check(AdminProperties properties, String token, String confirm) {
        if (!properties.isWriteEnabled()) {
            throw new IllegalStateException(
                    "agentscope.admin.write-enabled is false; admin write operations are disabled");
        }
        if (confirm == null || !"yes".equalsIgnoreCase(confirm.trim())) {
            throw new IllegalArgumentException(
                    "missing or invalid 'confirm' parameter; send confirm=yes to proceed");
        }
        String expected = properties.getWriteToken();
        if (!expected.isEmpty() && !expected.equals(token)) {
            throw new SecurityException("missing or invalid 'token' parameter");
        }
    }
}
