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
package io.agentscope.harness.agent.bus;

import java.time.Duration;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * 跟踪已转入后台的异步工具执行的注册表。
 *
 * <p>工具执行超过配置的等待时限后，{@code AsyncToolMiddleware} 在此注册它。注册表跟踪生命周期：
 * <ul><li>正常完成时更新记录，并通过收件箱交付结果。</li>
 * <li>进程崩溃或重启后，可以识别并清理过期的 RUNNING 记录。</li></ul>
 *
 * <p>实现：<ul><li>{@code WorkspaceAsyncToolRegistry}：基于工作区文件系统的实现，适合测试；持久性取决于后端。</li></ul>
 */
public interface AsyncToolRegistry {

    /**
     * 注册新的异步工具执行。
     * @param record 状态为 {@link AsyncToolRecord#RUNNING} 的执行记录
     */
    Mono<Void> register(AsyncToolRecord record);

    /**
     * 将异步执行标记为完成。
     * @param id 异步工具记录 ID
     * @param result 工具执行结果文本
     */
    Mono<Void> complete(String id, String result);

    /**
     * 将异步执行标记为失败。
     * @param id 异步工具记录 ID
     * @param error 错误信息
     */
    Mono<Void> fail(String id, String error);

    /**
     * 查找指定会话中 RUNNING 持续时间超过 TTL 的记录。这些记录可能因进程崩溃而失去执行者。
     * @param sessionId 待检查会话
     * @param ttl 超过此时长的记录视为过期
     * @return 过期的 RUNNING 记录
     */
    Mono<List<AsyncToolRecord>> findStale(String sessionId, Duration ttl);

    /**
     * 将过期记录标记为超时，避免后续 {@link #findStale} 再次返回它。
     * @param id 异步工具记录 ID
     */
    Mono<Void> markTimeout(String id);
}
