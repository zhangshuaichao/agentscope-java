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
package io.agentscope.extensions.channel.common;

import java.util.Map;
import java.util.Objects;

/**
 * 渠道入站映射器暴露的图片、音频、视频与文件消息的元数据键契约。
 * <p>适配器刻意不将提供商媒体解码为内容块：{@code media_id}、{@code image_key}、{@code file_key}、{@code downloadCode}
 * 等素材标识符不是 URL，通常需带令牌下载才能取得短期有效的素材。是否获取、何时获取、如何缓冲与降级由应用决定。
 * 映射器将媒体事件转为用户 {@code Msg}，文本是中性 {@link #markerText(String, String) marker}，
 * 元数据带此处定义的键，供应用观察、下载或回复指导。
 * <p>值均为字符串；平台没有的字段省略，读取方应按稀疏映射处理，不假定键必然存在。
 */
public final class ChannelMediaMetadata {

    /**
     * {@link #markerText(String, String)} 内嵌文件名副本的最大 Unicode 码点数。
     */
    private static final int MARKER_FILE_NAME_MAX_LENGTH = 80;

    /**
     * 规范化媒体类型：图片。
     */
    public static final String KIND_IMAGE = "image";

    /**
     * 规范化媒体类型：音频或语音消息。
     */
    public static final String KIND_AUDIO = "audio";

    /**
     * 规范化媒体类型：视频，包括带视频的复合消息。
     */
    public static final String KIND_VIDEO = "video";

    /**
     * 规范化媒体类型：普通文件附件。
     */
    public static final String KIND_FILE = "file";

    /**
     * 规范化类型，为 {@code image}、{@code audio}、{@code video}、{@code file} 之一。
     */
    public static final String KIND = "channelMediaKind";

    /**
     * 保留提供商原始类型值：企业微信 {@code MsgType}、飞书 {@code message_type}、钉钉 {@code msgtype}，
     * 例如 {@code voice}、{@code picture}、{@code media}。
     */
    public static final String PROVIDER_TYPE = "channelMediaProviderType";

    /**
     * 保留主要素材原始标识符：{@code MediaId}、{@code image_key}、{@code file_key} 或 {@code downloadCode}。
     */
    public static final String ID = "channelMediaId";

    /**
     * 平台提供的次要素材标识符，例如缩略图、封面或图片专用下载码。
     */
    public static final String SECONDARY_ID = "channelMediaSecondaryId";

    /**
     * 平台同时提供的可直接获取 URL，例如企业微信图片的 {@code PicUrl}。
     */
    public static final String URL = "channelMediaUrl";

    /**
     * 平台提供的文件名。
     */
    public static final String FILE_NAME = "channelMediaFileName";

    /**
     * 容器或格式标记，例如企业微信语音 {@code Format}、钉钉视频 {@code videoType}。
     */
    public static final String FORMAT = "channelMediaFormat";

    /**
     * 平台报告的播放时长，以毫秒表示；此映射契约按飞书与钉钉音视频字段的毫秒语义处理。
     */
    public static final String DURATION_MS = "channelMediaDurationMs";

    /**
     * 平台提供的音频转文字结果。
     */
    public static final String RECOGNITION = "channelMediaRecognition";

    private ChannelMediaMetadata() {}

    /**
     * 返回媒体消息 {@code Msg} 使用的中性标记：{@code "[image]"}、{@code "[audio]"}、{@code "[video]"}，
     * 有文件名时可为 {@code "[file: report.pdf]"}。让纯文本智能体仍能看见事件，应用可在分发前改写文本。
     * <p>文件名由提供商控制，内嵌副本会清理括号、控制与不可见格式字符，折叠空白并截断，
     * 降低恶意名字破坏标记显示的风险；原始名称仍原样保留在 {@link #FILE_NAME}，不应当作可信指令。
     * @param kind 规范化媒体类型，参见 {@link #KIND}
     * @param fileName 可选文件名，非空白时嵌入清理后的副本
     * @throws NullPointerException {@code kind} 为 {@code null} 时抛出
     */
    public static String markerText(String kind, String fileName) {
        Objects.requireNonNull(kind, "kind cannot be null");
        if (fileName == null || fileName.isBlank()) {
            return "[" + kind + "]";
        }
        String safe = sanitizeMarkerFileName(fileName);
        if (safe.isEmpty()) {
            return "[" + kind + "]";
        }
        return "[" + kind + ": " + safe + "]";
    }

    /**
     * 生成适合标记显示的文件名副本：将括号、控制字符、不可见格式字符（零宽空格、双向覆盖）
     * 及 Unicode 行／段落分隔符替换为空格，避免 {@code ]} 提前结束标记、换行插入新行或不可见字符混淆显示。
     * 连续空白折叠成一个空格，最多保留 {@code MARKER_FILE_NAME_MAX_LENGTH} 个码点，不切断代理对。
     * 仅修改此副本，原始名称仍经 {@link #FILE_NAME} 单独传递。
     */
    private static String sanitizeMarkerFileName(String fileName) {
        String safe =
                fileName.replaceAll("[\\[\\]\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", " ")
                        .replaceAll("\\s+", " ")
                        .strip();
        if (safe.codePointCount(0, safe.length()) <= MARKER_FILE_NAME_MAX_LENGTH) {
            return safe;
        }
        int limit = safe.offsetByCodePoints(0, MARKER_FILE_NAME_MAX_LENGTH);
        return safe.substring(0, limit).strip();
    }

    /**
     * 仅当 {@code value} 既非 {@code null} 也非空白时写入 {@code key}，{@code value} 为空则静默跳过。
     * 平台可选字段被省略，保持元数据稀疏。
     * @throws NullPointerException {@code metadata} 或 {@code key} 为 {@code null} 时抛出
     */
    public static void putIfPresent(Map<String, Object> metadata, String key, String value) {
        Objects.requireNonNull(metadata, "metadata cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }
}
