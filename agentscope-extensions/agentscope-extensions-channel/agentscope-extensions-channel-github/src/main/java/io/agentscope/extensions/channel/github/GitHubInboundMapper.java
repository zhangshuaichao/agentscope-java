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
package io.agentscope.extensions.channel.github;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
import java.util.List;
import java.util.Optional;

/**
 * 将 GitHub webhook 交付映射为 {@link InboundMessage}。
 * <p>当前支持两类事件：
 * <ul><li>{@code issue_comment}：Issue 或 PR 评论，二者均触发 {@code issue_comment}，PR 的 {@code issue} 对象带 {@code pull_request} 子对象。</li>
 * <li>{@code pull_request_review_comment}：PR 差异中的行级审查评论。</li></ul>
 * <p>对端模型：{@code peerKind} 为 {@link PeerKind#THREAD}，{@code peerId} 为 {@code "<owner>/<repo>#<number>"}，
 * 出站客户端根据此稳定标识构造评论 URL。
 */
public final class GitHubInboundMapper {

    private final String channelId;

    public GitHubInboundMapper(String channelId) {
        this.channelId = channelId;
    }

    /**
     * 返回去重键 {@code comment.id}；编辑以相同 ID 的 {@code action=edited} 到达，当前实现忽略编辑。
     */
    public static Optional<Long> extractCommentId(JsonNode payload) {
        if (payload == null) {
            return Optional.empty();
        }
        long id = payload.path("comment").path("id").asLong(-1);
        return id > 0 ? Optional.of(id) : Optional.empty();
    }

    /**
     * 返回评论作者数字 ID，供渠道识别机器人自身回流。
     */
    public static Optional<Long> extractCommenterId(JsonNode payload) {
        if (payload == null) {
            return Optional.empty();
        }
        long id = payload.path("comment").path("user").path("id").asLong(-1);
        return id > 0 ? Optional.of(id) : Optional.empty();
    }

    /**
     * 将 {@code issue_comment} 或 {@code pull_request_review_comment} 载荷映射为 {@link InboundMessage}。
     * 仅处理 {@code "created"}，不支持的动作或格式错误返回空值。
     */
    public Optional<InboundMessage> map(String eventType, JsonNode payload) {
        if (payload == null) {
            return Optional.empty();
        }
        // 只响应新建评论，忽略编辑和删除。
        String action = payload.path("action").asText(null);
        if (!"created".equals(action)) {
            return Optional.empty();
        }

        JsonNode repo = payload.path("repository");
        String fullName = repo.path("full_name").asText(null);
        String ownerLogin = repo.path("owner").path("login").asText(null);
        if (fullName == null || fullName.isBlank()) {
            return Optional.empty();
        }

        long number;
        if ("issue_comment".equals(eventType)) {
            number = payload.path("issue").path("number").asLong(-1);
        } else if ("pull_request_review_comment".equals(eventType)) {
            number = payload.path("pull_request").path("number").asLong(-1);
        } else {
            return Optional.empty();
        }
        if (number <= 0) {
            return Optional.empty();
        }

        JsonNode comment = payload.path("comment");
        String body = comment.path("body").asText(null);
        String authorLogin = comment.path("user").path("login").asText(null);
        if (body == null || authorLogin == null) {
            return Optional.empty();
        }

        String peerId = fullName + "#" + number;
        Peer peer = new Peer(PeerKind.THREAD, peerId);
        Msg msg = Msg.builder().role(MsgRole.USER).name(authorLogin).textContent(body).build();
        return Optional.of(
                InboundMessage.builder(channelId, peer, List.of(msg))
                        .accountId(ownerLogin)
                        .senderId(authorLogin)
                        .build());
    }
}
