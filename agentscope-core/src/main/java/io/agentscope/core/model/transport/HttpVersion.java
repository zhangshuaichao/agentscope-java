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
package io.agentscope.core.model.transport;

import java.net.http.HttpClient;

/** HTTP 版本。 */
public enum HttpVersion {

    /** HTTP 1.1。强制所有请求使用 HTTP/1.1，包括 {@code https://} URL。 */
    HTTP_1_1,

    /**
     * HTTP 2。在 {@code https://} URL 上通过 ALPN 协商；在明文 URL 上尝试
     * h2c 升级（RFC 7540 第 3.2 节）。某些服务端（例如 vLLM/uvicorn）
     * 处理升级时会丢弃请求体，因此仅在确认服务端支持时才显式设置。
     */
    HTTP_2;

    public HttpClient.Version toJdkHttpVersion() {
        return switch (this) {
            case HTTP_1_1 -> HttpClient.Version.HTTP_1_1;
            case HTTP_2 -> HttpClient.Version.HTTP_2;
        };
    }
}
