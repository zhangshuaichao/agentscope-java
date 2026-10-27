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
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * 长期记忆实现的抽象基类。
 *
 * <p>此类提供时序记忆管理系统，在单次对话会话之外
 * 持久化信息。长期记忆使智能体能够：
 * <ul>
 *   <li>跨会话记住用户偏好、习惯和个人信息
 *   <li>从过去交互中学习并逐步改善
 *   <li>为长期任务或项目保留上下文
 *   <li>基于历史数据构建个性化体验
 * </ul>
 *
 * <p>此类定义供框架级集成使用的核心记忆 API：
 * <ul>
 *   <li>{@link #record(List)}：记录消息到记忆（由框架调用）
 *   <li>{@link #retrieve(Msg)}：检索相关记忆（由框架调用）
 * </ul>
 *
 * <p>智能体控制的记忆操作（AGENT_CONTROL 模式）使用 {@link LongTermMemoryTools}，
 * 它提供将核心方法适配为智能体工具的函数。
 *
 * <p>所有方法都是异步的，返回 Reactor {@link Mono} 类型，
 * 以非阻塞方式与智能体框架集成。
 *
 * <p><b>使用示例：</b>
 * <pre>{@code
 * // 创建长期记忆实例
 * LongTermMemoryBase longTermMemory = Mem0LongTermMemory.builder()
 *     .agentName("Assistant")
 *     .userName("user_123")
 *     .apiBaseUrl("http://localhost:8000")
 *     .build();
 *
 * // 在 ReActAgent 中使用
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(model)
 *     .longTermMemory(longTermMemory)
 *     .longTermMemoryMode(LongTermMemoryMode.BOTH)
 *     .build();
 * }</pre>
 *
 * @see LongTermMemoryMode
 * @see io.agentscope.core.ReActAgent
 * @deprecated 自 2.0.0 起弃用。旧长期记忆功能已移除；对话上下文现位于
 *     {@link io.agentscope.core.state.AgentState#getContext()}。跨会话持久化
 *     请在应用层集成。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public interface LongTermMemory {

    /**
     * 将消息记录到长期记忆。
     *
     * <p>这是面向开发者、设计为由框架调用的方法
     * （例如在每次智能体回复结束时自动调用）。实现应
     * 从消息中提取有意义的信息，并将其持久化到底层
     * 记忆存储。
     *
     * <p>处理前会过滤 null 消息。空列表会被平稳处理，
     * 不会报错。
     *
     * <p><b>框架集成：</b>配置 {@link LongTermMemoryMode#STATIC_CONTROL} 或
     * {@link LongTermMemoryMode#BOTH} 时，会自动调用此方法，
     * 在每次智能体回复后记录对话。
     *
     * @param msgs 要记录的消息列表（过滤 null 条目）
     * @return 记录完成时结束的 Mono
     */
    Mono<Void> record(List<Msg> msgs);

    /**
     * 根据输入消息，从长期记忆检索相关信息。
     *
     * <p>这是面向开发者、设计为由框架调用的方法
     * （例如在每次智能体回复开始时自动调用）。实现应
     * 使用消息内容搜索相关记忆，并以文本形式返回。
     *
     * <p>返回文本通常加入智能体系统提示词，
     * 提供此前交互的上下文。
     *
     * <p><b>框架集成：</b>配置 {@link LongTermMemoryMode#STATIC_CONTROL} 或
     * {@link LongTermMemoryMode#BOTH} 时，会自动调用此方法，
     * 在每次智能体推理之前注入相关上下文。
     *
     * @param msg 用作记忆检索查询的消息
     * @return 发出检索记忆文本的 Mono（可能为空）
     */
    Mono<String> retrieve(Msg msg);
}
