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
package io.agentscope.core.memory.mem0;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Mem0 添加记忆 API 的响应。
 *
 * <p>由 {@code POST /v1/memories} 添加请求返回，包含提取结果及相关操作元数据。
 *
 * <p>{@code results} 描述本次响应提供的记忆结果；同步完成提取且没有结果时，可能表示未找到值得记忆的信息。
 * 异步模式下不能仅凭立即返回的空列表断定后台永远不会生成记忆。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Mem0AddResponse {

    /**
     * 记忆结果列表，每项包含提取的记忆与元数据。
     * 未提取出记忆或异步结果尚未提供时可能为空。
     */
    private List<Map<String, Object>> results;

    /** 可选的操作结果说明消息。 */
    private String message;

    /** 供 Jackson 使用的默认构造器。 */
    public Mem0AddResponse() {}

    // 读取与设置方法。

    public List<Map<String, Object>> getResults() {
        return results;
    }

    public void setResults(List<Map<String, Object>> results) {
        this.results = results;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public String toString() {
        return "Mem0AddResponse{"
                + "results="
                + (results != null ? results.size() + " items" : "null")
                + ", message='"
                + message
                + '\''
                + '}';
    }
}
