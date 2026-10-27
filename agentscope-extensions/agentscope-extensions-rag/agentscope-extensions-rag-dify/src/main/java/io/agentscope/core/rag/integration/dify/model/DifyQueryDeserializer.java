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
package io.agentscope.core.rag.integration.dify.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

/**
 * {@link DifyResponse.Query} 的自定义反序列化器。
 *
 * <p>Dify 按部署方式以两种形式序列化检索响应的顶层 {@code query} 字段：
 * <pre>{@code
 * // Dify 云服务（api.dify.ai）
 * {"query": "ping", "records": [...]}
 *
 * // 自行托管的 Dify
 * {"query": {"content": "ping"}, "records": [...]}
 * }</pre>
 *
 * <p>该字段仅用于说明，因此未知形状不应导致整个响应失败。
 * 仅将它建模为对象会使 Dify Cloud 返回的所有检索记录都被丢弃。
 */
public class DifyQueryDeserializer extends JsonDeserializer<DifyResponse.Query> {

    @Override
    public DifyResponse.Query deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {

        JsonNode node = p.getCodec().readTree(p);

        if (node == null || node.isNull()) {
            return null;
        }

        DifyResponse.Query query = new DifyResponse.Query();

        if (node.isTextual()) {
            query.setContent(node.asText());
            return query;
        }

        if (node.isObject()) {
            JsonNode content = node.get("content");
            if (content != null && content.isTextual()) {
                query.setContent(content.asText());
            }
            return query;
        }

        // 未知形状：查询文本仅作说明，保留检索记录。
        return null;
    }
}
