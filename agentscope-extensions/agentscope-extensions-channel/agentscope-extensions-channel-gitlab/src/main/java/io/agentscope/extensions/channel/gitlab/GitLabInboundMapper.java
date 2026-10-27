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
package io.agentscope.extensions.channel.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
import java.util.List;
import java.util.Optional;

/**
 * 将 GitLab {@code Note Hook} 载荷映射为 {@link InboundMessage}。
 * <p>当前只映射 Issue 和 MergeRequest 笔记，丢弃 Commit 与 Snippet；
 * 通过 {@code object_attributes.system} 排除标签或状态变化等系统笔记。
 * <p>对端模型：{@code peerKind} 为 {@link PeerKind#THREAD}，
 * {@code peerId} 为 {@code "<project.path_with_namespace>#<iid>:<noteable_type>"}；
 * 尾部 {@code :Issue|MergeRequest} 用于出站分发器选择正确端点。
 */
public final class GitLabInboundMapper {

    private final String channelId;

    public GitLabInboundMapper(String channelId) {
        this.channelId = channelId;
    }

    /**
     * 返回用作去重键的 {@code object_attributes.id}。
     */
    public static Optional<Long> extractNoteId(JsonNode payload) {
        if (payload == null) {
            return Optional.empty();
        }
        long id = payload.path("object_attributes").path("id").asLong(-1);
        return id > 0 ? Optional.of(id) : Optional.empty();
    }

    /**
     * 返回用于检测机器人自身回流的 {@code user.id}。
     */
    public static Optional<Long> extractAuthorId(JsonNode payload) {
        if (payload == null) {
            return Optional.empty();
        }
        long id = payload.path("user").path("id").asLong(-1);
        return id > 0 ? Optional.of(id) : Optional.empty();
    }

    /**
     * 将 Note Hook 载荷映射为 {@link InboundMessage}；不支持的 Commit／Snippet 类型、系统笔记或格式错误返回空值。
     */
    public Optional<InboundMessage> map(JsonNode payload) {
        if (payload == null) {
            return Optional.empty();
        }
        JsonNode attrs = payload.path("object_attributes");
        if (attrs.isMissingNode() || !attrs.isObject()) {
            return Optional.empty();
        }
        if (attrs.path("system").asBoolean(false)) {
            return Optional.empty();
        }
        String noteableType = attrs.path("noteable_type").asText(null);
        if (!"Issue".equals(noteableType) && !"MergeRequest".equals(noteableType)) {
            return Optional.empty();
        }

        long iid;
        if ("Issue".equals(noteableType)) {
            iid = payload.path("issue").path("iid").asLong(-1);
        } else {
            iid = payload.path("merge_request").path("iid").asLong(-1);
        }
        if (iid <= 0) {
            return Optional.empty();
        }

        String pathWithNamespace = payload.path("project").path("path_with_namespace").asText(null);
        String namespace = payload.path("project").path("namespace").asText(null);
        if (pathWithNamespace == null || pathWithNamespace.isBlank()) {
            return Optional.empty();
        }

        String note = attrs.path("note").asText(null);
        String username = payload.path("user").path("username").asText(null);
        if (note == null || username == null) {
            return Optional.empty();
        }

        String peerId = pathWithNamespace + "#" + iid + ":" + noteableType;
        Peer peer = new Peer(PeerKind.THREAD, peerId);
        Msg msg = Msg.builder().role(MsgRole.USER).name(username).textContent(note).build();
        return Optional.of(
                InboundMessage.builder(channelId, peer, List.of(msg))
                        .accountId(namespace)
                        .senderId(username)
                        .build());
    }
}
