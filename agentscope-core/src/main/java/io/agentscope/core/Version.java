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
package io.agentscope.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AgentScope 版本及 User-Agent 信息。
 *
 * <p>为所有模型请求提供统一 User-Agent 字符串，用于识别 AgentScope Java 客户端和收集使用统计。
 *
 * <p>类加载时按三级回退链解析版本：
 * <ol>
 * <li>类路径资源 {@code META-INF/agentscope/version.properties}，其中 {@code ${project.version}}
 * 占位符由 Maven 资源过滤解析；</li>
 * <li>所在 jar 的 manifest 条目 {@code Implementation-Version}，由 Maven jar 插件根据
 * {@code ${project.version}} 填充；</li>
 * <li>最终回退为字面值 {@code "unknown"}（例如从没有 manifest 的 IDE {@code target/classes}
 * 直接运行），并记录一次警告日志。</li>
 * </ol>
 */
public final class Version {

    private static final Logger log = LoggerFactory.getLogger(Version.class);

    private static final String VERSION_RESOURCE = "/META-INF/agentscope/version.properties";

    /**
     * 无法找到构建时解析的版本时返回的哨兵值。
     */
    static final String UNKNOWN = "unknown";

    /**
     * AgentScope Java 版本。
     *
     * <p>构建时由 Maven 资源过滤从 {@code ${project.version}} 注入；回退链见类 Javadoc。
     */
    public static final String VERSION = resolveVersion();

    private Version() {
        // 工具类，禁止实例化
    }

    private static String resolveVersion() {
        // 1）Maven 过滤后的类路径资源（正常 Maven 构建及测试）。
        String fromResource;
        try (InputStream in = Version.class.getResourceAsStream(VERSION_RESOURCE)) {
            fromResource = resolveVersionFromResource(in);
        } catch (IOException e) {
            log.debug("Failed to read {} from the classpath", VERSION_RESOURCE, e);
            fromResource = UNKNOWN;
        }
        if (!UNKNOWN.equals(fromResource)) {
            return fromResource;
        }

        // 2）jar manifest 的 Implementation-Version（打包 jar 中的 META-INF/agentscope
        // 目录可能因 shading 或自定义打包被移除）。
        String fromManifest = resolveVersionFromManifest(getImplementationVersion());
        if (!UNKNOWN.equals(fromManifest)) {
            log.debug(
                    "Resolved version from jar manifest Implementation-Version: {}", fromManifest);
            return fromManifest;
        }

        // 3）最终回退：让失败可见，而不是悄悄使用陈旧版本值。
        log.warn(
                "Could not resolve the AgentScope version from {} or the jar manifest "
                        + "Implementation-Version; reporting '{}'. Run against a Maven-built "
                        + "classpath for accurate versioning.",
                VERSION_RESOURCE,
                UNKNOWN);
        return UNKNOWN;
    }

    /**
     * 从类路径资源流加载并解析版本。
     *
     * <p>包可见，便于单元测试。
     *
     * @param in {@code version.properties} 的输入流；资源不存在时为 {@code null}
     * @return 解析出的版本；流为 {@code null} 或无法读取时为 {@code "unknown"}
     */
    static String resolveVersionFromResource(InputStream in) {
        if (in == null) {
            return UNKNOWN;
        }
        try (in) {
            Properties props = new Properties();
            props.load(in);
            return resolveVersionFrom(props);
        } catch (IOException e) {
            log.debug("Failed to read {}", VERSION_RESOURCE, e);
            return UNKNOWN;
        }
    }

    /**
     * 从 properties 对象解析版本字符串，防止缺失、空白及未过滤的 {@code ${...}} 值。
     *
     * <p>包可见，便于单元测试。
     *
     * @param props 从版本资源加载的属性
     * @return 去掉首尾空白的版本；缺失、空白或未过滤时为 {@code "unknown"}
     */
    static String resolveVersionFrom(Properties props) {
        if (props == null) {
            return UNKNOWN;
        }
        String version = props.getProperty("version");
        // 防止返回未过滤的 ${project.version} 字面量，例如
        // 从未执行 Maven 资源过滤的 IDE 运行时。
        if (version != null && !version.isBlank() && !version.startsWith("${")) {
            return version.trim();
        }
        return UNKNOWN;
    }

    /**
     * 从 jar manifest 的 {@code Implementation-Version} 条目解析版本。
     *
     * <p>包可见，便于单元测试。
     *
     * @param implementationVersion manifest 值；不存在时为 {@code null}
     * @return 解析出的版本；缺失、空白或未过滤时为 {@code "unknown"}
     */
    static String resolveVersionFromManifest(String implementationVersion) {
        if (implementationVersion != null
                && !implementationVersion.isBlank()
                && !implementationVersion.startsWith("${")) {
            return implementationVersion.trim();
        }
        return UNKNOWN;
    }

    private static String getImplementationVersion() {
        Package pkg = Version.class.getPackage();
        return pkg == null ? null : pkg.getImplementationVersion();
    }

    /**
     * 为所有模型生成标准 User-Agent 字符串。
     *
     * <p>格式：{@code agentscope-java/{version}; java/{java_version}; platform/{os}}
     *
     * @return 统一 User-Agent 字符串
     */
    public static String getUserAgent() {
        return String.format(
                "agentscope-java/%s; java/%s; platform/%s",
                VERSION, System.getProperty("java.version"), System.getProperty("os.name"));
    }
}
