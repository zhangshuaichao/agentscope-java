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
package io.agentscope.core.studio.pojo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.message.Msg;

/**
 * 向 Studio 推送消息的请求载荷。通过 POST /trpc/pushMessage 发送，在 Studio 界面显示消息。
 */
public class PushMessageRequest {
    @JsonProperty("runId")
    private final String runId;

    @JsonProperty("replyId")
    private final String replyId;

    @JsonProperty("name")
    private final String name;

    @JsonProperty("role")
    private final String role;

    @JsonProperty("msg")
    private final Msg msg;

    private PushMessageRequest(Builder builder) {
        this.runId = builder.runId;
        this.replyId = builder.replyId;
        this.name = builder.name;
        this.role = builder.role;
        this.msg = builder.msg;
    }

    /**
     * 获取此消息的运行 ID。
     * @return 运行 ID
     */
    public String getRunId() {
        return runId;
    }

    /**
     * 获取此消息的回复 ID。
     * @return 回复 ID，非回复消息时为 null
     */
    public String getReplyId() {
        return replyId;
    }

    /**
     * 获取产生消息的智能体名称。
     * @return 智能体名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取消息发送者角色。
     * @return 角色，如 "assistant"、"user"
     */
    public String getRole() {
        return role;
    }

    /**
     * 获取消息内容。
     * @return 消息对象
     */
    public Msg getMsg() {
        return msg;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String runId;
        private String replyId;
        private String name;
        private String role;
        private Msg msg;

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder replyId(String replyId) {
            this.replyId = replyId;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder role(String role) {
            this.role = role;
            return this;
        }

        public Builder msg(Msg msg) {
            this.msg = msg;
            return this;
        }

        public PushMessageRequest build() {
            return new PushMessageRequest(this);
        }
    }
}
