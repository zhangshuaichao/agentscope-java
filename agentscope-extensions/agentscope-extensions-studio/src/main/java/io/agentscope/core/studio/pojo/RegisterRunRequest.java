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

/**
 * 向 Studio 注册运行的请求载荷。初始化集成时通过 POST /trpc/registerRun 发送。
 */
public class RegisterRunRequest {
    @JsonProperty("id")
    private final String id;

    @JsonProperty("project")
    private final String project;

    @JsonProperty("name")
    private final String name;

    @JsonProperty("timestamp")
    private final String timestamp;

    @JsonProperty("pid")
    private final long pid;

    @JsonProperty("status")
    private final String status;

    @JsonProperty("run_dir")
    private final String runDir;

    private RegisterRunRequest(Builder builder) {
        this.id = builder.id;
        this.project = builder.project;
        this.name = builder.name;
        this.timestamp = builder.timestamp;
        this.pid = builder.pid;
        this.status = builder.status;
        this.runDir = builder.runDir;
    }

    /**
     * 获取本次运行的唯一标识。
     * @return 运行 ID
     */
    public String getId() {
        return id;
    }

    /**
     * 获取本次运行的项目名称。
     * @return 项目名称
     */
    public String getProject() {
        return project;
    }

    /**
     * 获取运行名称。
     * @return 运行名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取运行创建时间戳。
     * @return ISO 8601 格式时间戳
     */
    public String getTimestamp() {
        return timestamp;
    }

    /**
     * 获取运行应用的进程 ID。
     * @return 进程 ID
     */
    public long getPid() {
        return pid;
    }

    /**
     * 获取运行的当前状态。
     * @return 状态，如 "running"、"completed"、"failed"
     */
    public String getStatus() {
        return status;
    }

    /**
     * 获取运行产物存储目录。
     * @return 运行目录路径
     */
    public String getRunDir() {
        return runDir;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String project;
        private String name;
        private String timestamp;
        private long pid;
        private String status = "running";
        private String runDir = "";

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder project(String project) {
            this.project = project;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder timestamp(String timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder pid(long pid) {
            this.pid = pid;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder runDir(String runDir) {
            this.runDir = runDir;
            return this;
        }

        public RegisterRunRequest build() {
            return new RegisterRunRequest(this);
        }
    }
}
