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
package io.agentscope.extensions.channel.dingtalk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 实现钉钉机器人消息接收的 HTTP 回调请求验证及可选请求体解密。
 * 参见钉钉文档：<a
 * href="https://open.dingtalk.com/document/robots/verify-valid-requests-for-intra-enterprise-robot-group-chat">验证机器人请求</a>及<a
 * href="https://open.dingtalk.com/document/development/callback-event-message-body-encryption-and-decryption">回调体加解密</a>。
 *
 * <p>输入：
 *
 * <ul>
 *   <li>{@code appSecret} — 企业内部应用密钥，作为请求签名验证的 HMAC 密钥。
 *       每次回调携带 {@code timestamp} 与 {@code sign} 请求头，
 *       其中 {@code sign = Base64(HmacSHA256(appSecret, timestamp + "\n" + appSecret))}。
 *       时间戳为毫秒纪元值，允许的历史窗口为一小时；超前本地时间的值仅在时钟偏差容限内接受。
 *   <li>{@code aesKey} — 可选的 43 字符 Base64（无填充）。机器人启用加密时，
 *       回调体为 {@code {"encrypt": ...}} 信封而非明文 JSON；
 *       此密钥追加 {@code "="} 后解码为 32 字节，前 16 字节同时作为 CBC IV，供解密使用。
 *       未启用加密的机器人可为 {@code null}。
 * </ul>
 *
 * <p>实例不可变且线程安全；{@link #verify} 与 {@link #decrypt} 可由多个请求线程并发调用。
 */
public final class DingTalkCallbackCrypto {

    /**
     * 依据钉钉文档设置的请求时间戳最大历史窗口。
     */
    private static final long MAX_TIMESTAMP_AGE_MS = 3_600_000L;

    /**
     * 时间戳超前本地时间的最大容限，用于容忍时钟偏差，
     * 并限定已捕获请求头组合在未来方向可接受的范围；历史方向保留文档规定的一小时窗口。
     */
    private static final long MAX_FUTURE_TIMESTAMP_MS = 300_000L;

    private final String appSecret;
    private final byte[] aesKey; // 32 字节；未启用加密时为 null
    private final IvParameterSpec iv; // 未启用加密时为 null

    public DingTalkCallbackCrypto(String appSecret, String aesKey) {
        if (appSecret == null || appSecret.isBlank()) {
            throw new IllegalArgumentException("appSecret is required");
        }
        if (aesKey != null && aesKey.length() != 43) {
            throw new IllegalArgumentException(
                    "aesKey must be 43 characters (got " + aesKey.length() + ")");
        }
        this.appSecret = appSecret;
        if (aesKey == null) {
            this.aesKey = null;
            this.iv = null;
        } else {
            byte[] key = Base64.getDecoder().decode(aesKey + "=");
            if (key.length != 32) {
                throw new IllegalArgumentException(
                        "Decoded AES key must be 32 bytes (got " + key.length + ")");
            }
            this.aesKey = key;
            this.iv = new IvParameterSpec(Arrays.copyOf(key, 16));
        }
    }

    /**
     * 验证回调的 {@code timestamp}/{@code sign} 请求头：
     * 毫秒时间戳距今不得超过 {@link #MAX_TIMESTAMP_AGE_MS}，
     * 也不得超前本地时间超过 {@link #MAX_FUTURE_TIMESTAMP_MS}；
     * {@code sign} 必须等于 {@code
     * Base64(HmacSHA256(appSecret, timestamp + "\n" + appSecret))}。
     */
    // 【学习提示】此方法验证的是时间戳签名和时间窗口，不含请求体参数，也不记录已用签名。
    // 所以不能把验签成功等同于业务消息只处理一次；消息去重和回调体处理属于其他边界。
    public boolean verify(String timestamp, String sign) {
        if (timestamp == null || sign == null) {
            return false;
        }
        long ts;
        String normalized = timestamp.trim();
        try {
            ts = Long.parseLong(normalized);
        } catch (NumberFormatException e) {
            return false;
        }
        long age = System.currentTimeMillis() - ts;
        if (age > MAX_TIMESTAMP_AGE_MS || -age > MAX_FUTURE_TIMESTAMP_MS) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest =
                    mac.doFinal((normalized + "\n" + appSecret).getBytes(StandardCharsets.UTF_8));
            String expected = Base64.getEncoder().encodeToString(digest);
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    sign.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 解密请求体 {@code encrypt} 字段，以 UTF-8 字符串返回内层 JSON 载荷。
     *
     * <p>AES-CBC 解密并移除 PKCS#7 填充后的钉钉明文布局：
     *
     * <pre>
     * | 16 bytes random | 4 bytes msg_len (big-endian) | msg (msg_len bytes) | optional trailing bytes |
     * </pre>
     *
     * <p>字节布局中文说明：前 16 字节为随机数，接着 4 字节按大端序保存消息长度，然后是对应长度的消息正文，最后可能带平台附加尾部字节。
     *
     * <p>{@code msg_len} 之后的尾随字节不属于消息，予以忽略。
     * 此信封的钉钉事件回调变体会在这里附加接收者 ID。
     */
    public String decrypt(String encryptBase64) {
        if (aesKey == null) {
            throw new IllegalStateException("aesKey is not configured for this channel");
        }
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
            return new String(unpad, 20, msgLen, StandardCharsets.UTF_8);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException("DingTalk decrypt failed: " + e.getMessage(), e);
        }
    }

    /**
     * 返回此渠道是否配置了 {@code aesKey}（加密回调）。
     */
    public boolean hasAesKey() {
        return aesKey != null;
    }

    private static byte[] pkcs7Unpad(byte[] in) {
        if (in.length == 0) {
            throw new IllegalStateException("Decrypted payload is empty");
        }
        // 此信封族（企业微信 EncodingAESKey／钉钉回调加密）按
        // 32 字节块填充，因此合法填充值范围为 1..32，而非 AES 块大小 16。
        int pad = in[in.length - 1] & 0xff;
        if (pad < 1 || pad > 32 || pad > in.length) {
            throw new IllegalStateException("Invalid PKCS#7 padding length: " + pad);
        }
        for (int i = in.length - pad; i < in.length; i++) {
            if ((in[i] & 0xff) != pad) {
                throw new IllegalStateException("Invalid PKCS#7 padding bytes");
            }
        }
        return Arrays.copyOf(in, in.length - pad);
    }
}
