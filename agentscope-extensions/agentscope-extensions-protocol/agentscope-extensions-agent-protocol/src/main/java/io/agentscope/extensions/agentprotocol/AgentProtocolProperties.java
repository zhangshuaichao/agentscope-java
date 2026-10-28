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
package io.agentscope.extensions.agentprotocol;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** AgentScope 任务 HTTP 协议端点的配置。 */
@ConfigurationProperties(prefix = "agentscope.agent-protocol")
public class AgentProtocolProperties {

    /** 为 {@code true} 时注册 {@code /tasks} REST 端点。 */
    private boolean enabled = false;

    /** 为 {@code true} 时通过 SSE {@code /tasks/{id}/events} 推送 AgentEvent。 */
    private boolean streamingEnabled = true;

    /** 为 {@code true} 时暂停远程任务，以等待工具调用的人机协作确认（HITL）。 */
    private boolean hitlEnabled = true;

    /** 每个任务为较晚订阅 SSE 的客户端保留的重放缓冲大小。 */
    private int sseReplayBufferSize = 256;

    /** 向客户端提供的 SSE 空闲超时提示（毫秒）；默认不在服务端强制执行。 */
    private long sseTimeoutMs = 10_800_000L;

    /** 用于持久化协议任务记录的独立控制平面目录，与任何执行智能体的工作区无关。为 null 或空白时，默认为 {@code ${user.dir}/.agentscope/agent-protocol}。 */
    private String taskStorePath;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isStreamingEnabled() {
        return streamingEnabled;
    }

    public void setStreamingEnabled(boolean streamingEnabled) {
        this.streamingEnabled = streamingEnabled;
    }

    public boolean isHitlEnabled() {
        return hitlEnabled;
    }

    public void setHitlEnabled(boolean hitlEnabled) {
        this.hitlEnabled = hitlEnabled;
    }

    public int getSseReplayBufferSize() {
        return sseReplayBufferSize;
    }

    public void setSseReplayBufferSize(int sseReplayBufferSize) {
        this.sseReplayBufferSize = sseReplayBufferSize;
    }

    public long getSseTimeoutMs() {
        return sseTimeoutMs;
    }

    public void setSseTimeoutMs(long sseTimeoutMs) {
        this.sseTimeoutMs = sseTimeoutMs;
    }

    /** 解析后的控制平面路径。此 getter 不返回空白路径，而是回退到 {@code ${user.dir}/.agentscope/agent-protocol}。 */
    public String getTaskStorePath() {
        if (taskStorePath == null || taskStorePath.isBlank()) {
            return Path.of(System.getProperty("user.dir"), ".agentscope", "agent-protocol")
                    .toString();
        }
        return taskStorePath;
    }

    public void setTaskStorePath(String taskStorePath) {
        this.taskStorePath = taskStorePath;
    }
}
