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

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

/**
 * 解析和生成带 YAML 前置信息的 Markdown 文件的工具。
 *
 * <p>此工具可以：
 * <ul>
 *   <li>从文本提取 YAML 前置元数据和 Markdown 正文
 *   <li>根据元数据和正文生成带 YAML 前置信息的 Markdown 文件
 * </ul>
 *
 * <p>前置信息格式：
 * <pre>{@code
 * ---
 * name: example-skill
 * description: Example skill description
 * version: 1.0.0
 * ---
 * # Skill Content
 * This is the markdown content.
 * }</pre>
 *
 * <p>使用示例：
 * <pre>{@code
 * // Parse markdown with frontmatter
 * ParsedMarkdown parsed = MarkdownSkillParser.parse(markdownContent);
 * Map<String, Object> metadata = parsed.getMetadata();
 * String content = parsed.getContent();
 *
 * // Generate markdown with frontmatter
 * String markdown = MarkdownSkillParser.generate(metadata, content);
 * }</pre>
 */
public class MarkdownSkillParser {

    private static final int FRONTMATTER_CODE_POINT_LIMIT = 16_384;

    private static final Logger logger = LoggerFactory.getLogger(MarkdownSkillParser.class);

    private static final Pattern FRONTMATTER_PATTERN =
            Pattern.compile(
                    "^\\uFEFF?---\\s*[\\r\\n]+(.*?)(?<=[\\r\\n])---"
                            + "(?=[ \\t]*(?:[\\r\\n]|\\z))(?:\\s*[\\r\\n]+)?(.*)",
                    Pattern.DOTALL);

    private static final Pattern SIMPLE_KV_PATTERN =
            Pattern.compile("^([A-Za-z][A-Za-z0-9_-]*)\\s*:\\s*(.*)$");

    private static final LoaderOptions LOADER_OPTIONS = createLoaderOptions();

    private static final DumperOptions DUMPER_OPTIONS = createDumperOptions();

    /**
     * 私有构造方法，防止实例化。
     */
    private MarkdownSkillParser() {}

    /**
     * 解析带 YAML 前置信息的 Markdown 内容。
     *
     * <p>提取 YAML 元数据与 Markdown 正文。起始分隔符前允许有可选字节顺序标记（BOM）。
     * 没有前置信息时，返回空元数据，并保留全部正文。
     *
     * @param markdown Markdown 内容（可以包含或不包含前置信息）
     * @return 包含元数据和正文的 ParsedMarkdown
     */
    public static ParsedMarkdown parse(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return new ParsedMarkdown(Map.of(), "");
        }

        Matcher matcher = FRONTMATTER_PATTERN.matcher(markdown);

        if (!matcher.matches()) {
            return new ParsedMarkdown(Map.of(), markdown);
        }

        String yamlContent = matcher.group(1).trim();
        String markdownContent = matcher.group(2);

        if (yamlContent.isEmpty()) {
            return new ParsedMarkdown(Map.of(), markdownContent);
        }

        return new ParsedMarkdown(parseYamlMetadata(yamlContent), markdownContent);
    }

    /**
     * 生成带 YAML 前置信息的 Markdown 内容。
     *
     * <p>把元数据序列化为文首 YAML 前置信息，再附加正文。
     *
     * @param metadata 要序列化为 YAML 前置信息的元数据（可以为 null 或空）
     * @param content Markdown 正文（可以为 null 或空）
     * @return 包含前置信息的完整 Markdown
     */
    public static String generate(Map<String, Object> metadata, String content) {
        StringBuilder sb = new StringBuilder();

        if (metadata != null && !metadata.isEmpty()) {
            sb.append("---\n");
            sb.append(createDumperYaml().dump(metadata));
            sb.append("---\n");
        }

        if (content != null && !content.isEmpty()) {
            if (metadata != null && !metadata.isEmpty()) {
                sb.append("\n");
            }
            sb.append(content);
        }

        return sb.toString();
    }

    private static Map<String, Object> parseYamlMetadata(String yamlContent) {
        if (yamlContent.codePointCount(0, yamlContent.length()) > FRONTMATTER_CODE_POINT_LIMIT) {
            logger.debug(
                    "Skipping YAML frontmatter because it exceeds the code point limit: {}",
                    FRONTMATTER_CODE_POINT_LIMIT);
            return Map.of();
        }

        Object loaded;
        try {
            loaded = createParserYaml().load(yamlContent);
        } catch (RuntimeException e) {
            logger.debug("Full YAML parse failed, falling back to simple key-value extraction", e);
            return fallbackParseSimpleKeyValues(yamlContent);
        }

        if (loaded == null) {
            return Map.of();
        }

        if (!(loaded instanceof Map<?, ?> rawMap)) {
            logger.debug(
                    "Skipping YAML frontmatter because top-level object is not a map: {}",
                    loaded.getClass());
            return Map.of();
        }

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            Object key = entry.getKey();
            if (!(key instanceof String stringKey)) {
                logger.debug("Skipping YAML metadata entry with non-string key: {}", key);
                continue;
            }

            metadata.put(stringKey, normalizeMetadataValue(entry.getValue()));
        }
        return metadata;
    }

    private static LoaderOptions createLoaderOptions() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(10);
        options.setNestingDepthLimit(10);
        options.setCodePointLimit(FRONTMATTER_CODE_POINT_LIMIT);
        return options;
    }

    private static DumperOptions createDumperOptions() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setSplitLines(false);
        return options;
    }

    private static Yaml createParserYaml() {
        return new Yaml(new SafeConstructor(LOADER_OPTIONS));
    }

    /**
     * 完整 SnakeYAML 解析失败时，逐行提取简单 {@code key: value} 的回退解析器。
     * 跳过不能处理的列表、嵌套对象和块标量，同时仍提取 {@code name}
     * 和 {@code description} 等标量键值。
     */
    private static Map<String, Object> fallbackParseSimpleKeyValues(String yamlContent) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        String[] lines = yamlContent.split("\\r?\\n");

        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }

            // 缩进行属于前一个块标量或复杂结构，因此跳过
            if (line.startsWith(" ") || line.startsWith("\t")) {
                continue;
            }

            // 跳过顶层列表项
            if (line.trim().startsWith("-")) {
                continue;
            }

            Matcher matcher = SIMPLE_KV_PATTERN.matcher(line);
            if (!matcher.matches()) {
                logger.debug("Fallback parser skipping unsupported line: {}", line);
                continue;
            }

            String key = matcher.group(1);
            String rawValue = matcher.group(2).trim();

            // 跳过块标量修饰符，其内容位于后续缩进行
            if (rawValue.startsWith("|") || rawValue.startsWith(">")) {
                logger.debug("Fallback parser skipping block scalar key: {}", key);
                continue;
            }

            // 跳过值为空的键（后面可能跟随嵌套内容）
            if (rawValue.isEmpty()) {
                continue;
            }

            result.put(key, unquote(rawValue));
        }

        if (result.isEmpty()) {
            logger.debug("Fallback parser extracted no metadata");
        } else {
            logger.debug(
                    "Fallback parser extracted {} entries: {}", result.size(), result.keySet());
        }
        return result;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static Yaml createDumperYaml() {
        return new Yaml(new Representer(DUMPER_OPTIONS), DUMPER_OPTIONS);
    }

    /**
     * 把 YAML 解析结果规范化为稳定的元数据树。
     *
     * <p>原因：{@link SafeConstructor} 保证安全解析，但仍可能返回宽泛容器类型，
     * 例如 {@code Map<?, ?>}、各种 {@link Collection} 实现和数组。
     * 技能处理流程需要可预测的结构，以保留嵌套元数据，并在后续渲染为 XML 时不丢失结构。
     *
     * <p>做法：递归把嵌套值规范为三种形式：
     * <ul>
     *   <li>标量原样保留</li>
     *   <li>映射转换为 {@code Map<String, Object>}</li>
     *   <li>集合和数组转换为 {@code List<Object>}</li>
     * </ul>
     */
    private static Object normalizeMetadataValue(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Map<?, ?> rawMap) {
            LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                Object key = entry.getKey();
                String normalizedKey = key instanceof String ? (String) key : String.valueOf(key);
                normalized.put(normalizedKey, normalizeMetadataValue(entry.getValue()));
            }
            return normalized;
        }

        if (value instanceof Collection<?> collection) {
            List<Object> normalized = new ArrayList<>(collection.size());
            for (Object item : collection) {
                normalized.add(normalizeMetadataValue(item));
            }
            return normalized;
        }

        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> normalized = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                normalized.add(normalizeMetadataValue(Array.get(value, i)));
            }
            return normalized;
        }

        return value;
    }

    /**
     * 解析带前置信息 Markdown 的结果。
     *
     * <p>包含提取出的元数据和 Markdown 正文。
     */
    public static class ParsedMarkdown {
        private final Map<String, Object> metadata;
        private final String content;

        /**
         * 创建 Markdown 解析结果。
         *
         * @param metadata YAML 元数据（不可为 null，可以为空）
         * @param content Markdown 正文（不可为 null，可以为空）
         */
        public ParsedMarkdown(Map<String, Object> metadata, String content) {
            this.metadata =
                    metadata != null
                            ? Collections.unmodifiableMap(new LinkedHashMap<>(metadata))
                            : Collections.emptyMap();
            this.content = content != null ? content : "";
        }

        /**
         * 获取从 YAML 前置信息提取的元数据。
         *
         * @return 元数据映射（不可为 null，可以为空）
         */
        public Map<String, Object> getMetadata() {
            return metadata;
        }

        /**
         * 获取 Markdown 正文（不含前置信息）。
         *
         * @return Markdown 正文（不可为 null，可以为空）
         */
        public String getContent() {
            return content;
        }

        /**
         * 检查是否存在前置信息。
         *
         * @return 元数据非空时为 true
         */
        public boolean hasFrontmatter() {
            return !metadata.isEmpty();
        }

        @Override
        public String toString() {
            return String.format(
                    "ParsedMarkdown{metadata=%s, content='%s'}",
                    metadata, content.substring(0, Math.min(50, content.length())));
        }
    }
}
