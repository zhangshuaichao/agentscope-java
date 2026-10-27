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

/**
 * 将宿主文件系统中的单个文件复制到沙箱工作区的布局条目。
 *
 * <p>{@code sourcePath} 是宿主机上的绝对路径，以字符串保存，确保跨平台 JSON 序列化安全。
 */
public class LocalFileEntry extends WorkspaceEntry {

    private String sourcePath;

    /**
 * 创建空本地文件条目。
 */
    public LocalFileEntry() {}

    /**
     * 使用给定宿主源路径创建本地文件条目。
     *
     * @param sourcePath 宿主文件系统中的绝对路径
     */
    public LocalFileEntry(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    /**
     * 返回宿主侧源路径。
     *
     * @return 字符串形式的宿主绝对路径
     */
    public String getSourcePath() {
        return sourcePath;
    }

    /**
     * 设置宿主侧源路径。
     *
     * @param sourcePath 字符串形式的宿主绝对路径
     */
    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }
}
