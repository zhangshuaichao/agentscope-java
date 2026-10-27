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
 * 服务器内部错误异常，HTTP 5xx。
 *
 * <p>表示服务端出现问题，可考虑短暂等待后重试请求。
 *
 * <p>常见状态码：
 * <ul><li>500：服务器内部错误。</li><li>502：网关错误。</li><li>503：服务不可用。</li><li>504：网关超时。</li></ul>
 */
public class InternalServerException extends OpenAIException {

    public InternalServerException(
            String message, int statusCode, String errorCode, String responseBody) {
        super(message, statusCode, errorCode, responseBody);
    }
}
