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

package io.agentscope.spring.boot.nacos.properties;

import com.alibaba.nacos.api.PropertyKeyConst;
import java.util.Properties;

/**
 * Nacos 客户端连接服务端的属性。
 *
 * <p>配置服务端基础属性，例如 {@link #serverAddr}、{@link #namespace}、{@link #username}、{@link #password} 等。
 *
 * <p>{@link #properties} 用于高级属性，其键来自 {@link PropertyKeyConst}。
 */
public class BaseNacosProperties {

    private static final String DEFAULT_ADDRESS = "127.0.0.1:8848";

    private static final String DEFAULT_NAMESPACE = "public";

    /**
     * Nacos 服务端地址。
     *
     * <p>默认 {@code 127.0.0.1:8848}。
     *
     * <p>格式为 {@code [http://|https://]domain[:port]}；未指定协议时使用 http://，未指定端口时使用 8848。
     */
    String serverAddr;

    /**
     * Nacos 服务端命名空间。
     *
     * <p>默认 {@code public}。
     *
     * <p>应配置 namespaceId，而非命名空间名称。
     */
    String namespace;

    /**
     * Nacos 服务端用户名。
     *
     * <p>应填写服务端用户名称。
     *
     * <p>服务端启用认证时配置，与 {@link #password} 配合使用。
     */
    String username;

    /**
     * Nacos 服务端密码。
     *
     * <p>应填写服务端用户密码。
     *
     * <p>服务端启用认证时配置，与 {@link #username} 配合使用。
     */
    String password;

    /**
     * Nacos 服务端 AccessKey。
     *
     * <p>应填写阿里云等云供应商提供的 accessKey。
     *
     * <p>云服务商提供的 Nacos 启用认证时配置，与 {@link #secretKey} 配合使用。
     */
    String accessKey;

    /**
     * Nacos 服务端 SecretKey。
     *
     * <p>应填写阿里云等云供应商提供的 secretKey。
     *
     * <p>云服务商提供的 Nacos 启用认证时配置，与 {@link #accessKey} 配合使用。
     */
    String secretKey;

    /**
     * Nacos 服务端和客户端的高级属性。
     *
     * <p>属性键来自 {@link PropertyKeyConst}。
     *
     * <p>{@link BaseNacosProperties} 中已定义的属性非 null 时，会覆盖高级属性映射中的同名值：
     * <ul>
     *   <li>{@link #serverAddr} 非 null 时，{@link PropertyKeyConst#SERVER_ADDR} 由 {@link #serverAddr} 覆盖</li>
     *   <li>{@link #namespace} 非 null 时，{@link PropertyKeyConst#NAMESPACE} 由 {@link #namespace} 覆盖</li>
     *   <li>{@link #username} 非 null 时，{@link PropertyKeyConst#USERNAME} 由 {@link #username} 覆盖</li>
     *   <li>{@link #password} 非 null 时，{@link PropertyKeyConst#PASSWORD} 由 {@link #password} 覆盖</li>
     *   <li>{@link #accessKey} 非 null 时，{@link PropertyKeyConst#ACCESS_KEY} 由 {@link #accessKey} 覆盖</li>
     *   <li>{@link #secretKey} 非 null 时，{@link PropertyKeyConst#SECRET_KEY} 由 {@link #secretKey} 覆盖</li>
     * </ul>
     */
    Properties properties;

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getServerAddr() {
        return serverAddr;
    }

    public void setServerAddr(String serverAddr) {
        this.serverAddr = serverAddr;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public Properties getProperties() {
        return properties;
    }

    public void setProperties(Properties properties) {
        this.properties = properties;
    }

    /**
     * 构建仅含显式配置字段的 {@link Properties} 实例。
     *
     * <p>与 {@link #getNacosProperties()} 不同，不为 {@code serverAddr} 和 {@code namespace} 应用默认值；适用于覆盖／合并场景，避免默认值覆盖父配置。
     *
     * <p>【学习提示】A2A 自动装配先读取通用 Nacos 的完整配置，再 putAll 此处显式 A2A 配置。区分“未指定”与“默认值”，才能让 A2A 只覆盖用户实际设置的字段，并继承其余通用连接／凭据配置。
     *
     * @return 仅含显式 Nacos 配置的 {@link Properties} 实例
     */
    public Properties getExplicitNacosProperties() {
        Properties result = new Properties();
        if (null != properties && !properties.isEmpty()) {
            result.putAll(properties);
        }
        if (null != serverAddr) {
            result.put(PropertyKeyConst.SERVER_ADDR, serverAddr);
        }
        if (null != namespace) {
            result.put(PropertyKeyConst.NAMESPACE, namespace);
        }
        if (null != username) {
            result.put(PropertyKeyConst.USERNAME, username);
        }
        if (null != password) {
            result.put(PropertyKeyConst.PASSWORD, password);
        }
        if (null != accessKey) {
            result.put(PropertyKeyConst.ACCESS_KEY, accessKey);
        }
        if (null != secretKey) {
            result.put(PropertyKeyConst.SECRET_KEY, secretKey);
        }
        return result;
    }

    /**
     * 构建用于配置 Nacos 客户端的 {@link Properties} 实例。
     *
     * <p>基于 {@link #getExplicitNacosProperties()}，在对应字段未显式配置时额外应用默认值（{@link #DEFAULT_ADDRESS} 和 {@link #DEFAULT_NAMESPACE}）。
     *
     * @return 包含全部解析后 Nacos 配置的 {@link Properties} 实例
     */
    public Properties getNacosProperties() {
        Properties result = getExplicitNacosProperties();
        result.putIfAbsent(PropertyKeyConst.SERVER_ADDR, DEFAULT_ADDRESS);
        result.putIfAbsent(PropertyKeyConst.NAMESPACE, DEFAULT_NAMESPACE);
        return result;
    }
}
