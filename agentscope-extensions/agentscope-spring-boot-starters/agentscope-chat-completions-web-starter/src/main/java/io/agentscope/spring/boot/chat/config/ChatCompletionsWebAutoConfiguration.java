/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.spring.boot.chat.config;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.chat.completions.builder.ChatCompletionsResponseBuilder;
import io.agentscope.core.chat.completions.converter.ChatMessageConverter;
import io.agentscope.core.chat.completions.converter.OpenAIToolConverter;
import io.agentscope.core.chat.completions.streaming.ChatCompletionsStreamingAdapter;
import io.agentscope.spring.boot.chat.service.ChatCompletionsStreamingService;
import io.agentscope.spring.boot.chat.web.ChatCompletionsController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 暴露 Chat Completions 风格 HTTP API 的自动配置。
 *
 * <p>要求类路径中存在核心 {@code agentscope-spring-boot-starter}，并能够获取 {@link ReActAgent} bean。
 *
 * <p><b>无状态 API 的设计意图：</b>
 *
 * <p>此接口以兼容 OpenAI Chat Completions 的格式接收客户端完整历史：
 *
 * <ul>
 *   <li>客户端在 {@code messages} 中发送完整对话历史
 *   <li>服务端从提供方获取智能体，处理消息并返回响应
 *   <li>客户端将响应追加到历史，用于下一次请求
 * </ul>
 *
 * <p>若要求每个请求均使用全新智能体，需要将实际提供的 bean 配置为 prototype；ObjectProvider 本身不会改变单例作用域。需要服务端会话管理时，可在更高层实现独立会话机制。
 */
@AutoConfiguration
@EnableConfigurationProperties(ChatCompletionsProperties.class)
@ConditionalOnProperty(
        prefix = "agentscope.chat-completions",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@ConditionalOnClass(ReActAgent.class)
public class ChatCompletionsWebAutoConfiguration {

    /**
     * 创建消息转换器 bean。
     *
     * @return 用于将 HTTP DTO 转换为框架消息的 {@link ChatMessageConverter} 新实例
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatMessageConverter chatMessageConverter() {
        return new ChatMessageConverter();
    }

    /**
     * 创建 OpenAI 工具转换器 bean。
     *
     * @return 用于将 OpenAI 工具转换为 ToolSchema 的 {@link OpenAIToolConverter} 新实例
     */
    @Bean
    @ConditionalOnMissingBean
    public OpenAIToolConverter openAIToolConverter() {
        return new OpenAIToolConverter();
    }

    /**
     * 创建响应构造器 bean。
     *
     * @return 用于构建聊天补全响应的 {@link ChatCompletionsResponseBuilder} 新实例
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatCompletionsResponseBuilder chatCompletionsResponseBuilder() {
        return new ChatCompletionsResponseBuilder();
    }

    /**
     * 创建流式适配器 bean。
     *
     * <p>这是处理核心流式逻辑的框架无关适配器。
     *
     * @return 新的 {@link ChatCompletionsStreamingAdapter} 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatCompletionsStreamingAdapter chatCompletionsStreamingAdapter() {
        return new ChatCompletionsStreamingAdapter();
    }

    /**
     * 创建流式服务 bean。
     *
     * <p>这是将分块转换为 SSE 事件的 Spring 专用适配器。
     *
     * @param streamingAdapter 框架无关的流式适配器
     * @return 新的 {@link ChatCompletionsStreamingService} 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatCompletionsStreamingService chatCompletionsStreamingService(
            ChatCompletionsStreamingAdapter streamingAdapter) {
        return new ChatCompletionsStreamingService(streamingAdapter);
    }

    /**
     * 创建聊天补全控制器 bean。
     *
     * <p>控制器通过 {@link ObjectProvider} 获取智能体；只有实际 bean 为 prototype 时，每次获取才会创建新实例。
     *
     * @param agentProvider 提供 ReActAgent 实例的对象提供方
     * @param messageConverter 将 HTTP DTO 转换为框架消息的转换器
     * @param responseBuilder 响应对象构造器
     * @param streamingService 流式响应服务
     * @param toolConverter 将 OpenAI 工具转换为 ToolSchema 的转换器
     * @return 配置完成的 ChatCompletionsController bean
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatCompletionsController chatCompletionsController(
            ObjectProvider<ReActAgent> agentProvider,
            ChatMessageConverter messageConverter,
            ChatCompletionsResponseBuilder responseBuilder,
            ChatCompletionsStreamingService streamingService,
            OpenAIToolConverter toolConverter) {
        return new ChatCompletionsController(
                agentProvider, messageConverter, responseBuilder, streamingService, toolConverter);
    }
}
