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

package io.agentscope.extensions.judge.jev.context;

import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * 宿主授权的不可变压缩前快照存储；绝不重新执行工具。
 */
public interface JevContextArchive {
    record Scope(String userId, String agentId, String sessionId) {
        public Scope {
            for (String value : new String[] {userId, agentId, sessionId})
                if (value == null
                        || value.isBlank()
                        || value.length() > 512
                        || value.indexOf(0) >= 0)
                    throw new IllegalArgumentException("user, agent and session scope required");
        }
    }

    Mono<String> save(Scope scope, List<Msg> messages);

    /**
     * 返回先前快照供检查。宿主不得覆盖更新的活动历史。
     */
    Mono<List<Msg>> restore(Scope scope, String reference);
}
