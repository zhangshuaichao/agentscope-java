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
package io.agentscope.core.state;

/**
 * 智能体会话状态的乐观并发保存与其他写入方的更新冲突时应用的策略。
 *
 * <p>CAS 失败发生在一轮结束时；回复可能已流式输出，工具副作用也可能已经发生，
 * 因此该策略不能回滚这些操作。请显式选择：
 *
 * <p>【学习提示】CAS 保护的是状态写入的版本一致性，不是整个智能体调用的事务回滚。
 * 不能因保存冲突就盲目重放整轮工具调用，否则可能重复产生外部副作用。
 *
 * <ul>
 *   <li>{@link #OVERWRITE} — 无条件最后写入者获胜（默认，与引入版本控制前的行为一致）。
 *   <li>{@link #FAIL} — 抛出 {@link ConcurrentSessionModificationException} 并跳过写入。
 *       启用分布式会话轮次门控时建议使用。
 *   <li>{@link #APPEND_MERGE} — 重新加载最新基线，追加本轮新增消息，再重试 CAS。
 *       适合普通聊天轮次，不适合上下文压缩／摘要轮次。
 * </ul>
 */
public enum ConflictPolicy {
    /** 无条件覆盖（默认；保留旧版最后写入者获胜的语义）。 */
    OVERWRITE,

    /** 保存失败并抛出 {@link ConcurrentSessionModificationException}。 */
    FAIL,

    /**
     * 重新加载最新基线，追加本轮新增消息，再重试 CAS。
     * 仅在显式选择时启用；会重写历史的上下文压缩／摘要轮次不能安全使用此策略。
     */
    APPEND_MERGE
}
