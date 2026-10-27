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
package io.agentscope.core.memory.reme;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

/**
 * 使用 ReMe 存储的长期记忆实现。
 *
 * <p>ReMe 是面向 AI 应用的记忆层，通过 LLM 提取提供持久化、可搜索的记忆。
 *
 * <p><b>主要能力：</b>
 * <ul><li>LLM 驱动的记忆提取和推断。</li><li>基于工作区区分记忆。</li>
 * <li>从对话轨迹自动总结记忆。</li><li>响应式操作接口。</li></ul>
 *
 * <p><b>记忆隔离：</b>按 userId 组织，映射为 ReMe 的 workspace_id，使不同用户使用独立记忆上下文。
 * 应用仍须验证用户身份与工作区访问权限，不能将任意传入的 ID 当作授权凭据。
 *
 * <p><b>用法示例：</b>
 * <pre>{@code
 * // 创建记忆实例
 * ReMeLongTermMemory memory = ReMeLongTermMemory.builder()
 *     .userId("task_workspace")
 *     .apiBaseUrl("http://localhost:8002")
 *     .build();
 *
 * // 在 ReActAgent 中使用
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(model)
 *     .longTermMemory(memory)
 *     .longTermMemoryMode(LongTermMemoryMode.BOTH)
 *     .build();
 * }</pre>
 *
 * @see LongTermMemory
 * @see ReMeClient
 */
public class ReMeLongTermMemory implements LongTermMemory {

    private final ReMeClient client;
    private final String userId;

    /** 私有构造器，请使用 Builder。 */
    private ReMeLongTermMemory(Builder builder) {
        // 先验证必填字段，再创建 ReMeClient。
        if (builder.userId == null || builder.userId.isEmpty()) {
            throw new IllegalArgumentException("userId is required");
        }
        if (builder.apiBaseUrl == null || builder.apiBaseUrl.isEmpty()) {
            throw new IllegalArgumentException("apiBaseUrl is required");
        }

        this.client = new ReMeClient(builder.apiBaseUrl, builder.timeout);
        this.userId = builder.userId;
    }

    /**
     * 将消息记录到长期记忆。
     *
     * <p>将消息转换为 ReMe 轨迹格式，发送给 ReMe API 提取并存储值得记忆的信息。
     *
     * <p>仅记录 USER 与 ASSISTANT，助手消息必须不含 ToolUseBlock，以排除工具调用请求。
     * TOOL 与 SYSTEM 消息也被过滤，使输入集中在用户与助手交互。
     *
     * <p>排除含 &lt;compressed_history&gt; 标记的消息，避免重复存储压缩信息。
     * null 消息与空白文本也会过滤，空消息列表不会报错。
     *
     * @param msgs 待记录的消息列表
     * @return 记录操作完成时结束的 Mono
     */
    @Override
    public Mono<Void> record(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return Mono.empty();
        }

        // 将 Msg 列表转换为 ReMeMessage 列表。
        // 仅保留 USER 与 ASSISTANT，排除带 ToolUseBlock 的助手消息。
        List<ReMeMessage> remeMessages =
                msgs.stream()
                        .filter(Objects::nonNull)
                        .filter(
                                msg -> {
                                    // 按角色过滤，仅保留 USER 与 ASSISTANT。
                                    MsgRole role = msg.getRole();
                                    if (role != MsgRole.USER && role != MsgRole.ASSISTANT) {
                                        return false;
                                    }

                                    // 助手消息排除带 ToolUseBlock 的情况，
                                    // 工具调用请求不记录为记忆。
                                    if (role == MsgRole.ASSISTANT
                                            && msg.hasContentBlocks(ToolUseBlock.class)) {
                                        return false;
                                    }

                                    // 检查非空文本内容。
                                    String textContent = msg.getTextContent();
                                    if (textContent == null || textContent.isEmpty()) {
                                        return false;
                                    }

                                    // 排除压缩历史消息。
                                    if (textContent.contains("<compressed_history>")) {
                                        return false;
                                    }

                                    return true;
                                })
                        .map(this::convertToReMeMessage)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

        if (remeMessages.isEmpty()) {
            return Mono.empty();
        }

        // 用消息创建对话轨迹。
        ReMeTrajectory trajectory = ReMeTrajectory.builder().messages(remeMessages).build();

        // 构建请求。
        // 注意：userId 作为 ReMe API 的 workspaceId。
        ReMeAddRequest request =
                ReMeAddRequest.builder()
                        .workspaceId(userId)
                        .trajectories(List.of(trajectory))
                        .build();

        // 发送给 ReMe API。
        return client.add(request).then();
    }

    /**
     * 将 Msg 转换为 ReMeMessage。
     *
     * <p>角色映射：
     * <ul><li>USER → "user"。</li><li>ASSISTANT → "assistant"，仅限不含 ToolUseBlock 的纯回复。</li></ul>
     *
     * <p>不支持的 TOOL、SYSTEM 或含 ToolUseBlock 的 ASSISTANT 返回 null，由调用方过滤。
     */
    private ReMeMessage convertToReMeMessage(Msg msg) {
        String role =
                switch (msg.getRole()) {
                    case USER -> "user";
                    case ASSISTANT -> "assistant";
                    default -> null; // 过滤不支持的消息类型。
                };

        if (role == null) {
            return null;
        }

        return ReMeMessage.builder().role(role).content(msg.getTextContent()).build();
    }

    /**
     * 根据输入消息检索相关记忆。
     *
     * <p>以消息内容为查询，只搜索配置工作区中的记忆。
     * 返回换行分隔的记忆片段；没有相关记忆时返回空字符串。
     *
     * <p>【学习提示】优先使用服务端 answer；只有它不可用时才拼接 memory_list。
     * 这两种文本并非保证逐字等价，检索输出也不是完整会话日志或新的用户授权。
     *
     * @param msg 用作搜索查询的消息
     * @return 发出检索记忆文本的 Mono，可能为空
     */
    @Override
    public Mono<String> retrieve(Msg msg) {
        if (msg == null) {
            return Mono.just("");
        }

        String query = msg.getTextContent();
        if (query == null || query.isEmpty()) {
            return Mono.just("");
        }

        // 构建搜索请求。
        // 注意：userId 作为 ReMe API 的 workspaceId。
        ReMeSearchRequest request =
                ReMeSearchRequest.builder().workspaceId(userId).query(query).topK(5).build();

        // 搜索并将响应转换为字符串。
        return client.search(request)
                .map(
                        response -> {
                            // 优先使用 answer，否则使用 memory_list。
                            if (response.getAnswer() != null && !response.getAnswer().isEmpty()) {
                                return response.getAnswer();
                            }

                            List<String> memories = response.getMemories();
                            if (memories == null || memories.isEmpty()) {
                                return "";
                            }

                            return memories.stream()
                                    .filter(Objects::nonNull)
                                    .collect(Collectors.joining("\n"));
                        })
                .onErrorReturn("");
    }

    /**
     * 创建 ReMeLongTermMemory 构建器。
     *
     * @return 新构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** ReMeLongTermMemory 的构建器。 */
    public static class Builder {
        private String userId;
        private String apiBaseUrl;
        private Duration timeout = Duration.ofSeconds(60);

        /**
         * 设置 userId。
         *
         * @param userId 用户标识
         * @return 当前构建器
         */
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        /**
         * 设置 ReMe API 基础 URL。
         *
         * @param apiBaseUrl 基础 URL，例如 "http://localhost:8002"
         * @return 当前构建器
         */
        public Builder apiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
            return this;
        }

        /**
         * 设置 HTTP 请求超时。
         *
         * @param timeout 超时时长
         * @return 当前构建器
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * 构建 ReMeLongTermMemory 实例。
         *
         * @return 新的 ReMeLongTermMemory 实例
         * @throws IllegalArgumentException 缺少必填字段时抛出
         */
        public ReMeLongTermMemory build() {
            return new ReMeLongTermMemory(this);
        }
    }
}
