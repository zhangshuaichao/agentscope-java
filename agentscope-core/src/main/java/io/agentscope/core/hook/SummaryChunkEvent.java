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
package io.agentscope.core.hook;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import java.util.Objects;

/**
 * 摘要流式生成期间触发的事件。
 *
 * <p><b>可修改：</b>否（仅通知）
 *
 * <p><b>上下文：</b>
 * <ul>
 *   <li>{@link #getAgent()}：智能体实例</li>
 *   <li>{@link #getMemory()}：智能体记忆</li>
 *   <li>{@link #getModelName()}：模型名称</li>
 *   <li>{@link #getGenerateOptions()}：生成选项</li>
 *   <li>{@link #getIncrementalChunk()}：仅本片段新增的内容</li>
 *   <li>{@link #getAccumulated()}：截至目前累积的完整消息</li>
 * </ul>
 *
 * <p><b>用途：</b>
 * <ul>
 *   <li>使用 {@link #getIncrementalChunk()} 增量显示（仅追加）</li>
 *   <li>使用 {@link #getAccumulated()} 显示完整上下文（替换整个文本）</li>
 *   <li>实时显示流式摘要输出</li>
 *   <li>监控摘要生成进度</li>
 *   <li>记录流式内容</li>
 * </ul>
 *
 * <p><b>示例：</b>
 * <pre>{@code
 * case SummaryChunkEvent e -> {
 *     // 增量模式：只打印新增内容
 *     System.out.print(extractText(e.getIncrementalChunk()));
 *
 *     // 或者使用累积模式，更新完整显示内容
 *     ui.setText(extractText(e.getAccumulated()));
 *
 *     yield Mono.just(e);
 * }
 * }</pre>
 *
 * @deprecated 自 2.0.0 起弃用。请改用 {@link io.agentscope.core.middleware.MiddlewareBase}。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public final class SummaryChunkEvent extends SummaryEvent {

    private final Msg incrementalChunk;
    private final Msg accumulated;

    /**
     * SummaryChunkEvent 的构造器。
     *
     * @param agent 智能体实例（不可为 null）
     * @param modelName 模型名称（不可为 null）
     * @param generateOptions 生成选项（可为 null）
     * @param incrementalChunk 仅本次流式事件新生成的内容（不可为
     *     null）
     * @param accumulated 包含截至目前全部生成内容的完整累积消息（不可
     *     为 null）
     * @throws NullPointerException 当 agent、modelName、incrementalChunk 或 accumulated 为 null 时抛出
     */
    public SummaryChunkEvent(
            Agent agent,
            String modelName,
            GenerateOptions generateOptions,
            Msg incrementalChunk,
            Msg accumulated) {
        super(HookEventType.SUMMARY_CHUNK, agent, modelName, generateOptions);
        this.incrementalChunk =
                Objects.requireNonNull(incrementalChunk, "incrementalChunk cannot be null");
        this.accumulated = Objects.requireNonNull(accumulated, "accumulated cannot be null");
    }

    /**
     * 仅获取本次流式事件新生成的内容。
     *
     * @return 增量片段
     */
    public Msg getIncrementalChunk() {
        return incrementalChunk;
    }

    /**
     * 获取包含截至目前全部生成内容的完整累积消息。
     *
     * @return 累积消息
     */
    public Msg getAccumulated() {
        return accumulated;
    }
}
