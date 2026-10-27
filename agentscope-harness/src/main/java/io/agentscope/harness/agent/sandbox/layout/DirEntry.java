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
package io.agentscope.harness.agent.sandbox.layout;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 创建目录的布局条目，可包含嵌套子条目。
 *
 * <p>子条目是文件名到 {@link WorkspaceEntry} 的映射，本身也可为嵌套的 {@code DirEntry} 实例，支持递归树状工作区结构。
 */
public class DirEntry extends WorkspaceEntry {

    private Map<String, WorkspaceEntry> children = new LinkedHashMap<>();

    /**
 * 创建空目录条目。
 */
    public DirEntry() {}

    /**
     * 使用给定子条目创建目录条目。
     *
     * @param children 子名称到子条目的映射
     */
    public DirEntry(Map<String, WorkspaceEntry> children) {
        this.children = new LinkedHashMap<>(children);
    }

    /**
     * 返回此目录中的子条目。
     *
     * @return 子名称到子条目的可变映射
     */
    public Map<String, WorkspaceEntry> getChildren() {
        return children;
    }

    /**
     * 设置此目录的子条目。
     *
     * @param children 子名称到子条目的映射
     */
    public void setChildren(Map<String, WorkspaceEntry> children) {
        this.children = children != null ? children : new LinkedHashMap<>();
    }

    /**
     * 向此目录添加子条目。
     *
     * @param name 子文件名
     * @param entry 子条目
     * @return 当前实例，支持链式调用
     */
    public DirEntry child(String name, WorkspaceEntry entry) {
        this.children.put(name, entry);
        return this;
    }
}
