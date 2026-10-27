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
import io.agentscope.core.message.TextBlock;

/**
 * 用于累积流式文本片段的文本内容累积器。
 *
 * <p>按顺序连接所有文本片段，构建完整文本内容。
 * @hidden
 */
public class TextAccumulator implements ContentAccumulator<TextBlock> {

    private final StringBuilder accumulated = new StringBuilder();

    /**
     * @hidden
     */
    @Override
    public void add(TextBlock block) {
        if (block != null && block.getText() != null) {
            accumulated.append(block.getText());
        }
    }

    /**
     * @hidden
     */
    @Override
    public boolean hasContent() {
        return accumulated.length() > 0;
    }

    /**
     * @hidden
     */
    @Override
    public ContentBlock buildAggregated() {
        if (!hasContent()) {
            return null;
        }
        return TextBlock.builder().text(accumulated.toString()).build();
    }

    /**
     * @hidden
     */
    @Override
    public void reset() {
        accumulated.setLength(0);
    }

    /**
     * 获取已累积的文本内容。
     *
     * @hidden
     * @return 以字符串形式返回累积的文本
     */
    public String getAccumulated() {
        return accumulated.toString();
    }

    /**
     * 用从模型调用事件流重建的内容替换已累积文本。
     *
     * @hidden
     */
    public void replace(String text) {
        accumulated.setLength(0);
        if (text != null) {
            accumulated.append(text);
        }
    }
}
