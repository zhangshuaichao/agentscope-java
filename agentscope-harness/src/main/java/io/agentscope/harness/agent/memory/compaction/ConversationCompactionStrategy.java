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
package io.agentscope.harness.agent.memory.compaction;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Optional;
import reactor.core.publisher.Mono;

/** 在既有最终请求边界执行的可选压缩策略。 */
@FunctionalInterface
public interface ConversationCompactionStrategy {
    /** 空值表示使用配置的标准压缩器；存在的列表表示提议采用的历史。 */
    Mono<Optional<List<Msg>>> compact(Request request);

    record Request(
            RuntimeContext context,
            List<Msg> messages,
            String agentId,
            String sessionId,
            int tokenBudget) {
        public Request {
            messages = List.copyOf(messages);
        }
    }
}
