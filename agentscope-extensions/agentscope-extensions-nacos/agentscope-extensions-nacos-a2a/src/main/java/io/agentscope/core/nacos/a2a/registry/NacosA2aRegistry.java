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

package io.agentscope.core.nacos.a2a.registry;

import com.alibaba.nacos.api.ai.A2aService;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.constant.AiConstants;
import com.alibaba.nacos.api.ai.model.a2a.AgentCardDetailInfo;
import com.alibaba.nacos.api.ai.model.a2a.AgentEndpoint;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.exception.runtime.NacosRuntimeException;
import io.agentscope.core.nacos.a2a.utils.AgentCardConverterUtil;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 将 A2A 智能体卡片与 A2A 实例端点注册到 Nacos 的 AgentScope 扩展。 */
public class NacosA2aRegistry {

    private static final Logger log = LoggerFactory.getLogger(NacosA2aRegistry.class);

    private final A2aService a2aService;

    /**
     * 根据 Nacos 服务器属性创建 {@link NacosA2aRegistry} 实例。
     * @param nacosProperties Nacos 服务器属性
     * @throws NacosException 构建 Nacos 客户端失败时
     */
    public NacosA2aRegistry(Properties nacosProperties) throws NacosException {
        this(AiFactory.createAiService(nacosProperties));
    }

    /**
     * 根据 Nacos 客户端创建 {@link NacosA2aRegistry} 实例。
     * @param a2aService Nacos A2aService 实例，也可使用 {@link com.alibaba.nacos.api.ai.AiService}。
     */
    public NacosA2aRegistry(A2aService a2aService) {
        this.a2aService = a2aService;
    }

    /**
     * 将 A2A 智能体卡片和端点注册到 Nacos。
     * @param agentCard     待注册的智能体卡片
     * @param a2aProperties A2A 注册属性
     */
    public void registerAgent(AgentCard agentCard, NacosA2aRegistryProperties a2aProperties) {
        var nacosAgentCard = AgentCardConverterUtil.convertToNacosAgentCard(agentCard);
        try {
            tryReleaseAgentCard(nacosAgentCard, a2aProperties);
            registerEndpoint(nacosAgentCard, a2aProperties);
        } catch (NacosException e) {
            log.error("Register agent card {} to Nacos failed.", agentCard.name(), e);
            throw new NacosRuntimeException(e.getErrCode(), e.getErrMsg(), e);
        }
    }

    private void tryReleaseAgentCard(
            com.alibaba.nacos.api.ai.model.a2a.AgentCard agentCard,
            NacosA2aRegistryProperties a2aProperties)
            throws NacosException {
        if (null != tryGetAgentCardFromNacos(agentCard)) {
            log.warn(
                    "Agent card {} already exists, agentCard release might be ignored.",
                    agentCard.getName());
        }
        log.info("Register agent card {} to Nacos.", agentCard.getName());
        a2aService.releaseAgentCard(
                agentCard,
                AiConstants.A2a.A2A_ENDPOINT_TYPE_SERVICE,
                a2aProperties.isSetAsLatest());
        log.info("Register agent card {} to Nacos successfully.", agentCard.getName());
    }

    private AgentCardDetailInfo tryGetAgentCardFromNacos(
            com.alibaba.nacos.api.ai.model.a2a.AgentCard agentCard) throws NacosException {
        try {
            return a2aService.getAgentCard(agentCard.getName(), agentCard.getVersion());
        } catch (NacosException ignored) {
            return null;
        }
    }

    private void registerEndpoint(
            com.alibaba.nacos.api.ai.model.a2a.AgentCard agentCard,
            NacosA2aRegistryProperties a2aProperties)
            throws NacosException {
        if (!a2aProperties.enabledRegisterEndpoint()) {
            log.info("Disabled register endpoint(s) to Agent, skip endpoint(s) register step.");
            return;
        }
        if (a2aProperties.transportProperties().isEmpty()) {
            log.warn("No endpoint(s) found, skip endpoint(s) register step.");
            return;
        }
        log.info("Register {} endpoint(s) to Nacos", a2aProperties.transportProperties().size());
        if (a2aProperties.transportProperties().size() == 1) {
            AgentEndpoint endpoint =
                    buildAgentEndpoint(
                            a2aProperties.transportProperties().values().iterator().next(),
                            agentCard.getVersion());
            a2aService.registerAgentEndpoint(agentCard.getName(), endpoint);
        } else {
            Set<AgentEndpoint> endpoints =
                    a2aProperties.transportProperties().values().stream()
                            .map(
                                    transportProperties ->
                                            buildAgentEndpoint(
                                                    transportProperties, agentCard.getVersion()))
                            .collect(Collectors.toSet());
            a2aService.registerAgentEndpoint(agentCard.getName(), endpoints);
        }
    }

    private AgentEndpoint buildAgentEndpoint(
            NacosA2aRegistryTransportProperties transportProperties, String version) {
        AgentEndpoint result = new AgentEndpoint();
        result.setTransport(transportProperties.transport());
        result.setAddress(transportProperties.host());
        result.setPort(transportProperties.port());
        result.setPath(transportProperties.path());
        result.setSupportTls(transportProperties.supportTls());
        result.setVersion(version);
        result.setProtocol(transportProperties.protocol());
        result.setQuery(transportProperties.query());
        return result;
    }
}
