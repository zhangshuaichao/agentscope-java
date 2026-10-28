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
package io.agentscope.core.studio;

import java.time.Duration;
import java.util.UUID;

/**
 * AgentScope Studio 集成配置。
 * <p>包含连接 Studio 和与其通信需要的全部参数，包括 HTTP 与 WebSocket 设置。
 * <p>使用示例：
 * <pre>{@code
 * StudioConfig config = StudioConfig.builder()
 *     .studioUrl("http://localhost:3000")
 *     .project("MyProject")
 *     .runName("experiment_001")
 *     .maxRetries(5)
 *     .build();
 * }</pre>
 * @see StudioManager
 * @see StudioClient
 */
public class StudioConfig {
    private final String studioUrl;
    private final String tracingUrl;
    private final String project;
    private final String runName;
    private final String runId;
    private final int maxRetries;
    private final int reconnectAttempts;
    private final Duration reconnectDelay;
    private final Duration reconnectMaxDelay;

    private StudioConfig(Builder builder) {
        this.studioUrl = builder.studioUrl;
        this.tracingUrl = builder.tracingUrl;
        this.project = builder.project;
        this.runName = builder.runName;
        this.runId = builder.runId;
        this.maxRetries = builder.maxRetries;
        this.reconnectAttempts = builder.reconnectAttempts;
        this.reconnectDelay = builder.reconnectDelay;
        this.reconnectMaxDelay = builder.reconnectMaxDelay;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 获取Studio 服务基础 URL。
     * @return Studio URL，默认 "http://localhost:3000"
     */
    public String getStudioUrl() {
        return studioUrl;
    }

    /**
     * 获取用于推送消息的跟踪端点 URL。
     * @return 跟踪 URL，默认 studioUrl + "/v1/traces"
     */
    public String getTracingUrl() {
        return tracingUrl;
    }

    /**
     * 获取本次运行的项目名称。
     * @return 项目名称，默认 "UnnamedProject"
     */
    public String getProject() {
        return project;
    }

    /**
     * 获取本次运行的可读名称。
     * @return 运行名称，默认 "run_" + 时间戳
     */
    public String getRunName() {
        return runName;
    }

    /**
     * 获取本次运行的唯一标识。
     * @return 运行 ID，默认随机 UUID
     */
    public String getRunId() {
        return runId;
    }

    /**
     * 获取HTTP 请求最大重试次数。
     * @return 最大重试次数，默认 3
     */
    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * 获取WebSocket 最大重连次数。
     * @return 重连次数，默认 3
     */
    public int getReconnectAttempts() {
        return reconnectAttempts;
    }

    /**
     * 获取重连尝试的初始间隔。
     * @return 重连间隔，默认 1 秒
     */
    public Duration getReconnectDelay() {
        return reconnectDelay;
    }

    /**
     * 获取重连尝试的最大间隔。
     * @return 最大重连间隔，默认 5 秒
     */
    public Duration getReconnectMaxDelay() {
        return reconnectMaxDelay;
    }

    public static class Builder {
        private String studioUrl = "http://localhost:3000";
        private String tracingUrl;
        private String project = "UnnamedProject";
        private String runName = "run_" + System.currentTimeMillis();
        private String runId = UUID.randomUUID().toString();
        private int maxRetries = 3;
        private int reconnectAttempts = 3;
        private Duration reconnectDelay = Duration.ofSeconds(1);
        private Duration reconnectMaxDelay = Duration.ofSeconds(5);

        public Builder studioUrl(String studioUrl) {
            this.studioUrl = studioUrl;
            return this;
        }

        public Builder tracingUrl(String tracingUrl) {
            this.tracingUrl = tracingUrl;
            return this;
        }

        public Builder project(String project) {
            this.project = project;
            return this;
        }

        public Builder runName(String runName) {
            this.runName = runName;
            return this;
        }

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder reconnectAttempts(int reconnectAttempts) {
            this.reconnectAttempts = reconnectAttempts;
            return this;
        }

        public Builder reconnectDelay(Duration reconnectDelay) {
            this.reconnectDelay = reconnectDelay;
            return this;
        }

        public Builder reconnectMaxDelay(Duration reconnectMaxDelay) {
            this.reconnectMaxDelay = reconnectMaxDelay;
            return this;
        }

        public StudioConfig build() {
            if (tracingUrl == null) {
                tracingUrl = studioUrl + "/v1/traces";
            }
            return new StudioConfig(this);
        }
    }
}
