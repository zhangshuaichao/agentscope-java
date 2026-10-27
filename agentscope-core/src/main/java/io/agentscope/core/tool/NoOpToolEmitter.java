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
package io.agentscope.core.tool;

import io.agentscope.core.message.ToolResultBlock;

/**
 * ToolEmitter 的空操作实现，静默丢弃全部发出的数据块。
 *
 * <p>未配置数据块回调时用作默认值，使工具无需检查 null 即可调用 emit()。
 */
public class NoOpToolEmitter implements ToolEmitter {

    /**
     * 单例实例。
     */
    public static final NoOpToolEmitter INSTANCE = new NoOpToolEmitter();

    private NoOpToolEmitter() {}

    /**
     * 静默丢弃数据块，不执行任何操作。
     *
     * <p>此空操作实现使工具无需检查 null 即可发送数据块，即使未配置实际回调也能保持一致行为。
     *
     * @param chunk 要丢弃的工具结果数据块（忽略）
     */
    @Override
    public void emit(ToolResultBlock chunk) {
        // 空操作：静默丢弃数据块
    }
}
