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
package io.agentscope.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryUnconditionalVersionTest {
    record T(String v) implements State {}

    @Test
    @DisplayName("unconditional save returns its own version even when a writer interleaves")
    void unconditionalSaveReturnsOwnVersion() {
        InMemoryAgentStateStore store =
                new InMemoryAgentStateStore() {
                    @Override
                    public void save(String u, String s, String k, State v) {
                        super.save(u, s, k, v);
                        // 恰好在被测值通过 save() 写入时
                        // 注入并发写入者：主分支旧实现中，无条件 saveIfVersion
                        // 委托给 save()，并在锁外读取版本，因此注入
                        // 会命中该窗口。修复路径不再经过
                        // save()，所以修复后此钩子保持静默。
                        if (v instanceof T t && "mine".equals(t.v())) {
                            super.save(u, s, k, new T("other"));
                        }
                    }
                };
        store.save("u", "s", "k", new T("initial")); // 版本 1
        long returned =
                store.saveIfVersion("u", "s", "k", new T("mine"), AgentStateStore.UNVERSIONED);
        // 本调用方写入版本为 2；主分支旧实现中注入写入者会使其返回 3。
        assertEquals(2L, returned);
        // 并发写入者在无条件保存返回后提交，存储推进到
        // 版本 3，但上方返回版本仍是本调用方自身写入的版本 2。
        store.save("u", "s", "k", new T("other"));
        var latest = store.getVersioned("u", "s", "k", T.class);
        assertEquals(3L, latest.version());
        assertEquals("other", latest.value().v());
    }
}
