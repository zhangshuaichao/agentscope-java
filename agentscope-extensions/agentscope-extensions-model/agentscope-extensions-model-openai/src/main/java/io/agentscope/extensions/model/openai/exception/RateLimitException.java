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
package io.agentscope.extensions.model.openai.exception;

/**
 * 超过速率限制时抛出的异常，HTTP 429。
 *
 * <p>表示超过分配的速率限制，可考虑指数退避重试。
 *
 * <p>常见原因：
 * <ul><li>每分钟请求过多。</li><li>超出 token 配额。</li><li>达到并发请求上限。</li></ul>
 */
public class RateLimitException extends OpenAIException {

    public RateLimitException(String message, String errorCode, String responseBody) {
        super(message, 429, errorCode, responseBody);
    }
}
