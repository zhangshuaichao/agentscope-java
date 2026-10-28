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
package io.agentscope.dataagent.tools.data;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 由管理员维护的数据源目录项，描述智能体可查询的命名数据源。v1 是轻量描述符：稳定 ID、可读标签、JDBC 风格 URL 前缀（或其他连接器提示），以及不透明的连接器专用 {@code properties} 配置映射。<p>具体连接器（JDBC、BigQuery、Hologres、OSS+Parquet）不在 v1 范围内；工具包返回描述符供智能体判断使用哪个源，真实 {@code run_sql_preview} 需后续连接器模块实现。 */
public record DataSource(
        String id,
        String label,
        String description,
        String kind,
        String urlHint,
        List<String> tags,
        Map<String, String> properties) {

    public DataSource {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kind, "kind");
        tags = tags == null ? List.of() : List.copyOf(tags);
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
