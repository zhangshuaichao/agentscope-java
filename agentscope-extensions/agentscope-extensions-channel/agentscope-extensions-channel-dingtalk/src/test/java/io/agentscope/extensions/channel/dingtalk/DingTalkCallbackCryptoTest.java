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

import static io.agentscope.extensions.channel.dingtalk.DingTalkCallbackTestSupport.AES_KEY;
import static io.agentscope.extensions.channel.dingtalk.DingTalkCallbackTestSupport.SECRET;
import static io.agentscope.extensions.channel.dingtalk.DingTalkCallbackTestSupport.encrypt;
import static io.agentscope.extensions.channel.dingtalk.DingTalkCallbackTestSupport.sign;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/** {@link DingTalkCallbackCrypto} 测试：请求头签名校验与 AES 解密。 */
class DingTalkCallbackCryptoTest {

    @Test
    void verifiesValidSignature() throws Exception {
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, null);
        String timestamp = Long.toString(System.currentTimeMillis());
        assertTrue(crypto.verify(timestamp, sign(timestamp)));
    }

    @Test
    void verifiesPaddedTimestampHeader() throws Exception {
        // 请求头先修剪一次，规范化值同时用于新鲜度窗口
        // 和 HMAC 输入，因此带首尾空格的头仍可验证。
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, null);
        String timestamp = Long.toString(System.currentTimeMillis());
        assertTrue(crypto.verify("  " + timestamp + "  ", sign(timestamp)));
    }

    @Test
    void rejectsWrongSignature() throws Exception {
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, null);
        String timestamp = Long.toString(System.currentTimeMillis());
        assertFalse(crypto.verify(timestamp, sign("another-secret", timestamp)));
    }

    @Test
    void rejectsSignatureComputedWithDifferentTimestamp() throws Exception {
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, null);
        String timestamp = Long.toString(System.currentTimeMillis());
        assertFalse(
                crypto.verify(
                        timestamp, sign(Long.toString(System.currentTimeMillis() - 60_000L))));
    }

    @Test
    void rejectsStaleTimestamp() throws Exception {
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, null);
        String timestamp = Long.toString(System.currentTimeMillis() - 2 * 3_600_000L);
        assertFalse(crypto.verify(timestamp, sign(timestamp)));
    }

    @Test
    void rejectsFutureTimestamp() throws Exception {
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, null);
        String timestamp = Long.toString(System.currentTimeMillis() + 2 * 3_600_000L);
        assertFalse(crypto.verify(timestamp, sign(timestamp)));
    }

    @Test
    void rejectsFutureTimestampBeyondClockSkew() throws Exception {
        // 未来时间戳只允许时钟偏差容忍范围，而非完整的一小时
        // 新鲜度窗口，避免捕获的请求头组合在很久之后
        // 仍可重放。
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, null);
        String timestamp = Long.toString(System.currentTimeMillis() + 10 * 60_000L);
        assertFalse(crypto.verify(timestamp, sign(timestamp)));
    }

    @Test
    void rejectsNonNumericTimestamp() {
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, null);
        assertFalse(crypto.verify("not-a-number", "any-sign"));
        assertFalse(crypto.verify(null, "any-sign"));
        assertFalse(crypto.verify(Long.toString(System.currentTimeMillis()), null));
    }

    @Test
    void decryptsEnvelope() throws Exception {
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, AES_KEY);
        String json = "{\"msgtype\":\"text\",\"text\":{\"content\":\"hello\"}}";
        assertEquals(json, crypto.decrypt(encrypt(json, null)));
    }

    @Test
    void ignoresTrailingBytesBeyondMsgLength() throws Exception {
        // 钉钉事件回调变体会在消息后追加接收 ID，
        // 机器人回调应兼容两种信封结构。
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, AES_KEY);
        String json = "{\"msgtype\":\"text\"}";
        assertEquals(json, crypto.decrypt(encrypt(json, "receive-id")));
    }

    @Test
    void decryptRejectsTamperedFraming() throws Exception {
        // CBC 与请求头签名都不提供请求体完整性认证，签名仅覆盖
        // 时间戳头；这里通过结构校验而非密码学认证发现篡改：
        // 破坏首个密文块会扰乱 4 字节 msg_len 字段，
        // 必须拒绝。
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, AES_KEY);
        byte[] cipherBytes = Base64.getDecoder().decode(encrypt("{\"msgtype\":\"text\"}", null));
        cipherBytes[0] ^= 0x55;
        String tampered = Base64.getEncoder().encodeToString(cipherBytes);
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(tampered));
    }

    @Test
    void decryptRejectsTamperedPadding() throws Exception {
        // 破坏最后密文块会扰乱填充区域；严格检查 PKCS#7，
        // 拒绝损坏填充而不是静默接受。
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, AES_KEY);
        byte[] cipherBytes = Base64.getDecoder().decode(encrypt("{\"msgtype\":\"text\"}", null));
        cipherBytes[cipherBytes.length - 1] ^= 0x55;
        String tampered = Base64.getEncoder().encodeToString(cipherBytes);
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(tampered));
    }

    @Test
    void decryptWithoutAesKeyFails() {
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, null);
        assertThrows(IllegalStateException.class, () -> crypto.decrypt("anything"));
    }

    @Test
    void constructorRejectsWrongAesKeyLength() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DingTalkCallbackCrypto(SECRET, "too-short"));
        assertThrows(
                IllegalArgumentException.class, () -> new DingTalkCallbackCrypto(null, AES_KEY));
    }
}
