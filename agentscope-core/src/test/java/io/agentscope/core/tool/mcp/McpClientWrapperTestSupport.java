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
package io.agentscope.core.tool.mcp;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模拟 {@link McpClientWrapper} 实例的共享工厂。
 *
 * <p>Mockito 将未设置桩的布尔方法默认设为 {@code false}，会静默使 {@link McpTool#callAsync} 进入“省略 meta”而非传播路径。因此使用模拟包装器测试 {@link McpTool} 时，必须通过此辅助方法创建模拟对象，显式设置连接级开关，明确测试意图。
 */
public final class McpClientWrapperTestSupport {

    private McpClientWrapperTestSupport() {}

    /**
     * 创建显式设置 {@code isPropagateMeta()} 桩的模拟包装器。
     *
     * @param name {@link McpClientWrapper#getName()} 返回值
     * @param propagateMeta {@link McpClientWrapper#isPropagateMeta()} 返回值
     * @return 模拟包装器
     */
    public static McpClientWrapper mockWrapper(String name, boolean propagateMeta) {
        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.getName()).thenReturn(name);
        when(wrapper.isPropagateMeta()).thenReturn(propagateMeta);
        return wrapper;
    }
}
