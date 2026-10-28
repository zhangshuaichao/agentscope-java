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
package io.agentscope.examples.documentation2.hitl;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;

/**
 * HookStopAgentExample：通过 {@link MiddlewareBase} 完成人工工具确认。
 * <p>从旧 quickstart 迁移：{@code PostReasoningEvent.stopAgent()} 改为从 {@link MiddlewareBase#onActing} 发出 {@link RequestStopEvent}；待处理调用自动保留于 {@code AgentState.context}。危险 {@link ToolUseBlock} 标为 {@link ToolCallState#ASKING}，第二次 {@code agent.call(...)} 携带 {@link ConfirmResult} 恢复。移除 {@code .memory(new InMemoryMemory())}，{@code .hooks(List)} 改为 {@code .middleware(...)}。
 */
public class HookStopAgentExample {

    private static final Set<String> DANGEROUS_TOOLS = Set.of("delete_file", "send_email");

    /**
     * 运行停止智能体钩子示例。
     * @param args 命令行参数，此处忽略
     * @throws Exception I/O 错误时
     */
    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Middleware Stop-Agent Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates human-in-the-loop tool confirmation.\n"
                        + "Sensitive tools (delete_file, send_email) require explicit approval\n"
                        + "before execution. Safe tools (search_web) run without interruption.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SensitiveTools());

        System.out.println("Registered tools:");
        System.out.println("  - delete_file : Delete a file (requires confirmation)");
        System.out.println("  - send_email  : Send an email (requires confirmation)");
        System.out.println("  - search_web  : Search the web (runs without confirmation)\n");

        ReActAgent agent =
                ReActAgent.builder()
                        .name("SafeAgent")
                        .sysPrompt(
                                "You are a helpful assistant with access to file and email tools."
                                        + " Always use the appropriate tool when asked to delete"
                                        + " files or send emails.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-plus")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .middleware(new ToolConfirmationMiddleware(DANGEROUS_TOOLS))
                        .build();

        System.out.println("Try these commands:");
        System.out.println("  - 'Delete the file temp.txt'");
        System.out.println("  - 'Send an email to john@example.com saying Hello'");
        System.out.println("  - 'Search for weather forecast'\n");

        startChatWithConfirmation(agent);
    }

    /**
     * 处理工具确认的交互聊天循环。
     * @param agent 对话智能体
     */
    static void startChatWithConfirmation(ReActAgent agent) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\nYou: ");
            String input = scanner.nextLine().trim();

            if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                System.out.println("Goodbye!");
                break;
            }

            if (input.isEmpty()) {
                continue;
            }

            Msg response = agent.call(new UserMessage("user", input)).block();

            while (response != null
                    && response.getGenerateReason() == GenerateReason.PERMISSION_ASKING) {
                System.out.println(
                        "\n[PAUSED] Agent paused - dangerous tool requires confirmation.");
                displayPendingContext(response);

                System.out.print("Confirm execution? (yes/no): ");
                String confirmation = scanner.nextLine().trim().toLowerCase();

                if (confirmation.equals("yes") || confirmation.equals("y")) {
                    System.out.println("Resuming execution...\n");
                    response = agent.call(buildConfirmMsg(response, true)).block();
                } else {
                    response = agent.call(buildConfirmMsg(response, false)).block();
                    System.out.println("Operation cancelled by user.\n");
                }
            }

            if (response != null) {
                System.out.println("\nAgent: " + response.getTextContent());
            }
        }
    }

    private static void displayPendingContext(Msg response) {
        if (response != null) {
            System.out.println("Agent reply so far: " + response.getTextContent());
        }
    }

    private static Msg buildConfirmMsg(Msg pausedResponse, boolean confirmed) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(confirmed ? "[confirm]" : "[deny]")
                .metadata(
                        Map.of(
                                Msg.METADATA_CONFIRM_RESULTS,
                                extractAskingToolCalls(pausedResponse).stream()
                                        .map(toolCall -> new ConfirmResult(confirmed, toolCall))
                                        .toList()))
                .build();
    }

    private static List<ToolUseBlock> extractAskingToolCalls(Msg response) {
        if (response == null) {
            return List.of();
        }
        return response.getContent().stream()
                .filter(
                        block ->
                                block instanceof ToolUseBlock toolUse
                                        && toolUse.getState() == ToolCallState.ASKING)
                .map(ToolUseBlock.class::cast)
                .toList();
    }

    /**
     * 拦截工具执行的中间件，危险工具即将运行时暂停智能体。
     */
    static class ToolConfirmationMiddleware implements MiddlewareBase {

        private final Set<String> dangerousTools;

        ToolConfirmationMiddleware(Set<String> dangerousTools) {
            this.dangerousTools = dangerousTools;
        }

        @Override
        public Flux<AgentEvent> onActing(
                Agent agent,
                RuntimeContext ctx,
                ActingInput input,
                Function<ActingInput, Flux<AgentEvent>> next) {
            List<ToolUseBlock> dangerousToolCalls =
                    input.toolCalls().stream()
                            .filter(toolCall -> dangerousTools.contains(toolCall.getName()))
                            .filter(toolCall -> toolCall.getState() != ToolCallState.ALLOWED)
                            .toList();

            if (!dangerousToolCalls.isEmpty()) {
                markToolCallsAsking(ctx, dangerousToolCalls);
                System.out.println(
                        "\n[MIDDLEWARE] Dangerous tool detected - requesting stop for"
                                + " confirmation.");
                return Flux.just(
                        new RequireUserConfirmEvent(null, dangerousToolCalls),
                        new RequestStopEvent(
                                "Dangerous tool requires user confirmation",
                                GenerateReason.PERMISSION_ASKING));
            }

            return next.apply(input);
        }

        private void markToolCallsAsking(
                RuntimeContext ctx, List<ToolUseBlock> dangerousToolCalls) {
            if (ctx == null || ctx.getAgentState() == null || dangerousToolCalls.isEmpty()) {
                return;
            }

            Set<String> askingIds =
                    dangerousToolCalls.stream()
                            .map(ToolUseBlock::getId)
                            .collect(Collectors.toSet());

            List<Msg> context = ctx.getAgentState().contextMutable();
            for (int i = context.size() - 1; i >= 0; i--) {
                Msg msg = context.get(i);
                if (msg.getRole() != MsgRole.ASSISTANT) {
                    continue;
                }

                boolean hasMatch =
                        msg.getContent().stream()
                                .anyMatch(
                                        block ->
                                                block instanceof ToolUseBlock toolUse
                                                        && askingIds.contains(toolUse.getId()));
                if (!hasMatch) {
                    continue;
                }

                List<ContentBlock> updatedContent =
                        msg.getContent().stream()
                                .map(
                                        block ->
                                                block instanceof ToolUseBlock toolUse
                                                                && askingIds.contains(
                                                                        toolUse.getId())
                                                        ? toolUse.withState(ToolCallState.ASKING)
                                                        : block)
                                .toList();
                context.set(i, msg.withContent(updatedContent));
                return;
            }
        }
    }

    /**
     * 模拟敏感工具，本示例无真实副作用。
     */
    public static class SensitiveTools {

        /**
         * 模拟删除文件。
         * @param filename 待删除文件名
         * @return 结果消息
         */
        @Tool(name = "delete_file", description = "Delete a file from the filesystem")
        public String deleteFile(
                @ToolParam(name = "filename", description = "Path to the file to delete")
                        String filename) {
            System.out.println("[TOOL] Deleting file: " + filename);
            return "File '" + filename + "' deleted successfully.";
        }

        /**
         * 模拟发送电子邮件。
         * @param to 收件地址
         * @param subject 主题
         * @param body 邮件正文
         * @return 结果消息
         */
        @Tool(name = "send_email", description = "Send an email to a recipient")
        public String sendEmail(
                @ToolParam(name = "to", description = "Recipient email address") String to,
                @ToolParam(name = "subject", description = "Email subject") String subject,
                @ToolParam(name = "body", description = "Email body") String body) {
            System.out.println("[TOOL] Sending email to " + to + " - subject: " + subject);
            return "Email sent to '" + to + "'.";
        }

        /**
         * 模拟网页搜索，无需确认。
         * @param query 搜索查询
         * @return 模拟搜索结果
         */
        @Tool(name = "search_web", description = "Search the web for information")
        public String searchWeb(
                @ToolParam(name = "query", description = "Search query") String query) {
            System.out.println("[TOOL] Searching web for: " + query);
            return "Search results for '" + query + "': [Simulated results]";
        }
    }
}
