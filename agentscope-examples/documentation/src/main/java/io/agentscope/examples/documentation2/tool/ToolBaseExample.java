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
package io.agentscope.examples.documentation2.tool;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * ToolBaseExample：继承 {@link ToolBase} 实现自定义工具。
 * <p>{@link ToolBase} 适合接入权限引擎（{@code checkPermissions}、{@code generateSuggestions}）、异步 Mono 结构化结果（{@code callAsync}）、安全标记（{@code readOnly}、{@code concurrencySafe}）。
 * <p>简单同步工具可在 POJO 方法使用 {@code @Tool}，但该注解不意味着绕过权限或始终允许；需要自检、异步与结构化元数据时继承 {@code ToolBase}。
 * <p>运行：
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.tool.ToolBaseExample
 * </pre>
 * <p>【学习提示】{@code @Tool} 解决工具暴露与参数模式问题，不是授权凭证；权限仍由执行链统一判断。
 */
public class ToolBaseExample {

    /**
     * 运行 ToolBase 示例。
     * @param args 命令行参数，此处忽略
     */
    public static void main(String[] args) throws java.io.IOException {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("ToolBase Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates a custom tool that extends ToolBase.\n"
                        + "The temperature tool will ask for confirmation when reading\n"
                        + "a location considered outside the safe allow-list.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new TemperatureTool());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("WeatherAgent")
                        .sysPrompt("You are a weather assistant. Use the temperature tool.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .build();

        System.out.println(
                "Try: 'What is the temperature in Shanghai?' or 'Get temperature for"
                        + " /etc/passwd'\n");
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

    /**
     * 继承 {@link ToolBase} 并参与权限求值的自定义工具。使用显式原始 JSON Schema 映射，而非注解驱动生成；继承 {@code ToolBase} 时由工具提供输入模式。
     */
    public static class TemperatureTool extends ToolBase {

        /**
         * 通过 {@link ToolBase.Builder} 提供元数据，创建 TemperatureTool。
         */
        public TemperatureTool() {
            super(
                    ToolBase.builder()
                            .name("get_temperature")
                            .description("Get the current temperature for a city or location")
                            .inputSchema(
                                    Map.of(
                                            "type", "object",
                                            "properties",
                                                    Map.of(
                                                            "location",
                                                            Map.of(
                                                                    "type",
                                                                    "string",
                                                                    "description",
                                                                    "City or region name,"
                                                                            + " e.g."
                                                                            + " 'Shanghai'")),
                                            "required", List.of("location")))
                            .readOnly(true) // 不修改任何状态
                            .concurrencySafe(true)); // 可安全并行调用
        }

        /**
         * 执行温度查询。真实实现可调用天气 API；此处为演示返回固定响应。
         * @param param 工具调用参数，含输入、上下文等
         * @return 发出温度字符串 ToolResultBlock 的 Mono
         */
        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            String location = (String) param.getInput().getOrDefault("location", "unknown");
            String callId =
                    param.getToolUseBlock() != null ? param.getToolUseBlock().getId() : null;
            String result = "Temperature in " + location + ": 22°C (simulated)";
            ToolResultBlock block =
                    new ToolResultBlock(
                            callId, getName(), List.of(TextBlock.builder().text(result).build()));
            return Mono.just(block);
        }

        /**
         * 温度工具权限自检：地点含可疑路径分隔符时拒绝，其他查询交由引擎默认规则处理。
         * @param toolInput 解析后的工具参数
         * @param context 当前权限求值上下文
         * @return 允许、拒绝或继续传递的决定
         */
        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            String location = (String) toolInput.getOrDefault("location", "");
            if (location.contains("/") || location.contains("\\")) {
                return Mono.just(
                        PermissionDecision.deny(
                                "Suspicious path in location argument: '" + location + "'"));
            }
            // 其他情况交给引擎默认处理
            return Mono.just(PermissionDecision.passthrough(getName()));
        }

        /**
         * 用户批准查询后，建议地点专属允许规则。
         * @param toolInput 解析后的工具参数
         * @return 建议权限规则列表
         */
        @Override
        public List<PermissionRule> generateSuggestions(Map<String, Object> toolInput) {
            String location = (String) toolInput.getOrDefault("location", "");
            String pattern = location.isBlank() ? null : "location:" + location;
            return List.of(
                    new PermissionRule(getName(), pattern, PermissionBehavior.ALLOW, "suggested"));
        }
    }
}
