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
import reactor.core.publisher.Mono;

/**
 * 始终返回 {@link SkillPromotionGate.PromotionDecision.Defer}：即使显式调用 {@code HarnessAgent.promoteSkill(...)}，当前门控本身仍不会批准。实际晋级需要配置能返回批准决定的门控。
 *
 * <p>这是最保守的默认策略：智能体自身操作不能单独把技能放入正式技能根目录。适合 SRE 要求严格人工控制晋级的情况。
 */
public class RejectAllGate implements SkillPromotionGate {

    private final Duration retryAfter;

    public RejectAllGate() {
        this(Duration.ofHours(24));
    }

    public RejectAllGate(Duration retryAfter) {
        this.retryAfter = retryAfter != null ? retryAfter : Duration.ofHours(24);
    }

    @Override
    public Mono<PromotionDecision> review(SkillCandidate candidate, RuntimeContext ctx) {
        return Mono.just(
                new PromotionDecision.Defer(
                        retryAfter,
                        "RejectAllGate: promotion requires explicit HarnessAgent.promoteSkill"
                                + " call by an authorized caller"));
    }
}
