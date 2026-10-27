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
package io.agentscope.harness.agent.skill.curator;

import io.agentscope.core.agent.RuntimeContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * 决定草稿技能是否获准晋级到正式技能目录。
 *
 * <p>实现：
 * <ul>
 * <li>{@code RejectAllGate}：全部延后，程序调用 {@code HarnessAgent.promoteSkill(...)} 仍需门控批准，默认实现不会放行（企业默认）。</li>
 * <li>{@code LocalApprovalGate}：通过宿主 Shell / CLI 提示实现单副本 HITL。</li>
 * <li>{@code NotifyAndWaitGate}：多副本模式，在草稿旁写入 {@code .review_request.json} 并通知一个或多个 {@code NotificationSink}；等待外部系统再次调用 {@code promoteSkill}。</li>
 * </ul>
 *
 * @see SkillCandidate
 * @see PromotionDecision
 */
public interface SkillPromotionGate {

    /**
     * 评审草稿。实现应执行所需副作用（通知、提示等），并返回 {@link PromotionDecision}。
     */
    Mono<PromotionDecision> review(SkillCandidate candidate, RuntimeContext ctx);

    /**
     * 一次门控评审的最终结果。
     */
    sealed interface PromotionDecision
            permits PromotionDecision.Approve, PromotionDecision.Reject, PromotionDecision.Defer {

        record Approve(String reviewerId, List<String> targetEnvironments, Instant decidedAt)
                implements PromotionDecision {}

        record Reject(String reason, String reviewerId) implements PromotionDecision {}

        record Defer(Duration retryAfter, String reason) implements PromotionDecision {}
    }
}
