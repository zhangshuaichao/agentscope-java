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

package io.agentscope.spring.boot.a2a.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 用于部署的 A2A 服务端配置。 */
@ConfigurationProperties(Constants.A2A_SERVER_PREFIX)
public class A2aCommonProperties {

    private boolean enabled = true;

    /** 智能体完成任务的超时时间，单位为秒。 */
    private Integer agentCompletionTimeoutSeconds;

    /** A2A 服务端消费已完成智能体任务的流式输出的超时时间，单位为秒。 */
    private Integer consumptionCompletionTimeoutSeconds;

    /** 智能体任务状态更新为完成时，A2A 服务端是否返回完成消息。 */
    private boolean completeWithMessage;

    /** A2A 服务端是否返回 TOOL_CALL 等内部事件和消息。 */
    private boolean requireInnerMessage;

    public A2aCommonProperties() {}

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getAgentCompletionTimeoutSeconds() {
        return agentCompletionTimeoutSeconds;
    }

    public void setAgentCompletionTimeoutSeconds(Integer agentCompletionTimeoutSeconds) {
        this.agentCompletionTimeoutSeconds = agentCompletionTimeoutSeconds;
    }

    public Integer getConsumptionCompletionTimeoutSeconds() {
        return consumptionCompletionTimeoutSeconds;
    }

    public void setConsumptionCompletionTimeoutSeconds(
            Integer consumptionCompletionTimeoutSeconds) {
        this.consumptionCompletionTimeoutSeconds = consumptionCompletionTimeoutSeconds;
    }

    public boolean isCompleteWithMessage() {
        return completeWithMessage;
    }

    public void setCompleteWithMessage(boolean completeWithMessage) {
        this.completeWithMessage = completeWithMessage;
    }

    public boolean isRequireInnerMessage() {
        return requireInnerMessage;
    }

    public void setRequireInnerMessage(boolean requireInnerMessage) {
        this.requireInnerMessage = requireInnerMessage;
    }
}
