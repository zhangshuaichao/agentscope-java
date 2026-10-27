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
package io.agentscope.core.agent.accumulator;

import io.agentscope.core.message.ContentBlock;

/**
 * 用于累积流式内容块的内容累积器接口。
 *
 * <p>定义从流式响应中累积内容块的契约；文本、思考、工具调用等不同内容类型采用不同累积策略。
 *
 * @hidden
 * @param <T> 要累积的内容块类型
 */
public interface ContentAccumulator<T extends ContentBlock> {

    /**
     * 向累积器添加内容块片段。
     *
     * @hidden
     * @param block 要添加的内容块片段
     */
    void add(T block);

    /**
     * 检查累积器是否已有内容。
     *
     * @hidden
     * @return 存在累积内容时为 true，否则为 false
     */
    boolean hasContent();

    /**
     * 根据全部已累积片段构建汇总内容块。
     *
     * @hidden
     * @return 汇总内容块；没有内容时为 null
     */
    ContentBlock buildAggregated();

    /**
     * @hidden
     * 重置累积器状态，清空全部已累积内容。
     */
    void reset();
}
