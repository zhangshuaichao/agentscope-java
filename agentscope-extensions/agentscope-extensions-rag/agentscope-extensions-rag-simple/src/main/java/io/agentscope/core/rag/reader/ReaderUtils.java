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
package io.agentscope.core.rag.reader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 读取器常用操作工具类。
 *
 * <p>提供共享功能：
 * <ul>
 * <li>使用哈希算法生成文档 ID。</li>
 * <li>将字节数组转换为十六进制字符串。</li>
 * </ul>
 */
public final class ReaderUtils {

    private ReaderUtils() {
        // 禁止实例化。
    }

    /**
     * 使用指定哈希算法生成确定性文档 ID。
     *
     * <p>适合根据文件路径等标识信息生成一致的文档 ID。
     *
     * @param input 待哈希字符串，通常为文件路径
     * @param algorithm 哈希算法，如 MD5、SHA-256
     * @return 输入哈希的十六进制字符串
     * @throws RuntimeException 当指定算法不可用时
     */
    public static String generateDocId(String input, String algorithm) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(algorithm + " algorithm not available", e);
        }
    }

    /**
     * 使用 MD5 生成确定性文档 ID。
     *
     * <p>使用 MD5 的便捷方法。
     *
     * @param input 待哈希字符串，通常为文件路径
     * @return 输入 MD5 的十六进制字符串
     */
    public static String generateDocIdMD5(String input) {
        return generateDocId(input, "MD5");
    }

    /**
     * 使用 SHA-256 生成确定性文档 ID。
     *
     * <p>使用 SHA-256 的便捷方法。
     *
     * @param input 待哈希字符串，通常为文件路径
     * @return 输入 SHA-256 的十六进制字符串
     */
    public static String generateDocIdSHA256(String input) {
        return generateDocId(input, "SHA-256");
    }

    /**
     * 将字节数组转换为十六进制字符串。
     *
     * @param bytes 待转换字节数组
     * @return 小写十六进制字符串
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
