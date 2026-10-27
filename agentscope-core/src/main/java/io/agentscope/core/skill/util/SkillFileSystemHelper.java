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
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 技能相关文件系统操作的工具类。
 *
 * <p>提供可复用的加载、列举、保存和删除操作，供文件系统仓库、Git 仓库等多个实现共享。
 */
public final class SkillFileSystemHelper {

    private static final Logger logger = LoggerFactory.getLogger(SkillFileSystemHelper.class);
    private static final String SKILL_FILE_NAME = "SKILL.md";

    private SkillFileSystemHelper() {}

    /**
     * 从指定基础目录按名称加载技能。
     *
     * @param baseDir 包含技能目录的基础目录
     * @param skillName 要加载的技能名称
     * @param source 为所创建技能设置的来源标识符
     * @return 加载出的 AgentSkill
     * @throws IllegalArgumentException 未找到技能或技能无效时抛出
     */
    public static AgentSkill loadSkill(Path baseDir, String skillName, String source) {
        Path skillDir =
                findSkillDirectoryByName(baseDir, skillName)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Skill directory does not exist for skill name: "
                                                        + skillName));
        return loadSkillFromDirectory(skillDir, source);
    }

    /**
     * 从指定目录加载技能，将技能树中所有非隐藏文件预先读入内存。
     * 等同于 {@link #loadSkillFromDirectory(Path, String, boolean)
     *  loadSkillFromDirectory(skillDir, source, true)}。
     */
    public static AgentSkill loadSkillFromDirectory(Path skillDir, String source) {
        return loadSkillFromDirectory(skillDir, source, true);
    }

    /**
     * 从指定目录加载技能。
     *
     * <p>返回的技能始终带有与 {@code skillDir} 对应的绝对 {@code originDir}，
     * 调用方（提示词提供者、加载工具）可直接定位磁盘文件，无需再次复制。
     *
     * @param skillDir 包含 SKILL.md 的目录
     * @param source 为所创建技能设置的来源标识符
     * @param includeResources 为 {@code true} 时，将 {@code skillDir} 下所有非隐藏文件
     *      预加载到技能的内存资源映射中（旧行为）；为 {@code false} 时仅读取 SKILL.md，
     *      调用方按需通过 {@code originDir} 回退到磁盘访问
     * @return 加载出的 AgentSkill（已填充 {@code originDir}）
     * @throws IllegalArgumentException SKILL.md 缺失或无效时抛出
     */
    public static AgentSkill loadSkillFromDirectory(
            Path skillDir, String source, boolean includeResources) {
        if (!Files.exists(skillDir)) {
            throw new IllegalArgumentException("Skill directory does not exist: " + skillDir);
        }

        if (!Files.isDirectory(skillDir)) {
            throw new IllegalArgumentException("Skill path is not a directory: " + skillDir);
        }

        Path skillFile = skillDir.resolve(SKILL_FILE_NAME);
        if (!Files.exists(skillFile)) {
            throw new IllegalArgumentException(
                    "SKILL.md not found in skill directory: " + skillDir);
        }

        try {
            String skillMdContent = Files.readString(skillFile, StandardCharsets.UTF_8);
            Map<String, String> resources =
                    includeResources ? loadResources(skillDir, skillFile) : null;
            AgentSkill base = SkillUtil.createFrom(skillMdContent, resources, source);
            return base.toBuilder().originDir(skillDir.toAbsolutePath().normalize()).build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load skill from: " + skillDir, e);
        }
    }

    /**
     * 解析每个技能目录中的 SKILL.md 元数据，获取全部技能名称。
     *
     * @param baseDir 包含技能目录的基础目录
     * @return 按字母顺序排序的技能名称列表
     */
    public static List<String> getAllSkillNames(Path baseDir) {
        List<String> skillNames = new ArrayList<>();

        try (Stream<Path> subdirs = Files.list(baseDir)) {
            subdirs.filter(Files::isDirectory)
                    .forEach(
                            dir -> {
                                if (hasSkillFile(dir)) {
                                    readSkillName(dir).ifPresent(skillNames::add);
                                }
                            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to list skill directories", e);
        }

        skillNames.sort(String::compareTo);
        return skillNames;
    }

    /**
     * 获取基础目录中的全部技能，并将资源预加载到内存。
     * 等同于 {@link #getAllSkills(Path, String, boolean) getAllSkills(baseDir, source,
     *  true)}。
     */
    public static List<AgentSkill> getAllSkills(Path baseDir, String source) {
        return getAllSkills(baseDir, source, true);
    }

    /**
     * 获取基础目录中的全部技能。
     *
     * @param baseDir 包含技能目录的基础目录
     * @param source 为所创建技能设置的来源标识符
     * @param includeResources 为 {@code false} 时，技能资源映射保持为空，
     *      调用方必须依靠技能的 {@code originDir} 读取磁盘内容
     * @return 技能列表
     */
    public static List<AgentSkill> getAllSkills(
            Path baseDir, String source, boolean includeResources) {
        List<AgentSkill> skills = new ArrayList<>();

        try (Stream<Path> subdirs = Files.list(baseDir)) {
            subdirs.filter(Files::isDirectory)
                    .forEach(
                            dir -> {
                                if (hasSkillFile(dir)) {
                                    try {
                                        skills.add(
                                                loadSkillFromDirectory(
                                                        dir, source, includeResources));
                                    } catch (Exception e) {
                                        logger.warn(
                                                "Failed to load skill from '{}': {}",
                                                dir,
                                                e.getMessage(),
                                                e);
                                    }
                                }
                            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to list skill directories", e);
        }

        return skills;
    }

    /**
     * 将技能保存到基础目录。
     *
     * @param baseDir 保存技能的基础目录
     * @param skills 要保存的技能
     * @param force 是否覆盖已存在的技能
     * @return 全部技能均保存成功时为 true，否则为 false
     */
    public static boolean saveSkills(Path baseDir, List<AgentSkill> skills, boolean force) {
        if (skills == null || skills.isEmpty()) {
            return false;
        }

        int size = skills.size();
        int saveCount = 0;
        try {
            for (AgentSkill skill : skills) {
                String skillName = skill.getName();
                Path skillDir = validateAndResolvePath(baseDir, skillName);

                if (Files.exists(skillDir)) {
                    if (!force) {
                        logger.info(
                                "Skill directory already exists and force=false: {}", skillName);
                        continue; // force=false 时跳过并处理下一个技能
                    } else {
                        logger.info("Overwriting existing skill directory: {}", skillName);
                        deleteDirectory(skillDir);
                    }
                }

                Files.createDirectories(skillDir);

                String skillMdContent =
                        MarkdownSkillParser.generate(skill.getMetadata(), skill.getSkillContent());

                Path skillFile = skillDir.resolve(SKILL_FILE_NAME);
                Files.writeString(skillFile, skillMdContent, StandardCharsets.UTF_8);

                Map<String, String> resources = skill.getResources();
                for (Map.Entry<String, String> entry : resources.entrySet()) {
                    Path resourceFile = skillDir.resolve(entry.getKey());
                    Files.createDirectories(resourceFile.getParent());
                    String content = entry.getValue();
                    if (content != null && content.startsWith("base64:")) {
                        String base64 = content.substring("base64:".length());
                        byte[] bytes = Base64.getDecoder().decode(base64);
                        Files.write(resourceFile, bytes);
                    } else {
                        Files.writeString(resourceFile, content, StandardCharsets.UTF_8);
                    }
                }

                saveCount++;
                logger.info("Successfully saved skill: {}", skillName);
            }
            boolean allSaved = (size == saveCount);
            if (!allSaved) {
                logger.warn("Not all skills were saved. Saved {} of {}", saveCount, size);
            }

            return allSaved;
        } catch (IOException e) {
            logger.error("Failed to save skills", e);
            throw new RuntimeException("Failed to save skills", e);
        }
    }

    /**
     * 根据 SKILL.md 元数据中的技能名称删除技能。
     *
     * @param baseDir 包含技能目录的基础目录
     * @param skillName 要删除的技能名称
     * @return 删除成功为 true，未找到为 false
     */
    public static boolean deleteSkill(Path baseDir, String skillName) {
        Optional<Path> skillDir = findSkillDirectoryByName(baseDir, skillName);
        if (skillDir.isEmpty()) {
            logger.warn("Skill directory does not exist: {}", skillName);
            return false;
        }

        try {
            deleteDirectory(skillDir.get());
            logger.info("Successfully deleted skill: {}", skillName);
            return true;
        } catch (IOException e) {
            logger.error("Failed to delete skill: {}", skillName, e);
            throw new RuntimeException("Failed to delete skill: " + skillName, e);
        }
    }

    /**
     * 扫描 SKILL.md 元数据，检查技能是否存在。
     *
     * @param baseDir 包含技能目录的基础目录
     * @param skillName 要检查的技能名称
     * @return 存在匹配技能时为 true
     */
    public static boolean skillExists(Path baseDir, String skillName) {
        if (skillName == null || skillName.isEmpty()) {
            return false;
        }
        return findSkillDirectoryByName(baseDir, skillName).isPresent();
    }

    /**
     * 验证解析出的路径是否位于基础目录内。
     *
     * @param baseDir 用于约束路径解析的基础目录
     * @param skillName 要解析的技能名称
     * @return 验证后的绝对路径
     * @throws IllegalArgumentException 路径逃逸出基础目录时抛出
     */
    public static Path validateAndResolvePath(Path baseDir, String skillName) {
        if (skillName == null || skillName.isEmpty()) {
            throw new IllegalArgumentException("Skill name cannot be null or empty");
        }

        Path absoluteBaseDir = baseDir.toAbsolutePath().normalize();
        Path resolvedPath = absoluteBaseDir.resolve(skillName).normalize();

        if (!resolvedPath.startsWith(absoluteBaseDir)) {
            throw new IllegalArgumentException(
                    "Invalid skill name: path traversal detected. Skill name '"
                            + skillName
                            + "' would escape base directory");
        }

        return resolvedPath;
    }

    /**
     * 检查目录是否包含 SKILL.md 文件。
     *
     * @param dir 要检查的目录
     * @return 目录中存在 SKILL.md 时为 true
     */
    public static boolean hasSkillFile(Path dir) {
        try (Stream<Path> files = Files.walk(dir, 1)) {
            return files.anyMatch(p -> p.getFileName().toString().equals(SKILL_FILE_NAME));
        } catch (IOException e) {
            logger.warn("Failed to check for SKILL.md in directory: {}", dir, e);
            return false;
        }
    }

    /**
     * 递归删除目录及其全部内容。
     *
     * @param directory 要删除的目录
     * @throws IOException 删除失败时抛出
     */
    public static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    logger.error("Failed to delete: {}", path, e);
                                    throw new RuntimeException("Failed to delete: " + path, e);
                                }
                            });
        }
    }

    /**
     * 注册 JVM 关闭钩子，在退出时删除指定目录。
     *
     * @param directory 关闭时要删除的目录
     */
    public static Thread registerTempDirectoryCleanup(Path directory) {
        Thread hook =
                new Thread(
                        () -> {
                            try {
                                deleteDirectory(directory);
                                logger.debug(
                                        "Cleaned up temporary directory on shutdown: {}",
                                        directory);
                            } catch (Exception e) {
                                logger.warn(
                                        "Failed to cleanup temp directory on shutdown: {}",
                                        directory,
                                        e);
                            }
                        });
        Runtime.getRuntime().addShutdownHook(hook);
        return hook;
    }

    private static Map<String, String> loadResources(Path skillDir, Path skillFile) {
        Map<String, String> resources = new HashMap<>();
        // 从根目录开始递归遍历
        collectResources(skillDir, skillDir, skillFile, resources);
        return resources;
    }

    private static void collectResources(
            Path currentDir, Path skillDir, Path skillFile, Map<String, String> resources) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(currentDir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    if (!entry.getFileName().toString().startsWith(".")) {
                        collectResources(entry, skillDir, skillFile, resources);
                    }
                } else {
                    if (isValidResource(entry, skillFile)) {
                        readAndPutResource(entry, skillDir, resources);
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to open directory: {}", currentDir, e);
        }
    }

    private static void readAndPutResource(
            Path file, Path skillDir, Map<String, String> resources) {
        String relativePath = skillDir.relativize(file).toString().replace('\\', '/');
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            resources.put(relativePath, content);
        } catch (MalformedInputException e) {
            try {
                byte[] bytes = Files.readAllBytes(file);
                String base64 = Base64.getEncoder().encodeToString(bytes);
                resources.put(relativePath, "base64:" + base64);
            } catch (IOException ex) {
                logger.warn("Failed to read binary resource file: {}", file, ex);
            }
        } catch (IOException e) {
            logger.warn("Failed to read resource file: {}", file, e);
        }
    }

    private static boolean isValidResource(Path file, Path skillFile) {
        if (file.equals(skillFile)) {
            return false;
        }

        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            return false;
        }

        if (file.getFileName().toString().startsWith(".")) {
            return false;
        }

        try {
            return !Files.isHidden(file);
        } catch (IOException e) {
            logger.warn("Failed to check attributes for file: {}", file, e);
            return true;
        }
    }

    private static Optional<Path> findSkillDirectoryByName(Path baseDir, String skillName) {
        if (skillName == null || skillName.isEmpty()) {
            return Optional.empty();
        }

        try (Stream<Path> subdirs = Files.list(baseDir)) {
            return subdirs.filter(Files::isDirectory)
                    .filter(SkillFileSystemHelper::hasSkillFile)
                    .filter(dir -> skillName.equals(readSkillName(dir).orElse(null)))
                    .findFirst();
        } catch (IOException e) {
            throw new RuntimeException("Failed to list skill directories", e);
        }
    }

    private static Optional<String> readSkillName(Path skillDir) {
        Path skillFile = skillDir.resolve(SKILL_FILE_NAME);
        if (!Files.exists(skillFile)) {
            return Optional.empty();
        }

        try {
            String skillMdContent = Files.readString(skillFile, StandardCharsets.UTF_8);
            ParsedMarkdown parsed = MarkdownSkillParser.parse(skillMdContent);
            Map<String, Object> metadata = parsed.getMetadata();
            Object nameObject = metadata.get("name");
            String name = nameObject != null ? String.valueOf(nameObject) : null;
            if (name == null || name.isEmpty()) {
                logger.warn("Missing skill name in SKILL.md: {}", skillFile);
                return Optional.empty();
            }
            return Optional.of(name);
        } catch (Exception e) {
            logger.warn("Failed to parse SKILL.md: {}", skillFile, e);
            return Optional.empty();
        }
    }
}
