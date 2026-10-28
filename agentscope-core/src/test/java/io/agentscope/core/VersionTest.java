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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * {@link Version} 单元测试。
 *
 * <p>验证构建时解析的版本和用于标识 AgentScope Java 客户端的 User-Agent 字符串生成。
 */
class VersionTest {

    private static final String MAIN_VERSION_RESOURCE = "/META-INF/agentscope/version.properties";
    private static final String TEST_VERSION_RESOURCE = "/agentscope-test-version.properties";
    private static final String SEMVER = "\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?";

    private static Properties loadProps(String resourcePath) throws IOException {
        try (InputStream in = VersionTest.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return null;
            }
            Properties props = new Properties();
            props.load(in);
            return props;
        }
    }

    @Test
    void testVersionConstant() throws IOException {
        Properties props = loadProps(MAIN_VERSION_RESOURCE);
        // 仅当构建完全没有执行 Maven 资源处理时资源才缺失，例如
        // IDE 直接针对未带资源的原始 target/classes 运行，此时没有
        // 可交叉检查的期望值，因此跳过而非失败。
        if (props == null) {
            Assumptions.abort(
                    MAIN_VERSION_RESOURCE
                            + " is missing from the classpath (non-Maven/IDE run);"
                            + " skipping strict version cross-check");
        }
        String expectedVersion = props.getProperty("version");
        Assertions.assertNotNull(expectedVersion, "version property should be present");

        // 资源存在但仍含未过滤的 ${project.version}
        // 占位符时，说明 Maven 资源过滤确实未生效，应明确
        // 失败而非视为前提不满足，否则打包 JAR 会静默携带
        // 占位符，而 Version.resolveVersionFrom 会将其掩盖为 "unknown"。
        Assertions.assertFalse(
                expectedVersion.contains("${"),
                MAIN_VERSION_RESOURCE
                        + " is unfiltered (still contains '${'): Maven resource filtering"
                        + " did not apply");

        // 严格交叉检查：运行时版本必须与 Maven 项目版本完全一致。
        Assertions.assertEquals(
                expectedVersion, Version.VERSION, "VERSION must match the Maven project version");

        // 语义版本格式检查。
        Assertions.assertTrue(
                Version.VERSION.matches(SEMVER),
                "VERSION should be a valid semver: " + Version.VERSION);
    }

    @Test
    void testVersionConstant_CrossCheckTestResource() throws IOException {
        Properties props = loadProps(TEST_VERSION_RESOURCE);
        if (props == null) {
            Assumptions.abort(
                    TEST_VERSION_RESOURCE
                            + " is missing from the classpath (non-Maven/IDE run);"
                            + " skipping cross-check");
        }
        String expectedVersion = props.getProperty("version");
        Assertions.assertNotNull(expectedVersion, "version property should be present");
        Assertions.assertFalse(
                expectedVersion.contains("${"), "test version.properties is unfiltered");
        Assertions.assertEquals(
                expectedVersion,
                Version.VERSION,
                "test-resource version must match the runtime VERSION");
    }

    @Test
    void testResolveVersionFromResource_Normal() throws IOException {
        String content = "version=2.0.3-SNAPSHOT";
        Assertions.assertEquals(
                "2.0.3-SNAPSHOT",
                Version.resolveVersionFromResource(
                        new java.io.ByteArrayInputStream(
                                content.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    void testResolveVersionFromResource_NullStream() {
        Assertions.assertEquals(Version.UNKNOWN, Version.resolveVersionFromResource(null));
    }

    @Test
    void testResolveVersionFromResource_UnreadableStream() {
        InputStream broken =
                new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("boom");
                    }
                };
        Assertions.assertEquals(Version.UNKNOWN, Version.resolveVersionFromResource(broken));
    }

    @Test
    void testResolveVersionFrom_Normal() {
        Properties props = new Properties();
        props.setProperty("version", "  2.0.3-SNAPSHOT  ");
        Assertions.assertEquals("2.0.3-SNAPSHOT", Version.resolveVersionFrom(props));
    }

    @Test
    void testResolveVersionFrom_UnfilteredPlaceholder() {
        Properties props = new Properties();
        props.setProperty("version", "${project.version}");
        Assertions.assertEquals(Version.UNKNOWN, Version.resolveVersionFrom(props));
    }

    @Test
    void testResolveVersionFrom_NullValue() {
        Properties props = new Properties();
        Assertions.assertEquals(Version.UNKNOWN, Version.resolveVersionFrom(props));
    }

    @Test
    void testResolveVersionFrom_BlankValue() {
        Properties props = new Properties();
        props.setProperty("version", "   ");
        Assertions.assertEquals(Version.UNKNOWN, Version.resolveVersionFrom(props));
    }

    @Test
    void testResolveVersionFrom_NullProps() {
        Assertions.assertEquals(Version.UNKNOWN, Version.resolveVersionFrom(null));
    }

    @Test
    void testResolveVersionFromManifest_Normal() {
        Assertions.assertEquals(
                "2.0.3-SNAPSHOT", Version.resolveVersionFromManifest("2.0.3-SNAPSHOT"));
    }

    @Test
    void testResolveVersionFromManifest_Trimmed() {
        Assertions.assertEquals(
                "2.0.3-SNAPSHOT", Version.resolveVersionFromManifest("  2.0.3-SNAPSHOT  "));
    }

    @Test
    void testResolveVersionFromManifest_Null() {
        Assertions.assertEquals(Version.UNKNOWN, Version.resolveVersionFromManifest(null));
    }

    @Test
    void testResolveVersionFromManifest_Blank() {
        Assertions.assertEquals(Version.UNKNOWN, Version.resolveVersionFromManifest("   "));
    }

    @Test
    void testResolveVersionFromManifest_UnfilteredPlaceholder() {
        Assertions.assertEquals(
                Version.UNKNOWN, Version.resolveVersionFromManifest("${project.version}"));
    }

    @Test
    void testGetUserAgent_Format() {
        // 获取 User-Agent 字符串
        String userAgent = Version.getUserAgent();

        // 验证非 null 且非空
        Assertions.assertNotNull(userAgent, "User-Agent should not be null");
        Assertions.assertFalse(userAgent.isEmpty(), "User-Agent should not be empty");

        // 验证格式：agentscope-java/{version}; java/{java_version}; platform/{os}
        Assertions.assertTrue(
                userAgent.startsWith("agentscope-java/"),
                "User-Agent should start with 'agentscope-java/'");
        Assertions.assertTrue(userAgent.contains("; java/"), "User-Agent should contain '; java/'");
        Assertions.assertTrue(
                userAgent.contains("; platform/"), "User-Agent should contain '; platform/'");
    }

    @Test
    void testGetUserAgent_ContainsVersion() {
        String userAgent = Version.getUserAgent();

        // 验证包含 AgentScope 版本
        Assertions.assertTrue(
                userAgent.contains(Version.VERSION),
                "User-Agent should contain AgentScope version: " + Version.VERSION);
    }

    @Test
    void testGetUserAgent_ContainsJavaVersion() {
        String userAgent = Version.getUserAgent();
        String javaVersion = System.getProperty("java.version");

        // 验证包含 Java 版本
        Assertions.assertTrue(
                userAgent.contains(javaVersion),
                "User-Agent should contain Java version: " + javaVersion);
    }

    @Test
    void testGetUserAgent_ContainsPlatform() {
        String userAgent = Version.getUserAgent();
        String platform = System.getProperty("os.name");

        // 验证包含平台/操作系统名称
        Assertions.assertTrue(
                userAgent.contains(platform), "User-Agent should contain platform: " + platform);
    }

    @Test
    void testGetUserAgent_Consistency() {
        // 验证多次调用返回相同值
        String userAgent1 = Version.getUserAgent();
        String userAgent2 = Version.getUserAgent();

        Assertions.assertEquals(
                userAgent1,
                userAgent2,
                "Multiple calls to getUserAgent() should return consistent results");
    }

    @Test
    void testGetUserAgent_ExampleFormat() {
        String userAgent = Version.getUserAgent();

        // 验证符合预期模式，对不同环境采用宽松检查
        String pattern = "^agentscope-java/.+; java/[0-9.]+; platform/.+$";
        Assertions.assertTrue(
                userAgent.matches(pattern),
                "User-Agent should match pattern: " + pattern + ", but got: " + userAgent);
    }
}
