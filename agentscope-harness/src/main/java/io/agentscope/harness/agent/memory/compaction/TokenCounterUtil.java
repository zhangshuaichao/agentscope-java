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
package io.agentscope.harness.agent.memory.compaction;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;

/**
 * 估算消息 token 数量的工具类。
 *
 * <p>用于估计将消息发送给 LLM 时消耗的输入 token 数。采用基于字符数量的近似算法，
 * 同时兼顾英文和中文文本。
 *
 * <p>token 估算策略：
 * <ul>
 * <li>文本内容：约每 2～4 个字符计 1 个 token（随语言变化）。</li>
 * <li>思考内容：采用与文本相同的字符近似估算。</li>
 * <li>工具调用：包含工具名称、参数和结构开销。</li>
 * <li>工具结果：包含输出内容和结构开销。</li>
 * <li>消息结构：包含角色、名称和格式开销。</li>
 * </ul>
 *
 * <p>【学习提示】这是预算规划用的启发式估算，不是提供商分词器或计费用量；
 * 媒体块这里只计少量结构开销，不能据此推断图像、音频的实际 token 成本。
 */
public class TokenCounterUtil {

    // token 估算比例。
    // 英文：约每 4 个字符计 1 个 token。
    // 中文：约每 1～2 个字符计 1 个 token。
    // 采用适合混合内容的保守比例。
    private static final double CHARS_PER_TOKEN = 2.5;

    // 消息结构（角色、名称、格式）的 token 开销。
    private static final int MESSAGE_OVERHEAD = 5;

    // 工具调用结构的 token 开销。
    private static final int TOOL_CALL_OVERHEAD = 10;

    // 工具结果结构的 token 开销。
    private static final int TOOL_RESULT_OVERHEAD = 8;

    /**
     * 计算消息列表的预计输入 token 总数。
     *
     * <p>估算过程：
     * <ul>
     * <li>提取消息中的全部文本内容。</li>
     * <li>统计工具调用和结果中的字符数。</li>
     * <li>为每条消息及内容块加上结构开销。</li>
     * </ul>
     *
     * @param messages 待估算 token 数量的消息列表
     * @return 预计输入 token 数量
     */
    public static int calculateToken(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        int totalTokens = 0;

        for (Msg msg : messages) {
            totalTokens += estimateMessageTokens(msg);
        }

        return totalTokens;
    }

    /**
     * 估算单条消息的 token 数量。
     *
     * @param msg 待估算的消息
     * @return 此消息的预计 token 数量
     */
    private static int estimateMessageTokens(Msg msg) {
        if (msg == null) {
            return 0;
        }

        int tokens = MESSAGE_OVERHEAD;

        // 加入角色与名称的开销。
        if (msg.getRole() != null) {
            tokens += estimateTextTokens(msg.getRole().name());
        }
        if (msg.getName() != null) {
            tokens += estimateTextTokens(msg.getName());
        }

        // 估算内容块的 token 数量。
        List<ContentBlock> content = msg.getContent();
        if (content != null) {
            for (ContentBlock block : content) {
                tokens += estimateContentBlockTokens(block);
            }
        }

        return tokens;
    }

    /**
     * 估算内容块的 token 数量。
     *
     * @param block 待估算的内容块
     * @return 此块的预计 token 数量
     */
    private static int estimateContentBlockTokens(ContentBlock block) {
        if (block == null) {
            return 0;
        }

        if (block instanceof TextBlock textBlock) {
            return estimateTextTokens(textBlock.getText());
        } else if (block instanceof ThinkingBlock thinkingBlock) {
            return estimateTextTokens(thinkingBlock.getThinking());
        } else if (block instanceof ToolUseBlock toolUseBlock) {
            return estimateToolUseBlockTokens(toolUseBlock);
        } else if (block instanceof ToolResultBlock toolResultBlock) {
            return estimateToolResultBlockTokens(toolResultBlock);
        }

        // 对其他块类型（ImageBlock、AudioBlock 等）估算最小结构开销。
        return 5;
    }

    /**
     * 估算 ToolUseBlock 的 token 数量。
     *
     * @param toolUseBlock 待估算的工具调用块
     * @return 预计 token 数量
     */
    private static int estimateToolUseBlockTokens(ToolUseBlock toolUseBlock) {
        int tokens = TOOL_CALL_OVERHEAD;

        // 工具名称。
        if (toolUseBlock.getName() != null) {
            tokens += estimateTextTokens(toolUseBlock.getName());
        }

        // 工具 ID。
        if (toolUseBlock.getId() != null) {
            tokens += estimateTextTokens(toolUseBlock.getId());
        }

        // 工具输入参数。
        Map<String, Object> input = toolUseBlock.getInput();
        if (input != null && !input.isEmpty()) {
            // 估算参数 JSON 表示的 token 数量。
            String inputJson = estimateMapAsJson(input);
            tokens += estimateTextTokens(inputJson);
        }

        // 原始内容（若存在）。
        if (toolUseBlock.getContent() != null) {
            tokens += estimateTextTokens(toolUseBlock.getContent());
        }

        return tokens;
    }

    /**
     * 估算 ToolResultBlock 的 token 数量。
     *
     * @param toolResultBlock 待估算的工具结果块
     * @return 预计 token 数量
     */
    private static int estimateToolResultBlockTokens(ToolResultBlock toolResultBlock) {
        int tokens = TOOL_RESULT_OVERHEAD;

        // 工具名称。
        if (toolResultBlock.getName() != null) {
            tokens += estimateTextTokens(toolResultBlock.getName());
        }

        // 工具 ID。
        if (toolResultBlock.getId() != null) {
            tokens += estimateTextTokens(toolResultBlock.getId());
        }

        // 输出内容块。
        List<ContentBlock> output = toolResultBlock.getOutput();
        if (output != null) {
            for (ContentBlock outputBlock : output) {
                tokens += estimateContentBlockTokens(outputBlock);
            }
        }

        return tokens;
    }

    /**
     * 估算文本内容的 token 数量。
     *
     * <p>采用兼顾英文和中文的字符近似算法。
     *
     * @param text 待估算的文本
     * @return 预计 token 数量
     */
    private static int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        // 统计字符数并应用估算比例。
        int charCount = text.length();
        return (int) Math.ceil(charCount / CHARS_PER_TOKEN);
    }

    /**
     * 估算映射的 JSON 字符串长度，用于 token 计数。
     *
     * <p>这是统计键与字符串值的简化估算。
     *
     * @param map 待估算的映射
     * @return 预计 JSON 字符串长度
     */
    private static String estimateMapAsJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof String) {
                sb.append("\"").append(value).append("\"");
            } else {
                sb.append(value != null ? value.toString() : "null");
            }
        }
        sb.append("}");
        return sb.toString();
    }
}
