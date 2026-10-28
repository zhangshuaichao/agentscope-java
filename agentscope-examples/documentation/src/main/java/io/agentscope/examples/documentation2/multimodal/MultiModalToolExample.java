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
package io.agentscope.examples.documentation2.multimodal;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.extensions.model.dashscope.tool.DashScopeMultiModalTool;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.function.Function;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;

/**
 * MultiModalToolExample：演示多模态工具与调用日志中间件。
 * <p>迁移：{@code ToolCallLoggingHook implements Hook} 改为 {@code ToolCallLoggingMiddleware implements MiddlewareBase}；{@code PreActingEvent} 对应 {@code next.apply()} 之前的 {@code onActing()}，从 {@code ActingInput.toolCalls()} 读取工具名和输入；{@code PostActingEvent} 改为在 {@code onActing()} 流中观察 {@code ToolResultEndEvent} 及 {@code ToolResultTextDeltaEvent}，返回后通过 {@code agent.getState().getContext()} 检查完整内容。移除 {@code .memory(new InMemoryMemory())}；{@code .hook(...)} 改为 {@code .middleware(...)}。
 */
public class MultiModalToolExample {

    /**
     * 运行多模态工具示例。
     * @param args 命令行参数，此处忽略
     * @throws Exception I/O 错误时
     */
    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("MultiModal Tool Calling Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "This example demonstrates how to equip an Agent with multimodal tools.\n"
                        + "The agent has image, audio and video multimodal tools.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new DashScopeMultiModalTool(apiKey));
        printRegisteredTools();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("MultiModalToolAgent")
                        .sysPrompt(
                                "You are a helpful assistant with access to multimodal tools."
                                        + " Use tools when needed to answer questions accurately."
                                        + " Always explain what you're doing when using tools.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-plus")
                                        .stream(true)
                                        .enableThinking(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .middleware(new ToolCallLoggingMiddleware())
                        .build();

        printExamplePrompts();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Chat started. Type 'exit' to quit.\n");

        while (true) {
            System.out.print("You: ");
            String input = reader.readLine();
            if (input == null || input.trim().equalsIgnoreCase("exit")) {
                System.out.println("\nGoodbye!");
                break;
            }
            if (input.isBlank()) {
                continue;
            }
            Msg userMsg = new UserMessage(input.trim());
            System.out.print("\nAgent: ");
            agent.streamEvents(userMsg)
                    .doOnNext(
                            event -> {
                                if (event instanceof TextBlockDeltaEvent e) {
                                    System.out.print(e.getDelta());
                                }
                            })
                    .blockLast();
            System.out.println("\n");
        }
    }

    private static void printRegisteredTools() {
        String registeredTools =
                """
                Registered tools:
                - dashscope_text_to_image: Generate image(s) based on the given text.
                - dashscope_image_to_image: Edit or transform an existing image based on a text prompt and an input image.
                - dashscope_image_to_text: Generate text based on the given images.
                - dashscope_text_to_audio: Convert the given text to audio.
                - dashscope_audio_to_text: Convert the given audio to text.
                - dashscope_text_to_video: Generate video based on the given text prompt.
                - dashscope_image_to_video: Generate a video from a single input image and an optional text prompt.
                - dashscope_first_and_last_frame_image_to_video: Generate video transitioning between two frames.
                - dashscope_video_to_text: Analyze video and generate a text description.
                """;
        System.out.println(registeredTools);
        System.out.println("\n");
    }

    private static void printExamplePrompts() {
        String examplePrompts =
                """
                Example Prompts:
                [dashscope_text_to_image]:
                Generate a black dog image url.
                [dashscope_image_to_image]:
                Repaint the tiger image url of 'https://dashscope.oss-cn-beijing.aliyuncs.com/images/tiger.png' as a watercolor painting.
                [dashscope_image_to_text]:
                Describe the image url of 'https://dashscope.oss-cn-beijing.aliyuncs.com/images/tiger.png'.
                [dashscope_text_to_audio]:
                Convert the texts of 'hello, qwen!' to audio url.
                [dashscope_audio_to_text]:
                Convert the audio url of 'https://dashscope.oss-cn-beijing.aliyuncs.com/samples/audio/paraformer/hello_world_male2.wav' to text.
                [dashscope_text_to_video]:
                Generate a smart cat is running in the moonlight video.
                [dashscope_image_to_video]:
                Generate a video that a tiger is running in moonlight based on the image url of 'https://dashscope.oss-cn-beijing.aliyuncs.com/images/tiger.png'.
                [dashscope_video_to_text]:
                Describe the video url of 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241115/cqqkru/1.mp4'.
                """;
        System.out.println(examplePrompts);
        System.out.println("\n");
    }

    /**
     * 工具执行前记录名称／输入，执行后记录结果文本增量及最终状态的中间件。
     */
    static class ToolCallLoggingMiddleware implements MiddlewareBase {

        @Override
        public Flux<AgentEvent> onActing(
                Agent agent,
                RuntimeContext ctx,
                ActingInput input,
                Function<ActingInput, Flux<AgentEvent>> next) {
            // 执行前记录所有工具调用，替代 PreActingEvent
            for (ToolUseBlock toolCall : input.toolCalls()) {
                System.out.println(
                        "\n[MIDDLEWARE] Tool call START — name: "
                                + toolCall.getName()
                                + ", input: "
                                + toolCall.getInput());
            }

            String toolNames =
                    input.toolCalls().stream()
                            .map(ToolUseBlock::getName)
                            .collect(Collectors.joining(", "));

            return next.apply(input)
                    .doOnNext(
                            event -> {
                                // 记录工具结果的流式文本增量
                                if (event instanceof ToolResultTextDeltaEvent delta) {
                                    System.out.println(
                                            "[MIDDLEWARE] Tool result delta (id="
                                                    + delta.getToolCallId()
                                                    + "): "
                                                    + delta.getDelta());
                                } else if (event instanceof ToolResultEndEvent end) {
                                    // 最终完成状态，替代 PostActingEvent
                                    System.out.println(
                                            "[MIDDLEWARE] Tool result END — tool: "
                                                    + end.getToolCallId()
                                                    + ", state: "
                                                    + end.getState());
                                }
                            })
                    .doOnComplete(
                            () ->
                                    System.out.println(
                                            "[MIDDLEWARE] Tool call COMPLETE — "
                                                    + toolNames
                                                    + "\n"));
        }
    }
}
