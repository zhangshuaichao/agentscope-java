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

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;

/**
 * 获取当前服务器 IP 地址和端口配置的网络工具类。
 */
public class NetworkUtils {

    /**
     * 将主机格式化为 URL authority 可用形式，为无方括号的 IPv6 字面量添加方括号。
     *
     * @param host 主机名或 IP 地址
     * @return 格式化后可用于 URL authority 的主机
     */
    public static String formatHostForUrl(String host) {
        if (host == null || host.indexOf(':') < 0 || (host.startsWith("[") && host.endsWith("]"))) {
            return host;
        }
        return "[" + host + "]";
    }

    /**
     * 获取本地 IP 地址，优先非环回地址。
     *
     * @return 本地 IP 地址
     * @throws SocketException 网络异常
     */
    public static String getLocalIpAddress() throws SocketException {
        Stream<NetworkInterface> networkInterfaces = NetworkInterface.networkInterfaces();

        List<NetworkInterface> nis =
                networkInterfaces
                        .filter(NetworkUtils::isValidIpAddress)
                        .sorted(Comparator.comparing(NetworkInterface::getIndex))
                        .toList();

        for (NetworkInterface networkInterface : nis) {
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();

                // 跳过环回地址与 IPv6 地址
                if (address.isLoopbackAddress() || address.isLinkLocalAddress()) {
                    continue;
                }

                // 优先 IPv4 地址
                if (address.getAddress().length == 4) {
                    return address.getHostAddress();
                }
            }
        }

        // 未找到适用 IP 时返回 localhost
        return "localhost";
    }

    private static boolean isValidIpAddress(NetworkInterface n) {
        try {
            return n != null && !n.isLoopback() && n.isUp();
        } catch (SocketException e) {
            return false;
        }
    }
}
