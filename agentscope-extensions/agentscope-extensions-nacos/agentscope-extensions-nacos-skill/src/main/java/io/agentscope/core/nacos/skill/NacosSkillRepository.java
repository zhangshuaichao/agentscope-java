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
package io.agentscope.core.nacos.skill;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.common.utils.StringUtils;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.util.SkillUtil;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 Nacos AI 技能包的 {@link AgentSkillRepository}。
 * <p><strong>加载：</strong>通过 {@link AiService} 下载技能 ZIP，然后从中构建
 * {@link AgentSkill}（包括 {@code SKILL.md}）。Nacos 导出内容中带缩进的 YAML frontmatter
 * 会被规范化，以供 AgentScope 的扁平 {@code key: value} 解析器读取。API 选择：
 * 设置版本时使用 {@link AiService#downloadSkillZipByVersion(String, String)}；
 * 否则仅设置标签时使用 {@link
 * AiService#downloadSkillZipByLabel(String, String)}；均未设置时使用 {@link
 * AiService#downloadSkillZip(String)}。版本和标签都解析到值时，版本优先，下载不使用标签。
 * <p><strong>能力：</strong>支持读取操作：{@link #getSkill(String)}、{@link
 * #skillExists(String)}、{@link #getRepositoryInfo()}、{@link #getSource()}、{@link
 * #isWriteable()}（始终为 {@code false}）。不支持写入：{@link #save(List, boolean)} 和
 * {@link #delete(String)} 不做操作；忽略 {@link #setWriteable(boolean)}。这些路径会记录警告。
 * <p><strong>列举：</strong>此适配器使用的 Nacos AI Service 没有列举全部技能的 API。
 * 构造时未提供 {@code knownSkillNames}，则 {@link #getAllSkillNames()} 和 {@link #getAllSkills()}
 * 返回空结果并记录警告。向 {@link #NacosSkillRepository(AiService, String, Properties, List)}
 * 构造方法传入非空 {@code knownSkillNames} 可启用枚举；随后 {@link #getAllSkills()}
 * 顺序调用 {@link #getSkill(String)} 获取各技能（每技能一次网络调用；大型目录可优先考虑 {@code MysqlSkillRepository}）。
 * <p><strong>版本与标签解析：</strong>版本和标签分别采用第一个非空白值，顺序为：
 * （1）{@link #NacosSkillRepository(AiService, String, Properties)} 的 {@link Properties}；
 * （2）JVM 系统属性 {@link
 * #SKILL_VERSION_PATH} 或 {@link #SKILL_LABEL_PATH}；（3）环境变量 {@link
 * #ENV_SKILL_VERSION_PATH} 或 {@link #ENV_SKILL_LABEL_PATH}。
 * <p>【学习提示】版本/标签在构造时解析，修改本地属性不会自动刷新已有实例。
 * ZIP 规范化只处理技能元数据格式，不意味着远端技能内容已获信任或授权执行。
 */
public class NacosSkillRepository implements AgentSkillRepository {

    private static final Logger log = LoggerFactory.getLogger(NacosSkillRepository.class);

    private static final String SKILL_MD = "SKILL.md";

    /** 采用与 {@code MarkdownSkillParser} 简单 YAML 解析器一致的键模式（扁平 key: value）。 */
    private static final Pattern YAML_KV =
            Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_-]*)\\s*:\\s*(.*)$");

    /** 技能包条目：恰好一个路径段，随后是 {@value #SKILL_MD}。 */
    private static final Pattern ROOT_SKILL_MD = Pattern.compile("^([^/]+)/" + SKILL_MD + "$");

    private static final String REPO_TYPE = "nacos";
    private static final String LOCATION_PREFIX = "namespace:";

    /** {@link Properties}、JVM {@link System#getProperty(String)} 等使用的技能版本键。 */
    public static final String SKILL_VERSION_PATH = "agentscope.nacos.skill.version";

    /** {@link Properties}、JVM {@link System#getProperty(String)} 等使用的技能标签键。 */
    public static final String SKILL_LABEL_PATH = "agentscope.nacos.skill.label";

    /** 技能版本环境变量名（在应用属性和 JVM 属性之后作为后备）。 */
    public static final String ENV_SKILL_VERSION_PATH = "AGENTSCOPE_NACOS_SKILL_VERSION";

    /** 技能标签环境变量名（在应用属性和 JVM 属性之后作为后备）。 */
    public static final String ENV_SKILL_LABEL_PATH = "AGENTSCOPE_NACOS_SKILL_LABEL";

    private final AiService aiService;
    private final String namespaceId;
    private final String source;
    private final String location;
    private final String skillVersion;
    private final String skillLabel;
    private final List<String> knownSkillNames;

    /**
     * 等同于 {@link #NacosSkillRepository(AiService, String, Properties)}，其中 {@code properties
     * == null}。
     */
    public NacosSkillRepository(AiService aiService, String namespaceId) {
        this(aiService, namespaceId, null, Collections.emptyList());
    }

    /** 等同于 {@link #NacosSkillRepository(AiService, String, Properties, List)}，其中 {@code knownSkillNames} 为空。 */
    public NacosSkillRepository(AiService aiService, String namespaceId, Properties properties) {
        this(aiService, namespaceId, properties, Collections.emptyList());
    }

    /**
     * 为给定 Nacos 命名空间创建仓库，一次性解析技能版本与标签（优先级见类 Javadoc）。
     * @param aiService        Nacos AI 服务（不得为 null）
     * @param namespaceId      Nacos 命名空间 ID（空白视为 {@code public}）
     * @param properties       可选应用属性，可为 {@code null}
     * @param knownSkillNames  经 {@link #getAllSkillNames()} 和
     *                         {@link #getAllSkills()} 暴露的技能名；传入空列表保持不枚举行为
     */
    public NacosSkillRepository(
            AiService aiService,
            String namespaceId,
            Properties properties,
            List<String> knownSkillNames) {
        if (aiService == null) {
            throw new IllegalArgumentException("AiService cannot be null");
        }
        this.aiService = aiService;

        this.namespaceId = StringUtils.isBlank(namespaceId) ? "public" : namespaceId.trim();
        this.source = REPO_TYPE + "@" + this.namespaceId;
        this.location = LOCATION_PREFIX + this.namespaceId;
        this.skillVersion =
                firstNonBlank(
                        getProperties(properties, SKILL_VERSION_PATH),
                        System.getProperty(SKILL_VERSION_PATH),
                        System.getenv(ENV_SKILL_VERSION_PATH));
        this.skillLabel =
                firstNonBlank(
                        getProperties(properties, SKILL_LABEL_PATH),
                        System.getProperty(SKILL_LABEL_PATH),
                        System.getenv(ENV_SKILL_LABEL_PATH));
        this.knownSkillNames =
                knownSkillNames == null ? Collections.emptyList() : List.copyOf(knownSkillNames);
        log.info(
                "NacosSkillRepository initialized for namespace: {}, skillVersion: {},"
                        + " skillLabel: {}, knownSkillNames: {}",
                this.namespaceId,
                StringUtils.isBlank(skillVersion) ? "(none)" : skillVersion,
                StringUtils.isBlank(skillLabel) ? "(none)" : skillLabel,
                this.knownSkillNames.isEmpty() ? "(none)" : this.knownSkillNames);
    }

    @Override
    public AgentSkill getSkill(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill name cannot be null or empty");
        }
        try {
            byte[] zipBytes = downloadSkillZipBytes(name.trim());
            if (zipBytes == null || zipBytes.length == 0) {
                throw new IllegalArgumentException("Skill not found: " + name);
            }
            byte[] adaptedZip = adaptNacosSkillZipForYamlFrontmatter(zipBytes);
            return SkillUtil.createFromZip(adaptedZip, getSource());
        } catch (NacosException e) {
            if (e.getErrCode() == NacosException.NOT_FOUND) {
                throw new IllegalArgumentException("Skill not found: " + name, e);
            }
            throw new RuntimeException("Failed to load skill from Nacos: " + name, e);
        }
    }

    @Override
    public boolean skillExists(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        try {
            byte[] zipBytes = downloadSkillZipBytes(skillName.trim());
            return zipBytes != null && zipBytes.length > 0;
        } catch (NacosException e) {
            if (e.getErrCode() == NacosException.NOT_FOUND) {
                return false;
            }
            log.warn("Error checking skill existence for {}: {}", skillName, e.getMessage());
            return false;
        }
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo(REPO_TYPE, location, false);
    }

    @Override
    public String getSource() {
        return source;
    }

    @Override
    public void setWriteable(boolean writeable) {
        log.warn("NacosSkillRepository is read-only, set writeable operation ignored");
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    // ---------- 只读仓库的空操作（列举和写入） ----------

    @Override
    public List<String> getAllSkillNames() {
        if (knownSkillNames.isEmpty()) {
            log.warn(
                    "NacosSkillRepository: no knownSkillNames configured, returning empty list."
                            + " Pass knownSkillNames to the constructor to enable enumeration.");
            return Collections.emptyList();
        }
        return knownSkillNames;
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        if (knownSkillNames.isEmpty()) {
            log.warn(
                    "NacosSkillRepository: no knownSkillNames configured, returning empty list."
                            + " Pass knownSkillNames to the constructor to enable enumeration.");
            return Collections.emptyList();
        }
        // 每技能一次网络调用；大型目录优先考虑 MysqlSkillRepository
        List<AgentSkill> result = new ArrayList<>(knownSkillNames.size());
        for (String name : knownSkillNames) {
            try {
                result.add(getSkill(name));
            } catch (Exception e) {
                log.warn("NacosSkillRepository: failed to load skill '{}', skipping", name, e);
            }
        }
        return result;
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        log.warn("NacosSkillRepository is read-only, save operation ignored");
        return false;
    }

    @Override
    public boolean delete(String skillName) {
        log.warn("NacosSkillRepository is read-only, delete operation ignored");
        return false;
    }

    @Override
    public void close() {
        // AiService 生命周期由调用方管理，此处无需释放资源
    }

    /** 返回 {@link Properties#getProperty(String)}；{@code properties} 为 null 时返回 {@code null}。 */
    private static String getProperties(Properties properties, String key) {
        if (properties == null || key == null) {
            return null;
        }
        return properties.getProperty(key);
    }

    /** 返回首个非 null 且经 {@link String#trim()} 后非空白的参数；否则返回 {@code null}。 */
    private static String firstNonBlank(String... candidates) {
        for (String s : candidates) {
            if (StringUtils.isNotBlank(s)) {
                return s.trim();
            }
        }
        return null;
    }

    /** 按构造时解析的版本、标签或未指定版本的 ZIP 选择 AiService 调用。 */
    private byte[] downloadSkillZipBytes(String skillName) throws NacosException {
        if (StringUtils.isNotBlank(skillVersion)) {
            return aiService.downloadSkillZipByVersion(skillName, skillVersion);
        }
        if (StringUtils.isNotBlank(skillLabel)) {
            return aiService.downloadSkillZipByLabel(skillName, skillLabel);
        }
        return aiService.downloadSkillZip(skillName);
    }

    /**
     * Nacos 导出的 {@value #SKILL_MD} 常在 YAML frontmatter 中使用缩进续行（无 {@code key:} 前缀）。
     * AgentScope frontmatter 解析器只接受扁平 {@code key:
     * value} 行，因此将续行折叠到前一个键并重写 ZIP，使 {@link
     * SkillUtil#createFromZip} 能成功解析。
     */
    private static byte[] adaptNacosSkillZipForYamlFrontmatter(byte[] zipBytes) {
        String skillPath = null;
        String skillMd = null;
        Map<String, byte[]> entries = new LinkedHashMap<>();

        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = normalizeZipEntryName(entry.getName());
                byte[] data = readZipEntryBytes(zin);
                entries.put(name, data);
                if (ROOT_SKILL_MD.matcher(name).matches()) {
                    if (skillPath != null && !skillPath.equals(name)) {
                        return zipBytes;
                    }
                    skillPath = name;
                    skillMd = new String(data, StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to inspect skill zip for frontmatter adaptation: {}", e.getMessage());
            return zipBytes;
        }

        if (skillPath == null || skillMd == null) {
            return zipBytes;
        }

        String adaptedMd = adaptSkillMdFrontmatter(skillMd);
        if (adaptedMd.equals(skillMd)) {
            return zipBytes;
        }

        entries.put(skillPath, adaptedMd.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zout.putNextEntry(new ZipEntry(e.getKey()));
                zout.write(e.getValue());
                zout.closeEntry();
            }
        } catch (IOException e) {
            log.warn("Failed to repack skill zip after frontmatter adaptation: {}", e.getMessage());
            return zipBytes;
        }
        return bos.toByteArray();
    }

    /** 仅规范化 frontmatter，正文保持不变。 */
    private static String adaptSkillMdFrontmatter(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return markdown;
        }
        String text = markdown;
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        if (!text.startsWith("---")) {
            return markdown;
        }

        List<String> lines = splitLinesPreserveTrailing(text);
        if (lines.isEmpty() || !lines.get(0).trim().equals("---")) {
            return markdown;
        }

        int yamlEnd = -1;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().equals("---")) {
                yamlEnd = i;
                break;
            }
        }
        if (yamlEnd < 0) {
            return markdown;
        }

        List<String> yamlLines = lines.subList(1, yamlEnd);
        String normalizedYaml = normalizeFoldedFlatYaml(yamlLines);
        StringBuilder out = new StringBuilder();
        out.append("---\n");
        out.append(normalizedYaml);
        out.append("---");
        for (int i = yamlEnd + 1; i < lines.size(); i++) {
            out.append('\n').append(lines.get(i));
        }
        return out.toString();
    }

    private static List<String> splitLinesPreserveTrailing(String text) {
        String[] parts = text.split("\\R", -1);
        List<String> list = new ArrayList<>(parts.length);
        Collections.addAll(list, parts);
        return list;
    }

    /** 将不是 {@code key: value} 的行折叠到前一个键的值中（Nacos / 宽松 YAML 风格描述）。 */
    private static String normalizeFoldedFlatYaml(List<String> yamlLines) {
        StringBuilder emit = new StringBuilder();
        String pendingKey = null;
        String pendingVal = null;

        for (String raw : yamlLines) {
            String t = raw.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("#")) {
                continue;
            }
            Matcher m = YAML_KV.matcher(t);
            if (m.matches()) {
                flushYamlKvLine(emit, pendingKey, pendingVal);
                pendingKey = m.group(1);
                pendingVal = m.group(2).trim();
            } else if (pendingKey != null) {
                if (pendingVal == null || pendingVal.isEmpty()) {
                    pendingVal = t;
                } else {
                    pendingVal = pendingVal + " " + t;
                }
            }
        }
        flushYamlKvLine(emit, pendingKey, pendingVal);
        return emit.toString();
    }

    private static void flushYamlKvLine(StringBuilder sb, String key, String value) {
        if (key == null) {
            return;
        }
        sb.append(key).append(": ");
        if (value == null || value.isEmpty()) {
            sb.append('\n');
            return;
        }
        if (yamlValueNeedsQuoting(value)) {
            sb.append('"');
            appendYamlDoubleQuotedEscaped(sb, value);
            sb.append('"');
        } else {
            sb.append(value);
        }
        sb.append('\n');
    }

    /** 与 {@code MarkdownSkillParser.SimpleYamlParser#needsQuoting} 的行为保持一致。 */
    private static boolean yamlValueNeedsQuoting(String value) {
        if (value.isEmpty()) {
            return false;
        }
        if (value.contains(":")
                || value.contains("#")
                || value.contains("\n")
                || value.contains("\r")
                || value.contains("\t")) {
            return true;
        }
        if (Character.isWhitespace(value.charAt(0))
                || Character.isWhitespace(value.charAt(value.length() - 1))) {
            return true;
        }
        char first = value.charAt(0);
        return first == '"'
                || first == '\''
                || first == '['
                || first == ']'
                || first == '{'
                || first == '}'
                || first == '>'
                || first == '|'
                || first == '*'
                || first == '&'
                || first == '!'
                || first == '%'
                || first == '@'
                || first == '`';
    }

    private static void appendYamlDoubleQuotedEscaped(StringBuilder sb, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
            }
        }
    }

    private static String normalizeZipEntryName(String entryName) {
        if (entryName == null || entryName.isEmpty()) {
            throw new IllegalArgumentException("Zip entry name cannot be null or empty.");
        }
        String normalized = entryName.replace('\\', '/');
        if (normalized.startsWith("/")) {
            throw new IllegalArgumentException("Zip entry name must be a relative path.");
        }
        for (String segment : normalized.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException(
                        "Zip entry name must not contain parent directory segments.");
            }
        }
        return normalized;
    }

    private static byte[] readZipEntryBytes(ZipInputStream zipInputStream) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            while ((read = zipInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
        }
    }
}
