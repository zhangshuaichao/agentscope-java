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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 向外部系统（Slack webhook、工单服务、Nacos 配置项等）推送 {@link SkillCandidate} 元数据。{@code NotifyAndWaitGate} 每次草稿提交触发一次接收端；通知尽力而为，不应因失败阻止门控。
 */
public interface NotificationSink {

    Mono<Void> notify(SkillCandidate candidate);

    /**
     * 空操作，适合作为稍后自行接入通知端的默认值。
     */
    static NotificationSink noOp() {
        return c -> Mono.empty();
    }

    /**
     * 以 INFO 记录候选技能；生产环境可通过 slf4j 路由到 ELK / Loki。
     */
    static NotificationSink logging() {
        Logger log = LoggerFactory.getLogger("SkillNotificationSink.logging");
        return candidate -> {
            log.info(
                    "[skill-promote-request] name={} desc={} scan={} scripts={}",
                    candidate.name(),
                    candidate.description(),
                    candidate.securityScan() == null ? "n/a" : candidate.securityScan().verdict(),
                    candidate.scriptFiles() == null ? 0 : candidate.scriptFiles().size());
            return Mono.empty();
        };
    }
}
