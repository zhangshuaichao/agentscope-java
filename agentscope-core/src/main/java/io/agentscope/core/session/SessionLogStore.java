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
package io.agentscope.core.session;

import io.agentscope.core.agent.RuntimeContext;
import java.util.List;

/** 可扩展的会话日志后端。打开读取器不得获取写入者身份或修复历史。 */
@FunctionalInterface
public interface SessionLogStore {
    SessionLog open(SessionKey key, RuntimeContext context);

    /**
 * 在调用方存储命名空间中发现原生会话，不获取写入者身份。
     * 实现应将结果限制在调用方的用户身份内。这是发现视图，不是事务快照；
     * 并发创建的会话可能在后续调用中才出现。
 */
    default List<SessionKey> list(RuntimeContext context) {
        throw new UnsupportedOperationException(
                "This session log store does not support discovery");
    }
}
