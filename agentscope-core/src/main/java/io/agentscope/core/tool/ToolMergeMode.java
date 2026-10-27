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
 * 定义如何将外部注入工具（例如前端工具）与智能体共享工具集合并的枚举。
 *
 * <p>外部工具按定义只有模式、没有本地实现。此枚举控制逐调用 {@link ToolRequestConfig} 如何与共享无状态 {@link Toolkit} 组合，形成一次调用可见的工具范围。
 */
public enum ToolMergeMode {

    /**
     * 仅使用外部注入工具。
     *
     * <p>完全忽略智能体共享工具集，只提供逐调用请求配置携带的工具。
     */
    EXTERNAL_ONLY,

    /**
     * 仅使用智能体共享工具集。
     *
     * <p>忽略外部注入工具，只提供已注册到智能体工具集的工具。
     */
    AGENT_ONLY,

    /**
     * 合并外部工具与智能体工具集，外部工具优先。
     *
     * <p>两者均可用；名称冲突时，外部工具覆盖智能体工具。
     */
    MERGE_EXTERNAL_PRIORITY
}
