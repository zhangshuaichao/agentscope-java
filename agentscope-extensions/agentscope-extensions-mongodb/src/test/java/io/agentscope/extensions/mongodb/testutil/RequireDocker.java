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

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * 当前环境无 Docker 时禁用被注解测试类的组合注解。
 *
 * <p>与 {@code @Testcontainers} 一起使用，避免无 Docker 守护进程的 CI 运行器启动容器失败：
 *
 * <pre>{@literal @}Testcontainers
 * @RequireDocker
 * class MyContainerTest { ... }
 * </pre>
 *
 * <p>无 Docker 时报告为<em>跳过</em>而非失败；具有 Docker 的运行器仍执行完整测试。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@ExtendWith(DockerAvailabilityCondition.class)
public @interface RequireDocker {}
