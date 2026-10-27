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
package io.agentscope.core.agent.user;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

/**
 * 处理智能体框架内用户交互的 UserAgent 类。
 *
 * <p>在不同用户输入来源（流、网页界面等）与消息系统之间充当桥梁。
 * 通过 UserInputBase 接口支持可插拔输入方式，自定义输入收集及转换为框架消息的过程。
 *
 * <p>设计思想：
 * <ul>
 * <li>UserAgent 不管理记忆，只负责获取用户输入</li>
 * <li>通过可插拔 UserInputBase 实现获取输入</li>
 * <li>支持简单文本及带校验的结构化输入</li>
 * <li>可加入 MsgHub 参与多智能体对话</li>
 * </ul>
 *
 * <p>用法示例：
 * <pre>{@code
 * // 简单控制台输入，默认方式
 * UserAgent user = UserAgent.builder()
 *     .name("User")
 *     .build();
 * Msg input = user.call().block();
 *
 * // 使用自定义输入方法
 * UserAgent user = UserAgent.builder()
 *     .name("User")
 *     .inputMethod(myCustomInput)
 *     .build();
 *
 * // 使用钩子
 * UserAgent user = UserAgent.builder()
 *     .name("User")
 *     .hooks(List.of(myHook))
 *     .build();
 * }</pre>
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public class UserAgent extends AgentBase {

    private static UserInputBase defaultInputMethod = StreamUserInput.builder().build();
    private UserInputBase inputMethod;

    /**
     * 私有构造方法，请通过 builder() 创建实例。
     *
     * @param builder 构建器实例
     */
    private UserAgent(Builder builder) {
        super(builder.name, builder.description, builder.checkRunning, builder.hooks);
        this.inputMethod = builder.inputMethod != null ? builder.inputMethod : defaultInputMethod;
    }

    /**
     * 创建新的 UserAgent 构建器。
     *
     * @return 新构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 处理多条输入消息并生成用户输入回复。
     * 提示用户输入前先展示传入消息。
     *
     * @hidden
     * @param msgs 要展示的输入消息
     * @return 用户输入消息
     */
    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        return getUserInput(msgs, null);
    }

    /**
     * 使用结构化模型处理多条输入消息并生成用户输入回复。
     * 提示用户输入前先展示传入消息。
     *
     * @hidden
     * @param msgs 要展示的输入消息
     * @param structuredModel 定义预期输入结构的可选类
     * @return 在元数据中携带结构化数据的用户输入消息
     */
    @Override
    public Mono<Msg> doCall(List<Msg> msgs, Class<?> structuredModel) {
        return getUserInput(msgs, structuredModel);
    }

    /**
     * 使用可选的上下文消息和结构化模型获取用户输入。
     * 这是获取用户输入的核心方法。
     *
     * @hidden
     * @param contextMessages 提示输入前可选展示的消息
     * @param structuredModel 定义预期输入结构的可选类
     * @return 包含用户输入消息的 Mono
     */
    public Mono<Msg> getUserInput(List<Msg> contextMessages, Class<?> structuredModel) {
        return inputMethod
                .handleInput(getAgentId(), getName(), contextMessages, structuredModel)
                .map(this::createMessageFromInput)
                .doOnNext(this::printMessage);
    }

    /**
     * 根据用户输入数据创建消息。
     * 将包含内容块和可选结构化数据的 UserInputData 转换为框架中角色为 USER 的 Msg。
     *
     * @param inputData 要转换的用户输入数据
     * @return 表示用户输入的 Msg 实例
     */
    private Msg createMessageFromInput(UserInputData inputData) {
        List<ContentBlock> blocksInput = inputData.getBlocksInput();
        Map<String, Object> structuredInput = inputData.getStructuredInput();

        // 将块输入转换为内容列表
        List<ContentBlock> content;
        if (blocksInput != null && !blocksInput.isEmpty()) {
            content = blocksInput;
        } else {
            // 没有内容时创建空文本块
            content = List.of(TextBlock.builder().text("").build());
        }

        // 创建消息
        Msg.Builder msgBuilder = Msg.builder().name(getName()).role(MsgRole.USER).content(content);

        // 存在结构化输入时将其加入元数据
        if (structuredInput != null && !structuredInput.isEmpty()) {
            msgBuilder.metadata(Map.of(MessageMetadataKeys.STRUCTURED_OUTPUT, structuredInput));
        }

        return msgBuilder.build();
    }

    /**
     * 将消息打印到控制台。
     */
    private void printMessage(Msg msg) {
        System.out.println(
                "[" + msg.getName() + " (" + msg.getRole() + ")]: " + extractTextFromMsg(msg));
    }

    /**
     * 从消息中提取文本供展示。
     * 连接 TextBlock 和 ThinkingBlock 实例中的文本，多块之间用换行分隔，忽略非文本块。
     *
     * @param msg 要提取文本的消息
     * @return 全部文本内容的字符串；未找到时为空字符串
     */
    private String extractTextFromMsg(Msg msg) {
        return msg.getContent().stream()
                .map(
                        block -> {
                            if (block instanceof TextBlock) {
                                return ((TextBlock) block).getText();
                            } else if (block instanceof ThinkingBlock) {
                                return ((ThinkingBlock) block).getThinking();
                            }
                            return "";
                        })
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 覆盖此 UserAgent 实例的输入方式。
     *
     * @hidden
     * @param inputMethod 要使用的新输入方式
     * @throws IllegalArgumentException inputMethod 为 null 时抛出
     */
    protected void overrideInstanceInputMethod(UserInputBase inputMethod) {
        if (inputMethod == null) {
            throw new IllegalArgumentException("Input method cannot be null");
        }
        this.inputMethod = inputMethod;
    }

    /**
     * 覆盖所有新 UserAgent 实例的默认输入方式。
     * 此类级设置影响本次调用之后创建的实例。
     *
     * @hidden
     * @param inputMethod 新的默认输入方式
     * @throws IllegalArgumentException inputMethod 为 null 时抛出
     */
    protected static void overrideClassInputMethod(UserInputBase inputMethod) {
        if (inputMethod == null) {
            throw new IllegalArgumentException("Input method cannot be null");
        }
        defaultInputMethod = inputMethod;
    }

    /**
     * 获取此实例当前的输入方式。
     *
     * @hidden
     * @return 当前输入方式
     */
    protected UserInputBase getInputMethod() {
        return inputMethod;
    }

    /**
     * 观察消息但不生成回复。
     * UserAgent 无需观察其他智能体消息，因此这是空操作。
     *
     * @hidden
     * @param msg 要观察的消息
     * @return 立即完成的 Mono
     */
    @Override
    protected Mono<Void> doObserve(Msg msg) {
        // UserAgent 不执行观察，直接完成
        return Mono.empty();
    }

    /**
     * 处理中断场景。
     * UserAgent 遇到中断时仅返回一条中断消息。
     *
     * @param context 包含中断元数据的中断上下文
     * @param originalArgs 传给 call() 方法的原始参数
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
     * UserAgent 的构建器。
     *
     * <p>提供配置智能体名称、输入方式和钩子的流式 API。名称必填，其他属性具有合理默认值。
     */
    public static class Builder {
        private String name;
        private String description;
        private boolean checkRunning = true;
        private UserInputBase inputMethod;
        private List<Hook> hooks;

        private Builder() {}

        /**
         * 设置智能体名称（必填）。
         *
         * @param name 智能体名称
         * @return 当前构建器
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 设置智能体描述。
         *
         * @param description 智能体描述
         * @return 当前构建器
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * 设置 checkRunning 标志。
         *
         * @param checkRunning checkRunning 标志
         * @return 当前构建器
         */
        public Builder checkRunning(boolean checkRunning) {
            this.checkRunning = checkRunning;
            return this;
        }

        /**
         * 设置用户交互的输入方式。
         *
         * @param inputMethod 输入方式实现（默认使用基于 System.in/out 的 StreamUserInput）
         * @return 当前构建器
         */
        public Builder inputMethod(UserInputBase inputMethod) {
            this.inputMethod = inputMethod;
            return this;
        }

        /**
         * 设置用于监测智能体执行的钩子。
         *
         * @param hooks 钩子列表
         * @return 当前构建器
         */
        public Builder hooks(List<Hook> hooks) {
            this.hooks = hooks;
            return this;
        }

        /**
         * 构建 UserAgent 实例。
         *
         * @return 新的 UserAgent 实例
         * @throws IllegalArgumentException name 为 null 或空时抛出
         */
        public UserAgent build() {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Agent name is required");
            }
            return new UserAgent(this);
        }
    }
}
