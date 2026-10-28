/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.scheduler.quartz;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.scheduler.config.ModelConfig;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * 调度器单元测试使用的完全本地 {@link ModelConfig}。
 *
 * <p>旧测试用占位密钥构建真实 {@code DashScopeChatModel}，触发器若在测试中触发就访问生产端点。CI 竞争下 {@code AgentQuartzJob} 会阻塞模型调用，而 {@code tearDown} 的 {@code scheduler.shutdown(true)} 又等待它，传输默认值可能使每次尝试停顿数分钟。此占位实现立即回答，使误调度本身无害，而不依赖赢得时序竞争。
 */
final class StubModelConfig implements ModelConfig {

    @Override
    public String getModelName() {
        return "stub-model";
    }

    @Override
    public Model createModel() {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                ContentBlock block = TextBlock.builder().text("ok").build();
                ChatResponse resp =
                        ChatResponse.builder().id("stub-1").content(List.of(block)).build();
                return Flux.just(resp);
            }

            @Override
            public String getModelName() {
                return "stub-model";
            }
        };
    }
}
