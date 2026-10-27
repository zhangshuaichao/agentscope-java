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
package io.agentscope.core.model;

/**
 * 为可暴露 HTTP 状态码的模型服务商定义异常契约。
 *
 * <p>此契约使重试分类与具体服务商无关，同时允许扩展模块保留
 * 服务商特有的异常类型。
 */
public interface ModelHttpException {

    /**
     * 返回此次模型失败对应的 HTTP 状态码。
     *
     * @return HTTP 状态码；无法获取时为 null
     */
    Integer getStatusCode();

    /**
     * 返回此 HTTP 状态默认是否可重试。
     *
     * <p>限流（429）与服务端错误（5xx）被视为可重试。
     *
     * @return 此状态码应重试时返回 true
     */
    default boolean isRetryableHttpStatus() {
        Integer statusCode = getStatusCode();
        return statusCode != null && (statusCode == 429 || statusCode >= 500 && statusCode < 600);
    }
}
