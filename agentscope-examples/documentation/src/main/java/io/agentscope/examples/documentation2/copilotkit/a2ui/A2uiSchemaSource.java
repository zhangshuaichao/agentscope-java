/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.documentation2.copilotkit.a2ui;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 提供模型可见的 A2UI 语法，并将回复转为经过检查的载荷。默认实现 {@link BundledA2uiSchemaSource} 结合手写语法和 {@link A2uiOperations} 结构验证。
 * <p>提示与解析共用一个契约，因为语法必须与执行它的验证器对应才有意义。
 */
public interface A2uiSchemaSource {

    /**
     * 暴露给浏览器的短标识，使演示可展示当前使用的路径。
     */
    String name();

    /**
     * 构建 A2UI 组合器的系统提示词。
     * @param clientComponents 浏览器可渲染的自定义组件；为空表示不限制，公布服务端完整目录
     */
    String systemPrompt(Set<String> clientComponents);

    /**
     * 解析并校验原始模型回复。
     * @param rawModelReply 模型完整回答，预期包含 {@code <a2ui-json>} 块
     * @param surfaceId 调用方分配的 ID；操作统一重写到该 ID，即使模型自拟 ID，浏览器也能寻址
     * @param clientComponents 浏览器可渲染组件，限定验证范围
     * @return 可直接传输的 A2UI 服务端到客户端消息
     * @throws RuntimeException 回复不可用时，使组合器尝试下一模型并最终确定性回退
     */
    List<Map<String, Object>> parse(
            String rawModelReply, String surfaceId, Set<String> clientComponents);
}
