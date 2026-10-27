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
package io.agentscope.core.formatter;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tracing.TracerRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 为各格式化器实现提供公共功能的抽象基类。
 *
 * <p>公共逻辑包括：
 * <ul>
 *   <li>文本内容提取（过滤 ThinkingBlock）
 *   <li>媒体内容检测
 *   <li>角色标签格式化
 *   <li>共享 ObjectMapper 实例
 * </ul>
 *
 * @param <TReq> 服务商专用请求消息类型
 * @param <TResp> 服务商专用响应类型
 * @param <TParams> 服务商专用请求参数构建器类型
 */
public abstract class AbstractBaseFormatter<TReq, TResp, TParams>
        implements Formatter<TReq, TResp, TParams> {

    private static final Logger log = LoggerFactory.getLogger(AbstractBaseFormatter.class);

    /**
     * 将 AgentScope 消息转换为服务商专用请求格式。
     *
     * @param msgs AgentScope 消息列表
     * @return 服务商专用请求消息列表
     */
    @Override
    public List<TReq> format(List<Msg> msgs) {
        return TracerRegistry.get().callFormat(this, msgs, () -> doFormat(msgs));
    }

    /**
     * 使用请求级生成选项格式化 AgentScope 消息。
     *
     * @param msgs AgentScope 消息列表
     * @param options 请求级生成选项；可以为 {@code null}
     * @return 服务商专用请求消息列表
     */
    @Override
    public List<TReq> format(List<Msg> msgs, GenerateOptions options) {
        return TracerRegistry.get().callFormat(this, msgs, () -> doFormat(msgs, options));
    }

    protected abstract List<TReq> doFormat(List<Msg> msgs);

    /**
     * 使用请求级生成选项格式化 AgentScope 消息。
     *
     * <p>对于消息转换不需要生成选项的服务商，默认实现委派给 {@link #doFormat(List)}。
     *
     * @param msgs AgentScope 消息列表
     * @param options 请求级生成选项；可以为 {@code null}
     * @return 服务商专用请求消息列表
     */
    protected List<TReq> doFormat(List<Msg> msgs, GenerateOptions options) {
        return doFormat(msgs);
    }

    /**
     * 从消息提取文本内容，并过滤 ThinkingBlock。
     *
     * @param msg 要提取文本的消息
     * @return 拼接后的文本内容（以换行分隔）
     */
    // 【学习提示】这是有损的“提取文本”路径，不是整个 Msg 的序列化。
    // 未匹配的内容块会被忽略；多模态能力应由具体格式化器的对应路径处理。
    protected String extractTextContent(Msg msg) {
        return msg.getContent().stream()
                .flatMap(
                        block -> {
                            if (block instanceof TextBlock tb) {
                                return Stream.of(tb.getText());
                            } else if (block instanceof HintBlock hb) {
                                return Stream.of(hb.getHint());
                            } else if (block instanceof ThinkingBlock) {
                                // 重要：ThinkingBlock 不会在此路径中发送回 LLM API
                                // ThinkingBlock 会保存在内存中，但在格式化时跳过
                                // 消息中的此类内容块
                                log.debug(
                                        "Skipping ThinkingBlock when formatting message for LLM"
                                                + " API");
                                return Stream.empty();
                            } else if (block instanceof ToolResultBlock toolResult) {
                                // 从工具结果输出中提取文本
                                return toolResult.getOutput().stream()
                                        .filter(output -> output instanceof TextBlock)
                                        .map(output -> ((TextBlock) output).getText());
                            }
                            return Stream.empty();
                        })
                .collect(Collectors.joining("\n"));
    }

    /**
     * 从单个 ContentBlock 提取文本内容。
     *
     * @param block 内容块
     * @return 文本内容或空字符串
     */
    protected String extractTextContent(ContentBlock block) {
        if (block instanceof TextBlock tb) {
            return tb.getText();
        } else if (block instanceof HintBlock hb) {
            return hb.getHint();
        }
        return "";
    }

    /**
     * 检查消息是否含多模态内容（图像、音频、视频）。
     *
     * @param msg 要检查的消息
     * @return 消息包含媒体内容时为 true
     */
    protected boolean hasMediaContent(Msg msg) {
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof ImageBlock
                    || block instanceof AudioBlock
                    || block instanceof VideoBlock
                    || block instanceof DataBlock) {
                return true;
            }
        }
        return false;
    }

    /**
     * 为对话历史格式化角色标签。
     *
     * @param role 消息角色
     * @return 格式化后的角色标签（例如 "User"、"Assistant"）
     */
    protected String formatRoleLabel(MsgRole role) {
        return switch (role) {
            case USER -> "User";
            case ASSISTANT -> "Assistant";
            case SYSTEM -> "System";
            case TOOL -> "Tool";
        };
    }

    /**
     * 检查消息是否应跳过多智能体格式化器的历史合并。
     * 设置了 {@link MessageMetadataKeys#BYPASS_MULTIAGENT_HISTORY_MERGE} 标记且值为 {@code true}
     * 的消息应独立保留，而不是合并进对话历史。
     *
     * @param msg 要检查的消息
     * @return 消息应跳过历史合并时为 true
     */
    protected boolean shouldBypassHistory(Msg msg) {
        if (msg.getMetadata() == null) {
            return false;
        }
        Object bypassFlag =
                msg.getMetadata().get(MessageMetadataKeys.BYPASS_MULTIAGENT_HISTORY_MERGE);
        return Boolean.TRUE.equals(bypassFlag);
    }

    /**
     * 从 options 获取选项值，必要时回退到 defaultOptions。
     *
     * @param <T> 选项值的类型
     * @param options 优先使用的选项对象
     * @param defaultOptions 回退选项对象
     * @param getter 从 GenerateOptions 提取值的函数
     * @return 选项值；两个对象中均未找到时为 null
     */
    protected <T> T getOptionOrDefault(
            GenerateOptions options,
            GenerateOptions defaultOptions,
            Function<GenerateOptions, T> getter) {
        T value = options != null ? getter.apply(options) : null;
        return value != null
                ? value
                : (defaultOptions != null ? getter.apply(defaultOptions) : null);
    }

    /**
     * 将工具结果输出转换为字符串表示。
     * 将图像／音频／视频转换为文本引用，以处理多模态内容。
     *
     * @param output 工具结果内容块（可以为 null 或空列表）
     * @return 字符串表示（单项直接返回，多项添加 "- " 前缀）
     */
    protected String convertToolResultToString(List<ContentBlock> output) {
        if (output == null || output.isEmpty()) {
            return "";
        }

        List<String> textualOutput = new ArrayList<>();

        for (ContentBlock block : output) {
            if (block instanceof TextBlock tb) {
                textualOutput.add(tb.getText());
            } else if (block instanceof ImageBlock ib) {
                String reference = convertMediaBlockToTextReference(ib, "image");
                textualOutput.add(reference);
            } else if (block instanceof AudioBlock ab) {
                String reference = convertMediaBlockToTextReference(ab, "audio");
                textualOutput.add(reference);
            } else if (block instanceof VideoBlock vb) {
                String reference = convertMediaBlockToTextReference(vb, "video");
                textualOutput.add(reference);
            } else if (block instanceof DataBlock db) {
                String reference = convertMediaBlockToTextReference(db, "data");
                textualOutput.add(reference);
            }
            // 忽略其他内容块类型（例如 ThinkingBlock）
        }

        // 行为：单项直接返回，多项使用 "- " 前缀
        if (textualOutput.size() == 1) {
            return textualOutput.get(0);
        } else {
            return textualOutput.stream().map(s -> "- " + s).collect(Collectors.joining("\n"));
        }
    }

    /**
     * 将媒体内容块（图像／音频／视频）转换为文本引用。
     *
     * @param block 媒体内容块（ImageBlock、AudioBlock 或 VideoBlock）
     * @param mediaType 媒体类型字符串（"image"、"audio" 或 "video"）
     * @return 媒体的文本引用（URL 或临时文件路径）
     */
    private String convertMediaBlockToTextReference(ContentBlock block, String mediaType) {
        Source source = getSourceFromBlock(block);

        if (source instanceof URLSource urlSource) {
            return String.format(
                    "The returned %s can be found at: %s", mediaType, urlSource.getUrl());
        } else if (source instanceof Base64Source base64Source) {
            // 保存到临时文件并返回路径
            try {
                String filePath =
                        saveBase64DataToTempFile(
                                base64Source.getMediaType(), base64Source.getData());
                return String.format("The returned %s can be found at: %s", mediaType, filePath);
            } catch (IOException e) {
                log.error("Failed to save base64 data to temp file", e);
                return String.format("[%s - failed to save file: %s]", mediaType, e.getMessage());
            }
        }

        return String.format("[%s - unsupported source type]", mediaType);
    }

    /**
     * 从媒体内容块提取来源。
     *
     * @param block 媒体内容块
     * @return 来源对象
     */
    private Source getSourceFromBlock(ContentBlock block) {
        if (block instanceof ImageBlock ib) {
            return ib.getSource();
        } else if (block instanceof AudioBlock ab) {
            return ab.getSource();
        } else if (block instanceof VideoBlock vb) {
            return vb.getSource();
        } else if (block instanceof DataBlock db) {
            return db.getSource();
        }
        throw new IllegalArgumentException("Unsupported block type: " + block.getClass());
    }

    /**
     * 将 Base64 数据保存到临时文件。
     *
     * @param mediaType MIME 类型（例如 "image/png"、"audio/wav"）
     * @param base64Data Base64 编码的数据（不含前缀）
     * @return 临时文件的绝对路径
     * @throws IOException 文件创建或写入失败时抛出
     */
    protected String saveBase64DataToTempFile(String mediaType, String base64Data)
            throws IOException {
        // 从 MIME 类型提取扩展名（例如 "image/png" → ".png"）
        String extension = "." + (mediaType.contains("/") ? mediaType.split("/")[1] : mediaType);

        // 创建带扩展名的临时文件
        Path tempFile = Files.createTempFile("agentscope_", extension);

        // 解码 Base64 数据
        byte[] decodedData = Base64.getDecoder().decode(base64Data);

        // 写入文件
        Files.write(tempFile, decodedData);

        log.debug("Saved base64 data to temp file: {}", tempFile);

        // 返回绝对路径
        return tempFile.toAbsolutePath().toString();
    }
}
