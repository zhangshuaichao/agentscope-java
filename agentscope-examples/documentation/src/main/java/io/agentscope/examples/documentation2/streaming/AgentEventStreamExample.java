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
package io.agentscope.examples.documentation2.streaming;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;

/**
 * AgentEventStreamExample：演示 {@link ReActAgent#streamEvents} 与 {@link AgentEvent} 事件层次。
 * <p>{@code streamEvents()} 返回 {@link reactor.core.publisher.Flux}{@code <AgentEvent>}，覆盖启动、模型调用、文本增量、工具调用与结果、结束，方便实时界面、审计和费用跟踪，无需自定义中间件。
 * <p>下方保留无工具响应、工具调用的事件序列及运行命令：
 * <pre>
 *   AGENT_START
 *     MODEL_CALL_START
 *       TEXT_BLOCK_START
 *         TEXT_BLOCK_DELTA  (repeated — one per streamed token chunk)
 *       TEXT_BLOCK_END
 *     MODEL_CALL_END        (carries token usage)
 *   AGENT_END
 * </pre>
 * <p>事件图中文对照：智能体开始后进入模型调用；文本块开始，按流式 token 块反复发出 TEXT_BLOCK_DELTA，随后文本块结束；MODEL_CALL_END 携带 token 用量，最后智能体结束。
 * <pre>
 *     TOOL_CALL_START       (tool name + call ID)
 *       TOOL_CALL_DELTA     (optional — streamed tool input)
 *     TOOL_CALL_END
 *     TOOL_RESULT_START
 *       TOOL_RESULT_TEXT_DELTA / TOOL_RESULT_DATA_DELTA
 *     TOOL_RESULT_END       (carries ToolResultState: SUCCESS / ERROR)
 * </pre>
 * <p>工具事件图中文对照：TOOL_CALL_START 携带工具名及调用 ID，可选 TOOL_CALL_DELTA 传递流式参数，随后调用描述结束；工具结果开始，发出文本或数据增量；TOOL_RESULT_END 携带 SUCCESS 或 ERROR 状态。
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.streaming.AgentEventStreamExample
 * </pre>
 */
public class AgentEventStreamExample {

    /**
     * 运行智能体事件流演示。
     * @param args 命令行参数，此处忽略
     */
    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("AgentEvent Stream Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Shows every lifecycle event emitted by streamEvents(), including tool calls.");
        System.out.println("=".repeat(60) + "\n");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WeatherTools());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("WeatherAgent")
                        .sysPrompt("You are a helpful assistant. Use tools when appropriate.")
                        .model("dashscope:qwen-plus")
                        .toolkit(toolkit)
                        .build();

        Msg userMsg = new UserMessage("user", "What is the weather like in Beijing and Shanghai?");

        System.out.println("User: What is the weather like in Beijing and Shanghai?\n");

        // ── 订阅细粒度事件流 ──
        //
        // streamEvents(Msg) 是 streamEvents(List<Msg>) 的便捷重载。
        // 每个 AgentEvent 携带：
        // event.getType()：AgentEventType 枚举值
        // event.getId()：唯一事件 ID
        // event.getCreatedAt()：ISO-8601 时间戳
        //
        // 使用 instanceof 访问类型专属字段。
        agent.streamEvents(userMsg).doOnNext(AgentEventStreamExample::handleEvent).blockLast();
    }

    /**
     * 将 {@link AgentEvent} 分派给类型专属处理器。推荐 {@code instanceof} 模式，可直接访问强类型字段；只需要类型标识时，也可对 {@link io.agentscope.core.event.AgentEventType} 使用穷尽 {@code switch}。
     * @param event 待处理事件
     */
    private static void handleEvent(AgentEvent event) {
        if (event instanceof AgentStartEvent e) {
            // 调用刚开始时发出一次。
            // replyId 将后续事件关联到此次调用。
            System.out.printf(
                    "[AGENT_START]        agent=%s  replyId=%s%n", e.getName(), e.getReplyId());

        } else if (event instanceof ModelCallStartEvent e) {
            // 每次模型 API 调用前发出。
            // 多工具 ReAct 循环每次迭代发出一次 MODEL_CALL_START。
            System.out.printf("[MODEL_CALL_START]   replyId=%s%n", e.getReplyId());

        } else if (event instanceof TextBlockDeltaEvent e) {
            // 模型每个流式 token 块发出时触发。
            // 不换行打印，增量呈现响应。
            System.out.print(e.getDelta());

        } else if (event instanceof ModelCallEndEvent e) {
            // 模型调用完成后发出。
            // 模型报告用量时，ChatUsage 携带输入／输出 token 数。
            System.out.println();
            if (e.getUsage() != null) {
                System.out.printf(
                        "[MODEL_CALL_END]     inputTokens=%d  outputTokens=%d%n",
                        e.getUsage().getInputTokens(), e.getUsage().getOutputTokens());
            } else {
                System.out.println("[MODEL_CALL_END]");
            }

        } else if (event instanceof ToolCallStartEvent e) {
            // 模型请求工具调用时发出。
            // toolCallId 关联 ToolCallStart/End 与 ToolResultStart/End。
            System.out.printf(
                    "[TOOL_CALL_START]    tool=%s  callId=%s%n",
                    e.getToolCallName(), e.getToolCallId());

        } else if (event instanceof ToolCallEndEvent e) {
            System.out.printf("[TOOL_CALL_END]      callId=%s%n", e.getToolCallId());

        } else if (event instanceof ToolResultEndEvent e) {
            // 工具结果生成后发出。
            // ToolResultState 为 SUCCESS 或 ERROR。
            System.out.printf(
                    "[TOOL_RESULT_END]    callId=%s  state=%s%n", e.getToolCallId(), e.getState());

        } else if (event instanceof AgentEndEvent e) {
            // 智能体完成所有迭代后发出一次。
            System.out.printf("[AGENT_END]          replyId=%s%n", e.getReplyId());

        } else {
            // 其他事件（TEXT_BLOCK_START/END、TOOL_RESULT_START、THINKING_BLOCK_* 等）
            // 在此示例中忽略，但可供高级场景使用。
            System.out.printf("[%-24s] (skipped)%n", event.getType());
        }
    }

    /**
     * 触发工具调用事件的模拟天气工具。
     */
    public static class WeatherTools {

        /**
         * 返回指定城市的当前天气。
         * @param city 待查询城市名
         * @return 简短天气描述
         */
        @Tool(description = "Get the current weather for a city")
        public String get_weather(
                @ToolParam(name = "city", description = "City name") String city) {
            // 模拟实现，真实应用应调用天气 API
            return switch (city.toLowerCase()) {
                case "beijing" -> "Beijing: 28°C, partly cloudy";
                case "shanghai" -> "Shanghai: 32°C, humid and sunny";
                default -> city + ": 25°C, clear skies";
            };
        }
    }
}
