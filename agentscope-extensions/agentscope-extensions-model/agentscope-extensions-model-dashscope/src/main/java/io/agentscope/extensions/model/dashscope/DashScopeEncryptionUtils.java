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
package io.agentscope.extensions.model.dashscope;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 用于 DashScope API 安全访问的加密工具。
 *
 * <p>为 API 调用提供：
 * <ul>
 * <li>请求/响应数据的 AES-GCM 加密。</li>
 * <li>AES 密钥交换的 RSA 公钥加密。</li>
 * </ul>
 *
 * <p>【学习提示】AES-GCM 负责内容的机密性与完整性，RSA 公钥用于包装 AES 密钥；Base64 只是编码，不提供加密。协议层加密不替代 TLS、凭据管理或日志脱敏，也不能单凭启用此选项就宣称符合所有企业安全政策。
 *
 * <p>实现遵循阿里云云端模型推理的加密协议要求，配合 TLS 和令牌认证使用。
 */
public final class DashScopeEncryptionUtils {

    /**
     * AES-GCM 认证标签位数（128 位，即 16 字节）。
     */
    private static final int GCM_TAG_LENGTH = 128;

    /**
     * AES-GCM IV 字节数（12 字节）。
     */
    private static final int GCM_IV_LENGTH = 12;

    /**
     * AES 密钥位数（256 位）。
     */
    private static final int AES_KEY_SIZE = 256;

    private DashScopeEncryptionUtils() {
        // 工具类，禁止实例化。
    }

    /**
     * 生成新的 AES 加密密钥。
     *
     * @return 新的 AES-256 密钥
     * @throws EncryptionException 当密钥生成失败时
     */
    public static SecretKey generateAesSecretKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            SecureRandom secureRandom = new SecureRandom();
            keyGen.init(AES_KEY_SIZE, secureRandom);
            return keyGen.generateKey();
        } catch (Exception e) {
            throw new EncryptionException("Failed to generate AES secret key", e);
        }
    }

    /**
     * 为 AES-GCM 加密生成初始化向量（IV）。
     *
     * @return 随机的 12 字节 IV
     */
    public static byte[] generateIv() {
        byte[] iv = new byte[GCM_IV_LENGTH];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(iv);
        return iv;
    }

    /**
     * 使用 AES-GCM 加密数据。
     *
     * @param secretKey AES 密钥
     * @param iv 初始化向量
     * @param plaintext 待加密明文
     * @return Base64 编码的密文
     * @throws EncryptionException 当加密失败时
     */
    public static String encryptWithAes(SecretKey secretKey, byte[] iv, String plaintext) {
        try {
            byte[] content = plaintext.getBytes(StandardCharsets.UTF_8);

            Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            aesCipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(secretKey.getEncoded(), "AES"),
                    gcmParameterSpec);

            byte[] encryptedBytes = aesCipher.doFinal(content);
            return Base64.getEncoder().encodeToString(encryptedBytes);
        } catch (Exception e) {
            throw new EncryptionException("Failed to encrypt data with AES-GCM", e);
        }
    }

    /**
     * 使用 AES-GCM 解密数据。
     *
     * @param secretKey AES 密钥
     * @param iv 初始化向量
     * @param encryptedData Base64 编码的密文
     * @return 解密后的明文
     * @throws EncryptionException 当解密失败时
     */
    public static String decryptWithAes(SecretKey secretKey, byte[] iv, String encryptedData) {
        try {
            byte[] encryptedBytes = Base64.getDecoder().decode(encryptedData);

            Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            aesCipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(secretKey.getEncoded(), "AES"),
                    gcmParameterSpec);

            byte[] decryptedBytes = aesCipher.doFinal(encryptedBytes);
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new EncryptionException("Failed to decrypt data with AES-GCM", e);
        }
    }

    /**
     * 使用 RSA 公钥加密 AES 密钥。
     *
     * @param aesSecretKey 待加密的 AES 密钥
     * @param publicKeyBase64 Base64 编码的 RSA 公钥
     * @return Base64 编码的加密 AES 密钥
     * @throws EncryptionException 当加密失败时
     */
    public static String encryptAesKeyWithRsa(SecretKey aesSecretKey, String publicKeyBase64) {
        try {
            byte[] aesKeyBytes = aesSecretKey.getEncoded();
            String base64AesKey = Base64.getEncoder().encodeToString(aesKeyBytes);

            byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(publicKeyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PublicKey pubKey = kf.generatePublic(spec);

            Cipher rsaCipher = Cipher.getInstance("RSA");
            rsaCipher.init(Cipher.ENCRYPT_MODE, pubKey);
            byte[] encryptedBytes =
                    rsaCipher.doFinal(base64AesKey.getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(encryptedBytes);
        } catch (Exception e) {
            throw new EncryptionException("Failed to encrypt AES key with RSA", e);
        }
    }

    /**
     * 加密/解密操作失败时抛出的异常。
     */
    public static class EncryptionException extends RuntimeException {
        public EncryptionException(String message) {
            super(message);
        }

        public EncryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
