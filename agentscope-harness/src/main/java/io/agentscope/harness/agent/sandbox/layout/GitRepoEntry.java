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
 * 将 Git 仓库克隆到沙箱工作区的布局条目。
 *
 * <p>从 {@code url} 克隆仓库并检出给定 {@code ref}（分支、标签或提交 SHA）。此类提供 JSON 序列化所需的类型骨架。
 */
public class GitRepoEntry extends WorkspaceEntry {

    private String url;
    private String ref = "HEAD";

    /**
 * 创建空 Git 仓库条目。
 */
    public GitRepoEntry() {}

    /**
     * 使用给定 URL 和引用创建 Git 仓库条目。
     *
     * @param url 仓库克隆 URL
     * @param ref 要检出的分支、标签或提交 SHA
     */
    public GitRepoEntry(String url, String ref) {
        this.url = url;
        this.ref = ref;
    }

    /**
     * 返回仓库克隆 URL。
     *
     * @return 克隆 URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * 设置仓库克隆 URL。
     *
     * @param url 克隆 URL
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * 返回要检出的 Git 引用（分支、标签或提交 SHA）。
     *
     * @return Git 引用
     */
    public String getRef() {
        return ref;
    }

    /**
     * 设置 Git 引用。
     *
     * @param ref 分支、标签或提交 SHA
     */
    public void setRef(String ref) {
        this.ref = ref;
    }
}
