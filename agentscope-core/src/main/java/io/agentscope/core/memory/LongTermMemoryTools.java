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
package io.agentscope.core.memory;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * 将长期记忆操作暴露为智能体可调用工具的适配器。
 *
 * <p>此类清晰分离了核心记忆 API（定义于
 * {@link LongTermMemory}）与智能体使用的工具接口。它将
 * 面向开发者的 {@code record()} 和 {@code retrieve()} 方法适配为
 * 签名便于智能体使用的工具函数。
 *
 * <p><b>架构：</b>
 * <ul>
 *   <li><b>LongTermMemoryBase：</b>定义核心存储 API（{@code record()}、{@code retrieve()}）
 *   <li><b>LongTermMemoryTools：</b>将核心 API 适配为供智能体控制的工具接口
 *   <li><b>ReActAgent：</b>启用 {@code AGENT_CONTROL} 模式时注册工具
 * </ul>
 *
 * <p><b>使用示例：</b>
 * <pre>{@code
 * // 创建长期记忆实例
 * LongTermMemoryBase memory = Mem0LongTermMemory.builder()
 *     .agentName("Assistant")
 *     .userName("user_123")
 *     .build();
 *
 * // 创建工具适配器
 * LongTermMemoryTools tools = new LongTermMemoryTools(memory);
 *
 * // 在 ReActAgent 中注册，由框架自动完成
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(model)
 *     .longTermMemory(memory)
 *     .longTermMemoryMode(LongTermMemoryMode.AGENT_CONTROL)
 *     .build();
 * }</pre>
 *
 * @see LongTermMemory
 * @see LongTermMemoryMode
 * @deprecated 自 2.0.0 起弃用。旧长期记忆功能已移除。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public class LongTermMemoryTools {

    private final LongTermMemory memory;

    /**
     * 为给定长期记忆实例创建新的工具适配器。
     *
     * @param memory 要适配的长期记忆实例
     * @throws IllegalArgumentException 当 memory 为 null 时抛出
     */
    public LongTermMemoryTools(LongTermMemory memory) {
        if (memory == null) {
            throw new IllegalArgumentException("Long-term memory cannot be null");
        }
        this.memory = memory;
    }

    /*
     * 以特殊格式包装给定文本，表明它检索自长期记忆。
     */
    public static String wrap(String text) {
        return "Below is content retrieved from the long-term memory associated with the current"
                + " user, Please extract useful information from it in the context of the"
                + " current conversation.\n"
                + "<long_term_memory>\n"
                + text
                + "\n</long_term_memory>";
    }

    /**
     * 供智能体向长期记忆记录重要信息的工具函数。
     *
     * @param thinking 智能体关于记录内容及原因的思考
     * @param content 要记住的具体事实列表（应清晰简洁）
     * @return 表示成功或失败的状态消息
     */
    @Tool(
            description =
                    "Record important information to long-term memory for future reference. Use"
                            + " this when the user shares preferences, personal information, or"
                            + " important facts that should be remembered across conversations.")
    public Mono<ToolResultBlock> recordToMemory(
            @ToolParam(
                            name = "thinking",
                            description = "Your reasoning about what to record and why")
                    String thinking,
            @ToolParam(
                            name = "content",
                            description =
                                    "List of specific facts to remember. Each item should be clear"
                                            + " and concise.")
                    List<String> content) {
        if (content == null || content.isEmpty()) {
            return Mono.just(ToolResultBlock.error("No content provided to record"));
        }

        // 将 thinking 与 content 组合成消息
        List<Msg> messages = new ArrayList<>();

        if (thinking != null && !thinking.isEmpty()) {
            messages.add(
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text(thinking).build())
                            .build());
        }

        for (String item : content) {
            if (item != null && !item.isEmpty()) {
                messages.add(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text(item).build())
                                .build());
            }
        }

        if (messages.isEmpty()) {
            return Mono.just(ToolResultBlock.error("No valid content to record"));
        }

        // 调用底层记忆记录方法
        return memory.record(messages)
                .then(
                        Mono.just(
                                ToolResultBlock.success(
                                        "Successfully recorded to long-term memory")))
                .onErrorResume(e -> Mono.just(ToolResultBlock.error(e.getMessage())));
    }

    /**
     * 供智能体从长期记忆检索信息的工具函数。
     *
     * <p>此方法将智能体输入的关键词适配为查询消息，并调用
     * 底层记忆的 {@link LongTermMemory#retrieve(Msg)} 方法。
     *
     * <p><b>使用时机：</b>
     * <ul>
     *   <li>用户询问过去的偏好或信息
     *   <li>智能体需要先前对话的上下文
     *   <li>查找规律或历史数据
     *   <li>验证已存储的信息
     * </ul>
     *
     * <p><b>智能体使用示例：</b>
     * <pre>{@code
     * {
     *   "name": "retrieve_from_memory",
     *   "input": {
     *     "keywords": ["travel", "Hangzhou", "preferences"]
     *   }
     * }
     * }</pre>
     *
     * @param keywords 要搜索的关键词列表（应具体且相关）
     * @return 以文本形式返回的检索记忆
     */
    @Tool(
            description =
                    "Retrieve information from long-term memory based on keywords. Use this to"
                            + " recall user preferences, past conversations, or important facts.")
    public Mono<ToolResultBlock> retrieveFromMemory(
            @ToolParam(
                            name = "keywords",
                            description =
                                    "Keywords to search for in memory. Be specific (e.g., person"
                                            + " names, dates, locations).")
                    List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return Mono.just(ToolResultBlock.error("No keywords provided for search"));
        }

        // 将关键词组合为查询消息
        String query = String.join(" ", keywords);
        Msg queryMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(query).build())
                        .build();

        // 调用底层记忆检索方法
        return memory.retrieve(queryMsg)
                .map(
                        result -> {
                            if (result == null || result.isEmpty()) {
                                return ToolResultBlock.success("No relevant memories found");
                            }
                            return ToolResultBlock.success(wrap(result));
                        })
                .onErrorResume(e -> Mono.just(ToolResultBlock.error(e.getMessage())));
    }
}
