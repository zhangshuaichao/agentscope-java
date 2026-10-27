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
package io.agentscope.core.rag.integration.ragflow.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * RAGFlow ResponseData 的自定义反序列化器。
 *
 * <p>RAGFlow API 在错误、无结果等情况下可能返回 "data": false 或 "data": null。
 * 此反序列化器通过返回 null 而非抛出异常，平稳处理这些情况。
 *
 * <p><b>API 响应示例：</b>
 *
 * <pre>{@code
 * // Success case
 * {"code": 0, "data": {"chunks": [...], "total": 1}}
 *
 * // Error or empty case
 * {"code": 404, "message": "Dataset not found", "data": false}
 * {"code": 0, "message": "No results", "data": null}
 * }</pre>
 *
 * @author RAGFlow Integration Team
 */
public class ResponseDataDeserializer extends JsonDeserializer<RAGFlowResponse.ResponseData> {

    @Override
    public RAGFlowResponse.ResponseData deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {

        JsonNode node = p.getCodec().readTree(p);

        // 处理 data 为 false、null 或非对象的情况
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }

        // 正常反序列化对象
        RAGFlowResponse.ResponseData responseData = new RAGFlowResponse.ResponseData();

        // 解析 chunks
        if (node.has("chunks") && node.get("chunks").isArray()) {
            List<RAGFlowChunk> chunks = new ArrayList<>();
            for (JsonNode chunkNode : node.get("chunks")) {
                RAGFlowChunk chunk = p.getCodec().treeToValue(chunkNode, RAGFlowChunk.class);
                if (chunk != null) {
                    chunks.add(chunk);
                }
            }
            responseData.setChunks(chunks);
        }

        // 解析 total
        if (node.has("total") && !node.get("total").isNull()) {
            responseData.setTotal(node.get("total").asInt());
        }

        // 解析 doc_aggs
        if (node.has("doc_aggs") && node.get("doc_aggs").isArray()) {
            List<RAGFlowResponse.DocAgg> docAggs = new ArrayList<>();
            for (JsonNode aggNode : node.get("doc_aggs")) {
                RAGFlowResponse.DocAgg docAgg =
                        p.getCodec().treeToValue(aggNode, RAGFlowResponse.DocAgg.class);
                if (docAgg != null) {
                    docAggs.add(docAgg);
                }
            }
            responseData.setDocAggs(docAggs);
        }

        return responseData;
    }
}
