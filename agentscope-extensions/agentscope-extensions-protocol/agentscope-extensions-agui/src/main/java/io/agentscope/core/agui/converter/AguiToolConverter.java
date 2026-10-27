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
package io.agentscope.core.agui.converter;

import io.agentscope.core.agui.model.AguiTool;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AG-UI 工具与 AgentScope 工具 schema 之间的转换器。
 *
 * <p>负责 AG-UI 协议工具格式与 AgentScope 内部 ToolSchema 格式的双向转换。
 */
public class AguiToolConverter {

    /**
     * 将 AG-UI 工具转换为 AgentScope ToolSchema。
     *
     * @param aguiTool 要转换的 AG-UI 工具
     * @return 转换后的 ToolSchema
     */
    public ToolSchema toToolSchema(AguiTool aguiTool) {
        return ToolSchema.builder()
                .name(aguiTool.getName())
                .description(aguiTool.getDescription())
                .parameters(aguiTool.getParameters())
                .build();
    }

    /**
     * 将 AgentScope ToolSchema 转换为 AG-UI 工具。
     *
     * @param schema 要转换的 AgentScope ToolSchema
     * @return 转换后的 AG-UI 工具
     */
    public AguiTool toAguiTool(ToolSchema schema) {
        return new AguiTool(schema.getName(), schema.getDescription(), schema.getParameters());
    }

    /**
     * 将 AG-UI 工具列表转换为 AgentScope ToolSchema 列表。
     *
     * @param aguiTools 要转换的 AG-UI 工具
     * @return 转换后的 ToolSchema 列表
     */
    public List<ToolSchema> toToolSchemaList(List<AguiTool> aguiTools) {
        return aguiTools.stream().map(this::toToolSchema).collect(Collectors.toList());
    }

    /**
     * 将 AgentScope ToolSchema 列表转换为 AG-UI 工具。
     *
     * @param schemas 要转换的 AgentScope ToolSchema
     * @return 转换后的 AG-UI 工具
     */
    public List<AguiTool> toAguiToolList(List<ToolSchema> schemas) {
        return schemas.stream().map(this::toAguiTool).collect(Collectors.toList());
    }
}
