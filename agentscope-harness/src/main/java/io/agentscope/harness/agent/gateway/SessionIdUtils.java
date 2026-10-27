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
package io.agentscope.harness.agent.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 确定性的会话 ID 派生。使用 SHA-256 从任意输入片段生成 12 位十六进制字符串，
 * 相同输入总能得到相同输出，与 JVM 实例或机器无关。
 */
public final class SessionIdUtils {

    private SessionIdUtils() {}

    /**
     * 将给定片段用 {@code "/"} 连接，派生出 12 位小写十六进制哈希。
     *
     * <p>哈希是确定性的：相同输入在任意机器上产生相同输出。
     * 输出仅含小写十六进制字符，可安全用于文件名；空间为 2^48。
     *
     * <p>【学习提示】确定性不等于唯一性：截断到 48 位仍可能碰撞，且分隔符连接也不是
     * 通用的无歧义元组编码。此方法用于稳定寻址，不应当作认证令牌或绝对无碰撞的身份保证。
     *
     * @param parts 一个或多个非 null 种子值
     * @return 12 位十六进制字符串
     */
    public static String deterministicHash(String... parts) {
        String seed = String.join("/", parts);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
