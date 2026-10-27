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
package io.agentscope.core.tool.mcp;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.util.JsonUtils;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 在 MCP 内容类型和 AgentScope 内容块之间转换的工具类，将协议数据结构转换为内部表示。
 *
 * <p>支持：
 * <ul>
 * <li>MCP TextContent → AgentScope TextBlock</li>
 * <li>MCP ImageContent → AgentScope ImageBlock</li>
 * <li>MCP AudioContent → AgentScope AudioBlock</li>
 * <li>MCP CallToolResult → AgentScope ToolResultBlock</li>
 * <li>MCP CallToolResult.structuredContent → AgentScope TextBlock（JSON）</li>
 * </ul>
 */
public class McpContentConverter {

    /**
     * 私有构造器，禁止实例化。
     */
    private McpContentConverter() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * 将 MCP CallToolResult 转换为 AgentScope ToolResultBlock。
     *
     * @param mcpResult MCP 工具调用结果
     * @return 转换后的 ToolResultBlock
     */
    public static ToolResultBlock convertCallToolResult(McpSchema.CallToolResult mcpResult) {
        if (mcpResult == null) {
            return ToolResultBlock.error("MCP tool returned null result");
        }

        // 检查结果是否为错误
        if (Boolean.TRUE.equals(mcpResult.isError())) {
            String errorMsg = extractErrorMessage(mcpResult.content());
            return ToolResultBlock.error(errorMsg);
        }

        // 转换成功结果：内容块以及可选结构化内容
        List<ContentBlock> contentBlocks = new ArrayList<>(convertContentList(mcpResult.content()));
        if (mcpResult.structuredContent() != null) {
            contentBlocks.add(
                    TextBlock.builder().text(toJson(mcpResult.structuredContent())).build());
        }

        return ToolResultBlock.of(contentBlocks);
    }

    /**
     * 将 MCP Content 列表转换为 AgentScope ContentBlock 列表。
     *
     * @param mcpContents MCP 内容列表
     * @return 转换后的内容块列表
     */
    public static List<ContentBlock> convertContentList(List<McpSchema.Content> mcpContents) {
        if (mcpContents == null || mcpContents.isEmpty()) {
            return List.of(TextBlock.builder().text("").build());
        }

        List<ContentBlock> blocks = new ArrayList<>();

        for (McpSchema.Content content : mcpContents) {
            ContentBlock block = convertContent(content);
            if (block != null) {
                blocks.add(block);
            }
        }

        return blocks.isEmpty() ? List.of(TextBlock.builder().text("").build()) : blocks;
    }

    /**
     * 将单个 MCP Content 转换为 AgentScope ContentBlock。
     *
     * @param content MCP 内容
     * @return 转换后的内容块；不支持时为 null
     */
    public static ContentBlock convertContent(McpSchema.Content content) {
        if (content == null) {
            return null;
        }

        if (content instanceof McpSchema.TextContent textContent) {
            return convertTextContent(textContent);
        } else if (content instanceof McpSchema.ImageContent imageContent) {
            return convertImageContent(imageContent);
        } else if (content instanceof McpSchema.AudioContent audioContent) {
            return convertAudioContent(audioContent);
        } else if (content instanceof McpSchema.EmbeddedResource embeddedResource) {
            return convertEmbeddedResource(embeddedResource);
        }

        return TextBlock.builder()
                .text("[Unsupported content type: " + content.getClass().getSimpleName() + "]")
                .build();
    }

    /**
     * 将 MCP TextContent 转换为 AgentScope TextBlock。
     *
     * @param textContent MCP 文本内容
     * @return 转换后的 TextBlock
     */
    private static TextBlock convertTextContent(McpSchema.TextContent textContent) {
        return TextBlock.builder()
                .text(textContent.text() != null ? textContent.text() : "")
                .build();
    }

    /**
     * 将 MCP ImageContent 转换为 AgentScope ImageBlock。
     *
     * @param imageContent MCP 图像内容
     * @return 转换后的 ImageBlock
     */
    private static ImageBlock convertImageContent(McpSchema.ImageContent imageContent) {
        String data = imageContent.data();
        String mimeType = imageContent.mimeType();

        if (data == null || data.isEmpty()) {
            return null;
        }

        if (data.startsWith("http://")
                || data.startsWith("https://")
                || data.startsWith("file://")) {
            return new ImageBlock(new URLSource(data, mimeType));
        }

        Base64Source source = new Base64Source(mimeType, data);
        return new ImageBlock(source);
    }

    /**
     * 将 MCP AudioContent 转换为 AgentScope AudioBlock。
     *
     * @param audioContent MCP 音频内容
     * @return 转换后的 AudioBlock
     */
    private static AudioBlock convertAudioContent(McpSchema.AudioContent audioContent) {
        String data = audioContent.data();
        String mimeType = audioContent.mimeType();

        if (data == null || data.isEmpty()) {
            return null;
        }

        if (data.startsWith("http://")
                || data.startsWith("https://")
                || data.startsWith("file://")) {
            return new AudioBlock(new URLSource(data, mimeType));
        }

        return new AudioBlock(new Base64Source(mimeType, data));
    }

    /**
     * 将 MCP structuredContent 序列化为 JSON 字符串，作为 TextBlock 纳入工具结果。
     */
    private static String toJson(Object structuredContent) {
        try {
            return JsonUtils.getJsonCodec().toJson(structuredContent);
        } catch (Exception e) {
            return String.valueOf(structuredContent);
        }
    }

    /**
     * 将 MCP EmbeddedResource 转换为 AgentScope ContentBlock。
     *
     * @param embeddedResource MCP 嵌入资源
     * @return 转换后的 ContentBlock
     */
    private static ContentBlock convertEmbeddedResource(
            McpSchema.EmbeddedResource embeddedResource) {
        McpSchema.ResourceContents resource = embeddedResource.resource();

        if (resource instanceof McpSchema.TextResourceContents textResource) {
            return TextBlock.builder().text(textResource.text()).build();
        } else if (resource instanceof McpSchema.BlobResourceContents blobResource) {
            // MIME 类型表示图像时，将二进制数据转换为图像
            String mimeType = blobResource.mimeType();
            if (mimeType != null && mimeType.startsWith("image/")) {
                Base64Source source = new Base64Source(mimeType, blobResource.blob());
                return new ImageBlock(source);
            } else {
                return TextBlock.builder()
                        .text(
                                "[Binary resource: "
                                        + mimeType
                                        + ", URI: "
                                        + blobResource.uri()
                                        + "]")
                        .build();
            }
        }

        return TextBlock.builder().text("[Unknown resource type]").build();
    }

    /**
     * 从 MCP 内容列表提取错误消息。
     *
     * @param contents MCP 内容列表
     * @return 提取的错误消息
     */
    private static String extractErrorMessage(List<McpSchema.Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return "Unknown error";
        }

        return contents.stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"));
    }
}
