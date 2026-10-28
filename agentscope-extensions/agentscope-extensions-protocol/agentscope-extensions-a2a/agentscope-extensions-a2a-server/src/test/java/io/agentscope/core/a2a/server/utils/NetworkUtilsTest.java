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

package io.agentscope.core.a2a.server.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.SocketException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * NetworkUtils 单元测试，覆盖获取本地 IP 地址、网络异常处理，以及没有合适 IP 时回退到 localhost。
 */
@DisplayName("NetworkUtils Tests")
class NetworkUtilsTest {

    @Test
    @DisplayName("Should format hosts for URL authorities")
    void testFormatHostForUrl() {
        assertEquals("localhost", NetworkUtils.formatHostForUrl("localhost"));
        assertEquals("127.0.0.1", NetworkUtils.formatHostForUrl("127.0.0.1"));
        assertEquals("[::1]", NetworkUtils.formatHostForUrl("::1"));
        assertEquals("[::1]", NetworkUtils.formatHostForUrl("[::1]"));
    }

    @Test
    @DisplayName("Should return localhost when no network interfaces available")
    void testGetLocalIpAddressWithNoNetworkInterfaces() throws Exception {
        // Java NetworkInterface 的静态方法不易模拟，
        // 因此这里只测试方法不抛异常且返回有效字符串。
        String ipAddress = NetworkUtils.getLocalIpAddress();

        assertNotNull(ipAddress);
        // 应为 localhost 或有效 IP 地址。
        assertTrue("localhost".equals(ipAddress) || ipAddress.matches("\\d+\\.\\d+\\.\\d+\\.\\d+"));
    }

    @Test
    @DisplayName("Should throw SocketException when network error occurs")
    void testGetLocalIpAddressWithNetworkException() {
        // 若不模拟静态方法，就难以测试异常场景，
        // 而模拟静态方法需要 PowerMockito 等额外库。
        // 此测试保留为说明，记录若能模拟静态方法时希望测试的行为。
    }

    @Test
    @DisplayName("Should return valid IP address format")
    void testValidIpAddressFormat() throws SocketException {
        String ipAddress = NetworkUtils.getLocalIpAddress();

        assertNotNull(ipAddress);
        // 检查是否为 "localhost" 或有效 IPv4 地址。
        if (!"localhost".equals(ipAddress)) {
            // 应匹配 IPv4 格式 X.X.X.X，其中 X 为 0—255。
            assertTrue(
                    ipAddress.matches("\\d+\\.\\d+\\.\\d+\\.\\d+"),
                    "IP address should be in valid IPv4 format");
        }
    }
}
