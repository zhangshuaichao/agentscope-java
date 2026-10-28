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
package io.agentscope.spring.boot.agui.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 为自动注册指定自定义智能体 ID 的注解。
 *
 * <p>Agent bean 带有此注解时，在 {@link io.agentscope.core.agui.registry.AguiAgentRegistry} 中使用指定 ID 而非 bean 名称注册。
 *
 * <p><b>用法：</b>
 *
 * <pre>{@code
 * @Bean
 * @AguiAgentId("custom-chat-agent")
 * public Agent chatAgent() {
 *     return ReActAgent.builder()
 *         .name("ChatAgent")
 *         .model(model)
 *         .build();
 * }
 * }</pre>
 *
 * <p>未声明此注解时，使用 bean 名称作为智能体 ID。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AguiAgentId {

    /**
     * 注册使用的智能体 ID。
     *
     * @return 智能体 ID
     */
    String value();
}
