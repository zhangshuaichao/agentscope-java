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
package io.agentscope.builder.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 托管环境 {@code type=sandbox}（E2B 云）的全局默认值。单个环境的 {@code config} 可覆盖这些值。若不在环境配置中内嵌密钥，请设置 {@code BUILDER_E2B_API_KEY} 或 {@code E2B_API_KEY}。 */
@ConfigurationProperties(prefix = "builder.e2b")
public class BuilderE2bProperties {

    /** E2B API 密钥（优先的全局来源）。 */
    private String apiKey = "";

    /** 环境配置未指定 {@code templateId} 时的默认 E2B 模板 ID。 */
    private String templateId = "base";

    /** E2B 沙箱中的默认工作区根目录。 */
    private String workspaceRoot = "/home/user";

    /** 默认沙箱存活时间/空闲超时秒数。 */
    private int sandboxTimeoutSeconds = 300;

    /** 可选 API 基础 URL 覆盖值（默认值来自 E2B 扩展）。 */
    private String apiBaseUrl = "";

    /** 可选域名覆盖值（默认值来自 E2B 扩展）。 */
    private String domain = "";

    /** {@code TAR} 或 {@code NATIVE_SNAPSHOT}。 */
    private String persistenceMode = "TAR";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public int getSandboxTimeoutSeconds() {
        return sandboxTimeoutSeconds;
    }

    public void setSandboxTimeoutSeconds(int sandboxTimeoutSeconds) {
        this.sandboxTimeoutSeconds = sandboxTimeoutSeconds;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getPersistenceMode() {
        return persistenceMode;
    }

    public void setPersistenceMode(String persistenceMode) {
        this.persistenceMode = persistenceMode;
    }
}
