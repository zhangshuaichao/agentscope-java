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
package io.agentscope.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.core.util.TypeUtils;
import java.beans.Transient;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 表示 AgentScope 框架中的消息。
 *
 * <p>消息是智能体、用户和工具之间的基本通信单元。每条消息都有角色（user、assistant、system 或 tool）、内容块和可选元数据。
 *
 * <p>内容块可包括文本、图像、音频、视频、思考内容、工具使用块和工具结果块。内容以不可变列表保存，以保证线程安全。
 *
 * <p>消息使用 Jackson 序列化为 JSON，并包含用于追踪的唯一 ID。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "role",
        visible = true,
        defaultImpl = Msg.class)
@JsonSubTypes({
    @JsonSubTypes.Type(value = UserMessage.class, name = "USER"),
    @JsonSubTypes.Type(value = AssistantMessage.class, name = "ASSISTANT"),
    @JsonSubTypes.Type(value = SystemMessage.class, name = "SYSTEM"),
    @JsonSubTypes.Type(value = ToolResultMessage.class, name = "TOOL"),
})
public class Msg implements State {

    /**
     * 用于存储生成原因的元数据键。
     */
    public static final String METADATA_GENERATE_REASON = "agentscope_generate_reason";

    /**
     * 恢复权限 HITL 暂停时携带 {@code List<ConfirmResult>} 的元数据键。接收方 {@code ReActAgent.call(msgs)} 提取这些结果，并应用于上下文中处于 ASKING 状态的工具调用。
     */
    public static final String METADATA_CONFIRM_RESULTS = "agentscope_confirm_results";

    /**
     * 存储暂停本轮助手执行的 {@code RequireUserConfirmEvent} 的 {@code replyId}，用于关联后续 {@code UserConfirmResultEvent} 的元数据键。
     */
    public static final String METADATA_CONFIRM_REQUEST_REPLY_ID =
            "agentscope_confirm_request_reply_id";

    /**
     * 存储暂停本轮助手执行的 {@code RequireExternalExecutionEvent} 的 {@code replyId}，用于关联后续 {@code ExternalExecutionResultEvent} 的元数据键。
     */
    public static final String METADATA_EXTERNAL_EXECUTION_REQUEST_REPLY_ID =
            "agentscope_external_execution_request_reply_id";

    /**
     * 用于将消息标记为<em>合成消息</em>的布尔元数据键：消息由框架注入，而非用户、模型或工具生成。合成消息（例如 {@code TaskReminderMiddleware} 每轮生成的待办提醒）临时追加到推理输入，不会持久化到 {@code AgentState.context}。此标记让观察到该消息的下游消费者（记忆提取、压缩、RAG 召回）能够识别并跳过它。
     */
    public static final String METADATA_SYNTHETIC = "agentscope_synthetic";

    /**
     * 描述消息所携带的合成提醒种类的字符串元数据键，例如 {@code "todo_state"}。仅当 {@link #METADATA_SYNTHETIC} 为 {@code true} 时有效。
     */
    public static final String METADATA_REMINDER_KIND = "agentscope_reminder_kind";

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final String id;

    private final String name;

    private final MsgRole role;

    private final List<ContentBlock> content;

    private final Map<String, Object> metadata;

    private final String timestamp;

    private final ChatUsage usage;

    /**
     * 使用指定字段创建消息。
     *
     * @param id 消息唯一标识符
     * @param name 可选的消息名称（可为 null）
     * @param role 发送者角色（user、assistant、system 或 tool）
     * @param content 构成消息内容的内容块列表
     * @param metadata 用于附加信息的可选元数据映射
     * @param timestamp 可选时间戳字符串（为 null 时自动生成）
     */
    @JsonCreator
    protected Msg(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("role") MsgRole role,
            @JsonProperty("content") List<ContentBlock> content,
            @JsonProperty("metadata") Map<String, Object> metadata,
            @JsonProperty("timestamp") String timestamp,
            @JsonProperty("usage") ChatUsage usage) {
        this.id = id;
        this.name = name;
        this.role = role;
        this.content =
                Objects.nonNull(content)
                        ? content.stream().filter(Objects::nonNull).toList()
                        : List.of();
        this.metadata = new HashMap<>();
        if (Objects.nonNull(metadata)) {
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (Objects.isNull(key) || Objects.isNull(value)) {
                    continue;
                }
                this.metadata.put(key, value);
            }
        }
        this.timestamp = timestamp;
        this.usage = usage;
        validateRoleContent(this.role, this.content);
    }

    /**
     * 验证内容块与消息角色是否兼容。
     *
     * <p>旧的 {@link ImageBlock}/{@link AudioBlock}/{@link VideoBlock} 与统一的 {@link DataBlock} 一样，仍可用于 {@link MsgRole#USER}。为保持向后兼容，旧的 {@link MsgRole#TOOL} 角色不受限制（与 assistant 相同）。
     *
     * @param role 消息角色
     * @param content 内容块
     * @throws IllegalArgumentException 当某个内容块不允许用于该角色时
     */
    private static void validateRoleContent(MsgRole role, List<ContentBlock> content) {
        if (role == null || content == null || content.isEmpty()) {
            return;
        }
        switch (role) {
            case USER -> {
                for (ContentBlock block : content) {
                    if (!(block instanceof TextBlock
                            || block instanceof DataBlock
                            || block instanceof ImageBlock
                            || block instanceof AudioBlock
                            || block instanceof VideoBlock)) {
                        throw new IllegalArgumentException(
                                "USER message may only contain text/data/image/audio/video blocks,"
                                        + " got "
                                        + block.getClass().getSimpleName());
                    }
                }
            }
            case SYSTEM -> {
                for (ContentBlock block : content) {
                    if (!(block instanceof TextBlock)) {
                        throw new IllegalArgumentException(
                                "SYSTEM message may only contain text blocks, got "
                                        + block.getClass().getSimpleName());
                    }
                }
            }
            case ASSISTANT, TOOL -> {
                // 不加限制；保留 TOOL 以维持向后兼容。
            }
        }
    }

    /**
     * 创建带有随机生成 ID 的消息构建器。
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建与给定角色匹配的具体 {@link Msg} 子类型构建器。
     *
     * <p>与产生普通 {@link Msg} 的 {@link #builder()} 不同，此工厂返回角色专用构建器：{@link MsgRole#USER} 对应 {@link UserMessage}，{@link MsgRole#ASSISTANT} 对应 {@link AssistantMessage}，{@link MsgRole#SYSTEM} 对应 {@link SystemMessage}，{@link MsgRole#TOOL} 对应 {@link ToolResultMessage}。角色为 {@code null} 时回退到基础 {@link #builder()}。
     *
     * <p>返回的构建器以协变返回类型覆写 {@code build()}，因此即使静态类型声明为 {@link Builder}，运行时仍产生具体子类型实例。
     *
     * @param role 要构建的具体消息子类型对应的角色
     * @return 角色专用构建器；{@code role} 为 {@code null} 时返回基础构建器
     */
    public static Builder builderForRole(MsgRole role) {
        if (role == null) {
            return builder();
        }
        return switch (role) {
            case USER -> UserMessage.builder();
            case ASSISTANT -> AssistantMessage.builder();
            case SYSTEM -> SystemMessage.builder();
            case TOOL -> ToolResultMessage.builder();
        };
    }

    /**
     * 生成用作消息 ID 的随机 UUID 字符串。向子类开放，使便捷构造器能够复用 {@link Builder} 的行为，而不必重复实现 UUID 逻辑。
     */
    protected static String generateId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 以本类的标准格式返回当前挂钟时间戳。向子类开放，使便捷构造器为消息添加时间戳的方式与 {@link Builder} 一致。
     */
    protected static String currentTimestamp() {
        return TIMESTAMP_FORMATTER.format(Instant.now());
    }

    /**
     * 获取消息的唯一标识符。
     *
     * @return 消息 ID
     */
    public String getId() {
        return id;
    }

    /**
     * 获取消息的可选名称。
     *
     * @return 消息名称；未设置时为 null
     */
    public String getName() {
        return name;
    }

    /**
     * 获取消息发送者的角色。
     *
     * @return 消息角色（user、assistant、system 或 tool）
     */
    public MsgRole getRole() {
        return role;
    }

    /**
     * 获取消息内容块的不可变列表。
     *
     * @return 内容块列表，可为空但不会为 null
     */
    public List<ContentBlock> getContent() {
        return content;
    }

    /**
     * 获取消息关联的元数据。
     *
     * @return 元数据映射；未设置时为 null
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * 获取消息时间戳。
     *
     * @return 格式为 "yyyy-MM-dd HH:mm:ss.SSS" 的时间戳字符串
     */
    public String getTimestamp() {
        return timestamp;
    }

    /**
     * 获取消息关联的 token 用量信息。
     *
     * <p>与 Python 的 {@code Msg.usage}（PR #1639）对齐。存在时包含生成此消息的模型调用所消耗的输入和输出 token 数量。
     *
     * @return 用量对象；不可用时为 null
     */
    public ChatUsage getUsage() {
        return usage;
    }

    /**
     * 以类型安全的方式检查消息是否含有指定类型的内容块。
     *
     * @param blockClass 待检查的内容块类
     * @param <T> 内容块类型
     * @return 至少存在一个该类型的内容块时为 true
     */
    @Transient
    @JsonIgnore
    public <T extends ContentBlock> boolean hasContentBlocks(Class<T> blockClass) {
        return content.stream().anyMatch(blockClass::isInstance);
    }

    /**
     * 以类型安全的方式获取指定类型的全部内容块。
     *
     * @param blockClass 用于筛选的内容块类
     * @param <T> 内容块类型
     * @return 匹配的内容块列表
     */
    @Transient
    @JsonIgnore
    public <T extends ContentBlock> List<T> getContentBlocks(Class<T> blockClass) {
        return content.stream()
                .filter(blockClass::isInstance)
                .map(b -> TypeUtils.safeCast(b, blockClass))
                .collect(Collectors.toList());
    }

    /**
     * 获取第一个内容块；列表为空时返回 null。
     *
     * @return 第一个内容块或 null
     */
    @Transient
    @JsonIgnore
    public ContentBlock getFirstContentBlock() {
        return content.isEmpty() ? null : content.get(0);
    }

    /**
     * 以类型安全的方式获取指定类型的第一个内容块。
     *
     * @param blockClass 待查找的内容块类
     * @param <T> 内容块类型
     * @return 第一个匹配的内容块或 null
     */
    @Transient
    @JsonIgnore
    public <T extends ContentBlock> T getFirstContentBlock(Class<T> blockClass) {
        return content.stream()
                .filter(blockClass::isInstance)
                .map(b -> TypeUtils.safeCast(b, blockClass))
                .findFirst()
                .orElse(null);
    }

    /**
     * 检查消息元数据中是否包含结构化数据。
     *
     * @return 元数据存在且非空时为 true
     */
    @Transient
    @JsonIgnore
    public boolean hasStructuredData() {
        return metadata != null && metadata.containsKey(MessageMetadataKeys.STRUCTURED_OUTPUT);
    }

    /**
     * 从消息元数据提取结构化数据，并转换为指定类型。
     *
     * <p>适用于消息包含用户智能体的结构化输入或大语言模型的结构化输出的情况。使用 Jackson 的 ObjectMapper 将元数据映射转换为 Java 对象。
     *
     * <p>使用示例：
     * <pre>{@code
     * public class TaskPlan {
     *     public String goal;
     *     public int priority;
     * }
     *
     * Msg msg = userAgent.call(null, TaskPlan.class).block();
     * TaskPlan plan = msg.getStructuredData(TaskPlan.class);
     * }</pre>
     *
     * @param targetClass 元数据要转换成的目标类
     * @param <T> 结构化数据类型
     * @return 结构化数据对象
     * @throws IllegalStateException 当元数据不存在时
     * @throws IllegalArgumentException 当转换失败时
     */
    @Transient
    @JsonIgnore
    public <T> T getStructuredData(Class<T> targetClass) {
        if (metadata == null || metadata.isEmpty()) {
            throw new IllegalStateException(
                    "No structured data in message. Use hasStructuredData() to check first.");
        }
        Object structuredOutput = metadata.get(MessageMetadataKeys.STRUCTURED_OUTPUT);
        if (structuredOutput == null) {
            throw new IllegalStateException(
                    "No structured output in message metadata. Key '"
                            + MessageMetadataKeys.STRUCTURED_OUTPUT
                            + "' not found.");
        }
        try {
            return JsonUtils.getJsonCodec().convertValue(structuredOutput, targetClass);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to convert metadata to "
                            + targetClass.getSimpleName()
                            + ". Ensure the target class has appropriate fields matching metadata"
                            + " keys.",
                    e);
        }
    }

    /**
     * 从消息元数据提取结构化数据，并转换为 java.util.Map。
     *
     * <p>适用于消息包含用户智能体的结构化输入或大语言模型的结构化输出的情况，支持动态模式处理。
     *
     * <p>使用示例：
     * <pre>{@code
     * String json = """
     *         {
     *                 						 "type": "object",
     *                 						 "properties": {
     *                 						   "productName": {
     *                 							 "type": "string"
     *                 						                                              },
     *                 						   "features": {
     *                 							 "type": "array",
     *                 							 "items": {
     *                 							   "type": "string"                                             *                                           }
     *                 						   },
     *                 						   "pricing": {
     *                 							 "type": "object",
     *                 							 "properties": {
     *                 							   "amount": {
     *                                                  e": "number"
     *                 							   },
     *                 							   "currency": {
     *                                                  e": "string"
     *                                             }
     *                                           }
     *                 						   },
     *                 						   "ratings": {
     *                 							 "type": "object",
     *                 							 "additionalProperties": {
     *                                                   e": "integer"
     *                                           }
     *                                         }
     *                                       }
     *                 					   }
     *         """;
     *  JsonNode sampleJsonNode = new ObjectMapper().readTree(json);
     *   Msg msg = agent.call(input, sampleJsonNode).block(TEST_TIMEOUT);
     *   Map<String, Object> structuredData = msg.getStructuredData(false);
     * }</pre>
     *
     * @return 复制的元数据
     * @throws IllegalStateException 当元数据不存在时
     */
    @Transient
    @JsonIgnore
    @SuppressWarnings("unchecked")
    public Map<String, Object> getStructuredData(boolean mutable) {
        if (metadata == null || metadata.isEmpty()) {
            throw new IllegalStateException(
                    "No structured data in message. Use hasStructuredData() to check first.");
        }
        Object structuredOutput = metadata.get(MessageMetadataKeys.STRUCTURED_OUTPUT);
        if (structuredOutput == null) {
            throw new IllegalStateException(
                    "No structured output in message metadata. Key '"
                            + MessageMetadataKeys.STRUCTURED_OUTPUT
                            + "' not found.");
        }
        if (!(structuredOutput instanceof Map)) {
            throw new IllegalStateException(
                    "Structured output is not a Map. Use getStructuredData(Class<T>) instead.");
        }
        Map<String, Object> result = (Map<String, Object>) structuredOutput;
        if (mutable) {
            return result;
        }
        try {
            return JsonUtils.getJsonCodec()
                    .convertValue(result, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to convert structured output to Map.", e);
        }
    }

    /**
     * 从消息提取纯文本内容。
     *
     * <p>将所有文本块用换行符连接。消息不含文本块时返回空字符串。
     *
     * @return 所有文本块连接后的文本；没有文本块时为空字符串
     */
    @Transient
    @JsonIgnore
    public String getTextContent() {
        return content.stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(Collectors.joining("\n"));
    }

    /**
     * 从消息元数据获取对话用量统计。
     *
     * <p>获取模型生成过程中记录的累积 token 用量信息；不可用时返回 null。
     *
     * <p>使用示例：
     * <pre>{@code
     * Msg response = agent.call(userMsg).block();
     * ChatUsage usage = response.getChatUsage();
     * if (usage != null) {
     *     System.out.println("Input tokens: " + usage.getInputTokens());
     *     System.out.println("Output tokens: " + usage.getOutputTokens());
     *     System.out.println("Cached tokens: " + usage.getCachedTokens());
     *     System.out.println("Cache creation tokens: " + usage.getCacheCreationTokens());
     *     System.out.println("Reasoning tokens: " + usage.getReasoningTokens());
     *     System.out.println("Tool-use prompt tokens: " + usage.getToolUsePromptTokens());
     *     System.out.println("Total tokens: " + usage.getTotalTokens());
     *     System.out.println("Time: " + usage.getTime() + "s");
     * }
     * }</pre>
     *
     * @return 包含 token 数量和耗时的 ChatUsage 对象；不可用时为 null
     */
    @Transient
    @JsonIgnore
    public ChatUsage getChatUsage() {
        if (this.usage != null) {
            return this.usage;
        }
        if (metadata == null) {
            return null;
        }
        Object metaUsage = metadata.get(MessageMetadataKeys.CHAT_USAGE);
        if (metaUsage instanceof ChatUsage) {
            return (ChatUsage) metaUsage;
        }
        if (metaUsage instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) metaUsage;
            ChatUsage chatUsage =
                    ChatUsage.builder()
                            .inputTokens(toInt(map.get("inputTokens")))
                            .outputTokens(toInt(map.get("outputTokens")))
                            .cachedTokens(toInt(map.get("cachedTokens")))
                            .cacheCreationTokens(toInt(map.get("cacheCreationTokens")))
                            .reasoningTokens(toInt(map.get("reasoningTokens")))
                            .toolUsePromptTokens(toInt(map.get("toolUsePromptTokens")))
                            .time(toDouble(map.get("time")))
                            .build();
            metadata.put(MessageMetadataKeys.CHAT_USAGE, chatUsage);
            return chatUsage;
        }
        return null;
    }

    private static int toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    private static double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }

    /**
     * 获取消息的生成原因。
     *
     * <p>此方法帮助用户理解智能体执行上下文：
     * <ul>
     * <li>{@link GenerateReason#MODEL_STOP}：任务正常完成</li>
     * <li>{@link GenerateReason#TOOL_SUSPENDED}：外部工具需要用户执行</li>
     * <li>{@link GenerateReason#REASONING_STOP_REQUESTED}：推理阶段因 HITL 停止</li>
     * <li>{@link GenerateReason#ACTING_STOP_REQUESTED}：行动阶段因 HITL 停止</li>
     * <li>{@link GenerateReason#INTERRUPTED}：智能体被中断</li>
     * <li>{@link GenerateReason#MAX_ITERATIONS}：达到最大迭代次数</li>
     * <li>{@link GenerateReason#TOOL_RETURN_DIRECT}：工具结果直接返回给调用方</li>
     * </ul>
     *
     * @return 生成原因；未设置时默认为 {@link GenerateReason#MODEL_STOP}
     */
    @Transient
    @JsonIgnore
    public GenerateReason getGenerateReason() {
        if (metadata == null) {
            return GenerateReason.MODEL_STOP;
        }
        Object reason = metadata.get(METADATA_GENERATE_REASON);
        if (reason instanceof String s) {
            try {
                return GenerateReason.valueOf(s);
            } catch (IllegalArgumentException e) {
                return GenerateReason.MODEL_STOP;
            }
        }
        if (reason instanceof GenerateReason gr) {
            return gr;
        }
        return GenerateReason.MODEL_STOP;
    }

    /**
     * 创建具有指定生成原因的新消息。
     *
     * <p>返回新的 Msg 实例，将更新后的生成原因保存到元数据中，不修改原消息（保持不可变）。
     *
     * @param reason 要设置的生成原因
     * @return 更新了生成原因的新 Msg 实例
     */
    public Msg withGenerateReason(GenerateReason reason) {
        Map<String, Object> newMetadata = new HashMap<>(this.metadata);
        newMetadata.put(METADATA_GENERATE_REASON, reason.name());
        return new Msg(
                this.id,
                this.name,
                this.role,
                this.content,
                newMetadata,
                this.timestamp,
                this.usage);
    }

    /**
     * 返回使用给定内容块的消息副本。
     *
     * @param newContent 替换用的内容块
     * @return 元数据相同、内容被替换的新 Msg
     */
    public Msg withContent(List<ContentBlock> newContent) {
        return new Msg(
                this.id,
                this.name,
                this.role,
                newContent,
                this.metadata,
                this.timestamp,
                this.usage);
    }

    /**
     * 返回使用给定元数据的消息副本。
     *
     * @param newMetadata 替换用的元数据
     * @return 内容相同、元数据被替换的新 Msg
     */
    public Msg withMetadata(Map<String, Object> newMetadata) {
        return new Msg(
                this.id,
                this.name,
                this.role,
                this.content,
                newMetadata,
                this.timestamp,
                this.usage);
    }

    public static class Builder {

        protected String id;

        protected String name;

        protected MsgRole role = MsgRole.USER;

        protected List<ContentBlock> content = List.of();

        protected Map<String, Object> metadata = Map.of();

        protected String timestamp = TIMESTAMP_FORMATTER.format(Instant.now());

        protected ChatUsage usage;

        /**
         * 创建带有随机生成消息 ID 的构建器。
         */
        public Builder() {
            randomId();
        }

        /**
         * 设置消息的唯一标识符。
         *
         * @param id 消息 ID
         * @return 当前构建器，支持链式调用
         */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /**
         * 生成随机 UUID 作为消息 ID。
         */
        private void randomId() {
            this.id = UUID.randomUUID().toString();
        }

        /**
         * 设置消息的可选名称。
         *
         * @param name 消息名称
         * @return 当前构建器，支持链式调用
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 设置消息角色。
         *
         * @param role 消息角色（user、assistant、system 或 tool）
         * @return 当前构建器，支持链式调用
         */
        public Builder role(MsgRole role) {
            this.role = role;
            return this;
        }

        /**
         * 通过内容块列表设置内容。
         * @param content 内容块列表
         * @return 当前构建器
         */
        public Builder content(List<ContentBlock> content) {
            this.content = content;
            return this;
        }

        /**
         * 通过单个内容块设置内容（便捷方法）。内容块会自动包装为列表。
         * @param block 单个内容块
         * @return 当前构建器
         */
        public Builder content(ContentBlock block) {
            this.content = block == null ? List.of() : List.of(block);
            return this;
        }

        /**
         * 通过可变参数内容块设置内容（便捷方法）。
         * @param blocks 内容块
         * @return 当前构建器
         */
        public Builder content(ContentBlock... blocks) {
            this.content = blocks == null ? List.of() : List.of(blocks);
            return this;
        }

        /**
         * 通过字符串设置文本内容。
         * @param text 文本内容
         * @return 当前构建器
         */
        public Builder textContent(String text) {
            this.content = List.of(TextBlock.builder().text(text).build());
            return this;
        }

        /**
         * 设置结构化输出的元数据。
         * @param metadata 元数据映射
         * @return 当前构建器
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata == null ? Map.of() : metadata;
            return this;
        }

        /**
         * 设置消息时间戳。
         *
         * @param timestamp 时间戳字符串
         * @return 当前构建器，支持链式调用
         */
        public Builder timestamp(String timestamp) {
            this.timestamp =
                    timestamp == null ? TIMESTAMP_FORMATTER.format(Instant.now()) : timestamp;
            return this;
        }

        /**
         * 设置消息的 token 用量信息。
         *
         * @param usage 包含 token 数量的 ChatUsage
         * @return 当前构建器，支持链式调用
         */
        public Builder usage(ChatUsage usage) {
            this.usage = usage;
            return this;
        }

        /**
         * 设置消息生成原因。
         *
         * <p>生成原因说明智能体为何产生该消息，帮助用户理解执行上下文和所需的后续操作。
         *
         * @param reason 生成原因
         * @return 当前构建器，支持链式调用
         */
        public Builder generateReason(GenerateReason reason) {
            if (reason != null) {
                if (this.metadata == null || this.metadata.isEmpty()) {
                    this.metadata = new HashMap<>();
                } else if (!(this.metadata instanceof HashMap)) {
                    this.metadata = new HashMap<>(this.metadata);
                }
                this.metadata.put(METADATA_GENERATE_REASON, reason.name());
            }
            return this;
        }

        /**
         * 使用配置的属性构建消息实例。未设置时间戳时自动生成。
         *
         * @return 新的不可变消息
         */
        public Msg build() {
            return new Msg(id, name, role, content, metadata, timestamp, usage);
        }
    }
}
