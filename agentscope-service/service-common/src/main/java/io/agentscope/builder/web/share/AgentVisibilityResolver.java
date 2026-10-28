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
package io.agentscope.builder.web.share;

import io.agentscope.builder.web.catalog.AgentDefinition;
import java.util.Optional;

/** 解析智能体对用户是否可见，并返回其定义。控制平面目录服务（权威来源，基于文件及 JPA）和数据平面（共享数据库上的纯 JPA 读取视图）分别实现，使两个平面无需跨平面依赖即可使用 {@link AgentAccessGuard}。 */
public interface AgentVisibilityResolver {

    /** 当 {@code agentId} 对 {@code userId} 可见时返回其定义（全局范围、用户自有，或经授权共享）；否则返回 {@link Optional#empty()}。 */
    Optional<AgentDefinition> findVisible(String userId, String agentId);
}
