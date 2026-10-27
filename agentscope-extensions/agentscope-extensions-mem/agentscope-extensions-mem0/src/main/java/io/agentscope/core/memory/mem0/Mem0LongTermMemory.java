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
package io.agentscope.core.memory.mem0;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

/**
 * 使用 Mem0 存储的长期记忆实现。
 *
 * <p>Mem0 是面向 AI 应用的记忆层，使用向量嵌入与 LLM 提取提供持久化、可搜索记忆。
 *
 * <p><b>主要能力：</b>
 * <ul><li>向量语义记忆搜索。</li><li>LLM 记忆提取与推断。</li><li>按智能体、用户、运行组织数据。</li>
 * <li>以自定义元数据标记和过滤记忆。</li><li>响应式操作接口。</li></ul>
 *
 * <p><b>记忆作用域：</b>
 * <ul><li><b>agentId：</b>智能体标识，可选。</li><li><b>userId：</b>用户/工作区标识，可选。</li>
 * <li><b>runId：</b>会话/运行标识，可选。</li><li><b>metadata：</b>附加过滤键值对，可选。</li></ul>
 * 至少需要一个标识。检索时根据构建出的过滤条件限定结果。
 *
 * <p><b>用法示例：</b>
 * <pre>{@code
 * // 创建带身份验证的记忆实例
 * Mem0LongTermMemory memory = Mem0LongTermMemory.builder()
 *     .agentName("Assistant")
 *     .userId("user_123")
 *     .apiBaseUrl("http://localhost:8000")
 *     .apiKey(System.getenv("MEM0_API_KEY"))
 *     .build();
 *
 * // 用于不需要身份验证的本地部署
 * Mem0LongTermMemory localMemory = Mem0LongTermMemory.builder()
 *     .agentName("Assistant")
 *     .userId("user_123")
 *     .apiBaseUrl("http://localhost:8000")
 *     .build();
 *
 * // 用于自行托管的 Mem0
 * Mem0LongTermMemory selfHostedMemory = Mem0LongTermMemory.builder()
 *     .agentName("Assistant")
 *     .userId("user_123")
 *     .apiBaseUrl("http://localhost:8000")
 *     .apiType(Mem0ApiType.SELF_HOSTED)  // 指定自行托管的 API 类型
 *     .build();
 *
 * // 使用自定义元数据进行过滤
 * Map<String, Object> metadata = new HashMap<>();
 * metadata.put("category", "travel");
 * metadata.put("project_id", "proj_001");
 *
 * Mem0LongTermMemory memoryWithMetadata = Mem0LongTermMemory.builder()
 *     .agentName("Assistant")
 *     .userId("user_123")
 *     .apiBaseUrl("http://localhost:8000")
 *     .metadata(metadata)  // 用于存储和过滤的自定义元数据
 *     .build();
 *
 * // 记录消息（元数据会与记忆一起存储）
 * Msg msg = Msg.builder()
 *     .role(MsgRole.USER)
 *     .content("I prefer homestays when traveling")
 *     .build();
 *
 * memory.record(List.of(msg)).block();
 *
 * // 检索相关记忆（元数据用作过滤条件）
 * Msg query = Msg.builder()
 *     .role(MsgRole.USER)
 *     .content("What are my travel preferences?")
 *     .build();
 *
 * String memories = memory.retrieve(query).block();
 * // 结果："User prefers homestays when traveling"
 * }</pre>
 *
 * <p>【学习提示】这些标识是数据组织维度，不是访问授权凭证。应用应限制可设置的标识和元数据，
 * 并保证写入与检索使用一致作用域；本实现不会自动备份失败写入或保证异步记忆立即可见。
 *
 * @see LongTermMemory
 * @see Mem0Client
 */
public class Mem0LongTermMemory implements LongTermMemory {

    private final Mem0Client client;
    private final String agentId;
    private final String userId;
    private final String runId;

    /**
     * 随记忆保存并在检索时用作过滤的自定义元数据。
     *
     * <p>用途：
     * <ul><li>放入 {@code metadata} 字段，在 {@link #record(List)} 记录时发送。</li>
     * <li>放入 {@code filters} 字段，在 {@link #retrieve(Msg)} 检索时发送。</li></ul>
     *
     * <p>可用于类别标签、项目/租户等业务属性过滤，以及保存所有记忆共有的附加上下文。
     */
    private final Map<String, Object> metadata;

    /** 私有构造器，请使用 Builder。 */
    private Mem0LongTermMemory(Builder builder) {
        Mem0ApiType apiType = builder.apiType != null ? builder.apiType : Mem0ApiType.PLATFORM;
        this.client = new Mem0Client(builder.apiBaseUrl, builder.apiKey, apiType, builder.timeout);
        this.agentId = builder.agentName;
        this.userId = builder.userId;
        this.runId = builder.runName;
        this.metadata = builder.metadata;

        // 验证至少提供一个标识。
        if (agentId == null && userId == null && runId == null) {
            throw new IllegalArgumentException(
                    "At least one of agentName, userName, or runName must be provided");
        }
    }

    /**
     * 将消息记录到长期记忆。
     *
     * <p>将消息转换为 Mem0Message，保留角色与内容结构，发送给 Mem0 进行 LLM 记忆提取。
     *
     * <p>过滤 null 消息、空文本和压缩历史标记消息，空消息列表不会报错。
     *
     * <p>【学习提示】此适配器与百炼/ReMe 的角色过滤不同：SYSTEM 映射为 user，TOOL 映射为 assistant。
     * 调用方需在记录前决定哪些上下文允许持久化，避免把内部指令或敏感工具输出当作用户事实保存。
     *
     * @param msgs 待记录消息列表
     * @return 添加请求结束时完成的 Mono；服务端异步提取未必已完成
     */
    @Override
    public Mono<Void> record(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return Mono.empty();
        }

        // 将 Msg 列表转换为 Mem0Message 列表。
        List<Mem0Message> mem0Messages =
                msgs.stream()
                        .filter(Objects::nonNull)
                        .filter(
                                msg ->
                                        msg.getTextContent() != null
                                                && !msg.getTextContent().isEmpty()
                                                && !msg.getTextContent()
                                                        .contains("<compressed_history>"))
                        .map(this::convertToMem0Message)
                        .collect(Collectors.toList());

        if (mem0Messages.isEmpty()) {
            return Mono.empty();
        }

        // 将消息发送给 Mem0。
        Mem0AddRequest request =
                Mem0AddRequest.builder()
                        .messages(mem0Messages)
                        .agentId(agentId)
                        .userId(userId)
                        .runId(runId)
                        .metadata(metadata)
                        .infer(true)
                        .build();

        return client.add(request).then();
    }

    /**
     * 将 Msg 转换为 Mem0Message。
     *
     * <p>角色映射：
     * <ul><li>USER → "user"。</li><li>ASSISTANT → "assistant"。</li>
     * <li>SYSTEM → "user"，作为用户上下文。</li><li>TOOL → "assistant"，作为助手上下文。</li></ul>
     */
    private Mem0Message convertToMem0Message(Msg msg) {
        String role =
                switch (msg.getRole()) {
                    case USER, SYSTEM -> "user";
                    case ASSISTANT, TOOL -> "assistant";
                };

        return Mem0Message.builder().role(role).content(msg.getTextContent()).build();
    }

    /**
     * 根据给定查询构建搜索请求。
     *
     * <p>请求包含：
     * <ul><li>通过构建器便捷方法添加的 userId、agentId、runId 标准过滤条件。</li>
     * <li>通过 builder.getFilters() 合并的自定义元数据过滤条件。</li></ul>
     *
     * <p>【学习提示】putAll 可覆盖同名 agent_id/run_id 等键；build 会再同步非 null 的 userId。
     * 因此自定义元数据不应允许不受信任输入覆盖作用域键，过滤条件也不能代替服务端授权。
     *
     * @param query 搜索查询字符串
     * @return 已配置的 v2 API Mem0SearchRequest
     */
    private Mem0SearchRequest buildSearchRequest(String query) {
        Mem0SearchRequest.Builder builder =
                Mem0SearchRequest.builder()
                        .query(query)
                        .userId(userId)
                        .agentId(agentId)
                        .runId(runId)
                        .topK(5);

        // 存在自定义元数据时将其合并到 filters。
        if (metadata != null && !metadata.isEmpty()) {
            builder.getFilters().putAll(metadata);
        }

        return builder.build();
    }

    /**
     * 根据输入消息检索相关记忆。
     *
     * <p>按消息内容进行语义搜索，返回换行分隔的记忆文本；无相关记忆时返回空字符串。
     * 结果由请求中的 agentId、userId、runId 及自定义过滤条件限定。
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

        return client.search(buildSearchRequest(query))
                .map(
                        response -> {
                            if (response.getResults() == null || response.getResults().isEmpty()) {
                                return "";
                            }

                            return response.getResults().stream()
                                    .map(Mem0SearchResult::getMemory)
                                    .filter(Objects::nonNull)
                                    .collect(Collectors.joining("\n"));
                        })
                .onErrorReturn("");
    }

    /**
     * 创建 Mem0LongTermMemory 构建器。
     *
     * @return 新构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Mem0LongTermMemory 的构建器。 */
    public static class Builder {
        private String agentName;
        private String userId;
        private String runName;
        private String apiBaseUrl;
        private String apiKey;
        private Mem0ApiType apiType;
        private Duration timeout = Duration.ofSeconds(60);
        private Map<String, Object> metadata;

        /**
         * 设置智能体名称标识。
         *
         * @param agentName 智能体名称
         * @return 当前构建器
         */
        public Builder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        /**
         * 设置用户标识。
         *
         * @param userId 用户 ID
         * @return 当前构建器
         */
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        /**
         * 设置运行名称标识。
         *
         * @param runName 运行/会话名称或 ID
         * @return 当前构建器
         */
        public Builder runName(String runName) {
            this.runName = runName;
            return this;
        }

        /**
         * 设置 Mem0 API 基础 URL。
         *
         * @param apiBaseUrl 基础 URL，例如 "http://localhost:8000"
         * @return 当前构建器
         */
        public Builder apiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
            return this;
        }

        /**
         * 设置 Mem0 API 密钥。
         *
         * @param apiKey 认证 API 密钥，无认证本地部署可省略
         * @return 当前构建器
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
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
         * 设置 Mem0 API 类型。
         *
         * @param apiType API 类型枚举
         * @return 当前构建器
         */
        public Builder apiType(Mem0ApiType apiType) {
            this.apiType = apiType;
            return this;
        }

        /**
         * 设置随记忆保存并用于过滤的自定义元数据。
         *
         * <p>记录记忆时加入请求体，搜索/检索时加入 filters。
         *
         * <p>用法示例：
         * <pre>{@code
         * Map<String, Object> metadata = new HashMap<>();
         * metadata.put("category", "travel");
         * metadata.put("priority", "high");
         *
         * Mem0LongTermMemory memory = Mem0LongTermMemory.builder()
         *     .agentName("Assistant")
         *     .userId("user_123")
         *     .apiBaseUrl("http://localhost:8000")
         *     .metadata(metadata)
         *     .build();
         * }</pre>
         *
         * @param metadata 自定义元数据映射，可为 null
         * @return 当前构建器
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * 构建 Mem0LongTermMemory 实例。
         *
         * @return 新的 Mem0LongTermMemory 实例
         * @throws IllegalArgumentException 缺少必填字段时抛出
         */
        public Mem0LongTermMemory build() {
            if (apiBaseUrl == null || apiBaseUrl.isEmpty()) {
                throw new IllegalArgumentException("apiBaseUrl is required");
            }

            return new Mem0LongTermMemory(this);
        }
    }
}
