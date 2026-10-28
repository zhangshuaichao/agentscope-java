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
package io.agentscope.spring.boot.admin.subagent;

import java.util.List;

/**
 * 枚举当前进程可用子智能体的 SPI。
 *
 * <p>提供 SPI 的原因：已构建的 {@code HarnessAgent} 不直接暴露注册子智能体列表，编排在 {@code SubagentsMiddleware} 内捕获条目，中间件自身私有持有。应用可将 {@link io.agentscope.harness.agent.middleware.SubagentEntry} 或 {@link io.agentscope.harness.agent.subagent.SubagentDeclaration} 暴露为 Spring bean（默认 {@link SpringSubagentInventory} 收集），或提供自己的 {@code SubagentInventory} 实现。
 */
public interface SubagentInventory {

    /**
 * 当前进程已知的所有子智能体注册项，可为空。
 */
    List<SubagentDescriptor> list();
}
