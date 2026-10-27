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
package io.agentscope.extensions.channel.wecom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 实现企业微信 URL 握手签名、AES-CBC 和 PKCS#7 加解密方案。参见 <a href="https://developer.work.weixin.qq.com/document/path/90968">企业微信回调规范</a>。
 *
 * <p>输入：
 * <ul>
 *   <li>{@code token}：开发者提供的回调令牌。
 *   <li>{@code encodingAesKey}：43 字符无填充 base64，追加 {@code "="} 后解码为 32 字节 AES 密钥；前 16 字节也用作 CBC IV。
 *   <li>{@code receiveId}：自建应用的企业 ID 或 ISV 应用的套件 ID；解密后验证尾部接收方 ID 与此值一致。
 * </ul>
 */
public final class WeComCrypto {

    private final String token;
    private final byte[] aesKey; // 32 字节
    private final IvParameterSpec iv;
    private final String receiveId;

    public WeComCrypto(String token, String encodingAesKey, String receiveId) {
        if (token == null) {
            throw new IllegalArgumentException("token is required");
        }
        if (encodingAesKey == null || encodingAesKey.length() != 43) {
            throw new IllegalArgumentException(
                    "encodingAesKey must be 43 characters (got "
                            + (encodingAesKey == null ? "null" : encodingAesKey.length())
                            + ")");
        }
        if (receiveId == null) {
            throw new IllegalArgumentException("receiveId is required");
        }
        this.token = token;
        this.aesKey = Base64.getDecoder().decode(encodingAesKey + "=");
        if (aesKey.length != 32) {
            throw new IllegalArgumentException(
                    "Decoded AES key must be 32 bytes (got " + aesKey.length + ")");
        }
        this.iv = new IvParameterSpec(Arrays.copyOf(aesKey, 16));
        this.receiveId = receiveId;
    }

    /**
     * 验证 SHA-1 签名：对 {@code [token, timestamp, nonce, encrypt]} 排序、拼接并计算哈希。
     */
    public boolean verifySignature(
            String signature, String timestamp, String nonce, String encrypt) {
        if (signature == null) {
            return false;
        }
        String[] parts = new String[] {token, timestamp, nonce, encrypt};
        Arrays.sort(parts);
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append(p);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
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
     * 解密 {@code Encrypt} 正文，返回 UTF-8 字符串形式的内部 XML 载荷。
     *
     * <p>AES-CBC 解密并移除 PKCS#7 填充后的企业微信明文布局：
     * <pre>
     * | 16 bytes random | 4 bytes msg_len (big-endian) | msg (XML, msg_len bytes) | receive_id |
     * </pre>
     */
    public String decrypt(String encryptBase64) {
        try {
            byte[] cipherBytes = Base64.getDecoder().decode(encryptBase64);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), iv);
            byte[] plain = cipher.doFinal(cipherBytes);
            byte[] unpad = pkcs7Unpad(plain);
            if (unpad.length < 20) {
                throw new IllegalStateException("Decrypted payload too short");
            }
            int msgLen =
                    ((unpad[16] & 0xff) << 24)
                            | ((unpad[17] & 0xff) << 16)
                            | ((unpad[18] & 0xff) << 8)
                            | (unpad[19] & 0xff);
            if (msgLen < 0 || 20 + msgLen > unpad.length) {
                throw new IllegalStateException("Invalid msg_len in decrypted payload: " + msgLen);
            }
            String xml = new String(unpad, 20, msgLen, StandardCharsets.UTF_8);
            String trailingReceiveId =
                    new String(
                            unpad, 20 + msgLen, unpad.length - 20 - msgLen, StandardCharsets.UTF_8);
            if (!receiveId.equals(trailingReceiveId)) {
                throw new IllegalStateException(
                        "receive_id mismatch (expected '"
                                + receiveId
                                + "', got '"
                                + trailingReceiveId
                                + "')");
            }
            return xml;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException("WeCom decrypt failed: " + e.getMessage(), e);
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
