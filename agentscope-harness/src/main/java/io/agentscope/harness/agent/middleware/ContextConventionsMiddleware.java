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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.context.ContextItem;
import io.agentscope.harness.agent.context.WorkspaceContextMaterials;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import reactor.core.publisher.Mono;

/** 框架自有、与领域无关的上下文指令，从带版本的资源中加载一次。 */
public final class ContextConventionsMiddleware implements HarnessRuntimeMiddleware {
    private static final String INSTRUCTIONS = load();

    private static String load() {
        try (var stream =
                ContextConventionsMiddleware.class.getResourceAsStream(
                        "/io/agentscope/harness/context-conventions.md")) {
            if (stream == null)
                throw new IllegalStateException("Missing context conventions resource");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load context conventions", error);
        }
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String prompt) {
        return Mono.fromSupplier(
                () -> {
                    // 安装在其他框架提供者之前：每次智能体调用都会取得新的快照，
                    // 即使调用方复用了 RuntimeContext，或子智能体继承了该上下文。
                    // 已禁用的提供者不得保留上一个智能体的数据。
                    RuntimeContext rc = context == null ? RuntimeContext.empty() : context;
                    rc.put(
                            WorkspaceContextMaterials.class,
                            new WorkspaceContextMaterials(
                                    List.of(
                                            ContextItem.instruction(
                                                    "instruction_rules",
                                                    "harness:context-conventions",
                                                    INSTRUCTIONS))));
                    return prompt == null ? "" : prompt;
                });
    }
}
