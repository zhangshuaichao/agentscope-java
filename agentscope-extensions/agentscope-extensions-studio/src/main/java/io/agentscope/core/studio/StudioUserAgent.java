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
package io.agentscope.core.studio;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.studio.pojo.UserInputMetadata;
import io.agentscope.core.util.JsonUtils;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 代表人类用户的用户代理智能体。
 * <p>支持终端／控制台输入（默认），或启用 Studio 集成后的网页界面。
 * <ol><li>发送 requestUserInput HTTP 请求。</li><li>Studio 网页展示输入表单。</li><li>用户在浏览器输入。</li><li>Studio 通过 WebSocket 返回输入。</li><li>智能体接收并处理输入。</li></ol>
 * <p>以下示例分别使用终端和 Studio：
 * <pre>{@code
 * UserProxyAgent user = UserProxyAgent.builder()
 *     .name("User")
 *     .build();
 * }</pre>
 * <pre>{@code
 * UserProxyAgent user = UserProxyAgent.builder()
 *     .name("User")
 *     .studioClient(StudioManager.getClient())
 *     .webSocketClient(StudioManager.getWebSocketClient())
 *     .build();
 * }</pre>
 */
public class StudioUserAgent extends AgentBase {
    private static final Logger logger = LoggerFactory.getLogger(StudioUserAgent.class);

    private final StudioClient studioClient;
    private final StudioWebSocketClient webSocketClient;
    private final Duration inputTimeout;
    private final BufferedReader terminalReader;

    private StudioUserAgent(Builder builder) {
        super(builder.name, builder.description);
        this.studioClient = builder.studioClient;
        this.webSocketClient = builder.webSocketClient;
        this.inputTimeout = builder.inputTimeout;
        this.terminalReader = builder.terminalReader;
    }

    /**
     * 从终端／控制台获取用户输入。
     * @return 包含用户输入的 Mono
     */
    private Mono<Msg> getInputFromTerminal() {
        return Mono.fromCallable(
                () -> {
                    logger.debug("Prompting user for input via terminal");
                    System.out.print(getName() + " > ");
                    BufferedReader reader =
                            terminalReader != null
                                    ? terminalReader
                                    : new BufferedReader(new InputStreamReader(System.in));
                    String input = reader.readLine();

                    if (input == null || input.trim().isEmpty()) {
                        input = "";
                    }

                    return Msg.builder()
                            .id(UUID.randomUUID().toString())
                            .name(getName())
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text(input).build())
                            .metadata(Map.of("source", "terminal"))
                            .build();
                });
    }

    /**
     * 从 Studio 网页获取用户输入。先通过 HTTP 请求展示表单，再等待用户通过 WebSocket 提交。
     * @return 包含用户输入的 Mono
     */
    private Mono<Msg> getInputFromStudio() {
        return studioClient
                .requestUserInput(getAgentId(), getName(), null)
                .flatMap(
                        requestId ->
                                webSocketClient
                                        .waitForInput(requestId)
                                        .timeout(inputTimeout)
                                        .flatMap(
                                                inputData -> {
                                                    // 将 Studio 输入转换为 Msg
                                                    List<ContentBlock> blocks =
                                                            inputData.getBlocksInput();
                                                    Map<String, Object> structuredInput =
                                                            inputData.getStructuredInput();

                                                    // 使用 POJO 创建元数据
                                                    UserInputMetadata metadata =
                                                            UserInputMetadata.builder()
                                                                    .requestId(requestId)
                                                                    .structuredInput(
                                                                            structuredInput)
                                                                    .build();

                                                    // 将 POJO 转为 Map，供 Msg.Builder 使用
                                                    Map<String, Object> metadataMap =
                                                            JsonUtils.getJsonCodec()
                                                                    .convertValue(
                                                                            metadata, Map.class);

                                                    // 创建带内容块的消息
                                                    Msg.Builder msgBuilder =
                                                            Msg.builder()
                                                                    .id(
                                                                            UUID.randomUUID()
                                                                                    .toString())
                                                                    .name(getName())
                                                                    .role(MsgRole.USER)
                                                                    .metadata(metadataMap);

                                                    // 添加内容块
                                                    if (blocks != null && !blocks.isEmpty()) {
                                                        if (blocks.size() == 1) {
                                                            msgBuilder.content(blocks.get(0));
                                                        } else {
                                                            msgBuilder.content(blocks);
                                                        }
                                                    } else {
                                                        // 回退为空文本
                                                        msgBuilder.content(
                                                                TextBlock.builder()
                                                                        .text("")
                                                                        .build());
                                                    }

                                                    Msg userMsg = msgBuilder.build();

                                                    return Mono.just(userMsg);
                                                }))
                .onErrorResume(
                        e -> {
                            logger.warn(
                                    "Failed to get input from Studio, falling back to terminal", e);
                            return getInputFromTerminal();
                        });
    }

    /**
     * 处理中断场景。用户代理遇到中断时仅返回中断消息。
     * @param context 包含中断元数据的上下文
     * @param originalArgs 传给 call() 的原始参数
     * @return 包含中断消息的 Mono
     */
    @Override
    protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
        Msg interruptMsg =
                Msg.builder()
                        .name(getName())
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Interrupted by user").build())
                        .build();

        return Mono.just(interruptMsg);
    }

    /**
     * 处理输入消息列表。
     * @param msgs 输入消息，此处忽略
     * @return 用户输入消息
     */
    @Override
    public Mono<Msg> doCall(List<Msg> msgs) {
        // 启用 Studio 集成时通过 Studio 输入
        if (studioClient != null && webSocketClient != null) {
            return getInputFromStudio();
        } else {
            // 否则使用终端输入
            return getInputFromTerminal();
        }
    }

    /**
     * 创建 UserProxyAgent 构建器。
     * @return 新构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * UserProxyAgent 的构建器。
     */
    public static class Builder {
        private String name = "User";
        private String description;
        private StudioClient studioClient;
        private StudioWebSocketClient webSocketClient;
        private Duration inputTimeout = Duration.ofMinutes(30);
        private BufferedReader terminalReader;

        /**
         * 设置智能体名称，默认 "User"。
         * @param name 智能体名称
         * @return 当前构建器
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 设置可选智能体描述。
         * @param description 智能体描述
         * @return 当前构建器
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * 设置请求用户输入的 Studio HTTP 客户端。
         * <p>studioClient 和 webSocketClient 都必须设置才能启用 Studio 集成。
         * @param studioClient Studio HTTP 客户端
         * @return 当前构建器
         */
        public Builder studioClient(StudioClient studioClient) {
            this.studioClient = studioClient;
            return this;
        }

        /**
         * 设置接收用户输入的 Studio WebSocket 客户端。
         * <p>studioClient 和 webSocketClient 都必须设置才能启用 Studio 集成。
         * @param webSocketClient Studio WebSocket 客户端
         * @return 当前构建器
         */
        public Builder webSocketClient(StudioWebSocketClient webSocketClient) {
            this.webSocketClient = webSocketClient;
            return this;
        }

        /**
         * 设置等待 Studio 用户输入的超时，默认 30 分钟。
         * @param inputTimeout 超时时长
         * @return 当前构建器
         */
        public Builder inputTimeout(Duration inputTimeout) {
            this.inputTimeout = inputTimeout;
            return this;
        }

        /**
         * 设置终端输入的自定义 BufferedReader，包级可见以便测试。
         * @param terminalReader 自定义 BufferedReader
         * @return 当前构建器
         */
        Builder terminalReader(BufferedReader terminalReader) {
            this.terminalReader = terminalReader;
            return this;
        }

        /**
         * 构建 UserProxyAgent。
         * @return 新 UserProxyAgent 实例
         */
        public StudioUserAgent build() {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Agent name cannot be null or empty");
            }
            return new StudioUserAgent(this);
        }
    }
}
