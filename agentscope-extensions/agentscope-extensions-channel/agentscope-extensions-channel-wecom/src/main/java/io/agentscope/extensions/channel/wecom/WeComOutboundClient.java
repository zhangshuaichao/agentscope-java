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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 企业微信出站 HTTP 客户端，封装 {@code /cgi-bin/message/send}（私聊）和 {@code /cgi-bin/appchat/send}（应用群聊）。发送每个 {@link Msg} 的首个 {@link Msg#getTextContent() text content}。
 *
 * <p>文本包含 Markdown 控制字符时使用 {@code msgtype=markdown}，否则使用纯文本。这只是启发式判断；明确需要 Markdown 的调用方应在内容中使用 Markdown 语法。
 */
public final class WeComOutboundClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int MAX_SNIPPET_LENGTH = 200;

    private final WebClient client;
    private final WeComAccessTokenProvider tokenProvider;
    private final int agentId;

    public WeComOutboundClient(
            String apiBase, WeComAccessTokenProvider tokenProvider, int agentId) {
        this.client = WebClient.builder().baseUrl(apiBase).build();
        this.tokenProvider = tokenProvider;
        this.agentId = agentId;
    }

    /**
     * 将每个 {@code msg} 发送到解析后的对端。地址的 {@code to} 字段形式为 {@code "channelId:peerKey"}，其中 {@code peerKey} 为 {@code "DIRECT:userid"} 或 {@code "GROUP:chatid"}。
     */
    public Mono<Void> send(OutboundAddress address, List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return Mono.empty();
        }
        PeerTarget target = parseAddress(address);
        return Mono.from(
                Mono.defer(() -> tokenProvider.token())
                        .flatMapMany(
                                token ->
                                        reactor.core.publisher.Flux.fromIterable(messages)
                                                .concatMap(msg -> sendOne(token, target, msg)))
                        .then());
    }

    private Mono<Void> sendOne(String token, PeerTarget target, Msg msg) {
        String text = msg.getTextContent();
        if (text == null || text.isBlank()) {
            return Mono.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        boolean useMarkdown = looksLikeMarkdown(text);
        switch (target.kind()) {
            case DIRECT -> {
                body.put("touser", target.id());
                body.put("agentid", agentId);
                body.put("msgtype", useMarkdown ? "markdown" : "text");
                body.put(
                        useMarkdown ? "markdown" : "text",
                        Collections.singletonMap("content", text));
                return post("/cgi-bin/message/send", token, body);
            }
            case GROUP -> {
                body.put("chatid", target.id());
                body.put("msgtype", useMarkdown ? "markdown" : "text");
                body.put(
                        useMarkdown ? "markdown" : "text",
                        Collections.singletonMap("content", text));
                return post("/cgi-bin/appchat/send", token, body);
            }
            default -> {
                return Mono.error(
                        new IllegalArgumentException(
                                "WeCom outbound only supports DIRECT and GROUP peer kinds,"
                                        + " got "
                                        + target.kind()));
            }
        }
    }

    private Mono<Void> post(String path, String token, Map<String, Object> body) {
        return client.post()
                .uri(uri -> uri.path(path).queryParam("access_token", token).build())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                // HTTP 200 但正文为空会使 bodyToMono 空完成；使用默认值进入
                // 下面的验证，避免将未经验证的发送报告为已投递。
                .defaultIfEmpty("")
                .timeout(Duration.ofSeconds(10))
                .doOnNext(this::checkSendResponse)
                .then();
    }

    /**
     * 验证企业微信发送响应正文。企业微信可能用 HTTP 200 加非零 {@code errcode} 报告投递失败；这些拒绝传播为 {@link IllegalStateException}，使调用方的 {@code send} 流以 {@code onError} 结束。空正文也被拒绝。令牌过期错误码会先使缓存令牌失效再失败。
     */
    private void checkSendResponse(String body) {
        JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid WeCom send response: " + truncate(body), e);
        }
        if (!node.path("errcode").isIntegralNumber()) {
            throw new IllegalStateException(
                    "WeCom send response errcode missing: " + truncate(body));
        }
        int errcode = node.path("errcode").asInt();
        if (errcode == 42001 || errcode == 40014) {
            tokenProvider.invalidate();
        }
        if (errcode != 0) {
            throw new IllegalStateException(
                    "WeCom rejected message with errcode="
                            + errcode
                            + ", errmsg="
                            + node.path("errmsg").asText());
        }
    }

    /**
     * 限制错误消息中嵌入的响应正文片段长度。无法解析的响应可能是网关 HTML 页面或回显的请求载荷，而非预期的小型 JSON；消息会传播到调用方的 {@code onError} 处理器。
     *
     * <p>【学习提示】截断仅限制长度，不等于脱敏；应用日志仍须防止错误片段泄露令牌或用户内容。
     */
    private static String truncate(String body) {
        return body.length() <= MAX_SNIPPET_LENGTH
                ? body
                : body.substring(0, MAX_SNIPPET_LENGTH) + "...(" + body.length() + " chars)";
    }

    private static PeerTarget parseAddress(OutboundAddress address) {
        String to = address.to();
        int sep = to.indexOf(':');
        if (sep < 0) {
            return new PeerTarget(PeerKind.DIRECT, to);
        }
        String rest = to.substring(sep + 1);
        int sep2 = rest.indexOf(':');
        String kindRaw;
        String id;
        if (sep2 < 0) {
            kindRaw = "DIRECT";
            id = rest;
        } else {
            kindRaw = rest.substring(0, sep2);
            id = rest.substring(sep2 + 1);
        }
        PeerKind kind;
        try {
            kind = PeerKind.valueOf(kindRaw.toUpperCase());
        } catch (IllegalArgumentException e) {
            kind = PeerKind.DIRECT;
        }
        return new PeerTarget(kind, id);
    }

    private static boolean looksLikeMarkdown(String text) {
        return text.contains("\n")
                || text.contains("**")
                || text.contains("```")
                || text.contains("[");
    }

    private record PeerTarget(PeerKind kind, String id) {}
}
