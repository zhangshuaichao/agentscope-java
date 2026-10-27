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
package io.agentscope.extensions.model.anthropic.formatter;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.List;

/** 将多智能体对话消息合并为供 Anthropic API 使用的格式化对话历史。 */
public class AnthropicConversationMerger {

    /**
     * 将对话消息合并为带历史标签的格式化内容块。
     *
     * @param messages 对话消息列表
     * @param conversationHistoryPrompt 前置提示词文本
     * @return 内容块列表，包含文本和图像
     */
    public static List<Object> mergeConversation(
            List<Msg> messages, String conversationHistoryPrompt) {
        List<Object> conversationBlocks = new ArrayList<>();
        List<String> accumulatedText = new ArrayList<>();

        for (Msg msg : messages) {
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof TextBlock tb) {
                    String msgName = msg.getName() != null ? msg.getName() : msg.getRole().name();
                    accumulatedText.add(msgName + ": " + tb.getText());
                } else if (block instanceof HintBlock hb) {
                    String msgName = msg.getName() != null ? msg.getName() : msg.getRole().name();
                    accumulatedText.add(msgName + ": " + hb.getHint());
                } else if (block instanceof ImageBlock ib) {
                    // 在图像之前添加累积文本。
                    if (!accumulatedText.isEmpty()) {
                        conversationBlocks.add(String.join("\n", accumulatedText));
                        accumulatedText.clear();
                    }
                    conversationBlocks.add(ib);
                } else if (block instanceof DataBlock db) {
                    if (!accumulatedText.isEmpty()) {
                        conversationBlocks.add(String.join("\n", accumulatedText));
                        accumulatedText.clear();
                    }
                    conversationBlocks.add(db);
                }
            }
        }

        // 添加剩余文本。
        if (!accumulatedText.isEmpty()) {
            conversationBlocks.add(String.join("\n", accumulatedText));
        }

        // 用历史标签包裹。
        return wrapWithHistoryTags(conversationBlocks, conversationHistoryPrompt);
    }

    /** 用历史标签包裹对话内容块。 */
    private static List<Object> wrapWithHistoryTags(
            List<Object> blocks, String conversationHistoryPrompt) {
        if (blocks.isEmpty()) {
            return blocks;
        }

        List<Object> result = new ArrayList<>();

        // 向首个文本块添加起始标签，或插入新文本块。
        if (blocks.get(0) instanceof String firstText) {
            result.add(conversationHistoryPrompt + "<history>\n" + firstText);
            for (int i = 1; i < blocks.size(); i++) {
                result.add(blocks.get(i));
            }
        } else {
            result.add(conversationHistoryPrompt + "<history>\n");
            result.addAll(blocks);
        }

        // 向最后一个文本块添加结束标签，或追加新文本块。
        int lastIndex = result.size() - 1;
        if (result.get(lastIndex) instanceof String lastText) {
            result.set(lastIndex, lastText + "\n</history>");
        } else {
            result.add("</history>");
        }

        return result;
    }
}
