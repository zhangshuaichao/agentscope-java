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
package io.agentscope.core.memory.reme;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * ReMe API 格式的消息。
 *
 * <p>消息是记忆记录的主要输入，每条消息具有 user 或 assistant 角色及内容。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReMeMessage {

    /** 消息发送者角色，通常为 "user" 或 "assistant"。 */
    private String role;

    /** 消息实际文本内容。 */
    private String content;

    /** 供 Jackson 反序列化使用的默认构造器。 */
    public ReMeMessage() {}

    /**
     * 使用指定角色与内容创建 ReMeMessage。
     *
     * @param role 角色，例如 "user"、"assistant"
     * @param content 消息内容
     */
    public ReMeMessage(String role, String content) {
        this.role = role;
        this.content = content;
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

    /**
     * 创建 ReMeMessage 实例的构建器。
     *
     * @return 新构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** ReMeMessage 的构建器类。 */
    public static class Builder {
        private String role;
        private String content;

        public Builder role(String role) {
            this.role = role;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public ReMeMessage build() {
            return new ReMeMessage(role, content);
        }
    }
}
