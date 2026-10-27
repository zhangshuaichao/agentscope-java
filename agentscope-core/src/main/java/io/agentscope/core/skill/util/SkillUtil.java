/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.skill.util;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.MarkdownSkillParser.ParsedMarkdown;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 创建 AgentSkill 实例的工具类。
 *
 * <p>提供从不同来源创建 AgentSkill 的工厂方法，尤其支持带 YAML 前置信息的 Markdown。
 *
 * <p><b>使用示例：</b>
 *
 * <pre>{@code
 * // Create skill from markdown with default source
 * String skillMd = "---\nname: my-skill\ndescription: Does something\n---\nContent here";
 * Map<String, String> resources = Map.of("file1.txt", "content1");
 * AgentSkill skill = SkillUtil.createFrom(skillMd, resources);
 *
 * // Create skill from markdown with custom source
 * AgentSkill skill2 = SkillUtil.createFrom(skillMd, resources, "github");
 * }</pre>
 */
public class SkillUtil {

    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String DEFAULT_SOURCE = "custom";

    /**
     * 技能名称的最大长度，遵循 Agent Skills 生态标准。
     */
    public static final int MAX_SKILL_NAME_LENGTH = 64;

    /**
     * 技能名称必须匹配的规则：以小写字母或数字开头，后续仅含小写字母、数字和连字符。下划线会与 {@code name_source} 技能 ID 分隔符冲突，因此不允许使用。
     */
    public static final Pattern VALID_SKILL_NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    /**
     * 私有构造方法，防止实例化。
     */
    private SkillUtil() {}

    /**
     * 检查技能名称是否非 null、匹配 {@link #VALID_SKILL_NAME_PATTERN}，且长度不超过 {@link #MAX_SKILL_NAME_LENGTH} 个字符。
     *
     * @param name 要校验的技能名称（可以为 {@code null}）
     *
     * @return 名称合法时为 {@code true}，否则为 {@code false}
     */
    public static boolean isValidSkillName(String name) {
        return name != null
                && name.length() <= MAX_SKILL_NAME_LENGTH
                && VALID_SKILL_NAME_PATTERN.matcher(name).matches();
    }

    /**
     * 技能名称无效时立即失败。所有写入边界共享此方法，以保持校验规则和拒绝消息一致。
     *
     * @param name 要校验的技能名称
     *
     * @throws IllegalArgumentException 名称为 null、不匹配 {@link #VALID_SKILL_NAME_PATTERN} 或超过 {@link
     * #MAX_SKILL_NAME_LENGTH} 时抛出
     */
    public static void requireValidSkillName(String name) {
        if (!isValidSkillName(name)) {
            throw new IllegalArgumentException(
                    "Invalid skill name '"
                            + name
                            + "': names must match "
                            + VALID_SKILL_NAME_PATTERN.pattern()
                            + " and be at most "
                            + MAX_SKILL_NAME_LENGTH
                            + " characters");
        }
    }

    /**
     * 在 {@code baseDir} 下解析 {@code relativeSegment}，仅当规范化结果仍位于 {@code baseDir} 内时返回；空结果表示“拒绝写入”。
     *
     * <p>必须先规范化再检查包含关系：绝对路径片段会使 {@code Path.resolve} 完全替换基础路径，而 {@code ..}
     * 也可能使路径逃逸。技能写入路径（包括本地镜像写入器）必须复用此方法，而不是自行实现检查。
     *
     * @param baseDir 解析后的路径必须位于其中的目录
     *
     * @param relativeSegment 尚未解析的路径片段（技能 ID 或资源相对路径）
     *
     * @return 规范化后位于目录内的路径；逃逸出 {@code baseDir} 时返回 {@code Optional.empty()}
     */
    public static Optional<Path> resolveContainedPath(Path baseDir, String relativeSegment) {
        Path resolved = baseDir.resolve(relativeSegment).normalize();
        return resolved.startsWith(baseDir) ? Optional.of(resolved) : Optional.empty();
    }

    /**
     * 根据带 YAML 前置信息的 Markdown 创建 AgentSkill。
     *
     * <p>解析 Markdown，提取 YAML 前置信息中的元数据（name、description）和正文。默认来源为 "custom"。
     *
     * @param skillMd 带 YAML 前置信息的 Markdown，元数据包含 name 和 description
     *
     * @param resources 技能引用的支持资源（可以为 null）
     *
     * @return 创建出的 AgentSkill 实例
     *
     * @throws IllegalArgumentException name 或 description 缺失，或正文为空时抛出
     */
    public static AgentSkill createFrom(String skillMd, Map<String, String> resources) {
        return createFrom(skillMd, resources, DEFAULT_SOURCE);
    }

    /**
     * 根据带 YAML 前置信息的 Markdown 和自定义来源创建 AgentSkill。
     *
     * <p>解析 Markdown，提取 YAML 前置信息中的元数据（name、description）和正文。source 参数表示技能来源。
     *
     * @param skillMd 带 YAML 前置信息的 Markdown，元数据包含 name 和 description
     *
     * @param resources 技能引用的支持资源（可以为 null）
     *
     * @param source 技能来源标识符（null 默认使用 "custom"）
     *
     * @return 创建出的 AgentSkill 实例
     *
     * @throws IllegalArgumentException name 或 description 缺失，或正文为空时抛出
     */
    public static AgentSkill createFrom(
            String skillMd, Map<String, String> resources, String source) {
        ParsedMarkdown parsed = MarkdownSkillParser.parse(skillMd);
        Map<String, Object> metadata = parsed.getMetadata();

        String name = stringifyRequiredMetadata(metadata, "name");
        String description = stringifyRequiredMetadata(metadata, "description");
        metadata = new LinkedHashMap<>(metadata);
        metadata.put("name", name);
        metadata.put("description", description);
        String skillContent = parsed.getContent();

        if (name == null || name.isEmpty() || description == null || description.isEmpty()) {
            throw new IllegalArgumentException(
                    "The SKILL.md must have a YAML Front Matter including `name` and"
                            + " `description` fields.");
        }
        if (skillContent == null || skillContent.isEmpty()) {
            throw new IllegalArgumentException(
                    "The SKILL.md must have content except for the YAML Front Matter.");
        }

        return new AgentSkill(metadata, skillContent, resources, source);
    }

    /**
     * 从技能 ZIP 包创建 AgentSkill。
     *
     * <p>包内必须有 {@value #SKILL_FILE_NAME} 条目，并可包含可选资源文件。.skill 与 .zip 扩展名对应同一种包格式。
     *
     * @param zipBytes ZIP 内容的字节数组
     *
     * @return 创建出的 AgentSkill 实例
     *
     * @throws IllegalArgumentException zipBytes 为 null 或空、未找到 SKILL.md 或 ZIP 条目无效时抛出
     */
    public static AgentSkill createFromZip(byte[] zipBytes) {
        return createFromZip(zipBytes, DEFAULT_SOURCE, StandardCharsets.UTF_8);
    }

    /**
     * 根据技能 ZIP 包文件路径创建 AgentSkill。
     *
     * <p>包内必须有 {@value #SKILL_FILE_NAME} 条目，并可包含可选资源文件。.skill 与 .zip 扩展名对应同一种包格式。
     *
     * @param zipPath ZIP 文件路径
     *
     * @return 创建出的 AgentSkill 实例
     *
     * @throws IllegalArgumentException zipPath 为 null 时抛出
     *
     * @throws RuntimeException 无法读取 ZIP 文件时抛出
     */
    public static AgentSkill createFromZip(Path zipPath) {
        return createFromZip(zipPath, DEFAULT_SOURCE, StandardCharsets.UTF_8);
    }

    /**
     * 根据技能 ZIP 包文件路径，使用指定字符集创建 AgentSkill。
     *
     * <p>包内必须有 {@value #SKILL_FILE_NAME} 条目，并可包含可选资源文件。.skill 与 .zip 扩展名对应同一种包格式。
     *
     * @param zipPath ZIP 文件路径
     *
     * @param charset 解码 ZIP 条目名称与文件内容的字符集（null 默认使用 UTF-8）。当 ZIP 使用 GBK/GB18030 等非 UTF-8 字符集创建，默认重载解码时报告
     * {@code malformed input} 等错误，可指定此参数
     *
     * @return 创建出的 AgentSkill 实例
     */
    public static AgentSkill createFromZip(Path zipPath, Charset charset) {
        return createFromZip(zipPath, DEFAULT_SOURCE, charset);
    }

    /**
     * 从技能 ZIP 包输入流创建 AgentSkill。
     *
     * <p>包内必须有 {@value #SKILL_FILE_NAME} 条目，并可包含可选资源文件。.skill 与 .zip 扩展名对应同一种包格式。
     *
     * @param zipStream ZIP 内容流
     *
     * @return 创建出的 AgentSkill 实例
     *
     * @throws IllegalArgumentException zipStream 为 null 时抛出
     */
    public static AgentSkill createFromZip(InputStream zipStream) {
        return createFromZip(zipStream, DEFAULT_SOURCE, StandardCharsets.UTF_8);
    }

    /**
     * 从技能 ZIP 包创建带自定义来源的 AgentSkill。
     *
     * <p>包内必须有 {@value #SKILL_FILE_NAME} 条目，并可包含可选资源文件。.skill 与 .zip 扩展名对应同一种包格式。
     *
     * @param zipBytes ZIP 内容的字节数组
     *
     * @param source 技能来源标识符（null 默认使用 "custom"）
     *
     * @return 创建出的 AgentSkill 实例
     *
     * @throws IllegalArgumentException zipBytes 为 null 或空、未找到 SKILL.md 或 ZIP 条目无效时抛出
     */
    public static AgentSkill createFromZip(byte[] zipBytes, String source) {
        return createFromZip(zipBytes, source, StandardCharsets.UTF_8);
    }

    /**
     * 从技能 ZIP 包创建 AgentSkill，并指定来源与字符集。
     *
     * <p>包内必须有 {@value #SKILL_FILE_NAME} 条目，并可包含可选资源文件。.skill 与 .zip 扩展名对应同一种包格式。
     *
     * @param zipBytes ZIP 内容的字节数组
     *
     * @param source 技能来源标识符（null 默认使用 "custom"）
     *
     * @param charset 解码 ZIP 条目名称与文件内容的字符集（null 默认使用 UTF-8）。当 ZIP 使用 GBK/GB18030 等非 UTF-8 字符集创建，默认重载解码时报告
     * {@code malformed input} 等错误，可指定此参数
     *
     * @return 创建出的 AgentSkill 实例
     *
     * @throws IllegalArgumentException zipBytes 为 null 或空、未找到 SKILL.md 或 ZIP 条目无效时抛出
     */
    public static AgentSkill createFromZip(byte[] zipBytes, String source, Charset charset) {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new IllegalArgumentException("Zip content cannot be null or empty.");
        }
        return createFromZip(new ByteArrayInputStream(zipBytes), source, charset);
    }

    /**
     * 根据技能 ZIP 包路径创建带自定义来源的 AgentSkill。
     *
     * <p>包内必须有 {@value #SKILL_FILE_NAME} 条目，并可包含可选资源文件。.skill 与 .zip 扩展名对应同一种包格式。
     *
     * @param zipPath ZIP 文件路径
     *
     * @param source 技能来源标识符（null 默认使用 "custom"）
     *
     * @return 创建出的 AgentSkill 实例
     *
     * @throws IllegalArgumentException zipPath 为 null 时抛出
     *
     * @throws RuntimeException 无法读取 ZIP 文件时抛出
     */
    public static AgentSkill createFromZip(Path zipPath, String source) {
        return createFromZip(zipPath, source, StandardCharsets.UTF_8);
    }

    /**
     * 根据技能 ZIP 包路径创建 AgentSkill，并指定来源与字符集。
     *
     * <p>包内必须有 {@value #SKILL_FILE_NAME} 条目，并可包含可选资源文件。.skill 与 .zip 扩展名对应同一种包格式。
     *
     * @param zipPath ZIP 文件路径
     *
     * @param source 技能来源标识符（null 默认使用 "custom"）
     *
     * @param charset 解码 ZIP 条目名称与文件内容的字符集（null 默认使用 UTF-8）。当 ZIP 使用 GBK/GB18030 等非 UTF-8 字符集创建，默认重载解码时报告
     * {@code malformed input} 等错误，可指定此参数
     *
     * @return 创建出的 AgentSkill 实例
     *
     * @throws IllegalArgumentException zipPath 为 null 时抛出
     *
     * @throws RuntimeException 无法读取 ZIP 文件时抛出
     */
    public static AgentSkill createFromZip(Path zipPath, String source, Charset charset) {
        if (zipPath == null) {
            throw new IllegalArgumentException("Zip path cannot be null.");
        }
        try (InputStream inputStream = Files.newInputStream(zipPath)) {
            return createFromZip(inputStream, source, charset);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read skill zip content.", e);
        }
    }

    /**
     * 从技能 ZIP 包输入流创建带自定义来源的 AgentSkill。
     *
     * <p>包内必须有 {@value #SKILL_FILE_NAME} 条目，并可包含可选资源文件。.skill 与 .zip 扩展名对应同一种包格式。
     *
     * @param zipStream ZIP 内容流
     *
     * @param source 技能来源标识符（null 默认使用 "custom"）
     *
     * @return 创建出的 AgentSkill 实例
     *
     * @throws IllegalArgumentException zipStream 为 null 时抛出
     *
     * @throws RuntimeException 无法读取 ZIP 内容时抛出
     */
    public static AgentSkill createFromZip(InputStream zipStream, String source) {
        return createFromZip(zipStream, source, StandardCharsets.UTF_8);
    }

    /**
     * 从技能 ZIP 包输入流创建 AgentSkill，并指定来源与字符集。
     *
     * <p>包内必须有 {@value #SKILL_FILE_NAME} 条目，并可包含可选资源文件。.skill 与 .zip 扩展名对应同一种包格式。
     *
     * @param zipStream ZIP 内容流
     *
     * @param source 技能来源标识符（null 默认使用 "custom"）
     *
     * @param charset 解码 ZIP 条目名称与文件内容的字符集（null 默认使用 UTF-8）。当 ZIP 使用 GBK/GB18030 等非 UTF-8 字符集创建，默认重载解码时报告
     * {@code malformed input} 等错误，可指定此参数
     *
     * @return 创建出的 AgentSkill 实例
     *
     * @throws IllegalArgumentException zipStream 为 null 时抛出
     *
     * @throws RuntimeException 无法读取 ZIP 内容时抛出
     */
    public static AgentSkill createFromZip(InputStream zipStream, String source, Charset charset) {
        if (zipStream == null) {
            throw new IllegalArgumentException("Zip stream cannot be null.");
        }

        Charset zipCharset = charset == null ? StandardCharsets.UTF_8 : charset;
        String skillEntryName = null;
        String skillMdContent = null;
        String rootDir = null;
        Map<String, String> resources = new HashMap<>();

        try (ZipInputStream zipInputStream = new ZipInputStream(zipStream, zipCharset)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = normalizeZipEntryName(entry.getName());
                int separatorIndex = entryName.indexOf('/');
                if (separatorIndex <= 0) {
                    throw new IllegalArgumentException(
                            "Zip entries must be under a single root directory.");
                }
                String entryRoot = entryName.substring(0, separatorIndex);
                if (rootDir == null) {
                    rootDir = entryRoot;
                } else if (!rootDir.equals(entryRoot)) {
                    throw new IllegalArgumentException(
                            "Zip entries must share the same root directory.");
                }
                String content = readZipEntryContent(zipInputStream, zipCharset);

                String expectedSkillEntry = entryRoot + "/" + SKILL_FILE_NAME;
                if (entryName.endsWith("/" + SKILL_FILE_NAME)
                        && !entryName.equals(expectedSkillEntry)) {
                    throw new IllegalArgumentException(
                            "SKILL.md must be located directly under the root directory.");
                }
                if (entryName.equals(expectedSkillEntry)) {
                    if (skillEntryName != null && !skillEntryName.equals(entryName)) {
                        throw new IllegalArgumentException(
                                "Multiple SKILL.md entries found in zip content.");
                    }
                    skillEntryName = entryName;
                    skillMdContent = content;
                    continue;
                }

                String rootPrefix = rootDir + "/";
                if (!entryName.startsWith(rootPrefix)) {
                    throw new IllegalArgumentException(
                            "Zip entries must share the same root directory as SKILL.md.");
                }
                String resourceName = entryName.substring(rootPrefix.length());
                resources.put(resourceName, content);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read skill zip content.", e);
        }

        if (skillEntryName == null) {
            throw new IllegalArgumentException("SKILL.md not found in zip content.");
        }

        return createFrom(skillMdContent, resources, source);
    }

    private static String normalizeZipEntryName(String entryName) {
        if (entryName == null || entryName.isEmpty()) {
            throw new IllegalArgumentException("Zip entry name cannot be null or empty.");
        }
        String normalized = entryName.replace('\\', '/');
        if (normalized.startsWith("/")) {
            throw new IllegalArgumentException("Zip entry name must be a relative path.");
        }
        String[] segments = normalized.split("/");
        for (String segment : segments) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException(
                        "Zip entry name must not contain parent directory segments.");
            }
        }
        return normalized;
    }

    private static String readZipEntryContent(ZipInputStream zipInputStream, Charset charset)
            throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            while ((read = zipInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toString(charset);
        }
    }

    private static String stringifyRequiredMetadata(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }

        Object value = metadata.get(key);
        if (!(value instanceof String stringValue)) {
            return null;
        }

        return stringValue.isBlank() ? null : stringValue;
    }
}
