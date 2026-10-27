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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 用于文本发送和更新轮询的最小 iLink JSON 客户端。
 */
public final class WeixinOutboundClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http;
    private final WeixinChannelProperties p;
    private final WeixinCredentialProvider credentials;

    public WeixinOutboundClient(WeixinChannelProperties p, WeixinCredentialProvider credentials) {
        this.p = java.util.Objects.requireNonNull(p, "properties");
        this.credentials = java.util.Objects.requireNonNull(credentials, "credentials");
        this.http =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(p.requestTimeoutMs()))
                        .build();
    }

    public Mono<Void> send(OutboundAddress address, List<Msg> messages) {
        return sendWithContext(address, messages, null);
    }

    public Mono<Void> sendWithContext(OutboundAddress address, List<Msg> messages, String context) {
        return sendWithContext(address, messages, context, () -> {});
    }

    Mono<Void> sendWithContext(
            OutboundAddress address, List<Msg> messages, String context, Runnable beforeSend) {
        return Mono.<Void>fromRunnable(
                        () -> {
                            for (Msg msg : messages) {
                                if (msg.getTextContent() == null
                                        || msg.getTextContent().isBlank()) {
                                    continue;
                                }
                                sendOne(address, msg, context, beforeSend);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 发送一条消息并返回服务方回执。持久保存回复的宿主需要服务方回执，而非本地确认：服务方消息 ID 才能表明 iLink 已接受回复。
     *
     * <p>【学习提示】接受回执不是对端已读证明；也不自动把外部发送与本地收件箱提交变成一个事务。恢复与重试仍需考虑重复投递。
     */
    Mono<String> sendWithReceipt(
            OutboundAddress address, Msg message, String context, Runnable beforeSend) {
        return Mono.fromCallable(() -> sendOne(address, message, context, beforeSend))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private String sendOne(
            OutboundAddress address, Msg message, String context, Runnable beforeSend) {
        String text = message.getTextContent();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Weixin reply is empty");
        }
        String token =
                context != null
                        ? context
                        : (message.getMetadata() == null
                                ? null
                                : (String) message.getMetadata().get("weixinContextToken"));
        try {
            Map<String, Object> item = Map.of("type", 1, "text_item", Map.of("text", text));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from_user_id", "");
            m.put("to_user_id", peer(address));
            m.put("client_id", UUID.randomUUID().toString());
            m.put("message_type", 2);
            m.put("message_state", 2);
            m.put("item_list", List.of(item));
            if (token != null) m.put("context_token", token);
            // iLink 发送成功时只返回消息 ID：成功响应
            // 不包含 ret/errcode。
            com.fasterxml.jackson.databind.JsonNode response =
                    assertSuccess(
                            "sendmessage",
                            post(
                                    "/ilink/bot/sendmessage",
                                    Map.of("msg", m, "base_info", baseInfo()),
                                    beforeSend),
                            "message_id");
            String receipt = response.path("message_id").asText("");
            if (receipt.isBlank()) {
                throw new WeixinOperationException("iLink sendmessage returned no receipt");
            }
            return receipt;
        } catch (WeixinCredentialRejectedException | WeixinOperationException e) {
            // 保留服务方结果异常和凭据失败的原始类型，包装会
            // 向调用方的 onError 隐藏失败原因。
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Weixin send failed", e);
        }
    }

    /**
 * 长轮询窗口之后、放弃请求之前的宽限时间。
 */
    private static final long LONG_POLL_GRACE_MS = 5_000L;

    public JsonNodeResponse updates(String cursor) throws Exception {
        String body =
                post(
                        "/ilink/bot/getupdates",
                        Map.of(
                                "get_updates_buf",
                                cursor == null ? "" : cursor,
                                "base_info",
                                baseInfo()));
        com.fasterxml.jackson.databind.JsonNode response = parse(body);
        requireProviderResult("getupdates", response, "msgs", "get_updates_buf");
        List<com.fasterxml.jackson.databind.JsonNode> messages = new ArrayList<>();
        response.path("msgs").forEach(messages::add);
        return new JsonNodeResponse(
                response.path("ret").asInt(0),
                response.has("errcode") ? response.path("errcode").asInt() : null,
                response.path("errmsg").asText(null),
                messages,
                response.path("get_updates_buf").asText(null),
                response.has("longpolling_timeout_ms")
                        ? response.path("longpolling_timeout_ms").asInt()
                        : null);
    }

    public void notifyStart() throws Exception {
        notifyStart(() -> {});
    }

    void notifyStart(Runnable beforeSend) throws Exception {
        assertSuccess(
                "notifystart",
                post("/ilink/bot/msg/notifystart", Map.of("base_info", baseInfo()), beforeSend));
    }

    public void notifyStop() throws Exception {
        notifyStop(() -> {});
    }

    void notifyStop(Runnable beforeSend) throws Exception {
        assertSuccess(
                "notifystop",
                post("/ilink/bot/msg/notifystop", Map.of("base_info", baseInfo()), beforeSend));
    }

    private String post(String path, Object payload) throws Exception {
        return post(path, payload, () -> {});
    }

    private String post(String path, Object payload, Runnable beforeSend) throws Exception {
        HttpRequest req =
                WeixinProtocolHeaders.authenticatedJsonPost(
                                HttpRequest.newBuilder(
                                                URI.create(p.baseUrl().replaceAll("/$", "") + path))
                                        .timeout(Duration.ofMillis(timeoutFor(path))),
                                token())
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)))
                        .build();
        beforeSend.run();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2)
            throw new WeixinOperationException("iLink HTTP " + r.statusCode());
        return r.body();
    }

    /**
     * {@code getupdates} 在服务方长轮询窗口内保持连接，因此读取超时必须长于 {@code longPollTimeoutMs}；所有控制调用仍使用 {@code requestTimeoutMs}。
     */
    private long timeoutFor(String path) {
        return path.endsWith("getupdates")
                ? Math.max((long) p.longPollTimeoutMs() + LONG_POLL_GRACE_MS, p.requestTimeoutMs())
                : p.requestTimeoutMs();
    }

    private String token() {
        return credentials.current().botToken();
    }

    private Map<String, String> baseInfo() {
        return Map.of("channel_version", p.channelVersion(), "bot_agent", p.botAgent());
    }

    /**
     * 验证服务方结果并返回已解析正文。失败文本只携带服务方数字码：绝不将服务方自身的 {@code errmsg} 复制进异常，因为文本会进入宿主日志，可能嵌入服务方回显的任意内容。
     */
    private static com.fasterxml.jackson.databind.JsonNode assertSuccess(
            String operation, String body, String... outcomeFields) throws Exception {
        com.fasterxml.jackson.databind.JsonNode response = parse(body);
        requireProviderResult(operation, response, outcomeFields);
        int ret = response.path("ret").asInt(0);
        int errcode = response.path("errcode").asInt(0);
        if (ret != 0 || errcode != 0) {
            if (ret == -14 || errcode == -14) {
                throw new WeixinCredentialRejectedException(operation, -14);
            }
            throw new WeixinOperationException(
                    "iLink " + operation + " failed ret=" + ret + " errcode=" + errcode);
        }
        return response;
    }

    /**
     * 解析服务方正文，但不允许 Jackson 引用正文片段：响应包含收件箱载荷和上下文令牌，而默认解析错误会嵌入部分输入。
     */
    private static com.fasterxml.jackson.databind.JsonNode parse(String body) throws Exception {
        try {
            return JSON.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            com.fasterxml.jackson.core.JsonLocation where = error.getLocation();
            throw new WeixinOperationException(
                    "iLink returned an unparseable response"
                            + (where == null
                                    ? ""
                                    : " near line "
                                            + where.getLineNr()
                                            + ", column "
                                            + where.getColumnNr()));
        }
    }

    /**
     * 服务方响应必须明确表明结果。若把缺失的 {@code ret} 默认为零，任何 2xx 正文（空对象、以 JSON 呈现的代理错误页）都会被当作消息已投递，渠道随后完成收件箱认领并丢弃回复。
     *
     * <p>{@code getupdates} 是唯一无需 {@code ret}/{@code errcode} 即可报告成功的调用：空闲长轮询只返回批次字段（{@code msgs}、{@code get_updates_buf}），不返回结果码。这些字段本身表示结果；既无状态字段也无任何 {@code outcomeFields} 的轮询正文仍会被拒绝，放宽规则不能将无关 2xx 正文误当为空批次。
     */
    private static void requireProviderResult(
            String operation,
            com.fasterxml.jackson.databind.JsonNode response,
            String... outcomeFields) {
        if (response.has("ret") || response.has("errcode")) {
            return;
        }
        for (String field : outcomeFields) {
            if (response.has(field)) {
                return;
            }
        }
        throw new WeixinOperationException(
                "iLink " + operation + " response carries no ret/errcode");
    }

    private static String peer(OutboundAddress a) {
        String s = a.to();
        int i = s.lastIndexOf(':');
        return i < 0 ? s : s.substring(i + 1);
    }

    public record JsonNodeResponse(
            int ret,
            Integer errcode,
            String errmsg,
            List<com.fasterxml.jackson.databind.JsonNode> msgs,
            String get_updates_buf,
            Integer longpolling_timeout_ms) {}
}
