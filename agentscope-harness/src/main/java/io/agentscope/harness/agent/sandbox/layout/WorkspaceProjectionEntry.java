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

import java.util.ArrayList;
import java.util.List;

/**
 * 在 {@link io.agentscope.harness.agent.sandbox.Sandbox} 启动时，将宿主工作区选定的文件／目录投影到沙箱工作区的布局条目。
 *
 * <p>此条目为沙箱专用，{@link io.agentscope.harness.agent.sandbox.WorkspaceSpecApplier} 的普通文件物化逻辑会忽略它；投影改由 {@link io.agentscope.harness.agent.sandbox.Sandbox#start()} 通过归档恢复应用。
 */
public class WorkspaceProjectionEntry extends WorkspaceEntry {

    private String sourceRoot;
    private List<String> includeRoots = new ArrayList<>();

    /**
     * 作为投影来源的宿主侧工作区绝对根路径。
     */
    public String getSourceRoot() {
        return sourceRoot;
    }

    public void setSourceRoot(String sourceRoot) {
        this.sourceRoot = sourceRoot;
    }

    /**
     * {@link #getSourceRoot()} 下的相对包含根路径。
     */
    public List<String> getIncludeRoots() {
        return includeRoots;
    }

    public void setIncludeRoots(List<String> includeRoots) {
        this.includeRoots =
                includeRoots != null ? new ArrayList<>(includeRoots) : new ArrayList<>();
    }
}
