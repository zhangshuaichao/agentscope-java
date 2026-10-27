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

import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import java.io.StringReader;
import java.util.Objects;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import reactor.core.publisher.Mono;

/**
 * 处理企业微信回调 URL 握手（GET）和入站加密消息投递（POST）。
 *
 * <p>每个 {@link WeComChannel} 在启动时向控制器注册；控制器按 URL 路径提取的 {@code channelId} 分发请求。避免在 Spring 侧动态注册映射，使组装易于测试。
 */
@RestController
@RequestMapping("/api/channels/wecom")
public class WeComCallbackController {

    private static final Logger log = LoggerFactory.getLogger(WeComCallbackController.class);
    private static final DocumentBuilderFactory DBF = newSafeDocumentBuilderFactory();

    private final WeComChannelRegistry registry;

    public WeComCallbackController() {
        this(WeComChannelRegistry.instance());
    }

    /**
 * 为测试开放，可注入新的注册表。
 */
    WeComCallbackController(WeComChannelRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * URL 验证握手。企业微信发送加密的 {@code echostr}，服务端必须解密后原样返回，让企业微信确认服务端持有正确的编码 AES 密钥。
     */
    @GetMapping(value = "/{channelId}/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(
            @org.springframework.web.bind.annotation.PathVariable String channelId,
            @RequestParam("msg_signature") String signature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            @RequestParam("echostr") String echostr) {
        WeComChannel channel = registry.get(channelId);
        if (channel == null) {
            log.warn("WeCom verify: no channel registered for id='{}'", channelId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        WeComCrypto crypto = channel.crypto();
        if (!crypto.verifySignature(signature, timestamp, nonce, echostr)) {
            log.warn("WeCom verify: signature mismatch for channelId='{}'", channelId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            String plain = crypto.decrypt(echostr);
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(plain);
        } catch (RuntimeException e) {
            log.warn(
                    "WeCom verify: decrypt failed for channelId='{}': {}",
                    channelId,
                    e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }

    /**
     * 入站加密消息，请求体为包含 {@code <Encrypt>...</Encrypt>} 的 XML。验证签名、解密、按 {@code MsgId} 去重、应用机器人循环保护，再分发到渠道路由。正常处理与分发错误后返回 {@code 200}，避免企业微信重试；未知渠道、无效签名、缺少加密正文或解密失败会返回相应错误状态。
     *
     * <p>【学习提示】去重标记先于智能体执行写入，分发失败也确认成功；这优先防止重试风暴，并非“业务已成功”或持久投递保证。需要可靠重试的宿主应在自身持久队列边界处理，而不能仅依赖 webhook 返回码。
     */
    @PostMapping(
            value = "/{channelId}/callback",
            consumes = MediaType.APPLICATION_XML_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<ResponseEntity<String>> dispatch(
            @org.springframework.web.bind.annotation.PathVariable String channelId,
            @RequestParam("msg_signature") String signature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            @RequestBody String body) {
        WeComChannel channel = registry.get(channelId);
        if (channel == null) {
            log.warn("WeCom dispatch: no channel registered for id='{}'", channelId);
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
        }
        String encrypt = extractEncrypt(body);
        if (encrypt == null) {
            log.warn("WeCom dispatch: missing <Encrypt> in body (channelId='{}')", channelId);
            return Mono.just(ResponseEntity.badRequest().body(""));
        }
        if (!channel.crypto().verifySignature(signature, timestamp, nonce, encrypt)) {
            log.warn("WeCom dispatch: signature mismatch (channelId='{}')", channelId);
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }
        String xml;
        try {
            xml = channel.crypto().decrypt(encrypt);
        } catch (RuntimeException e) {
            log.warn(
                    "WeCom dispatch: decrypt failed (channelId='{}'): {}",
                    channelId,
                    e.getMessage());
            return Mono.just(ResponseEntity.badRequest().body(""));
        }

        // 幂等性：重复 MsgId 静默返回 200，阻止企业微信继续重试。
        Optional<String> msgId = WeComInboundMapper.extractMsgId(xml);
        if (msgId.isPresent() && !channel.idempotency().firstSeen(channelId + "|" + msgId.get())) {
            log.debug(
                    "WeCom dispatch: duplicate msgId={} (channelId='{}')", msgId.get(), channelId);
            return Mono.just(ResponseEntity.ok(""));
        }

        Optional<InboundMessage> inbound = channel.mapper().map(xml);
        if (inbound.isEmpty()) {
            // 未映射的载荷或无内容：确认接收，阻止企业微信继续重试。
            return Mono.just(ResponseEntity.ok(""));
        }
        InboundMessage in = inbound.get();
        // 按会话对端键执行机器人循环保护。
        if (!channel.botLoopGuard().allow(in.peer().key())) {
            log.warn(
                    "WeCom dispatch: bot-loop guard tripped for peer='{}' (channelId='{}')",
                    in.peer().key(),
                    channelId);
            return Mono.just(ResponseEntity.ok(""));
        }

        // 分发给渠道；渠道负责出站回复投递。
        return channel.dispatch(in)
                .then(Mono.just(ResponseEntity.ok("")))
                .onErrorResume(
                        err -> {
                            log.warn(
                                    "WeCom dispatch: agent run failed (channelId='{}'): {}",
                                    channelId,
                                    err.getMessage());
                            // 仍向企业微信返回 2xx，避免重试风暴。
                            return Mono.just(ResponseEntity.ok(""));
                        });
    }

    private static String extractEncrypt(String xml) {
        if (xml == null || xml.isBlank()) {
            return null;
        }
        try {
            DocumentBuilder builder = DBF.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            Element root = doc.getDocumentElement();
            NodeList list = root.getElementsByTagName("Encrypt");
            if (list == null || list.getLength() == 0) {
                return null;
            }
            Node node = list.item(0);
            String v = node != null ? node.getTextContent() : null;
            return (v == null || v.isBlank()) ? null : v.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static DocumentBuilderFactory newSafeDocumentBuilderFactory() {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (Exception ignored) {
            // 尽力加强解析安全性。
        }
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f;
    }
}
