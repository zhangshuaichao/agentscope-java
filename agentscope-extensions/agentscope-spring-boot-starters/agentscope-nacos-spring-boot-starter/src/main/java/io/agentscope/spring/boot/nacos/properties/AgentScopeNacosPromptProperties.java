/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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

package io.agentscope.spring.boot.nacos.properties;

import io.agentscope.spring.boot.nacos.constants.NacosConstants;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AgentScope Nacos Prompt 集成配置属性。
 *
 * <p>允许从 Nacos 驱动系统提示。Nacos 中的提示优先于 YAML 定义的 {@code agentscope.agent.sys-prompt}，YAML 值仍可作为默认回退。
 *
 * <p>配置示例：
 * <pre>{@code
 * agentscope:
 *   agent:
 *     enabled: true
 *     name: "Assistant"
 *     sys-prompt: "You are a helpful AI assistant."
 *
 *   nacos:
 *     server-addr: 127.0.0.1:8848
 *     namespace: public
 *
 *     prompt:
 *       enabled: true
 *       sys-prompt-key: agent-main
 *       version: "1.0.0"
 *       label: "prod"
 *       variables:
 *         env: prod
 *         app: order-service
 * }</pre>
 */
@ConfigurationProperties(prefix = NacosConstants.NACOS_PROMPT_PREFIX)
public class AgentScopeNacosPromptProperties extends BaseNacosProperties {

    /**
     * 是否启用 Nacos 提示集成。
     */
    private boolean enabled = true;

    /**
     * 用于在 Nacos 定位系统提示的 promptKey。
     */
    private String sysPromptKey;

    /**
     * 目标提示版本（例如 "1.0.0"），与 {@link #label} 互斥。两者均为 null 时使用最新版本。
     */
    private String version;

    /**
     * 目标提示标签（例如 "prod"、"staging"），与 {@link #version} 互斥。两者均为 null 时使用最新版本。
     */
    private String label;

    /**
     * 用于渲染 Nacos 提示中 {{}} 占位符的模板变量。
     */
    private Map<String, String> variables = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSysPromptKey() {
        return sysPromptKey;
    }

    public void setSysPromptKey(String sysPromptKey) {
        this.sysPromptKey = sysPromptKey;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Map<String, String> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, String> variables) {
        this.variables = variables;
    }
}
