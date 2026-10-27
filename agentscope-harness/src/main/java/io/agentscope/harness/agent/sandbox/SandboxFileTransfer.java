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
package io.agentscope.harness.agent.sandbox;

/**
 * 支持原生（非 exec）文件传输的 {@link Sandbox} 后端可选能力，例如沙箱运行时提供的专用 HTTP 上传／下载 API。
 *
 * <p>沙箱实现此接口时，{@link io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem} 优先使用它，而不是默认通过 {@link Sandbox#exec} 发送内嵌 base64 载荷的 Shell 命令。原生传输避免 base64 膨胀和每个命令行参数的长度限制（Linux 上每参数约 128 KiB），默认执行策略处理大文件时会触及该限制。
 *
 * <p>后端通常只支持沙箱文件系统某个子树的原生传输（例如运行时文件 API 基目录下的路径）。{@link #supportsFileTransfer} 让后端逐路径接受或拒绝；被拒绝的路径使用 exec 策略处理。
 */
public interface SandboxFileTransfer {

    /**
     * 此沙箱是否能原生传输给定路径。
     *
     * @param path 要探测的路径，通常为沙箱内绝对路径；实现也可接受自行解析的形式（如工作区相对路径）
     * @return {@link #uploadFile} / {@link #downloadFile} 接受此路径时为 true
     */
    boolean supportsFileTransfer(String path);

    /**
     * 在沙箱内写入文件，按需创建父目录。
     *
     * @param path 沙箱内目标路径，可以是绝对路径或实现能够解析的形式
     * @param content 文件字节
     * @throws Exception 传输失败时抛出；与拒绝路径不同，传输失败不会通过 exec 策略重试
     */
    void uploadFile(String path, byte[] content) throws Exception;

    /**
     * 从沙箱读取文件。
     *
     * @param path 沙箱内路径，可以是绝对路径或实现能够解析的形式
     * @return 文件字节
     * @throws Exception 传输失败时抛出；与拒绝路径不同，传输失败不会通过 exec 策略重试
     */
    byte[] downloadFile(String path) throws Exception;
}
