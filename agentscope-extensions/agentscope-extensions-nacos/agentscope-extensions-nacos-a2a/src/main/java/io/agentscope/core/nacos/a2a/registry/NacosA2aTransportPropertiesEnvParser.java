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

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.exception.runtime.NacosRuntimeException;
import io.agentscope.core.nacos.a2a.registry.constants.Constants;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 从环境变量解析 {@link NacosA2aRegistryTransportProperties}。
 * <p>A2A 传输属性环境变量以 {@link Constants#PROPERTIES_ENV_PREFIX} 为前缀，
 * 后接 {TRANSPORT} 和 {ATTRIBUTE}，例如 NACOS_A2A_AGENT_JSONRPC_HOST=127.0.0.1。
 * <p>解析器读取以 {@link Constants#PROPERTIES_ENV_PREFIX} 开头的环境变量，
 * 转换为 {@link NacosA2aRegistryTransportProperties}。去掉前缀后按下划线拆分，
 * 第一部分是传输名，第二部分是属性名。若出现重复键，使用最后处理的值。
 * @see Constants.TransportPropertiesAttribute
 */
public class NacosA2aTransportPropertiesEnvParser {

    /**
     * 从环境变量获取传输属性，转换为 {@link NacosA2aRegistryTransportProperties} 映射。
     * <p>扫描以预定义前缀开头的环境变量，按预期格式解析并转换为配置对象。
     * 环境变量应遵循 PREFIX_TRANSPORT_ATTRIBUTE=value 格式。
     * @return 键为传输名称、值为对应 NacosA2aRegistryTransportProperties 对象的映射
     */
    public Map<String, NacosA2aRegistryTransportProperties> getTransportProperties() {
        return doGetTransportProperties(System.getenv());
    }

    private Map<String, NacosA2aRegistryTransportProperties> doGetTransportProperties(
            Map<String, String> environment) {
        Set<String> transportPropertiesKeys =
                environment.keySet().stream()
                        .filter(key -> key.startsWith(Constants.PROPERTIES_ENV_PREFIX))
                        .collect(Collectors.toSet());
        Map<String, Map<Constants.TransportPropertiesAttribute, Object>> transportProperties =
                parse(transportPropertiesKeys, environment);
        Map<String, NacosA2aRegistryTransportProperties> result =
                new HashMap<>(transportProperties.size());
        transportProperties.forEach(
                (transport, properties) ->
                        result.put(transport, convertToProperties(transport, properties)));
        return result;
    }

    private Map<String, Map<Constants.TransportPropertiesAttribute, Object>> parse(
            Set<String> transportPropertiesKeys, Map<String, String> environment) {
        Map<String, Map<Constants.TransportPropertiesAttribute, Object>> result = new HashMap<>();
        transportPropertiesKeys.forEach(
                envKey -> {
                    String subKey = envKey.substring(Constants.PROPERTIES_ENV_PREFIX.length());
                    String[] transportAttrs = subKey.split("_");
                    if (transportAttrs.length != 2) {
                        return;
                    }
                    String transport = transportAttrs[0].trim().toUpperCase();
                    String attr = transportAttrs[1].trim().toUpperCase();
                    String value = environment.get(envKey);
                    Constants.TransportPropertiesAttribute attribute =
                            Constants.TransportPropertiesAttribute.getByEnvKey(attr);
                    if (null == attribute) {
                        return;
                    }
                    result.compute(
                            transport,
                            (key, oldValue) -> {
                                if (null == oldValue) {
                                    oldValue = new HashMap<>();
                                }
                                oldValue.put(attribute, value);
                                return oldValue;
                            });
                });
        return result;
    }

    private NacosA2aRegistryTransportProperties convertToProperties(
            String transport, Map<Constants.TransportPropertiesAttribute, Object> properties) {
        NacosA2aRegistryTransportProperties.Builder builder =
                NacosA2aRegistryTransportProperties.builder().transport(transport);
        properties.forEach(
                (key, value) -> {
                    switch (key) {
                        case HOST -> builder.host(value.toString());
                        case PORT -> builder.port(parseInt(transport, value.toString()));
                        case PATH -> builder.path(value.toString());
                        case PROTOCOL -> builder.protocol(value.toString());
                        case QUERY -> builder.query(value.toString());
                        case SUPPORT_TLS ->
                                builder.supportTls(Boolean.parseBoolean(value.toString()));
                    }
                });
        return builder.build();
    }

    private int parseInt(String transport, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new NacosRuntimeException(
                    NacosException.INVALID_PARAM,
                    String.format("Invalid `port` value for transport `%s`: %s", transport, value));
        }
    }
}
