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
 * 创建带内联文本内容文件的布局条目。
 */
public class FileEntry extends WorkspaceEntry {

    private String content = "";
    private String encoding = "UTF-8";

    /**
 * 创建空文件条目。
 */
    public FileEntry() {}

    /**
     * 使用给定内容创建文件条目。
     *
     * @param content 字符串形式的文件内容
     */
    public FileEntry(String content) {
        this.content = content;
    }

    /**
     * 使用给定内容和编码创建文件条目。
     *
     * @param content 字符串形式的文件内容
     * @param encoding 写入文件所用字符编码
     */
    public FileEntry(String content, String encoding) {
        this.content = content;
        this.encoding = encoding;
    }

    /**
     * 返回文件内容。
     *
     * @return 文件内容字符串
     */
    public String getContent() {
        return content;
    }

    /**
     * 设置文件内容。
     *
     * @param content 文件内容字符串
     */
    public void setContent(String content) {
        this.content = content;
    }

    /**
     * 返回写入此文件使用的字符编码。
     *
     * @return 编码名称（例如 "UTF-8"）
     */
    public String getEncoding() {
        return encoding;
    }

    /**
     * 设置写入此文件使用的字符编码。
     *
     * @param encoding 编码名称（例如 "UTF-8"）
     */
    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }
}
