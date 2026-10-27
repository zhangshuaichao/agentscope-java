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
package io.agentscope.extensions.channel.common;

/**
 * 对平台重复交付的入站事件去重。
 * <p>渠道处理事件前查询此接口；企业微信、钉钉、GitHub、GitLab 等平台可能在未及时确认时重复交付。
 * 去重旨在减少同一事件的重复执行，但不能替代外部副作用的幂等设计。
 * <p>键由调用方组合，通常为 {@code channelId + "|" + platformEventId}。
 * 共享后端应按环境或应用前缀区分部署；跨部署误用同一键会丢弃真实流量。
 * <p>默认 {@link IdempotencyStore} 仅存于 JVM 堆，重试落到其他实例或重启后无法识别。
 * 多实例部署应注入共享实现，例如按渠道和平台事件 ID 组织的 Redis {@code SET ... NX EX}。
 * 当前支持以代码通过 {@code fromProperties(channelId, routing, rawProperties, InboundEventDeduplicator)}
 * 重载注入；{@code agentscope.json} 经 Harness {@code ChannelFactory} 的配置路径仍使用默认进程内存储。
 * <p>实现必须线程安全，渠道会在并发回调线程调用 {@link #firstSeen(String)}。
 * <p>【学习提示】firstSeen 登记发生在业务处理之前，不记录业务成功或失败。
 * 已登记后处理失败，后续重投在保留窗口内仍可能被抑制；这不是端到端恰好一次事务。
 */
public interface InboundEventDeduplicator {

    /**
     * 登记 {@code key} 并报告是否首次观察到。
     * @param key 调用方组合的不透明去重键；{@code null} 始终视为首次出现
     * @return 保留窗口内未见过 {@code key} 时为 {@code true}，调用方可处理；重复交付为 {@code false}，应丢弃
     */
    boolean firstSeen(String key);
}
