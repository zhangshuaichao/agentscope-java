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

/**
 * 已生成、可发送至浏览器的 A2UI 界面。
 * @param surfaceId {@code operations} 各操作共享的界面标识
 * @param catalogId 组件来源目录
 * @param intent 生成界面的自然语言请求
 * @param generatedBy 生成方：{@code gemini}、{@code dashscope}、{@code fallback}
 * @param componentCount 渲染树的组件数量
 * @param operations 原始 A2UI v0.9 服务端到客户端消息
 */
public record A2uiSurface(
        String surfaceId,
        String catalogId,
        String intent,
        String generatedBy,
        int componentCount,
        List<Map<String, Object>> operations) {}
