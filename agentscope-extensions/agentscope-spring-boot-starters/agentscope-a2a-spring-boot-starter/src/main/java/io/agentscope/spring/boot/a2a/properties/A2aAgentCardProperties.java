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

package io.agentscope.spring.boot.a2a.properties;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A2A 服务端智能体卡片配置。
 *
 * <p>嵌套值使用本 starter 自身的类型，而非 A2A SDK 类型，使 SDK 类型变化时配置仍可绑定。属性名遵循 A2A 智能体卡片字段。
 */
@ConfigurationProperties(Constants.A2A_SERVER_CARD_PREFIX)
public class A2aAgentCardProperties {

    private String name;

    private String description;

    private String url;

    private Provider provider;

    private String version;

    private String documentationUrl;

    private List<String> defaultInputModes;

    private List<String> defaultOutputModes;

    private List<Skill> skills;

    private Map<String, SecurityScheme> securitySchemes;

    private List<Map<String, List<String>>> security;

    private String iconUrl;

    private List<AgentInterface> additionalInterfaces;

    private String preferredTransport;
    ;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDocumentationUrl() {
        return documentationUrl;
    }

    public void setDocumentationUrl(String documentationUrl) {
        this.documentationUrl = documentationUrl;
    }

    public List<String> getDefaultInputModes() {
        return defaultInputModes;
    }

    public void setDefaultInputModes(List<String> defaultInputModes) {
        this.defaultInputModes = defaultInputModes;
    }

    public List<String> getDefaultOutputModes() {
        return defaultOutputModes;
    }

    public void setDefaultOutputModes(List<String> defaultOutputModes) {
        this.defaultOutputModes = defaultOutputModes;
    }

    public List<Skill> getSkills() {
        return skills;
    }

    public void setSkills(List<Skill> skills) {
        this.skills = skills;
    }

    public Map<String, SecurityScheme> getSecuritySchemes() {
        return securitySchemes;
    }

    public void setSecuritySchemes(Map<String, SecurityScheme> securitySchemes) {
        this.securitySchemes = securitySchemes;
    }

    public List<Map<String, List<String>>> getSecurity() {
        return security;
    }

    public void setSecurity(List<Map<String, List<String>>> security) {
        this.security = security;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public void setIconUrl(String iconUrl) {
        this.iconUrl = iconUrl;
    }

    public List<AgentInterface> getAdditionalInterfaces() {
        return additionalInterfaces;
    }

    public void setAdditionalInterfaces(List<AgentInterface> additionalInterfaces) {
        this.additionalInterfaces = additionalInterfaces;
    }

    public String getPreferredTransport() {
        return preferredTransport;
    }

    public void setPreferredTransport(String preferredTransport) {
        this.preferredTransport = preferredTransport;
    }

    /**
     * 提供智能体的组织。
     *
     * @param organization 组织名称
     * @param url 组织网站
     */
    public record Provider(String organization, String url) {}

    /**
     * 智能体可以执行的技能。
     *
     * @param id 唯一技能标识
     * @param name 技能名称
     * @param description 技能描述
     * @param tags 描述技能的关键词
     * @param examples 提示词示例
     * @param inputModes 支持的输入媒体类型，覆盖卡片默认值
     * @param outputModes 支持的输出媒体类型，覆盖卡片默认值
     * @param security 此技能的安全要求
     */
    public record Skill(
            String id,
            String name,
            String description,
            List<String> tags,
            List<String> examples,
            List<String> inputModes,
            List<String> outputModes,
            List<Map<String, List<String>>> security) {}

    /**
     * 智能体卡片声明的安全方案。适用字段取决于 {@code type}：
     *
     * <ul>
     *   <li>{@code apiKey}：{@code name} 与 {@code in}（{@code header}、{@code query} 或 {@code cookie}）
     *   <li>{@code http}：{@code scheme}，以及可选的 {@code bearerFormat}
     *   <li>{@code oauth2}：{@code flows}，以及可选的 {@code oauth2MetadataUrl}
     *   <li>{@code openIdConnect}：{@code openIdConnectUrl}
     *   <li>{@code mutualTLS}：无其他字段
     * </ul>
     *
     * @param type 方案类型
     * @param description 方案描述
     * @param name 携带 API 密钥的请求头、查询参数或 cookie 名称
     * @param in API 密钥的位置
     * @param scheme HTTP 认证方案，如 {@code bearer}
     * @param bearerFormat bearer 令牌格式，如 {@code JWT}
     * @param flows OAuth 2.0 流程
     * @param oauth2MetadataUrl OAuth 2.0 授权服务器元数据 URL
     * @param openIdConnectUrl OpenID Connect 发现 URL
     */
    public record SecurityScheme(
            String type,
            String description,
            String name,
            String in,
            String scheme,
            String bearerFormat,
            OAuthFlows flows,
            String oauth2MetadataUrl,
            String openIdConnectUrl) {}

    /**
     * {@code oauth2} 安全方案的 OAuth 2.0 流程。
     *
     * @param authorizationCode 授权码流程
     * @param clientCredentials 客户端凭据流程
     * @param deviceCode 设备授权流程
     * @param implicit 不支持：A2A 1.0 没有隐式流程，配置后将导致启动失败
     * @param password 不支持：A2A 1.0 没有密码流程，配置后将导致启动失败
     */
    public record OAuthFlows(
            OAuthFlow authorizationCode,
            OAuthFlow clientCredentials,
            OAuthFlow deviceCode,
            OAuthFlow implicit,
            OAuthFlow password) {}

    /**
     * 单个 OAuth 2.0 流程，适用字段取决于流程类型。
     *
     * @param authorizationUrl 授权码流程的授权 URL
     * @param tokenUrl 令牌 URL
     * @param refreshUrl 刷新 URL
     * @param deviceAuthorizationUrl 设备码流程的设备授权 URL
     * @param pkceRequired 授权码流程是否要求 PKCE
     * @param scopes 可用作用域及其描述
     */
    public record OAuthFlow(
            String authorizationUrl,
            String tokenUrl,
            String refreshUrl,
            String deviceAuthorizationUrl,
            Boolean pkceRequired,
            Map<String, String> scopes) {}

    /**
     * 可访问智能体的传输接口。
     *
     * @param transport 传输协议，如 {@code JSONRPC}
     * @param url 端点 URL
     */
    public record AgentInterface(String transport, String url) {}
}
