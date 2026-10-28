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
package io.agentscope.extensions.model.gemini.formatter;

import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.VideoBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 为 Gemini API 合并多智能体对话消息。
 *
 * <p>将多个智能体消息整合为单个 Content，对话历史包裹在特殊标签内，
 * 在合并文本中保留智能体名称与角色。
 *
 * <p><b>格式：</b>
 * <pre>
 * # Conversation History
 * &lt;history&gt;
 * ## AgentName (role)
 * Agent message content...
 * &lt;/history&gt;
 * </pre>
 *
 * <p>格式示例中文说明：Conversation History 表示会话历史；history 标签包围历史内容；每个 AgentName 后带角色，再接该智能体消息正文。示例结构与原始文本值保留，以便对照实际格式化结果。
 */
public class GeminiConversationMerger {

    private static final Logger log = LoggerFactory.getLogger(GeminiConversationMerger.class);

    private static final String HISTORY_START_TAG = "<history>";
    private static final String HISTORY_END_TAG = "</history>";

    private final GeminiMediaConverter mediaConverter;
    private final String conversationHistoryPrompt;

    /**
     * 使用自定义对话历史提示创建 GeminiConversationMerger。
     *
     * @param conversationHistoryPrompt 添加到对话历史之前的提示
     */
    public GeminiConversationMerger(String conversationHistoryPrompt) {
        this.mediaConverter = new GeminiMediaConverter();
        this.conversationHistoryPrompt = conversationHistoryPrompt;
    }

    /**
     * 将对话消息合并为单个 Content（用于 Gemini API）。
     *
     * <p>此方法将所有智能体消息合并为单个 "user" 角色 Content，
     * 对话历史包装在 {@code <history>} 标签内，智能体名称和角色嵌入文本。
     *
     * @param msgs 待合并的对话消息列表
     * @param nameExtractor 从消息提取智能体名称的函数
     * @param toolResultConverter 将工具结果块转换为字符串的函数
     * @param historyPrompt 添加到前面的提示（非首组时为空）
     * @return 供 Gemini API 使用的单个合并 Content
     */
    public Content mergeToContent(
            List<Msg> msgs,
            Function<Msg, String> nameExtractor,
            Function<List<ContentBlock>, String> toolResultConverter,
            String historyPrompt) {

        List<Part> parts = new ArrayList<>();
        List<String> accumulatedText = new ArrayList<>();

        // 逐条处理消息及其内容块
        for (Msg msg : msgs) {
            String name = nameExtractor.apply(msg);

            List<ContentBlock> blocks = msg.getContent();
            for (ContentBlock block : blocks) {
                if (block instanceof TextBlock tb) {
                    accumulatedText.add(name + ": " + tb.getText());
                } else if (block instanceof HintBlock hb) {
                    accumulatedText.add(name + ": " + hb.getHint());

                } else if (block instanceof ThinkingBlock) {
                    // 跳过 ThinkingBlock，不发送给 API
                    log.debug("Skipping ThinkingBlock in multi-agent merge");

                } else if (block instanceof ToolResultBlock trb) {
                    // 将工具结果转换为文本并累积
                    String toolResultText = toolResultConverter.apply(trb.getOutput());
                    accumulatedText.add("Tool: " + trb.getName() + "\n" + toolResultText);

                } else if (block instanceof ImageBlock ib) {
                    // 将累积文本输出为 Part
                    if (!accumulatedText.isEmpty()) {
                        parts.add(Part.builder().text(String.join("\n", accumulatedText)).build());
                        accumulatedText.clear();
                    }
                    // 将图像作为单独 Part 添加
                    parts.add(mediaConverter.convertToInlineDataPart(ib));

                } else if (block instanceof AudioBlock ab) {
                    // 将累积文本输出为 Part
                    if (!accumulatedText.isEmpty()) {
                        parts.add(Part.builder().text(String.join("\n", accumulatedText)).build());
                        accumulatedText.clear();
                    }
                    // 将音频作为单独 Part 添加
                    parts.add(mediaConverter.convertToInlineDataPart(ab));

                } else if (block instanceof VideoBlock vb) {
                    // 将累积文本输出为 Part
                    if (!accumulatedText.isEmpty()) {
                        parts.add(Part.builder().text(String.join("\n", accumulatedText)).build());
                        accumulatedText.clear();
                    }
                    // 将视频作为单独 Part 添加
                    parts.add(mediaConverter.convertToInlineDataPart(vb));

                } else if (block instanceof DataBlock db) {
                    // 将累积文本输出为 Part
                    if (!accumulatedText.isEmpty()) {
                        parts.add(Part.builder().text(String.join("\n", accumulatedText)).build());
                        accumulatedText.clear();
                    }
                    // 将数据块作为单独 Part 添加
                    parts.add(mediaConverter.convertToInlineDataPart(db));
                }
            }
        }

        // 输出剩余累积文本
        if (!accumulatedText.isEmpty()) {
            parts.add(Part.builder().text(String.join("\n", accumulatedText)).build());
        }

        // 添加对话历史提示与 <history> 标签
        if (!parts.isEmpty()) {
            Part firstPart = parts.get(0);
            if (firstPart.text().isPresent()) {
                String modifiedText = historyPrompt + HISTORY_START_TAG + firstPart.text().get();
                parts.set(0, Part.builder().text(modifiedText).build());
            } else {
                // 首个部分是媒体，在开头插入文本部分
                parts.add(0, Part.builder().text(historyPrompt + HISTORY_START_TAG).build());
            }

            // 为最后一个文本部分添加结束标签
            Part lastPart = parts.get(parts.size() - 1);
            if (lastPart.text().isPresent()) {
                String modifiedText = lastPart.text().get() + "\n" + HISTORY_END_TAG;
                parts.set(parts.size() - 1, Part.builder().text(modifiedText).build());
            } else {
                // 最后一个部分是媒体，在末尾追加文本部分
                parts.add(Part.builder().text(HISTORY_END_TAG).build());
            }
        }

        // 返回角色为 "user" 的 Content
        return Content.builder().role("user").parts(parts).build();
    }
}
