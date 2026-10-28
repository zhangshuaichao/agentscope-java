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

package io.agentscope.core.nacos.a2a.utils;

import com.alibaba.nacos.api.ai.model.a2a.AgentCapabilities;
import com.alibaba.nacos.api.ai.model.a2a.AgentCard;
import com.alibaba.nacos.api.ai.model.a2a.AgentExtension;
import com.alibaba.nacos.api.ai.model.a2a.AgentInterface;
import com.alibaba.nacos.api.ai.model.a2a.AgentProvider;
import com.alibaba.nacos.api.ai.model.a2a.AgentSkill;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.a2aproject.sdk.spec.SecurityRequirement;

/**
 * 在 Nacos {@link AgentCard} 与 A2A 规范 {@link org.a2aproject.sdk.spec.AgentCard} 之间转换智能体卡片。
 * <p>Nacos 模型遵循 A2A 0.3 卡片，A2A SDK 模型遵循 A2A 1.0：
 * <ul>
 *     <li>A2A 支持接口首先包含 Nacos 的 url 和首选传输，然后包含 Nacos 附加接口。
 *     Nacos 协议版本作为这些接口的版本。</li>
 *     <li>A2A 安全要求对应 Nacos security。</li>
 *     <li>A2A {@code capabilities.extendedAgentCard} 对应 Nacos
 *     {@code supportsAuthenticatedExtendedCard}。</li>
 *     <li>能力扩展保留 URI、描述、必需标志及参数。</li>
 *     <li>A2A 安全方案在 Nacos 中按 A2A 0.3 格式保存为映射，参见
 *     {@code SecuritySchemeConverterUtil}。</li>
 * </ul>
 * <p>【学习提示】转换安全方案描述不等于执行身份认证；真正的凭据提供与远端授权仍由调用链负责。
 */
public class AgentCardConverterUtil {

    /**
     * 将 Nacos AgentCard 转换为 A2A 规范 AgentCard。
     * @param agentCard Nacos AgentCard 对象
     * @return 转换后的 A2A 规范 AgentCard；输入为 null 时返回 null
     */
    public static org.a2aproject.sdk.spec.AgentCard convertToA2aAgentCard(AgentCard agentCard) {
        if (agentCard == null) {
            return null;
        }
        return org.a2aproject.sdk.spec.AgentCard.builder()
                .name(agentCard.getName())
                .description(agentCard.getDescription())
                .version(agentCard.getVersion())
                .iconUrl(agentCard.getIconUrl())
                .capabilities(
                        convertToA2aAgentCapabilities(
                                agentCard.getCapabilities(),
                                agentCard.getSupportsAuthenticatedExtendedCard()))
                .skills(
                        mapList(
                                agentCard.getSkills(),
                                AgentCardConverterUtil::convertToA2aAgentSkill))
                .supportedInterfaces(convertToA2aAgentInterfaces(agentCard))
                .provider(convertToA2aAgentProvider(agentCard.getProvider()))
                .documentationUrl(agentCard.getDocumentationUrl())
                .securitySchemes(
                        SecuritySchemeConverterUtil.convertToA2aSecuritySchemes(
                                agentCard.getSecuritySchemes()))
                .securityRequirements(convertToA2aSecurityRequirements(agentCard.getSecurity()))
                .defaultInputModes(agentCard.getDefaultInputModes())
                .defaultOutputModes(agentCard.getDefaultOutputModes())
                .build();
    }

    private static org.a2aproject.sdk.spec.AgentProvider convertToA2aAgentProvider(
            AgentProvider provider) {
        if (null == provider) {
            return null;
        }
        return new org.a2aproject.sdk.spec.AgentProvider(
                provider.getOrganization(), provider.getUrl());
    }

    private static List<org.a2aproject.sdk.spec.AgentInterface> convertToA2aAgentInterfaces(
            AgentCard agentCard) {
        String protocolVersion =
                null != agentCard.getProtocolVersion()
                        ? agentCard.getProtocolVersion()
                        : org.a2aproject.sdk.spec.AgentInterface.CURRENT_PROTOCOL_VERSION;
        List<org.a2aproject.sdk.spec.AgentInterface> result = new ArrayList<>();
        if (null != agentCard.getUrl()) {
            String transport =
                    null != agentCard.getPreferredTransport()
                            ? agentCard.getPreferredTransport()
                            : "JSONRPC";
            result.add(
                    new org.a2aproject.sdk.spec.AgentInterface(
                            transport, agentCard.getUrl(), null, protocolVersion));
        }
        if (null != agentCard.getAdditionalInterfaces()) {
            agentCard.getAdditionalInterfaces().stream()
                    .filter(Objects::nonNull)
                    .map(
                            each ->
                                    new org.a2aproject.sdk.spec.AgentInterface(
                                            each.getTransport(),
                                            each.getUrl(),
                                            null,
                                            protocolVersion))
                    .filter(each -> !result.contains(each))
                    .forEach(result::add);
        }
        return result;
    }

    private static org.a2aproject.sdk.spec.AgentCapabilities convertToA2aAgentCapabilities(
            AgentCapabilities nacosCapabilities, Boolean extendedAgentCard) {
        org.a2aproject.sdk.spec.AgentCapabilities.Builder builder =
                org.a2aproject.sdk.spec.AgentCapabilities.builder()
                        .extendedAgentCard(Boolean.TRUE.equals(extendedAgentCard));
        if (nacosCapabilities != null) {
            builder.streaming(Boolean.TRUE.equals(nacosCapabilities.getStreaming()))
                    .pushNotifications(
                            Boolean.TRUE.equals(nacosCapabilities.getPushNotifications()));
            if (nacosCapabilities.getExtensions() != null) {
                builder.extensions(
                        mapList(
                                nacosCapabilities.getExtensions(),
                                AgentCardConverterUtil::convertToA2aAgentExtension));
            }
        }
        return builder.build();
    }

    private static org.a2aproject.sdk.spec.AgentExtension convertToA2aAgentExtension(
            AgentExtension extension) {
        return org.a2aproject.sdk.spec.AgentExtension.builder()
                .uri(extension.getUri())
                .description(extension.getDescription())
                .required(Boolean.TRUE.equals(extension.getRequired()))
                .params(extension.getParams())
                .build();
    }

    private static org.a2aproject.sdk.spec.AgentSkill convertToA2aAgentSkill(
            AgentSkill nacosSkill) {
        return org.a2aproject.sdk.spec.AgentSkill.builder()
                .id(nacosSkill.getId())
                .tags(nacosSkill.getTags())
                .examples(nacosSkill.getExamples())
                .name(nacosSkill.getName())
                .description(nacosSkill.getDescription())
                .inputModes(nacosSkill.getInputModes())
                .outputModes(nacosSkill.getOutputModes())
                .build();
    }

    private static List<SecurityRequirement> convertToA2aSecurityRequirements(
            List<Map<String, List<String>>> security) {
        return null == security ? null : mapList(security, SecurityRequirement::new);
    }

    /**
     * 将 A2A 规范 AgentCard 转换为 Nacos AgentCard。
     * @param agentCard A2A 规范 AgentCard 对象
     * @return 转换后的 Nacos AgentCard
     */
    public static AgentCard convertToNacosAgentCard(org.a2aproject.sdk.spec.AgentCard agentCard) {
        AgentCard card = new AgentCard();
        List<org.a2aproject.sdk.spec.AgentInterface> interfaces = agentCard.supportedInterfaces();
        if (!interfaces.isEmpty()) {
            org.a2aproject.sdk.spec.AgentInterface preferred = interfaces.get(0);
            card.setProtocolVersion(preferred.protocolVersion());
            card.setUrl(preferred.url());
            card.setPreferredTransport(preferred.protocolBinding());
        }
        card.setAdditionalInterfaces(
                mapList(interfaces, AgentCardConverterUtil::convertToNacosAgentInterface));
        card.setName(agentCard.name());
        card.setDescription(agentCard.description());
        card.setVersion(agentCard.version());
        card.setIconUrl(agentCard.iconUrl());
        card.setCapabilities(convertToNacosAgentCapabilities(agentCard.capabilities()));
        card.setSkills(
                mapList(agentCard.skills(), AgentCardConverterUtil::convertToNacosAgentSkill));
        card.setProvider(convertToNacosAgentProvider(agentCard.provider()));
        card.setDocumentationUrl(agentCard.documentationUrl());
        card.setSecuritySchemes(
                SecuritySchemeConverterUtil.convertToNacosSecuritySchemes(
                        agentCard.securitySchemes()));
        card.setSecurity(
                null == agentCard.securityRequirements()
                        ? null
                        : mapList(agentCard.securityRequirements(), SecurityRequirement::schemes));
        card.setDefaultInputModes(agentCard.defaultInputModes());
        card.setDefaultOutputModes(agentCard.defaultOutputModes());
        card.setSupportsAuthenticatedExtendedCard(agentCard.capabilities().extendedAgentCard());
        return card;
    }

    private static AgentCapabilities convertToNacosAgentCapabilities(
            org.a2aproject.sdk.spec.AgentCapabilities capabilities) {
        AgentCapabilities nacosCapabilities = new AgentCapabilities();
        nacosCapabilities.setStreaming(capabilities.streaming());
        nacosCapabilities.setPushNotifications(capabilities.pushNotifications());
        // 不属于 A2A 1.0。
        nacosCapabilities.setStateTransitionHistory(false);
        if (capabilities.extensions() != null) {
            nacosCapabilities.setExtensions(
                    mapList(
                            capabilities.extensions(),
                            AgentCardConverterUtil::convertToNacosAgentExtension));
        }
        return nacosCapabilities;
    }

    private static AgentExtension convertToNacosAgentExtension(
            org.a2aproject.sdk.spec.AgentExtension extension) {
        AgentExtension nacosExtension = new AgentExtension();
        nacosExtension.setUri(extension.uri());
        nacosExtension.setDescription(extension.description());
        nacosExtension.setRequired(extension.required());
        nacosExtension.setParams(extension.params());
        return nacosExtension;
    }

    private static AgentSkill convertToNacosAgentSkill(
            org.a2aproject.sdk.spec.AgentSkill agentSkill) {
        AgentSkill skill = new AgentSkill();
        skill.setId(agentSkill.id());
        skill.setName(agentSkill.name());
        skill.setDescription(agentSkill.description());
        skill.setTags(agentSkill.tags());
        skill.setExamples(agentSkill.examples());
        skill.setInputModes(agentSkill.inputModes());
        skill.setOutputModes(agentSkill.outputModes());
        return skill;
    }

    private static AgentInterface convertToNacosAgentInterface(
            org.a2aproject.sdk.spec.AgentInterface agentInterface) {
        AgentInterface nacosAgentInterface = new AgentInterface();
        nacosAgentInterface.setUrl(agentInterface.url());
        nacosAgentInterface.setTransport(agentInterface.protocolBinding());
        return nacosAgentInterface;
    }

    private static AgentProvider convertToNacosAgentProvider(
            org.a2aproject.sdk.spec.AgentProvider agentProvider) {
        if (null == agentProvider) {
            return null;
        }
        AgentProvider nacosAgentProvider = new AgentProvider();
        nacosAgentProvider.setOrganization(agentProvider.organization());
        nacosAgentProvider.setUrl(agentProvider.url());
        return nacosAgentProvider;
    }

    private static <T, R> List<R> mapList(List<T> values, Function<T, R> mapper) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).map(mapper).toList();
    }
}
