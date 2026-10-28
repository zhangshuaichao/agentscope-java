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
package io.agentscope.harness.coding.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 编码智能体集中式 Micrometer 指标。
 *
 * <p>通过 Spring Boot Actuator（{@code /actuator/prometheus}）暴露，可由 Prometheus 抓取，或经 Micrometer OTel 桥发送到 OTel collector。
 *
 * <p>主要指标：
 * <ul>
 * <li>{@code coding_agent.webhook.received}：收到的 GitHub webhook 总数，按 event 标记</li>
 * <li>{@code coding_agent.webhook.duplicate}：去重的 webhook 投递</li>
 * <li>{@code coding_agent.dispatch.total}：智能体分发总数，按 agent_id 标记</li>
 * <li>{@code coding_agent.dispatch.errors}：分发失败数</li>
 * <li>{@code coding_agent.model.calls}：全部线程的 LLM 调用数</li>
 * <li>{@code coding_agent.findings.added}：新增审查发现项</li>
 * <li>{@code coding_agent.review.published}：发布到 GitHub 的 PR 审查</li>
 * <li>{@code coding_agent.dispatch.duration}：端到端分发延迟</li>
 * </ul>
 */
public class CodingAgentMetrics {

    private final Counter webhookReceived;
    private final Counter webhookDuplicate;
    private final Counter dispatchTotal;
    private final Counter dispatchErrors;
    private final Counter modelCalls;
    private final Counter findingsAdded;
    private final Counter reviewsPublished;
    private final Timer dispatchDuration;

    public CodingAgentMetrics(MeterRegistry registry) {
        this.webhookReceived =
                Counter.builder("coding_agent.webhook.received")
                        .description("Total GitHub webhooks received")
                        .register(registry);
        this.webhookDuplicate =
                Counter.builder("coding_agent.webhook.duplicate")
                        .description("Duplicate webhook deliveries skipped")
                        .register(registry);
        this.dispatchTotal =
                Counter.builder("coding_agent.dispatch.total")
                        .description("Total agent run dispatches")
                        .register(registry);
        this.dispatchErrors =
                Counter.builder("coding_agent.dispatch.errors")
                        .description("Agent dispatch failures")
                        .register(registry);
        this.modelCalls =
                Counter.builder("coding_agent.model.calls")
                        .description("Total LLM model calls across all threads")
                        .register(registry);
        this.findingsAdded =
                Counter.builder("coding_agent.findings.added")
                        .description("Reviewer findings added")
                        .register(registry);
        this.reviewsPublished =
                Counter.builder("coding_agent.review.published")
                        .description("PR reviews published to GitHub")
                        .register(registry);
        this.dispatchDuration =
                Timer.builder("coding_agent.dispatch.duration")
                        .description("End-to-end agent dispatch latency")
                        .register(registry);
    }

    public void recordWebhookReceived() {
        webhookReceived.increment();
    }

    public void recordWebhookDuplicate() {
        webhookDuplicate.increment();
    }

    public void recordDispatch() {
        dispatchTotal.increment();
    }

    public void recordDispatchError() {
        dispatchErrors.increment();
    }

    public void recordModelCall() {
        modelCalls.increment();
    }

    public void recordFindingAdded() {
        findingsAdded.increment();
    }

    public void recordReviewPublished() {
        reviewsPublished.increment();
    }

    public Timer getDispatchDuration() {
        return dispatchDuration;
    }
}
