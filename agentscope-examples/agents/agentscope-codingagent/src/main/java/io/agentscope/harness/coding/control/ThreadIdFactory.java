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
package io.agentscope.harness.coding.control;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 根据 GitHub issue/PR/审查上下文生成确定性线程 ID。
 *
 * <p>将规范键字符串的 SHA-256 哈希转换为 UUID，兼顾唯一性与可读性。
 *
 * <p>Slack 与 Linear 线程 ID 生成延后到后续阶段。
 */
public final class ThreadIdFactory {

    private ThreadIdFactory() {}

    /**
     * GitHub issue 会话的线程 ID。
     *
     * @param owner 仓库所有者
     * @param repo 仓库名
     * @param issueNumber issue 编号
     */
    public static String fromGitHubIssue(String owner, String repo, int issueNumber) {
        return toUUID("github:issue:" + owner + "/" + repo + "#" + issueNumber);
    }

    /**
     * GitHub PR 编码会话的线程 ID，保持分支稳定。
     *
     * @param owner 仓库所有者
     * @param repo 仓库名
     * @param prNumber PR 编号
     */
    public static String fromGitHubPr(String owner, String repo, int prNumber) {
        return toUUID("github:pr:" + owner + "/" + repo + "#" + prNumber);
    }

    /**
     * 指定 PR 审查会话的线程 ID。
     *
     * @param owner 仓库所有者
     * @param repo 仓库名
     * @param prNumber PR 编号
     */
    public static String fromGitHubReviewer(String owner, String repo, int prNumber) {
        return toUUID("github:reviewer:" + owner + "/" + repo + "#" + prNumber);
    }

    /**
     * 从 GitHub issue 或 PR 评论派生的线程 ID。
     *
     * @param owner 仓库所有者
     * @param repo 仓库名
     * @param issueOrPrNumber issue/PR 编号
     */
    public static String fromGitHubComment(String owner, String repo, int issueOrPrNumber) {
        return fromGitHubIssue(owner, repo, issueOrPrNumber);
    }

    /**
     * 钉钉会话（私聊或群聊）的线程 ID。同一会话始终映射到相同线程，使连续消息共享会话状态。
     *
     * @param appKey 企业内部应用键，为不同租户的会话 ID 划分命名空间
     * @param conversationId 钉钉会话 ID（私聊 senderStaffId 或群聊 openConversationId）
     */
    public static String fromDingtalkConversation(String appKey, String conversationId) {
        return toUUID("dingtalk:" + appKey + ":" + conversationId);
    }

    /**
     * 飞书/Lark 聊天（私聊、群聊或线程）的线程 ID。
     *
     * @param tenantKey 飞书租户键，为不同租户聊天 ID 划分命名空间
     * @param chatId 飞书聊天 ID（私聊/群聊为 {@code open_chat_id}，线程会话为 {@code thread_id}）
     */
    public static String fromFeishuChat(String tenantKey, String chatId) {
        return toUUID("feishu:" + tenantKey + ":" + chatId);
    }

    // -----------------------------------------------------------------
    // 内部实现
    // -----------------------------------------------------------------

    static String toUUID(String canonicalKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalKey.getBytes(StandardCharsets.UTF_8));
            // 取前 16 字节作为 UUID 字节，设置 UUID v4 风格的版本/变体位
            byte[] uuidBytes = new byte[16];
            System.arraycopy(hash, 0, uuidBytes, 0, 16);
            uuidBytes[6] = (byte) ((uuidBytes[6] & 0x0f) | 0x40); // 版本 4
            uuidBytes[8] = (byte) ((uuidBytes[8] & 0x3f) | 0x80); // 变体 1
            return uuidBytesToString(uuidBytes);
        } catch (NoSuchAlgorithmException e) {
            return UUID.nameUUIDFromBytes(canonicalKey.getBytes(StandardCharsets.UTF_8)).toString();
        }
    }

    private static String uuidBytesToString(byte[] b) {
        String hex = HexFormat.of().formatHex(b);
        return hex.substring(0, 8)
                + "-"
                + hex.substring(8, 12)
                + "-"
                + hex.substring(12, 16)
                + "-"
                + hex.substring(16, 20)
                + "-"
                + hex.substring(20, 32);
    }
}
