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

import java.util.List;

/**
 * 范围明确的存储能力。CAS 必须在共享此命名空间的所有写入者之间保持原子性。
 * 缺失值为 null；读写失败应抛出异常。实现必须先持久化字节，再返回 ACK。
 * 模型的文件系统工具不得写入运行时日志存储。
 */
public interface AtomicSessionStorage {
    record Value(long version, byte[] bytes) {
        public Value {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    Value read(String path);

    /** 可选的只读能力，用于发现某前缀下的对象路径。恢复过程绝不依赖列表枚举。 */
    default List<String> listPaths(String prefix) {
        throw new UnsupportedOperationException("This session storage does not support discovery");
    }

    /** expectedVersion=0 表示不存在时创建。不支持的后端必须抛出异常。 */
    boolean compareAndSet(String path, long expectedVersion, byte[] bytes);
}
