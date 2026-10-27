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

/**
 * 定义谁管理 {@link ToolGroup} 的激活生命周期。
 *
 * <ul>
 * <li>{@link #META}：由智能体通过 {@code reset_equipped_tools} 元工具管理。这些组出现在元工具参数枚举中，遵循替换语义：只有 {@code to_activate} 参数显式列出的组保持激活。</li>
 * <li>{@link #EXTERNAL}：由开发者代码管理（例如程序化激活或停用）。对元工具不可见，也不受其调用影响。</li>
 * </ul>
 */
public enum ToolGroupScope {

    /**
     * 由元工具 {@code reset_equipped_tools} 管理的组，智能体可在运行时激活或停用。
     */
    META,

    /**
     * 由外部开发者代码管理的组，元工具不能查看或修改。
     */
    EXTERNAL
}
