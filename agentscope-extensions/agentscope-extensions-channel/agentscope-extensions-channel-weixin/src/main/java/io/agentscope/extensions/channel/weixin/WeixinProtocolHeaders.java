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
package io.agentscope.extensions.channel.weixin;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * iLink 协议所需的公共应用和认证请求头。
 */
final class WeixinProtocolHeaders {
    static final String APP_ID = "bot";

    // 编码为 major << 16 | minor << 8 | patch。在成为可配置平台能力前，
    // 保持与上游 2.4.9 协议实现兼容。
    static final String APP_CLIENT_VERSION = "132105";

    private static final SecureRandom RANDOM = new SecureRandom();

    private WeixinProtocolHeaders() {}

    static HttpRequest.Builder application(HttpRequest.Builder request) {
        return request.header("iLink-App-Id", APP_ID)
                .header("iLink-App-ClientVersion", APP_CLIENT_VERSION);
    }

    static HttpRequest.Builder jsonPost(HttpRequest.Builder request) {
        return application(request)
                .header("Content-Type", "application/json")
                .header("AuthorizationType", "ilink_bot_token")
                .header("X-WECHAT-UIN", randomWechatUin());
    }

    static HttpRequest.Builder authenticatedJsonPost(HttpRequest.Builder request, String botToken) {
        return jsonPost(request).header("Authorization", "Bearer " + botToken.strip());
    }

    private static String randomWechatUin() {
        long value = Integer.toUnsignedLong(RANDOM.nextInt());
        return Base64.getEncoder()
                .encodeToString(Long.toString(value).getBytes(StandardCharsets.UTF_8));
    }
}
