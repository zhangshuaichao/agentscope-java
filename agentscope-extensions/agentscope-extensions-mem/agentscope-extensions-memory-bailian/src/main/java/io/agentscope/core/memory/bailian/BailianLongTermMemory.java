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
package io.agentscope.core.memory.bailian;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportFactory;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 基于百炼长期记忆服务的实现。
 *
 * <p>集成阿里云百炼记忆服务，通过语义相似性与 LLM 记忆提取提供持久化、可搜索的记忆存储。
 *
 * <p><b>主要能力：</b>
 * <ul><li>基于向量嵌入的语义记忆搜索。</li><li>LLM 驱动的记忆提取与推断。</li>
 * <li>通过 user_id、memory_library_id 区分数据作用域。</li><li>响应式操作接口。</li></ul>
 *
 * <p><b>用法示例：</b>
 * <pre>{@code
 * // 使用 try-with-resources（推荐）
 * try (BailianLongTermMemory memory = BailianLongTermMemory.builder()
 *         .apiKey(System.getenv("DASHSCOPE_API_KEY"))
 *         .userId("user_001")
 *         .memoryLibraryId("memory_library_123")
 *         .projectId(project_id_123)
 *         .profileSchema(profile_schema_123)
 *         .build()) {
 *
 *     // 记录消息
 *     Msg msg = Msg.builder()
 *         .role(MsgRole.USER)
 *         .content("Remind me to drink water at 9 a.m. every day")
 *         .build();
 *     memory.record(List.of(msg)).block();
 *
 *     // 检索记忆
 *     Msg query = Msg.builder()
 *         .role(MsgRole.USER)
 *         .content("What reminder do I have?")
 *         .build();
 *     String memories = memory.retrieve(query).block();
 * }
 * }</pre>
 *
 * <p>【学习提示】长期记忆是从筛选后的对话中提取的持久信息，不是完整会话日志。
 * 用户与记忆库标识用于服务端组织和检索，不替代应用自身的身份认证与授权检查。
 *
 * @see LongTermMemory
 * @see BailianMemoryClient
 */
public class BailianLongTermMemory implements LongTermMemory, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BailianLongTermMemory.class);
    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com";
    private static final Integer DEFAULT_TOP_K = 10;
    private static final Double DEFAULT_MIN_SCORE = 0.3;

    private final BailianMemoryClient client;
    private final String userId;
    private final String memoryLibraryId;
    private final String projectId;
    private final String profileSchema;
    private final Integer topK;
    private final Double minScore;
    private final Boolean enableRerank;
    private final Boolean enableJudge;
    private final Boolean enableRewrite;
    private final Map<String, Object> metadata;

    /**
     * 创建 {@link BailianLongTermMemory} 实例。
     *
     * @param builder 用于配置记忆的构建器
     */
    private BailianLongTermMemory(Builder builder) {
        if (builder.apiKey == null || builder.apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey cannot be null or blank");
        }
        if (builder.userId == null || builder.userId.isBlank()) {
            throw new IllegalArgumentException("userId cannot be null or blank");
        }
        this.userId = builder.userId;
        this.memoryLibraryId = builder.memoryLibraryId;
        this.projectId = builder.projectId;
        this.profileSchema = builder.profileSchema;
        this.topK = builder.topK;
        this.minScore = builder.minScore;
        this.enableRerank = builder.enableRerank;
        this.enableJudge = builder.enableJudge;
        this.enableRewrite = builder.enableRewrite;
        this.metadata = builder.metadata;
        this.client =
                BailianMemoryClient.builder()
                        .apiBaseUrl(builder.apiBaseUrl)
                        .apiKey(builder.apiKey)
                        .httpTransport(builder.httpTransport)
                        .build();
    }

    /**
     * 将消息记录到长期记忆。
     *
     * <p>转换为 BailianMessage，保留角色与内容结构，再发送给百炼 API，由 LLM 提取值得记忆的信息。
     *
     * <p>只记录 USER 与 ASSISTANT；助手消息仅保留不含 ToolUseBlock 的纯回复，排除工具调用请求。
     * TOOL 与 SYSTEM 也被过滤，使输入集中于用户与助手交互。
     *
     * <p>含 &lt;compressed_history&gt; 标记的消息被排除，避免重复存储压缩内容。
     * null 消息和空白文本同样过滤，空列表不会报错。
     *
     * @param msgs 待记录消息列表
     * @return 记录操作完成时结束的 Mono
     */
    @Override
    public Mono<Void> record(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return Mono.empty();
        }

        List<BailianMessage> bailianMessages =
                msgs.stream()
                        .filter(Objects::nonNull)
                        .filter(
                                msg -> {
                                    // 按角色过滤，仅保留 USER 与 ASSISTANT。
                                    MsgRole role = msg.getRole();
                                    if (role != MsgRole.USER && role != MsgRole.ASSISTANT) {
                                        return false;
                                    }

                                    // ASSISTANT 消息排除含 ToolUseBlock 的情况，
                                    // 工具调用请求不记录为记忆。
                                    if (role == MsgRole.ASSISTANT
                                            && msg.hasContentBlocks(ToolUseBlock.class)) {
                                        return false;
                                    }

                                    // 检查非空白文本内容。
                                    String textContent = msg.getTextContent();
                                    if (textContent == null || textContent.isBlank()) {
                                        return false;
                                    }

                                    // 排除压缩历史消息。
                                    if (textContent.contains("<compressed_history>")) {
                                        return false;
                                    }

                                    return true;
                                })
                        .map(this::convertToBailianMessage)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

        if (bailianMessages.isEmpty()) {
            return Mono.empty();
        }

        BailianAddRequest.Builder addRequestBuilder =
                BailianAddRequest.builder().userId(userId).messages(bailianMessages);

        if (memoryLibraryId != null && !memoryLibraryId.isBlank()) {
            addRequestBuilder.memoryLibraryId(memoryLibraryId);
        }

        if (projectId != null && !projectId.isBlank()) {
            addRequestBuilder.projectId(projectId);
        }

        if (profileSchema != null && !profileSchema.isBlank()) {
            addRequestBuilder.profileSchema(profileSchema);
        }

        if (metadata != null && !metadata.isEmpty()) {
            addRequestBuilder.metadata(metadata);
        }

        return client.add(addRequestBuilder.build())
                .doOnError(e -> log.warn("Failed to record Bailian memory", e))
                .onErrorComplete()
                .then();
    }

    /**
     * 根据输入消息检索相关记忆。
     *
     * <p>使用语义搜索，返回按换行拼接的记忆文本；没有相关记忆时返回空字符串。
     *
     * @param msg 用作搜索查询的消息
     * @return 发出记忆文本的 Mono，文本可能为空
     */
    @Override
    public Mono<String> retrieve(Msg msg) {
        if (msg == null) {
            return Mono.just("");
        }

        String query = msg.getTextContent();
        if (query == null || query.isBlank()) {
            return Mono.just("");
        }

        BailianMessage searchMessage = BailianMessage.builder().role("user").content(query).build();

        BailianSearchRequest.Builder searchRequestBuilder =
                BailianSearchRequest.builder()
                        .userId(userId)
                        .messages(List.of(searchMessage))
                        .topK(topK)
                        .minScore(minScore)
                        .enableRerank(enableRerank)
                        .enableJudge(enableJudge)
                        .enableRewrite(enableRewrite);

        if (memoryLibraryId != null && !memoryLibraryId.isBlank()) {
            searchRequestBuilder.memoryLibraryId(memoryLibraryId);
        }

        if (projectId != null && !projectId.isBlank()) {
            searchRequestBuilder.projectIds(List.of(projectId));
        }

        return client.search(searchRequestBuilder.build())
                .map(
                        response -> {
                            if (response.getMemoryNodes() == null
                                    || response.getMemoryNodes().isEmpty()) {
                                return "";
                            }

                            return response.getMemoryNodes().stream()
                                    .map(BailianMemoryNode::getContent)
                                    .filter(s -> s != null && !s.isBlank())
                                    .collect(Collectors.joining("\n"));
                        })
                .doOnError(e -> log.warn("Failed to retrieve Bailian memory", e))
                .onErrorReturn("");
    }

    /**
     * 将 Msg 转换为 BailianMessage。
     *
     * <p>角色映射：
     * <ul><li>USER → "user"。</li><li>ASSISTANT → "assistant"，仅限不含 ToolUseBlock 的纯回复。</li></ul>
     *
     * <p>不支持的 TOOL、SYSTEM 及含 ToolUseBlock 的 ASSISTANT 消息返回 null，由调用方过滤。
     */
    private BailianMessage convertToBailianMessage(Msg msg) {
        String role =
                switch (msg.getRole()) {
                    case USER -> "user";
                    case ASSISTANT -> "assistant";
                    default -> null; // 过滤不支持的消息类型。
                };

        if (role == null) {
            return null;
        }

        return BailianMessage.builder().role(role).content(msg.getTextContent()).build();
    }

    /**
     * 创建 {@link BailianLongTermMemory} 构建器。
     *
     * @return 新构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** 关闭 BailianMemoryClient 并释放资源。 */
    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }

    /** {@link BailianLongTermMemory} 的构建器。 */
    public static class Builder {
        private String apiKey;
        private String apiBaseUrl = DEFAULT_BASE_URL;
        private String userId;
        private String memoryLibraryId;
        private String projectId;
        private String profileSchema;
        private Integer topK = DEFAULT_TOP_K;
        private Double minScore = DEFAULT_MIN_SCORE;
        private Boolean enableRerank = false;
        private Boolean enableJudge = false;
        private Boolean enableRewrite = false;
        private Map<String, Object> metadata;
        private HttpTransport httpTransport = HttpTransportFactory.getDefault();

        /**
         * 设置百炼 API 密钥。
         *
         * @param apiKey 百炼 API 密钥
         * @return 当前构建器
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 设置百炼 API 基础 URL。
         *
         * @param apiBaseUrl 百炼 API 基础 URL
         * @return 当前构建器
         */
        public Builder apiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
            return this;
        }

        /**
         * 设置用于组织记忆的用户标识（必填）。
         *
         * @param userId 用于组织记忆的用户标识（必填）
         * @return 当前构建器
         */
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        /**
         * 设置记忆库标识。
         *
         * @param memoryLibraryId 记忆库标识
         * @return 当前构建器
         */
        public Builder memoryLibraryId(String memoryLibraryId) {
            this.memoryLibraryId = memoryLibraryId;
            return this;
        }

        /**
         * 设置项目标识。
         *
         * @param projectId 项目标识
         * @return 当前构建器
         */
        public Builder projectId(String projectId) {
            this.projectId = projectId;
            return this;
        }

        /**
         * 设置画像模式标识。
         *
         * @param profileSchema 画像模式标识
         * @return 当前构建器
         */
        public Builder profileSchema(String profileSchema) {
            this.profileSchema = profileSchema;
            return this;
        }

        /**
         * 设置最多搜索结果数。
         *
         * @param topK 最多搜索结果数
         * @return 当前构建器
         */
        public Builder topK(Integer topK) {
            this.topK = topK;
            return this;
        }

        /**
         * 设置最低相似度分数阈值 [0,1]。
         *
         * @param minScore 最低相似度分数阈值 [0,1]
         * @return 当前构建器
         */
        public Builder minScore(Double minScore) {
            this.minScore = minScore;
            return this;
        }

        /**
         * 设置重排序开关，true 启用，false 禁用。
         *
         * @param enableRerank 重排序开关，true 启用，false 禁用
         * @return 当前构建器
         */
        public Builder enableRerank(Boolean enableRerank) {
            this.enableRerank = enableRerank;
            return this;
        }

        /**
         * 设置意图判别开关，true 启用，false 禁用。
         *
         * @param enableJudge 意图判别开关，true 启用，false 禁用
         * @return 当前构建器
         */
        public Builder enableJudge(Boolean enableJudge) {
            this.enableJudge = enableJudge;
            return this;
        }

        /**
         * 设置查询改写开关，true 启用，false 禁用。
         *
         * @param enableRewrite 查询改写开关，true 启用，false 禁用
         * @return 当前构建器
         */
        public Builder enableRewrite(Boolean enableRewrite) {
            this.enableRewrite = enableRewrite;
            return this;
        }

        /**
         * 设置随记忆保存的自定义元数据映射。
         *
         * @param metadata 随记忆保存的自定义元数据映射
         * @return 当前构建器
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * 设置HTTP 传输实现。
         *
         * @param httpTransport HTTP 传输实现
         * @return 当前构建器
         */
        public Builder httpTransport(HttpTransport httpTransport) {
            this.httpTransport = httpTransport;
            return this;
        }

        /**
         * 构建 {@link BailianLongTermMemory} 实例。
         *
         * @return 新的 {@link BailianLongTermMemory} 实例
         */
        public BailianLongTermMemory build() {
            return new BailianLongTermMemory(this);
        }
    }
}
