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
package io.agentscope.spring.boot.admin.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AgentScope 管理／运维 starter 配置属性。
 *
 * <p>须显式启用，设置 {@code agentscope.admin.enabled=true} 才激活。写操作另受 {@code agentscope.admin.write-enabled} 门控，使生产部署默认保持只读安全边界。
 */
@ConfigurationProperties(prefix = "agentscope.admin")
public class AdminProperties {

    /**
 * 总开关，false 时不注册管理 bean、控制器或端点。
 */
    private boolean enabled = false;

    /**
     * 是否接受写操作（压缩、中止、重置、分叉、排空、关闭等）。false 时只读检查端点仍可工作。
     */
    private boolean writeEnabled = false;

    /**
 * 数据平面 REST 控制器基础路径，移除尾部斜杠。
 */
    private String basePath = "/v1/admin";

    /**
     * 可选共享秘密令牌，每次写请求须通过 {@code X-Agentscope-Admin-Token} 请求头提供。为空时不校验令牌，依赖外围安全层。生产环境强烈建议配置。
     */
    private String writeToken = "";

    /**
 * 压缩会话时原样保留的最大消息数量。
 */
    private int compactKeepLastMessages = 2;

    /**
 * 是否向 Spring {@code ApplicationEventPublisher} 广播 {@code AdminAuditEvent}。
 */
    private boolean publishAuditEvents = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isWriteEnabled() {
        return writeEnabled;
    }

    public void setWriteEnabled(boolean writeEnabled) {
        this.writeEnabled = writeEnabled;
    }

    public String getBasePath() {
        String trimmed = basePath == null ? "/v1/admin" : basePath.trim();
        if (trimmed.isEmpty()) {
            return "/v1/admin";
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public String getWriteToken() {
        return writeToken == null ? "" : writeToken;
    }

    public void setWriteToken(String writeToken) {
        this.writeToken = writeToken;
    }

    public int getCompactKeepLastMessages() {
        return Math.max(0, compactKeepLastMessages);
    }

    public void setCompactKeepLastMessages(int compactKeepLastMessages) {
        this.compactKeepLastMessages = compactKeepLastMessages;
    }

    public boolean isPublishAuditEvents() {
        return publishAuditEvents;
    }

    public void setPublishAuditEvents(boolean publishAuditEvents) {
        this.publishAuditEvents = publishAuditEvents;
    }
}
