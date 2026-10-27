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
import io.agentscope.core.message.ThinkingBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用于累积流式思考片段的思考内容累积器。
 *
 * <p>按顺序连接全部思考片段，构建完整思考内容。
 *
 * <p>列表类型元数据（例如推理详情）按流顺序累积：每个片段的条目追加而非替换。
 * 标量元数据采用后写覆盖策略。列表值始终复制，防止调用方修改影响累积器状态。
 * @hidden
 */
public class ThinkingAccumulator implements ContentAccumulator<ThinkingBlock> {

    private final StringBuilder accumulated = new StringBuilder();
    private final Map<String, Object> metadata = new HashMap<>();

    /**
     * @hidden
     */
    @Override
    public void add(ThinkingBlock block) {
        if (block != null && block.getThinking() != null) {
            accumulated.append(block.getThinking());
        }
        if (block != null && block.getMetadata() != null && !block.getMetadata().isEmpty()) {
            mergeMetadata(block.getMetadata());
        }
    }

    /**
     * @hidden
     */
    @Override
    public boolean hasContent() {
        return accumulated.length() > 0 || !metadata.isEmpty();
    }

    /**
     * @hidden
     */
    @Override
    public ContentBlock buildAggregated() {
        if (!hasContent()) {
            return null;
        }
        ThinkingBlock.Builder builder = ThinkingBlock.builder().thinking(accumulated.toString());
        if (!metadata.isEmpty()) {
            builder.metadata(new HashMap<>(metadata));
        }
        return builder.build();
    }

    /**
     * @hidden
     */
    @Override
    public void reset() {
        accumulated.setLength(0);
        metadata.clear();
    }

    /**
     * 将传入元数据合并到累积器的元数据映射中。
     *
     * <p>列表值按流顺序累积（追加而非替换）；标量值采用后写覆盖策略。
     * 列表值始终复制，防止调用方修改。
     */
    private void mergeMetadata(Map<String, Object> incoming) {
        for (Map.Entry<String, Object> entry : incoming.entrySet()) {
            String key = entry.getKey();
            Object newValue = entry.getValue();
            Object existing = metadata.get(key);
            if (existing instanceof List<?> existingList && newValue instanceof List<?> newList) {
                // 两者都是列表：按流顺序连接
                List<Object> combined = new ArrayList<>(existingList);
                combined.addAll(newList);
                metadata.put(key, combined);
            } else if (newValue instanceof List<?> newList) {
                // 此键首次出现列表：复制以防调用方修改
                metadata.put(key, new ArrayList<>(newList));
            } else {
                // 标量值：后写覆盖
                metadata.put(key, newValue);
            }
        }
    }

    /**
     * 获取已累积的思考内容。
     *
     * @hidden
     * @return 以字符串形式返回累积的思考内容
     */
    public String getAccumulated() {
        return accumulated.toString();
    }
}
