/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.spring.boot.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Chat Completions Web Starter 的配置属性。
 *
 * <p>配置示例（session-manager 为上游示例中的预留配置，当前属性类仅定义 enabled 和 base-path）：
 *
 * <pre>{@code
 * agentscope:
 *   chat-completions:
 *     enabled: true
 *     base-path: /v1/chat/completions
 *     session-manager:
 *       type: in-memory  # 选项：in-memory、redis、mysql（未来规划）
 * }</pre>
 */
@ConfigurationProperties(prefix = "agentscope.chat-completions")
public class ChatCompletionsProperties {

    /** 是否启用聊天补全 HTTP API。 */
    private boolean enabled = true;

    /** 聊天补全端点的基础路径。 */
    private String basePath = "/v1/chat/completions";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }
}
