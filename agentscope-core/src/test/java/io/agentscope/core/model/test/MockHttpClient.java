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
package io.agentscope.core.model.test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 用于测试模型实现的模拟 HTTP 客户端。
 *
 * <p>允许测试模拟 HTTP 响应而不发出真实网络调用。
 */
public class MockHttpClient {

    private final List<MockRequest> requests = new ArrayList<>();
    private Function<MockRequest, MockResponse> responseProvider;
    private boolean shouldThrowError = false;
    private String errorMessage = "Mock HTTP error";

    public MockHttpClient() {
        // 默认响应提供者
        this.responseProvider = req -> MockResponse.success(200, "{}");
    }

    /**
     * 配置响应提供者。
     */
    public MockHttpClient withResponseProvider(Function<MockRequest, MockResponse> provider) {
        this.responseProvider = provider;
        return this;
    }

    /**
     * 配置为抛出错误。
     */
    public MockHttpClient withError(String errorMessage) {
        this.shouldThrowError = true;
        this.errorMessage = errorMessage;
        return this;
    }

    /**
     * 模拟 HTTP 请求。
     */
    public MockResponse request(
            String method, String url, Map<String, String> headers, String body) {
        MockRequest request = new MockRequest(method, url, headers, body);
        requests.add(request);

        if (shouldThrowError) {
            throw new RuntimeException(errorMessage);
        }

        return responseProvider.apply(request);
    }

    /**
     * 获取已发出的全部请求。
     */
    public List<MockRequest> getRequests() {
        return new ArrayList<>(requests);
    }

    /**
     * 获取请求数量。
     */
    public int getRequestCount() {
        return requests.size();
    }

    /**
     * 重置模拟状态。
     */
    public void reset() {
        requests.clear();
        shouldThrowError = false;
    }

    /**
     * 模拟 HTTP 请求。
     */
    public static class MockRequest {
        private final String method;
        private final String url;
        private final Map<String, String> headers;
        private final String body;

        public MockRequest(String method, String url, Map<String, String> headers, String body) {
            this.method = method;
            this.url = url;
            this.headers = headers != null ? new HashMap<>(headers) : new HashMap<>();
            this.body = body;
        }

        public String getMethod() {
            return method;
        }

        public String getUrl() {
            return url;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public String getBody() {
            return body;
        }
    }

    /**
     * 模拟 HTTP 响应。
     */
    public static class MockResponse {
        private final int statusCode;
        private final String body;
        private final Map<String, String> headers;

        public MockResponse(int statusCode, String body, Map<String, String> headers) {
            this.statusCode = statusCode;
            this.body = body;
            this.headers = headers != null ? new HashMap<>(headers) : new HashMap<>();
        }

        public static MockResponse success(int statusCode, String body) {
            return new MockResponse(statusCode, body, new HashMap<>());
        }

        public static MockResponse error(int statusCode, String errorMessage) {
            return new MockResponse(statusCode, errorMessage, new HashMap<>());
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getBody() {
            return body;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }
    }
}
