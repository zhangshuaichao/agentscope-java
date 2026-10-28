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
package io.agentscope.builder.web.persistence.jpa;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data 仓库，对应 {@link AgentEntity}. */
public interface AgentEntityRepository extends JpaRepository<AgentEntity, Long> {

    /** {@code ownerId} 拥有的全部智能体定义，最早创建的在前。 */
    List<AgentEntity> findByOwnerIdOrderByCreatedAtAsc(String ownerId);

    /** 通过 {@code (ownerId, agentId)} 组合查找单个定义。 */
    Optional<AgentEntity> findByOwnerIdAndAgentId(String ownerId, String agentId);
}
