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

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 基于流的用户输入实现，可从任意 InputStream 读取，并向任意 OutputStream 写入。
 * 默认使用 System.in 和 System.out 进行控制台交互。
 *
 * <p>支持简单文本输入及 key=value 键值对形式的结构化数据输入。
 * 阻塞 I/O 在有界弹性调度器上执行，以保持响应式兼容性。
 *
 * <p>用法示例：
 * <pre>{@code
 * // Default console input/output
 * StreamUserInput consoleInput = StreamUserInput.builder().build();
 *
 * // Custom streams (e.g., for testing)
 * StreamUserInput customInput = StreamUserInput.builder()
 *     .inputStream(myInputStream)
 *     .outputStream(myOutputStream)
 *     .inputHint("Enter: ")
 *     .build();
 * }</pre>
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public class StreamUserInput implements UserInputBase {

    private final String inputHint;
    private final BufferedReader reader;
    private final PrintStream output;

    /**
     * 私有构造方法，请通过 builder() 创建实例。
     *
     * @param builder 构建器实例
     */
    private StreamUserInput(Builder builder) {
        this.inputHint = builder.inputHint;
        this.reader = new BufferedReader(new InputStreamReader(builder.inputStream));
        this.output = new PrintStream(builder.outputStream);
    }

    /**
     * 创建新的 StreamUserInput 构建器。
     *
     * @return 新构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 处理已配置输入流中的用户输入。先打印上下文消息，再展示配置的输入提示，读取一行文本；
     * 若提供模型类，则可选地收集结构化数据。返回同时包含文本内容块和结构化输入的 UserInputData。
     *
     * @param agentId 智能体标识符（此实现未使用）
     * @param agentName 智能体名称（此实现未使用）
     * @param contextMessages 提示输入前可选展示的消息（例如助手回复）
     * @param structuredModel 定义结构化输入格式的可选类
     * @return 包含用户输入数据的 Mono
     */
    @Override
    public Mono<UserInputData> handleInput(
            String agentId, String agentName, List<Msg> contextMessages, Class<?> structuredModel) {
        return Mono.fromCallable(
                        () -> {
                            try {
                                // 提示输入前打印上下文消息
                                if (contextMessages != null && !contextMessages.isEmpty()) {
                                    for (Msg msg : contextMessages) {
                                        printMessage(msg);
                                    }
                                }

                                output.print(inputHint);
                                String textInput = reader.readLine();

                                if (textInput == null) {
                                    textInput = "";
                                }

                                // 创建文本块内容
                                List<ContentBlock> blocksInput =
                                        Collections.singletonList(
                                                TextBlock.builder().text(textInput).build());

                                // 提供模型时处理结构化输入
                                Map<String, Object> structuredInput = null;
                                if (structuredModel != null) {
                                    structuredInput = handleStructuredInput(structuredModel);
                                }

                                return new UserInputData(blocksInput, structuredInput);
                            } catch (IOException e) {
                                throw new RuntimeException("Error reading user input", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 向输出流打印消息。先格式化发送者名称和角色，再附加文本内容。
     *
     * @param msg 要打印的消息
     */
    private void printMessage(Msg msg) {
        StringBuilder sb = new StringBuilder();

        // 添加发送者名称和角色
        if (msg.getName() != null && !msg.getName().isEmpty()) {
            sb.append("[").append(msg.getName());
            if (msg.getRole() != null) {
                sb.append(" (").append(msg.getRole()).append(")");
            }
            sb.append("]: ");
        }

        // 提取并追加文本内容
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock textBlock) {
                sb.append(textBlock.getText());
            }
        }

        output.println(sb.toString());
    }

    /**
     * 根据提供的模型类处理结构化输入，采用简单的 key=value 键值对解析。
     * 未来可增强为通过反射解析模型结构以进行字段级校验。
     *
     * @param structuredModel 定义预期结构的模型类
     * @return 包含从用户输入解析出的键值对的映射
     */
    private Map<String, Object> handleStructuredInput(Class<?> structuredModel) {
        Map<String, Object> structuredInput = new HashMap<>();

        try {
            output.println("Structured input (press Enter to skip for optional fields):");
            output.print("\tEnter structured data as key=value pairs (or press Enter to skip): ");
            String input = reader.readLine();

            if (input != null && !input.trim().isEmpty()) {
                // 简单的 key=value 解析
                String[] pairs = input.split(",");
                for (String pair : pairs) {
                    String[] keyValue = pair.split("=", 2);
                    if (keyValue.length == 2) {
                        structuredInput.put(keyValue[0].trim(), keyValue[1].trim());
                    }
                }
            }
        } catch (IOException e) {
            output.println("Error reading structured input: " + e.getMessage());
        }

        return structuredInput;
    }

    /**
     * StreamUserInput 的构建器。
     *
     * <p>提供配置输入／输出流和输入提示的流式 API；未指定时默认使用 System.in/System.out。
     */
    public static class Builder {
        private String inputHint = "User Input: ";
        private InputStream inputStream = System.in;
        private OutputStream outputStream = System.out;

        private Builder() {}

        /**
         * 设置输入提示。
         *
         * @param inputHint 用户输入前展示的提示文本
         * @return 当前构建器
         */
        public Builder inputHint(String inputHint) {
            if (inputHint != null) {
                this.inputHint = inputHint;
            }
            return this;
        }

        /**
         * 设置读取所用输入流。
         *
         * @param inputStream 输入流（默认为 System.in）
         * @return 当前构建器
         */
        public Builder inputStream(InputStream inputStream) {
            if (inputStream != null) {
                this.inputStream = inputStream;
            }
            return this;
        }

        /**
         * 设置写入所用输出流。
         *
         * @param outputStream 输出流（默认为 System.out）
         * @return 当前构建器
         */
        public Builder outputStream(OutputStream outputStream) {
            if (outputStream != null) {
                this.outputStream = outputStream;
            }
            return this;
        }

        /**
         * 构建 StreamUserInput 实例。
         *
         * @return 新的 StreamUserInput 实例
         */
        public StreamUserInput build() {
            return new StreamUserInput(this);
        }
    }
}
