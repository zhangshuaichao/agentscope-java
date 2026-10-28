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
package io.agentscope.extensions.channel.feishu;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 实现飞书回调加密与签名验证。
 *
 * <p>开发者控制台配置 Encrypt Key 后，飞书加密回调体。
 * 线格式为 JSON {@code {"encrypt":"<base64-ciphertext>"}}。
 * AES 密钥由 {@code SHA-256(encryptKey)} 派生（32 字节）。密文布局：
 *
 * <pre>
 * | 16 bytes IV | AES-CBC + PKCS#7 padded body |
 * </pre>
 *
 * <p>布局中文说明：前 16 字节是 IV（初始化向量），后面是 AES-CBC 加密且采用 PKCS#7 填充的正文。
 *
 * <p>签名头 {@code X-Lark-Signature} 按
 * {@code SHA-256(timestamp + nonce + encryptKey + rawBody)} 计算；
 * {@code rawBody} 是原始 HTTP 请求体（序列化为 JSON 的信封，而非解密后的明文）。
 *
 * @see <a href="https://open.feishu.cn/document/server-docs/event-subscription-guide/event-subscription-configure-/encrypt-key-encryption-configuration-case">飞书 Encrypt Key 规范</a>
 */
public final class FeishuCrypto {

    private final String encryptKey;
    private final byte[] aesKey; // 32 字节，由 encryptKey 的 SHA-256 派生

    public FeishuCrypto(String encryptKey) {
        if (encryptKey == null || encryptKey.isBlank()) {
            throw new IllegalArgumentException("encryptKey is required");
        }
        this.encryptKey = encryptKey;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            this.aesKey = md.digest(encryptKey.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive AES key: " + e.getMessage(), e);
        }
    }

    /**
     * 验证 SHA-256 签名：{@code hex(SHA-256(timestamp + nonce + encryptKey + body))}。
     * 任一参数为 null 或摘要不匹配时返回 false。
     */
    public boolean verifySignature(String signature, String timestamp, String nonce, String body) {
        if (signature == null || timestamp == null || nonce == null || body == null) {
            return false;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(timestamp.getBytes(StandardCharsets.UTF_8));
            md.update(nonce.getBytes(StandardCharsets.UTF_8));
            md.update(encryptKey.getBytes(StandardCharsets.UTF_8));
            md.update(body.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return constantTimeEquals(hex.toString(), signature);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 解密飞书回调的 {@code encrypt} 字符串，以 UTF-8 字符串返回 JSON 明文。
     *
     * <p>Base64 解码后的布局：
     *
     * <pre>
     * | 16 bytes IV | AES-256-CBC + PKCS#7 padded plaintext |
     * </pre>
     *
     * <p>解密布局中文说明：先取 16 字节 IV，再按 AES-256-CBC 解密并处理 PKCS#7 填充；图中 plaintext 指解密后的明文。
     */
    public String decrypt(String encryptBase64) {
        try {
            byte[] cipherBytes = Base64.getDecoder().decode(encryptBase64);
            if (cipherBytes.length < 16 + 16) {
                throw new IllegalStateException("Encrypted payload too short");
            }
            byte[] iv = Arrays.copyOfRange(cipherBytes, 0, 16);
            byte[] body = Arrays.copyOfRange(cipherBytes, 16, cipherBytes.length);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
            byte[] plain = cipher.doFinal(body);
            byte[] unpadded = pkcs7Unpad(plain);
            return new String(unpadded, StandardCharsets.UTF_8);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException("Feishu decrypt failed: " + e.getMessage(), e);
        }
    }

    private static byte[] pkcs7Unpad(byte[] in) {
        if (in.length == 0) {
            return in;
        }
        int pad = in[in.length - 1] & 0xff;
        if (pad < 1 || pad > 32 || pad > in.length) {
            return in;
        }
        return Arrays.copyOf(in, in.length - pad);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
