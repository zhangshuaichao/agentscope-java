/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.mongodb.testutil;

import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * Docker 不可用时禁用测试类的 JUnit 5 {@link ExecutionCondition}。
 *
 * <p>防止 {@code @Testcontainers} 在无 Docker 守护进程的环境（如 GitHub 托管 Windows 运行器）启动容器，触发 {@code IllegalStateException: Could not find a valid Docker environment}。
 *
 * <p>通过 {@link RequireDocker @RequireDocker} 组合注解使用。
 */
class DockerAvailabilityCondition implements ExecutionCondition {

    private static final ConditionEvaluationResult ENABLED =
            ConditionEvaluationResult.enabled("Docker is available");
    private static final ConditionEvaluationResult DISABLED =
            ConditionEvaluationResult.disabled("Docker is not available; skipping Testcontainers");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Optional<Class<?>> testClass = context.getTestClass();
        if (testClass.isEmpty()) {
            return ENABLED;
        }

        boolean requireDocker =
                testClass.get().getAnnotation(RequireDocker.class) != null
                        || context.getTestMethod()
                                .map(Method::getDeclaringClass)
                                .map(c -> c.getAnnotation(RequireDocker.class) != null)
                                .orElse(false);

        if (!requireDocker) {
            return ENABLED;
        }

        try {
            return DockerClientFactory.instance().isDockerAvailable() ? ENABLED : DISABLED;
        } catch (Exception e) {
            return DISABLED;
        }
    }
}
