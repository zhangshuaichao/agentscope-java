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
package io.agentscope.claw2.web.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 HarnessAgent 生成的 JSONL 会话记录解析为结构化轮次条目。
 *
 * <p>每行是 JSON 对象，至少包含以下字段：
 *
 * <pre>{@code
 * {"type":"message","id":"…","timestamp":…,"role":"USER|ASSISTANT|TOOL","content":"…"}
 * }</pre>
 *
 * <p>工具调用行还携带 {@code toolName}、{@code toolInput}，工具结果行携带 {@code toolResult}。
 */
public final class SessionTurnParser {

    private static final Logger log = LoggerFactory.getLogger(SessionTurnParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SessionTurnParser() {}

    /**
     * 将 JSONL 字符串解析为 {@link TurnEntry} 对象列表。
     *
     * @param jsonl 会话记录的完整 JSONL 内容（可为空白）
     * @return 有序轮次列表，不为 null
     */
    public static List<TurnEntry> parse(String jsonl) {
        List<TurnEntry> turns = new ArrayList<>();
        if (jsonl == null || jsonl.isBlank()) return turns;

        for (String line : jsonl.split("\n")) {
            line = line.strip();
            if (line.isEmpty()) continue;
            try {
                JsonNode node = MAPPER.readTree(line);
                String type = text(node, "type");
                if (!"message".equals(type)) continue;

                String id = text(node, "id");
                String parentId = text(node, "parentId");
                String role = text(node, "role");
                String content = text(node, "content");
                double ts = node.path("timestamp").asDouble(0);
                long timestampMs = (long) (ts * 1000);

                String toolName = text(node, "toolName");
                String toolInput = node.has("toolInput") ? node.get("toolInput").toString() : null;
                String toolResult =
                        node.has("toolResult") ? node.get("toolResult").toString() : null;

                turns.add(
                        new TurnEntry(
                                id,
                                parentId,
                                role,
                                content,
                                timestampMs,
                                toolName,
                                toolInput,
                                toolResult));
            } catch (Exception e) {
                log.debug("Skipping unparseable session line: {}", e.getMessage());
            }
        }
        return turns;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    /**
     * 从会话 JSONL 记录解析出的单个条目。
     *
     * @param id 条目 ID
     * @param parentId 父条目 ID，用于线程关联
     * @param role {@code USER}、{@code ASSISTANT} 或 {@code TOOL}
     * @param content 消息文本；纯工具调用条目为 null
     * @param timestampMs epoch 毫秒时间戳
     * @param toolName 被调用的工具名称；非工具条目为 null
     * @param toolInput 工具调用参数的 JSON 字符串；不适用时为 null
     * @param toolResult 工具结果的 JSON 字符串；不适用时为 null
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TurnEntry(
            String id,
            String parentId,
            String role,
            String content,
            long timestampMs,
            String toolName,
            String toolInput,
            String toolResult) {}
}
