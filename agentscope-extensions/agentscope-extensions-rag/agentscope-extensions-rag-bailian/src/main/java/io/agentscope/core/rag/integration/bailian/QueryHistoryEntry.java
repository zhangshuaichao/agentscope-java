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
package io.agentscope.core.rag.integration.bailian;

/**
 * 用于多轮对话改写的查询历史条目。
 *
 * <p>表示对话历史中的单条消息，用于多轮对话改写以提高检索效果。
 */
public class QueryHistoryEntry {

    private final String role;
    private final String content;

    /**
     * 创建新的 QueryHistoryEntry。
     *
     * @param role 角色（user 或 assistant）
     * @param content 消息内容
     */
    public QueryHistoryEntry(String role, String content) {
        if (role == null || (!role.equals("user") && !role.equals("assistant"))) {
            throw new IllegalArgumentException("Role must be 'user' or 'assistant'");
        }
        this.role = role;
        this.content = content;
    }

    /**
     * 获取消息角色。
     *
     * @return 角色（user 或 assistant）
     */
    public String getRole() {
        return role;
    }

    /**
     * 获取消息内容。
     *
     * @return 消息内容
     */
    public String getContent() {
        return content;
    }

    /**
     * 创建用户消息条目。
     *
     * @param content 用户消息
     * @return 角色为 "user" 的新 QueryHistoryEntry
     */
    public static QueryHistoryEntry user(String content) {
        return new QueryHistoryEntry("user", content);
    }

    /**
     * 创建助手消息条目。
     *
     * @param content 助手回复
     * @return 角色为 "assistant" 的新 QueryHistoryEntry
     */
    public static QueryHistoryEntry assistant(String content) {
        return new QueryHistoryEntry("assistant", content);
    }
}
