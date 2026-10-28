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
package io.agentscope.dataagent.runtime.session;

/** 控制运行完成后如何处理会话记录与对话文本，对应 OpenClaw 子智能体派生时的 {@code cleanup} 参数。<ul><li>{@link #KEEP}：保留会话元数据及对话记录（会话模式默认值）。<li>{@link #DELETE}：完成后移除会话元数据及对话记录。</ul> */
public enum CleanupPolicy {
    KEEP,
    DELETE
}
