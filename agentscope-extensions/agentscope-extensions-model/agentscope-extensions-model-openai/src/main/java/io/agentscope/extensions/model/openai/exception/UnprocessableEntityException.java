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
 * 请求格式正确但语义不合法时抛出的异常，HTTP 422。
 *
 * <p>常见原因：
 * <ul><li>参数值超出范围。</li><li>参数冲突。</li><li>校验错误。</li></ul>
 */
public class UnprocessableEntityException extends OpenAIException {

    public UnprocessableEntityException(String message, String errorCode, String responseBody) {
        super(message, 422, errorCode, responseBody);
    }
}
