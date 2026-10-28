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
package io.agentscope.core.e2e;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/** E2E 测试的 JUnit 5 执行条件。<p>要求 ENABLE_E2E_TESTS 为 true，且至少有一个 API 密钥（如 DASHSCOPE_API_KEY），显式控制测试执行。<ul><li>{@code mvn test}：默认禁用 E2E。<li>{@code ENABLE_E2E_TESTS=true mvn test}：启用 E2E。<li>{@code mvn test -DENABLE_E2E_TESTS=true}：通过系统属性启用。</ul> */
public class E2ETestCondition implements ExecutionCondition {

    /** 启用 E2E 测试的环境变量名。 */
    public static final String ENABLE_E2E_ENV = "ENABLE_E2E_TESTS";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        // 检查是否显式启用 E2E 测试
        String enableE2E = System.getenv(ENABLE_E2E_ENV);
        if (enableE2E == null || enableE2E.isEmpty()) {
            enableE2E = System.getProperty(ENABLE_E2E_ENV);
        }

        // ENABLE_E2E_TESTS 未设置或为 false 时禁用
        if (enableE2E == null
                || enableE2E.isEmpty()
                || "false".equalsIgnoreCase(enableE2E)
                || "0".equals(enableE2E)) {
            return ConditionEvaluationResult.disabled(
                    "E2E tests disabled. Set ENABLE_E2E_TESTS=true to enable.");
        }

        // 检查是否存在可用 API 密钥
        if (!ProviderFactory.hasAnyApiKey()) {
            return ConditionEvaluationResult.disabled(
                    "E2E tests enabled but no API keys configured. "
                            + "Set DASHSCOPE_API_KEY or other provider keys.");
        }

        return ConditionEvaluationResult.enabled(
                "E2E tests enabled. Available API keys: " + ProviderFactory.getApiKeyStatus());
    }
}
