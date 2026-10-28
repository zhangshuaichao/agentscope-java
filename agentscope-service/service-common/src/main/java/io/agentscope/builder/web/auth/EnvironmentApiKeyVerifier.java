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
package io.agentscope.builder.web.auth;

/** 依据控制平面状态验证 Environment Worker API 密钥。实现不得从数据平面直接读取控制平面表，应调用控制平面内部 API。 */
@FunctionalInterface
public interface EnvironmentApiKeyVerifier {

    /**
     * @param environmentId 请求路径中的环境 ID
     * @param plaintextKey {@code X-Builder-Environment-Key} 的值
     * @return 密钥对该环境有效时为 true
     */
    boolean matches(String environmentId, String plaintextKey);
}
