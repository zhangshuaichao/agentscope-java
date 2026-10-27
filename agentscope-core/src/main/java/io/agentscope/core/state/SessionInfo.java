/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.state;

/**
 * 会话信息。
 * <p>
 * 包含已存储会话的元数据，例如存储大小、修改时间以及已保存的状态组件数量。
 */
public class SessionInfo {
    private final String sessionId;
    private final long size;
    private final long lastModified;
    private final int componentCount;

    /**
     * 创建新的 SessionInfo 实例。
     *
     * @param sessionId 会话的唯一标识符
     * @param size 会话存储大小，单位为字节
     * @param lastModified 自纪元起的最后修改时间戳，单位为毫秒
     * @param componentCount 会话中存储的状态组件数量
     */
    public SessionInfo(String sessionId, long size, long lastModified, int componentCount) {
        this.sessionId = sessionId;
        this.size = size;
        this.lastModified = lastModified;
        this.componentCount = componentCount;
    }

    /**
     * 获取此会话的唯一标识符。
     *
     * @return 会话 ID
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取会话存储大小。
     *
     * @return 以字节为单位的大小
     */
    public long getSize() {
        return size;
    }

    /**
     * 获取最后修改时间戳。
     *
     * @return 自纪元起的毫秒时间戳
     */
    public long getLastModified() {
        return lastModified;
    }

    /**
     * 获取此会话存储的状态组件数量。
     *
     * @return 组件数量
     */
    public int getComponentCount() {
        return componentCount;
    }

    /**
     * 返回会话信息的字符串表示。
     *
     * @return 包含会话 ID、大小、最后修改时间和组件数量的格式化字符串
     */
    @Override
    public String toString() {
        return String.format(
                "SessionInfo{id='%s', size=%d, lastModified=%d, components=%d}",
                sessionId, size, lastModified, componentCount);
    }
}
