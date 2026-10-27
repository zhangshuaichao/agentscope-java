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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 腾讯 iLink 个人微信账户可复用的二维码登录客户端。
 *
 * <p><b>状态 URL 携带登录秘密。</b>协议将 {@code qrcode}（以及需要时的 {@code verify_code}）作为 {@code get_qrcode_status} 的 GET 查询参数，因此请求 URI 属于短期凭据材料，不可随意记录。不要添加请求 URI 日志、通用 HTTP 客户端拦截器，或将 URI 复制到异常消息中的包装器。失败仅按状态码和解析位置报告，参见 {@link #response}。
 */
public final class WeixinLoginClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http;
    private final String baseUrl;
    private final Duration timeout;

    public WeixinLoginClient() {
        this(WeixinChannelProperties.DEFAULT_BASE_URL, Duration.ofSeconds(45));
    }

    public WeixinLoginClient(String baseUrl, Duration timeout) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.timeout = java.util.Objects.requireNonNull(timeout, "timeout");
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public WeixinLoginChallenge start(String botType) throws Exception {
        String normalizedBotType = botType == null ? "3" : botType.strip();
        if (!normalizedBotType.matches("[0-9]+")) {
            throw new IllegalArgumentException("botType must be numeric");
        }
        JsonNode n =
                post(
                        "/ilink/bot/get_bot_qrcode?bot_type=" + normalizedBotType,
                        Map.of("local_token_list", List.of()));
        return new WeixinLoginChallenge(
                new WeixinLoginSession(required(n, "qrcode"), baseUrl),
                required(n, "qrcode_img_content"));
    }

    public WeixinLoginStep poll(WeixinLoginSession session) throws Exception {
        return observe(session, null);
    }

    public WeixinLoginStep verify(WeixinLoginSession session, String verifyCode) throws Exception {
        if (verifyCode == null || verifyCode.isBlank()) {
            throw new IllegalArgumentException("verifyCode must not be blank");
        }
        return observe(session, verifyCode.strip());
    }

    private WeixinLoginStep observe(WeixinLoginSession session, String verifyCode)
            throws Exception {
        java.util.Objects.requireNonNull(session, "session");
        String path =
                "/ilink/bot/get_qrcode_status?qrcode="
                        + java.net.URLEncoder.encode(
                                session.qrcode(), java.nio.charset.StandardCharsets.UTF_8);
        if (verifyCode != null && !verifyCode.isBlank())
            path +=
                    "&verify_code="
                            + java.net.URLEncoder.encode(
                                    verifyCode, java.nio.charset.StandardCharsets.UTF_8);
        String pollingBaseUrl =
                validateProviderEndpoint(baseUrl, normalizeBaseUrl(session.pollingBaseUrl()));
        JsonNode n = get(pollingBaseUrl, path);
        String status = n.path("status").asText("unknown");
        WeixinLoginSession updatedSession = session;
        String redirectHost = n.path("redirect_host").asText(null);
        if ("scaned_but_redirect".equalsIgnoreCase(status)
                && redirectHost != null
                && !redirectHost.isBlank()) {
            updatedSession =
                    session.withPollingBaseUrl(
                            validateProviderEndpoint(
                                    session.pollingBaseUrl(), normalizeBaseUrl(redirectHost)));
        }
        String token = n.path("bot_token").asText(null);
        WeixinCredentials credentials =
                token == null || token.isBlank() ? null : new WeixinCredentials(token);
        String returnedBaseUrl = n.path("baseurl").asText(null);
        if (returnedBaseUrl != null && !returnedBaseUrl.isBlank()) {
            returnedBaseUrl =
                    validateProviderEndpoint(
                            updatedSession.pollingBaseUrl(), normalizeBaseUrl(returnedBaseUrl));
        }
        return new WeixinLoginStep(
                updatedSession,
                status,
                credentials,
                n.path("ilink_bot_id").asText(null),
                n.path("ilink_user_id").asText(null),
                returnedBaseUrl);
    }

    private JsonNode post(String path, Object body) throws Exception {
        HttpRequest r =
                WeixinProtocolHeaders.jsonPost(request(baseUrl, path))
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                        .build();
        return response(r);
    }

    private JsonNode get(String pollingBaseUrl, String path) throws Exception {
        return response(
                WeixinProtocolHeaders.application(request(pollingBaseUrl, path)).GET().build());
    }

    private HttpRequest.Builder request(String targetBaseUrl, String path) {
        return HttpRequest.newBuilder(URI.create(targetBaseUrl + path)).timeout(timeout);
    }

    private static String normalizeBaseUrl(String raw) {
        return WeixinEndpointPolicy.normalizeBaseUrl(raw);
    }

    private static String validateProviderEndpoint(String current, String candidate) {
        return WeixinEndpointPolicy.validateProviderEndpoint(current, candidate);
    }

    private JsonNode response(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2)
            throw new IllegalStateException("iLink HTTP " + response.statusCode());
        try {
            return JSON.readTree(response.body());
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            // 有意不保留响应正文片段：登录响应携带 bot_token，Jackson
            // 默认错误消息会引用输入中出错的词元。
            com.fasterxml.jackson.core.JsonLocation where = error.getLocation();
            throw new IllegalStateException(
                    "iLink returned an unparseable login response"
                            + (where == null
                                    ? ""
                                    : " near line "
                                            + where.getLineNr()
                                            + ", column "
                                            + where.getColumnNr()));
        }
    }

    private static String required(JsonNode n, String field) {
        String value = n.path(field).asText(null);
        if (value == null || value.isBlank())
            throw new IllegalStateException("iLink response missing " + field);
        return value;
    }
}
