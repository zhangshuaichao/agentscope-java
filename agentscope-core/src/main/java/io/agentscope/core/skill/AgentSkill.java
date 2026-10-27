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

package io.agentscope.core.skill;

import io.agentscope.core.skill.util.SkillUtil;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 表示智能体可加载和使用的技能。
 *
 * <p>技能包含：
 * <ul>
 * <li>名称和描述：标识技能。
 * <li>技能内容：实际实现或指令。
 * <li>资源：技能引用的辅助文件或数据。
 * <li>版本和来源：跟踪技能来源与版本。
 * </ul>
 *
 * <p><b>创建方式：</b>
 * <ul>
 * <li>从带 YAML frontmatter 的 Markdown 创建，自动提取元数据。
 * <li>通过显式参数直接构造全部字段。
 * <li>通过构建器创建现有技能的修改版本。
 * </ul>
 *
 * <p><b>用法示例：</b>
 * <pre>{@code
 * // 从带 frontmatter 的 Markdown 创建，使用 SkillUtil
 * String skillMd = "---\nname: my-skill\ndescription: Does something\n---\nContent here";
 * Map<String, String> resources = Map.of("file1.txt", "content1");
 * AgentSkill skill = SkillUtil.createFrom(skillMd, resources);
 *
 * // 直接构造
 * AgentSkill skill2 = new AgentSkill("my-skill", "Does something", "Content here", resources);
 *
 * // 使用构建器创建修改后的版本
 * AgentSkill modified = skill.toBuilder()
 *     .description("Updated description")
 *     .skillContent("Modified instructions")
 *     .addResource("config.json", "{\"key\": \"value\"}")
 *     .build();
 * }</pre>
 *
 * @see io.agentscope.core.skill.util.SkillUtil
 * @see io.agentscope.core.skill.util.MarkdownSkillParser
 * 在应用代码中管理 Markdown 技能目录。
 */
public class AgentSkill {
    private final Map<String, Object> metadata;
    private final String skillContent;
    private final Map<String, String> resources;
    private final String source;

    /**
     * 技能在磁盘上的源目录绝对路径，可选。存在时，提示提供者可为每个技能输出 {@code <files-root>}，使 LLM 无需经过 {@code uploadSkillFiles} 即可通过 Shell 直接执行脚本；加载工具也可在内存资源缺失时回退到此目录。
     */
    private final Path originDir;

    /**
     * 使用显式参数创建 AgentSkill。
     *
     * <p>适合无需解析 Markdown、直接创建技能的情况。默认来源为 "custom"。
     *
     * @param name 技能名称（不能为 null 或空）
     * @param description 技能描述（不能为 null 或空）
     * @param skillContent 技能实现或指令（不能为 null 或空）
     * @param resources 技能引用的辅助资源（可为 null）
     * @throws IllegalArgumentException 当 name、description 或 skillContent 为 null 或空时
     */
    public AgentSkill(
            String name, String description, String skillContent, Map<String, String> resources) {
        this(name, description, skillContent, resources, "custom");
    }

    /**
     * 使用显式参数和自定义来源创建 AgentSkill。
     *
     * <p>适合无需解析 Markdown、直接创建技能的情况。source 参数标识技能的来源。
     *
     * @param name 技能名称（不能为 null 或空）
     * @param description 技能描述（不能为 null 或空）
     * @param skillContent 技能实现或指令（不能为 null 或空）
     * @param resources 技能引用的辅助资源（可为 null）
     * @param source 技能来源标识（null 默认为 "custom"）
     * @throws IllegalArgumentException 当 name、description 或 skillContent 为 null 或空时
     */
    public AgentSkill(
            String name,
            String description,
            String skillContent,
            Map<String, String> resources,
            String source) {
        this(createMetadata(name, description), skillContent, resources, source);
    }

    /**
     * 使用显式元数据创建 AgentSkill。
     *
     * <p>元数据必须包含非空字符串 {@code name} 和 {@code description}。元数据映射复制后以不可变形式保存。
     *
     * @param metadata 技能元数据，包含必填的 {@code name} 和 {@code description}
     * @param skillContent 技能实现或指令（不能为 null 或空）
     * @param resources 技能引用的辅助资源（可为 null）
     * @param source 技能来源标识（null 默认为 "custom"）
     * @throws IllegalArgumentException 当元数据无效或 skillContent 为 null 或空时
     */
    public AgentSkill(
            Map<String, Object> metadata,
            String skillContent,
            Map<String, String> resources,
            String source) {
        this(metadata, skillContent, resources, source, null);
    }

    /**
     * 使用显式元数据和磁盘源目录创建 AgentSkill。
     *
     * <p>{@code name} 必须符合标准命名规则（{@link SkillUtil#VALID_SKILL_NAME_PATTERN}，最多 {@link SkillUtil#MAX_SKILL_NAME_LENGTH} 个字符），因为物化文件时会将名称原样作为目录键。
     *
     * @param metadata 技能元数据，包含必填的 {@code name} 和 {@code description}
     * @param skillContent 技能实现或指令（不能为 null 或空）
     * @param resources 技能引用的辅助资源（可为 null）
     * @param source 技能来源标识（null 默认为 "custom"）
     * @param originDir 技能源目录的绝对路径；非文件系统来源（如 classpath、远程、合成来源）为 {@code null}，按原值保存
     * @throws IllegalArgumentException 当元数据无效、skillContent 为 null 或空，或名称不符合标准命名规则时
     */
    public AgentSkill(
            Map<String, Object> metadata,
            String skillContent,
            Map<String, String> resources,
            String source,
            Path originDir) {
        String name = getRequiredMetadataString(metadata, "name");
        String description = getRequiredMetadataString(metadata, "description");
        SkillUtil.requireValidSkillName(name);
        if (skillContent == null || skillContent.isEmpty()) {
            throw new IllegalArgumentException("The skill must have content");
        }

        LinkedHashMap<String, Object> metadataCopy = new LinkedHashMap<>(metadata);
        metadataCopy.put("name", name);
        metadataCopy.put("description", description);

        this.metadata = Collections.unmodifiableMap(metadataCopy);
        this.skillContent = skillContent;
        this.resources = resources != null ? new HashMap<>(resources) : new HashMap<>();
        this.source = source != null ? source : "custom";
        this.originDir = originDir;
    }

    /**
     * 获取技能名称。
     *
     * @return 技能名称（不会为 null）
     */
    public String getName() {
        return (String) metadata.get("name");
    }

    /**
     * 获取技能描述。
     *
     * @return 技能描述（不会为 null）
     */
    public String getDescription() {
        return (String) metadata.get("description");
    }

    /**
     * 获取技能元数据。
     *
     * @return 不可变元数据映射（不会为 null；除必填字段外可以为空）
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * 按键获取元数据值。
     *
     * @param key 元数据键
     * @return 元数据值；未找到时为 null
     */
    public Object getMetadataValue(String key) {
        return metadata.get(key);
    }

    /**
     * 获取技能内容。
     *
     * <p>包含实际的技能实现或指令。
     *
     * @return 技能内容（不会为 null）
     */
    public String getSkillContent() {
        return skillContent;
    }

    /**
     * 获取技能来源标识。
     *
     * @return 来源标识（不会为 null）
     */
    public String getSource() {
        return source;
    }

    /**
     * 获取技能资源。
     *
     * @return 资源映射（不会为 null，可以为空）
     */
    public Map<String, String> getResources() {
        return new HashMap<>(resources);
    }

    /**
     * 按路径获取资源内容。
     *
     * @param resourcePath 资源路径
     * @return 资源内容；未找到时为 null
     */
    public String getResource(String resourcePath) {
        return resources.get(resourcePath);
    }

    /**
     * 获取此技能的全部资源路径。
     *
     * @return 不可修改的资源路径集合
     */
    public Set<String> getResourcePaths() {
        return Collections.unmodifiableSet(new HashSet<>(resources.keySet()));
    }

    /**
     * 获取此技能的唯一标识。
     *
     * <p>ID 由名称与来源组成："name_source"。标准命名规则禁止名称包含下划线，因此可在首个下划线处确定性拆分，不同的 {@code (name, source)} 对总会生成不同 ID。若同 ID 注册覆盖了不同的 {@code (name, source)} 对，{@code SkillRegistry} 还会发出警告；在当前字符集规则下此情况不可达，仅用于防范未来放宽规则造成的问题。
     *
     * @return 唯一技能标识（不会为 null）
     */
    public String getSkillId() {
        return getName() + "_" + source;
    }

    /**
     * 返回技能在磁盘上的源目录绝对路径（若可用）。提示提供者据此输出每个技能的 {@code <files-root>} 条目；加载工具在内存资源缺失时也可回退到磁盘读取。
     *
     * @return 源目录；非文件系统来源时为空
     */
    public Optional<Path> getOriginDir() {
        return Optional.ofNullable(originDir);
    }

    /**
     * 创建用当前技能值初始化的构建器。
     *
     * <p>适合创建现有技能的修改版本。
     *
     * @return 新的构建器实例
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * 创建从零构建技能的构建器。
     *
     * @return 字段为空的新构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 返回此技能的字符串表示。
     *
     * @return 包含名称、描述和来源的字符串表示
     */
    @Override
    public String toString() {
        return "AgentSkill{name='"
                + getName()
                + "', description='"
                + getDescription()
                + "', source='"
                + source
                + "'}";
    }

    /**
     * 创建 AgentSkill 实例的构建器。
     *
     * <p>可选择性修改技能字段，在已有技能基础上创建新实例，或从零创建技能。
     *
     * <p><b>用法示例：</b>
     * <pre>{@code
     * // 从头创建
     * AgentSkill skill = AgentSkill.builder()
     *     .name("my-skill")
     *     .description("Does something")
     *     .skillContent("Instructions here")
     *     .addResource("file.txt", "content")
     *     .build();
     *
     * // 修改已有技能
     * AgentSkill modified = existingSkill.toBuilder()
     *     .description("Updated description")
     *     .addResource("new_file.txt", "new content")
     *     .build();
     * }</pre>
     */
    public static class Builder {
        private Map<String, Object> metadata;
        private String skillContent;
        private Map<String, String> resources;
        private String source;
        private Path originDir;

        /**
         * 创建空构建器。
         */
        private Builder() {
            this.metadata = new LinkedHashMap<>();
            this.resources = new HashMap<>();
        }

        /**
         * 创建用现有技能值初始化的构建器。
         *
         * @param baseSkill 提供待复制值的技能
         */
        private Builder(AgentSkill baseSkill) {
            this.metadata = new LinkedHashMap<>(baseSkill.metadata);
            this.skillContent = baseSkill.skillContent;
            this.resources = new HashMap<>(baseSkill.resources);
            this.source = baseSkill.source;
            this.originDir = baseSkill.originDir;
        }

        /**
         * 设置技能名称。
         *
         * @param name 技能名称
         * @return 当前构建器
         */
        public Builder name(String name) {
            this.metadata.put("name", name);
            return this;
        }

        /**
         * 设置技能描述。
         *
         * @param description 技能描述
         * @return 当前构建器
         */
        public Builder description(String description) {
            this.metadata.put("description", description);
            return this;
        }

        /**
         * 用新映射替换全部元数据。
         *
         * @param metadata 新元数据映射
         * @return 当前构建器
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata =
                    metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
            return this;
        }

        /**
         * 添加或更新一个元数据条目。
         *
         * @param key 元数据键
         * @param value 元数据值
         * @return 当前构建器
         */
        public Builder putMetadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        /**
         * 移除一个元数据条目。
         *
         * @param key 要移除的元数据键
         * @return 当前构建器
         */
        public Builder removeMetadata(String key) {
            this.metadata.remove(key);
            return this;
        }

        /**
         * 设置技能内容。
         *
         * @param skillContent 技能内容或指令
         * @return 当前构建器
         */
        public Builder skillContent(String skillContent) {
            this.skillContent = skillContent;
            return this;
        }

        /**
         * 用新映射替换全部资源。
         *
         * @param resources 新资源映射
         * @return 当前构建器
         */
        public Builder resources(Map<String, String> resources) {
            this.resources = new HashMap<>(resources);
            return this;
        }

        /**
         * 添加或更新一个资源。
         *
         * @param path 资源路径
         * @param content 资源内容
         * @return 当前构建器
         */
        public Builder addResource(String path, String content) {
            this.resources.put(path, content);
            return this;
        }

        /**
         * 移除资源。
         *
         * @param path 要移除的资源路径
         * @return 当前构建器
         */
        public Builder removeResource(String path) {
            this.resources.remove(path);
            return this;
        }

        /**
         * 清空全部资源。
         *
         * @return 当前构建器
         */
        public Builder clearResources() {
            this.resources.clear();
            return this;
        }

        /**
         * 设置来源标识。
         *
         * @param source 来源标识
         * @return 当前构建器
         */
        public Builder source(String source) {
            this.source = source;
            return this;
        }

        /**
         * 设置技能在磁盘上的源目录。从本地目录物化技能的仓库应调用此方法，以便提示提供者输出 {@code <files-root>}，并让加载工具可回退到磁盘读取。
         *
         * @param originDir 技能源目录的绝对路径，或 {@code null}
         * @return 当前构建器
         */
        public Builder originDir(Path originDir) {
            this.originDir = originDir;
            return this;
        }

        /**
         * 构建 AgentSkill 实例。
         *
         * @return 新的 AgentSkill 实例
         * @throws IllegalArgumentException 当缺少必填字段时
         */
        public AgentSkill build() {
            return new AgentSkill(metadata, skillContent, resources, source, originDir);
        }
    }

    private static Map<String, Object> createMetadata(String name, String description) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        metadata.put("description", description);
        return metadata;
    }

    private static String getRequiredMetadataString(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            throw new IllegalArgumentException(
                    "The skill must have `name` and `description` fields.");
        }

        Object value = metadata.get(key);
        if (!(value instanceof String stringValue) || stringValue.isEmpty()) {
            throw new IllegalArgumentException(
                    "The skill must have `name` and `description` fields.");
        }
        return stringValue;
    }
}
