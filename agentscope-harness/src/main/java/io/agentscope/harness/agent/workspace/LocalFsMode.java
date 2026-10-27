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
package io.agentscope.harness.agent.workspace;

/**
 * 以主机目录为根的文件系统（{@link io.agentscope.harness.agent.filesystem.local.LocalFilesystem}
 * 及相关实现）的路径解析策略模式。
 *
 * <p>控制智能体提供绝对路径时的处理方式：
 * <ul>
 * <li>{@link #SANDBOXED}：将所有路径锚定到文件系统根目录，拒绝 {@code ..} 以及离开根目录的绝对路径。
 * 等价于旧版 {@code virtualMode=true}。</li>
 * <li>{@link #ROOTED}：仅当绝对路径位于已配置 {@link PathPolicy} 的某个根目录
 * （项目、工作区及附加根目录）下时才接受。相对路径仍按文件系统根目录解析。
 * 这是本地模式智能体的默认值，与 Claude Code 风格的“项目及附加目录”允许列表一致。</li>
 * <li>{@link #UNRESTRICTED}：绝对路径原样传递，等价于旧版 {@code virtualMode=false}。
 * 为需要读取任意主机路径的工具或测试提供显式放开限制的选项。</li>
 * </ul>
 */
public enum LocalFsMode {
    SANDBOXED,
    ROOTED,
    UNRESTRICTED
}
