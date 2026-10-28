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
package io.agentscope.spring.boot.admin.endpoint;

import io.agentscope.spring.boot.admin.subagent.SubagentDescriptor;
import io.agentscope.spring.boot.admin.subagent.SubagentInventory;
import java.util.List;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * {@code GET /actuator/agentscope-subagents}：枚举已注册子智能体。
 *
 * <p>由 {@link SubagentInventory} bean 填充，默认扫描 Spring 中的 {@link io.agentscope.harness.agent.middleware.SubagentEntry} 和 {@link io.agentscope.harness.agent.subagent.SubagentDeclaration} bean。
 */
@Endpoint(id = "agentscope-subagents")
public class AgentscopeSubagentsEndpoint {

    private final SubagentInventory inventory;

    public AgentscopeSubagentsEndpoint(SubagentInventory inventory) {
        this.inventory = inventory;
    }

    @ReadOperation
    public List<SubagentDescriptor> subagents() {
        return inventory.list();
    }
}
