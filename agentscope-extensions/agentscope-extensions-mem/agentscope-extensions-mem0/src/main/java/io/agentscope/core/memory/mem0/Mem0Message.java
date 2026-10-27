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
package io.agentscope.core.memory.mem0;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Mem0 API 格式的消息。
 *
 * <p>消息是记忆记录的主要输入，包含 user 或 assistant 角色、内容，以及可选的名称标识。
 *
 * <p>Mem0 从消息中提取并推断值得记忆的信息，存入向量数据库供后续检索。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Mem0Message {

    /** 发送者角色，通常为 "user" 或 "assistant"。 */
    private String role;

    /** 消息实际文本内容。 */
    private String content;

    /** 发送者可选名称标识。 */
    private String name;

    /** 供 Jackson 反序列化使用的默认构造器。 */
    public Mem0Message() {}

    /**
     * 使用指定角色与内容创建 Mem0Message。
     *
     * @param role 角色，例如 "user"、"assistant"
     * @param content 消息内容
     */
    public Mem0Message(String role, String content) {
        this.role = role;
        this.content = content;
    }

    /**
     * 使用角色、内容和名称创建 Mem0Message。
     *
     * @param role 角色，例如 "user"、"assistant"
     * @param content 消息内容
     * @param name 发送者名称
     */
    public Mem0Message(String role, String content, String name) {
        this.role = role;
        this.content = content;
        this.name = name;
    }

    // 读取与设置方法。

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * 创建 Mem0Message 实例的构建器。
     *
     * @return 新构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Mem0Message 的构建器类。 */
    public static class Builder {
        private String role;
        private String content;
        private String name;

        public Builder role(String role) {
            this.role = role;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Mem0Message build() {
            return new Mem0Message(role, content, name);
        }
    }
}
