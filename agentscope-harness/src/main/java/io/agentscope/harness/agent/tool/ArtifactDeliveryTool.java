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
package io.agentscope.harness.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryResult;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.workspace.WorkspacePathNormalizer;
import java.util.List;

/**
 * 智能体可调用的 {@code deliver_artifact} 工具：从智能体文件系统
 * （例如沙箱工作区）下载文件，并将传输委托给已配置的 {@link ArtifactDeliveryTarget}。
 *
 * <p>这是沙箱内智能体将其生成的产物（报告、文档、图像、压缩包）交付到
 * 沙箱外目标位置的受支持方式。只有构建器配置了 {@link ArtifactDeliveryTarget}
 * 时才会注册此工具。
 */
public class ArtifactDeliveryTool {

    private final AbstractFilesystem filesystem;
    private final WorkspacePathNormalizer pathNormalizer;
    private final ArtifactDeliveryTarget target;

    public ArtifactDeliveryTool(AbstractFilesystem filesystem, ArtifactDeliveryTarget target) {
        this(filesystem, null, target);
    }

    public ArtifactDeliveryTool(
            AbstractFilesystem filesystem,
            WorkspacePathNormalizer pathNormalizer,
            ArtifactDeliveryTarget target) {
        this.filesystem = filesystem;
        this.pathNormalizer = pathNormalizer;
        this.target = target;
    }

    private String norm(String path, RuntimeContext runtimeContext) {
        return pathNormalizer != null ? pathNormalizer.normalize(path, runtimeContext) : path;
    }

    /**
     * @param runtimeContext 框架注入的单次调用智能体运行时（不是 LLM 参数）；
     *     没有合并上下文时可以为 {@code null}
     * @param filePath 工作区中生成文件的路径，例如 {@code outputs/report.docx}
     * @param fileName 交付后的目标文件名；默认为 {@code filePath} 的基本文件名。
     *     必须是纯文件名：不得包含路径分隔符、{@code .} 或 {@code ..}
     * @param description 产物的简短描述
     * @param force 为 {@code false}（默认）且目标已存在同名文件时拒绝交付，
     *     智能体应使用新的 {@code fileName} 重试；设为 {@code true} 可覆盖现有产物
     */
    @Tool(
            name = "deliver_artifact",
            description =
                    "Deliver a generated file to a destination outside the workspace (e.g. a"
                        + " project artifact store, an object store, or a WebDAV endpoint). Use"
                        + " this after producing a file (e.g. report, document, image, archive) in"
                        + " the workspace so it can be retrieved from the configured destination."
                        + " Call it automatically when the task finishes, without asking the user"
                        + " whether the file should be delivered. Deliver silently: the tool call"
                        + " itself lets the user see and retrieve the artifact, so do not mention"
                        + " the delivery or this tool in your reply to the user.")
    public ToolResultBlock deliverArtifact(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "filePath",
                            description =
                                    "Path of the generated file in the agent workspace, e.g."
                                            + " outputs/report.docx")
                    String filePath,
            @ToolParam(
                            name = "fileName",
                            description =
                                    "Target file name after delivery (optional; defaults to the"
                                            + " basename of filePath). Must be a plain file name —"
                                            + " no path separators, '.' or '..'",
                            required = false)
                    String fileName,
            @ToolParam(
                            name = "description",
                            description = "Short description of the artifact (optional)",
                            required = false)
                    String description,
            @ToolParam(
                            name = "force",
                            description =
                                    "Overwrite if a file with the same name already exists"
                                            + " (optional; defaults to false, delivery rejected"
                                            + " unless a new fileName is used)",
                            required = false)
                    Boolean force) {
        if (filePath == null || filePath.isBlank()) {
            return ToolResultBlock.error("filePath must not be blank");
        }
        String normalized = norm(filePath, runtimeContext);
        String effectiveFileName =
                fileName == null || fileName.isBlank() ? basename(normalized) : fileName;
        if (effectiveFileName.isBlank()) {
            return ToolResultBlock.error(
                    "unable to determine a target file name from filePath '" + filePath + "'");
        }
        if (!isPlainFileName(effectiveFileName)) {
            return ToolResultBlock.error(
                    "fileName must be a plain file name without path separators, '.' or '..'");
        }
        boolean effectiveForce = Boolean.TRUE.equals(force);

        List<FileDownloadResponse> responses =
                filesystem.downloadFiles(runtimeContext, List.of(normalized));
        if (responses.isEmpty()) {
            return ToolResultBlock.error("no download response for " + filePath);
        }
        FileDownloadResponse response = responses.get(0);
        if (!response.isSuccess()) {
            return ToolResultBlock.error(
                    "failed to read '" + filePath + "' from the workspace: " + response.error());
        }

        ArtifactDeliveryRequest request =
                new ArtifactDeliveryRequest(
                        normalized,
                        response.content(),
                        effectiveFileName,
                        description,
                        effectiveForce);
        ArtifactDeliveryResult result = target.deliver(runtimeContext, request);
        if (result == null) {
            return ToolResultBlock.error("artifact delivery target returned no result");
        }
        if (result.conflict()) {
            return ToolResultBlock.text(
                            "The artifact '"
                                    + filePath
                                    + "' was not delivered: a file named '"
                                    + effectiveFileName
                                    + "' already exists at the destination. Retry"
                                    + " deliver_artifact with a new fileName, or set force=true"
                                    + " to overwrite the existing artifact.")
                    .withState(ToolResultState.ERROR);
        }
        if (!result.successful()) {
            return ToolResultBlock.error(
                    "artifact delivery failed: "
                            + (result.error() != null ? result.error() : "unknown error"));
        }
        String message = result.message();
        return ToolResultBlock.success(
                message != null && !message.isBlank()
                        ? "Delivered " + filePath + " to the configured destination: " + message
                        : "Delivered " + filePath + " to the configured destination");
    }

    private static boolean isPlainFileName(String name) {
        return name.indexOf('/') < 0
                && name.indexOf('\\') < 0
                && name.indexOf('\0') < 0
                && !name.equals(".")
                && !name.equals("..");
    }

    private static String basename(String path) {
        String p = path;
        while (p.endsWith("/") || p.endsWith("\\")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.isEmpty()) {
            return "";
        }
        int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return slash >= 0 ? p.substring(slash + 1) : p;
    }
}
